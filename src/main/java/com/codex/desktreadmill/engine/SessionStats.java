package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.DailyActivity;
import org.jetbrains.annotations.Nullable;

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
     * Sums activity on or after the cutoff. Legacy sessions have no dated
     * breakdown and retain their original creation-date attribution.
     */
    public static Totals totalsSince(List<SessionData> sessions, long cutoffMillis) {
        Totals totals = new Totals();
        for (SessionData session : sessions) {
            if (session.elapsedSeconds == 0) {
                continue;
            }
            if (cutoffMillis == 0) {
                totals.distanceKm += session.distanceKm;
                totals.steps += session.steps;
                totals.calories += session.calories;
                totals.sessionCount++;
                continue;
            }
            boolean counted = false;
            for (DailyActivity day : activityDays(session)) {
                if (day.dateMillis < cutoffMillis || day.elapsedSeconds == 0) {
                    continue;
                }
                totals.distanceKm += day.distanceKm;
                totals.steps += day.steps;
                totals.calories += day.calories;
                counted = true;
            }
            if (counted) {
                totals.sessionCount++;
            }
        }
        return totals;
    }

    /**
     * Distance walked per day for the last {@code days} days. The last array
     * element is {@code today}; sessions without a timestamp are skipped.
     */
    public static double[] dailyDistanceKm(List<SessionData> sessions, LocalDate today, ZoneId zone, int days) {
        double[] daily = new double[days];
        for (Map.Entry<Long, Double> entry : distanceByEpochDay(sessions, zone).entrySet()) {
            LocalDate date = LocalDate.ofEpochDay(entry.getKey());
            long daysAgo = ChronoUnit.DAYS.between(date, today);
            if (daysAgo >= 0 && daysAgo < days) {
                daily[days - 1 - (int) daysAgo] += entry.getValue();
            }
        }
        return daily;
    }

    /** Distance walked per day keyed by epoch day, across the full history. */
    public static Map<Long, Double> distanceByEpochDay(List<SessionData> sessions, ZoneId zone) {
        Map<Long, Double> byDay = new HashMap<>();
        for (Map.Entry<Long, DayTotals> entry : totalsByDay(sessions, zone).entrySet()) {
            byDay.put(entry.getKey(), entry.getValue().distanceKm);
        }
        return byDay;
    }

    private static List<DailyActivity> activityDays(SessionData session) {
        return session.activityDays.isEmpty()
                ? List.of(new DailyActivity(session.createdMillis, session.elapsedSeconds,
                        session.distanceKm, session.steps, session.calories))
                : session.activityDays;
    }

    private static Map<Long, DayTotals> totalsByDay(List<SessionData> sessions, ZoneId zone) {
        Map<Long, DayTotals> byDay = new HashMap<>();
        for (SessionData session : sessions) {
            if (session.elapsedSeconds == 0) {
                continue;
            }
            for (DailyActivity day : activityDays(session)) {
                if (day.dateMillis <= 0 || day.elapsedSeconds == 0) {
                    continue;
                }
                long epochDay = Instant.ofEpochMilli(day.dateMillis).atZone(zone).toLocalDate().toEpochDay();
                DayTotals totals = byDay.computeIfAbsent(epochDay, ignored -> new DayTotals());
                totals.distanceKm += day.distanceKm;
                totals.steps += day.steps;
            }
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
        for (SessionData session : sessions) {
            if (session.elapsedSeconds == 0) {
                continue;
            }
            if (session.elapsedSeconds > records.longestSessionSeconds) {
                records.longestSessionSeconds = session.elapsedSeconds;
                records.longestSessionName = session.name;
            }
        }
        for (Map.Entry<Long, DayTotals> entry : totalsByDay(sessions, zone).entrySet()) {
            if (entry.getValue().distanceKm > records.bestDayDistanceKm) {
                records.bestDayDistanceKm = entry.getValue().distanceKm;
                records.bestDayDistanceEpochDay = entry.getKey();
            }
            records.bestDaySteps = Math.max(records.bestDaySteps, entry.getValue().steps);
        }
        return records;
    }

    /**
     * Longest session in the history, ignoring one session id. Record detection
     * passes the running session's id: a session compared against a best that
     * already includes itself would "break" its own earlier state.
     */
    public static long longestSessionSeconds(List<SessionData> sessions, @Nullable String excludeId) {
        long longest = 0L;
        for (SessionData session : sessions) {
            if (excludeId != null && excludeId.equals(session.id)) {
                continue;
            }
            longest = Math.max(longest, session.elapsedSeconds);
        }
        return longest;
    }

    public static final class DayTotals {
        public double distanceKm;
        public long steps;
    }

    /**
     * Best single-day distance and step totals, ignoring one day. Record
     * detection passes today, so today is measured against the other days
     * rather than against a best that already counts today's walking.
     *
     * @param excludeEpochDay the day to leave out; pass {@link Long#MIN_VALUE}
     *                        to consider every day (0 and -1 are real days)
     */
    public static DayTotals bestDay(List<SessionData> sessions, ZoneId zone, long excludeEpochDay) {
        DayTotals best = new DayTotals();
        for (Map.Entry<Long, DayTotals> entry : totalsByDay(sessions, zone).entrySet()) {
            if (entry.getKey() == excludeEpochDay) {
                continue;
            }
            best.distanceKm = Math.max(best.distanceKm, entry.getValue().distanceKm);
            best.steps = Math.max(best.steps, entry.getValue().steps);
        }
        return best;
    }
}
