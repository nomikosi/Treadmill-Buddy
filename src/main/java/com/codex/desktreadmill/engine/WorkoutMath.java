package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;

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
        recalcRemaining(session, profile);
    }

    public static void recalcRemaining(SessionData session, UserProfile profile) {
        SessionMode mode = SessionMode.fromId(session.modeId);
        if (mode == SessionMode.MARATHON) {
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
