package com.codex.desktreadmill.startup;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TreadmillStartupActivity implements ProjectActivity {
    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!TreadmillSettings.getInstance().getProfile().isComplete()) {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(TreadmillNotifications.GROUP_ID)
                    .createNotification(
                            TreadmillBundle.message("notification.title"),
                            TreadmillBundle.message("notification.profile.content"),
                            NotificationType.INFORMATION
                    )
                    .addAction(NotificationAction.createSimpleExpiring(
                            TreadmillBundle.message("notification.profile.action"),
                            () -> ProfileDialog.showIfNeeded(project)
                    ))
                    .notify(project);
        }
        return Unit.INSTANCE;
    }
}
