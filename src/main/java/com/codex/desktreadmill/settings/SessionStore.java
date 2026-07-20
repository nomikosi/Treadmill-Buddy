package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable session history shared across all JetBrains IDEs. Sessions live in
 * {@code ~/.treadmill-buddy/sessions.json} instead of per-IDE settings, so a
 * walk logged in IntelliJ also counts in Rider and survives IDE reinstalls.
 *
 * <p>Writes are atomic (temp file + move). Before each write, sessions another
 * IDE added since our last read are merged in by id, so two open IDEs don't
 * clobber each other's saves; ids deleted here stay deleted.</p>
 */
public final class SessionStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<SessionData> sessions = new ArrayList<>();
    private final Set<String> deletedIds = new HashSet<>();
    private boolean loaded;

    public SessionStore(Path file) {
        this.file = file;
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
        SessionData copy = session.copy();
        deletedIds.remove(copy.id);
        int index = indexOf(copy.id);
        if (index >= 0) {
            sessions.set(index, copy);
        } else {
            sessions.add(copy);
        }
        write();
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

    /**
     * Merges sessions another IDE instance wrote since our last read. In-memory
     * sessions win on id conflicts (ours may include an in-flight workout);
     * ids deleted here stay deleted.
     */
    public synchronized void reload() {
        if (!loaded) {
            return; // Nothing cached; the next access reads the file fresh anyway.
        }
        for (SessionData onDisk : readFile()) {
            if (indexOf(onDisk.id) < 0 && !deletedIds.contains(onDisk.id)) {
                sessions.add(onDisk);
            }
        }
    }

    /** Adds sessions from the legacy per-IDE storage that the file doesn't know yet. */
    public synchronized void migrate(List<SessionData> legacySessions) {
        ensureLoaded();
        boolean added = false;
        for (SessionData session : legacySessions) {
            if (session.id != null && !session.id.isBlank() && indexOf(session.id) < 0) {
                sessions.add(session.copy());
                added = true;
            }
        }
        if (added) {
            write();
        }
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
            return List.of();
        }
    }

    private void write() {
        // Merge sessions another IDE instance wrote since we last read.
        for (SessionData onDisk : readFile()) {
            if (indexOf(onDisk.id) < 0 && !deletedIds.contains(onDisk.id)) {
                sessions.add(onDisk);
            }
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
        } catch (IOException exception) {
            // Sessions stay in memory; the next successful write persists them.
        }
    }
}
