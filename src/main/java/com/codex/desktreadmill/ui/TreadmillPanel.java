package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.SessionStats;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillConfigurable;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public final class TreadmillPanel extends JPanel implements WorkoutEngine.Listener, Disposable {
    private static final DateTimeFormatter SESSION_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SESSION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Project project;
    private final TreadmillSettings settings = TreadmillSettings.getInstance();
    private final WorkoutEngine engine = WorkoutEngine.getInstance();
    private final DigitalClockDisplay clockDisplay = new DigitalClockDisplay();
    private final ComboBox<SessionMode> modeCombo = new ComboBox<>(SessionMode.values());
    private final ComboBox<CalorieAlgorithm> algorithmCombo = new ComboBox<>(CalorieAlgorithm.values());
    private final CollectionListModel<SessionData> sessionsModel = new CollectionListModel<>();
    private final JBList<SessionData> sessionsList = new JBList<>(sessionsModel);
    private final JBTextField sessionNameField = new JBTextField();
    private final JBTextField speedField = new JBTextField("3.0");
    private final JBTextField inclineField = new JBTextField("0");
    private final JBTextField calorieTargetField = new JBTextField("300");
    private final JBTextField fatTargetField = new JBTextField("0.5");
    private final JBLabel statusLabel = new JBLabel(TreadmillBundle.message("status.ready"));
    private final JBLabel statsLabel = new JBLabel();
    private final DailyDistanceChart dailyChart = new DailyDistanceChart();
    private final JProgressBar goalProgressBar = new JProgressBar(0, 100);
    private final JBLabel speedRowLabel = new JBLabel();
    private final JBLabel fatTargetRowLabel = new JBLabel();
    private final JBLabel distanceLabel = valueLabel("0.00 km");
    private final JBLabel stepsLabel = valueLabel("0 steps");
    private final JBLabel caloriesLabel = valueLabel("0 kcal");
    private final JBLabel targetLabel = valueLabel("-");
    private final JButton startPauseButton = new JButton("Start");
    private final JButton floatButton = new JButton("Float Clock");
    private final JButton saveButton = new JButton("Save Session");
    private final JButton resetButton = new JButton("Reset");
    private final JButton newButton = new JButton("New");
    private final CardLayout targetCards = new CardLayout();
    private final JPanel targetPanel = new JPanel(targetCards);
    private final FloatingClockWindow floatingClock;

    private boolean populatingFields;
    private boolean highSpeedWarningShown;
    private UnitSystem currentUnits;

    public TreadmillPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));
        currentUnits = settings.getUnitSystem();
        speedField.setText(format(currentUnits.speedFromKmh(3.0)));
        fatTargetField.setText(format(currentUnits.weightFromKg(0.5)));
        updateUnitLabels();
        ComboHelp.configureModeCombo(modeCombo);
        ComboHelp.configureAlgorithmCombo(algorithmCombo, settings::getSelectedAlgorithm);
        algorithmCombo.setSelectedItem(settings.getSelectedAlgorithm());
        sessionNameField.setText(defaultSessionName(SessionMode.MARATHON));
        floatingClock = new FloatingClockWindow(project, this::toggleRunning, () -> saveCurrentSession(true));

        add(clockDisplay, BorderLayout.NORTH);
        add(new JBScrollPane(createBody()), BorderLayout.CENTER);
        add(createButtons(), BorderLayout.SOUTH);

        modeCombo.addActionListener(this::modeChanged);
        algorithmCombo.addActionListener(event -> algorithmChanged());
        startPauseButton.addActionListener(event -> toggleRunning());
        floatButton.addActionListener(event -> floatingClock.showWindow());
        saveButton.addActionListener(event -> saveCurrentSession(true));
        resetButton.addActionListener(event -> resetCurrentSession());
        newButton.addActionListener(event -> newSession());
        installSpeedListener();
        installTargetPreviewListeners();
        installFieldValidators();

        engine.addListener(this);
        refreshSavedSessions();
        SessionData existing = engine.getSession();
        if (existing != null) {
            populateFields(existing);
        } else {
            loadLastSessionOrDefault();
        }
        updateDisplay();
    }

    @Override
    public void dispose() {
        engine.removeListener(this);
        floatingClock.dispose();
    }

    @Override
    public void workoutStateChanged() {
        updateDisplay();
    }

    @Override
    public void sessionCompleted(SessionData session) {
        updateDisplay();
    }

    @Override
    public void sessionsPersisted() {
        refreshSavedSessions();
    }

    private JComponent createBody() {
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(JBUI.Borders.emptyTop(12));
        body.add(createSessionForm(), BorderLayout.NORTH);
        body.add(createMetrics(), BorderLayout.CENTER);
        return body;
    }

    private JComponent createSessionForm() {
        JPanel calorieCard = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Calories to burn"), calorieTargetField, 1, false)
                .getPanel();
        JPanel fatCard = FormBuilder.createFormBuilder()
                .addLabeledComponent(fatTargetRowLabel, fatTargetField, 1, false)
                .getPanel();
        JPanel emptyCard = new JPanel(new BorderLayout());
        targetPanel.add(emptyCard, SessionMode.MARATHON.name());
        targetPanel.add(calorieCard, SessionMode.CALORIE_BURN.name());
        targetPanel.add(fatCard, SessionMode.FAT_BURN.name());

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Saved sessions"), createSavedSessionsPanel(), 1, false)
                .addLabeledComponent(new JBLabel("Mode"), modeCombo, 1, false)
                .addLabeledComponent(new JBLabel("Session name"), sessionNameField, 1, false)
                .addLabeledComponent(speedRowLabel, speedField, 1, false)
                .addLabeledComponent(new JBLabel("Incline (%)"), inclineField, 1, false)
                .addLabeledComponent(new JBLabel("Calorie algorithm"), algorithmCombo, 1, false)
                .addComponent(targetPanel)
                .getPanel();
    }

    private JComponent createMetrics() {
        JPanel metrics = new JPanel(new GridLayout(2, 2, 8, 8));
        metrics.add(metricTile("Distance", distanceLabel));
        metrics.add(metricTile("Steps", stepsLabel));
        metrics.add(metricTile("Calories", caloriesLabel));
        metrics.add(metricTile("Target", targetLabel));

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        statsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statsLabel.setFont(JBUI.Fonts.smallFont());
        statsLabel.setForeground(UIUtil.getContextHelpForeground());
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dailyChart.setToolTipText("Daily distance over the last 14 days (rightmost bar is today)");
        goalProgressBar.setStringPainted(true);
        goalProgressBar.setVisible(false);
        goalProgressBar.setAlignmentX(CENTER_ALIGNMENT);
        dailyChart.setAlignmentX(CENTER_ALIGNMENT);
        statsLabel.setAlignmentX(CENTER_ALIGNMENT);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);
        south.add(goalProgressBar);
        south.add(Box.createVerticalStrut(4));
        south.add(dailyChart);
        south.add(Box.createVerticalStrut(2));
        south.add(statsLabel);
        south.add(statusLabel);

        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.add(metrics, BorderLayout.CENTER);
        wrapper.add(south, BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent createButtons() {
        JButton settingsButton = new JButton("Settings");
        settingsButton.setToolTipText("Open the Treadmill Buddy settings page");
        settingsButton.addActionListener(event ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TreadmillConfigurable.class));

        // WrapFlowLayout reports the wrapped height, so buttons flow to the next
        // line when the tool window narrows instead of getting clipped.
        JPanel buttons = new JPanel(new WrapFlowLayout(FlowLayout.CENTER, 8, 8));
        buttons.add(startPauseButton);
        buttons.add(floatButton);
        buttons.add(saveButton);
        buttons.add(resetButton);
        buttons.add(newButton);
        buttons.add(settingsButton);
        return buttons;
    }

    private JComponent createSavedSessionsPanel() {
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
        sessionsList.getEmptyText().setText("No saved sessions");
        sessionsList.setVisibleRowCount(5);
        sessionsList.setToolTipText("Double-click or press Enter to load a session");
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
                .setRemoveActionName("Delete Session")
                .disableAddAction()
                .disableUpDownActions()
                .addExtraAction(new DumbAwareAction("Export CSV", "Export all saved sessions to a CSV file", AllIcons.ToolbarDecorator.Export) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent event) {
                        exportSessionsCsv();
                    }
                })
                .createPanel();
    }

    private static JBLabel valueLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private static JComponent metricTile(String title, JBLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border()),
                JBUI.Borders.empty(10)
        ));
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private void modeChanged(ActionEvent event) {
        if (populatingFields) {
            return;
        }
        SessionMode mode = selectedMode();
        targetCards.show(targetPanel, mode.name());
        sessionNameField.setText(defaultSessionName(mode));
        engine.clearSession();
        updateDisplay();
    }

    private void toggleRunning() {
        if (engine.isRunning()) {
            engine.pause();
            return;
        }
        if (!ensureProfile()) {
            return;
        }
        SessionData session = engine.getSession();
        if (session == null || session.completed) {
            SessionData built = buildSessionFromInputs();
            if (built == null) {
                return;
            }
            engine.startSession(built);
        } else {
            applySpeedFromField(false);
            applyInclineFromField();
            engine.resume();
        }
        floatingClock.showWindow();
    }

    private SessionData buildSessionFromInputs() {
        double speed = parseSpeedKmh();
        if (speed <= 0 || speed > 25) {
            showError(speedRangeMessage());
            return null;
        }
        double incline = parseInclineOrDefault();
        if (incline < 0) {
            showError("Enter an incline between 0 and 30 percent.");
            return null;
        }
        maybeShowHighSpeedPrompt(speed);
        CalorieAlgorithm algorithm = selectedAlgorithm();
        UserProfile profile = settings.getProfile();
        double caloriesPerMinute = algorithm.kcalPerMinute(profile, speed, incline);
        if (caloriesPerMinute <= 0) {
            showError("The selected calorie algorithm produced a zero burn rate.");
            return null;
        }

        SessionMode mode = selectedMode();
        SessionData session = new SessionData();
        session.id = String.valueOf(System.currentTimeMillis());
        session.createdMillis = System.currentTimeMillis();
        session.name = sessionNameField.getText().trim().isBlank() ? defaultSessionName(mode) : sessionNameField.getText().trim();
        session.modeId = mode.name();
        session.algorithmId = algorithm.name();
        session.speedKmh = speed;
        session.inclinePercent = incline;

        if (mode == SessionMode.CALORIE_BURN) {
            double targetCalories = parseDouble(calorieTargetField.getText());
            if (targetCalories <= 0) {
                showError("Enter calories greater than zero.");
                return null;
            }
            session.targetCalories = targetCalories;
            session.targetSeconds = WorkoutMath.secondsForCalories(targetCalories, caloriesPerMinute);
            session.remainingSeconds = session.targetSeconds;
        } else if (mode == SessionMode.FAT_BURN) {
            double fatInput = parseDouble(fatTargetField.getText());
            if (fatInput <= 0) {
                showError("Enter a weight target greater than zero.");
                return null;
            }
            double fatKg = currentUnits.weightToKg(fatInput);
            double targetCalories = fatKg * WorkoutMath.FAT_KCAL_PER_KG;
            long seconds = WorkoutMath.secondsForCalories(targetCalories, caloriesPerMinute);
            long days = seconds / 86_400L;
            if (days > 99L) {
                Messages.showInfoMessage(
                        project,
                        "baby steps, it looks like you would need more than 99 days for your goal. Let's try to set a more tangible one first",
                        "Goal Too Large"
                );
                return null;
            }
            session.targetFatKg = fatKg;
            session.targetCalories = targetCalories;
            session.targetSeconds = seconds;
            session.remainingSeconds = seconds;
        }
        WorkoutMath.recalcRemaining(session, profile);
        return session;
    }

    private void updateDisplay() {
        syncUnitsIfChanged();
        SessionData session = engine.getSession();
        long seconds;
        if (session != null) {
            SessionMode mode = SessionMode.fromId(session.modeId);
            seconds = mode == SessionMode.MARATHON ? session.elapsedSeconds : session.remainingSeconds;
        } else {
            seconds = previewSecondsFromInputs();
        }
        TimeFormatter.DisplayTime displayTime = TimeFormatter.displayTime(seconds);
        clockDisplay.setDisplay(displayTime.getDayPrefix(), displayTime.getTimeText());
        floatingClock.setDisplay(displayTime.getDayPrefix(), displayTime.getTimeText());

        if (session == null) {
            distanceLabel.setText("0.00 " + currentUnits.distanceUnit());
            stepsLabel.setText("0 steps");
            caloriesLabel.setText("0 kcal");
            targetLabel.setText(previewTargetText());
            statusLabel.setText(TreadmillBundle.message("status.ready"));
            startPauseButton.setText(TreadmillBundle.message("button.start"));
            floatingClock.setPauseResumeText(TreadmillBundle.message("button.start"));
            return;
        }

        SessionMode mode = SessionMode.fromId(session.modeId);
        distanceLabel.setText(String.format("%.2f %s",
                currentUnits.distanceFromKm(session.distanceKm), currentUnits.distanceUnit()));
        stepsLabel.setText(String.format("%,d steps", session.steps));
        caloriesLabel.setText(String.format("%.0f kcal", session.calories));
        if (mode == SessionMode.MARATHON) {
            targetLabel.setText("Open");
        } else if (mode == SessionMode.CALORIE_BURN) {
            targetLabel.setText(String.format("%.0f kcal", session.targetCalories));
        } else {
            targetLabel.setText(String.format("%.2f %s",
                    currentUnits.weightFromKg(session.targetFatKg), currentUnits.weightUnit()));
        }
        String status = engine.isRunning()
                ? TreadmillBundle.message("status.running")
                : session.completed ? TreadmillBundle.message("status.complete") : TreadmillBundle.message("status.paused");
        String statusNote = engine.getStatusNote();
        statusLabel.setText(statusNote.isBlank()
                ? status + " - " + session.name
                : status + " - " + session.name + " - " + statusNote);
        String buttonText = engine.isRunning()
                ? TreadmillBundle.message("button.pause")
                : session.completed ? TreadmillBundle.message("button.start") : TreadmillBundle.message("button.resume");
        startPauseButton.setText(buttonText);
        floatingClock.setPauseResumeText(buttonText);
    }

    private void saveCurrentSession(boolean showConfirmation) {
        SessionData session = engine.getSession();
        if (session == null) {
            SessionData built = buildSessionFromInputs();
            if (built == null) {
                return;
            }
            engine.loadSession(built);
        } else {
            engine.setSessionName(sessionNameField.getText());
        }
        engine.persistNow();
        if (showConfirmation) {
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message("notification.session.saved"));
        }
    }

    private void resetCurrentSession() {
        engine.reset();
        updateDisplay();
    }

    private void newSession() {
        engine.clearSession();
        SessionMode mode = selectedMode();
        sessionNameField.setText(defaultSessionName(mode));
        updateDisplay();
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
        updateDisplay();
        TreadmillNotifications.withUndo(
                project,
                TreadmillBundle.message("notification.session.deleted", deleted.name),
                () -> {
                    settings.saveSession(deleted);
                    engine.notifySessionsChanged();
                }
        );
    }

    private void refreshSavedSessions() {
        syncUnitsIfChanged();
        SessionData selected = sessionsList.getSelectedValue();
        String selectedId = selected == null ? null : selected.id;
        List<SessionData> sessions = settings.getSessions();
        sessions.sort(Comparator.comparingLong((SessionData s) -> s.createdMillis).reversed());
        sessionsModel.replaceAll(sessions);
        if (selectedId != null) {
            for (int i = 0; i < sessions.size(); i++) {
                if (selectedId.equals(sessions.get(i).id)) {
                    sessionsList.setSelectedIndex(i);
                    break;
                }
            }
        }
        updateStatsLabel(sessions);
    }

    private void updateStatsLabel(List<SessionData> sessions) {
        ZoneId zone = ZoneId.systemDefault();
        long startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long startOfWeek = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals today = SessionStats.totalsSince(sessions, startOfToday);
        SessionStats.Totals week = SessionStats.totalsSince(sessions, startOfWeek);
        SessionStats.Totals allTime = SessionStats.totalsSince(sessions, 0L);
        double[] daily = SessionStats.dailyDistanceKm(sessions, LocalDate.now(zone), zone, 14);
        dailyChart.setData(daily);
        int walkingDays = 0;
        for (double km : daily) {
            if (km > 0) {
                walkingDays++;
            }
        }
        // A single bar reads as a stray box, and today's numbers are already in the stats line.
        dailyChart.setVisible(walkingDays >= 2);
        int streak = SessionStats.streakDays(daily);
        String streakText = streak > 1 ? "   |   Streak " + streak + " days" : "";
        String unit = currentUnits.distanceUnit();
        statsLabel.setText(String.format(
                "Today %.1f %s · %.0f kcal   |   7 days %.1f %s · %.0f kcal   |   All time %.1f %s · %.0f kcal%s",
                currentUnits.distanceFromKm(today.distanceKm), unit, today.calories,
                currentUnits.distanceFromKm(week.distanceKm), unit, week.calories,
                currentUnits.distanceFromKm(allTime.distanceKm), unit, allTime.calories,
                streakText
        ));
        updateGoalProgress(today);
        statsLabel.setToolTipText(String.format(
                "Today: %,d steps in %d sessions | Last 7 days: %,d steps in %d sessions | All time: %,d steps in %d sessions",
                today.steps, today.sessionCount,
                week.steps, week.sessionCount,
                allTime.steps, allTime.sessionCount
        ));
    }

    private void updateGoalProgress(SessionStats.Totals today) {
        GoalType goalType = settings.getDailyGoalType();
        double target = settings.getDailyGoalValue();
        if (goalType == GoalType.NONE || target <= 0) {
            goalProgressBar.setVisible(false);
            return;
        }
        double progress = switch (goalType) {
            case STEPS -> today.steps;
            case DISTANCE -> today.distanceKm;
            case CALORIES -> today.calories;
            case NONE -> 0.0;
        };
        int percent = (int) Math.max(0, Math.min(100, Math.round(progress / target * 100)));
        goalProgressBar.setVisible(true);
        goalProgressBar.setValue(percent);
        goalProgressBar.setString("Daily goal: "
                + goalType.formatValue(progress, currentUnits)
                + " / " + goalType.formatValue(target, currentUnits)
                + " (" + percent + "%)");
    }

    private void syncUnitsIfChanged() {
        UnitSystem units = settings.getUnitSystem();
        if (units == currentUnits) {
            return;
        }
        UnitSystem previous = currentUnits;
        currentUnits = units;
        populatingFields = true;
        try {
            double speed = parseDouble(speedField.getText());
            if (speed > 0) {
                speedField.setText(format(units.speedFromKmh(previous.speedToKmh(speed))));
            }
            double fatTarget = parseDouble(fatTargetField.getText());
            if (fatTarget > 0) {
                fatTargetField.setText(format(units.weightFromKg(previous.weightToKg(fatTarget))));
            }
        } finally {
            populatingFields = false;
        }
        updateUnitLabels();
    }

    private void updateUnitLabels() {
        speedRowLabel.setText("Speed (" + currentUnits.speedUnit() + ")");
        fatTargetRowLabel.setText("Weight to burn (" + currentUnits.weightUnit() + ")");
    }

    /** Parses the speed field (display units) and returns km/h, or -1 when invalid. */
    private double parseSpeedKmh() {
        double display = parseDouble(speedField.getText());
        return display <= 0 ? -1.0 : currentUnits.speedToKmh(display);
    }

    private String speedRangeMessage() {
        return String.format("Enter a treadmill speed between 0 and %.1f %s.",
                currentUnits.speedFromKmh(25.0), currentUnits.speedUnit());
    }

    private void loadSelectedSession() {
        SessionData session = sessionsList.getSelectedValue();
        if (session != null) {
            loadSession(session);
        }
    }

    private void loadLastSessionOrDefault() {
        SessionData last = settings.findSession(settings.getLastSessionId());
        if (last != null) {
            loadSession(last);
        }
    }

    private void loadSession(SessionData session) {
        engine.loadSession(session.copy());
        populateFields(engine.getSession());
        updateDisplay();
    }

    private void populateFields(SessionData session) {
        populatingFields = true;
        try {
            modeCombo.setSelectedItem(SessionMode.fromId(session.modeId));
            targetCards.show(targetPanel, session.modeId);
            sessionNameField.setText(session.name);
            speedField.setText(format(currentUnits.speedFromKmh(session.speedKmh)));
            inclineField.setText(session.inclinePercent > 0 ? format(session.inclinePercent) : "0");
            algorithmCombo.setSelectedItem(CalorieAlgorithm.fromId(session.algorithmId));
            calorieTargetField.setText(format(session.targetCalories));
            fatTargetField.setText(format(currentUnits.weightFromKg(session.targetFatKg)));
        } finally {
            populatingFields = false;
        }
    }

    private boolean ensureProfile() {
        if (settings.getProfile().isComplete()) {
            return true;
        }
        ProfileDialog.showIfNeeded(project);
        return settings.getProfile().isComplete();
    }

    private SessionMode selectedMode() {
        Object selected = modeCombo.getSelectedItem();
        return selected instanceof SessionMode ? (SessionMode) selected : SessionMode.MARATHON;
    }

    private CalorieAlgorithm selectedAlgorithm() {
        Object selected = algorithmCombo.getSelectedItem();
        return selected instanceof CalorieAlgorithm ? (CalorieAlgorithm) selected : settings.getSelectedAlgorithm();
    }

    private void algorithmChanged() {
        ComboHelp.updateAlgorithmTooltip(algorithmCombo);
        if (populatingFields) {
            return;
        }
        engine.setAlgorithm(selectedAlgorithm());
        updateDisplay();
    }

    private void maybeShowHighSpeedPrompt(double speed) {
        if (speed <= 20.0) {
            highSpeedWarningShown = false;
            return;
        }
        if (!highSpeedWarningShown) {
            highSpeedWarningShown = true;
            Messages.showWarningDialog(project, "slow down coyote beep beep!!", "Speed Warning");
        }
    }

    private void installSpeedListener() {
        speedField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                speedChanged();
            }
        });
        inclineField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                inclineChanged();
            }
        });
    }

    private void installFieldValidators() {
        installValidator(speedField, () -> {
            double speed = parseSpeedKmh();
            return speed <= 0 || speed > 25
                    ? new ValidationInfo(String.format("Speed must be between 0 and %.1f %s",
                    currentUnits.speedFromKmh(25.0), currentUnits.speedUnit()), speedField)
                    : null;
        });
        installValidator(inclineField, () -> parseInclineOrDefault() < 0
                ? new ValidationInfo("Incline must be between 0 and 30 percent", inclineField)
                : null);
        installValidator(calorieTargetField, () -> {
            if (selectedMode() != SessionMode.CALORIE_BURN) {
                return null;
            }
            return parseDouble(calorieTargetField.getText()) <= 0
                    ? new ValidationInfo("Enter calories greater than zero", calorieTargetField)
                    : null;
        });
        installValidator(fatTargetField, () -> {
            if (selectedMode() != SessionMode.FAT_BURN) {
                return null;
            }
            return parseDouble(fatTargetField.getText()) <= 0
                    ? new ValidationInfo("Enter a KG target greater than zero", fatTargetField)
                    : null;
        });
    }

    private void installValidator(JBTextField field, Supplier<ValidationInfo> validator) {
        new ComponentValidator(this).withValidator(validator::get).installOn(field);
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                ComponentValidator.getInstance(field).ifPresent(ComponentValidator::revalidate);
            }
        });
    }

    private void installTargetPreviewListeners() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                previewInputsChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                previewInputsChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                previewInputsChanged();
            }
        };
        calorieTargetField.getDocument().addDocumentListener(listener);
        fatTargetField.getDocument().addDocumentListener(listener);
    }

    private void speedChanged() {
        if (populatingFields) {
            return;
        }
        applySpeedFromField(false);
        if (engine.getSession() == null) {
            updateDisplay();
        }
    }

    private void inclineChanged() {
        if (populatingFields) {
            return;
        }
        applyInclineFromField();
        if (engine.getSession() == null) {
            updateDisplay();
        }
    }

    private void applySpeedFromField(boolean showError) {
        double speed = parseSpeedKmh();
        if (speed <= 0 || speed > 25) {
            if (showError) {
                showError(speedRangeMessage());
            }
            return;
        }
        maybeShowHighSpeedPrompt(speed);
        engine.setSpeed(speed);
    }

    private void applyInclineFromField() {
        double incline = parseInclineOrDefault();
        if (incline >= 0) {
            engine.setIncline(incline);
        }
    }

    /** Returns the incline in percent, or -1 when the field text is invalid. */
    private double parseInclineOrDefault() {
        String text = inclineField.getText().trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        double incline = parseDouble(text);
        if (incline < 0 || incline > 30) {
            return -1.0;
        }
        return incline;
    }

    private void previewInputsChanged() {
        if (!populatingFields && engine.getSession() == null) {
            updateDisplay();
        }
    }

    private long previewSecondsFromInputs() {
        SessionMode mode = selectedMode();
        if (mode == SessionMode.MARATHON) {
            return 0L;
        }
        double speed = parseSpeedKmh();
        if (speed <= 0 || speed > 25) {
            return 0L;
        }
        double targetCalories;
        if (mode == SessionMode.CALORIE_BURN) {
            targetCalories = parseDouble(calorieTargetField.getText());
        } else {
            double fatInput = parseDouble(fatTargetField.getText());
            targetCalories = fatInput > 0 ? currentUnits.weightToKg(fatInput) * WorkoutMath.FAT_KCAL_PER_KG : -1.0;
        }
        if (targetCalories <= 0) {
            return 0L;
        }
        double incline = Math.max(0.0, parseInclineOrDefault());
        double caloriesPerMinute = selectedAlgorithm().kcalPerMinute(settings.getProfile(), speed, incline);
        if (caloriesPerMinute <= 0) {
            return 0L;
        }
        return WorkoutMath.secondsForCalories(targetCalories, caloriesPerMinute);
    }

    private String previewTargetText() {
        SessionMode mode = selectedMode();
        if (mode == SessionMode.MARATHON) {
            return "Open";
        }
        if (mode == SessionMode.CALORIE_BURN) {
            double targetCalories = parseDouble(calorieTargetField.getText());
            return targetCalories > 0 ? String.format("%.0f kcal", targetCalories) : "-";
        }
        double fatInput = parseDouble(fatTargetField.getText());
        return fatInput > 0 ? String.format("%.2f %s", fatInput, currentUnits.weightUnit()) : "-";
    }

    private void exportSessionsCsv() {
        List<SessionData> sessions = settings.getSessions();
        if (sessions.isEmpty()) {
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message("notification.export.none"));
            return;
        }
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                "Export Treadmill Sessions",
                "Save all saved sessions as a CSV file"
        );
        descriptor.withExtensionFilter("csv");
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((VirtualFile) null, "treadmill-sessions.csv");
        if (wrapper == null) {
            return;
        }
        StringBuilder csv = new StringBuilder(
                "name,mode,algorithm,created,speed_kmh,incline_percent,elapsed_seconds,distance_km,steps,calories,target_calories,target_fat_kg,completed\n");
        for (SessionData session : sessions) {
            csv.append(csvField(session.name)).append(',')
                    .append(SessionMode.fromId(session.modeId).getLabel()).append(',')
                    .append(CalorieAlgorithm.fromId(session.algorithmId).getLabel()).append(',')
                    .append(session.createdMillis > 0
                            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(session.createdMillis), ZoneId.systemDefault())
                            .format(SESSION_NAME_FORMAT)
                            : "").append(',')
                    .append(String.format("%.1f", session.speedKmh)).append(',')
                    .append(String.format("%.1f", session.inclinePercent)).append(',')
                    .append(session.elapsedSeconds).append(',')
                    .append(String.format("%.3f", session.distanceKm)).append(',')
                    .append(session.steps).append(',')
                    .append(String.format("%.1f", session.calories)).append(',')
                    .append(String.format("%.1f", session.targetCalories)).append(',')
                    .append(String.format("%.2f", session.targetFatKg)).append(',')
                    .append(session.completed)
                    .append('\n');
        }
        try {
            Files.writeString(wrapper.getFile().toPath(), csv.toString());
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message("notification.export.done", sessions.size(), wrapper.getFile().getName()));
        } catch (IOException exception) {
            showError("Could not write CSV file: " + exception.getMessage());
        }
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private String describeSession(SessionData session) {
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

    private static String defaultSessionName(SessionMode mode) {
        return mode.getLabel() + " " + LocalDateTime.now().format(SESSION_NAME_FORMAT);
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private static String format(double value) {
        if (value <= 0) {
            return "";
        }
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }

    private void showError(String message) {
        Messages.showErrorDialog(project, message, "Treadmill Buddy");
    }
}
