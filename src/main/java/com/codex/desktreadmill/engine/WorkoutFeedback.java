package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.UnitSystem;

/**
 * What the engine tells the user beyond its listeners: chimes and balloons.
 * The IDE implementation lives with the notification helpers; unit tests pass
 * {@link #SILENT} or record the calls to check what would have been shown.
 */
public interface WorkoutFeedback {
    /** An interval block just began and lasts {@code minutes}. */
    void intervalBlockStarted(boolean walking, long minutes);

    /** A countdown session reached its target. */
    void sessionCompleted(SessionData session, UnitSystem units);

    /** Today's goal ({@code weekly} false) or this week's was just reached. */
    void goalReached(boolean weekly, GoalType type, double target, UnitSystem units);

    /** A pause or completion set at least one personal record. */
    void recordsBroken(RecordTracker.BrokenRecords records, SessionData session, UnitSystem units);

    /** Nobody has walked for {@code minutes}. */
    void moveReminderDue(int minutes);

    WorkoutFeedback SILENT = new WorkoutFeedback() {
        @Override
        public void intervalBlockStarted(boolean walking, long minutes) {
        }

        @Override
        public void sessionCompleted(SessionData session, UnitSystem units) {
        }

        @Override
        public void goalReached(boolean weekly, GoalType type, double target, UnitSystem units) {
        }

        @Override
        public void recordsBroken(RecordTracker.BrokenRecords records, SessionData session, UnitSystem units) {
        }

        @Override
        public void moveReminderDue(int minutes) {
        }
    };
}
