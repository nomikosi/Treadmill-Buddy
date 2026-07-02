package com.codex.desktreadmill.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitSystemTest {
    @Test
    void metricIsPassThrough() {
        assertEquals(5.0, UnitSystem.METRIC.speedFromKmh(5.0), 0.0001);
        assertEquals(5.0, UnitSystem.METRIC.speedToKmh(5.0), 0.0001);
        assertEquals(70.0, UnitSystem.METRIC.weightFromKg(70.0), 0.0001);
        assertEquals(170.0, UnitSystem.METRIC.heightFromCm(170.0), 0.0001);
        assertEquals(3.0, UnitSystem.METRIC.distanceFromKm(3.0), 0.0001);
    }

    @Test
    void imperialConversions() {
        assertEquals(3.107, UnitSystem.IMPERIAL.speedFromKmh(5.0), 0.001);
        assertEquals(154.32, UnitSystem.IMPERIAL.weightFromKg(70.0), 0.01);
        assertEquals(66.93, UnitSystem.IMPERIAL.heightFromCm(170.0), 0.01);
        assertEquals(6.214, UnitSystem.IMPERIAL.distanceFromKm(10.0), 0.001);
    }

    @Test
    void conversionsRoundTrip() {
        for (UnitSystem units : UnitSystem.values()) {
            assertEquals(5.0, units.speedToKmh(units.speedFromKmh(5.0)), 0.0001);
            assertEquals(70.0, units.weightToKg(units.weightFromKg(70.0)), 0.0001);
            assertEquals(170.0, units.heightToCm(units.heightFromCm(170.0)), 0.0001);
            assertEquals(3.0, units.distanceToKm(units.distanceFromKm(3.0)), 0.0001);
        }
    }

    @Test
    void unitLabels() {
        assertEquals("km/h", UnitSystem.METRIC.speedUnit());
        assertEquals("mph", UnitSystem.IMPERIAL.speedUnit());
        assertEquals("kg", UnitSystem.METRIC.weightUnit());
        assertEquals("lb", UnitSystem.IMPERIAL.weightUnit());
        assertEquals("km", UnitSystem.METRIC.distanceUnit());
        assertEquals("mi", UnitSystem.IMPERIAL.distanceUnit());
        assertEquals("cm", UnitSystem.METRIC.heightUnit());
        assertEquals("in", UnitSystem.IMPERIAL.heightUnit());
    }

    @Test
    void fromIdFallsBackToMetric() {
        assertEquals(UnitSystem.METRIC, UnitSystem.fromId("bogus"));
        assertEquals(UnitSystem.METRIC, UnitSystem.fromId(null));
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromId("IMPERIAL"));
    }

    @Test
    void goalTypeFormatsMetricValues() {
        assertEquals("5,000 steps", GoalType.STEPS.formatValue(5000, UnitSystem.METRIC));
        assertEquals("300 kcal", GoalType.CALORIES.formatValue(300, UnitSystem.METRIC));
        assertEquals("5.0 km", GoalType.DISTANCE.formatValue(5.0, UnitSystem.METRIC));
        assertEquals("3.1 mi", GoalType.DISTANCE.formatValue(5.0, UnitSystem.IMPERIAL));
        assertEquals("", GoalType.NONE.formatValue(5.0, UnitSystem.METRIC));
    }
}
