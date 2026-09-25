package com.codex.desktreadmill;

import com.codex.desktreadmill.engine.RecordTracker;
import com.codex.desktreadmill.engine.WorkoutFeedback;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.UnitSystem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.awt.Toolkit;
import java.util.Locale;

/** The engine's announcements as the IDE shows them: a chime and a balloon. */
public final class IdeWorkoutFeedback implements WorkoutFeedback {
    @Override
    public void intervalBlockStarted(boolean walking, long minutes) {
        Toolkit.getDefaultToolkit().beep();
        // Transient: a block chime is stale the moment the block ends, and a
        // three-hour interval walk would otherwise pile up a dozen dead rows.
        TreadmillNotifications.transientInfo(
                TreadmillBundle.message(walking ? "notification.interval.walk.title" : "notification.interval.break.title"),
                TreadmillBundle.message(walking ? "notification.interval.walk.content" : "notification.interval.break.content", minutes)
        );
    }

    @Override
    public void sessionCompleted(SessionData session, UnitSystem units) {
        Toolkit.getDefaultToolkit().beep();
        TreadmillNotifications.info(
                TreadmillBundle.message("notification.session.complete.title"),
                completionMessage(session, units)
        );
    }

    @Override
    public void goalReached(boolean weekly, GoalType type, double target, UnitSystem units) {
        TreadmillNotifications.info(
                TreadmillBundle.message(weekly ? "notification.goal.weekly.title" : "notification.goal.title"),
                TreadmillBundle.message(weekly ? "notification.goal.weekly.content" : "notification.goal.content",
                        type.formatValue(target, units))
        );
    }

    @Override
    public void recordsBroken(RecordTracker.BrokenRecords records, SessionData session, UnitSystem units) {
        String title = TreadmillBundle.message("notification.record.title");
        if (records.longestSession) {
            TreadmillNotifications.info(title, TreadmillBundle.message("notification.record.session",
                    formatDuration(session.elapsedSeconds), formatDuration(records.previousLongestSeconds)));
        }
        if (records.dayDistance) {
            TreadmillNotifications.info(title, TreadmillBundle.message("notification.record.distance",
                    String.format("%.2f", units.distanceFromKm(records.todayDistanceKm)), units.distanceUnit()));
        }
        if (records.daySteps) {
            TreadmillNotifications.info(title, TreadmillBundle.message("notification.record.steps",
                    String.format("%,d", records.todaySteps)));
        }
    }

    @Override
    public void moveReminderDue(int minutes) {
        // Goes through TreadmillNotifications so it picks up the shared
        // auto-close behaviour instead of being the one balloon that lingers.
        TreadmillNotifications.withAction(
                null,
                TreadmillBundle.message("notification.move.title"),
                TreadmillBundle.message("notification.move.content", minutes),
                TreadmillBundle.message("notification.move.action"),
                IdeWorkoutFeedback::openToolWindow);
    }

    /**
     * The completion balloon in the user's display units - it used to print
     * the raw kilometres under a hard-coded "km" for imperial users too. The
     * name is escaped because balloon content is rendered as HTML.
     */
    static String completionMessage(SessionData session, UnitSystem units) {
        return TreadmillBundle.message("notification.session.complete.content",
                StringUtil.escapeXmlEntities(session.name),
                String.format("%.2f", units.distanceFromKm(session.distanceKm)),
                units.distanceUnit(),
                String.format("%.0f", session.calories));
    }

    static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        // Sub-minute walks would otherwise both render as a meaningless "0m".
        return minutes > 0 ? minutes + "m" : seconds + "s";
    }

    private static void openToolWindow() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) {
                continue;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TreadmillToolWindowFactory.TOOL_WINDOW_ID);
            if (toolWindow != null) {
                toolWindow.activate(null);
                return;
            }
        }
    }
}
