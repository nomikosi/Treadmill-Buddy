package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {
    @TempDir
    Path tempDir;

    private SessionData session(String id, String name) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = name;
        session.elapsedSeconds = 60L;
        session.distanceKm = 1.5;
        session.createdMillis = 1_000_000L;
        session.segments.add(new SpeedSegment(3.0, 60L));
        return session;
    }

    @Test
    void savedSessionsSurviveANewStoreInstance() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        store.saveSession(session("a", "Morning walk"));

        SessionStore reopened = new SessionStore(file);
        SessionData read = reopened.findSession("a");
        assertNotNull(read);
        assertEquals("Morning walk", read.name);
        assertEquals(1.5, read.distanceKm, 0.0001);
        assertEquals(1, read.segments.size());
        assertEquals(60L, read.segments.get(0).seconds);
    }

    @Test
    void deleteRemovesSessionFromDisk() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        store.saveSession(session("a", "One"));
        store.saveSession(session("b", "Two"));
        store.deleteSession("a");

        SessionStore reopened = new SessionStore(file);
        assertNull(reopened.findSession("a"));
        assertNotNull(reopened.findSession("b"));
    }

    @Test
    void saveWithExistingIdReplacesInsteadOfDuplicating() {
        SessionStore store = new SessionStore(tempDir.resolve("sessions.json"));
        store.saveSession(session("a", "Before"));
        store.saveSession(session("a", "After"));
        assertEquals(1, store.getSessions().size());
        assertEquals("After", store.findSession("a").name);
    }

    @Test
    void migrateAddsOnlyUnknownSessions() {
        SessionStore store = new SessionStore(tempDir.resolve("sessions.json"));
        store.saveSession(session("a", "Existing"));
        store.migrate(List.of(session("a", "Legacy duplicate"), session("b", "Legacy new")));
        assertEquals(2, store.getSessions().size());
        assertEquals("Existing", store.findSession("a").name);
        assertEquals("Legacy new", store.findSession("b").name);
    }

    @Test
    void sessionsWrittenByAnotherStoreAreMergedOnWrite() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore first = new SessionStore(file);
        first.saveSession(session("a", "From first IDE"));

        // Simulates a second IDE that loaded earlier and now saves its own session.
        SessionStore second = new SessionStore(file);
        second.getSessions();
        first.saveSession(session("b", "Also from first"));
        second.saveSession(session("c", "From second IDE"));

        SessionStore reopened = new SessionStore(file);
        assertEquals(3, reopened.getSessions().size());
    }

    @Test
    void corruptFileIsToleratedAndOverwritten() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        Files.writeString(file, "this is not json{{{");
        SessionStore store = new SessionStore(file);
        assertTrue(store.getSessions().isEmpty());
        store.saveSession(session("a", "Fresh"));
        assertEquals(1, new SessionStore(file).getSessions().size());
    }
}
