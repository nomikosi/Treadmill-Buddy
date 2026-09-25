package com.codex.desktreadmill;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeWorkoutFeedbackTest {
    @Test
    void completionMessageUsesDisplayUnitsAndEscapesTheName() {
        SessionData session = new SessionData();
        session.name = "Walk <b>bold</b>";
        session.distanceKm = 2.25;
        session.calories = 150.4;
        String imperial = IdeWorkoutFeedback.completionMessage(session, UnitSystem.IMPERIAL);
        assertTrue(imperial.contains(" mi,"), "imperial users must not be told km: " + imperial);
        assertFalse(imperial.contains("km"));
        assertTrue(imperial.contains("&lt;b&gt;"), "balloon content is HTML, names must be escaped: " + imperial);
        assertTrue(IdeWorkoutFeedback.completionMessage(session, UnitSystem.METRIC).contains(" km,"));
    }

    @Test
    void durationFormatDropsTheZeroHourForShortSessions() {
        assertEquals("45s", IdeWorkoutFeedback.formatDuration(45));
        assertEquals("25m", IdeWorkoutFeedback.formatDuration(25 * 60));
        assertEquals("1h 05m", IdeWorkoutFeedback.formatDuration(3600 + 5 * 60));
    }
}
