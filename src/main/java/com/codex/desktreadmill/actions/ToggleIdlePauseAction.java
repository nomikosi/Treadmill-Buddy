package com.codex.desktreadmill.actions;

import com.codex.desktreadmill.engine.WorkoutEngine;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * "Keep running while idle": suspends the inactivity auto-pause for reading
 * code, meetings, and other keyboard-quiet walking. System-sleep pause still
 * applies. The toggle lasts until turned off or the IDE restarts.
 */
public final class ToggleIdlePauseAction extends ToggleAction implements DumbAware {

    @Override
    public boolean isSelected(@NotNull AnActionEvent event) {
        return WorkoutEngine.getInstance().isKeepRunningWhenIdle();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent event, boolean selected) {
        WorkoutEngine.getInstance().setKeepRunningWhenIdle(selected);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
