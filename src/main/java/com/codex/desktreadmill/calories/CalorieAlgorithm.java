package com.codex.desktreadmill.calories;

import com.codex.desktreadmill.model.UserProfile;

public enum CalorieAlgorithm {
    ACSM_FLAT(
            "ACSM treadmill",
            "Most commonly used in exercise-science references. Uses ACSM walking/running oxygen-cost equations and supports incline."
    ) {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            double speedMetersPerMinute = speedKmh * 1000.0 / 60.0;
            double grade = Math.max(0.0, inclinePercent) / 100.0;
            double vo2 = speedKmh <= WALK_RUN_TRANSITION_KMH
                    ? 0.1 * speedMetersPerMinute + 1.8 * speedMetersPerMinute * grade + 3.5
                    : 0.2 * speedMetersPerMinute + 0.9 * speedMetersPerMinute * grade + 3.5;
            return vo2 * profile.weightKg / 1000.0 * KCAL_PER_LITER_OXYGEN;
        }
    },
    COMPENDIUM_MET_GROSS(
            "Compendium MET gross",
            "Uses speed bands from the Compendium of Physical Activities and includes resting energy. Ignores incline."
    ) {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            return metForSpeed(speedKmh) * 3.5 * profile.weightKg / 200.0;
        }
    },
    COMPENDIUM_MET_ACTIVE(
            "Compendium MET active",
            "Uses Compendium MET speed bands minus 1 MET, a conservative active-calorie estimate. Ignores incline."
    ) {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            return Math.max(0.0, metForSpeed(speedKmh) - 1.0) * 3.5 * profile.weightKg / 200.0;
        }
    },
    DISTANCE_COST(
            "Distance cost per km",
            "Uses common cost-of-transport estimates: about 0.8 kcal/kg/km walking, 1.0 running. Ignores incline."
    ) {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            double costPerKgKm = speedKmh <= WALK_RUN_TRANSITION_KMH ? 0.8 : 1.0;
            return costPerKgKm * profile.weightKg * speedKmh / 60.0;
        }
    };

    private static final double WALK_RUN_TRANSITION_KMH = 8.0;
    private static final double KCAL_PER_LITER_OXYGEN = 5.0;

    private final String label;
    private final String description;

    CalorieAlgorithm(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public abstract double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent);

    public final double kcalPerMinute(UserProfile profile, double speedKmh) {
        return kcalPerMinute(profile, speedKmh, 0.0);
    }

    public double caloriesForSeconds(UserProfile profile, double speedKmh, long seconds) {
        return kcalPerMinute(profile, speedKmh) * seconds / 60.0;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public static CalorieAlgorithm fromId(String id) {
        for (CalorieAlgorithm algorithm : values()) {
            if (algorithm.name().equals(id)) {
                return algorithm;
            }
        }
        return ACSM_FLAT;
    }

    private static double metForSpeed(double speedKmh) {
        if (speedKmh < 2.0) {
            return 2.0;
        }
        if (speedKmh < 3.2) {
            return 2.8;
        }
        if (speedKmh < 4.0) {
            return 3.0;
        }
        if (speedKmh < 4.8) {
            return 3.5;
        }
        if (speedKmh < 5.6) {
            return 4.3;
        }
        if (speedKmh < 6.4) {
            return 5.0;
        }
        if (speedKmh < 8.0) {
            return 6.3;
        }
        if (speedKmh < 9.7) {
            return 8.3;
        }
        if (speedKmh < 11.3) {
            return 9.8;
        }
        if (speedKmh < 12.9) {
            return 11.0;
        }
        return 12.8;
    }

    @Override
    public String toString() {
        return label;
    }
}
