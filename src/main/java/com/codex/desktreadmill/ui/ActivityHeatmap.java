package com.codex.desktreadmill.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub-style calendar heatmap of walking distance. Columns are weeks
 * (oldest left), rows are Monday through Sunday; the rightmost column is the
 * current week. Cell intensity scales with the day's distance.
 */
public final class ActivityHeatmap extends JComponent {
    private static final int WEEKS = 26;
    private static final Color EMPTY = new JBColor(new Color(0xEBEDF0), new Color(0x2B2D30));
    private static final Color[] LEVELS = {
            new JBColor(new Color(0xC6D8F0), new Color(0x2E4460)),
            new JBColor(new Color(0x9BB6D3), new Color(0x3C5A7E)),
            new JBColor(new Color(0x6D97D8), new Color(0x4874A8)),
            new JBColor(new Color(0x3574F0), new Color(0x548AF7)),
    };

    private Map<Long, Double> kmByDay = Map.of();
    private LocalDate today = LocalDate.now();
    private double maxKm;
    private String distanceUnit = "km";
    private double unitFactor = 1.0;

    public ActivityHeatmap() {
        setOpaque(false);
        // Tooltips are computed per cell in getToolTipText; registering any
        // text just turns tooltip support on.
        setToolTipText("");
        int height = JBUI.scale(7 * 9 + 2) + labelHeight();
        setPreferredSize(new Dimension(JBUI.scale(WEEKS * 9), height));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    private static int labelHeight() {
        return JBUI.scale(12);
    }

    public void setData(Map<Long, Double> kmByDay, LocalDate today, String distanceUnit, double unitFactor) {
        this.kmByDay = kmByDay == null ? Map.of() : kmByDay;
        this.today = today;
        this.distanceUnit = distanceUnit;
        this.unitFactor = unitFactor;
        maxKm = 0.0;
        long firstDay = firstMonday().toEpochDay();
        for (Map.Entry<Long, Double> entry : this.kmByDay.entrySet()) {
            if (entry.getKey() >= firstDay) {
                maxKm = Math.max(maxKm, entry.getValue());
            }
        }
        repaint();
    }

    private LocalDate firstMonday() {
        return today.with(DayOfWeek.MONDAY).minusWeeks(WEEKS - 1);
    }

    private int cellSize() {
        return JBUI.scale(9);
    }

    private int gridX() {
        return Math.max(0, (getWidth() - WEEKS * cellSize()) / 2);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cell = cellSize();
        int box = cell - JBUI.scale(1);
        int startX = gridX();
        int gridY = labelHeight();

        // Month initials above the first column whose Monday enters a new month.
        g.setFont(JBUI.Fonts.miniFont());
        g.setColor(UIUtil.getContextHelpForeground());
        int previousMonth = -1;
        for (int week = 0; week < WEEKS; week++) {
            LocalDate monday = firstMonday().plusWeeks(week);
            if (monday.getMonthValue() != previousMonth) {
                // Skip a label crammed into the very first column mid-month;
                // it would sit flush against the next month's label.
                if (previousMonth != -1 || monday.getDayOfMonth() <= 7) {
                    g.drawString(monday.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            startX + week * cell, gridY - JBUI.scale(2));
                }
                previousMonth = monday.getMonthValue();
            }
        }

        LocalDate date = firstMonday();
        for (int week = 0; week < WEEKS; week++) {
            for (int day = 0; day < 7; day++, date = date.plusDays(1)) {
                if (date.isAfter(today)) {
                    continue;
                }
                double km = kmByDay.getOrDefault(date.toEpochDay(), 0.0);
                g.setColor(colorFor(km));
                g.fillRoundRect(startX + week * cell, gridY + day * cell, box, box, 2, 2);
            }
        }
        g.dispose();
    }

    private Color colorFor(double km) {
        if (km <= 0 || maxKm <= 0) {
            return EMPTY;
        }
        int level = (int) Math.min(LEVELS.length - 1, Math.floor(km / maxKm * LEVELS.length));
        return LEVELS[level];
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int cell = cellSize();
        int week = (event.getX() - gridX()) / cell;
        int day = (event.getY() - labelHeight()) / cell;
        if (week < 0 || week >= WEEKS || day < 0 || day >= 7) {
            return null;
        }
        LocalDate date = firstMonday().plusWeeks(week).plusDays(day);
        if (date.isAfter(today)) {
            return null;
        }
        double km = kmByDay.getOrDefault(date.toEpochDay(), 0.0);
        return km > 0
                ? String.format("%s: %.2f %s", date, km * unitFactor, distanceUnit)
                : date + ": no walking";
    }
}
