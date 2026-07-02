package com.codex.desktreadmill.actions;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.model.SessionData;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class TogglePauseAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        WorkoutEngine engine = WorkoutEngine.getInstance();
        SessionData session = engine.getSession();
        if (session == null || session.completed) {
            // Starting fresh needs the input form: send the user to the tool window.
            OpenToolWindowAction.openToolWindow(event.getProject());
            return;
        }
        if (engine.isRunning()) {
            engine.pause();
        } else {
            engine.resume();
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        WorkoutEngine engine = WorkoutEngine.getInstance();
        SessionData session = engine.getSession();
        if (engine.isRunning()) {
            event.getPresentation().setText(TreadmillBundle.message("action.toggle.pause"));
            event.getPresentation().setIcon(AllIcons.Actions.Pause);
        } else if (session != null && !session.completed) {
            event.getPresentation().setText(TreadmillBundle.message("action.toggle.resume"));
            event.getPresentation().setIcon(AllIcons.Actions.Resume);
        } else {
            event.getPresentation().setText(TreadmillBundle.message("action.toggle.start"));
            event.getPresentation().setIcon(AllIcons.Actions.Execute);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
