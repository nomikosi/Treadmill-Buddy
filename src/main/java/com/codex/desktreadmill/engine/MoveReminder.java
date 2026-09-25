package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.settings.TreadmillSettings;

/** The optional "time to move" nudge: at most once per interval, never while a session runs. */
final class MoveReminder {
    private final TreadmillSettings settings;
    private final WorkoutFeedback feedback;
    private long lastReminderMillis;

    MoveReminder(TreadmillSettings settings, WorkoutFeedback feedback, long nowMillis) {
        this.settings = settings;
        this.feedback = feedback;
        lastReminderMillis = nowMillis;
    }

    void check(long nowMillis, boolean running, long lastWalkMillis) {
        int reminderMinutes = settings.getMoveReminderMinutes();
        if (reminderMinutes <= 0 || running) {
            return;
        }
        long reminderMillis = reminderMinutes * 60_000L;
        if (nowMillis - lastWalkMillis < reminderMillis || nowMillis - lastReminderMillis < reminderMillis) {
            return;
        }
        lastReminderMillis = nowMillis;
        feedback.moveReminderDue(reminderMinutes);
    }
}
