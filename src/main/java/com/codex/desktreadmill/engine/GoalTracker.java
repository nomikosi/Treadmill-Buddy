package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.settings.TreadmillSettings;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/** Daily and weekly goals, each celebrated once per day or Monday-based week. */
final class GoalTracker {
    private final TreadmillSettings settings;
    private final WorkoutFeedback feedback;

    GoalTracker(TreadmillSettings settings, WorkoutFeedback feedback) {
        this.settings = settings;
        this.feedback = feedback;
    }

    /** Checks both goals after a save; the history is only copied while a goal is still open. */
    void check(Supplier<List<SessionData>> history, long nowMillis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        List<SessionData> sessions = null;

        GoalType daily = settings.getDailyGoalType();
        double dailyTarget = settings.getDailyGoalValue();
        if (open(daily, dailyTarget) && settings.getLastGoalAchievedDay() != today.toEpochDay()) {
            sessions = history.get();
            if (reached(sessions, daily, dailyTarget, today, zone)) {
                settings.setLastGoalAchievedDay(today.toEpochDay());
                feedback.goalReached(false, daily, dailyTarget, settings.getUnitSystem());
            }
        }

        GoalType weekly = settings.getWeeklyGoalType();
        double weeklyTarget = settings.getWeeklyGoalValue();
        if (open(weekly, weeklyTarget) && settings.getLastWeeklyGoalAchievedWeek() != weekStart.toEpochDay()) {
            if (sessions == null) {
                sessions = history.get();
            }
            if (reached(sessions, weekly, weeklyTarget, weekStart, zone)) {
                settings.setLastWeeklyGoalAchievedWeek(weekStart.toEpochDay());
                feedback.goalReached(true, weekly, weeklyTarget, settings.getUnitSystem());
            }
        }
    }

    private static boolean open(GoalType type, double target) {
        return type != GoalType.NONE && target > 0;
    }

    private static boolean reached(List<SessionData> sessions, GoalType type, double target,
                                   LocalDate since, ZoneId zone) {
        long cutoff = since.atStartOfDay(zone).toInstant().toEpochMilli();
        return SessionStats.goalProgress(type, SessionStats.totalsSince(sessions, cutoff)) >= target;
    }
}
