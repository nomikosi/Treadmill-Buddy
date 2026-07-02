package com.codex.desktreadmill.actions;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class SaveSessionAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        WorkoutEngine engine = WorkoutEngine.getInstance();
        if (engine.getSession() == null) {
            return;
        }
        engine.persistNow();
        TreadmillNotifications.info(event.getProject(),
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.session.saved"));
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(WorkoutEngine.getInstance().getSession() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
