package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The saved-session list with its toolbar: load, delete (single and
 * older-than-N-days bulk), import (CSV/JSON), and export (CSV/JSON/TCX).
 */
public final class SavedSessionsPanel {
    /** Durable cross-IDE history can grow into the hundreds; keep the list scannable. */
    private static final int RECENT_SESSIONS_LIMIT = 25;
    private static final DateTimeFormatter SESSION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Project project;
    private final TreadmillSettings settings;
    private final WorkoutEngine engine;
    private final Consumer<SessionData> onLoadSession;
    private final Supplier<UnitSystem> units;
    private final CollectionListModel<SessionData> sessionsModel = new CollectionListModel<>();
    private final JBList<SessionData> sessionsList = new JBList<>(sessionsModel);
    private final JComponent component;

    private boolean showAllSessions;

    public SavedSessionsPanel(
            Project project,
            TreadmillSettings settings,
            WorkoutEngine engine,
            Consumer<SessionData> onLoadSession,
            Supplier<UnitSystem> units
    ) {
        this.project = project;
        this.settings = settings;
        this.engine = engine;
        this.onLoadSession = onLoadSession;
        this.units = units;
        component = build();
    }

    public JComponent getComponent() {
        return component;
    }

    /** Re-renders the list from an already-sorted (newest first) session list. */
    public void refresh(List<SessionData> sessions) {
        SessionData selected = sessionsList.getSelectedValue();
        String selectedId = selected == null ? null : selected.id;
        List<SessionData> shown = showAllSessions || sessions.size() <= RECENT_SESSIONS_LIMIT
                ? sessions
                : sessions.subList(0, RECENT_SESSIONS_LIMIT);
        sessionsModel.replaceAll(shown);
        sessionsList.setToolTipText(shown.size() < sessions.size()
                ? TreadmillBundle.message("sessions.tooltip.trimmed", shown.size(), sessions.size())
                : TreadmillBundle.message("sessions.tooltip"));
        if (selectedId != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (selectedId.equals(shown.get(i).id)) {
                    sessionsList.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private JComponent build() {
        sessionsList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(
                    @NotNull JList<? extends SessionData> list,
                    SessionData value,
                    int index,
                    boolean selected,
                    boolean hasFocus
            ) {
                append(value.name);
                append("  " + describeSession(value), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });
        sessionsList.getEmptyText().setText(TreadmillBundle.message("sessions.empty"));
        sessionsList.setVisibleRowCount(5);
        sessionsList.setToolTipText(TreadmillBundle.message("sessions.tooltip"));
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                loadSelectedSession();
                return true;
            }
        }.installOn(sessionsList);
        sessionsList.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "loadSelectedSession");
        sessionsList.getActionMap().put("loadSelectedSession", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                loadSelectedSession();
            }
        });

        return ToolbarDecorator.createDecorator(sessionsList)
                .setRemoveAction(button -> deleteSelectedSession())
                .setRemoveActionName(TreadmillBundle.message("sessions.delete"))
                .disableAddAction()
                .disableUpDownActions()
                .addExtraAction(transferAction("sessions.export.csv.text", "sessions.export.csv.description",
                        AllIcons.ToolbarDecorator.Export, () -> SessionTransfer.exportCsv(project, settings)))
                .addExtraAction(transferAction("sessions.export.json.text", "sessions.export.json.description",
                        AllIcons.FileTypes.Json, () -> SessionTransfer.exportJson(project, settings)))
                .addExtraAction(transferAction("sessions.export.tcx.text", "sessions.export.tcx.description",
                        AllIcons.Actions.Upload, () -> SessionTransfer.exportTcx(project, sessionsList.getSelectedValue())))
                .addExtraAction(transferAction("sessions.import.csv.text", "sessions.import.csv.description",
                        AllIcons.ToolbarDecorator.Import, () -> {
                            if (SessionTransfer.importCsv(project, settings) > 0) {
                                engine.notifySessionsChanged();
                            }
                        }))
                .addExtraAction(transferAction("sessions.import.json.text", "sessions.import.json.description",
                        AllIcons.Actions.Download, () -> {
                            if (SessionTransfer.importJson(project, settings) > 0) {
                                engine.notifySessionsChanged();
                            }
                        }))
                .addExtraAction(transferAction("sessions.deleteOld.text", "sessions.deleteOld.description",
                        AllIcons.Actions.GC, this::deleteOldSessions))
                .addExtraAction(new ShowAllSessionsAction())
                .createPanel();
    }

    private static DumbAwareAction transferAction(String textKey, String descriptionKey, javax.swing.Icon icon, Runnable action) {
        return new DumbAwareAction(TreadmillBundle.message(textKey), TreadmillBundle.message(descriptionKey), icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                action.run();
            }
        };
    }

    private void loadSelectedSession() {
        SessionData session = sessionsList.getSelectedValue();
        if (session != null) {
            onLoadSession.accept(session);
        }
    }

    private void deleteSelectedSession() {
        SessionData session = sessionsList.getSelectedValue();
        if (session == null) {
            return;
        }
        SessionData deleted = session.copy();
        settings.deleteSession(session.id);
        engine.clearSessionIf(session.id);
        engine.notifySessionsChanged();
        TreadmillNotifications.withUndo(
                project,
                TreadmillBundle.message("notification.session.deleted", deleted.name),
                () -> {
                    settings.saveSession(deleted);
                    engine.notifySessionsChanged();
                }
        );
    }

    private void deleteOldSessions() {
        String input = Messages.showInputDialog(project,
                TreadmillBundle.message("sessions.deleteOld.prompt"),
                TreadmillBundle.message("sessions.deleteOld.title"), null, "365", null);
        if (input == null) {
            return;
        }
        int days;
        try {
            days = Integer.parseInt(input.trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (days <= 0) {
            return;
        }
        long cutoffMillis = System.currentTimeMillis() - days * 86_400_000L;
        List<String> oldIds = new ArrayList<>();
        for (SessionData session : settings.getSessions()) {
            if (session.createdMillis > 0 && session.createdMillis < cutoffMillis) {
                oldIds.add(session.id);
            }
        }
        if (oldIds.isEmpty()) {
            Messages.showInfoMessage(project,
                    TreadmillBundle.message("sessions.deleteOld.none", days),
                    TreadmillBundle.message("sessions.deleteOld.title"));
            return;
        }
        int answer = Messages.showYesNoDialog(project,
                TreadmillBundle.message("sessions.deleteOld.confirm", oldIds.size(), days),
                TreadmillBundle.message("sessions.deleteOld.title"), Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        settings.deleteSessions(oldIds);
        SessionData active = engine.getSession();
        if (active != null && oldIds.contains(active.id)) {
            engine.clearSession();
        }
        engine.notifySessionsChanged();
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.sessions.deletedOld", oldIds.size(), days));
    }

    private String describeSession(SessionData session) {
        UnitSystem currentUnits = units.get();
        StringBuilder text = new StringBuilder(SessionMode.fromId(session.modeId).getLabel());
        if (session.elapsedSeconds > 0) {
            TimeFormatter.DisplayTime time = TimeFormatter.displayTime(session.elapsedSeconds);
            text.append(" · ");
            if (!time.getDayPrefix().isBlank()) {
                text.append(time.getDayPrefix()).append(' ');
            }
            text.append(time.getTimeText());
            text.append(String.format(" · %.2f %s · %.0f kcal",
                    currentUnits.distanceFromKm(session.distanceKm), currentUnits.distanceUnit(), session.calories));
        }
        if (session.createdMillis > 0) {
            text.append(" · ").append(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(session.createdMillis), ZoneId.systemDefault())
                            .format(SESSION_DATE_FORMAT));
        }
        return text.toString();
    }

    private final class ShowAllSessionsAction extends ToggleAction implements DumbAware {
        ShowAllSessionsAction() {
            super(TreadmillBundle.message("sessions.showAll.text"),
                    TreadmillBundle.message("sessions.showAll.description", RECENT_SESSIONS_LIMIT),
                    AllIcons.Actions.Show);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent event) {
            return showAllSessions;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent event, boolean selected) {
            showAllSessions = selected;
            engine.notifySessionsChanged();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
