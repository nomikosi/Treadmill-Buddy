package com.codex.desktreadmill.ui;

import org.junit.jupiter.api.Test;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaintedComponentTest {
    @Test
    void screenReadersCanReadTheClockAndFindTheCharts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DigitalClockDisplay clock = new DigitalClockDisplay();
            AccessibleContext context = clock.getAccessibleContext();
            assertNotNull(context, "a plain JComponent has none, so screen readers skipped the clock");
            assertEquals(AccessibleRole.LABEL, context.getAccessibleRole());
            clock.setDisplay("Walk", "00:12:34");
            assertEquals("Workout clock Walk 00:12:34", context.getAccessibleName());
            clock.setDisplay("", "01:02:03");
            assertEquals("Workout clock 01:02:03", context.getAccessibleName());

            assertNotNull(new DailyDistanceChart().getAccessibleContext().getAccessibleName());
            assertTrue(new ActivityHeatmap().getAccessibleContext().getAccessibleName().contains("26"));
        });
    }
}
