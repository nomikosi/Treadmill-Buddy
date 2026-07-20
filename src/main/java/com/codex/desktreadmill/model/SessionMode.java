package com.codex.desktreadmill.model;

public enum SessionMode {
    MARATHON("Marathon", "Counts up from 00:00:00 for an open-ended walking or running session."),
    CALORIE_BURN("Calorie burn", "Counts down from the estimated time needed to burn your calorie target."),
    FAT_BURN("KG burn", "Counts down from the estimated time needed to burn a target mass in kg."),
    INTERVAL("Interval walk", "Alternates walk and break blocks (e.g. 25 min walking, 5 min standing) with a chime at each switch.");

    private final String label;
    private final String description;

    SessionMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** True for modes that count down to a target and complete when they reach it. */
    public boolean isCountdown() {
        return this == CALORIE_BURN || this == FAT_BURN;
    }

    public static SessionMode fromId(String id) {
        for (SessionMode mode : values()) {
            if (mode.name().equals(id)) {
                return mode;
            }
        }
        return MARATHON;
    }

    @Override
    public String toString() {
        return label;
    }
}
