package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.settings.TreadmillSettings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Personal records, checked when a session pauses or completes. A record only
 * counts when the current session beats every <em>other</em> session, and
 * today beats every other day: measuring against a best that already includes
 * the current walk would let a session break its own earlier state, which on
 * a fresh install means congratulating the very first walk for beating itself.
 *
 * <p>Each record is announced once, guarded by persisted state keyed the way
 * the record is measured - by session id for the longest session, by day for
 * the day totals - so neither a mid-walk pause, a second session the same
 * afternoon, nor an IDE restart re-fires it.</p>
 */
public final class RecordTracker {
    private final TreadmillSettings settings;
    private final WorkoutFeedback feedback;

    RecordTracker(TreadmillSettings settings, WorkoutFeedback feedback) {
        this.settings = settings;
        this.feedback = feedback;
    }

    void check(SessionData session, long nowMillis) {
        if (session.elapsedSeconds == 0) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        BrokenRecords broken = brokenRecords(
                settings.getSessions(), session, today, zone,
                settings.getLastSessionRecordId(),
                settings.getLastDistanceRecordDay(),
                settings.getLastStepsRecordDay());
        if (!broken.any()) {
            return;
        }
        // The guards are consumed whether or not a balloon can be shown.
        if (broken.longestSession) {
            settings.setLastSessionRecordId(session.id);
        }
        if (broken.dayDistance) {
            settings.setLastDistanceRecordDay(today.toEpochDay());
        }
        if (broken.daySteps) {
            settings.setLastStepsRecordDay(today.toEpochDay());
        }
        feedback.recordsBroken(broken, session, settings.getUnitSystem());
    }

    /** Which records a pause or completion broke, and the numbers to announce. */
    public static final class BrokenRecords {
        public boolean longestSession;
        public boolean dayDistance;
        public boolean daySteps;
        public long previousLongestSeconds;
        public double todayDistanceKm;
        public long todaySteps;

        boolean any() {
            return longestSession || dayDistance || daySteps;
        }
    }

    /**
     * Pure record decision, split out from the announcement so it can be tested
     * without an IDE. {@code history} is expected to already contain
     * {@code session} (the engine persists before checking); the current
     * session and today are excluded from their own baselines.
     */
    static BrokenRecords brokenRecords(
            List<SessionData> history,
            SessionData session,
            LocalDate today,
            ZoneId zone,
            String lastSessionRecordId,
            long lastDistanceRecordDay,
            long lastStepsRecordDay
    ) {
        BrokenRecords broken = new BrokenRecords();
        long epochDay = today.toEpochDay();
        broken.previousLongestSeconds = SessionStats.longestSessionSeconds(history, session.id);
        broken.longestSession = broken.previousLongestSeconds > 0
                && session.elapsedSeconds > broken.previousLongestSeconds
                // A blank id can't be told apart from the "nothing announced
                // yet" sentinel, so never let it match.
                && !(!session.id.isEmpty() && session.id.equals(lastSessionRecordId));

        long startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals todayTotals = SessionStats.totalsSince(history, startOfToday);
        SessionStats.DayTotals bestOtherDay = SessionStats.bestDay(history, zone, epochDay);
        broken.todayDistanceKm = todayTotals.distanceKm;
        broken.todaySteps = todayTotals.steps;
        broken.dayDistance = bestOtherDay.distanceKm > 0
                && todayTotals.distanceKm > bestOtherDay.distanceKm
                && lastDistanceRecordDay != epochDay;
        broken.daySteps = bestOtherDay.steps > 0
                && todayTotals.steps > bestOtherDay.steps
                && lastStepsRecordDay != epochDay;
        return broken;
    }
}
