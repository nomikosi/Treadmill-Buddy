package com.codex.desktreadmill.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SessionData {
    public String id = "";
    public String name = "";
    public String modeId = SessionMode.MARATHON.name();
    public String algorithmId = "ACSM_FLAT";
    public double speedKmh = 3.0;
    public double inclinePercent = 0.0;
    public double targetCalories = 0.0;
    public double targetFatKg = 0.0;
    public long targetSeconds = 0L;
    public long elapsedSeconds = 0L;
    /** Fraction of a timer second, retained across pause, load, and IDE restarts. */
    public long timerRemainderMillis = 0L;
    public long remainingSeconds = 0L;
    public double distanceKm = 0.0;
    public long steps = 0L;
    /** Rounding carry for newly estimated steps, in [-0.5, 0.5). */
    public double stepRemainder = 0.0;
    public double calories = 0.0;
    public boolean completed = false;
    public long createdMillis = 0L;
    /** Interval mode: length of one walking block in seconds. */
    public long intervalWalkSeconds = 0L;
    /** Interval mode: length of one break block in seconds. */
    public long intervalBreakSeconds = 0L;
    /** Interval mode: true while in a walking block. */
    public boolean intervalWalking = true;
    /** Interval mode: seconds spent in the current block. */
    public long intervalPhaseSeconds = 0L;
    /** Per-speed breakdown of walked time, appended as the session ticks. */
    public List<SpeedSegment> segments = new ArrayList<>();
    /** Empty for legacy history, whose activity is attributed to createdMillis. */
    public List<DailyActivity> activityDays = new ArrayList<>();

    /**
     * Repairs values that hand-edited or foreign JSON can carry. Gson keeps
     * the field initializers for <em>missing</em> keys but writes an explicit
     * {@code null} through, and a null name later takes down CSV export and the
     * history list for the whole store. Non-finite numbers (a lenient parser
     * accepts {@code NaN}) fall back to their defaults: they would poison every
     * later calculation and make Gson refuse to write the file at all.
     * Returns this for chaining.
     */
    public SessionData sanitize() {
        if (name == null) {
            name = "";
        }
        if (modeId == null) {
            modeId = SessionMode.MARATHON.name();
        }
        if (algorithmId == null) {
            algorithmId = "ACSM_FLAT";
        }
        if (segments == null) {
            segments = new ArrayList<>();
        } else {
            segments.removeIf(Objects::isNull);
            for (SpeedSegment segment : segments) {
                segment.speedKmh = finiteOr(segment.speedKmh, 0.0);
            }
        }
        speedKmh = finiteOr(speedKmh, 3.0);
        timerRemainderMillis = Math.max(0L, Math.min(999L, timerRemainderMillis));
        if (!Double.isFinite(stepRemainder) || stepRemainder < -0.5 || stepRemainder >= 0.5) {
            stepRemainder = 0.0;
        }
        if (activityDays == null) {
            activityDays = new ArrayList<>();
        } else {
            activityDays.removeIf(Objects::isNull);
            for (DailyActivity day : activityDays) {
                day.distanceKm = finiteOr(day.distanceKm, 0.0);
                day.calories = finiteOr(day.calories, 0.0);
            }
        }
        inclinePercent = finiteOr(inclinePercent, 0.0);
        targetCalories = finiteOr(targetCalories, 0.0);
        targetFatKg = finiteOr(targetFatKg, 0.0);
        distanceKm = finiteOr(distanceKm, 0.0);
        calories = finiteOr(calories, 0.0);
        return this;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    /** Clears all accumulated progress while retaining identity and workout configuration. */
    public void resetProgress() {
        elapsedSeconds = 0;
        timerRemainderMillis = 0;
        remainingSeconds = targetSeconds;
        distanceKm = 0;
        steps = 0;
        stepRemainder = 0;
        calories = 0;
        completed = false;
        segments.clear();
        activityDays.clear();
        intervalWalking = true;
        intervalPhaseSeconds = 0;
    }

    public long latestActivityMillis() {
        long latest = createdMillis;
        for (DailyActivity day : activityDays) {
            if (day.elapsedSeconds > 0) {
                latest = Math.max(latest, day.dateMillis);
            }
        }
        return latest;
    }

    public SessionData copy() {
        SessionData copy = new SessionData();
        copy.id = id;
        copy.name = name;
        copy.modeId = modeId;
        copy.algorithmId = algorithmId;
        copy.speedKmh = speedKmh;
        copy.inclinePercent = inclinePercent;
        copy.targetCalories = targetCalories;
        copy.targetFatKg = targetFatKg;
        copy.targetSeconds = targetSeconds;
        copy.elapsedSeconds = elapsedSeconds;
        copy.timerRemainderMillis = timerRemainderMillis;
        copy.remainingSeconds = remainingSeconds;
        copy.distanceKm = distanceKm;
        copy.steps = steps;
        copy.stepRemainder = stepRemainder;
        copy.calories = calories;
        copy.completed = completed;
        copy.createdMillis = createdMillis;
        copy.intervalWalkSeconds = intervalWalkSeconds;
        copy.intervalBreakSeconds = intervalBreakSeconds;
        copy.intervalWalking = intervalWalking;
        copy.intervalPhaseSeconds = intervalPhaseSeconds;
        copy.segments = new ArrayList<>();
        for (SpeedSegment segment : segments) {
            copy.segments.add(segment.copy());
        }
        copy.activityDays = new ArrayList<>();
        for (DailyActivity day : activityDays) {
            copy.activityDays.add(day.copy());
        }
        return copy;
    }
}
