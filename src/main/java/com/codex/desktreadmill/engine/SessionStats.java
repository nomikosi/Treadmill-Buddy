package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SessionStats {
    private SessionStats() {
    }

    public static final class Totals {
        public double distanceKm;
        public long steps;
        public double calories;
        public int sessionCount;
    }

    /**
     * Sums distance, steps, and calories across all sessions created at or after
     * {@code cutoffMillis}. Sessions without a creation timestamp only count when
     * {@code cutoffMillis} is zero (all-time totals).
     */
    public static Totals totalsSince(List<SessionData> sessions, long cutoffMillis) {
        Totals totals = new Totals();
        for (SessionData session : sessions) {
            if (cutoffMillis > 0 && session.createdMillis < cutoffMillis) {
                continue;
            }
            if (session.elapsedSeconds == 0) {
                continue;
            }
            totals.distanceKm += session.distanceKm;
            totals.steps += session.steps;
            totals.calories += session.calories;
            totals.sessionCount++;
        }
        return totals;
    }

    /**
     * Distance walked per day for the last {@code days} days. The last array
     * element is {@code today}; sessions without a timestamp are skipped.
     */
    public static double[] dailyDistanceKm(List<SessionData> sessions, LocalDate today, ZoneId zone, int days) {
        double[] daily = new double[days];
        for (SessionData session : sessions) {
            if (session.createdMillis <= 0 || session.elapsedSeconds == 0) {
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(session.createdMillis).atZone(zone).toLocalDate();
            long daysAgo = ChronoUnit.DAYS.between(date, today);
            if (daysAgo >= 0 && daysAgo < days) {
                daily[days - 1 - (int) daysAgo] += session.distanceKm;
            }
        }
        return daily;
    }

    /**
     * Consecutive walking days ending today. A quiet today doesn't break the
     * streak (the day isn't over yet); the count then starts from yesterday.
     */
    public static int streakDays(double[] dailyDistanceKm) {
        int last = dailyDistanceKm.length - 1;
        if (last < 0) {
            return 0;
        }
        int start = dailyDistanceKm[last] > 0 ? last : last - 1;
        int streak = 0;
        for (int i = start; i >= 0 && dailyDistanceKm[i] > 0; i--) {
            streak++;
        }
        return streak;
    }

    /** Distance walked per day keyed by epoch day, across the full history. */
    public static Map<Long, Double> distanceByEpochDay(List<SessionData> sessions, ZoneId zone) {
        Map<Long, Double> byDay = new HashMap<>();
        for (SessionData session : sessions) {
            if (session.createdMillis <= 0 || session.elapsedSeconds == 0) {
                continue;
            }
            long epochDay = Instant.ofEpochMilli(session.createdMillis).atZone(zone).toLocalDate().toEpochDay();
            byDay.merge(epochDay, session.distanceKm, Double::sum);
        }
        return byDay;
    }

    /**
     * Walking streak over the full history, counting walked days ending today.
     * A quiet today doesn't break the streak (the day isn't over yet). Up to
     * {@code restDaysPerWeek} quiet days per calendar week (Monday-based) are
     * treated as rest days: they don't add to the count, but they don't end it.
     */
    public static int streakDays(List<SessionData> sessions, LocalDate today, ZoneId zone, int restDaysPerWeek) {
        Map<Long, Double> byDay = distanceByEpochDay(sessions, zone);
        LocalDate day = walked(byDay, today) ? today : today.minusDays(1);
        Map<Long, Integer> restUsedByWeek = new HashMap<>();
        int streak = 0;
        for (int guard = 0; guard < 3_660; guard++, day = day.minusDays(1)) {
            if (walked(byDay, day)) {
                streak++;
                continue;
            }
            long weekStart = day.with(DayOfWeek.MONDAY).toEpochDay();
            int used = restUsedByWeek.merge(weekStart, 1, Integer::sum);
            if (used > restDaysPerWeek) {
                break;
            }
        }
        return streak;
    }

    private static boolean walked(Map<Long, Double> byDay, LocalDate day) {
        return byDay.getOrDefault(day.toEpochDay(), 0.0) > 0.0;
    }

    public static final class Records {
        public long longestSessionSeconds;
        public String longestSessionName = "";
        public double bestDayDistanceKm;
        public long bestDayDistanceEpochDay;
        public long bestDaySteps;
    }

    /** Personal records across the full session history. */
    public static Records records(List<SessionData> sessions, ZoneId zone) {
        Records records = new Records();
        Map<Long, Double> kmByDay = new HashMap<>();
        Map<Long, Long> stepsByDay = new HashMap<>();
        for (SessionData session : sessions) {
            if (session.elapsedSeconds == 0) {
                continue;
            }
            if (session.elapsedSeconds > records.longestSessionSeconds) {
                records.longestSessionSeconds = session.elapsedSeconds;
                records.longestSessionName = session.name;
            }
            if (session.createdMillis > 0) {
                long epochDay = Instant.ofEpochMilli(session.createdMillis).atZone(zone).toLocalDate().toEpochDay();
                kmByDay.merge(epochDay, session.distanceKm, Double::sum);
                stepsByDay.merge(epochDay, session.steps, Long::sum);
            }
        }
        for (Map.Entry<Long, Double> entry : kmByDay.entrySet()) {
            if (entry.getValue() > records.bestDayDistanceKm) {
                records.bestDayDistanceKm = entry.getValue();
                records.bestDayDistanceEpochDay = entry.getKey();
            }
        }
        for (Map.Entry<Long, Long> entry : stepsByDay.entrySet()) {
            records.bestDaySteps = Math.max(records.bestDaySteps, entry.getValue());
        }
        return records;
    }
}
