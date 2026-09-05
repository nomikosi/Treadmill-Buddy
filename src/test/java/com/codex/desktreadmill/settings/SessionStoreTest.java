package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void reloadPicksUpSessionsWrittenByAnotherStore() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore first = new SessionStore(file);
        first.saveSession(session("a", "Mine"));

        SessionStore second = new SessionStore(file);
        second.getSessions();
        first.saveSession(session("b", "Written elsewhere"));

        assertNull(second.findSession("b"));
        second.reload();
        assertNotNull(second.findSession("b"));
    }

    @Test
    void reloadDoesNotResurrectLocallyDeletedSessions() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        store.saveSession(session("a", "Doomed"));
        store.deleteSession("a");
        store.reload();
        assertNull(store.findSession("a"));
    }

    @Test
    void migrateReportsFailureWhenTheStoreIsUnwritable() throws IOException {
        // The store path's parent is a regular file, so createDirectories fails
        // and nothing can ever reach disk.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        SessionStore store = new SessionStore(blocker.resolve("sessions.json"));

        assertFalse(store.migrate(List.of(session("a", "Legacy"))),
                "a migration that never reached disk must say so - the caller keeps the XML copy");
        // The sessions are still usable in memory for this run.
        assertNotNull(store.findSession("a"));
    }

    @Test
    void migrateWithNothingToAddSucceeds() {
        SessionStore store = new SessionStore(tempDir.resolve("sessions.json"));
        assertTrue(store.migrate(List.of()), "an empty migration has nothing to lose");
    }

    @Test
    void writeFailureCallbackFiresWhenSavesCannotReachDisk() throws IOException {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        SessionStore store = new SessionStore(blocker.resolve("sessions.json"));
        List<String> failures = new ArrayList<>();
        store.onWriteFailure(() -> failures.add("failed"));

        store.saveSession(session("a", "Doomed walk"));
        assertEquals(1, failures.size(), "a save that stayed in memory only must be reported");
    }

    @Test
    void batchSaveWritesAllSessionsAndReplacesById() {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        store.saveSession(session("a", "Before"));
        store.saveSessions(List.of(session("a", "After"), session("b", "New"), session("c", "Also new")));

        SessionStore reopened = new SessionStore(file);
        assertEquals(3, reopened.getSessions().size());
        assertEquals("After", reopened.findSession("a").name);
        assertNotNull(reopened.findSession("c"));
    }

    @Test
    void writeStillMergesOtherIdesSessionsWhenTheFileChangedOnDisk() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        SessionStore store = new SessionStore(file);
        store.saveSession(session("a", "Mine"));

        // Another IDE writes; make sure the timestamp actually differs even on
        // coarse filesystem clocks.
        SessionStore other = new SessionStore(file);
        other.saveSession(session("b", "Theirs"));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));

        // The next write must notice the change and keep "b".
        store.saveSession(session("c", "Mine too"));
        SessionStore reopened = new SessionStore(file);
        assertEquals(3, reopened.getSessions().size());
        assertNotNull(reopened.findSession("b"), "the other IDE's session must survive our write");
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

    /** Pushes the file's timestamp forward so a change registers even on coarse filesystem clocks. */
    private static void touchLater(Path file) throws IOException {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));
    }

    private static SessionData walked(String id, long elapsedSeconds) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = "Walk " + id;
        session.elapsedSeconds = elapsedSeconds;
        session.createdMillis = 1_000_000L;
        return session;
    }

    @Test
    void reloadRefreshesASessionAnotherStoreExtended() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        SessionStore first = new SessionStore(file);
        first.saveSession(walked("a", 60L));
        SessionStore second = new SessionStore(file);
        second.getSessions();

        // The walk is resumed and extended in the other IDE.
        first.saveSession(walked("a", 600L));
        touchLater(file);

        assertTrue(second.reload(), "a changed file must be reported");
        assertEquals(600L, second.findSession("a").elapsedSeconds,
                "the focus refresh must show the extended walk, not the stale copy");
    }

    @Test
    void reloadReportsNothingWhenTheFileDidNotChange() {
        SessionStore store = new SessionStore(tempDir.resolve("sessions.json"));
        store.saveSession(walked("a", 60L));
        assertFalse(store.reload(), "an unchanged file must not trigger a re-render");
    }

    @Test
    void writeKeepsTheNewerCopyOfASessionItDidNotTouch() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        SessionStore first = new SessionStore(file);
        first.saveSession(walked("a", 60L));
        SessionStore second = new SessionStore(file);
        second.getSessions();

        first.saveSession(walked("a", 600L));
        touchLater(file);

        // Saving something else here used to write the stale 60-second copy
        // of "a" back over the other IDE's 600 seconds.
        second.saveSession(walked("b", 30L));
        assertEquals(600L, new SessionStore(file).findSession("a").elapsedSeconds);
    }

    @Test
    void theSessionBeingSavedWinsOverTheDiskCopy() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        SessionStore first = new SessionStore(file);
        first.saveSession(walked("a", 60L));
        SessionStore second = new SessionStore(file);
        second.getSessions();

        first.saveSession(walked("a", 600L));
        touchLater(file);

        // This store is actively saving "a": its copy is the in-flight one.
        second.saveSession(walked("a", 120L));
        assertEquals(120L, new SessionStore(file).findSession("a").elapsedSeconds);
    }

    @Test
    void aLeftoverTempFileFromAnotherProcessIsLeftAlone() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        Path foreignTemp = tempDir.resolve("sessions.json.tmp");
        Files.writeString(foreignTemp, "another IDE is still writing this");

        SessionStore store = new SessionStore(file);
        store.saveSession(walked("a", 60L));

        assertEquals("another IDE is still writing this", Files.readString(foreignTemp),
                "each writer must stage in its own temp file");
        assertEquals(1, new SessionStore(file).getSessions().size());
    }

    @Test
    void nullFieldsInTheFileAreRepairedOnRead() throws IOException {
        Path file = tempDir.resolve("sessions.json");
        Files.writeString(file, "[{\"id\":\"a\",\"name\":null,\"modeId\":null,\"segments\":[null],\"elapsedSeconds\":60}]");

        SessionData read = new SessionStore(file).findSession("a");
        assertNotNull(read);
        assertEquals("", read.name, "a null name would take down CSV export and the list renderer");
        assertEquals("MARATHON", read.modeId);
        assertTrue(read.segments.isEmpty());
    }
}
