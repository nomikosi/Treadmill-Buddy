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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable session history shared across all JetBrains IDEs. Sessions live in
 * {@code ~/.treadmill-buddy/sessions.json} instead of per-IDE settings, so a
 * walk logged in IntelliJ also counts in Rider and survives IDE reinstalls.
 *
 * <p>Writes are atomic (uniquely named temp file + move, so two IDEs saving at
 * the same moment never share a temp file). Before a write, the file is
 * re-read when another IDE changed it since our last sync (checked by
 * modification time and size, so the routine 30-second autosave doesn't
 * re-parse the whole history on the EDT every time) and merged in by id: the
 * disk copy wins for every session except the ones being saved right now, so
 * a session resumed and extended in a second IDE keeps its newer state here
 * instead of being reverted to a stale copy. Ids deleted here stay deleted
 * for the life of this store; a delete made in another IDE is not propagated
 * (there are no tombstones in the file), which is a known limitation.</p>
 *
 * <p>Write failures are remembered and reported through the optional
 * {@link #onWriteFailure} callback, because a store that silently keeps
 * everything in memory looks healthy right up until the IDE closes.</p>
 */
public final class SessionStore {
    private static final Logger LOG = Logger.getInstance(SessionStore.class);

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<SessionData> sessions = new ArrayList<>();
    private final Set<String> deletedIds = new HashSet<>();
    private boolean loaded;
    /** Disk state we last saw, so unchanged files aren't re-parsed before each write. */
    private @Nullable FileTime lastSyncedTime;
    private long lastSyncedSize = -1L;
    private @Nullable Runnable writeFailureCallback;

    public SessionStore(Path file) {
        this.file = file;
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

    public synchronized void saveSession(SessionData session) {
        ensureLoaded();
        putInMemory(session);
        write(Collections.singleton(session.id));
    }

    /**
     * Bulk save with a single file write. Imports go through this: saving each
     * of N rows individually would rewrite the whole file N times.
     */
    public synchronized void saveSessions(List<SessionData> toSave) {
        if (toSave.isEmpty()) {
            return;
        }
        ensureLoaded();
        Set<String> saving = new HashSet<>();
        for (SessionData session : toSave) {
            putInMemory(session);
            saving.add(session.id);
        }
        write(saving);
    }

    private void putInMemory(SessionData session) {
        SessionData copy = session.copy();
        deletedIds.remove(copy.id);
        int index = indexOf(copy.id);
        if (index >= 0) {
            sessions.set(index, copy);
        } else {
            sessions.add(copy);
        }
    }

    public synchronized void deleteSession(String id) {
        ensureLoaded();
        int index = indexOf(id);
        if (index >= 0) {
            sessions.remove(index);
        }
        deletedIds.add(id);
        write(Set.of());
    }

    /** Bulk delete with a single file write (used by "delete older than" cleanup). */
    public synchronized void deleteSessions(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        ensureLoaded();
        sessions.removeIf(session -> ids.contains(session.id));
        deletedIds.addAll(ids);
        write(Set.of());
    }

    /**
     * Picks up what another IDE instance wrote since our last sync: new
     * sessions are added and sessions we already know are refreshed from the
     * file. Cheap when nothing changed - the file is only re-read when its
     * modification time or size moved, which matters because this runs on
     * every IDE focus change. Returns whether anything was picked up.
     */
    public synchronized boolean reload() {
        if (!loaded) {
            return false; // Nothing cached; the next access reads the file fresh anyway.
        }
        if (!diskChangedSinceLastSync()) {
            return false;
        }
        mergeFromDisk(Set.of());
        return true;
    }

    /**
     * Adds sessions from the legacy per-IDE storage that the file doesn't know
     * yet. Returns whether the result reached disk: the caller must NOT drop
     * its legacy copy on a false return, or an unwritable home directory
     * silently destroys the user's whole pre-migration history.
     */
    public synchronized boolean migrate(List<SessionData> legacySessions) {
        ensureLoaded();
        Set<String> added = new HashSet<>();
        for (SessionData session : legacySessions) {
            if (session.id != null && !session.id.isBlank() && indexOf(session.id) < 0) {
                sessions.add(session.copy());
                added.add(session.id);
            }
        }
        return added.isEmpty() || write(added);
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
        sessions.addAll(readFile());
        rememberDiskState();
    }

    /**
     * Folds the file into memory. The disk copy replaces ours for every id
     * except {@code keepLocal} (the sessions being saved in this very call,
     * which are by definition newer than anything on disk) and ids deleted
     * here. Sessions missing from the file are kept: a truncated or corrupt
     * file reads as empty, and dropping everything on that signal would turn
     * one bad read into a wiped history on the next write.
     */
    private void mergeFromDisk(Set<String> keepLocal) {
        for (SessionData onDisk : readFile()) {
            if (deletedIds.contains(onDisk.id) || keepLocal.contains(onDisk.id)) {
                continue;
            }
            int index = indexOf(onDisk.id);
            if (index >= 0) {
                sessions.set(index, onDisk);
            } else {
                sessions.add(onDisk);
            }
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

    private List<SessionData> readFile() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file);
            Type listType = new TypeToken<List<SessionData>>() {
            }.getType();
            List<SessionData> read = gson.fromJson(json, listType);
            if (read == null) {
                return List.of();
            }
            List<SessionData> valid = new ArrayList<>();
            for (SessionData session : read) {
                if (session != null && session.id != null && !session.id.isBlank()) {
                    valid.add(session.sanitize());
                }
            }
            return valid;
        } catch (IOException | JsonSyntaxException exception) {
            // A corrupt or unreadable file must not take the plugin down;
            // keep working in memory and overwrite on the next save.
            LOG.warn("Could not read " + file + "; continuing with in-memory sessions", exception);
            return List.of();
        }
    }

    private boolean write(Set<String> keepLocal) {
        // Merge what another IDE instance wrote since we last synced - but
        // only when the file actually changed, so the routine autosave isn't
        // a full read-parse of the history every 30 seconds.
        if (diskChangedSinceLastSync()) {
            mergeFromDisk(keepLocal);
        }
        try {
            Files.createDirectories(file.getParent());
            // Per-process, per-write temp name: two IDEs sharing one
            // "sessions.json.tmp" would overwrite each other's staging file
            // and one of the moves would fail or install the other's content.
            Path temp = file.resolveSibling(file.getFileName() + "."
                    + ProcessHandle.current().pid() + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".tmp");
            try {
                Files.writeString(temp, gson.toJson(sessions));
                moveIntoPlace(temp);
            } finally {
                discardQuietly(temp);
            }
            rememberDiskState();
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
