package com.codex.desktreadmill.calories;

import com.codex.desktreadmill.model.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalorieAlgorithmTest {
    private static UserProfile profile(double weightKg) {
        UserProfile profile = new UserProfile();
        profile.weightKg = weightKg;
        profile.heightCm = 170.0;
        return profile;
    }

    @Test
    void acsmWalkingUsesWalkingEquation() {
        // 5 km/h = 83.333 m/min; VO2 = 0.1 * 83.333 + 3.5 = 11.8333 ml/kg/min
        // kcal/min = 11.8333 * 70 / 1000 * 5 = 4.1417
        double kcal = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0);
        assertEquals(4.1417, kcal, 0.001);
    }

    @Test
    void acsmRunningUsesRunningEquation() {
        // 10 km/h = 166.667 m/min; VO2 = 0.2 * 166.667 + 3.5 = 36.8333 ml/kg/min
        // kcal/min = 36.8333 * 70 / 1000 * 5 = 12.8917
        double kcal = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 10.0);
        assertEquals(12.8917, kcal, 0.001);
    }

    @Test
    void compendiumGrossUsesMetBands() {
        // 4.5 km/h falls in the 3.5 MET band: 3.5 * 3.5 * 70 / 200 = 4.2875
        double kcal = CalorieAlgorithm.COMPENDIUM_MET_GROSS.kcalPerMinute(profile(70), 4.5);
        assertEquals(4.2875, kcal, 0.0001);
    }

    @Test
    void compendiumActiveSubtractsOneMet() {
        double gross = CalorieAlgorithm.COMPENDIUM_MET_GROSS.kcalPerMinute(profile(70), 4.5);
        double active = CalorieAlgorithm.COMPENDIUM_MET_ACTIVE.kcalPerMinute(profile(70), 4.5);
        double oneMet = 1.0 * 3.5 * 70 / 200.0;
        assertEquals(gross - oneMet, active, 0.0001);
    }

    @Test
    void compendiumActiveNeverNegative() {
        double kcal = CalorieAlgorithm.COMPENDIUM_MET_ACTIVE.kcalPerMinute(profile(70), 0.5);
        assertTrue(kcal >= 0.0);
    }

    @Test
    void distanceCostWalkingAndRunning() {
        // Walking: 0.8 kcal/kg/km * 70 kg * 5 km/h / 60 = 4.6667
        assertEquals(4.6667, CalorieAlgorithm.DISTANCE_COST.kcalPerMinute(profile(70), 5.0), 0.001);
        // Running: 1.0 kcal/kg/km * 70 kg * 10 km/h / 60 = 11.6667
        assertEquals(11.6667, CalorieAlgorithm.DISTANCE_COST.kcalPerMinute(profile(70), 10.0), 0.001);
    }

    @Test
    void caloriesForSecondsScalesLinearly() {
        double perMinute = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0);
        assertEquals(perMinute * 10, CalorieAlgorithm.ACSM_FLAT.caloriesForSeconds(profile(70), 5.0, 600), 0.0001);
    }

    @Test
    void acsmInclineRaisesWalkingCost() {
        // 5 km/h at 5% grade: VO2 = 0.1 * 83.333 + 1.8 * 83.333 * 0.05 + 3.5 = 19.3333
        // kcal/min = 19.3333 * 70 / 1000 * 5 = 6.7667
        double kcal = CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0, 5.0);
        assertEquals(6.7667, kcal, 0.001);
        assertTrue(kcal > CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0, 0.0));
    }

    @Test
    void acsmNegativeInclineTreatedAsFlat() {
        assertEquals(
                CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0, 0.0),
                CalorieAlgorithm.ACSM_FLAT.kcalPerMinute(profile(70), 5.0, -3.0),
                0.0001
        );
    }

    @Test
    void metAndDistanceAlgorithmsIgnoreIncline() {
        assertEquals(
                CalorieAlgorithm.COMPENDIUM_MET_GROSS.kcalPerMinute(profile(70), 4.5, 0.0),
                CalorieAlgorithm.COMPENDIUM_MET_GROSS.kcalPerMinute(profile(70), 4.5, 10.0),
                0.0001
        );
        assertEquals(
                CalorieAlgorithm.DISTANCE_COST.kcalPerMinute(profile(70), 5.0, 0.0),
                CalorieAlgorithm.DISTANCE_COST.kcalPerMinute(profile(70), 5.0, 10.0),
                0.0001
        );
    }

    @Test
    void fromIdFallsBackToAcsm() {
        assertEquals(CalorieAlgorithm.ACSM_FLAT, CalorieAlgorithm.fromId("UNKNOWN"));
        assertEquals(CalorieAlgorithm.ACSM_FLAT, CalorieAlgorithm.fromId(null));
        assertEquals(CalorieAlgorithm.DISTANCE_COST, CalorieAlgorithm.fromId("DISTANCE_COST"));
    }
}
