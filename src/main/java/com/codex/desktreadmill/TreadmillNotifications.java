package com.codex.desktreadmill;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

public final class TreadmillNotifications {
    public static final String GROUP_ID = "Treadmill Buddy";

    private TreadmillNotifications() {
    }

    public static void info(String title, String content) {
        info(null, title, content);
    }

    public static void info(@Nullable Project project, String title, String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, NotificationType.INFORMATION)
                .notify(project);
    }

    public static void withUndo(@Nullable Project project, String content, Runnable undo) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(TreadmillBundle.message("notification.title"), content, NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring(TreadmillBundle.message("notification.undo"), undo))
                .notify(project);
    }
}
