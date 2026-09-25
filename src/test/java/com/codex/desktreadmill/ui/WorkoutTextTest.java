package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UnitSystem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkoutTextTest {
    @Test
    void theSpeedBreakdownOnlyAppearsForMultiSpeedSessionsInDisplayUnits() {
        SessionData session = new SessionData();
        session.segments = new ArrayList<>(List.of(new SpeedSegment(4.828032, 600)));
        assertNull(WorkoutText.segmentsTooltip(session, UnitSystem.METRIC), "one speed needs no breakdown");

        session.segments.add(new SpeedSegment(6.437376, 30));
        String tooltip = WorkoutText.segmentsTooltip(session, UnitSystem.IMPERIAL);
        assertTrue(tooltip.startsWith("<html>") && tooltip.contains("3 mph") && tooltip.contains("4 mph"), tooltip);
        assertTrue(tooltip.contains("10 min") && tooltip.contains("&lt;1 min"), "the row text is escaped: " + tooltip);
    }

    @Test
    void metricTextsFollowTheDisplayUnits() {
        assertEquals("1.61 km", WorkoutText.distance(1.609344, UnitSystem.METRIC).replace(',', '.'));
        assertEquals("1.00 mi", WorkoutText.distance(1.609344, UnitSystem.IMPERIAL).replace(',', '.'));
        assertEquals("", WorkoutText.speed(0, UnitSystem.METRIC), "an unset speed stays blank");
    }
}
