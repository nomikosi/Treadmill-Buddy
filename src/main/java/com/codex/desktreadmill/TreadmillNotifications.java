package com.codex.desktreadmill;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;

public final class TreadmillNotifications {
    public static final String GROUP_ID = "Treadmill Buddy";

    /** Plain info balloons close on their own after this long. */
    private static final int INFO_AUTO_CLOSE_MILLIS = 2_500;
    /** Undo balloons stay longer - closing at info speed would snatch the action away. */
    private static final int UNDO_AUTO_CLOSE_MILLIS = 10_000;

    private TreadmillNotifications() {
    }

    public static void info(String title, String content) {
        info(null, title, content);
    }

    public static void info(@Nullable Project project, String title, String content) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, NotificationType.INFORMATION);
        notification.notify(project);
        expireAfter(notification, INFO_AUTO_CLOSE_MILLIS);
    }

    public static void withUndo(@Nullable Project project, String content, Runnable undo) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(TreadmillBundle.message("notification.title"), content, NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring(TreadmillBundle.message("notification.undo"), undo));
        notification.notify(project);
        expireAfter(notification, UNDO_AUTO_CLOSE_MILLIS);
    }

    private static void expireAfter(Notification notification, int millis) {
        Timer timer = new Timer(millis, event -> notification.expire());
        timer.setRepeats(false);
        timer.start();
    }
}
