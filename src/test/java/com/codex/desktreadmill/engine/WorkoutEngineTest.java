package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkoutEngineTest {
    @TempDir
    Path tempDir;

    private TreadmillSettings settings;
    private WorkoutEngine engine;
    private long nowMillis;

    @BeforeEach
    void setUp() {
        settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        profile.completed = true;
        settings.setProfile(profile);
        settings.setAutoPauseMinutes(0);
        // A realistic instant, and midday so "yesterday" stays a distinct
        // calendar day in every time zone the tests might run in.
        nowMillis = LocalDate.of(2026, 8, 10).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        engine = new WorkoutEngine(settings, () -> nowMillis, false);
    }

    @AfterEach
    void tearDown() {
        engine.dispose();
    }

    private SessionData marathonSession() {
        SessionData session = new SessionData();
        session.id = "test";
        session.name = "Test";
        session.modeId = SessionMode.MARATHON.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 5.0;
        return session;
    }

    @Test
    void creditsWallClockTimeNotTickCount() {
        engine.startSession(marathonSession());
        // One late tick must credit all five elapsed seconds.
        nowMillis += 5_000;
        engine.tick();
        assertEquals(5L, engine.getSession().elapsedSeconds);
    }

    @Test
    void subSecondRemaindersCarryOver() {
        engine.startSession(marathonSession());
        nowMillis += 400;
        engine.tick();
        assertEquals(0L, engine.getSession().elapsedSeconds);
        nowMillis += 700;
        engine.tick();
        assertEquals(1L, engine.getSession().elapsedSeconds);
    }

    @Test
    void suspendGapPausesWithoutCreditingTime() {
        engine.startSession(marathonSession());
        nowMillis += 90_000;
        engine.tick();
        assertFalse(engine.isRunning());
        assertTrue(engine.isAutoPaused());
        assertTrue(engine.getStatusNote().contains("sleep"));
        assertEquals(0L, engine.getSession().elapsedSeconds);
    }

    @Test
    void idleActivityAutoPauses() {
        settings.setAutoPauseMinutes(1);
        engine.startSession(marathonSession());
        nowMillis += 30_000;
        engine.tick();
        assertEquals(30L, engine.getSession().elapsedSeconds);
        // No user activity since start: 61s idle crosses the 1-minute threshold.
        nowMillis += 31_000;
        engine.tick();
        assertFalse(engine.isRunning());
        assertTrue(engine.isAutoPaused());
        assertEquals(30L, engine.getSession().elapsedSeconds);
    }

    @Test
    void countdownSessionCompletes() {
        SessionData session = marathonSession();
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.targetCalories = 0.5;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        long targetSeconds = session.remainingSeconds;
        assertTrue(targetSeconds > 0 && targetSeconds < 30);

        List<SessionData> completed = new ArrayList<>();
        engine.addListener(new WorkoutEngine.Listener() {
            @Override
            public void workoutStateChanged() {
            }

            @Override
            public void sessionCompleted(SessionData finished) {
                completed.add(finished);
            }
        });

        engine.startSession(session);
        nowMillis += (targetSeconds + 5) * 1000;
        engine.tick();
        assertFalse(engine.isRunning());
        assertTrue(engine.getSession().completed);
        assertEquals(1, completed.size());
    }

    @Test
    void pauseStopsCreditingTime() {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        engine.pause();
        nowMillis += 60_000;
        engine.tick();
        assertEquals(10L, engine.getSession().elapsedSeconds);
    }

    @Test
    void intervalModeAlternatesWalkAndBreakBlocks() {
        SessionData session = marathonSession();
        session.modeId = SessionMode.INTERVAL.name();
        session.intervalWalkSeconds = 60L;
        session.intervalBreakSeconds = 30L;
        engine.startSession(session);

        // Full walk block (ticked in two halves to stay under the suspend-gap
        // threshold): metrics accumulate, then the phase flips to break.
        nowMillis += 30_000;
        engine.tick();
        nowMillis += 30_000;
        engine.tick();
        assertEquals(60L, engine.getSession().elapsedSeconds);
        assertFalse(engine.getSession().intervalWalking);

        // Break block: time passes but no walking is credited.
        nowMillis += 30_000;
        engine.tick();
        assertEquals(60L, engine.getSession().elapsedSeconds);
        assertTrue(engine.getSession().intervalWalking);
        assertTrue(engine.isRunning());
    }

    @Test
    void intervalBreakBlockIsExemptFromIdlePause() {
        settings.setAutoPauseMinutes(1);
        SessionData session = marathonSession();
        session.modeId = SessionMode.INTERVAL.name();
        session.intervalWalkSeconds = 30L;
        session.intervalBreakSeconds = 120L;
        engine.startSession(session);

        // Walk block finishes within the idle window.
        nowMillis += 30_000;
        engine.tick();
        assertFalse(engine.getSession().intervalWalking);

        // Two idle minutes into the break: no typing is expected here, so the
        // break countdown must keep running instead of auto-pausing.
        nowMillis += 50_000;
        engine.tick();
        nowMillis += 50_000;
        engine.tick();
        nowMillis += 20_000;
        engine.tick();
        assertTrue(engine.isRunning());
        assertTrue(engine.getSession().intervalWalking);
    }

    /** Runs the engine for `seconds`, then pauses (which triggers the records check). */
    private void walkAndPause(long seconds) {
        for (long remaining = seconds; remaining > 0; remaining -= 30) {
            nowMillis += Math.min(30, remaining) * 1000;
            engine.tick();
        }
        engine.pause();
    }

    @Test
    void aSessionNeverSetsARecordAgainstItsOwnEarlierState() {
        // Fresh install, no history at all. Walking, pausing, resuming and
        // pausing again must not crown the first walk for beating itself.
        // createdMillis is set so today's totals really do include this
        // session - otherwise the day assertions below would pass trivially.
        SessionData session = marathonSession();
        session.createdMillis = nowMillis;
        engine.startSession(session);
        walkAndPause(60);
        engine.resume();
        walkAndPause(60);
        engine.resume();
        walkAndPause(60);

        assertTrue(engine.getSession().distanceKm > 0, "the session must have walked something");
        assertEquals("", settings.getLastSessionRecordId());
        assertEquals(0L, settings.getLastDistanceRecordDay());
        assertEquals(0L, settings.getLastStepsRecordDay());
    }

    @Test
    void longestSessionRecordIsMarkedWhenItBeatsOtherSessions() {
        SessionData historic = marathonSession();
        historic.id = "old";
        historic.elapsedSeconds = 120L;
        historic.distanceKm = 0.2;
        historic.steps = 300L;
        historic.createdMillis = nowMillis - 86_400_000L;
        settings.saveSession(historic);

        engine.startSession(marathonSession());
        walkAndPause(60);
        // Still shorter than the 120s record: nothing to announce yet.
        assertEquals("", settings.getLastSessionRecordId());

        engine.resume();
        walkAndPause(120);
        assertEquals("test", settings.getLastSessionRecordId());
    }

    @Test
    void dayDistanceRecordIsMarkedWhenTodayBeatsOtherDays() {
        SessionData yesterday = marathonSession();
        yesterday.id = "yesterday";
        yesterday.elapsedSeconds = 600L;
        yesterday.distanceKm = 0.1;
        yesterday.steps = 100L;
        yesterday.createdMillis = nowMillis - 86_400_000L;
        settings.saveSession(yesterday);

        SessionData today = marathonSession();
        today.createdMillis = nowMillis;
        engine.startSession(today);
        walkAndPause(120);
        long expectedDay = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
        assertEquals(expectedDay, settings.getLastDistanceRecordDay(),
                "the distance guard must be keyed on today, not just any non-zero day");
    }

    @Test
    void resetLetsTheSameSessionEarnTheRecordAgain() {
        SessionData historic = marathonSession();
        historic.id = "old";
        historic.elapsedSeconds = 120L;
        historic.createdMillis = nowMillis - 86_400_000L;
        settings.saveSession(historic);

        engine.startSession(marathonSession());
        walkAndPause(180);
        assertEquals("test", settings.getLastSessionRecordId());

        // Reset restarts the walk under the same id; the guard must let go,
        // otherwise this session can never announce a record again.
        engine.reset();
        assertEquals("", settings.getLastSessionRecordId());
        engine.resume();
        walkAndPause(180);
        assertEquals("test", settings.getLastSessionRecordId());
    }

    @Test
    void dayStepsAndDistanceGuardsAreNotCrossWired() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        // Yesterday: short but dense (many steps, little distance) so that
        // today can beat the distance record without beating the step record.
        SessionData yesterday = record("yesterday", 600L, 1.0, 99_999L, today.minusDays(1));
        SessionData current = record("current", 600L, 5.0, 10L, today);

        WorkoutEngine.BrokenRecords broken =
                decide(List.of(yesterday, current), current, today, "", 0L, 0L);
        assertTrue(broken.dayDistance, "5 km beats yesterday's 1 km");
        assertFalse(broken.daySteps, "10 steps does not beat yesterday's 99,999");

        // And the mirror image: the steps guard alone must block steps.
        WorkoutEngine.BrokenRecords stepsBlocked =
                decide(List.of(yesterday, current), current, today, "", 0L, today.toEpochDay());
        assertTrue(stepsBlocked.dayDistance, "the steps guard must not suppress the distance record");
    }

    /** The record decision is pure, so the once-only rules can be checked directly. */
    private static WorkoutEngine.BrokenRecords decide(
            List<SessionData> history, SessionData current, LocalDate today,
            String lastSessionId, long lastDistanceDay, long lastStepsDay
    ) {
        return WorkoutEngine.brokenRecords(history, current, today, ZoneOffset.UTC,
                lastSessionId, lastDistanceDay, lastStepsDay);
    }

    private static SessionData record(String id, long seconds, double km, long steps, LocalDate day) {
        SessionData session = new SessionData();
        session.id = id;
        session.elapsedSeconds = seconds;
        session.distanceKm = km;
        session.steps = steps;
        session.createdMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return session;
    }

    @Test
    void aSoloSessionNeverBreaksARecordAgainstItself() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        SessionData only = record("only", 3_600L, 5.0, 7_000L, today);
        WorkoutEngine.BrokenRecords broken = decide(List.of(only), only, today, "", 0L, 0L);
        // The baseline must be 0, not the session's own 3600 - that is the
        // difference between excluding the current session and not.
        assertEquals(0L, broken.previousLongestSeconds, "the session must not be its own baseline");
        assertFalse(broken.longestSession, "no other session to beat");
        assertFalse(broken.dayDistance, "no other day to beat");
        assertFalse(broken.daySteps, "no other day to beat");
    }

    @Test
    void theSameSessionIsNotAnnouncedTwice() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        SessionData older = record("old", 600L, 1.0, 1_000L, today.minusDays(1));
        SessionData current = record("current", 1_200L, 2.0, 2_000L, today);
        List<SessionData> history = List.of(older, current);

        assertTrue(decide(history, current, today, "", 0L, 0L).longestSession);
        // Once "current" is recorded as announced, a later pause stays quiet.
        assertFalse(decide(history, current, today, "current", 0L, 0L).longestSession);
    }

    @Test
    void aSecondSessionTheSameDayDoesNotReAnnounceTheDayRecord() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        long epochDay = today.toEpochDay();
        SessionData yesterday = record("yesterday", 600L, 1.0, 1_000L, today.minusDays(1));
        SessionData first = record("first", 600L, 1.5, 1_500L, today);
        SessionData second = record("second", 600L, 1.5, 1_500L, today);

        // First session of the day beats yesterday: announce.
        assertTrue(decide(List.of(yesterday, first), first, today, "", 0L, 0L).dayDistance);
        // Second session pushes the day total higher, but the day already fired.
        WorkoutEngine.BrokenRecords broken =
                decide(List.of(yesterday, first, second), second, today, "", epochDay, epochDay);
        assertFalse(broken.dayDistance);
        assertFalse(broken.daySteps);
    }

    @Test
    void durationFormatDropsTheZeroHourForShortSessions() {
        assertEquals("45s", WorkoutEngine.formatDuration(45));
        assertEquals("25m", WorkoutEngine.formatDuration(25 * 60));
        assertEquals("1h 05m", WorkoutEngine.formatDuration(3600 + 5 * 60));
    }

    @Test
    void keepRunningWhenIdleSuppressesIdlePause() {
        settings.setAutoPauseMinutes(1);
        engine.setKeepRunningWhenIdle(true);
        engine.startSession(marathonSession());
        nowMillis += 55_000;
        engine.tick();
        nowMillis += 20_000;
        engine.tick();
        assertTrue(engine.isRunning());
        assertEquals(75L, engine.getSession().elapsedSeconds);
    }

    @Test
    void runningSessionIsPersistedToSettings() {
        engine.startSession(marathonSession());
        nowMillis += 40_000;
        engine.tick();
        SessionData stored = settings.findSession("test");
        assertTrue(stored != null && stored.elapsedSeconds > 0);
    }

    @Test
    void clearingARunningSessionPersistsItsProgressFirst() {
        engine.startSession(marathonSession());
        // Ten seconds: well inside the 30-second autosave window, so nothing
        // but the clear itself can have written this progress.
        nowMillis += 10_000;
        engine.tick();
        engine.clearSession();
        SessionData stored = settings.findSession("test");
        assertNotNull(stored);
        assertEquals(10L, stored.elapsedSeconds, "New must not drop the walk since the last autosave");
    }

    @Test
    void loadingAnotherSessionPersistsTheRunningOneFirst() {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        SessionData other = marathonSession();
        other.id = "other";
        engine.loadSession(other);
        assertEquals("other", engine.getSession().id);
        assertFalse(engine.isRunning());
        assertEquals(10L, settings.findSession("test").elapsedSeconds);
    }

    @Test
    void startingANewSessionPersistsTheRunningOneFirst() {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        SessionData next = marathonSession();
        next.id = "next";
        engine.startSession(next);
        assertTrue(engine.isRunning());
        assertEquals(10L, settings.findSession("test").elapsedSeconds);
    }

    /** Simulates a second IDE extending the same session in the shared store. */
    private void extendElsewhere(String id, long elapsedSeconds) throws IOException {
        Path file = tempDir.resolve("sessions.json");
        TreadmillSettings otherIde = new TreadmillSettings(file);
        SessionData extended = otherIde.findSession(id);
        assertNotNull(extended);
        extended.elapsedSeconds = elapsedSeconds;
        otherIde.saveSession(extended);
        // Make sure the change is visible even on coarse filesystem clocks.
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
    }

    @Test
    void shutdownDoesNotOverwriteAPausedSessionExtendedInAnotherIde() throws IOException {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        engine.pause();
        extendElsewhere("test", 99L);

        // Pause already persisted this clock's state; flushing it again would
        // roll the other IDE's 99 seconds back to 10.
        engine.dispose();
        assertEquals(99L, new TreadmillSettings(tempDir.resolve("sessions.json")).findSession("test").elapsedSeconds);
    }

    @Test
    void resumeContinuesFromTheCopyExtendedInAnotherIde() throws IOException {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        engine.pause();
        extendElsewhere("test", 99L);

        assertTrue(settings.reloadSessions(), "the focus refresh must notice the other IDE's write");
        engine.adoptStoredProgress(settings.findSession("test"));
        assertEquals(99L, engine.getSession().elapsedSeconds);

        engine.resume();
        nowMillis += 5_000;
        engine.tick();
        assertEquals(104L, engine.getSession().elapsedSeconds);
        engine.pause();
        assertEquals(104L, settings.findSession("test").elapsedSeconds,
                "the pause after resume persists the continued walk, not a rollback to 10");
    }

    @Test
    void aRunningSessionIsNotReplacedByAStoredCopy() {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        SessionData stale = engine.getSession().copy();
        stale.elapsedSeconds = 99L;
        engine.adoptStoredProgress(stale);
        assertEquals(10L, engine.getSession().elapsedSeconds, "the clock that is running is the authority");
    }

    @Test
    void restoreAfterResetPutsTheWalkBackOnTheClockAndInHistory() {
        engine.startSession(marathonSession());
        nowMillis += 10_000;
        engine.tick();
        engine.pause();
        SessionData before = engine.getSession().copy();

        engine.reset();
        assertEquals(0L, settings.findSession("test").elapsedSeconds);

        engine.restoreSession(before);
        assertEquals(10L, engine.getSession().elapsedSeconds);
        assertEquals(10L, settings.findSession("test").elapsedSeconds);
    }

    @Test
    void onlyRealTypingCountsAsTyping() {
        JPanel source = new JPanel();
        assertTrue(WorkoutEngine.isTypingKey(
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a')));
        assertFalse(WorkoutEngine.isTypingKey(
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED)),
                "a lone Shift is not typing");
        assertFalse(WorkoutEngine.isTypingKey(
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED)));
        assertFalse(WorkoutEngine.isTypingKey(
                new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED)),
                "action keys never counted");
        assertFalse(WorkoutEngine.isTypingKey(
                new KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_A, 'a')));
    }

    @Test
    void completionMessageUsesDisplayUnitsAndEscapesTheName() {
        SessionData session = marathonSession();
        session.name = "Walk <b>bold</b>";
        session.distanceKm = 2.25;
        session.calories = 150.4;
        String imperial = WorkoutEngine.completionMessage(session, UnitSystem.IMPERIAL);
        assertTrue(imperial.contains(" mi,"), "imperial users must not be told km: " + imperial);
        assertFalse(imperial.contains("km"));
        assertTrue(imperial.contains("&lt;b&gt;"), "balloon content is HTML, names must be escaped: " + imperial);
        assertTrue(WorkoutEngine.completionMessage(session, UnitSystem.METRIC).contains(" km,"));
    }
}
