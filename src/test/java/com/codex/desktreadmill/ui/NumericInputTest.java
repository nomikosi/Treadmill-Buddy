package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumericInputTest {
    @Test
    void editableNumbersRemainParseableInArabicPersianAndDecimalCommaLocales() {
        Locale original = Locale.getDefault();
        try {
            for (String locale : new String[]{"ar-EG", "fa-IR", "de-DE", "en-US"}) {
                Locale.setDefault(Locale.forLanguageTag(locale));
                assertEquals("154.3", NumericInput.format(154.32, 1));
                assertEquals(154.3, NumericInput.parse(NumericInput.format(154.32, 1)), 0.0);
                assertEquals(0.55, NumericInput.parse(NumericInput.format(0.55, 2)), 0.0);
                MetricInput input = new MetricInput(MetricInput.Quantity.SPEED, 2);
                String text = input.display(3, UnitSystem.IMPERIAL);
                assertEquals("1.86", text);
                assertEquals(3, input.read(text, UnitSystem.IMPERIAL), 0.0);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void commaDecimalsAreAcceptedButPartialAndNonFiniteNumbersAreRejected() {
        assertEquals(1.25, NumericInput.parse(" 1,25 "), 0.0);
        for (String text : new String[]{"NaN", "Infinity", "1e999", "12kg", "1.2.3", ""}) {
            assertEquals(-1, NumericInput.parse(text), text);
        }
    }
}
