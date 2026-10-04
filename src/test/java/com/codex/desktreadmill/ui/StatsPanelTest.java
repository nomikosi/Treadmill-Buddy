package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.settings.TreadmillSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsPanelTest {
    @TempDir
    Path directory;

    @Test
    void theTotalsLineBreaksInTwoSoItFitsANarrowToolWindow() throws Exception {
        TreadmillSettings settings = new TreadmillSettings(directory.resolve("sessions.json"));
        SwingUtilities.invokeAndWait(() -> {
            StatsPanel panel = new StatsPanel(settings);
            panel.update(dailyWalks(6), UnitSystem.METRIC);

            JLabel totals = findLabel(panel, "Today");
            assertNotNull(totals);
            String html = totals.getText();
            String[] lines = html.split("<br>");
            assertEquals(2, lines.length, html);
            assertTrue(lines[0].contains("Today") && lines[0].contains("7&nbsp;days"), lines[0]);
            assertTrue(lines[1].contains("All&nbsp;time") && lines[1].contains("Streak"), lines[1]);

            String oneLine = html.replace("<br>", "   |   ").replaceAll("<[^>]+>", "").replace("&nbsp;", " ");
            int oneLineWidth = totals.getFontMetrics(totals.getFont()).stringWidth(oneLine);
            assertTrue(totals.getPreferredSize().width < oneLineWidth * 0.7,
                    "on one line the totals set the panel's width and the tool window scrolled sideways: "
                            + totals.getPreferredSize().width + " vs " + oneLineWidth);
        });
    }

    @Test
    void eachLineBreaksOnlyWhereItSays() {
        assertEquals("<html><center>a&nbsp;&lt;&nbsp;b<br>c&nbsp;&amp;&nbsp;d</center></html>",
                StatsPanel.twoLines("a < b", "c & d"));
    }

    private static List<SessionData> dailyWalks(int days) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<SessionData> sessions = new ArrayList<>();
        for (int ago = 0; ago < days; ago++) {
            SessionData session = new SessionData();
            session.createdMillis = today.minusDays(ago).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
            session.id = String.valueOf(session.createdMillis);
            session.elapsedSeconds = 3_600;
            session.distanceKm = 4.25;
            session.steps = 5_750;
            session.calories = 251.0;
            sessions.add(session);
        }
        return sessions;
    }

    private static JLabel findLabel(Container container, String textPart) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().contains(textPart)) {
                return label;
            }
            if (child instanceof Container nested) {
                JLabel found = findLabel(nested, textPart);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
