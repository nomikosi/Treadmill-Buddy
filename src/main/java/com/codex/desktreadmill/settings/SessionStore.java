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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable session history shared across all JetBrains IDEs. Sessions live in
 * {@code ~/.treadmill-buddy/sessions.json} instead of per-IDE settings, so a
 * walk logged in IntelliJ also counts in Rider and survives IDE reinstalls.
 *
 * <p>Writes are atomic (temp file + move). Before a write, sessions another
 * IDE added since our last read are merged in by id - but only when the file
 * actually changed on disk (checked by modification time and size), so the
 * routine 30-second autosave doesn't re-read and re-parse the whole history
 * on the EDT every time. Ids deleted here stay deleted.</p>
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
        write();
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
        for (SessionData session : toSave) {
            putInMemory(session);
        }
        write();
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
        write();
    }

    /** Bulk delete with a single file write (used by "delete older than" cleanup). */
    public synchronized void deleteSessions(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        ensureLoaded();
        sessions.removeIf(session -> ids.contains(session.id));
        deletedIds.addAll(ids);
        write();
    }

    /**
     * Merges sessions another IDE instance wrote since our last read. In-memory
     * sessions win on id conflicts (ours may include an in-flight workout);
     * ids deleted here stay deleted.
     */
    public synchronized void reload() {
        if (!loaded) {
            return; // Nothing cached; the next access reads the file fresh anyway.
        }
        mergeFromDisk();
    }

    /**
     * Adds sessions from the legacy per-IDE storage that the file doesn't know
     * yet. Returns whether the result reached disk: the caller must NOT drop
     * its legacy copy on a false return, or an unwritable home directory
     * silently destroys the user's whole pre-migration history.
     */
    public synchronized boolean migrate(List<SessionData> legacySessions) {
        ensureLoaded();
        boolean added = false;
        for (SessionData session : legacySessions) {
            if (session.id != null && !session.id.isBlank() && indexOf(session.id) < 0) {
                sessions.add(session.copy());
                added = true;
            }
        }
        return !added || write();
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

    private void mergeFromDisk() {
        for (SessionData onDisk : readFile()) {
            if (indexOf(onDisk.id) < 0 && !deletedIds.contains(onDisk.id)) {
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
                    if (session.segments == null) {
                        session.segments = new ArrayList<>();
                    }
                    valid.add(session);
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

    private boolean write() {
        // Merge sessions another IDE instance wrote since we last synced -
        // but only when the file actually changed, so the routine autosave
        // isn't a full read-parse of the history every 30 seconds.
        if (diskChangedSinceLastSync()) {
            mergeFromDisk();
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(sessions));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
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
}
