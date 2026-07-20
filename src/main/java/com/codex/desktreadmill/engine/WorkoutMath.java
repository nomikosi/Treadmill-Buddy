package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UserProfile;

import java.util.List;

public final class WorkoutMath {
    public static final double FAT_KCAL_PER_KG = 7700.0;
    private static final double STEP_LENGTH_HEIGHT_FACTOR = 0.414;
    private static final double MIN_STEP_LENGTH_METERS = 0.2;

    private WorkoutMath() {
    }

    public static long secondsForCalories(double targetCalories, double caloriesPerMinute) {
        return Math.max(1L, (long) Math.ceil(targetCalories / caloriesPerMinute * 60.0));
    }

    public static double stepLengthMeters(double heightCm) {
        return Math.max(MIN_STEP_LENGTH_METERS, heightCm / 100.0 * STEP_LENGTH_HEIGHT_FACTOR);
    }

    public static long stepsForDistance(double distanceKm, double heightCm) {
        return Math.round(distanceKm * 1000.0 / stepLengthMeters(heightCm));
    }

    public static void advanceOneSecond(SessionData session, UserProfile profile) {
        CalorieAlgorithm algorithm = CalorieAlgorithm.fromId(session.algorithmId);
        session.elapsedSeconds++;
        session.distanceKm += session.speedKmh / 3600.0;
        session.steps = stepsForDistance(session.distanceKm, profile.heightCm);
        session.calories += algorithm.kcalPerMinute(profile, session.speedKmh, session.inclinePercent) / 60.0;
        recordSegmentSecond(session);
        recalcRemaining(session, profile);
    }

    /**
     * Advances one second of an interval session: walking blocks accumulate
     * metrics, break blocks only advance the block clock. Returns true when
     * this second finished the current block (time to chime).
     */
    public static boolean advanceIntervalSecond(SessionData session, UserProfile profile) {
        if (session.intervalWalking) {
            advanceOneSecond(session, profile);
        }
        session.intervalPhaseSeconds++;
        if (session.intervalWalkSeconds <= 0 || session.intervalBreakSeconds <= 0) {
            return false;
        }
        long target = session.intervalWalking ? session.intervalWalkSeconds : session.intervalBreakSeconds;
        if (session.intervalPhaseSeconds >= target) {
            session.intervalWalking = !session.intervalWalking;
            session.intervalPhaseSeconds = 0L;
            return true;
        }
        return false;
    }

    /** Seconds left in the current interval block, or 0 when not configured. */
    public static long intervalBlockRemaining(SessionData session) {
        long target = session.intervalWalking ? session.intervalWalkSeconds : session.intervalBreakSeconds;
        return Math.max(0L, target - session.intervalPhaseSeconds);
    }

    private static void recordSegmentSecond(SessionData session) {
        List<SpeedSegment> segments = session.segments;
        if (!segments.isEmpty()) {
            SpeedSegment last = segments.get(segments.size() - 1);
            if (Math.abs(last.speedKmh - session.speedKmh) < 0.001) {
                last.seconds++;
                return;
            }
        }
        segments.add(new SpeedSegment(session.speedKmh, 1L));
    }

    public static void recalcRemaining(SessionData session, UserProfile profile) {
        SessionMode mode = SessionMode.fromId(session.modeId);
        if (!mode.isCountdown()) {
            session.remainingSeconds = 0L;
            return;
        }
        CalorieAlgorithm algorithm = CalorieAlgorithm.fromId(session.algorithmId);
        double caloriesPerMinute = algorithm.kcalPerMinute(profile, session.speedKmh, session.inclinePercent);
        if (caloriesPerMinute <= 0) {
            return;
        }
        double remainingCalories = Math.max(0.0, session.targetCalories - session.calories);
        session.remainingSeconds = remainingCalories == 0.0
                ? 0L
                : secondsForCalories(remainingCalories, caloriesPerMinute);
        session.targetSeconds = session.elapsedSeconds + session.remainingSeconds;
    }
}
