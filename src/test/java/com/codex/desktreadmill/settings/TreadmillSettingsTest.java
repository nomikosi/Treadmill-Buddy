package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreadmillSettingsTest {
    @TempDir
    Path tempDir;

    private static SessionData session(String id) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = "Walk " + id;
        session.elapsedSeconds = 60L;
        session.createdMillis = 1_000_000L;
        return session;
    }

    @Test
    void savingTheCurrentSessionMarksItAsTheLastOne() {
        TreadmillSettings settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        settings.saveSession(session("current"));
        assertEquals("current", settings.getLastSessionId());
    }

    @Test
    void importsDoNotChangeTheLastSession() {
        TreadmillSettings settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        settings.saveSession(session("current"));
        // A backup restore used to make its last row the walk the clock
        // opens with after the next IDE start.
        settings.saveSessions(List.of(session("old-1"), session("old-2")));
        assertEquals("current", settings.getLastSessionId());
        assertNotNull(settings.findSession("old-2"));
    }

    @Test
    void restoringADeletedSessionDoesNotChangeTheLastSession() {
        TreadmillSettings settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        settings.saveSession(session("current"));
        settings.restoreSession(session("undone"));
        assertEquals("current", settings.getLastSessionId());
        assertNotNull(settings.findSession("undone"));
    }

    @Test
    void floatingClockOpensAutomaticallyByDefault() {
        TreadmillSettings settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        assertTrue(settings.isFloatingClockAutoShow());
        settings.setFloatingClockAutoShow(false);
        assertFalse(settings.isFloatingClockAutoShow());
    }
}
