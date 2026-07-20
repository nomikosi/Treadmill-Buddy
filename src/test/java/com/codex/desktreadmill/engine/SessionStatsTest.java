package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SessionStatsTest {
    private static SessionData session(long createdMillis, double km, long steps, double kcal) {
        SessionData session = new SessionData();
        session.createdMillis = createdMillis;
        session.distanceKm = km;
        session.steps = steps;
        session.calories = kcal;
        session.elapsedSeconds = 60L;
        return session;
    }

    @Test
    void cutoffFiltersOlderSessions() {
        List<SessionData> sessions = List.of(
                session(1_000L, 1.0, 100, 10.0),
                session(2_000L, 2.0, 200, 20.0),
                session(3_000L, 3.0, 300, 30.0)
        );
        SessionStats.Totals totals = SessionStats.totalsSince(sessions, 2_000L);
        assertEquals(5.0, totals.distanceKm, 0.0001);
        assertEquals(500L, totals.steps);
        assertEquals(50.0, totals.calories, 0.0001);
        assertEquals(2, totals.sessionCount);
    }

    @Test
    void zeroCutoffIncludesEverythingEvenWithoutTimestamp() {
        List<SessionData> sessions = List.of(
                session(0L, 1.0, 100, 10.0),
                session(5_000L, 2.0, 200, 20.0)
        );
        SessionStats.Totals totals = SessionStats.totalsSince(sessions, 0L);
        assertEquals(3.0, totals.distanceKm, 0.0001);
        assertEquals(2, totals.sessionCount);
    }

    @Test
    void untimestampedSessionsExcludedFromCutoffTotals() {
        List<SessionData> sessions = List.of(
                session(0L, 1.0, 100, 10.0),
                session(5_000L, 2.0, 200, 20.0)
        );
        SessionStats.Totals totals = SessionStats.totalsSince(sessions, 1_000L);
        assertEquals(2.0, totals.distanceKm, 0.0001);
        assertEquals(1, totals.sessionCount);
    }

    @Test
    void neverStartedSessionsAreIgnored() {
        SessionData empty = session(5_000L, 0.0, 0, 0.0);
        empty.elapsedSeconds = 0L;
        SessionStats.Totals totals = SessionStats.totalsSince(List.of(empty), 0L);
        assertEquals(0, totals.sessionCount);
    }

    private static long epochMillisAtStartOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    @Test
    void dailyDistanceBucketsByDayWithTodayLast() {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate today = LocalDate.of(2026, 7, 2);
        List<SessionData> sessions = List.of(
                session(epochMillisAtStartOfDay(today, zone) + 3_600_000L, 2.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(1), zone), 1.5, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(1), zone) + 60_000L, 0.5, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(3), zone), 3.0, 100, 10.0),
                // Outside the window and in the future: both ignored.
                session(epochMillisAtStartOfDay(today.minusDays(10), zone), 9.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.plusDays(1), zone), 9.0, 100, 10.0)
        );
        double[] daily = SessionStats.dailyDistanceKm(sessions, today, zone, 4);
        assertArrayEquals(new double[]{3.0, 0.0, 2.0, 2.0}, daily, 0.0001);
    }

    @Test
    void fullHistoryStreakCountsWalkedDays() {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate today = LocalDate.of(2026, 7, 15);
        List<SessionData> sessions = List.of(
                session(epochMillisAtStartOfDay(today, zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(1), zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(2), zone), 1.0, 100, 10.0)
        );
        assertEquals(3, SessionStats.streakDays(sessions, today, zone, 0));
    }

    @Test
    void restDayKeepsStreakAliveWithoutCounting() {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate today = LocalDate.of(2026, 7, 15);
        // Walked today, gap yesterday, walked the two days before.
        List<SessionData> sessions = List.of(
                session(epochMillisAtStartOfDay(today, zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(2), zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(3), zone), 1.0, 100, 10.0)
        );
        assertEquals(1, SessionStats.streakDays(sessions, today, zone, 0));
        assertEquals(3, SessionStats.streakDays(sessions, today, zone, 1));
    }

    @Test
    void secondGapInSameWeekBreaksStreakWithOneRestDay() {
        ZoneId zone = ZoneOffset.UTC;
        // A Friday: two gaps in the same Monday-based week.
        LocalDate today = LocalDate.of(2026, 7, 17);
        List<SessionData> sessions = List.of(
                session(epochMillisAtStartOfDay(today, zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(2), zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(4), zone), 1.0, 100, 10.0)
        );
        assertEquals(2, SessionStats.streakDays(sessions, today, zone, 1));
    }

    @Test
    void quietTodayDoesNotEndFullHistoryStreak() {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate today = LocalDate.of(2026, 7, 15);
        List<SessionData> sessions = List.of(
                session(epochMillisAtStartOfDay(today.minusDays(1), zone), 1.0, 100, 10.0),
                session(epochMillisAtStartOfDay(today.minusDays(2), zone), 1.0, 100, 10.0)
        );
        assertEquals(2, SessionStats.streakDays(sessions, today, zone, 0));
    }

    @Test
    void recordsFindLongestSessionAndBestDays() {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate day = LocalDate.of(2026, 7, 10);
        SessionData longest = session(epochMillisAtStartOfDay(day, zone), 2.0, 2000, 100.0);
        longest.elapsedSeconds = 7200L;
        longest.name = "Long walk";
        List<SessionData> sessions = List.of(
                longest,
                // Two sessions on the same later day outweigh the single long one in distance.
                session(epochMillisAtStartOfDay(day.plusDays(1), zone), 1.5, 1500, 80.0),
                session(epochMillisAtStartOfDay(day.plusDays(1), zone) + 3_600_000L, 1.5, 1500, 80.0)
        );
        SessionStats.Records records = SessionStats.records(sessions, zone);
        assertEquals(7200L, records.longestSessionSeconds);
        assertEquals("Long walk", records.longestSessionName);
        assertEquals(3.0, records.bestDayDistanceKm, 0.0001);
        assertEquals(day.plusDays(1).toEpochDay(), records.bestDayDistanceEpochDay);
        assertEquals(3000L, records.bestDaySteps);
    }

    @Test
    void streakCountsConsecutiveWalkingDays() {
        assertEquals(3, SessionStats.streakDays(new double[]{0.0, 1.0, 2.0, 1.0}));
        // A quiet today falls back to counting from yesterday.
        assertEquals(2, SessionStats.streakDays(new double[]{0.0, 1.0, 2.0, 0.0}));
        assertEquals(0, SessionStats.streakDays(new double[]{1.0, 0.0, 0.0, 0.0}));
        assertEquals(4, SessionStats.streakDays(new double[]{1.0, 1.0, 1.0, 1.0}));
        assertEquals(0, SessionStats.streakDays(new double[]{}));
    }
}
