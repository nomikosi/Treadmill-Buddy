package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        for (String text : new String[]{"NaN", "Infinity", "1e999", "1e3", "12kg", "1.2.3", "5d", "", ".", "-"}) {
            assertEquals(-1, NumericInput.parse(text), text);
        }
    }

    @Test
    void aLoneCommaBeforeThreeDigitsIsAmbiguousInDecimalFields() {
        // "10,000" used to become 10, and "1,500" kcal became 1.5.
        for (String text : new String[]{"10,000", "1,500", "999,999", " 2,125 "}) {
            assertEquals(-1, NumericInput.parse(text), text);
            assertTrue(NumericInput.isAmbiguous(text), text);
        }
        String message = NumericInput.ambiguityMessage("1,500");
        assertNotNull(message);
        assertTrue(message.contains("1500") && message.contains("1.5"), message);
        assertTrue(NumericInput.ambiguityMessage("10,000").contains(" 10 "),
                "the decimal reading drops trailing zeros: " + NumericInput.ambiguityMessage("10,000"));
    }

    @Test
    void separatorsThatCanOnlyMeanOneThingAreRead() {
        assertEquals(0.5, NumericInput.parse("0,500"), 0.0, "a zero never starts a thousands group");
        assertEquals(1234.567, NumericInput.parse("1234,567"), 0.0, "four leading digits are not a group");
        assertEquals(67.125, NumericInput.parse("67.125"), 0.0, "a lone dot is a decimal point");
        assertEquals(1_234_567, NumericInput.parse("1,234,567"), 0.0);
        assertEquals(1_234_567, NumericInput.parse("1.234.567"), 0.0);
        assertEquals(1234.5, NumericInput.parse("1,234.5"), 0.0);
        assertEquals(1234.5, NumericInput.parse("1.234,5"), 0.0);
        assertEquals(10_000, NumericInput.parse("10 000"), 0.0);
        assertEquals(10_000, NumericInput.parse("10 000"), 0.0);
        assertEquals(10_000, NumericInput.parse("10'000"), 0.0);
        assertEquals(1234.5, NumericInput.parse("1 234,5"), 0.0);
        assertEquals(5, NumericInput.parse("5."), 0.0, "a number still being typed");
        assertEquals(0.5, NumericInput.parse(".5"), 0.0);
        assertEquals(-1.5, NumericInput.parse("-1,5"), 0.0);
        for (String text : new String[]{"1,2,3.4", "1.2,3,4", "1 5", "12,34,567", ",5,"}) {
            assertEquals(-1, NumericInput.parse(text), text);
            assertFalse(NumericInput.isAmbiguous(text), text);
        }
        assertNull(NumericInput.ambiguityMessage("67.125"));
    }

    @Test
    void wholeNumbersTreatEverySeparatorAsGrouping() {
        assertEquals(10_000, NumericInput.parseWholeNumber("10,000"));
        assertEquals(10_000, NumericInput.parseWholeNumber("10.000"));
        assertEquals(10_000, NumericInput.parseWholeNumber("10 000"));
        assertEquals(1_234_567, NumericInput.parseWholeNumber("1,234,567"));
        assertEquals(25, NumericInput.parseWholeNumber(" 25 "));
        for (String text : new String[]{"1,5", "2.5", "1,234.5", "", "abc", "99999999999999999999"}) {
            assertEquals(-1, NumericInput.parseWholeNumber(text), text);
        }
    }
}
