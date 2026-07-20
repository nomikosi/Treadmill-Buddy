package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.engine.SessionStats;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.settings.TreadmillSettings;
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
import java.time.LocalDate;
import java.time.ZoneId;

public final class TreadmillStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, WorkoutEngine.Listener {
    public static final String WIDGET_ID = "TreadmillBuddyWidget";

    /** Goal progress needs a scan over saved sessions; refresh it at most this often. */
    private static final long GOAL_REFRESH_MILLIS = 5_000L;
    private static final String[] GOAL_GLYPHS = {"○", "◔", "◑", "◕", "●"};

    private final WorkoutEngine engine = WorkoutEngine.getInstance();
    private StatusBar statusBar;
    private String cachedGoalText = "";
    private long goalTextComputedMillis;

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
        long seconds;
        String blockPrefix = "";
        if (mode == SessionMode.INTERVAL
                && session.intervalWalkSeconds > 0 && session.intervalBreakSeconds > 0) {
            seconds = WorkoutMath.intervalBlockRemaining(session);
            blockPrefix = (session.intervalWalking ? "Walk " : "Break ");
        } else {
            // Countdown modes show remaining; marathon and interval sessions
            // without block config (old imports) count up.
            seconds = mode.isCountdown() ? session.remainingSeconds : session.elapsedSeconds;
        }
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(seconds);
        String prefix = time.getDayPrefix().isBlank() ? "" : time.getDayPrefix() + " ";
        return TreadmillBundle.message("widget.text",
                blockPrefix + prefix + time.getTimeText(),
                String.format("%.0f", session.calories)) + goalText();
    }

    /** " · ◑ 52%" for the daily goal, cached because it scans saved sessions. */
    private String goalText() {
        long now = System.currentTimeMillis();
        if (now - goalTextComputedMillis < GOAL_REFRESH_MILLIS) {
            return cachedGoalText;
        }
        goalTextComputedMillis = now;
        TreadmillSettings settings = TreadmillSettings.getInstance();
        GoalType goalType = settings.getDailyGoalType();
        double target = settings.getDailyGoalValue();
        if (goalType == GoalType.NONE || target <= 0) {
            cachedGoalText = "";
            return cachedGoalText;
        }
        ZoneId zone = ZoneId.systemDefault();
        long startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals today = SessionStats.totalsSince(settings.getSessions(), startOfToday);
        double progress = switch (goalType) {
            case STEPS -> today.steps;
            case DISTANCE -> today.distanceKm;
            case CALORIES -> today.calories;
            case NONE -> 0.0;
        };
        int percent = (int) Math.max(0, Math.round(progress / target * 100));
        String glyph = GOAL_GLYPHS[Math.min(GOAL_GLYPHS.length - 1, percent * (GOAL_GLYPHS.length - 1) / 100)];
        cachedGoalText = " · " + glyph + " " + Math.min(percent, 999) + "%";
        return cachedGoalText;
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
                "TreadmillBuddy.KeepRunningWhenIdle",
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
