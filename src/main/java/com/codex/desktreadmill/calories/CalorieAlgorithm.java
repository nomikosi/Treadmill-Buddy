package com.codex.desktreadmill.calories;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.model.UserProfile;
import org.jetbrains.annotations.PropertyKey;

public enum CalorieAlgorithm {
    ACSM_FLAT("algorithm.acsm", "algorithm.acsm.description") {
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
    COMPENDIUM_MET_GROSS("algorithm.compendiumGross", "algorithm.compendiumGross.description") {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            return metForSpeed(speedKmh) * 3.5 * profile.weightKg / 200.0;
        }
    },
    COMPENDIUM_MET_ACTIVE("algorithm.compendiumActive", "algorithm.compendiumActive.description") {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            return Math.max(0.0, metForSpeed(speedKmh) - 1.0) * 3.5 * profile.weightKg / 200.0;
        }
    },
    DISTANCE_COST("algorithm.distanceCost", "algorithm.distanceCost.description") {
        @Override
        public double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent) {
            double costPerKgKm = speedKmh <= WALK_RUN_TRANSITION_KMH ? 0.8 : 1.0;
            return costPerKgKm * profile.weightKg * speedKmh / 60.0;
        }
    };

    private static final double WALK_RUN_TRANSITION_KMH = 8.0;
    private static final double KCAL_PER_LITER_OXYGEN = 5.0;

    private final String labelKey;
    private final String descriptionKey;

    CalorieAlgorithm(@PropertyKey(resourceBundle = TreadmillBundle.BUNDLE) String labelKey,
                     @PropertyKey(resourceBundle = TreadmillBundle.BUNDLE) String descriptionKey) {
        this.labelKey = labelKey;
        this.descriptionKey = descriptionKey;
    }

    public abstract double kcalPerMinute(UserProfile profile, double speedKmh, double inclinePercent);

    public final double kcalPerMinute(UserProfile profile, double speedKmh) {
        return kcalPerMinute(profile, speedKmh, 0.0);
    }

    public double caloriesForSeconds(UserProfile profile, double speedKmh, long seconds) {
        return kcalPerMinute(profile, speedKmh) * seconds / 60.0;
    }

    public String getLabel() {
        return TreadmillBundle.message(labelKey);
    }

    public String getDescription() {
        return TreadmillBundle.message(descriptionKey);
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
        return getLabel();
    }
}
