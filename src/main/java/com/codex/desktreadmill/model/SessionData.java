package com.codex.desktreadmill.model;

public class SessionData {
    public String id = "";
    public String name = "";
    public String modeId = SessionMode.MARATHON.name();
    public String algorithmId = "ACSM_FLAT";
    public double speedKmh = 3.0;
    public double targetCalories = 0.0;
    public double targetFatKg = 0.0;
    public long targetSeconds = 0L;
    public long elapsedSeconds = 0L;
    public long remainingSeconds = 0L;
    public double distanceKm = 0.0;
    public long steps = 0L;
    public double calories = 0.0;
    public boolean completed = false;

    public SessionData copy() {
        SessionData copy = new SessionData();
        copy.id = id;
        copy.name = name;
        copy.modeId = modeId;
        copy.algorithmId = algorithmId;
        copy.speedKmh = speedKmh;
        copy.targetCalories = targetCalories;
        copy.targetFatKg = targetFatKg;
        copy.targetSeconds = targetSeconds;
        copy.elapsedSeconds = elapsedSeconds;
        copy.remainingSeconds = remainingSeconds;
        copy.distanceKm = distanceKm;
        copy.steps = steps;
        copy.calories = calories;
        copy.completed = completed;
        return copy;
    }
}
