package com.codex.desktreadmill.model;

import java.util.ArrayList;
import java.util.List;

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
    public long remainingSeconds = 0L;
    public double distanceKm = 0.0;
    public long steps = 0L;
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
        copy.remainingSeconds = remainingSeconds;
        copy.distanceKm = distanceKm;
        copy.steps = steps;
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
        return copy;
    }
}
