package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.engine.WorkoutInputs;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillConfigurable;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
import java.util.Set;
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
    /** The name this panel generated last; a field still showing it was not named by the user. */
    private String generatedName = "";
    private UnitSystem currentUnits;
    private final SeenSession seen = new SeenSession();

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
        showDefaultName(SessionMode.MARATHON);
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
            if (seen.forget()) {
                // The walk left the clock (New in any window, a deletion, another
                // IDE): the next Start must not inherit its name and timestamp.
                showDefaultName(selectedMode());
            }
            return;
        }
        if (!seen.isShowing(session)) {
            populateFields(session);
            return;
        }
        Set<SeenSession.Field> changed = seen.update(session);
        if (changed.isEmpty()) {
            return;
        }
        populatingFields = true;
        try {
            if (changed.contains(SeenSession.Field.SPEED)) {
                // Read before display(): it resets the exact value the read compares against.
                double fieldSpeed = parseSpeedKmh();
                String display = speedInput.display(session.speedKmh, currentUnits);
                if (Math.abs(fieldSpeed - session.speedKmh) > 0.001) {
                    speedField.setText(display);
                }
            }
            if (changed.contains(SeenSession.Field.INCLINE)
                    && Math.abs(Math.max(0.0, parseInclineOrDefault()) - session.inclinePercent) > 0.001) {
                inclineField.setText(session.inclinePercent > 0 ? format(session.inclinePercent) : "0");
            }
            if (changed.contains(SeenSession.Field.ALGORITHM)) {
                algorithmCombo.setSelectedItem(CalorieAlgorithm.fromId(session.algorithmId));
            }
            if (changed.contains(SeenSession.Field.NAME) && !sessionNameField.getText().trim().equals(session.name)) {
                sessionNameField.setText(session.name);
            }
            if (changed.contains(SeenSession.Field.CALORIE_TARGET)) {
                calorieTargetField.setText(format(session.targetCalories));
            }
            if (changed.contains(SeenSession.Field.FAT_TARGET)) {
                fatTargetField.setText(fatInput.display(session.targetFatKg, currentUnits));
            }
            if (changed.contains(SeenSession.Field.INTERVALS)) {
                walkMinutesField.setText(String.valueOf(session.intervalWalkSeconds / 60));
                breakMinutesField.setText(String.valueOf(session.intervalBreakSeconds / 60));
            }
        } finally {
            populatingFields = false;
        }
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
        presetsButton.addActionListener(event -> new SpeedPresetsPopup(project, settings, () -> currentUnits,
                this::parseSpeedKmh, speed -> speedField.setText(speedInput.display(speed, currentUnits)),
                () -> inputError(WorkoutInputs.Field.SPEED)).showUnderneathOf(presetsButton));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(speedField, BorderLayout.CENTER);
        row.add(presetsButton, BorderLayout.EAST);
        return row;
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
        // Clear first: a name typed into the field is pushed to the engine,
        // and would otherwise rename the session being dropped.
        engine.clearSession();
        showDefaultName(mode);
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
        JBTextField source = textFieldFor(field);
        String ambiguity = source == null ? null : NumericInput.ambiguityMessage(source.getText());
        if (ambiguity != null) {
            return ambiguity;
        }
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

    /** The text field a validation failure comes from, or null for a computed value such as the burn rate. */
    private @Nullable JBTextField textFieldFor(WorkoutInputs.Field field) {
        return switch (field) {
            case SPEED -> speedField;
            case INCLINE -> inclineField;
            case CALORIES -> calorieTargetField;
            case FAT -> fatTargetField;
            case WALK -> walkMinutesField;
            case BREAK -> breakMinutesField;
            case BURN_RATE, TARGET_LIMIT -> null;
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
        // A generated name carries the time it was generated; renew it so a
        // walk started an hour after opening the form isn't named for then.
        String typed = sessionNameField.getText().trim();
        session.name = typed.isBlank() || typed.equals(generatedName) ? defaultSessionName(inputs.mode()) : typed;
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
            distanceLabel.setText(WorkoutText.distance(0.0, currentUnits));
            stepsLabel.setText(WorkoutText.steps(0L));
            caloriesLabel.setText(WorkoutText.kcal(0.0));
            targetLabel.setText(previewTargetText());
            statsPanel.setStatus(TreadmillBundle.message("status.ready"));
            startPauseButton.setText(TreadmillBundle.message("button.start"));
            floatingClock.setPauseResumeText(TreadmillBundle.message("button.start"));
            return;
        }

        SessionMode mode = SessionMode.fromId(session.modeId);
        distanceLabel.setText(WorkoutText.distance(session.distanceKm, currentUnits));
        stepsLabel.setText(WorkoutText.steps(session.steps));
        caloriesLabel.setText(WorkoutText.kcal(session.calories));
        if (mode == SessionMode.MARATHON) {
            targetLabel.setText(TreadmillBundle.message("panel.target.open"));
        } else if (mode == SessionMode.CALORIE_BURN) {
            targetLabel.setText(WorkoutText.kcal(session.targetCalories));
        } else if (mode == SessionMode.INTERVAL) {
            targetLabel.setText(WorkoutMath.hasIntervalBlocks(session)
                    ? WorkoutText.intervalBlocks(session.intervalWalkSeconds / 60, session.intervalBreakSeconds / 60)
                    : TreadmillBundle.message("panel.target.open"));
        } else {
            targetLabel.setText(WorkoutText.weight(session.targetFatKg, currentUnits));
        }
        distanceLabel.setToolTipText(WorkoutText.segmentsTooltip(session, currentUnits));
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
        showDefaultName(selectedMode());
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
            seen.remember(session);
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
        applySpeedFromField();
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

    /** Pushes each valid keystroke to the engine; the inline validator reports invalid ones. */
    private void applySpeedFromField() {
        double speed = parseSpeedKmh();
        if (speed <= 0 || speed > 25) {
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
            case CALORIE_BURN -> WorkoutText.kcal(inputs.calorieTarget());
            case INTERVAL -> WorkoutText.intervalBlocks((long) inputs.walkMinutes(), (long) inputs.breakMinutes());
            case FAT_BURN -> WorkoutText.weight(inputs.targetFatKg(), currentUnits);
            case MARATHON -> TreadmillBundle.message("panel.target.open");
        };
    }

    private static String defaultSessionName(SessionMode mode) {
        return mode.getLabel() + " " + LocalDateTime.now().format(SESSION_NAME_FORMAT);
    }

    /** Puts a generated name in the field, remembered so Start can tell it from a typed one. */
    private void showDefaultName(SessionMode mode) {
        generatedName = defaultSessionName(mode);
        boolean wasPopulating = populatingFields;
        populatingFields = true;
        try {
            sessionNameField.setText(generatedName);
        } finally {
            populatingFields = wasPopulating;
        }
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
