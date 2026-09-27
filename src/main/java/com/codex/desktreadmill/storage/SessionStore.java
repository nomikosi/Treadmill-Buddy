package com.codex.desktreadmill.storage;

import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Durable session history shared across all JetBrains IDEs. Sessions live in
 * {@code ~/.treadmill-buddy/sessions.json} instead of per-IDE settings, so a
 * walk logged in IntelliJ also counts in Rider and survives IDE reinstalls.
 *
 * <p>The file is the source of truth; this store holds the last state it
 * synced plus its own pending changes. Every write takes a cross-process lock
 * on a sidecar file, re-reads the history, folds it in
 * - the disk copy wins for every session except the ones changed here since
 * the last successful write, and a session that vanished from the file was
 * deleted in another IDE and is dropped here too - then stages the result in
 * a uniquely named temp file and moves it into place. Timestamps only avoid
 * unnecessary focus refreshes; they never decide whether a write merges.</p>
 *
 * <p>A file that exists but cannot be parsed is never treated as an empty
 * history: merges leave memory alone, and the next write moves the corrupt
 * file aside instead of overwriting it. Write failures are remembered and
 * reported through the optional {@link #onWriteFailure} callback, because a
 * store that silently keeps everything in memory looks healthy right up
 * until the IDE closes.</p>
 *
 * <p>A lock held by another IDE is not a failure of that kind: its write
 * takes milliseconds and the retry follows a second later, so contention is
 * reported only once it outlasts several retries. Closing is the one write
 * that waits for the lock, briefly - it is the last chance for pending
 * changes. With an executor, {@link #saveSessionLater} moves routine writes
 * such as autosaves off the calling thread.</p>
 *
 * <p>Threading: the object monitor guards the in-memory history and is only
 * ever held briefly, never across file I/O, so reads answer at once even
 * while a background write is busy with the disk. Writers take
 * {@link #writeLock} first - never while holding the monitor - so they run
 * one at a time. A write carries a snapshot of the history; every save and
 * delete is numbered, and a finished write clears only the changes it
 * carried, so one staged while it ran stays pending for the next.</p>
 */
public final class SessionStore implements AutoCloseable {
    private static final Logger LOG = Logger.getInstance(SessionStore.class);
    private static final long RETRY_DELAY_MILLIS = 1_000L;
    /** Failed attempts in a row (about 15 s of backoff) after which a busy lock is worth a warning. */
    private static final int LOCK_FAILURES_BEFORE_WARNING = 5;
    private static final long CLOSE_LOCK_WAIT_MILLIS = 2_000L;
    private static final long LOCK_POLL_MILLIS = 50L;
    private static final DateTimeFormatter CORRUPT_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Type SESSION_LIST = new TypeToken<List<SessionData>>() {
    }.getType();

    /** Why a write did not reach disk. */
    public enum WriteFailure {
        /** The file or its folder cannot be written: disk full, permissions, a file where the folder belongs. */
        IO_ERROR,
        /** Another writer kept the history lock through several retries. */
        LOCK_BUSY
    }

    private final Path file;
    private final Path lockFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** One writer at a time in this process. Taken before the monitor, never while holding it. */
    private final ReentrantLock writeLock = new ReentrantLock();
    private final @Nullable ScheduledExecutorService retryExecutor;

    // Everything below is guarded by the object monitor.
    /** The stored copies are never modified in place, so a shallow copy of this list is a snapshot. */
    private final List<SessionData> sessions = new ArrayList<>();
    /** Ids saved here that no successful write has carried to disk yet, with the change that staged them. */
    private final Map<String, Long> dirtyIds = new HashMap<>();
    /** Ids deleted here that no successful write has removed from disk yet, with the deleting change. */
    private final Map<String, Long> deletedIds = new HashMap<>();
    /** Numbers every staged save and delete. */
    private long changeCount;
    /** Counts successful writes, so a reload that raced one throws its older read away. */
    private long writeCount;
    private boolean loaded;
    /** Disk state we last saw, so unchanged files aren't re-parsed on every focus change. */
    private @Nullable FileTime lastSyncedTime;
    private long lastSyncedSize = -1L;
    /** The file exists but could not be parsed the last time it was read. */
    private boolean diskCorrupt;
    private @Nullable Consumer<WriteFailure> writeFailureCallback;
    /** The queued background write: an immediate one for {@link #saveSessionLater}, or a retry. */
    private @Nullable ScheduledFuture<?> pendingWrite;
    /** Bumped whenever the queued write is scheduled or dropped, so a superseded task stands down. */
    private long writeGeneration;
    private long retryDelayMillis = RETRY_DELAY_MILLIS;
    private int consecutiveLockFailures;
    private long closeLockWaitMillis = CLOSE_LOCK_WAIT_MILLIS;
    /** Callers of {@link #saveSessionLater} waiting for the write that carries their change. */
    private final List<PendingResult> pendingResults = new ArrayList<>();
    private boolean closed;

    private record PendingResult(long change, CompletableFuture<Boolean> future) {
    }

    /** What one look at the file found; {@code sessions} is null when there is no readable history. */
    private record DiskRead(@Nullable FileTime time, long size, @Nullable List<SessionData> sessions, boolean corrupt) {
    }

    public SessionStore(Path file) {
        this(file, null);
    }

    /** The optional retry executor is owned by this store and shut down on close. */
    public SessionStore(Path file, @Nullable ScheduledExecutorService retryExecutor) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
        this.retryExecutor = retryExecutor;
    }

    /**
     * Invoked on the writing thread when a write doesn't reach disk: at once
     * for an I/O error, and for a busy lock only when it keeps failing.
     */
    public synchronized void onWriteFailure(Consumer<WriteFailure> callback) {
        writeFailureCallback = callback;
    }

    /** Test hook: how long {@link #close} waits for another writer's lock. */
    synchronized void setCloseLockWaitMillis(long millis) {
        closeLockWaitMillis = millis;
    }

    public synchronized List<SessionData> getSessions() {
        ensureLoaded();
        List<SessionData> copies = new ArrayList<>();
        for (SessionData session : sessions) {
            copies.add(session.copy());
        }
        return copies;
    }

    public synchronized @Nullable SessionData findSession(String id) {
        ensureLoaded();
        for (SessionData session : sessions) {
            if (id.equals(session.id)) {
                return session.copy();
            }
        }
        return null;
    }

    /** Returns whether the session reached disk; on false it is kept in memory and retried with the next write. */
    public boolean saveSession(SessionData session) {
        synchronized (this) {
            ensureLoaded();
            putInMemory(session);
        }
        return write(0L);
    }

    /**
     * Takes the session into memory at once and writes it on the executor, so
     * a periodic autosave never makes the caller wait for disk. The future
     * says whether the write that carries it reached disk; it completes with
     * false straight away while a retry is already scheduled, which will carry
     * this change too. Without an executor the write happens before this returns.
     */
    public CompletableFuture<Boolean> saveSessionLater(SessionData session) {
        synchronized (this) {
            ensureLoaded();
            long change = putInMemory(session);
            if (retryExecutor != null && !closed) {
                if (pendingWrite != null && pendingWrite.getDelay(TimeUnit.MILLISECONDS) > 0) {
                    return CompletableFuture.completedFuture(false);
                }
                CompletableFuture<Boolean> result = new CompletableFuture<>();
                pendingResults.add(new PendingResult(change, result));
                if (pendingWrite == null) {
                    scheduleWrite(0L);
                }
                return result;
            }
        }
        return CompletableFuture.completedFuture(write(0L));
    }

    /**
     * Bulk save with a single file write. Imports go through this: saving each
     * of N rows individually would rewrite the whole file N times.
     */
    public boolean saveSessions(List<SessionData> toSave) {
        if (toSave.isEmpty()) {
            return true;
        }
        synchronized (this) {
            ensureLoaded();
            for (SessionData session : toSave) {
                putInMemory(session);
            }
        }
        return write(0L);
    }

    /** Stages a save and returns its change number. Caller holds the monitor. */
    private long putInMemory(SessionData session) {
        // Sanitized here, at the one entrance to the file: a NaN that slipped
        // past the UI would otherwise make Gson refuse every write from now on.
        SessionData copy = session.copy().sanitize();
        long change = ++changeCount;
        deletedIds.remove(copy.id);
        dirtyIds.put(copy.id, change);
        int index = indexOf(copy.id);
        if (index >= 0) {
            sessions.set(index, copy);
        } else {
            sessions.add(copy);
        }
        return change;
    }

    public boolean deleteSession(String id) {
        return deleteSessions(List.of(id));
    }

    /** Bulk delete with a single file write (used by "delete older than" cleanup). */
    public boolean deleteSessions(Collection<String> ids) {
        if (ids.isEmpty()) {
            return true;
        }
        synchronized (this) {
            ensureLoaded();
            sessions.removeIf(session -> ids.contains(session.id));
            long change = ++changeCount;
            for (String id : ids) {
                dirtyIds.remove(id);
                deletedIds.put(id, change);
            }
        }
        return write(0L);
    }

    /**
     * Picks up what another IDE instance wrote since our last sync: new
     * sessions are added, known sessions are refreshed from the file, and
     * sessions deleted there disappear here. Cheap when nothing changed - the
     * file is only re-read when its modification time or size moved, which
     * matters because this runs on every IDE focus change. Returns whether
     * the file had changed.
     */
    public boolean reload() {
        long writesBefore;
        synchronized (this) {
            if (!loaded) {
                return false; // Nothing cached; the next access reads the file fresh anyway.
            }
            if (!diskChangedSinceLastSync()) {
                return false;
            }
            writesBefore = writeCount;
        }
        DiskRead read = readDisk();
        synchronized (this) {
            // A write since the check merged the file itself, and this read
            // may predate it; folding it in now could only roll memory back.
            if (writeCount == writesBefore) {
                applyDiskRead(read);
            }
        }
        return true;
    }

    /**
     * Adds sessions from the legacy per-IDE storage that the file doesn't know
     * yet. Returns whether every legacy session is on disk: the caller must
     * NOT drop its legacy copy on a false return, or an unwritable home
     * directory silently destroys the user's whole pre-migration history. A
     * repeated attempt after a failed one keeps returning false until the
     * write actually succeeds - the sessions being already in memory from the
     * first attempt is not the same as being on disk.
     */
    public boolean migrate(List<SessionData> legacySessions) {
        boolean pending = false;
        synchronized (this) {
            ensureLoaded();
            for (SessionData session : legacySessions) {
                if (session.id == null || session.id.isBlank()) {
                    continue;
                }
                if (indexOf(session.id) < 0) {
                    putInMemory(session);
                }
                pending |= dirtyIds.containsKey(session.id);
            }
        }
        return !pending || write(0L);
    }

    private int indexOf(String id) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** First access reads the file; the only disk read done while holding the monitor. */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        DiskRead read = readDisk();
        lastSyncedTime = read.time();
        lastSyncedSize = read.size();
        diskCorrupt = read.corrupt();
        if (read.sessions() != null) {
            sessions.addAll(read.sessions());
        }
    }

    /**
     * Folds a read of the file into memory. The disk copy replaces ours for
     * every id except those changed here since the last successful write;
     * sessions we knew from disk that are no longer in the file were deleted
     * elsewhere and go too. A missing or unparseable file changes nothing -
     * reading "nothing" as "everything was deleted" would turn one bad read
     * into a wiped history on the next write. Caller holds the monitor.
     */
    private void applyDiskRead(DiskRead read) {
        lastSyncedTime = read.time();
        lastSyncedSize = read.size();
        diskCorrupt = read.corrupt();
        List<SessionData> onDisk = read.sessions();
        if (onDisk == null) {
            return;
        }
        Set<String> diskIds = new HashSet<>();
        for (SessionData session : onDisk) {
            diskIds.add(session.id);
            if (deletedIds.containsKey(session.id) || dirtyIds.containsKey(session.id)) {
                continue;
            }
            int index = indexOf(session.id);
            if (index >= 0) {
                sessions.set(index, session);
            } else {
                sessions.add(session);
            }
        }
        sessions.removeIf(session -> !diskIds.contains(session.id) && !dirtyIds.containsKey(session.id));
    }

    /** True when the file on disk differs from the state this store last read or wrote. */
    private boolean diskChangedSinceLastSync() {
        try {
            return !Files.getLastModifiedTime(file).equals(lastSyncedTime)
                    || Files.size(file) != lastSyncedSize;
        } catch (IOException fileGoneOrUnreadable) {
            return lastSyncedTime != null;
        }
    }

    /**
     * Reads the file without touching this store's state. Metadata comes
     * first: a replacement during the read must leave this snapshot eligible
     * for another refresh, not label old bytes as new. Duplicate ids collapse
     * to the last occurrence, so a hand-merged file cannot leave two rows
     * fighting over one id forever.
     */
    private DiskRead readDisk() {
        FileTime time;
        long size;
        try {
            time = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (IOException missing) {
            time = null;
            size = -1L;
        }
        if (!Files.exists(file)) {
            return new DiskRead(time, size, null, false);
        }
        try {
            List<SessionData> read = gson.fromJson(Files.readString(file), SESSION_LIST);
            if (read == null) {
                return new DiskRead(time, size, List.of(), false);
            }
            Map<String, SessionData> byId = new LinkedHashMap<>();
            for (SessionData session : read) {
                if (session != null && session.id != null && !session.id.isBlank()) {
                    byId.put(session.id, session.sanitize());
                }
            }
            return new DiskRead(time, size, new ArrayList<>(byId.values()), false);
        } catch (IOException | JsonSyntaxException exception) {
            // A corrupt or unreadable file must not take the plugin down; keep
            // working from memory, and keep the file - see preserveCorruptFile.
            LOG.warn("Could not read " + file + "; continuing with in-memory sessions", exception);
            return new DiskRead(time, size, null, true);
        }
    }

    /** One write attempt, waiting up to {@code lockWaitMillis} for another IDE's lock (0: not at all). */
    private boolean write(long lockWaitMillis) {
        writeLock.lock();
        try {
            return writeHoldingWriteLock(lockWaitMillis);
        } finally {
            writeLock.unlock();
        }
    }

    private boolean writeHoldingWriteLock(long lockWaitMillis) {
        List<PendingResult> carried = null;
        synchronized (this) {
            if (dirtyIds.isEmpty() && deletedIds.isEmpty()) {
                // An earlier write already carried everything, this caller's change included.
                carried = takeResultsUpTo(Long.MAX_VALUE);
            }
        }
        if (carried != null) {
            complete(carried, true);
            return true;
        }
        try {
            Files.createDirectories(file.getParent());
            try (ProcessLock ignored = acquireLock(lockWaitMillis)) {
                // A cached timestamp/size cannot prove our bytes are current:
                // another writer can replace the file during an unlocked read,
                // or replace it with the same size on a coarse filesystem clock.
                DiskRead read = readDisk();
                List<SessionData> snapshot;
                Map<String, Long> writtenDirty;
                Map<String, Long> writtenDeleted;
                long writtenChange;
                boolean corrupt;
                synchronized (this) {
                    applyDiskRead(read);
                    corrupt = diskCorrupt;
                    snapshot = new ArrayList<>(sessions);
                    writtenDirty = new HashMap<>(dirtyIds);
                    writtenDeleted = new HashMap<>(deletedIds);
                    writtenChange = changeCount;
                }
                if (corrupt) {
                    preserveCorruptFile();
                }
                Path temp = stagingFile();
                try {
                    Files.writeString(temp, gson.toJson(snapshot));
                    moveIntoPlace(temp);
                } finally {
                    discardQuietly(temp);
                }
                FileTime writtenTime = lastModified();
                long writtenSize = sizeOrMinusOne();
                synchronized (this) {
                    lastSyncedTime = writtenTime;
                    lastSyncedSize = writtenSize;
                    // Only what this write carried is on disk now; a change
                    // staged while it ran keeps its newer number and stays pending.
                    writtenDirty.forEach(dirtyIds::remove);
                    writtenDeleted.forEach(deletedIds::remove);
                    diskCorrupt = false;
                    writeCount++;
                    retryDelayMillis = RETRY_DELAY_MILLIS;
                    consecutiveLockFailures = 0;
                    carried = takeResultsUpTo(writtenChange);
                    if (dirtyIds.isEmpty() && deletedIds.isEmpty()) {
                        cancelPendingWrite();
                    } else {
                        scheduleFollowUp();
                    }
                }
            }
            complete(carried, true);
            return true;
        } catch (LockUnavailableException busy) {
            // Usually another IDE in the middle of its own few-millisecond
            // write; the retry picks the changes up a second later.
            LOG.info("History lock unavailable; retrying: " + busy.getMessage());
            boolean warn;
            synchronized (this) {
                scheduleRetry();
                warn = ++consecutiveLockFailures == LOCK_FAILURES_BEFORE_WARNING;
                carried = takeResultsUpTo(Long.MAX_VALUE);
            }
            if (warn) {
                reportFailure(WriteFailure.LOCK_BUSY);
            }
            complete(carried, false);
            return false;
        } catch (IOException exception) {
            // Sessions stay in memory and the next successful write persists
            // them - but the user has to hear about it, because "healthy until
            // the IDE closes, then everything since the failure is gone" is
            // the worst possible way to find out.
            LOG.warn("Could not write " + file + "; sessions are only in memory", exception);
            synchronized (this) {
                scheduleRetry();
                carried = takeResultsUpTo(Long.MAX_VALUE);
            }
            reportFailure(WriteFailure.IO_ERROR);
            complete(carried, false);
            return false;
        }
    }

    private @Nullable FileTime lastModified() {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException gone) {
            return null;
        }
    }

    private long sizeOrMinusOne() {
        try {
            return Files.size(file);
        } catch (IOException gone) {
            return -1L;
        }
    }

    private void reportFailure(WriteFailure failure) {
        Consumer<WriteFailure> callback;
        synchronized (this) {
            callback = writeFailureCallback;
        }
        if (callback != null) {
            callback.accept(failure);
        }
    }

    /**
     * Removes the waiting callers whose change is at most {@code change}.
     * Caller holds the monitor, and completes them after releasing it, so
     * their callbacks never run under it.
     */
    private List<PendingResult> takeResultsUpTo(long change) {
        List<PendingResult> taken = new ArrayList<>();
        for (Iterator<PendingResult> it = pendingResults.iterator(); it.hasNext(); ) {
            PendingResult result = it.next();
            if (result.change() <= change) {
                taken.add(result);
                it.remove();
            }
        }
        return taken;
    }

    private static void complete(List<PendingResult> results, boolean persisted) {
        for (PendingResult result : results) {
            result.future().complete(persisted);
        }
    }

    /**
     * Never writes without the lock. Routine writes don't wait for it at all -
     * the EDT must not stall on another IDE - and leave the changes pending;
     * {@code waitMillis} lets the final write on close outlast a short one.
     */
    private ProcessLock acquireLock(long waitMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            try {
                return tryLock();
            } catch (LockUnavailableException busy) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                    throw busy;
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
        }
    }

    private ProcessLock tryLock() throws IOException {
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new LockUnavailableException("Interrupted before locking " + lockFile, null);
            }
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new LockUnavailableException("History lock is held by another writer: " + lockFile, null);
            }
            return new ProcessLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw new LockUnavailableException("History lock is held by another writer: " + lockFile, exception);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel);
            throw exception;
        }
    }

    /** Retries all pending saves and deletions, including changes to a session no longer on the clock. */
    public boolean flushPendingWrites() {
        return write(0L);
    }

    /** Caller holds the monitor. */
    private void scheduleRetry() {
        if (retryExecutor == null || closed || pendingWrite != null) {
            return;
        }
        scheduleWrite(retryDelayMillis);
        retryDelayMillis = Math.min(60_000L, retryDelayMillis * 2);
    }

    /** Changes staged during a successful write go out right after it. Caller holds the monitor. */
    private void scheduleFollowUp() {
        if (retryExecutor == null || closed) {
            return;
        }
        if (pendingWrite != null && pendingWrite.getDelay(TimeUnit.MILLISECONDS) > 0) {
            cancelPendingWrite();
        }
        if (pendingWrite == null) {
            scheduleWrite(0L);
        }
    }

    /** Caller holds the monitor. */
    private void scheduleWrite(long delayMillis) {
        long generation = ++writeGeneration;
        pendingWrite = retryExecutor.schedule(() -> writeInBackground(generation), delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Caller holds the monitor. */
    private void cancelPendingWrite() {
        writeGeneration++;
        if (pendingWrite != null) {
            pendingWrite.cancel(false);
            pendingWrite = null;
        }
    }

    private void writeInBackground(long generation) {
        synchronized (this) {
            // A task that already started can't be cancelled; another write may
            // have done its job or taken its place while it waited.
            if (generation != writeGeneration || closed) {
                return;
            }
            pendingWrite = null;
        }
        write(0L);
    }

    @Override
    public void close() {
        long waitMillis;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancelPendingWrite();
            waitMillis = closeLockWaitMillis;
        }
        // The last chance for pending changes: give a writer in another IDE
        // a moment to finish instead of dropping them at the first try.
        boolean persisted = write(waitMillis);
        List<PendingResult> rest;
        synchronized (this) {
            rest = takeResultsUpTo(Long.MAX_VALUE);
        }
        complete(rest, persisted);
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
    }

    /** The lock is held elsewhere, or the writer was interrupted: transient, unlike a disk error. */
    private static final class LockUnavailableException extends IOException {
        private LockUnavailableException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ProcessLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private ProcessLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below drops the lock anyway.
            }
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(@Nullable FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing left to do with it.
        }
    }

    /**
     * Moves an unparseable history file aside before the store writes a fresh
     * one, so a bad byte never costs the user their walks: what this store
     * holds in memory is only what it managed to read plus its own changes.
     * Failing to move it fails the write - overwriting is not an option.
     */
    private void preserveCorruptFile() throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(CORRUPT_SUFFIX));
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        LOG.warn("Moved unreadable history " + file + " to " + aside);
    }

    /**
     * Per-process, per-write temp name: two IDEs sharing one
     * "sessions.json.tmp" would overwrite each other's staging file and one
     * of the moves would fail or install the other's content.
     */
    private Path stagingFile() {
        return file.resolveSibling(file.getFileName() + "."
                + ProcessHandle.current().pid() + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".tmp");
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a staging file that a failed write left behind; a no-op after a successful move. */
    private static void discardQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // The original failure is what gets reported; a stray temp file is harmless.
        }
    }
}
