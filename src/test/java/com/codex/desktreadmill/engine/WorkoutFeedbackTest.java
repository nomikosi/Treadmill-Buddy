package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the engine announces, observed through a recording {@link WorkoutFeedback}. */
class WorkoutFeedbackTest {
    @TempDir
    Path tempDir;

    private final List<String> announced = new ArrayList<>();
    private TreadmillSettings settings;
    private WorkoutEngine engine;
    private long now;

    @BeforeEach
    void setUp() {
        settings = new TreadmillSettings(tempDir.resolve("sessions.json"));
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        profile.completed = true;
        settings.setProfile(profile);
        settings.setAutoPauseMinutes(0);
        now = LocalDate.of(2026, 9, 9).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        engine = new WorkoutEngine(settings, () -> now, new RecordingFeedback());
    }

    @AfterEach
    void tearDown() {
        engine.dispose();
    }

    private SessionData walk(String id) {
        SessionData session = new SessionData();
        session.id = id;
        session.name = id;
        session.createdMillis = now;
        session.modeId = SessionMode.MARATHON.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 5.0;
        return session;
    }

    private void walkFor(long seconds) {
        for (long remaining = seconds; remaining > 0; remaining -= 30) {
            now += Math.min(30, remaining) * 1000;
            engine.tick();
        }
    }

    @Test
    void theDailyGoalIsCelebratedOncePerDay() {
        settings.setDailyGoalType(GoalType.STEPS);
        settings.setDailyGoalValue(50);
        engine.startSession(walk("today"));
        walkFor(90);
        engine.pause();
        engine.resume();
        walkFor(90);
        engine.pause();
        assertEquals(List.of("goal daily STEPS 50.0"), announced);
    }

    @Test
    void theWeeklyGoalIsCelebratedOncePerWeek() {
        settings.setWeeklyGoalType(GoalType.DISTANCE);
        settings.setWeeklyGoalValue(0.05);
        engine.startSession(walk("week"));
        walkFor(60);
        engine.pause();
        engine.resume();
        walkFor(60);
        engine.pause();
        assertEquals(List.of("goal weekly DISTANCE 0.05"), announced);
    }

    @Test
    void aLongestSessionRecordIsAnnounced() {
        SessionData older = walk("older");
        older.elapsedSeconds = 30;
        older.createdMillis = now - 86_400_000L;
        settings.saveSession(older);
        engine.startSession(walk("current"));
        walkFor(60);
        engine.pause();
        assertTrue(announced.contains("record longest 30"), announced.toString());
    }

    @Test
    void intervalSwitchesAnnounceTheBlockThatStarts() {
        SessionData session = walk("intervals");
        session.modeId = SessionMode.INTERVAL.name();
        session.intervalWalkSeconds = 120L;
        session.intervalBreakSeconds = 60L;
        engine.startSession(session);
        walkFor(120);
        walkFor(60);
        assertEquals(List.of("interval break 1", "interval walk 2"), announced);
    }

    @Test
    void aCompletedCountdownIsAnnounced() {
        SessionData session = walk("countdown");
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.targetCalories = 0.5;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        engine.startSession(session);
        walkFor(30);
        assertTrue(announced.contains("completed countdown"), announced.toString());
    }

    @Test
    void theMoveReminderWaitsForTheIntervalAndNeverInterruptsAWalk() {
        settings.setMoveReminderMinutes(1);
        now += 30_000;
        engine.checkMoveReminder();
        assertTrue(announced.isEmpty(), "a minute has not passed yet");
        now += 31_000;
        engine.checkMoveReminder();
        engine.checkMoveReminder();
        assertEquals(List.of("move 1"), announced, "once per interval");

        engine.startSession(walk("walking"));
        now += 120_000;
        engine.checkMoveReminder();
        assertEquals(1, announced.size(), "never while a session runs");
    }

    private final class RecordingFeedback implements WorkoutFeedback {
        @Override
        public void intervalBlockStarted(boolean walking, long minutes) {
            announced.add("interval " + (walking ? "walk " : "break ") + minutes);
        }

        @Override
        public void sessionCompleted(SessionData session, UnitSystem units) {
            announced.add("completed " + session.id);
        }

        @Override
        public void goalReached(boolean weekly, GoalType type, double target, UnitSystem units) {
            announced.add("goal " + (weekly ? "weekly " : "daily ") + type.name() + " " + target);
        }

        @Override
        public void recordsBroken(RecordTracker.BrokenRecords records, SessionData session, UnitSystem units) {
            if (records.longestSession) {
                announced.add("record longest " + records.previousLongestSeconds);
            }
            if (records.dayDistance) {
                announced.add("record distance");
            }
            if (records.daySteps) {
                announced.add("record steps");
            }
        }

        @Override
        public void moveReminderDue(int minutes) {
            announced.add("move " + minutes);
        }
    }
}
