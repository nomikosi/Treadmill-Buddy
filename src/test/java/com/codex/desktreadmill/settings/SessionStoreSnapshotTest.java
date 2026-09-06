package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreSnapshotTest {
    @TempDir Path directory;

    private static SessionData walk(String id, long seconds) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = id;
        session.elapsedSeconds = seconds;
        return session;
    }

    @Test
    void savingAfterAConcurrentReplacementDuringInitialReadPreservesAllChanges() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSessions(List.of(walk("extended", 60), walk("deleted", 60))));
        SessionStore reader = new SessionStore(file);
        duringNextDecode(reader, () -> {
            assertTrue(writer.deleteSession("deleted"));
            assertTrue(writer.saveSessions(List.of(walk("extended", 600), walk("new", 60))));
        });

        reader.getSessions();
        assertTrue(reader.saveSession(walk("local", 60)));

        SessionStore reopened = new SessionStore(file);
        assertNull(reopened.findSession("deleted"));
        assertEquals(600, reopened.findSession("extended").elapsedSeconds);
        assertNotNull(reopened.findSession("new"));
        assertNotNull(reopened.findSession("local"));
    }

    @Test
    void replacementDuringReloadIsStillVisibleToTheNextRefresh() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSession(walk("original", 60)));
        SessionStore reader = new SessionStore(file);
        reader.getSessions();
        assertTrue(writer.saveSession(walk("trigger", 60)));
        duringNextDecode(reader, () -> assertTrue(writer.saveSession(walk("concurrent", 60))));

        assertTrue(reader.reload());
        assertTrue(reader.reload(), "an old read must not be stamped with the replacement's metadata");
        assertNotNull(reader.findSession("concurrent"));
        assertFalse(reader.reload());
    }

    @Test
    void saveMergesEvenWhenAReplacementHasTheSameTimestampAndSize() throws Exception {
        Path file = directory.resolve("sessions.json");
        SessionStore writer = new SessionStore(file);
        assertTrue(writer.saveSession(walk("original", 60)));
        SessionStore reader = new SessionStore(file);
        reader.getSessions();
        var timestamp = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        assertTrue(writer.saveSession(walk("original", 90)));
        assertEquals(size, Files.size(file));
        Files.setLastModifiedTime(file, timestamp);

        assertTrue(reader.saveSession(walk("local", 60)));
        assertEquals(90, new SessionStore(file).findSession("original").elapsedSeconds);
    }

    /** Interleave another store's real writes after bytes were read, before decoding finishes. */
    private static void duringNextDecode(SessionStore store, Runnable concurrentWrite) throws Exception {
        AtomicBoolean pending = new AtomicBoolean(true);
        Gson delegate = new Gson();
        Gson gated = new GsonBuilder().registerTypeAdapter(SessionData.class,
                (JsonDeserializer<SessionData>) (json, type, context) -> {
                    if (pending.getAndSet(false)) {
                        concurrentWrite.run();
                    }
                    return delegate.fromJson(json, SessionData.class);
                }).create();
        Field gson = SessionStore.class.getDeclaredField("gson");
        gson.setAccessible(true);
        gson.set(store, gated);
    }
}
