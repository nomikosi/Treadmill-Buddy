package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeenSessionTest {
    private static SessionData session(String id) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = "Walk";
        return session;
    }

    @Test
    void onlyFieldsThatMovedSinceTheLastLookAreReported() {
        SeenSession seen = new SeenSession();
        SessionData session = session("a");
        seen.remember(session);
        assertTrue(seen.update(session).isEmpty());

        session.speedKmh = 4.5;
        session.name = "Renamed elsewhere";
        assertEquals(Set.of(SeenSession.Field.SPEED, SeenSession.Field.NAME), seen.update(session));
        assertTrue(seen.update(session).isEmpty(), "each change is reported once");

        session.intervalBreakSeconds = 300;
        assertEquals(Set.of(SeenSession.Field.INTERVALS), seen.update(session));
    }

    @Test
    void aSessionFromAnotherWindowIsNotTheOneBeingShown() {
        SeenSession seen = new SeenSession();
        assertFalse(seen.forget(), "nothing was shown yet");
        seen.remember(session("a"));
        assertTrue(seen.isShowing(session("a")));
        assertFalse(seen.isShowing(session("b")));
        assertTrue(seen.forget());
        assertFalse(seen.isShowing(session("a")));
        assertFalse(seen.forget(), "a second forget has nothing left to forget");
    }
}
