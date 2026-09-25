package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkoutLifecycleTest {
    @TempDir Path directory;
    private TreadmillSettings settings;
    private WorkoutEngine engine;
    private long now;
    private final ZoneId zone = ZoneId.systemDefault();
    private final LocalDate today = LocalDate.of(2026, 9, 7);

    @BeforeEach
    void setUp() {
        now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        settings = new TreadmillSettings(directory.resolve("sessions.json"));
        settings.setAutoPauseMinutes(0);
        engine = new WorkoutEngine(settings, () -> now, WorkoutFeedback.SILENT);
    }

    @AfterEach
    void tearDown() {
        engine.dispose();
        settings.dispose();
    }

    private SessionData walk(String id) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = id;
        session.createdMillis = now;
        session.speedKmh = 3.6; // One meter per second.
        return session;
    }

    private TreadmillSettings otherIde() {
        return new TreadmillSettings(directory.resolve("sessions.json"));
    }

    @Test
    void resumingImportedActivityNeverSubtractsStepsFromToday() {
        SessionData imported = walk("imported");
        imported.createdMillis = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        imported.steps = 4000;
        imported.elapsedSeconds = 600;
        imported.distanceKm = 1;
        settings.restoreSession(imported);
        engine.loadSession(imported);
        engine.resume();
        now += 1000;
        engine.pause();
        SessionData result = engine.getSession();
        assertEquals(4001, result.steps);
        assertTrue(result.activityDays.stream().allMatch(day -> day.steps >= 0));
        assertEquals(result.steps, result.activityDays.stream().mapToLong(day -> day.steps).sum());
    }

    @Test
    void fractionalStepsSurviveAnEngineAndStoreRestart() {
        SessionData session = walk("fractional");
        session.speedKmh = 0.5;
        engine.startSession(session);
        now += 2000;
        engine.pause();
        SessionData before = engine.getSession();
        SessionData expected = before.copy();
        WorkoutMath.advanceOneSecond(expected, settings.getProfile());
        engine.dispose();
        settings.dispose();
        settings = otherIde();
        settings.setAutoPauseMinutes(0);
        engine = new WorkoutEngine(settings, () -> now, WorkoutFeedback.SILENT);
        SessionData persisted = settings.findSession("fractional");
        assertEquals(before.stepRemainder, persisted.stepRemainder);
        engine.loadSession(persisted);
        engine.resume();
        now += 1000;
        engine.pause();
        assertEquals(expected.steps, engine.getSession().steps);
        assertEquals(expected.stepRemainder, engine.getSession().stepRemainder);
    }

    @Test
    void loadingTheCurrentHistoryRowPreservesLiveTimeAndEdits() {
        engine.startSession(walk("current"));
        SessionData row = settings.findSession("current");
        now += 20_750;
        engine.setSessionName("Edited name");
        engine.loadSession(row);
        assertFalse(engine.isRunning());
        assertEquals(20, engine.getSession().elapsedSeconds);
        assertEquals("Edited name", engine.getSession().name);
        engine.resume();
        now += 250;
        engine.pause();
        assertEquals(21, otherIde().findSession("current").elapsedSeconds);
    }

    @Test
    void loadingAnotherSessionSettlesTimeBeforeSwapping() {
        engine.startSession(walk("first"));
        now += 5_750;
        engine.loadSession(walk("second"));
        assertEquals(5, settings.findSession("first").elapsedSeconds);
        assertEquals(750, settings.findSession("first").timerRemainderMillis);
        engine.resume();
        now += 250;
        engine.pause();
        assertEquals(0, engine.getSession().elapsedSeconds, "fractions must not leak between sessions");
    }

    @Test
    void deletingRunningSessionCapturesLatestUndoDataAndNeverResavesIt() {
        engine.startSession(walk("current"));
        now += 10_750;
        WorkoutEngine.Deletion deletion = engine.deleteSessions(List.of("current"));
        assertTrue(deletion.persisted());
        assertEquals(10, deletion.sessions().getFirst().elapsedSeconds);
        assertEquals(750, deletion.sessions().getFirst().timerRemainderMillis);
        assertNull(engine.getSession());
        assertFalse(engine.isRunning());
        assertEquals("", settings.getLastSessionId());
        engine.dispose();
        assertNull(otherIde().findSession("current"));
        assertTrue(engine.undoDeletion(deletion));
        assertEquals(10, otherIde().findSession("current").elapsedSeconds);
        assertEquals("current", settings.getLastSessionId());
    }

    @Test
    void bulkDeleteDiscardsDirtyPausedSessionAndKeepsUnselectedWalks() {
        settings.restoreSession(walk("old"));
        settings.restoreSession(walk("keep"));
        engine.startSession(walk("current"));
        engine.pause();
        engine.setSpeed(5.0);
        engine.deleteSessions(List.of("old", "current"));
        engine.dispose();
        assertNull(otherIde().findSession("old"));
        assertNull(otherIde().findSession("current"));
        assertNotNull(otherIde().findSession("keep"));
    }

    @Test
    void autosavesAreWrittenInTheBackgroundAndAnnouncedOnTheEdt() throws Exception {
        TreadmillSettings background = new TreadmillSettings(directory.resolve("async.json"),
                new ScheduledThreadPoolExecutor(1));
        background.setAutoPauseMinutes(0);
        WorkoutEngine asyncEngine = new WorkoutEngine(background, () -> now, WorkoutFeedback.SILENT);
        try {
            asyncEngine.startSession(walk("autosaved"));
            CountDownLatch announced = new CountDownLatch(1);
            AtomicBoolean onEdt = new AtomicBoolean();
            asyncEngine.addListener(new WorkoutEngine.Listener() {
                @Override
                public void workoutStateChanged() {
                }

                @Override
                public void sessionsPersisted() {
                    onEdt.set(SwingUtilities.isEventDispatchThread());
                    announced.countDown();
                }
            });
            now += 30_000;
            asyncEngine.tick(); // Due for the 30-second autosave.

            assertTrue(announced.await(5, TimeUnit.SECONDS), "listeners hear about the autosave once it is written");
            assertTrue(onEdt.get(), "and they hear about it on the EDT, not the writer thread");
            assertEquals(30, new TreadmillSettings(directory.resolve("async.json"))
                    .findSession("autosaved").elapsedSeconds);
        } finally {
            asyncEngine.dispose();
            background.dispose();
        }
    }

    @Test
    void undoingAnUnrelatedDeletionKeepsAMarkerThatNewCleared() {
        settings.restoreSession(walk("other"));
        engine.startSession(walk("current"));
        engine.pause();
        WorkoutEngine.Deletion deletion = engine.deleteSessions(List.of("other"));
        assertEquals("current", deletion.lastSessionId());
        engine.clearSession();

        engine.undoDeletion(deletion);
        assertEquals("", settings.getLastSessionId(), "New emptied the clock; the undo must not refill it");
        assertNotNull(otherIde().findSession("other"));
    }

    @Test
    void undoDeletionDoesNotChangeANewerLastSession() {
        engine.startSession(walk("deleted"));
        WorkoutEngine.Deletion deletion = engine.deleteSessions(List.of("deleted"));
        engine.startSession(walk("new"));
        engine.undoDeletion(deletion);
        assertEquals("new", settings.getLastSessionId());
        assertEquals("new", engine.getSession().id);
        assertNotNull(otherIde().findSession("deleted"));
    }

    @Test
    void failedDeletionRemainsPendingWithoutResurrectingTheClock() throws Exception {
        engine.startSession(walk("current"));
        try (var channel = FileChannel.open(directory.resolve("sessions.json.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertFalse(engine.deleteSessions(List.of("current")).persisted());
            assertNull(engine.getSession());
            engine.dispose();
            assertNotNull(otherIde().findSession("current"), "disk is unchanged while locked");
        }
        settings.dispose(); // Flushes pending deletions once the lock is available.
        assertNull(otherIde().findSession("current"));
    }

    @Test
    void focusRefreshDiscardsSessionDeletedElsewhere() {
        engine.startSession(walk("current"));
        engine.pause();
        otherIde().deleteSession("current");
        engine.reloadSessions();
        assertNull(engine.getSession());
        engine.resume();
        assertFalse(engine.isRunning());
        assertNull(otherIde().findSession("current"));
    }

    @Test
    void resumeChecksExternalDeletionEvenWithoutAFocusEvent() {
        engine.startSession(walk("current"));
        engine.pause();
        otherIde().deleteSession("current");
        engine.resume();
        assertNull(engine.getSession());
        assertNull(otherIde().findSession("current"));
    }

    @Test
    void saveDoesNotRecreateAnExternallyDeletedSession() {
        engine.startSession(walk("current"));
        engine.pause();
        otherIde().deleteSession("current");
        engine.setSessionName("Unsaved local edit");
        assertFalse(engine.persistNow());
        assertNull(engine.getSession());
        assertNull(otherIde().findSession("current"));
    }

    @Test
    void reloadKeepsUnsavedSessionsAndHistoryThatCouldNotBeRead() throws Exception {
        engine.loadSession(walk("draft"));
        engine.reloadSessions();
        assertNotNull(engine.getSession(), "a draft missing from history was not deleted");
        engine.resume();
        now += 1_000;
        engine.pause();
        Files.writeString(directory.resolve("sessions.json"), "unreadable history");
        engine.reloadSessions();
        assertEquals(1, engine.getSession().elapsedSeconds);
    }

    @Test
    void storedMetadataChangesAreAdoptedWithoutElapsedTimeChanging() {
        engine.startSession(walk("current"));
        engine.pause();
        TreadmillSettings other = otherIde();
        SessionData changed = other.findSession("current");
        changed.name = "Changed in another IDE";
        changed.speedKmh = 6;
        other.saveSession(changed);
        engine.resume();
        assertEquals(changed.name, engine.getSession().name);
        assertEquals(6, engine.getSession().speedKmh);
    }

    @Test
    void partialSecondsAccumulateAcrossRepeatedPausesWithoutCountingPausedTime() {
        engine.startSession(walk("current"));
        for (int i = 0; i < 4; i++) {
            now += 750;
            if (i % 2 == 0) {
                engine.tick();
            }
            engine.pause();
            now += 600_000;
            engine.resume();
        }
        engine.pause();
        assertEquals(3, engine.getSession().elapsedSeconds);
        assertEquals(0, engine.getSession().timerRemainderMillis);
        assertEquals(0.003, engine.getSession().distanceKm, 1e-10);
    }

    @Test
    void partialSecondsSurviveRestartAndResetClearsThem() {
        engine.startSession(walk("current"));
        now += 750;
        engine.pause();
        engine.dispose();
        settings = otherIde();
        engine = new WorkoutEngine(settings, () -> now, WorkoutFeedback.SILENT);
        engine.loadSession(settings.findSession("current"));
        engine.resume();
        now += 750;
        engine.pause();
        assertEquals(1, engine.getSession().elapsedSeconds);
        assertEquals(500, engine.getSession().timerRemainderMillis);
        engine.reset();
        engine.resume();
        now += 500;
        engine.pause();
        assertEquals(0, engine.getSession().elapsedSeconds);
        assertTrue(engine.getSession().activityDays.isEmpty());
    }

    @Test
    void sleepKeepsThePreSleepFractionWithoutCreditingTheSleep() {
        engine.startSession(walk("current"));
        now += 750;
        engine.tick();
        now += 600_000;
        engine.tick();
        assertTrue(engine.isAutoPaused());
        engine.resume();
        now += 250;
        engine.pause();
        assertEquals(1, engine.getSession().elapsedSeconds);
    }

    @Test
    void saveSettlesTimeAndResumeWhileRunningDoesNotResetTheTimer() {
        engine.startSession(walk("current"));
        now += 1_750;
        engine.resume();
        engine.persistNow();
        assertEquals(1, otherIde().findSession("current").elapsedSeconds);
        assertEquals(750, otherIde().findSession("current").timerRemainderMillis);
    }

    @Test
    void walkingAcrossMidnightSplitsDailyAndWeeklyActivity() {
        long midnight = today.atStartOfDay(zone).toInstant().toEpochMilli();
        now = midnight - 2_000;
        engine.startSession(walk("overnight"));
        now += 5_000;
        engine.pause();
        List<SessionData> history = settings.getSessions();
        assertEquals(5, engine.getSession().elapsedSeconds);
        assertEquals(0.003, SessionStats.totalsSince(history, midnight).distanceKm, 1e-10);
        assertEquals(0.005, SessionStats.totalsSince(history, 0).distanceKm, 1e-10);
        assertArrayEquals(new double[]{0.002, 0.003}, SessionStats.dailyDistanceKm(history, today, zone, 2), 1e-10);
        assertEquals(2, SessionStats.streakDays(history, today, zone, 0));
        assertEquals(0.003, SessionStats.records(history, zone).bestDayDistanceKm, 1e-10);
        assertEquals(0.002, SessionStats.bestDay(history, zone, today.toEpochDay()).distanceKm, 1e-10);
        assertEquals(1, SessionStats.totalsSince(history, midnight).sessionCount);
    }

    @Test
    void pauseCanCompleteACountdownBeforeTheNextTimerEvent() {
        SessionData countdown = walk("countdown");
        countdown.modeId = SessionMode.CALORIE_BURN.name();
        countdown.targetCalories = 0.001;
        engine.startSession(countdown);
        now += 1_750;
        engine.pause();
        assertTrue(engine.getSession().completed);
        assertFalse(engine.isRunning());
        assertEquals(1, engine.getSession().elapsedSeconds);
        assertEquals(0, engine.getSession().timerRemainderMillis);
        assertEquals(1, engine.getSession().activityDays.getFirst().elapsedSeconds);
    }

    @Test
    void resumingLegacySessionPreservesOldTotalsAndCreditsToday() {
        SessionData legacy = walk("legacy");
        legacy.createdMillis -= 86_400_000;
        legacy.elapsedSeconds = 1_000;
        legacy.distanceKm = 1;
        legacy.steps = WorkoutMath.stepsForDistance(1, settings.getProfile().heightCm);
        legacy.calories = 50;
        settings.restoreSession(legacy);
        engine.loadSession(legacy);
        engine.resume();
        now += 10_000;
        engine.pause();
        long midnight = today.atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals currentDay = SessionStats.totalsSince(settings.getSessions(), midnight);
        assertEquals(0.010, currentDay.distanceKm, 1e-10);
        assertTrue(currentDay.steps > 0);
        assertTrue(currentDay.calories > 0);
        assertEquals(1.010, SessionStats.totalsSince(settings.getSessions(), 0).distanceKm, 1e-10);
        assertEquals(50 + currentDay.calories, engine.getSession().calories, 1e-10);
        assertEquals(2, engine.getSession().activityDays.size());
    }

    @Test
    void intervalBreakAcrossMidnightDoesNotCreateWalkingActivity() {
        long midnight = today.atStartOfDay(zone).toInstant().toEpochMilli();
        now = midnight - 3_000;
        SessionData interval = walk("interval");
        interval.modeId = SessionMode.INTERVAL.name();
        interval.intervalWalkSeconds = 2;
        interval.intervalBreakSeconds = 4;
        engine.startSession(interval);
        now += 5_000;
        engine.pause();
        assertEquals(0, SessionStats.totalsSince(settings.getSessions(), midnight).distanceKm);
        engine.resume();
        now += 3_000;
        engine.pause();
        assertEquals(0.002, SessionStats.totalsSince(settings.getSessions(), midnight).distanceKm, 1e-10);
        assertEquals(4, engine.getSession().elapsedSeconds);
    }
}
