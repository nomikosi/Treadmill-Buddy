package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;

/** Parsed workout configuration shared by form validation, previews, Start, and Resume. */
public record WorkoutInputs(SessionMode mode, CalorieAlgorithm algorithm, double speedKmh,
                            double inclinePercent, double targetCalories, double targetFatKg,
                            double walkMinutes, double breakMinutes) {
    public enum Field { SPEED, INCLINE, CALORIES, FAT, WALK, BREAK, BURN_RATE, TARGET_LIMIT }

    static WorkoutInputs fromSession(SessionData session) {
        return new WorkoutInputs(SessionMode.fromId(session.modeId), CalorieAlgorithm.fromId(session.algorithmId),
                session.speedKmh, session.inclinePercent, session.targetCalories, session.targetFatKg,
                session.intervalWalkSeconds / 60.0, session.intervalBreakSeconds / 60.0);
    }

    /** Reapplies only deliberate changes to the configuration most recently read from storage. */
    WorkoutInputs mergeChanges(WorkoutInputs baseline, WorkoutInputs latest) {
        return new WorkoutInputs(mode,
                algorithm != baseline.algorithm ? algorithm : latest.algorithm,
                speedKmh != baseline.speedKmh ? speedKmh : latest.speedKmh,
                inclinePercent != baseline.inclinePercent ? inclinePercent : latest.inclinePercent,
                targetCalories != baseline.targetCalories ? targetCalories : latest.targetCalories,
                targetFatKg != baseline.targetFatKg ? targetFatKg : latest.targetFatKg,
                walkMinutes != baseline.walkMinutes ? walkMinutes : latest.walkMinutes,
                breakMinutes != baseline.breakMinutes ? breakMinutes : latest.breakMinutes);
    }

    public boolean valid(Field field, UserProfile profile) {
        return switch (field) {
            case SPEED -> Double.isFinite(speedKmh) && speedKmh > 0 && speedKmh <= 25;
            case INCLINE -> Double.isFinite(inclinePercent) && inclinePercent >= 0 && inclinePercent <= 30;
            case CALORIES -> mode != SessionMode.CALORIE_BURN || positive(targetCalories);
            case FAT -> mode != SessionMode.FAT_BURN || positive(targetFatKg) && positive(calorieTarget());
            case WALK -> mode != SessionMode.INTERVAL || validMinutes(walkMinutes);
            case BREAK -> mode != SessionMode.INTERVAL || validMinutes(breakMinutes);
            case BURN_RATE -> positive(burnRate(profile));
            case TARGET_LIMIT -> mode != SessionMode.FAT_BURN
                    || WorkoutMath.secondsForCalories(calorieTarget(), burnRate(profile)) / 86_400L <= 99;
        };
    }

    public Field invalidField(UserProfile profile) {
        for (Field field : Field.values()) {
            if (!valid(field, profile)) {
                return field;
            }
        }
        return null;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean validMinutes(double value) {
        return Double.isFinite(value) && value >= 1 && value <= 720 && Math.rint(value) == value;
    }

    private double burnRate(UserProfile profile) {
        return algorithm.kcalPerMinute(profile, speedKmh, inclinePercent);
    }

    public double calorieTarget() {
        return mode == SessionMode.FAT_BURN ? targetFatKg * WorkoutMath.FAT_KCAL_PER_KG : targetCalories;
    }

    public SessionData createSession(UserProfile profile) {
        SessionData session = new SessionData();
        applyTo(session, profile);
        return session;
    }

    /** Updates configuration only; recorded activity and identity remain intact. */
    public void applyTo(SessionData session, UserProfile profile) {
        Field invalid = invalidField(profile);
        if (invalid != null) {
            throw new IllegalArgumentException("Invalid workout field: " + invalid);
        }
        applyConfiguration(session, profile);
    }

    /** Also used for merging existing session values, including legacy intervals shorter than a minute. */
    void applyConfiguration(SessionData session, UserProfile profile) {
        session.modeId = mode.name();
        session.algorithmId = algorithm.name();
        session.speedKmh = speedKmh;
        session.inclinePercent = inclinePercent;
        session.targetCalories = mode.isCountdown() ? calorieTarget() : 0;
        session.targetFatKg = mode == SessionMode.FAT_BURN ? targetFatKg : 0;
        if (mode == SessionMode.INTERVAL) {
            long walk = Math.round(walkMinutes * 60);
            long rest = Math.round(breakMinutes * 60);
            if (session.intervalWalkSeconds != walk || session.intervalBreakSeconds != rest) {
                session.intervalWalking = true;
                session.intervalPhaseSeconds = 0;
                session.timerRemainderMillis = 0;
            }
            session.intervalWalkSeconds = walk;
            session.intervalBreakSeconds = rest;
        }
        WorkoutMath.recalcRemaining(session, profile);
    }
}
