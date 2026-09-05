package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unit-conversion round trip of the settings form. The Swing form itself
 * needs a running IDE; the value resolution behind it does not.
 */
class ProfilePanelTest {
    private static final UnitSystem IMPERIAL = UnitSystem.IMPERIAL;
    private static final UnitSystem METRIC = UnitSystem.METRIC;

    @Test
    void anUntouchedImperialFieldHandsBackTheStoredMetricValueExactly() {
        // 70 kg displays as "154.3" lb; converting that back gives 69.99 kg,
        // which used to mark the page modified the moment it opened and
        // rewrite the stored weight on every OK.
        double kg = ProfilePanel.resolveMetric("154.3", 70.0, IMPERIAL::weightFromKg, IMPERIAL::weightToKg);
        assertEquals(70.0, kg, 0.0);

        double cm = ProfilePanel.resolveMetric("66.9", 170.0, IMPERIAL::heightFromCm, IMPERIAL::heightToCm);
        assertEquals(170.0, cm, 0.0);
    }

    @Test
    void anEditedFieldIsConverted() {
        double kg = ProfilePanel.resolveMetric("160", 70.0, IMPERIAL::weightFromKg, IMPERIAL::weightToKg);
        assertEquals(72.57, kg, 0.01);
    }

    @Test
    void aChangeSmallerThanTheDisplayPrecisionStillCountsOnceItIsVisible() {
        // 154.3 is the display of 70 kg; 154.4 is a real edit of one display step.
        double kg = ProfilePanel.resolveMetric("154.4", 70.0, IMPERIAL::weightFromKg, IMPERIAL::weightToKg);
        assertEquals(IMPERIAL.weightToKg(154.4), kg, 1e-9);
    }

    @Test
    void metricFieldsRoundTripUnchanged() {
        assertEquals(70.0, ProfilePanel.resolveMetric("70", 70.0, METRIC::weightFromKg, METRIC::weightToKg), 0.0);
        assertEquals(72.5, ProfilePanel.resolveMetric("72.5", 70.0, METRIC::weightFromKg, METRIC::weightToKg), 1e-9);
    }

    @Test
    void withoutABaselineTheTextIsSimplyConverted() {
        double kg = ProfilePanel.resolveMetric("154.3", 0.0, IMPERIAL::weightFromKg, IMPERIAL::weightToKg);
        assertEquals(IMPERIAL.weightToKg(154.3), kg, 1e-9);
    }
}
