package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkoutMathTest {
    private static UserProfile profile() {
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        return profile;
    }

    @Test
    void secondsForCaloriesRoundsUpAndHasFloor() {
        assertEquals(1200L, WorkoutMath.secondsForCalories(100.0, 5.0));
        // 1 kcal at 100 kcal/min = 0.6 s, rounds up to 1 s minimum
        assertEquals(1L, WorkoutMath.secondsForCalories(1.0, 100.0));
        // 10 kcal at 4.14 kcal/min = 144.9 s, ceil to 145
        assertEquals(145L, WorkoutMath.secondsForCalories(10.0, 4.14));
    }

    @Test
    void stepsForDistanceUsesHeightBasedStepLength() {
        // 170 cm -> step length 0.7038 m -> 1 km = 1421 steps
        assertEquals(1421L, WorkoutMath.stepsForDistance(1.0, 170.0));
    }

    @Test
    void stepLengthHasLowerBound() {
        assertEquals(0.2, WorkoutMath.stepLengthMeters(10.0), 0.0001);
    }

    @Test
    void advanceOneSecondAccumulatesMetrics() {
        SessionData session = new SessionData();
        session.modeId = SessionMode.MARATHON.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 6.0;
        for (int i = 0; i < 3600; i++) {
            WorkoutMath.advanceOneSecond(session, profile());
        }
        assertEquals(3600L, session.elapsedSeconds);
        assertEquals(6.0, session.distanceKm, 0.0001);
        double expectedCalories = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(), 6.0) * 60.0;
        assertEquals(expectedCalories, session.calories, 0.01);
        assertEquals(WorkoutMath.stepsForDistance(6.0, 170.0), session.steps);
    }

    @Test
    void recalcRemainingIsZeroForMarathon() {
        SessionData session = new SessionData();
        session.modeId = SessionMode.MARATHON.name();
        session.remainingSeconds = 500L;
        WorkoutMath.recalcRemaining(session, profile());
        assertEquals(0L, session.remainingSeconds);
    }

    @Test
    void recalcRemainingTracksBurnedCalories() {
        SessionData session = new SessionData();
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 5.0;
        session.targetCalories = 100.0;
        WorkoutMath.recalcRemaining(session, profile());
        long fullRemaining = session.remainingSeconds;
        assertTrue(fullRemaining > 0);

        session.calories = 50.0;
        WorkoutMath.recalcRemaining(session, profile());
        assertTrue(session.remainingSeconds < fullRemaining);
        assertEquals(session.elapsedSeconds + session.remainingSeconds, session.targetSeconds);
    }

    @Test
    void recalcRemainingReachesZeroWhenTargetMet() {
        SessionData session = new SessionData();
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 5.0;
        session.targetCalories = 100.0;
        session.calories = 100.0;
        WorkoutMath.recalcRemaining(session, profile());
        assertEquals(0L, session.remainingSeconds);
    }

    @Test
    void steeperInclineShortensRemainingTime() {
        SessionData flat = new SessionData();
        flat.modeId = SessionMode.CALORIE_BURN.name();
        flat.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        flat.speedKmh = 5.0;
        flat.targetCalories = 200.0;
        WorkoutMath.recalcRemaining(flat, profile());

        SessionData inclined = new SessionData();
        inclined.modeId = SessionMode.CALORIE_BURN.name();
        inclined.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        inclined.speedKmh = 5.0;
        inclined.inclinePercent = 8.0;
        inclined.targetCalories = 200.0;
        WorkoutMath.recalcRemaining(inclined, profile());

        assertTrue(inclined.remainingSeconds < flat.remainingSeconds);
    }

    @Test
    void fasterSpeedShortensRemainingTime() {
        SessionData slow = new SessionData();
        slow.modeId = SessionMode.CALORIE_BURN.name();
        slow.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        slow.speedKmh = 3.0;
        slow.targetCalories = 200.0;
        WorkoutMath.recalcRemaining(slow, profile());

        SessionData fast = new SessionData();
        fast.modeId = SessionMode.CALORIE_BURN.name();
        fast.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        fast.speedKmh = 6.0;
        fast.targetCalories = 200.0;
        WorkoutMath.recalcRemaining(fast, profile());

        assertTrue(fast.remainingSeconds < slow.remainingSeconds);
    }
}
