package com.codex.desktreadmill.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public final class DigitalClockDisplay extends JComponent {
    private static final Color BACKGROUND = new Color(238, 243, 247);
    private static final Color SEGMENT_ON = new Color(32, 38, 58);
    private static final Color SEGMENT_OFF = new Color(216, 224, 232);
    private static final Color COLON = new Color(126, 148, 166);

    private String dayPrefix = "";
    private String timeText = "00:00:00";

    public DigitalClockDisplay() {
        setOpaque(true);
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(460, 145));
        setMinimumSize(new Dimension(320, 110));
    }

    public void setDisplay(String dayPrefix, String timeText) {
        this.dayPrefix = dayPrefix == null ? "" : dayPrefix;
        this.timeText = timeText == null ? "00:00:00" : timeText;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, getWidth(), getHeight());

        double baseWidth = dayPrefix.isBlank() ? 405.0 : 485.0;
        double scale = Math.min((getWidth() - 24.0) / baseWidth, (getHeight() - 24.0) / 112.0);
        scale = Math.max(0.62, Math.min(scale, 1.35));
        int digitWidth = (int) Math.round(44 * scale);
        int digitHeight = (int) Math.round(86 * scale);
        int thickness = Math.max(5, (int) Math.round(8 * scale));
        int colonWidth = (int) Math.round(18 * scale);
        int gap = (int) Math.round(8 * scale);

        int totalWidth = measureTimeWidth(digitWidth, colonWidth, gap);
        int dayWidth = 0;
        if (!dayPrefix.isBlank()) {
            Font font = getFont().deriveFont(Font.BOLD, (float) (28 * scale));
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            dayWidth = metrics.stringWidth(dayPrefix) + (int) Math.round(16 * scale);
        }
        int x = Math.max(10, (getWidth() - totalWidth - dayWidth) / 2);
        int y = Math.max(8, (getHeight() - digitHeight) / 2);

        if (!dayPrefix.isBlank()) {
            g.setFont(getFont().deriveFont(Font.BOLD, (float) (28 * scale)));
            g.setColor(SEGMENT_ON);
            FontMetrics metrics = g.getFontMetrics();
            int textY = y + digitHeight - metrics.getDescent() - thickness;
            g.drawString(dayPrefix, x, textY);
            x += dayWidth;
        }

        for (int i = 0; i < timeText.length(); i++) {
            char ch = timeText.charAt(i);
            if (ch == ':') {
                drawColon(g, x, y, colonWidth, digitHeight, thickness);
                x += colonWidth + gap;
            } else if (Character.isDigit(ch)) {
                drawDigit(g, ch - '0', x, y, digitWidth, digitHeight, thickness);
                x += digitWidth + gap;
            }
        }
        g.dispose();
    }

    private int measureTimeWidth(int digitWidth, int colonWidth, int gap) {
        int width = 0;
        for (int i = 0; i < timeText.length(); i++) {
            width += timeText.charAt(i) == ':' ? colonWidth : digitWidth;
            if (i < timeText.length() - 1) {
                width += gap;
            }
        }
        return width;
    }

    private static void drawColon(Graphics2D g, int x, int y, int width, int height, int thickness) {
        int dot = Math.max(6, thickness + 2);
        int cx = x + width / 2 - dot / 2;
        g.setColor(COLON);
        g.fillOval(cx, y + height / 3 - dot / 2, dot, dot);
        g.fillOval(cx, y + height * 2 / 3 - dot / 2, dot, dot);
    }

    private static void drawDigit(Graphics2D g, int digit, int x, int y, int width, int height, int thickness) {
        boolean[][] segments = {
                {true, true, true, true, true, true, false},
                {false, true, true, false, false, false, false},
                {true, true, false, true, true, false, true},
                {true, true, true, true, false, false, true},
                {false, true, true, false, false, true, true},
                {true, false, true, true, false, true, true},
                {true, false, true, true, true, true, true},
                {true, true, true, false, false, false, false},
                {true, true, true, true, true, true, true},
                {true, true, true, true, false, true, true}
        };
        for (int i = 0; i < 7; i++) {
            g.setColor(segments[digit][i] ? SEGMENT_ON : SEGMENT_OFF);
            drawSegment(g, i, x, y, width, height, thickness);
        }
    }

    private static void drawSegment(Graphics2D g, int segment, int x, int y, int width, int height, int thickness) {
        int arc = Math.max(4, thickness / 2);
        int half = height / 2;
        g.setStroke(new BasicStroke(1f));
        switch (segment) {
            case 0:
                g.fillRoundRect(x + thickness, y, width - 2 * thickness, thickness, arc, arc);
                break;
            case 1:
                g.fillRoundRect(x + width - thickness, y + thickness, thickness, half - thickness, arc, arc);
                break;
            case 2:
                g.fillRoundRect(x + width - thickness, y + half, thickness, half - thickness, arc, arc);
                break;
            case 3:
                g.fillRoundRect(x + thickness, y + height - thickness, width - 2 * thickness, thickness, arc, arc);
                break;
            case 4:
                g.fillRoundRect(x, y + half, thickness, half - thickness, arc, arc);
                break;
            case 5:
                g.fillRoundRect(x, y + thickness, thickness, half - thickness, arc, arc);
                break;
            case 6:
                g.fillRoundRect(x + thickness, y + half - thickness / 2, width - 2 * thickness, thickness, arc, arc);
                break;
            default:
                break;
        }
    }
}
