package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.settings.TreadmillSettings;

/**
 * The optional "time to move" nudge: after the configured time at the
 * keyboard without a walk, at most once per interval, never while a session
 * runs. Only time someone is at the IDE counts. After {@link #ABSENCE_MILLIS}
 * without keyboard or mouse input they are away, so nothing piles up in the
 * notification log overnight, and the count starts over when they are back:
 * being away from the desk usually meant being up.
 */
final class MoveReminder {
    /** No keyboard or mouse input for this long means nobody is at the IDE. */
    static final long ABSENCE_MILLIS = 10 * 60_000L;

    private final TreadmillSettings settings;
    private final WorkoutFeedback feedback;
    private long lastInputMillis;
    /** When the current stretch at the keyboard began. */
    private long presentSinceMillis;
    private long lastReminderMillis;

    MoveReminder(TreadmillSettings settings, WorkoutFeedback feedback, long nowMillis) {
        this.settings = settings;
        this.feedback = feedback;
        lastInputMillis = nowMillis;
        presentSinceMillis = nowMillis;
        lastReminderMillis = nowMillis;
    }

    /** Keyboard or mouse input; the first after an absence starts a new stretch at the keyboard. */
    void noteInput(long nowMillis) {
        if (nowMillis - lastInputMillis >= ABSENCE_MILLIS) {
            presentSinceMillis = nowMillis;
        }
        lastInputMillis = nowMillis;
    }

    void check(long nowMillis, boolean running, long lastWalkMillis) {
        int reminderMinutes = settings.getMoveReminderMinutes();
        if (reminderMinutes <= 0 || running || nowMillis - lastInputMillis >= ABSENCE_MILLIS) {
            return;
        }
        long reminderMillis = reminderMinutes * 60_000L;
        long sittingSince = Math.max(lastWalkMillis, presentSinceMillis);
        if (nowMillis - sittingSince < reminderMillis || nowMillis - lastReminderMillis < reminderMillis) {
            return;
        }
        lastReminderMillis = nowMillis;
        feedback.moveReminderDue(reminderMinutes);
    }
}
