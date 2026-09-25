package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UnitSystem;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;

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

    /** An editable speed in display units, blank for a speed that isn't set. */
    static String speed(double kmh, UnitSystem units) {
        return kmh <= 0 ? "" : NumericInput.format(units.speedFromKmh(kmh), 2);
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
