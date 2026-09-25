package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Locale-neutral editable numbers; display-only labels may still be localized.
 *
 * <p>"1.5" and "1,5" both read as one and a half, so English and German
 * keyboards work alike. Thousands separators are accepted wherever they can
 * only mean grouping ("1,234,567", "1.234,5", "10 000"), but a lone comma
 * followed by exactly three digits is rejected in decimal fields: "1,500" is
 * 1500 to an English reader and 1.5 to a German one, and guessing turned a
 * "10,000" steps goal into 10 steps. A lone dot stays a decimal point, as in
 * every value the plugin displays ("67.125" inches). Whole-number fields have
 * no decimal reading, so there every separator is grouping.</p>
 */
public final class NumericInput {
    private NumericInput() {
    }

    /** The field's number, or -1 when it is not a usable one: "NaN" parses but passes every range check. */
    public static double parse(String text) {
        String canonical = canonical(text, false);
        if (canonical == null) {
            return -1.0;
        }
        double value = Double.parseDouble(canonical);
        return Double.isFinite(value) ? value : -1.0;
    }

    /** A whole number such as steps, minutes, or an hour, or -1 when the text is not one. */
    public static long parseWholeNumber(String text) {
        String canonical = canonical(text, true);
        if (canonical == null || canonical.length() > 18) {
            return -1L;
        }
        return Long.parseLong(canonical);
    }

    /** True for text such as "1,500" that reads as a different number in different locales. */
    public static boolean isAmbiguous(String text) {
        return ambiguousReadings(text) != null;
    }

    /** The inline error for an ambiguous number, or null when the text is not ambiguous. */
    public static @Nullable String ambiguityMessage(String text) {
        String[] readings = ambiguousReadings(text);
        return readings == null ? null
                : TreadmillBundle.message("error.ambiguousNumber", text.strip(), readings[0], readings[1]);
    }

    public static String format(double value, int decimals) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** The thousands and decimal readings of an ambiguous number, or null when it has only one. */
    private static String @Nullable [] ambiguousReadings(String text) {
        String body = text.strip();
        String sign = "";
        if (body.startsWith("-") || body.startsWith("+")) {
            sign = body.startsWith("-") ? "-" : "";
            body = body.substring(1);
        }
        int comma = body.indexOf(',');
        boolean ambiguous = comma >= 1 && comma <= 3
                && body.length() == comma + 4
                && body.charAt(0) != '0'
                && digits(body, 0, comma)
                && digits(body, comma + 1, body.length());
        if (!ambiguous) {
            return null;
        }
        String whole = body.substring(0, comma);
        String fraction = body.substring(comma + 1);
        String decimal = new BigDecimal(whole + "." + fraction).stripTrailingZeros().toPlainString();
        return new String[]{sign + whole + fraction, sign + decimal};
    }

    /**
     * Rewrites the text as a plain Java number ("-1234.5"), or returns null
     * when it is not a well-formed number in any of the accepted notations.
     */
    private static @Nullable String canonical(String text, boolean wholeNumber) {
        String body = text == null ? "" : text.strip();
        String sign = "";
        if (body.startsWith("-") || body.startsWith("+")) {
            sign = body.startsWith("-") ? "-" : "";
            body = body.substring(1);
        }
        if (body.isEmpty()) {
            return null;
        }
        int lastDot = body.lastIndexOf('.');
        int lastComma = body.lastIndexOf(',');
        int decimalAt = -1;
        if (lastDot >= 0 && lastComma >= 0) {
            // Both appear: the last one is the decimal point, the other groups.
            decimalAt = Math.max(lastDot, lastComma);
        } else if (lastDot >= 0 || lastComma >= 0) {
            int at = Math.max(lastDot, lastComma);
            char separator = body.charAt(at);
            boolean single = body.indexOf(separator) == at;
            if (single && !wholeNumber) {
                if (ambiguousReadings(sign + body) != null) {
                    return null;
                }
                decimalAt = at;
            }
            // Otherwise it repeats ("1,234,567") or cannot be a decimal point: grouping.
        }
        String whole = decimalAt >= 0 ? body.substring(0, decimalAt) : body;
        String fraction = decimalAt >= 0 ? body.substring(decimalAt + 1) : "";
        if (wholeNumber && decimalAt >= 0 || !digits(fraction, 0, fraction.length())) {
            return null;
        }
        String wholeDigits = ungroup(whole);
        if (wholeDigits == null || wholeDigits.isEmpty() && fraction.isEmpty()) {
            return null;
        }
        return sign + (wholeDigits.isEmpty() ? "0" : wholeDigits) + (fraction.isEmpty() ? "" : "." + fraction);
    }

    /**
     * The digits of an integer part, or null when its separators do not form
     * proper thousands groups: one kind of separator, a first group of one to
     * three digits, then groups of exactly three.
     */
    private static @Nullable String ungroup(String whole) {
        char separator = 0;
        for (int i = 0; i < whole.length(); i++) {
            char c = whole.charAt(i);
            if (isDigit(c)) {
                continue;
            }
            if (!isGroupingSeparator(c) || separator != 0 && c != separator) {
                return null;
            }
            separator = c;
        }
        if (separator == 0) {
            return whole;
        }
        String[] groups = whole.split(Pattern.quote(String.valueOf(separator)), -1);
        if (groups[0].isEmpty() || groups[0].length() > 3) {
            return null;
        }
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) {
                return null;
            }
        }
        return String.join("", groups);
    }

    private static boolean isGroupingSeparator(char c) {
        return c == ',' || c == '.' || c == ' ' || c == ' ' || c == ' ' || c == '\'' || c == '’';
    }

    private static boolean digits(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
