package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkoutEngineTest {
    private TreadmillSettings settings;
    private WorkoutEngine engine;
    private long nowMillis;

    @BeforeEach
    void setUp() {
        settings = new TreadmillSettings();
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        profile.completed = true;
        settings.setProfile(profile);
        settings.setAutoPauseMinutes(0);
        nowMillis = 1_000_000L;
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
    void runningSessionIsPersistedToSettings() {
        engine.startSession(marathonSession());
        nowMillis += 40_000;
        engine.tick();
        SessionData stored = settings.findSession("test");
        assertTrue(stored != null && stored.elapsedSeconds > 0);
    }
}
