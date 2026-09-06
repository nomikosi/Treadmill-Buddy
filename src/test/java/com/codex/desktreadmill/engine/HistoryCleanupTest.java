package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryCleanupTest {
    private static final long DAY = 86_400_000;
    private static final long TODAY = 1_800_057_600_000L;
    private static final long CUTOFF = TODAY - 365 * DAY;

    private static SessionData session(String id, long created) {
        SessionData session = new SessionData();
        session.id = id;
        session.createdMillis = created;
        session.elapsedSeconds = 60;
        return session;
    }

    @Test
    void oldSessionResumedTodayIsKept() {
        SessionData resumed = session("resumed", TODAY - 400 * DAY);
        resumed.activityDays.add(new DailyActivity(TODAY, 60, 0.06, 100, 2));
        SessionData old = session("old", TODAY - 400 * DAY);
        assertEquals(List.of("old"), HistoryCleanup.candidates(List.of(resumed, old), null, CUTOFF));
    }

    @Test
    void sessionOnTheClockIsProtectedEvenWithoutRecentTicks() {
        SessionData current = session("current", TODAY - 400 * DAY);
        assertEquals(List.of(), HistoryCleanup.candidates(List.of(current), current.id, CUTOFF));
    }

    @Test
    void legacySessionsUseCreationDateAndKeepTheCutoffDay() {
        assertEquals(List.of("old"), HistoryCleanup.candidates(List.of(
                session("old", CUTOFF - 1), session("boundary", CUTOFF),
                session("recent", TODAY), session("unknown", 0)), null, CUTOFF));
    }

    @Test
    void emptyActivityBucketsDoNotMakeAnOldSessionRecent() {
        SessionData old = session("old", CUTOFF - DAY);
        old.activityDays.add(new DailyActivity(TODAY, 0, 0, 0, 0));
        assertEquals(List.of("old"), HistoryCleanup.candidates(List.of(old), null, CUTOFF));
    }

    @Test
    void aSecondCheckRemovesCandidatesResumedWhileConfirmationWasOpen() {
        SessionData old = session("old", CUTOFF - DAY);
        List<String> candidates = HistoryCleanup.candidates(List.of(old), null, CUTOFF);
        assertEquals(List.of("old"), candidates);
        old.activityDays.add(new DailyActivity(TODAY, 1, 0.001, 1, 0.02));
        assertEquals(List.of(), HistoryCleanup.candidates(List.of(old), null, CUTOFF));
    }
}
