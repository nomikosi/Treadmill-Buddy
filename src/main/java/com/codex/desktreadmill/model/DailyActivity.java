package com.codex.desktreadmill.model;

/** The part of a session walked on one day; dateMillis is that day's local midnight. */
public class DailyActivity {
    public long dateMillis;
    public long elapsedSeconds;
    public double distanceKm;
    public long steps;
    public double calories;

    public DailyActivity() {
    }

    public DailyActivity(long dateMillis, long elapsedSeconds, double distanceKm, long steps, double calories) {
        this.dateMillis = dateMillis;
        this.elapsedSeconds = elapsedSeconds;
        this.distanceKm = distanceKm;
        this.steps = steps;
        this.calories = calories;
    }

    public DailyActivity copy() {
        return new DailyActivity(dateMillis, elapsedSeconds, distanceKm, steps, calories);
    }
}
