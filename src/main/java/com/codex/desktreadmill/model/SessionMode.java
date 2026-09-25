package com.codex.desktreadmill.model;

import com.codex.desktreadmill.TreadmillBundle;
import org.jetbrains.annotations.PropertyKey;

public enum SessionMode {
    MARATHON("mode.marathon", "mode.marathon.description"),
    CALORIE_BURN("mode.calorieBurn", "mode.calorieBurn.description"),
    FAT_BURN("mode.fatBurn", "mode.fatBurn.description"),
    INTERVAL("mode.interval", "mode.interval.description");

    private final String labelKey;
    private final String descriptionKey;

    SessionMode(@PropertyKey(resourceBundle = TreadmillBundle.BUNDLE) String labelKey,
                @PropertyKey(resourceBundle = TreadmillBundle.BUNDLE) String descriptionKey) {
        this.labelKey = labelKey;
        this.descriptionKey = descriptionKey;
    }

    public String getLabel() {
        return TreadmillBundle.message(labelKey);
    }

    public String getDescription() {
        return TreadmillBundle.message(descriptionKey);
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
        return getLabel();
    }
}
