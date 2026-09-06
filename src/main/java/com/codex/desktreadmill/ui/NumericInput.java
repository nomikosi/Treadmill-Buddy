package com.codex.desktreadmill.ui;

import java.util.Locale;

/** Locale-neutral editable numbers; display-only labels may still be localized. */
public final class NumericInput {
    private NumericInput() {
    }

    public static double parse(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : -1.0;
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    public static String format(double value, int decimals) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
