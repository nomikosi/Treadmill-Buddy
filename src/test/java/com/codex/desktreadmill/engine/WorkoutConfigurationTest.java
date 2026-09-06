package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class WorkoutConfigurationTest {
    @TempDir Path directory;
    private TreadmillSettings settings;
    private WorkoutEngine engine;
    private long now = 1_800_000_000_000L;

    @BeforeEach
    void setUp() {
        settings = new TreadmillSettings(directory.resolve("sessions.json"));
        settings.setAutoPauseMinutes(0);
        engine = new WorkoutEngine(settings, () -> now, false);
    }

    @AfterEach
    void tearDown() {
        engine.dispose();
        settings.dispose();
    }

    private SessionData walk(SessionMode mode) {
        SessionData session = new SessionData();
        session.id = "walk";
        session.name = "Original";
        session.createdMillis = now;
        session.modeId = mode.name();
        session.speedKmh = 3.6;
        session.targetCalories = 300;
        return session;
    }

    private WorkoutInputs inputs(SessionMode mode, double speed, double incline, double calories,
                                 double fat, double walking, double rest) {
        return new WorkoutInputs(mode, CalorieAlgorithm.ACSM_FLAT, speed, incline,
                calories, fat, walking, rest);
    }

    private void pauseTenSecondWalk(SessionMode mode) {
        engine.startSession(walk(mode));
        now += 10_000;
        engine.pause();
    }

    private SessionData extendElsewhere() throws Exception {
        Path file = directory.resolve("sessions.json");
        TreadmillSettings other = new TreadmillSettings(file);
        try {
            SessionData newer = other.findSession("walk");
            newer.elapsedSeconds = 600;
            newer.distanceKm = 0.6;
            newer.calories = 40;
            newer.steps = 900;
            newer.timerRemainderMillis = 750;
            newer.stepRemainder = 0.25;
            newer.speedKmh = 6;
            newer.inclinePercent = 2;
            newer.targetCalories = 500;
            newer.name = "Updated elsewhere";
            newer.segments.clear();
            newer.segments.add(new SpeedSegment(3.6, 600));
            newer.activityDays.clear();
            newer.activityDays.add(new DailyActivity(now, 600, 0.6, 900, 40));
            WorkoutMath.recalcRemaining(newer, other.getProfile());
            assertTrue(other.saveSession(newer));
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
            return newer;
        } finally {
            other.dispose();
        }
    }

    private void assertExtendedProgress(SessionData session) {
        assertEquals(600, session.elapsedSeconds);
        assertEquals(0.6, session.distanceKm);
        assertEquals(40, session.calories);
        assertEquals(900, session.steps);
        assertEquals(750, session.timerRemainderMillis);
        assertEquals(0.25, session.stepRemainder);
        assertEquals(1, session.segments.size());
        assertEquals(600, session.segments.getFirst().seconds);
        assertEquals(1, session.activityDays.size());
        assertEquals(600, session.activityDays.getFirst().elapsedSeconds);
        assertEquals(0.6, session.activityDays.getFirst().distanceKm);
    }

    @Test
    void savingAnUntouchedPausedFormKeepsNewerProgressAndConfiguration() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        extendElsewhere();
        assertTrue(engine.applyInputs(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 300, 0, 25, 5)));
        assertTrue(engine.persistNow());
        SessionData saved = settings.findSession("walk");
        assertExtendedProgress(saved);
        assertEquals(6, saved.speedKmh);
        assertEquals(2, saved.inclinePercent);
        assertEquals(500, saved.targetCalories);
        assertEquals("Updated elsewhere", saved.name);
    }

    @Test
    void resumingAnUntouchedFormKeepsNewerConfiguration() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        extendElsewhere();
        assertTrue(engine.resume(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 300, 0, 25, 5)));
        assertTrue(engine.isRunning());
        assertExtendedProgress(engine.getSession());
        assertEquals(6, engine.getSession().speedKmh);
        assertEquals(500, engine.getSession().targetCalories);
    }

    @Test
    void pausedLiveEditsMergeWithNewerProgressAndUntouchedFields() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        engine.setSpeed(4.5);
        engine.setSessionName("Local name");
        extendElsewhere();
        assertTrue(engine.resume(inputs(SessionMode.CALORIE_BURN, 4.5, 0, 350, 0, 25, 5)));
        SessionData saved = settings.findSession("walk");
        assertExtendedProgress(saved);
        assertEquals(4.5, saved.speedKmh);
        assertEquals("Local name", saved.name);
        assertEquals(350, saved.targetCalories);
        assertEquals(2, saved.inclinePercent);
    }

    @Test
    void anExternalWriteBetweenApplyingTheFormAndSavingKeepsNewProgress() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        assertTrue(engine.applyInputs(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 350, 0, 25, 5)));
        extendElsewhere();
        assertTrue(engine.persistNow());
        assertExtendedProgress(settings.findSession("walk"));
        assertEquals(350, settings.findSession("walk").targetCalories);
        assertEquals(6, settings.findSession("walk").speedKmh);
    }

    @Test
    void shuttingDownWithAPausedNameEditKeepsNewerProgress() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        engine.setSessionName("Local name");
        extendElsewhere();
        engine.dispose();
        TreadmillSettings reopened = new TreadmillSettings(directory.resolve("sessions.json"));
        try {
            assertExtendedProgress(reopened.findSession("walk"));
            assertEquals("Local name", reopened.findSession("walk").name);
        } finally {
            reopened.dispose();
        }
    }

    @Test
    void aStaleFormCannotResumeAWorkoutCompletedElsewhere() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        engine.setSpeed(4.5);
        SessionData completed = extendElsewhere();
        completed.completed = true;
        TreadmillSettings other = new TreadmillSettings(directory.resolve("sessions.json"));
        try {
            assertTrue(other.saveSession(completed));
        } finally {
            other.dispose();
        }
        assertFalse(engine.resume(inputs(SessionMode.CALORIE_BURN, 4.5, 0, 350, 0, 25, 5)));
        assertFalse(engine.isRunning());
        assertTrue(engine.getSession().completed);
        assertExtendedProgress(engine.getSession());
        assertEquals(6, engine.getSession().speedKmh);
    }

    @Test
    void failedSaveKeepsMergedProgressAndEditsForTheRetry() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        engine.setSessionName("Local name");
        extendElsewhere();
        assertTrue(engine.applyInputs(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 350, 0, 25, 5)));
        try (var channel = FileChannel.open(directory.resolve("sessions.json.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertFalse(engine.persistNow());
            assertExtendedProgress(engine.getSession());
            assertEquals(350, engine.getSession().targetCalories);
        }
        assertTrue(engine.persistNow());
        SessionData saved = settings.findSession("walk");
        assertExtendedProgress(saved);
        assertEquals(350, saved.targetCalories);
        assertEquals("Local name", saved.name);
    }

    @Test
    void staleFormEditsDoNotRecreateAnExternallyDeletedWorkout() throws Exception {
        pauseTenSecondWalk(SessionMode.CALORIE_BURN);
        engine.setSpeed(4.5);
        TreadmillSettings other = new TreadmillSettings(directory.resolve("sessions.json"));
        try {
            assertTrue(other.deleteSession("walk"));
            assertFalse(engine.applyInputs(inputs(SessionMode.CALORIE_BURN, 4.5, 0, 350, 0, 25, 5)));
            assertNull(engine.getSession());
            engine.dispose();
            other.reloadSessions();
            assertNull(other.findSession("walk"));
        } finally {
            other.dispose();
        }
    }

    @Test
    void mergingAnIntervalEditPreservesTheOtherLengthAndAllWalkedActivity() throws Exception {
        SessionData interval = walk(SessionMode.INTERVAL);
        interval.intervalWalkSeconds = 1500;
        interval.intervalBreakSeconds = 300;
        engine.startSession(interval);
        now += 10_000;
        engine.pause();
        TreadmillSettings other = new TreadmillSettings(directory.resolve("sessions.json"));
        try {
            SessionData newer = other.findSession("walk");
            newer.intervalWalkSeconds = 1800;
            newer.intervalWalking = false;
            newer.intervalPhaseSeconds = 120;
            newer.elapsedSeconds = 1800;
            newer.distanceKm = 1.8;
            assertTrue(other.saveSession(newer));
            assertTrue(engine.resume(inputs(SessionMode.INTERVAL, 3.6, 0, 0, 0, 25, 2)));
            assertEquals(1800, engine.getSession().intervalWalkSeconds);
            assertEquals(120, engine.getSession().intervalBreakSeconds);
            assertTrue(engine.getSession().intervalWalking);
            assertEquals(0, engine.getSession().intervalPhaseSeconds);
            assertEquals(1800, engine.getSession().elapsedSeconds);
            assertEquals(1.8, engine.getSession().distanceKm);
        } finally {
            other.dispose();
        }
    }

    @Test
    void mergingOnlySpeedKeepsLegacyIntervalLengthsAndThePausedBreak() throws Exception {
        SessionData interval = walk(SessionMode.INTERVAL);
        interval.intervalWalkSeconds = 30;
        interval.intervalBreakSeconds = 20;
        engine.startSession(interval);
        engine.pause();
        engine.setSpeed(4.5);
        TreadmillSettings other = new TreadmillSettings(directory.resolve("sessions.json"));
        try {
            SessionData newer = other.findSession("walk");
            newer.elapsedSeconds = 30;
            newer.intervalWalking = false;
            newer.intervalPhaseSeconds = 10;
            newer.timerRemainderMillis = 500;
            assertTrue(other.saveSession(newer));
            engine.resume();
            assertEquals(4.5, engine.getSession().speedKmh);
            assertEquals(30, engine.getSession().elapsedSeconds);
            assertEquals(30, engine.getSession().intervalWalkSeconds);
            assertEquals(20, engine.getSession().intervalBreakSeconds);
            assertFalse(engine.getSession().intervalWalking);
            assertEquals(10, engine.getSession().intervalPhaseSeconds);
            assertEquals(500, engine.getSession().timerRemainderMillis);
        } finally {
            other.dispose();
        }
    }

    @Test
    void speedChangeSettlesDelayedTimeAtThePreviousSpeed() {
        engine.startSession(walk(SessionMode.MARATHON));
        now += 10_000;
        engine.setSpeed(7.2);
        now += 1000;
        engine.pause();
        SessionData result = engine.getSession();
        assertEquals(11, result.elapsedSeconds);
        assertEquals(0.012, result.distanceKm, 1e-12);
        assertEquals(2, result.segments.size());
        assertEquals(10, result.segments.getFirst().seconds);
        assertEquals(3.6, result.segments.getFirst().speedKmh);
        assertEquals(1, result.segments.getLast().seconds);
    }

    @Test
    void inclineChangeUsesTheOldBurnRateForPendingTime() {
        engine.startSession(walk(SessionMode.MARATHON));
        double before = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(settings.getProfile(), 3.6, 0);
        double after = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(settings.getProfile(), 3.6, 10);
        now += 10_000;
        engine.setIncline(10);
        now += 1000;
        engine.pause();
        assertEquals((before * 10 + after) / 60, engine.getSession().calories, 1e-12);
    }

    @Test
    void algorithmChangeUsesTheOldModelForPendingTime() {
        engine.startSession(walk(SessionMode.MARATHON));
        double before = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(settings.getProfile(), 3.6, 0);
        double after = CalorieAlgorithm.DISTANCE_COST.kcalPerMinute(settings.getProfile(), 3.6, 0);
        now += 10_000;
        engine.setAlgorithm(CalorieAlgorithm.DISTANCE_COST);
        now += 1000;
        engine.pause();
        assertEquals((before * 10 + after) / 60, engine.getSession().calories, 1e-12);
    }

    @Test
    void settlingACompletedCountdownDoesNotChangeItsFinalConfiguration() {
        SessionData session = walk(SessionMode.CALORIE_BURN);
        session.targetCalories = 0.001;
        engine.startSession(session);
        now += 10_000;
        engine.setSpeed(7.2);
        assertTrue(engine.getSession().completed);
        assertFalse(engine.isRunning());
        assertEquals(3.6, engine.getSession().speedKmh);
        assertEquals(1, engine.getSession().elapsedSeconds);
    }

    @Test
    void resetClearsRoundingCarryAndRestartsLikeAFreshWalk() {
        engine.startSession(walk(SessionMode.MARATHON));
        now += 1750;
        engine.pause();
        assertNotEquals(0, engine.getSession().stepRemainder);
        engine.reset();
        SessionData reset = engine.getSession();
        assertEquals(0, reset.stepRemainder);
        assertEquals(0, reset.timerRemainderMillis);
        assertEquals(0, reset.elapsedSeconds);
        assertEquals(0, reset.distanceKm);
        assertEquals(0, reset.steps);
        assertEquals(0, reset.calories);
        assertTrue(reset.segments.isEmpty());
        assertTrue(reset.activityDays.isEmpty());
        assertEquals("walk", reset.id);
        assertEquals("Original", reset.name);
        engine.resume();
        now += 1000;
        engine.pause();
        assertEquals(1, engine.getSession().steps);
    }

    @Test
    void invalidResumeNeverStartsOrPartiallyUpdatesTheSession() {
        for (WorkoutInputs invalid : new WorkoutInputs[]{
                inputs(SessionMode.CALORIE_BURN, -1, 0, 100, 0, 25, 5),
                inputs(SessionMode.CALORIE_BURN, Double.NaN, 0, 100, 0, 25, 5),
                inputs(SessionMode.CALORIE_BURN, 26, 0, 100, 0, 25, 5),
                inputs(SessionMode.CALORIE_BURN, 6, 31, 100, 0, 25, 5),
                inputs(SessionMode.CALORIE_BURN, 6, 0, 0, 0, 25, 5)}) {
            engine.loadSession(walk(SessionMode.CALORIE_BURN));
            assertFalse(engine.resume(invalid));
            assertFalse(engine.isRunning());
            assertEquals(3.6, engine.getSession().speedKmh);
            assertEquals(300, engine.getSession().targetCalories);
        }
    }

    @Test
    void resumeAppliesEditedCaloriesAndKeepsRecordedProgress() {
        SessionData session = walk(SessionMode.CALORIE_BURN);
        session.elapsedSeconds = 600;
        session.calories = 25;
        session.distanceKm = 0.6;
        session.steps = 900;
        engine.loadSession(session);
        assertTrue(engine.resume(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 100, 0, 25, 5)));
        assertTrue(engine.isRunning());
        assertEquals(100, engine.getSession().targetCalories);
        assertEquals(25, engine.getSession().calories);
        assertEquals(600, engine.getSession().elapsedSeconds);
        assertEquals(900, engine.getSession().steps);
        assertEquals(0.6, engine.getSession().distanceKm);
        long expected = WorkoutMath.secondsForCalories(75,
                CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(settings.getProfile(), 3.6, 0));
        assertEquals(expected, engine.getSession().remainingSeconds);
    }

    @Test
    void savingEditedFatTargetDoesNotStartTheClock() {
        engine.loadSession(walk(SessionMode.FAT_BURN));
        assertTrue(engine.applyInputs(inputs(SessionMode.FAT_BURN, 3.6, 0, 0, 0.2, 25, 5)));
        assertTrue(engine.persistNow());
        assertFalse(engine.isRunning());
        SessionData saved = settings.findSession("walk");
        assertEquals(0.2, saved.targetFatKg);
        assertEquals(1540, saved.targetCalories);
    }

    @Test
    void reducingTargetBelowBurnedCaloriesCompletesWithoutAnExtraSecond() {
        SessionData session = walk(SessionMode.CALORIE_BURN);
        session.elapsedSeconds = 600;
        session.calories = 125;
        engine.loadSession(session);
        assertTrue(engine.resume(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 100, 0, 25, 5)));
        assertTrue(engine.getSession().completed);
        assertFalse(engine.isRunning());
        assertEquals(600, engine.getSession().elapsedSeconds);
        assertEquals(125, engine.getSession().calories);
    }

    @Test
    void editedIntervalLengthsRestartTheBlockButKeepWalkedTotals() {
        SessionData session = walk(SessionMode.INTERVAL);
        session.intervalWalkSeconds = 1500;
        session.intervalBreakSeconds = 300;
        session.intervalWalking = false;
        session.intervalPhaseSeconds = 120;
        session.elapsedSeconds = 1500;
        session.steps = 2100;
        session.timerRemainderMillis = 500;
        engine.loadSession(session);
        assertTrue(engine.resume(inputs(SessionMode.INTERVAL, 3.6, 0, 0, 0, 10, 2)));
        assertEquals(600, engine.getSession().intervalWalkSeconds);
        assertEquals(120, engine.getSession().intervalBreakSeconds);
        assertTrue(engine.getSession().intervalWalking);
        assertEquals(0, engine.getSession().intervalPhaseSeconds);
        assertEquals(0, engine.getSession().timerRemainderMillis);
        assertEquals(1500, engine.getSession().elapsedSeconds);
        assertEquals(2100, engine.getSession().steps);
    }

    @Test
    void unchangedIntervalConfigurationPreservesThePausedBreak() {
        SessionData session = walk(SessionMode.INTERVAL);
        session.intervalWalkSeconds = 1500;
        session.intervalBreakSeconds = 300;
        session.intervalWalking = false;
        session.intervalPhaseSeconds = 120;
        session.timerRemainderMillis = 500;
        engine.loadSession(session);
        assertTrue(engine.resume(inputs(SessionMode.INTERVAL, 3.6, 0, 0, 0, 25, 5)));
        assertFalse(engine.getSession().intervalWalking);
        assertEquals(120, engine.getSession().intervalPhaseSeconds);
        assertEquals(500, engine.getSession().timerRemainderMillis);
    }

    @Test
    void runningSessionRejectsTargetEdits() {
        engine.startSession(walk(SessionMode.CALORIE_BURN));
        assertFalse(engine.applyInputs(inputs(SessionMode.CALORIE_BURN, 3.6, 0, 100, 0, 25, 5)));
        assertEquals(300, engine.getSession().targetCalories);
    }

    @Test
    void creationAndPreviewUseTheSameIntervalValidation() {
        for (double minutes : new double[]{0.5, 1.5, 721, Double.NaN}) {
            WorkoutInputs invalid = inputs(SessionMode.INTERVAL, 3.6, 0, 0, 0, minutes, 5);
            assertEquals(WorkoutInputs.Field.WALK, invalid.invalidField(settings.getProfile()));
            assertThrows(IllegalArgumentException.class, () -> invalid.createSession(settings.getProfile()));
        }
        WorkoutInputs valid = inputs(SessionMode.INTERVAL, 3.6, 0, 0, 0, 25, 5);
        assertNull(valid.invalidField(settings.getProfile()));
        assertEquals(1500, WorkoutMath.displaySeconds(valid.createSession(settings.getProfile())));
    }

    @Test
    void importedUnknownModeCanResumeUsingTheExistingMarathonFallback() {
        SessionData session = walk(SessionMode.MARATHON);
        session.modeId = "foreign-mode";
        session.elapsedSeconds = 60;
        engine.loadSession(session);
        assertTrue(engine.resume(inputs(SessionMode.MARATHON, 3.6, 0, 0, 0, 25, 5)));
        assertEquals(SessionMode.MARATHON.name(), engine.getSession().modeId);
        assertEquals(60, engine.getSession().elapsedSeconds);
    }
}
