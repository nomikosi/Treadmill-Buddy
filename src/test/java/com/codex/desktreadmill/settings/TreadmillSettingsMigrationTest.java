package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreadmillSettingsMigrationTest {
    @TempDir
    Path tempDir;

    private static TreadmillSettings.StateData legacyState() {
        TreadmillSettings.StateData state = new TreadmillSettings.StateData();
        SessionData legacy = new SessionData();
        legacy.id = "legacy-1";
        legacy.name = "Pre-1.1.0 walk";
        legacy.elapsedSeconds = 600L;
        legacy.createdMillis = 1_000_000L;
        state.sessions.add(legacy);
        return state;
    }

    @Test
    void successfulMigrationMovesSessionsOutOfTheXmlState() {
        TreadmillSettings settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        settings.loadState(legacyState());

        assertTrue(settings.getState().sessions.isEmpty(),
                "once the store confirmed the write, the XML copy is redundant");
        assertNotNull(settings.findSession("legacy-1"));
    }

    @Test
    void failedMigrationKeepsTheXmlCopyForTheNextAttempt() throws IOException {
        // Store path is unwritable: its parent is a regular file.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        TreadmillSettings settings = new TreadmillSettings(blocker.resolve("sessions.json"));
        settings.loadState(legacyState());

        assertEquals(1, settings.getState().sessions.size(),
                "the XML copy is the only durable one left - clearing it would destroy the history");
        // The sessions are still visible for this run through the in-memory store.
        assertNotNull(settings.findSession("legacy-1"));
    }
}
