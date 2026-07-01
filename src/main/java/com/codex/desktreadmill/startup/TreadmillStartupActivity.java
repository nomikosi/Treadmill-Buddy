package com.codex.desktreadmill.startup;

import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class TreadmillStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        if (TreadmillSettings.getInstance().getProfile().isComplete()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                ProfileDialog.showIfNeeded(project);
            }
        });
    }
}
