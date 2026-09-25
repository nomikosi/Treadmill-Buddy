package com.codex.desktreadmill.model;

import com.codex.desktreadmill.TreadmillBundle;
import org.jetbrains.annotations.PropertyKey;

import java.util.Locale;

/**
 * Optional daily goal. The goal value is stored metric (steps, km, or kcal
 * depending on the type); {@link UnitSystem} conversion happens in the UI.
 */
public enum GoalType {
    NONE("goal.none"),
    STEPS("goal.steps"),
    DISTANCE("goal.distance"),
    CALORIES("goal.calories");

    private final String labelKey;

    GoalType(@PropertyKey(resourceBundle = TreadmillBundle.BUNDLE) String labelKey) {
        this.labelKey = labelKey;
    }

    /**
     * Formats a metric goal/progress value for display, e.g. "5,000 steps" or
     * "3.1 mi". The number is formatted here, locale-neutral as before, and
     * passed to the bundle as text so its message format cannot regroup it.
     */
    public String formatValue(double value, UnitSystem units) {
        return switch (this) {
            case STEPS -> TreadmillBundle.message("goal.value.steps", String.format(Locale.ROOT, "%,d", Math.round(value)));
            case DISTANCE -> TreadmillBundle.message("goal.value.distance",
                    String.format(Locale.ROOT, "%.1f", units.distanceFromKm(value)), units.distanceUnit());
            case CALORIES -> TreadmillBundle.message("goal.value.kcal", String.format(Locale.ROOT, "%.0f", value));
            case NONE -> "";
        };
    }

    public static GoalType fromId(String id) {
        for (GoalType type : values()) {
            if (type.name().equals(id)) {
                return type;
            }
        }
        return NONE;
    }

    @Override
    public String toString() {
        return TreadmillBundle.message(labelKey);
    }
}
