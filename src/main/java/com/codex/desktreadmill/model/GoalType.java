package com.codex.desktreadmill.model;

import java.util.Locale;

/**
 * Optional daily goal. The goal value is stored metric (steps, km, or kcal
 * depending on the type); {@link UnitSystem} conversion happens in the UI.
 */
public enum GoalType {
    NONE("No daily goal"),
    STEPS("Steps"),
    DISTANCE("Distance"),
    CALORIES("Calories");

    private final String label;

    GoalType(String label) {
        this.label = label;
    }

    /** Formats a metric goal/progress value for display, e.g. "5,000 steps" or "3.1 mi". */
    public String formatValue(double value, UnitSystem units) {
        return switch (this) {
            case STEPS -> String.format(Locale.ROOT, "%,d steps", Math.round(value));
            case DISTANCE -> String.format(Locale.ROOT, "%.1f %s", units.distanceFromKm(value), units.distanceUnit());
            case CALORIES -> String.format(Locale.ROOT, "%.0f kcal", value);
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
        return label;
    }
}
