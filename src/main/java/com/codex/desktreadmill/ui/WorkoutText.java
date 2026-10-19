package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UnitSystem;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The tool window's metric texts, in the display units the caller passes. */
final class WorkoutText {
    private WorkoutText() {
    }

    static String distance(double km, UnitSystem units) {
        return TreadmillBundle.message("panel.value.distance",
                String.format("%.2f", units.distanceFromKm(km)), units.distanceUnit());
    }

    static String steps(long steps) {
        return TreadmillBundle.message("panel.value.steps", steps);
    }

    static String kcal(double kcal) {
        return TreadmillBundle.message("panel.value.kcal", String.format("%.0f", kcal));
    }

    static String intervalBlocks(long walkMinutes, long breakMinutes) {
        return TreadmillBundle.message("panel.value.intervalBlocks", walkMinutes, breakMinutes);
    }

    static String weight(double kg, UnitSystem units) {
        return TreadmillBundle.message("panel.value.weight",
                String.format("%.2f", units.weightFromKg(kg)), units.weightUnit());
    }

    /**
     * A speed in display units, up to two decimals without trailing zeros, so a
     * breakdown reads "4 km/h" and "4.5 km/h" rather than "4" next to "4.50".
     * Blank for a speed that isn't set.
     */
    static String speed(double kmh, UnitSystem units) {
        return kmh <= 0 ? "" : BigDecimal.valueOf(units.speedFromKmh(kmh))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Multi-speed sessions get a per-speed breakdown tooltip on the distance tile. */
    static @Nullable String segmentsTooltip(SessionData session, UnitSystem units) {
        if (session.segments.size() < 2) {
            return null;
        }
        StringBuilder text = new StringBuilder("<html><b>")
                .append(StringUtil.escapeXmlEntities(TreadmillBundle.message("tooltip.segments.title")))
                .append("</b><br>");
        for (SpeedSegment segment : session.segments) {
            String duration = segment.seconds < 60
                    ? TreadmillBundle.message("tooltip.segments.underMinute")
                    : TreadmillBundle.message("tooltip.segments.minutes", segment.seconds / 60);
            text.append(StringUtil.escapeXmlEntities(TreadmillBundle.message("tooltip.segments.row",
                    duration, speed(segment.speedKmh, units), units.speedUnit())));
            text.append("<br>");
        }
        return text.append("</html>").toString();
    }
}
