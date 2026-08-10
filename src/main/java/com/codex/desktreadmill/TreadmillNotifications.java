package com.codex.desktreadmill;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

/**
 * Balloon helpers. All of them close the popup on a timer, because a stack of
 * treadmill balloons is the last thing an IDE needs - but they differ in what
 * survives afterwards:
 *
 * <ul>
 *   <li>{@link #info} hides the balloon and leaves the entry in the
 *       Notifications tool window, so a session summary or a personal record
 *       missed while walking can still be read.</li>
 *   <li>{@link #transientInfo} expires it instead: a walk/break chime is
 *       meaningless once the block is over, and a long interval session would
 *       otherwise leave dozens of dead rows behind.</li>
 *   <li>{@link #withUndo} expires too - the timeout <em>is</em> the undo
 *       window, and an Undo link that still works days later would silently
 *       resurrect a deleted session.</li>
 *   <li>{@link #withAction} hides only, for standing invitations like the move
 *       reminder whose action stays valid indefinitely.</li>
 * </ul>
 */
public final class TreadmillNotifications {
    public static final String GROUP_ID = "Treadmill Buddy";

    /** Plain info balloons close themselves after this long. */
    private static final int INFO_AUTO_CLOSE_MILLIS = 2_500;
    /** Balloons with a clickable action stay longer - closing at info speed would snatch the action away. */
    private static final int ACTION_AUTO_CLOSE_MILLIS = 10_000;

    private TreadmillNotifications() {
    }

    public static void info(String title, String content) {
        info(null, title, content);
    }

    public static void info(@Nullable Project project, String title, String content) {
        Notification notification = post(project, title, content, null, null);
        closeAfter(notification, INFO_AUTO_CLOSE_MILLIS, false);
    }

    /** For notices that are worthless once read; leaves nothing behind. */
    public static void transientInfo(String title, String content) {
        Notification notification = post(null, title, content, null, null);
        closeAfter(notification, INFO_AUTO_CLOSE_MILLIS, true);
    }

    public static void withUndo(@Nullable Project project, String content, Runnable undo) {
        Notification notification = post(project, TreadmillBundle.message("notification.title"), content,
                TreadmillBundle.message("notification.undo"), undo);
        // Expire: once the window closes the offer is off, here and in the log.
        closeAfter(notification, ACTION_AUTO_CLOSE_MILLIS, true);
    }

    public static void withAction(
            @Nullable Project project, String title, String content, String actionLabel, Runnable action) {
        Notification notification = post(project, title, content, actionLabel, action);
        closeAfter(notification, ACTION_AUTO_CLOSE_MILLIS, false);
    }

    private static Notification post(
            @Nullable Project project, String title, String content,
            @Nullable String actionLabel, @Nullable Runnable action) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, NotificationType.INFORMATION);
        if (actionLabel != null && action != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(actionLabel, action));
        }
        notification.notify(project);
        return notification;
    }

    /**
     * @param expire true to retire the notification entirely, false to close
     *               only the popup and keep the tool-window entry readable
     */
    private static void closeAfter(Notification notification, int millis, boolean expire) {
        // Posting during shutdown must not throw back into the caller: the
        // service lookup below would fail on a disposed container, and callers
        // like completeSession() would abort before notifying their listeners.
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return;
        }
        application.getService(TreadmillScheduler.class).onEdtAfter(millis, () -> {
            if (expire) {
                notification.expire();
            } else {
                notification.hideBalloon();
            }
        });
    }

}
