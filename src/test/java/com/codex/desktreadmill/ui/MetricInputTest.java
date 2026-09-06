package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricInputTest {
    @Test
    void smallPositiveValuesNeverDisplayAsZeroAndRetainTheirExactValue() {
        for (MetricInput.Quantity quantity : MetricInput.Quantity.values()) {
            MetricInput input = new MetricInput(quantity, 1);
            for (double metric : new double[]{0.05, 0.003, 0.000001}) {
                for (UnitSystem units : UnitSystem.values()) {
                    String text = input.display(metric, units);
                    assertTrue(NumericInput.parse(text) > 0, quantity + ": " + text);
                    assertEquals(metric, input.read(text, units), 0);
                    assertEquals(-1, input.read("0", units));
                }
            }
        }
    }

    @Test
    void aSmallFatTargetCanBeEditedAfterSwitchingUnits() {
        MetricInput input = new MetricInput(MetricInput.Quantity.WEIGHT, 2);
        String text = input.display(0.003, UnitSystem.METRIC);
        assertEquals(0.003, input.read(text, UnitSystem.METRIC), 0);
        text = input.display(input.read(text, UnitSystem.METRIC), UnitSystem.IMPERIAL);
        assertEquals(0.003, input.read(text, UnitSystem.IMPERIAL), 0);
        assertEquals(UnitSystem.IMPERIAL.weightToKg(0.02), input.read("0.02", UnitSystem.IMPERIAL), 0);
    }

    @Test
    void allMeasurementsRetainPrecisionAcrossUnitSwitches() {
        for (MetricInput.Quantity quantity : MetricInput.Quantity.values()) {
            MetricInput input = new MetricInput(quantity, 1);
            String text = input.display(170.123456, UnitSystem.METRIC);
            for (int i = 0; i < 20; i++) {
                text = input.display(input.read(text, UnitSystem.METRIC), UnitSystem.IMPERIAL);
                assertEquals(170.123456, input.read(text, UnitSystem.IMPERIAL), 0.0);
                text = input.display(input.read(text, UnitSystem.IMPERIAL), UnitSystem.METRIC);
                assertEquals(170.123456, input.read(text, UnitSystem.METRIC), 0.0);
            }
        }
    }

    @Test
    void clearingABaselineMakesTheTextANewValue() {
        MetricInput input = new MetricInput(MetricInput.Quantity.DISTANCE, 1);
        String text = input.display(1, UnitSystem.IMPERIAL);
        assertEquals("0.6", text);
        input.clear();
        assertEquals(UnitSystem.IMPERIAL.distanceToKm(0.6), input.read(text, UnitSystem.IMPERIAL), 0.0);
    }

    @Test
    void unitSwitchAndUntouchedResumeKeepTheExactSpeed() {
        MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
        String text = input.display(3, UnitSystem.METRIC);
        for (int i = 0; i < 20; i++) {
            text = input.display(input.read(text, UnitSystem.METRIC), UnitSystem.IMPERIAL);
            assertEquals("1.86", text);
            assertEquals(3, input.read(text, UnitSystem.IMPERIAL), 0.0);
            text = input.display(input.read(text, UnitSystem.IMPERIAL), UnitSystem.METRIC);
            assertEquals(3, input.read(text, UnitSystem.METRIC), 0.0);
        }
    }

    @Test
    void loadingOrSelectingAPresetPreservesMorePrecisionThanTheFieldDisplays() {
        MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
        for (UnitSystem units : UnitSystem.values()) {
            String text = input.display(3.14159265, units);
            assertEquals(3.14159265, input.read(text, units), 0.0);
        }
    }

    @Test
    void deliberateEditsAreConvertedAndSurviveSubsequentUnitSwitches() {
        MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
        input.display(3, UnitSystem.IMPERIAL);
        double edited = input.read("1.87", UnitSystem.IMPERIAL);
        assertEquals(UnitSystem.IMPERIAL.speedToKmh(1.87), edited, 0.0);
        String metric = input.display(edited, UnitSystem.METRIC);
        assertEquals(edited, input.read(metric, UnitSystem.METRIC), 0.0);
        String imperial = input.display(input.read(metric, UnitSystem.METRIC), UnitSystem.IMPERIAL);
        assertEquals("1.87", imperial);
        assertEquals(edited, input.read(imperial, UnitSystem.IMPERIAL), 0.0);
    }

    @Test
    void equivalentTextDoesNotCountAsAnEdit() {
        MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
        input.display(3, UnitSystem.IMPERIAL);
        assertEquals(3, input.read(" 1,860 ", UnitSystem.IMPERIAL), 0.0);
    }

    @Test
    void invalidTextCannotSilentlyReuseThePreviousValidSpeed() {
        MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
        input.display(3, UnitSystem.IMPERIAL);
        for (String text : new String[]{"", "NaN", "Infinity", "1.86oops", "0", "-1"}) {
            assertEquals(-1, input.read(text, UnitSystem.IMPERIAL), text);
        }
    }
}
