package com.codex.desktreadmill.settings;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable session history shared across all JetBrains IDEs. Sessions live in
 * {@code ~/.treadmill-buddy/sessions.json} instead of per-IDE settings, so a
 * walk logged in IntelliJ also counts in Rider and survives IDE reinstalls.
 *
 * <p>The file is the source of truth; this store holds the last state it
 * synced plus its own pending changes. Every write takes a cross-process lock
 * on a sidecar file, re-reads the history if it changed on disk, folds it in
 * - the disk copy wins for every session except the ones changed here since
 * the last successful write, and a session that vanished from the file was
 * deleted in another IDE and is dropped here too - then stages the result in
 * a uniquely named temp file and moves it into place. The re-read is skipped
 * when modification time and size are unchanged, so the routine 30-second
 * autosave doesn't re-parse the whole history on the EDT.</p>
 *
 * <p>A file that exists but cannot be parsed is never treated as an empty
 * history: merges leave memory alone, and the next write moves the corrupt
 * file aside instead of overwriting it. Write failures are remembered and
 * reported through the optional {@link #onWriteFailure} callback, because a
 * store that silently keeps everything in memory looks healthy right up
 * until the IDE closes.</p>
 */
public final class SessionStore {
    private static final Logger LOG = Logger.getInstance(SessionStore.class);
    /** How long a write waits for another IDE's write to finish before going ahead without the lock. */
    private static final long LOCK_WAIT_MILLIS = 1_500L;
    private static final long LOCK_RETRY_MILLIS = 20L;
    private static final DateTimeFormatter CORRUPT_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final Path lockFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<SessionData> sessions = new ArrayList<>();
    /** Ids saved here that no successful write has carried to disk yet; they outrank the disk copy. */
    private final Set<String> dirtyIds = new HashSet<>();
    /** Ids deleted here that no successful write has removed from disk yet. */
    private final Set<String> deletedIds = new HashSet<>();
    private boolean loaded;
    /** Disk state we last saw, so unchanged files aren't re-parsed before each write. */
    private @Nullable FileTime lastSyncedTime;
    private long lastSyncedSize = -1L;
    /** The file exists but could not be parsed the last time it was read. */
    private boolean diskCorrupt;
    private @Nullable Runnable writeFailureCallback;

    public SessionStore(Path file) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
    }

    /** Invoked (once per failure, on the caller's thread) when a write doesn't reach disk. */
    public synchronized void onWriteFailure(Runnable callback) {
        writeFailureCallback = callback;
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
    public synchronized boolean saveSession(SessionData session) {
        ensureLoaded();
        putInMemory(session);
        return write();
    }

    /**
     * Bulk save with a single file write. Imports go through this: saving each
     * of N rows individually would rewrite the whole file N times.
     */
    public synchronized boolean saveSessions(List<SessionData> toSave) {
        if (toSave.isEmpty()) {
            return true;
        }
        ensureLoaded();
        for (SessionData session : toSave) {
            putInMemory(session);
        }
        return write();
    }

    private void putInMemory(SessionData session) {
        // Sanitized here, at the one entrance to the file: a NaN that slipped
        // past the UI would otherwise make Gson refuse every write from now on.
        SessionData copy = session.copy().sanitize();
        deletedIds.remove(copy.id);
        dirtyIds.add(copy.id);
        int index = indexOf(copy.id);
        if (index >= 0) {
            sessions.set(index, copy);
        } else {
            sessions.add(copy);
        }
    }

    public synchronized boolean deleteSession(String id) {
        ensureLoaded();
        int index = indexOf(id);
        if (index >= 0) {
            sessions.remove(index);
        }
        dirtyIds.remove(id);
        deletedIds.add(id);
        return write();
    }

    /** Bulk delete with a single file write (used by "delete older than" cleanup). */
    public synchronized boolean deleteSessions(Collection<String> ids) {
        if (ids.isEmpty()) {
            return true;
        }
        ensureLoaded();
        sessions.removeIf(session -> ids.contains(session.id));
        dirtyIds.removeAll(ids);
        deletedIds.addAll(ids);
        return write();
    }

    /**
     * Picks up what another IDE instance wrote since our last sync: new
     * sessions are added, known sessions are refreshed from the file, and
     * sessions deleted there disappear here. Cheap when nothing changed - the
     * file is only re-read when its modification time or size moved, which
     * matters because this runs on every IDE focus change. Returns whether
     * the file had changed.
     */
    public synchronized boolean reload() {
        if (!loaded) {
            return false; // Nothing cached; the next access reads the file fresh anyway.
        }
        if (!diskChangedSinceLastSync()) {
            return false;
        }
        mergeFromDisk();
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
    public synchronized boolean migrate(List<SessionData> legacySessions) {
        ensureLoaded();
        boolean pending = false;
        for (SessionData session : legacySessions) {
            if (session.id == null || session.id.isBlank()) {
                continue;
            }
            if (indexOf(session.id) < 0) {
                putInMemory(session);
            }
            pending |= dirtyIds.contains(session.id);
        }
        return !pending || write();
    }

    private int indexOf(String id) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        List<SessionData> onDisk = readFile();
        if (onDisk != null) {
            sessions.addAll(onDisk);
        }
        rememberDiskState();
    }

    /**
     * Folds the file into memory. The disk copy replaces ours for every id
     * except those changed here since the last successful write; sessions we
     * knew from disk that are no longer in the file were deleted elsewhere
     * and go too. A missing or unparseable file changes nothing - reading
     * "nothing" as "everything was deleted" would turn one bad read into a
     * wiped history on the next write.
     */
    private void mergeFromDisk() {
        List<SessionData> onDisk = readFile();
        if (onDisk != null) {
            Set<String> diskIds = new HashSet<>();
            for (SessionData session : onDisk) {
                diskIds.add(session.id);
                if (deletedIds.contains(session.id) || dirtyIds.contains(session.id)) {
                    continue;
                }
                int index = indexOf(session.id);
                if (index >= 0) {
                    sessions.set(index, session);
                } else {
                    sessions.add(session);
                }
            }
            sessions.removeIf(session -> !diskIds.contains(session.id) && !dirtyIds.contains(session.id));
        }
        rememberDiskState();
    }

    private void rememberDiskState() {
        try {
            lastSyncedTime = Files.getLastModifiedTime(file);
            lastSyncedSize = Files.size(file);
        } catch (IOException ignored) {
            lastSyncedTime = null;
            lastSyncedSize = -1L;
        }
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
     * The sessions in the file, or null when there is no readable history:
     * the file is missing, unreadable, or not valid JSON. Duplicate ids
     * collapse to the last occurrence, so a hand-merged file cannot leave two
     * rows fighting over one id forever.
     */
    private @Nullable List<SessionData> readFile() {
        if (!Files.exists(file)) {
            diskCorrupt = false;
            return null;
        }
        try {
            String json = Files.readString(file);
            Type listType = new TypeToken<List<SessionData>>() {
            }.getType();
            List<SessionData> read = gson.fromJson(json, listType);
            diskCorrupt = false;
            if (read == null) {
                return List.of();
            }
            Map<String, SessionData> byId = new LinkedHashMap<>();
            for (SessionData session : read) {
                if (session != null && session.id != null && !session.id.isBlank()) {
                    byId.put(session.id, session.sanitize());
                }
            }
            return new ArrayList<>(byId.values());
        } catch (IOException | JsonSyntaxException exception) {
            // A corrupt or unreadable file must not take the plugin down; keep
            // working from memory, and keep the file - see preserveCorruptFile.
            LOG.warn("Could not read " + file + "; continuing with in-memory sessions", exception);
            diskCorrupt = true;
            return null;
        }
    }

    private boolean write() {
        try {
            Files.createDirectories(file.getParent());
            try (ProcessLock ignored = acquireLock()) {
                // Merge what another IDE instance wrote since we last synced -
                // but only when the file actually changed, so the routine
                // autosave isn't a full read-parse of the history every 30 s.
                if (diskChangedSinceLastSync()) {
                    mergeFromDisk();
                }
                if (diskCorrupt) {
                    preserveCorruptFile();
                }
                Path temp = stagingFile();
                try {
                    Files.writeString(temp, gson.toJson(sessions));
                    moveIntoPlace(temp);
                } finally {
                    discardQuietly(temp);
                }
                rememberDiskState();
            }
            dirtyIds.clear();
            deletedIds.clear();
            diskCorrupt = false;
            return true;
        } catch (IOException exception) {
            // Sessions stay in memory and the next successful write persists
            // them - but the user has to hear about it, because "healthy until
            // the IDE closes, then everything since the failure is gone" is
            // the worst possible way to find out.
            LOG.warn("Could not write " + file + "; sessions are only in memory", exception);
            if (writeFailureCallback != null) {
                writeFailureCallback.run();
            }
            return false;
        }
    }

    /**
     * Two IDEs saving at the same moment would each read, merge, and write
     * their own view; the lock serialises them so the second one sees the
     * first one's result. Held for the few milliseconds of a write, and given
     * up after a bounded wait rather than freezing the EDT behind a stuck
     * process - an unlocked write still merges, it just reopens the tiny
     * race window the lock closes.
     */
    private @Nullable ProcessLock acquireLock() {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
        while (true) {
            FileChannel channel = null;
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return new ProcessLock(channel, lock);
                }
            } catch (OverlappingFileLockException heldElsewhereInThisJvm) {
                // Another store instance in this process is mid-write; wait like for another process.
            } catch (IOException exception) {
                LOG.warn("Could not lock " + lockFile + "; writing without the cross-process lock", exception);
                closeQuietly(channel);
                return null;
            }
            closeQuietly(channel);
            if (System.currentTimeMillis() >= deadline) {
                LOG.warn("Gave up waiting for " + lockFile + "; writing without the cross-process lock");
                return null;
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
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
