package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;

public final class TreadmillStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, WorkoutEngine.Listener {
    public static final String WIDGET_ID = "TreadmillBuddyWidget";

    private final WorkoutEngine engine = WorkoutEngine.getInstance();
    private StatusBar statusBar;

    @Override
    public @NotNull String ID() {
        return WIDGET_ID;
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        engine.addListener(this);
    }

    @Override
    public void dispose() {
        engine.removeListener(this);
        statusBar = null;
    }

    @Override
    public @NotNull String getText() {
        SessionData session = engine.getSession();
        if (session == null) {
            return "";
        }
        SessionMode mode = SessionMode.fromId(session.modeId);
        long seconds = mode == SessionMode.MARATHON ? session.elapsedSeconds : session.remainingSeconds;
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(seconds);
        String prefix = time.getDayPrefix().isBlank() ? "" : time.getDayPrefix() + " ";
        return TreadmillBundle.message("widget.text",
                prefix + time.getTimeText(),
                String.format("%.0f", session.calories));
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public @Nullable String getTooltipText() {
        SessionData session = engine.getSession();
        if (session == null) {
            return TreadmillBundle.message("widget.tooltip.none");
        }
        if (engine.isRunning()) {
            return TreadmillBundle.message("widget.tooltip.running");
        }
        return session.completed
                ? TreadmillBundle.message("widget.tooltip.complete")
                : TreadmillBundle.message("widget.tooltip.paused");
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return this::showActionsPopup;
    }

    private void showActionsPopup(MouseEvent event) {
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup group = new DefaultActionGroup();
        for (String actionId : new String[]{
                "TreadmillBuddy.TogglePause",
                "TreadmillBuddy.SaveSession",
                "TreadmillBuddy.NewSession",
                "TreadmillBuddy.OpenToolWindow"
        }) {
            AnAction action = actionManager.getAction(actionId);
            if (action != null) {
                group.add(action);
            }
        }
        ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                TreadmillBundle.message("widget.display.name"),
                group,
                DataManager.getInstance().getDataContext(event.getComponent()),
                JBPopupFactory.ActionSelectionAid.MNEMONICS,
                true
        );
        popup.show(new RelativePoint(event));
    }

    @Override
    public void workoutStateChanged() {
        StatusBar bar = statusBar;
        if (bar == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            bar.updateWidget(WIDGET_ID);
        } else {
            SwingUtilities.invokeLater(() -> bar.updateWidget(WIDGET_ID));
        }
    }
}
