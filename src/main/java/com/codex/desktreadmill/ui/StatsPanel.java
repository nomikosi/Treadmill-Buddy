package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.engine.SessionStats;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * The aggregate view under the metric tiles: goal progress bars, the 14-day
 * chart, the activity heatmap, the totals/streak line, and the session status.
 */
public final class StatsPanel extends JPanel {
    private final TreadmillSettings settings;
    private final DailyDistanceChart dailyChart = new DailyDistanceChart();
    private final ActivityHeatmap heatmap = new ActivityHeatmap();
    private final JProgressBar goalProgressBar = new JProgressBar(0, 100);
    private final JProgressBar weeklyGoalProgressBar = new JProgressBar(0, 100);
    private final JBLabel statsLabel = new JBLabel();
    private final JBLabel statusLabel = new JBLabel(TreadmillBundle.message("status.ready"));

    public StatsPanel(TreadmillSettings settings) {
        this.settings = settings;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        statsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statsLabel.setFont(JBUI.Fonts.smallFont());
        statsLabel.setForeground(UIUtil.getContextHelpForeground());
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dailyChart.setToolTipText(TreadmillBundle.message("panel.chart.tooltip"));
        goalProgressBar.setStringPainted(true);
        goalProgressBar.setVisible(false);
        goalProgressBar.setAlignmentX(CENTER_ALIGNMENT);
        weeklyGoalProgressBar.setStringPainted(true);
        weeklyGoalProgressBar.setVisible(false);
        weeklyGoalProgressBar.setAlignmentX(CENTER_ALIGNMENT);
        dailyChart.setAlignmentX(CENTER_ALIGNMENT);
        heatmap.setAlignmentX(CENTER_ALIGNMENT);
        heatmap.setVisible(false);
        statsLabel.setAlignmentX(CENTER_ALIGNMENT);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);
        add(goalProgressBar);
        add(Box.createVerticalStrut(4));
        add(weeklyGoalProgressBar);
        add(Box.createVerticalStrut(4));
        add(dailyChart);
        add(Box.createVerticalStrut(4));
        add(heatmap);
        add(Box.createVerticalStrut(2));
        add(statsLabel);
        add(statusLabel);
    }

    /** The "Running - session name" line under the stats. */
    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    /** Recomputes everything from an already-sorted full session history. */
    public void update(List<SessionData> sessions, UnitSystem currentUnits) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate todayDate = LocalDate.now(zone);
        long startOfToday = todayDate.atStartOfDay(zone).toInstant().toEpochMilli();
        long startOfWeek = todayDate.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals today = SessionStats.totalsSince(sessions, startOfToday);
        SessionStats.Totals week = SessionStats.totalsSince(sessions, startOfWeek);
        SessionStats.Totals allTime = SessionStats.totalsSince(sessions, 0L);
        double[] daily = SessionStats.dailyDistanceKm(sessions, todayDate, zone, 14);
        dailyChart.setData(daily);
        int walkingDays = 0;
        for (double km : daily) {
            if (km > 0) {
                walkingDays++;
            }
        }
        // A single bar reads as a stray box, and today's numbers are already in the stats line.
        dailyChart.setVisible(walkingDays >= 2);

        Map<Long, Double> kmByDay = SessionStats.distanceByEpochDay(sessions, zone);
        heatmap.setData(kmByDay, todayDate, currentUnits.distanceUnit(), currentUnits.distanceFromKm(1.0));
        heatmap.setVisible(kmByDay.size() >= 5);

        int streak = SessionStats.streakDays(sessions, todayDate, zone, settings.getStreakRestDaysPerWeek());
        String streakText = streak > 1 ? "   |   Streak " + streak + " days" : "";
        // Late in the day with no walking yet, a live streak is one quiet
        // evening away from resetting - worth a nudge.
        int riskHour = settings.getStreakRiskHour();
        if (riskHour > 0 && streak >= 2 && today.distanceKm == 0.0 && LocalTime.now().getHour() >= riskHour) {
            streakText += TreadmillBundle.message("panel.streak.atRisk");
        }
        String unit = currentUnits.distanceUnit();
        statsLabel.setText(String.format(
                "Today %.1f %s · %.0f kcal   |   7 days %.1f %s · %.0f kcal   |   All time %.1f %s · %.0f kcal%s",
                currentUnits.distanceFromKm(today.distanceKm), unit, today.calories,
                currentUnits.distanceFromKm(week.distanceKm), unit, week.calories,
                currentUnits.distanceFromKm(allTime.distanceKm), unit, allTime.calories,
                streakText
        ));

        applyGoalProgress(goalProgressBar, TreadmillBundle.message("panel.goal.daily"),
                settings.getDailyGoalType(), settings.getDailyGoalValue(), today, currentUnits);
        GoalType weeklyType = settings.getWeeklyGoalType();
        if (weeklyType == GoalType.NONE || settings.getWeeklyGoalValue() <= 0) {
            weeklyGoalProgressBar.setVisible(false);
        } else {
            long startOfCalendarWeek = todayDate.with(DayOfWeek.MONDAY)
                    .atStartOfDay(zone).toInstant().toEpochMilli();
            SessionStats.Totals thisWeek = SessionStats.totalsSince(sessions, startOfCalendarWeek);
            // "(Mon-Sun)" because the stats line's "7 days" is a rolling window;
            // labeling the difference beats leaving readers to guess.
            applyGoalProgress(weeklyGoalProgressBar, TreadmillBundle.message("panel.goal.weekly"),
                    weeklyType, settings.getWeeklyGoalValue(), thisWeek, currentUnits);
        }

        SessionStats.Records records = SessionStats.records(sessions, zone);
        StringBuilder tooltip = new StringBuilder(String.format(
                "Today: %,d steps in %d sessions | Last 7 days: %,d steps in %d sessions | All time: %,d steps in %d sessions",
                today.steps, today.sessionCount,
                week.steps, week.sessionCount,
                allTime.steps, allTime.sessionCount
        ));
        if (records.longestSessionSeconds > 0) {
            tooltip.append(String.format(
                    " | Records: longest session %s, best day %.1f %s and %,d steps",
                    TimeFormatter.displayTime(records.longestSessionSeconds).getTimeText(),
                    currentUnits.distanceFromKm(records.bestDayDistanceKm), unit,
                    records.bestDaySteps));
        }
        statsLabel.setToolTipText(tooltip.toString());
    }

    private static void applyGoalProgress(
            JProgressBar bar, String label, GoalType goalType, double target,
            SessionStats.Totals totals, UnitSystem currentUnits
    ) {
        if (goalType == GoalType.NONE || target <= 0) {
            bar.setVisible(false);
            return;
        }
        double progress = switch (goalType) {
            case STEPS -> totals.steps;
            case DISTANCE -> totals.distanceKm;
            case CALORIES -> totals.calories;
            case NONE -> 0.0;
        };
        int percent = (int) Math.max(0, Math.min(100, Math.round(progress / target * 100)));
        bar.setVisible(true);
        bar.setValue(percent);
        bar.setString(label + ": "
                + goalType.formatValue(progress, currentUnits)
                + " / " + goalType.formatValue(target, currentUnits)
                + " (" + percent + "%)");
    }
}
