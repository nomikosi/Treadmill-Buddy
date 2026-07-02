package com.codex.desktreadmill.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Minimal bar chart of daily walking distance; the rightmost bar is today.
 */
public final class DailyDistanceChart extends JComponent {
    private static final Color BAR = new JBColor(new Color(0x9BB6D3), new Color(0x4B6478));
    private static final Color BAR_TODAY = new JBColor(new Color(0x3574F0), new Color(0x548AF7));
    private static final Color BASELINE = JBColor.border();

    private double[] dailyKm = new double[0];

    public DailyDistanceChart() {
        setOpaque(false);
        setPreferredSize(new Dimension(200, JBUI.scale(44)));
        setMinimumSize(new Dimension(100, JBUI.scale(30)));
        // BoxLayout stretches components to their maximum size; keep the height fixed.
        setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(44)));
    }

    public void setData(double[] dailyKm) {
        this.dailyKm = dailyKm == null ? new double[0] : dailyKm;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (dailyKm.length == 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();
        int baselineY = height - JBUI.scale(2);
        int gap = JBUI.scale(2);
        int barWidth = Math.max(2, (width - gap * (dailyKm.length - 1)) / dailyKm.length);
        int usableHeight = baselineY - JBUI.scale(4);

        double max = 0.0;
        for (double km : dailyKm) {
            max = Math.max(max, km);
        }

        int x = Math.max(0, (width - (barWidth + gap) * dailyKm.length + gap) / 2);
        for (int i = 0; i < dailyKm.length; i++) {
            boolean today = i == dailyKm.length - 1;
            g.setColor(today ? BAR_TODAY : BAR);
            if (max > 0 && dailyKm[i] > 0) {
                int barHeight = Math.max(JBUI.scale(2), (int) Math.round(dailyKm[i] / max * usableHeight));
                g.fillRoundRect(x, baselineY - barHeight, barWidth, barHeight, 3, 3);
            } else {
                // A flat tick keeps quiet days visible so gaps in the streak read clearly.
                g.fillRect(x, baselineY - 1, barWidth, 1);
            }
            x += barWidth + gap;
        }

        g.setColor(BASELINE);
        g.drawLine(0, baselineY, width, baselineY);
        g.dispose();
    }
}
