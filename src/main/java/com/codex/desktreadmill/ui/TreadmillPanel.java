package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.engine.WorkoutInputs;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedPreset;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UnitSystem;
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
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
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
    private final MetricInput speedInput = new MetricInput(MetricInput.Quantity.SPEED, 2);
    private final MetricInput fatInput = new MetricInput(MetricInput.Quantity.WEIGHT, 2);
    private final JBTextField inclineField = new JBTextField("0");
    private final JBTextField calorieTargetField = new JBTextField("300");
    private final JBTextField fatTargetField = new JBTextField("0.5");
    private final JBTextField walkMinutesField = new JBTextField("25");
    private final JBTextField breakMinutesField = new JBTextField("5");
    private final StatsPanel statsPanel = new StatsPanel(TreadmillSettings.getInstance());
    private final SavedSessionsPanel savedSessionsPanel;
    private final JBLabel speedRowLabel = new JBLabel();
    private final JBLabel fatTargetRowLabel = new JBLabel();
    // Filled by updateDisplay() at the end of the constructor.
    private final JBLabel distanceLabel = valueLabel();
    private final JBLabel stepsLabel = valueLabel();
    private final JBLabel caloriesLabel = valueLabel();
    private final JBLabel targetLabel = valueLabel();
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
    /**
     * What this panel last saw of the shared session. The engine is
     * application-wide, so a change made in another project window has to be
     * mirrored into these fields - without clobbering an edit in progress here.
     */
    private String seenSessionId;
    private double seenSpeedKmh;
    private double seenInclinePercent;
    private String seenAlgorithmId = "";
    private String seenName = "";
    private double seenTargetCalories;
    private double seenTargetFatKg;
    private long seenWalkSeconds;
    private long seenBreakSeconds;

    public TreadmillPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));
        currentUnits = settings.getUnitSystem();
        speedField.setText(speedInput.display(3.0, currentUnits));
        fatTargetField.setText(fatInput.display(0.5, currentUnits));
        updateUnitLabels();
        ComboHelp.configureModeCombo(modeCombo);
        ComboHelp.configureAlgorithmCombo(algorithmCombo, settings::getSelectedAlgorithm);
        algorithmCombo.setSelectedItem(settings.getSelectedAlgorithm());
        sessionNameField.setText(defaultSessionName(SessionMode.MARATHON));
        floatingClock = new FloatingClockWindow(project, this::toggleRunning, () -> saveCurrentSession(true));
        savedSessionsPanel = new SavedSessionsPanel(
                project, settings, engine, this::loadSession, () -> currentUnits, this);

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
        mirrorSharedSession();
        updateDisplay();
    }

    /**
     * Follows the shared session into this panel's editable fields. A session
     * this panel hasn't seen (started or loaded in another window) repopulates
     * everything; for the session it already shows, only a field whose engine
     * value moved since this panel last looked is refreshed, and only when the
     * field doesn't already say so - that keeps a keystroke this very panel
     * just pushed to the engine from bouncing back as a reformat. Without
     * this, Resume or Save in a second window pushed that window's stale
     * speed, incline, or name over the walk in progress.
     */
    private void mirrorSharedSession() {
        SessionData session = engine.getSession();
        if (session == null) {
            seenSessionId = null;
            return;
        }
        if (!session.id.equals(seenSessionId)) {
            populateFields(session);
            return;
        }
        populatingFields = true;
        try {
            if (session.speedKmh != seenSpeedKmh) {
                seenSpeedKmh = session.speedKmh;
                double fieldSpeed = parseSpeedKmh();
                String display = speedInput.display(session.speedKmh, currentUnits);
                if (Math.abs(fieldSpeed - session.speedKmh) > 0.001) {
                    speedField.setText(display);
                }
            }
            if (session.inclinePercent != seenInclinePercent) {
                seenInclinePercent = session.inclinePercent;
                if (Math.abs(Math.max(0.0, parseInclineOrDefault()) - session.inclinePercent) > 0.001) {
                    inclineField.setText(session.inclinePercent > 0 ? format(session.inclinePercent) : "0");
                }
            }
            if (!session.algorithmId.equals(seenAlgorithmId)) {
                seenAlgorithmId = session.algorithmId;
                algorithmCombo.setSelectedItem(CalorieAlgorithm.fromId(session.algorithmId));
            }
            if (!session.name.equals(seenName)) {
                seenName = session.name;
                if (!sessionNameField.getText().trim().equals(session.name)) {
                    sessionNameField.setText(session.name);
                }
            }
            if (session.targetCalories != seenTargetCalories) {
                seenTargetCalories = session.targetCalories;
                calorieTargetField.setText(format(session.targetCalories));
            }
            if (session.targetFatKg != seenTargetFatKg) {
                seenTargetFatKg = session.targetFatKg;
                fatTargetField.setText(fatInput.display(session.targetFatKg, currentUnits));
            }
            if (session.intervalWalkSeconds != seenWalkSeconds || session.intervalBreakSeconds != seenBreakSeconds) {
                seenWalkSeconds = session.intervalWalkSeconds;
                seenBreakSeconds = session.intervalBreakSeconds;
                walkMinutesField.setText(String.valueOf(seenWalkSeconds / 60));
                breakMinutesField.setText(String.valueOf(seenBreakSeconds / 60));
            }
        } finally {
            populatingFields = false;
        }
    }

    private void rememberSeen(SessionData session) {
        seenSessionId = session.id;
        seenSpeedKmh = session.speedKmh;
        seenInclinePercent = session.inclinePercent;
        seenAlgorithmId = session.algorithmId;
        seenName = session.name;
        seenTargetCalories = session.targetCalories;
        seenTargetFatKg = session.targetFatKg;
        seenWalkSeconds = session.intervalWalkSeconds;
        seenBreakSeconds = session.intervalBreakSeconds;
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
            String label = TreadmillBundle.message("presets.item", preset.name,
                    format(currentUnits.speedFromKmh(preset.speedKmh)), currentUnits.speedUnit());
            group.add(new DumbAwareAction(label) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    speedField.setText(speedInput.display(preset.speedKmh, currentUnits));
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
        metrics.add(metricTile(TreadmillBundle.message("panel.tile.distance"), distanceLabel));
        metrics.add(metricTile(TreadmillBundle.message("panel.tile.steps"), stepsLabel));
        metrics.add(metricTile(TreadmillBundle.message("panel.tile.calories"), caloriesLabel));
        metrics.add(metricTile(TreadmillBundle.message("panel.tile.target"), targetLabel));

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

    private static JBLabel valueLabel() {
        JBLabel label = new JBLabel();
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private static JComponent metricTile(String title, JBLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
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
        // Clear first: the default name below is pushed to the engine as it
        // is typed, and would otherwise rename the session being dropped.
        engine.clearSession();
        sessionNameField.setText(defaultSessionName(mode));
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
            WorkoutInputs inputs = validatedInputs();
            if (inputs == null || !engine.resume(inputs)) {
                return;
            }
        }
        // Opens with the session, but stays away once the user has closed it.
        floatingClock.showIfWanted();
    }

    private WorkoutInputs readInputs() {
        return new WorkoutInputs(selectedMode(), selectedAlgorithm(), parseSpeedKmh(), parseInclineOrDefault(),
                parseDouble(calorieTargetField.getText()), fatInput.read(fatTargetField.getText(), currentUnits),
                parseDouble(walkMinutesField.getText()), parseDouble(breakMinutesField.getText()));
    }

    private WorkoutInputs validatedInputs() {
        WorkoutInputs inputs = readInputs();
        WorkoutInputs.Field invalid = inputs.invalidField(settings.getProfile());
        if (invalid != null) {
            showError(inputError(invalid));
            return null;
        }
        maybeShowHighSpeedPrompt(inputs.speedKmh());
        return inputs;
    }

    private String inputError(WorkoutInputs.Field field) {
        return switch (field) {
            case SPEED -> speedRangeMessage();
            case INCLINE -> TreadmillBundle.message("error.incline");
            case CALORIES -> TreadmillBundle.message("error.calories");
            case FAT -> TreadmillBundle.message("error.weightTarget");
            case WALK, BREAK -> TreadmillBundle.message("error.intervalMinutes");
            case BURN_RATE -> TreadmillBundle.message("error.zeroBurnRate");
            case TARGET_LIMIT -> TreadmillBundle.message("dialog.goalTooLarge.message");
        };
    }

    private SessionData buildSessionFromInputs() {
        WorkoutInputs inputs = validatedInputs();
        if (inputs == null) {
            return null;
        }
        SessionData session = inputs.createSession(settings.getProfile());
        session.createdMillis = System.currentTimeMillis();
        session.id = String.valueOf(session.createdMillis);
        session.name = sessionNameField.getText().trim().isBlank()
                ? defaultSessionName(inputs.mode()) : sessionNameField.getText().trim();
        return session;
    }

    private void updateDisplay() {
        syncUnitsIfChanged();
        SessionData session = engine.getSession();
        boolean targetsEditable = !engine.isRunning();
        calorieTargetField.setEnabled(targetsEditable);
        fatTargetField.setEnabled(targetsEditable);
        walkMinutesField.setEnabled(targetsEditable);
        breakMinutesField.setEnabled(targetsEditable);
        long seconds = session != null ? WorkoutMath.displaySeconds(session) : previewSecondsFromInputs();
        TimeFormatter.DisplayTime displayTime = TimeFormatter.displayTime(seconds);
        String clockPrefix = displayTime.getDayPrefix();
        // Interval blocks are always well under a day, so the day-prefix slot
        // is free to show which block the clock is counting down.
        if (session != null && SessionMode.fromId(session.modeId) == SessionMode.INTERVAL
                && WorkoutMath.hasIntervalBlocks(session)) {
            clockPrefix = TreadmillBundle.message(session.intervalWalking ? "clock.prefix.walk" : "clock.prefix.break");
        }
        clockDisplay.setDisplay(clockPrefix, displayTime.getTimeText());
        floatingClock.setDisplay(clockPrefix, displayTime.getTimeText());

        if (session == null) {
            distanceLabel.setText(distanceText(0.0));
            stepsLabel.setText(stepsText(0L));
            caloriesLabel.setText(kcalText(0.0));
            targetLabel.setText(previewTargetText());
            statsPanel.setStatus(TreadmillBundle.message("status.ready"));
            startPauseButton.setText(TreadmillBundle.message("button.start"));
            floatingClock.setPauseResumeText(TreadmillBundle.message("button.start"));
            return;
        }

        SessionMode mode = SessionMode.fromId(session.modeId);
        distanceLabel.setText(distanceText(session.distanceKm));
        stepsLabel.setText(stepsText(session.steps));
        caloriesLabel.setText(kcalText(session.calories));
        if (mode == SessionMode.MARATHON) {
            targetLabel.setText(TreadmillBundle.message("panel.target.open"));
        } else if (mode == SessionMode.CALORIE_BURN) {
            targetLabel.setText(kcalText(session.targetCalories));
        } else if (mode == SessionMode.INTERVAL) {
            targetLabel.setText(WorkoutMath.hasIntervalBlocks(session)
                    ? intervalBlocksText(session.intervalWalkSeconds / 60, session.intervalBreakSeconds / 60)
                    : TreadmillBundle.message("panel.target.open"));
        } else {
            targetLabel.setText(weightText(currentUnits.weightFromKg(session.targetFatKg)));
        }
        distanceLabel.setToolTipText(segmentsTooltip(session));
        String status = engine.isRunning()
                ? TreadmillBundle.message("status.running")
                : session.completed ? TreadmillBundle.message("status.complete") : TreadmillBundle.message("status.paused");
        if (mode == SessionMode.INTERVAL && WorkoutMath.hasIntervalBlocks(session) && engine.isRunning()) {
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
            if (!engine.isRunning() && !session.completed) {
                WorkoutInputs inputs = validatedInputs();
                if (inputs == null || !engine.applyInputs(inputs)) {
                    return;
                }
            }
            engine.setSessionName(sessionNameField.getText());
        }
        boolean persisted = engine.persistNow();
        if (showConfirmation) {
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message(persisted ? "notification.session.saved" : "notification.session.saveFailed"));
        }
    }

    /**
     * Reset zeroes the session on the clock and persists that under the same
     * id - which, for a walk loaded from the history, erases a real record.
     * So a session with walked time asks first and offers an undo afterwards.
     */
    private void resetCurrentSession() {
        SessionData session = engine.getSession();
        if (session == null) {
            return;
        }
        SessionData before = session.copy();
        if (before.elapsedSeconds > 0) {
            int answer = Messages.showYesNoDialog(project,
                    TreadmillBundle.message("reset.confirm.message",
                            before.name, TimeFormatter.displayTime(before.elapsedSeconds).getTimeText()),
                    TreadmillBundle.message("reset.confirm.title"), Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
        }
        engine.reset();
        updateDisplay();
        if (before.elapsedSeconds > 0) {
            TreadmillNotifications.withUndo(project,
                    TreadmillBundle.message("notification.session.reset", StringUtil.escapeXmlEntities(before.name)),
                    () -> engine.restoreSession(before));
        }
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
            double speed = speedInput.read(speedField.getText(), previous);
            if (speed > 0) {
                speedField.setText(speedInput.display(speed, units));
            }
            double fatTarget = fatInput.read(fatTargetField.getText(), previous);
            if (fatTarget > 0) {
                fatTargetField.setText(fatInput.display(fatTarget, units));
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
        return speedInput.read(speedField.getText(), currentUnits);
    }

    private String speedRangeMessage() {
        return TreadmillBundle.message("error.speedRange", maxSpeedText(), currentUnits.speedUnit());
    }

    private String maxSpeedText() {
        return String.format("%.1f", currentUnits.speedFromKmh(25.0));
    }

    private String distanceText(double km) {
        return TreadmillBundle.message("panel.value.distance",
                String.format("%.2f", currentUnits.distanceFromKm(km)), currentUnits.distanceUnit());
    }

    private static String stepsText(long steps) {
        return TreadmillBundle.message("panel.value.steps", steps);
    }

    private static String kcalText(double kcal) {
        return TreadmillBundle.message("panel.value.kcal", String.format("%.0f", kcal));
    }

    private static String intervalBlocksText(long walkMinutes, long breakMinutes) {
        return TreadmillBundle.message("panel.value.intervalBlocks", walkMinutes, breakMinutes);
    }

    private String weightText(double displayWeight) {
        return TreadmillBundle.message("panel.value.weight", String.format("%.2f", displayWeight), currentUnits.weightUnit());
    }

    private void loadLastSessionOrDefault() {
        SessionData last = settings.findSession(settings.getLastSessionId());
        if (last != null) {
            loadSession(last);
        }
    }

    private void loadSession(SessionData session) {
        engine.loadSession(session.copy());
        if (engine.getSession() != null) {
            populateFields(engine.getSession());
        }
        updateDisplay();
    }

    private void populateFields(SessionData session) {
        populatingFields = true;
        try {
            modeCombo.setSelectedItem(SessionMode.fromId(session.modeId));
            targetCards.show(targetPanel, session.modeId);
            sessionNameField.setText(session.name);
            speedField.setText(speedInput.display(session.speedKmh, currentUnits));
            inclineField.setText(session.inclinePercent > 0 ? format(session.inclinePercent) : "0");
            algorithmCombo.setSelectedItem(CalorieAlgorithm.fromId(session.algorithmId));
            calorieTargetField.setText(format(session.targetCalories));
            fatTargetField.setText(fatInput.display(session.targetFatKg, currentUnits));
            if (session.intervalWalkSeconds > 0) {
                walkMinutesField.setText(String.valueOf(session.intervalWalkSeconds / 60));
                breakMinutesField.setText(String.valueOf(session.intervalBreakSeconds / 60));
            }
            rememberSeen(session);
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
            Messages.showWarningDialog(project,
                    TreadmillBundle.message("dialog.speedWarning.message"),
                    TreadmillBundle.message("dialog.speedWarning.title"));
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
        // The name follows the field as it is typed, like speed and incline,
        // so every window shows the same name and Save has nothing to catch up.
        sessionNameField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                if (!populatingFields) {
                    engine.setSessionName(sessionNameField.getText());
                }
            }
        });
    }

    private void installFieldValidators() {
        installInputValidator(speedField, WorkoutInputs.Field.SPEED);
        installInputValidator(inclineField, WorkoutInputs.Field.INCLINE);
        installInputValidator(calorieTargetField, WorkoutInputs.Field.CALORIES);
        installInputValidator(fatTargetField, WorkoutInputs.Field.FAT);
        installInputValidator(walkMinutesField, WorkoutInputs.Field.WALK);
        installInputValidator(breakMinutesField, WorkoutInputs.Field.BREAK);
    }

    private void installInputValidator(JBTextField field, WorkoutInputs.Field key) {
        installValidator(field, () -> readInputs().valid(key, settings.getProfile())
                ? null : new ValidationInfo(inputError(key), field));
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
        for (JBTextField field : List.of(calorieTargetField, fatTargetField, walkMinutesField, breakMinutesField)) {
            field.setToolTipText(TreadmillBundle.message("panel.targets.editHint"));
        }
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
        StringBuilder text = new StringBuilder("<html><b>")
                .append(StringUtil.escapeXmlEntities(TreadmillBundle.message("tooltip.segments.title")))
                .append("</b><br>");
        for (SpeedSegment segment : session.segments) {
            String duration = segment.seconds < 60
                    ? TreadmillBundle.message("tooltip.segments.underMinute")
                    : TreadmillBundle.message("tooltip.segments.minutes", segment.seconds / 60);
            text.append(StringUtil.escapeXmlEntities(TreadmillBundle.message("tooltip.segments.row",
                    duration, format(currentUnits.speedFromKmh(segment.speedKmh)), currentUnits.speedUnit())));
            text.append("<br>");
        }
        return text.append("</html>").toString();
    }

    private long previewSecondsFromInputs() {
        WorkoutInputs inputs = readInputs();
        return inputs.invalidField(settings.getProfile()) == null
                ? WorkoutMath.displaySeconds(inputs.createSession(settings.getProfile())) : 0;
    }

    private String previewTargetText() {
        WorkoutInputs inputs = readInputs();
        if (inputs.mode() == SessionMode.MARATHON) {
            return TreadmillBundle.message("panel.target.open");
        }
        if (inputs.invalidField(settings.getProfile()) != null) {
            return TreadmillBundle.message("panel.value.none");
        }
        return switch (inputs.mode()) {
            case CALORIE_BURN -> kcalText(inputs.calorieTarget());
            case INTERVAL -> intervalBlocksText((long) inputs.walkMinutes(), (long) inputs.breakMinutes());
            case FAT_BURN -> weightText(currentUnits.weightFromKg(inputs.targetFatKg()));
            case MARATHON -> TreadmillBundle.message("panel.target.open");
        };
    }

    private static String defaultSessionName(SessionMode mode) {
        return mode.getLabel() + " " + LocalDateTime.now().format(SESSION_NAME_FORMAT);
    }

    /** The field's number, or -1 when it is not a usable one: "NaN" parses but passes every range check. */
    private static double parseDouble(String value) {
        return NumericInput.parse(value);
    }

    private static String format(double value) {
        if (value <= 0) {
            return "";
        }
        return NumericInput.format(value, 2);
    }

    private void showError(String message) {
        Messages.showErrorDialog(project, message, TreadmillBundle.message("notification.title"));
    }
}
