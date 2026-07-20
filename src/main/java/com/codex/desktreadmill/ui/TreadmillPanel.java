package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedPreset;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillConfigurable;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tool window content: clock, session form, metric tiles, and buttons.
 * Aggregate views live in {@link StatsPanel}; the history list with its
 * import/export toolbar lives in {@link SavedSessionsPanel}.
 */
public final class TreadmillPanel extends JPanel implements WorkoutEngine.Listener, Disposable {
    private static final DateTimeFormatter SESSION_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Project project;
    private final TreadmillSettings settings = TreadmillSettings.getInstance();
    private final WorkoutEngine engine = WorkoutEngine.getInstance();
    private final DigitalClockDisplay clockDisplay = new DigitalClockDisplay();
    private final ComboBox<SessionMode> modeCombo = new ComboBox<>(SessionMode.values());
    private final ComboBox<CalorieAlgorithm> algorithmCombo = new ComboBox<>(CalorieAlgorithm.values());
    private final JBTextField sessionNameField = new JBTextField();
    private final JBTextField speedField = new JBTextField("3.0");
    private final JBTextField inclineField = new JBTextField("0");
    private final JBTextField calorieTargetField = new JBTextField("300");
    private final JBTextField fatTargetField = new JBTextField("0.5");
    private final JBTextField walkMinutesField = new JBTextField("25");
    private final JBTextField breakMinutesField = new JBTextField("5");
    private final StatsPanel statsPanel = new StatsPanel(TreadmillSettings.getInstance());
    private final SavedSessionsPanel savedSessionsPanel;
    private final JBLabel speedRowLabel = new JBLabel();
    private final JBLabel fatTargetRowLabel = new JBLabel();
    private final JBLabel distanceLabel = valueLabel("0.00 km");
    private final JBLabel stepsLabel = valueLabel("0 steps");
    private final JBLabel caloriesLabel = valueLabel("0 kcal");
    private final JBLabel targetLabel = valueLabel("-");
    private final JButton startPauseButton = new JButton(TreadmillBundle.message("button.start"));
    private final JButton floatButton = new JButton(TreadmillBundle.message("button.floatClock"));
    private final JButton saveButton = new JButton(TreadmillBundle.message("button.saveSession"));
    private final JButton resetButton = new JButton(TreadmillBundle.message("button.reset"));
    private final JButton newButton = new JButton(TreadmillBundle.message("button.new"));
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
        savedSessionsPanel = new SavedSessionsPanel(project, settings, engine, this::loadSession, () -> currentUnits);

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
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.caloriesToBurn")), calorieTargetField, 1, false)
                .getPanel();
        JPanel fatCard = FormBuilder.createFormBuilder()
                .addLabeledComponent(fatTargetRowLabel, fatTargetField, 1, false)
                .getPanel();
        JPanel intervalCard = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.walkBlock")), walkMinutesField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.breakBlock")), breakMinutesField, 1, false)
                .getPanel();
        JPanel emptyCard = new JPanel(new BorderLayout());
        targetPanel.add(emptyCard, SessionMode.MARATHON.name());
        targetPanel.add(calorieCard, SessionMode.CALORIE_BURN.name());
        targetPanel.add(fatCard, SessionMode.FAT_BURN.name());
        targetPanel.add(intervalCard, SessionMode.INTERVAL.name());

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.savedSessions")), savedSessionsPanel.getComponent(), 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.mode")), modeCombo, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.sessionName")), sessionNameField, 1, false)
                .addLabeledComponent(speedRowLabel, createSpeedRow(), 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.incline")), inclineField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("panel.algorithm")), algorithmCombo, 1, false)
                .addComponent(targetPanel)
                .getPanel();
    }

    private JComponent createSpeedRow() {
        JButton presetsButton = new JButton(TreadmillBundle.message("button.presets"));
        presetsButton.setToolTipText(TreadmillBundle.message("button.presets.tooltip"));
        presetsButton.addActionListener(event -> showPresetsPopup(presetsButton));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(speedField, BorderLayout.CENTER);
        row.add(presetsButton, BorderLayout.EAST);
        return row;
    }

    private void showPresetsPopup(JComponent anchor) {
        DefaultActionGroup group = new DefaultActionGroup();
        List<SpeedPreset> presets = settings.getSpeedPresets();
        for (SpeedPreset preset : presets) {
            String label = String.format("%s  (%s %s)", preset.name,
                    format(currentUnits.speedFromKmh(preset.speedKmh)), currentUnits.speedUnit());
            group.add(new DumbAwareAction(label) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    speedField.setText(format(currentUnits.speedFromKmh(preset.speedKmh)));
                }
            });
        }
        if (!presets.isEmpty()) {
            group.addSeparator();
        }
        group.add(new DumbAwareAction(TreadmillBundle.message("presets.saveCurrent")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                saveSpeedPreset();
            }
        });
        if (!presets.isEmpty()) {
            DefaultActionGroup removeGroup = DefaultActionGroup.createPopupGroup(
                    () -> TreadmillBundle.message("presets.removeGroup"));
            for (SpeedPreset preset : presets) {
                removeGroup.add(new DumbAwareAction(preset.name) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent event) {
                        settings.removeSpeedPreset(preset.name);
                    }
                });
            }
            group.add(removeGroup);
        }
        JBPopupFactory.getInstance()
                .createActionGroupPopup(TreadmillBundle.message("presets.popup.title"), group,
                        DataManager.getInstance().getDataContext(anchor),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showUnderneathOf(anchor);
    }

    private void saveSpeedPreset() {
        double speedKmh = parseSpeedKmh();
        if (speedKmh <= 0 || speedKmh > 25) {
            showError(speedRangeMessage());
            return;
        }
        String defaultName = format(currentUnits.speedFromKmh(speedKmh)) + " " + currentUnits.speedUnit();
        String name = Messages.showInputDialog(project,
                TreadmillBundle.message("presets.dialog.message"),
                TreadmillBundle.message("presets.dialog.title"), null, defaultName, null);
        if (name == null || name.isBlank()) {
            return;
        }
        settings.addSpeedPreset(new SpeedPreset(name.trim(), speedKmh));
    }

    private JComponent createMetrics() {
        JPanel metrics = new JPanel(new GridLayout(2, 2, 8, 8));
        metrics.add(metricTile("Distance", distanceLabel));
        metrics.add(metricTile("Steps", stepsLabel));
        metrics.add(metricTile("Calories", caloriesLabel));
        metrics.add(metricTile("Target", targetLabel));

        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.add(metrics, BorderLayout.CENTER);
        wrapper.add(statsPanel, BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent createButtons() {
        JButton settingsButton = new JButton(TreadmillBundle.message("button.settings"));
        settingsButton.setToolTipText(TreadmillBundle.message("button.settings.tooltip"));
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
        } else if (mode == SessionMode.INTERVAL) {
            long walkMinutes = Math.round(parseDouble(walkMinutesField.getText()));
            long breakMinutes = Math.round(parseDouble(breakMinutesField.getText()));
            if (walkMinutes <= 0 || walkMinutes > 720 || breakMinutes <= 0 || breakMinutes > 720) {
                showError("Enter walk and break blocks between 1 and 720 minutes.");
                return null;
            }
            session.intervalWalkSeconds = walkMinutes * 60L;
            session.intervalBreakSeconds = breakMinutes * 60L;
            session.intervalWalking = true;
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
            if (mode == SessionMode.INTERVAL) {
                seconds = WorkoutMath.intervalBlockRemaining(session);
            } else {
                seconds = mode == SessionMode.MARATHON ? session.elapsedSeconds : session.remainingSeconds;
            }
        } else {
            seconds = previewSecondsFromInputs();
        }
        TimeFormatter.DisplayTime displayTime = TimeFormatter.displayTime(seconds);
        String clockPrefix = displayTime.getDayPrefix();
        // Interval blocks are always well under a day, so the day-prefix slot
        // is free to show which block the clock is counting down.
        if (session != null && SessionMode.fromId(session.modeId) == SessionMode.INTERVAL) {
            clockPrefix = session.intervalWalking ? "Walk" : "Break";
        }
        clockDisplay.setDisplay(clockPrefix, displayTime.getTimeText());
        floatingClock.setDisplay(clockPrefix, displayTime.getTimeText());

        if (session == null) {
            distanceLabel.setText("0.00 " + currentUnits.distanceUnit());
            stepsLabel.setText("0 steps");
            caloriesLabel.setText("0 kcal");
            targetLabel.setText(previewTargetText());
            statsPanel.setStatus(TreadmillBundle.message("status.ready"));
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
            targetLabel.setText(TreadmillBundle.message("panel.target.open"));
        } else if (mode == SessionMode.CALORIE_BURN) {
            targetLabel.setText(String.format("%.0f kcal", session.targetCalories));
        } else if (mode == SessionMode.INTERVAL) {
            targetLabel.setText(String.format("%d / %d min",
                    session.intervalWalkSeconds / 60, session.intervalBreakSeconds / 60));
        } else {
            targetLabel.setText(String.format("%.2f %s",
                    currentUnits.weightFromKg(session.targetFatKg), currentUnits.weightUnit()));
        }
        distanceLabel.setToolTipText(segmentsTooltip(session));
        String status = engine.isRunning()
                ? TreadmillBundle.message("status.running")
                : session.completed ? TreadmillBundle.message("status.complete") : TreadmillBundle.message("status.paused");
        if (mode == SessionMode.INTERVAL && engine.isRunning()) {
            status += session.intervalWalking
                    ? " - " + TreadmillBundle.message("status.interval.walk")
                    : " - " + TreadmillBundle.message("status.interval.break");
        }
        String statusNote = engine.getStatusNote();
        statsPanel.setStatus(statusNote.isBlank()
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

    private void refreshSavedSessions() {
        syncUnitsIfChanged();
        List<SessionData> sessions = settings.getSessions();
        sessions.sort(Comparator.comparingLong((SessionData s) -> s.createdMillis).reversed());
        savedSessionsPanel.refresh(sessions);
        // Stats always cover the full history, not just the visible list slice.
        statsPanel.update(sessions, currentUnits);
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
        speedRowLabel.setText(TreadmillBundle.message("panel.speed", currentUnits.speedUnit()));
        fatTargetRowLabel.setText(TreadmillBundle.message("panel.weightToBurn", currentUnits.weightUnit()));
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
            if (session.intervalWalkSeconds > 0) {
                walkMinutesField.setText(String.valueOf(session.intervalWalkSeconds / 60));
                breakMinutesField.setText(String.valueOf(session.intervalBreakSeconds / 60));
            }
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
        installValidator(walkMinutesField, () -> intervalMinutesValidation(walkMinutesField));
        installValidator(breakMinutesField, () -> intervalMinutesValidation(breakMinutesField));
    }

    private ValidationInfo intervalMinutesValidation(JBTextField field) {
        if (selectedMode() != SessionMode.INTERVAL) {
            return null;
        }
        double minutes = parseDouble(field.getText());
        return minutes <= 0 || minutes > 720
                ? new ValidationInfo("Enter minutes between 1 and 720", field)
                : null;
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
        walkMinutesField.getDocument().addDocumentListener(listener);
        breakMinutesField.getDocument().addDocumentListener(listener);
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

    /** Multi-speed sessions get a per-speed breakdown tooltip on the distance tile. */
    private String segmentsTooltip(SessionData session) {
        if (session.segments.size() < 2) {
            return null;
        }
        StringBuilder text = new StringBuilder("<html><b>Speed breakdown</b><br>");
        for (SpeedSegment segment : session.segments) {
            String duration = segment.seconds < 60 ? "&lt;1 min" : (segment.seconds / 60) + " min";
            text.append(String.format("%s @ %s %s<br>",
                    duration, format(currentUnits.speedFromKmh(segment.speedKmh)), currentUnits.speedUnit()));
        }
        return text.append("</html>").toString();
    }

    private long previewSecondsFromInputs() {
        SessionMode mode = selectedMode();
        if (mode == SessionMode.MARATHON) {
            return 0L;
        }
        if (mode == SessionMode.INTERVAL) {
            double walkMinutes = parseDouble(walkMinutesField.getText());
            return walkMinutes > 0 && walkMinutes <= 720 ? Math.round(walkMinutes) * 60L : 0L;
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
            return TreadmillBundle.message("panel.target.open");
        }
        if (mode == SessionMode.CALORIE_BURN) {
            double targetCalories = parseDouble(calorieTargetField.getText());
            return targetCalories > 0 ? String.format("%.0f kcal", targetCalories) : "-";
        }
        if (mode == SessionMode.INTERVAL) {
            double walkMinutes = parseDouble(walkMinutesField.getText());
            double breakMinutes = parseDouble(breakMinutesField.getText());
            return walkMinutes > 0 && breakMinutes > 0
                    ? String.format("%d / %d min", Math.round(walkMinutes), Math.round(breakMinutes))
                    : "-";
        }
        double fatInput = parseDouble(fatTargetField.getText());
        return fatInput > 0 ? String.format("%.2f %s", fatInput, currentUnits.weightUnit()) : "-";
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
