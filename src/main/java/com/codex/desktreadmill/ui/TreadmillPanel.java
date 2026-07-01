package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.ProfileDialog;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class TreadmillPanel extends JPanel {
    private static final double FAT_KCAL_PER_KG = 7700.0;
    private static final DateTimeFormatter SESSION_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Project project;
    private final TreadmillSettings settings = TreadmillSettings.getInstance();
    private final DigitalClockDisplay clockDisplay = new DigitalClockDisplay();
    private final ComboBox<SessionMode> modeCombo = new ComboBox<>(SessionMode.values());
    private final ComboBox<CalorieAlgorithm> algorithmCombo = new ComboBox<>(CalorieAlgorithm.values());
    private final JPanel savedSessionsPanel = new JPanel();
    private final JBTextField sessionNameField = new JBTextField();
    private final JBTextField speedField = new JBTextField("3.0");
    private final JBTextField calorieTargetField = new JBTextField("300");
    private final JBTextField fatTargetField = new JBTextField("0.5");
    private final JBLabel statusLabel = new JBLabel("Ready");
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
    private final Timer timer;
    private final FloatingClockWindow floatingClock;

    private SessionData currentSession;
    private boolean running;
    private boolean refreshingSessions;
    private boolean populatingFields;
    private boolean autoPaused;
    private boolean highSpeedWarningShown;
    private long lastTypingActivityMillis;
    private String statusNote = "";

    public TreadmillPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));
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
        installTypingActivityTracker();

        timer = new Timer(1000, event -> tick());
        timer.start();
        refreshSavedSessions();
        loadLastSessionOrDefault();
        updateDisplay();
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
                .addLabeledComponent(new JBLabel("KG target"), fatTargetField, 1, false)
                .getPanel();
        JPanel emptyCard = new JPanel(new BorderLayout());
        targetPanel.add(emptyCard, SessionMode.MARATHON.name());
        targetPanel.add(calorieCard, SessionMode.CALORIE_BURN.name());
        targetPanel.add(fatCard, SessionMode.FAT_BURN.name());

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Saved sessions"), createSavedSessionsPanel(), 1, false)
                .addLabeledComponent(new JBLabel("Mode"), modeCombo, 1, false)
                .addLabeledComponent(new JBLabel("Session name"), sessionNameField, 1, false)
                .addLabeledComponent(new JBLabel("Speed (km/h)"), speedField, 1, false)
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

        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        wrapper.add(metrics, BorderLayout.CENTER);
        wrapper.add(statusLabel, BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent createButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        buttons.add(startPauseButton);
        buttons.add(floatButton);
        buttons.add(saveButton);
        buttons.add(resetButton);
        buttons.add(newButton);
        return buttons;
    }

    private JComponent createSavedSessionsPanel() {
        savedSessionsPanel.setLayout(new BoxLayout(savedSessionsPanel, BoxLayout.Y_AXIS));
        savedSessionsPanel.setBorder(BorderFactory.createLineBorder(JBColor.border()));
        return savedSessionsPanel;
    }

    private static JBLabel valueLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private static JComponent metricTile(String title, JBLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
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
        currentSession = null;
        running = false;
        autoPaused = false;
        statusNote = "";
        startPauseButton.setText("Start");
        updateDisplay();
    }

    private void toggleRunning() {
        if (running) {
            running = false;
            autoPaused = false;
            statusNote = "";
            startPauseButton.setText("Resume");
            saveCurrentSession(false);
            updateDisplay();
            return;
        }

        if (!ensureProfile()) {
            return;
        }
        if (currentSession == null || currentSession.completed) {
            currentSession = buildSessionFromInputs();
            if (currentSession == null) {
                return;
            }
        }
        updateCurrentSpeedFromInput(false);
        resumeSession(true);
    }

    private void resumeSession(boolean showFloatingClock) {
        if (currentSession == null || currentSession.completed) {
            return;
        }
        lastTypingActivityMillis = System.currentTimeMillis();
        running = true;
        autoPaused = false;
        currentSession.completed = false;
        statusNote = "";
        startPauseButton.setText("Pause");
        if (showFloatingClock) {
            floatingClock.showWindow();
        }
        saveCurrentSession(false);
        updateDisplay();
    }

    private void tick() {
        if (!running || currentSession == null) {
            return;
        }
        if (shouldAutoPauseForIdleTyping()) {
            running = false;
            autoPaused = true;
            statusNote = "Auto-paused after " + settings.getAutoPauseMinutes() + " minutes without typing";
            startPauseButton.setText("Resume");
            saveCurrentSession(false);
            updateDisplay();
            return;
        }
        updateCurrentSpeedFromInput(false);
        advanceSessionOneSecond(currentSession);
        SessionMode mode = SessionMode.fromId(currentSession.modeId);
        if (mode != SessionMode.MARATHON && currentSession.remainingSeconds == 0L) {
            running = false;
            autoPaused = false;
            currentSession.completed = true;
            statusNote = "";
            startPauseButton.setText("Start");
            Toolkit.getDefaultToolkit().beep();
            Messages.showInfoMessage(project, "Session complete.", "Treadmill Buddy");
        }
        saveCurrentSession(false);
        updateDisplay();
    }

    private SessionData buildSessionFromInputs() {
        double speed = parseDouble(speedField.getText());
        if (speed <= 0 || speed > 25) {
            showError("Enter a treadmill speed between 0 and 25 km/h.");
            return null;
        }
        maybeShowHighSpeedPrompt(speed);
        CalorieAlgorithm algorithm = selectedAlgorithm();
        UserProfile profile = settings.getProfile();
        double caloriesPerMinute = algorithm.kcalPerMinute(profile, speed);
        if (caloriesPerMinute <= 0) {
            showError("The selected calorie algorithm produced a zero burn rate.");
            return null;
        }

        SessionMode mode = selectedMode();
        SessionData session = new SessionData();
        session.id = String.valueOf(System.currentTimeMillis());
        session.name = sessionNameField.getText().trim().isBlank() ? defaultSessionName(mode) : sessionNameField.getText().trim();
        session.modeId = mode.name();
        session.algorithmId = algorithm.name();
        session.speedKmh = speed;

        if (mode == SessionMode.CALORIE_BURN) {
            double targetCalories = parseDouble(calorieTargetField.getText());
            if (targetCalories <= 0) {
                showError("Enter calories greater than zero.");
                return null;
            }
            session.targetCalories = targetCalories;
            session.targetSeconds = secondsForCalories(targetCalories, caloriesPerMinute);
            session.remainingSeconds = session.targetSeconds;
        } else if (mode == SessionMode.FAT_BURN) {
            double fatKg = parseDouble(fatTargetField.getText());
            if (fatKg <= 0) {
                showError("Enter a KG target greater than zero.");
                return null;
            }
            double targetCalories = fatKg * FAT_KCAL_PER_KG;
            long seconds = secondsForCalories(targetCalories, caloriesPerMinute);
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
        updateRemainingTimeForCurrentSpeed(session);
        return session;
    }

    private void advanceSessionOneSecond(SessionData session) {
        UserProfile profile = settings.getProfile();
        CalorieAlgorithm algorithm = CalorieAlgorithm.fromId(session.algorithmId);
        session.elapsedSeconds++;
        session.distanceKm += session.speedKmh / 3600.0;
        double stepLengthMeters = Math.max(0.2, profile.heightCm / 100.0 * 0.414);
        session.steps = Math.round(session.distanceKm * 1000.0 / stepLengthMeters);
        session.calories += algorithm.kcalPerMinute(profile, session.speedKmh) / 60.0;
        updateRemainingTimeForCurrentSpeed(session);
    }

    private void updateRemainingTimeForCurrentSpeed(SessionData session) {
        SessionMode mode = SessionMode.fromId(session.modeId);
        if (mode == SessionMode.MARATHON) {
            session.remainingSeconds = 0L;
            return;
        }
        CalorieAlgorithm algorithm = CalorieAlgorithm.fromId(session.algorithmId);
        double remainingCalories = Math.max(0.0, session.targetCalories - session.calories);
        double caloriesPerMinute = algorithm.kcalPerMinute(settings.getProfile(), session.speedKmh);
        session.remainingSeconds = remainingCalories == 0.0
                ? 0L
                : secondsForCalories(remainingCalories, caloriesPerMinute);
        session.targetSeconds = session.elapsedSeconds + session.remainingSeconds;
    }

    private void updateCurrentSpeedFromInput(boolean showError) {
        if (currentSession == null || currentSession.completed) {
            return;
        }
        double speed = parseDouble(speedField.getText());
        if (speed <= 0 || speed > 25) {
            if (showError) {
                showError("Enter a treadmill speed between 0 and 25 km/h.");
            }
            return;
        }
        maybeShowHighSpeedPrompt(speed);
        if (Math.abs(currentSession.speedKmh - speed) < 0.001) {
            return;
        }
        currentSession.speedKmh = speed;
        updateRemainingTimeForCurrentSpeed(currentSession);
        updateDisplay();
    }

    private void updateDisplay() {
        long seconds = 0L;
        if (currentSession != null) {
            SessionMode mode = SessionMode.fromId(currentSession.modeId);
            seconds = mode == SessionMode.MARATHON ? currentSession.elapsedSeconds : currentSession.remainingSeconds;
        } else {
            seconds = previewSecondsFromInputs();
        }
        TimeFormatter.DisplayTime displayTime = TimeFormatter.displayTime(seconds);
        clockDisplay.setDisplay(displayTime.getDayPrefix(), displayTime.getTimeText());
        floatingClock.setDisplay(displayTime.getDayPrefix(), displayTime.getTimeText());

        if (currentSession == null) {
            distanceLabel.setText("0.00 km");
            stepsLabel.setText("0 steps");
            caloriesLabel.setText("0 kcal");
            targetLabel.setText(previewTargetText());
            statusLabel.setText("Ready");
            startPauseButton.setText("Start");
            floatingClock.setPauseResumeText("Start");
            return;
        }

        SessionMode mode = SessionMode.fromId(currentSession.modeId);
        distanceLabel.setText(String.format("%.2f km", currentSession.distanceKm));
        stepsLabel.setText(String.format("%,d steps", currentSession.steps));
        caloriesLabel.setText(String.format("%.0f kcal", currentSession.calories));
        if (mode == SessionMode.MARATHON) {
            targetLabel.setText("Open");
        } else if (mode == SessionMode.CALORIE_BURN) {
            targetLabel.setText(String.format("%.0f kcal", currentSession.targetCalories));
        } else {
            targetLabel.setText(String.format("%.2f kg", currentSession.targetFatKg));
        }
        String status = running ? "Running" : currentSession.completed ? "Complete" : "Paused";
        statusLabel.setText(statusNote.isBlank()
                ? status + " - " + currentSession.name
                : status + " - " + currentSession.name + " - " + statusNote);
        floatingClock.setPauseResumeText(running ? "Pause" : currentSession.completed ? "Start" : "Resume");
    }

    private void saveCurrentSession(boolean showConfirmation) {
        if (currentSession == null) {
            currentSession = buildSessionFromInputs();
            if (currentSession == null) {
                return;
            }
        }
        currentSession.name = sessionNameField.getText().trim().isBlank()
                ? currentSession.name
                : sessionNameField.getText().trim();
        settings.saveSession(currentSession);
        refreshSavedSessions();
        if (showConfirmation) {
            Messages.showInfoMessage(project, "Session saved.", "Treadmill Buddy");
        }
    }

    private void resetCurrentSession() {
        if (currentSession == null) {
            updateDisplay();
            return;
        }
        running = false;
        autoPaused = false;
        currentSession.elapsedSeconds = 0L;
        currentSession.remainingSeconds = currentSession.targetSeconds;
        currentSession.distanceKm = 0.0;
        currentSession.steps = 0L;
        currentSession.calories = 0.0;
        currentSession.completed = false;
        updateRemainingTimeForCurrentSpeed(currentSession);
        statusNote = "";
        startPauseButton.setText("Start");
        saveCurrentSession(false);
        updateDisplay();
    }

    private void newSession() {
        running = false;
        autoPaused = false;
        currentSession = null;
        statusNote = "";
        startPauseButton.setText("Start");
        SessionMode mode = selectedMode();
        sessionNameField.setText(defaultSessionName(mode));
        updateDisplay();
    }

    private void deleteSession(SessionData session) {
        int answer = Messages.showYesNoDialog(
                project,
                "Delete session \"" + session.name + "\"?",
                "Delete Session",
                Messages.getQuestionIcon()
        );
        if (answer != Messages.YES) {
            return;
        }
        running = false;
        autoPaused = false;
        statusNote = "";
        settings.deleteSession(session.id);
        if (currentSession != null && session.id.equals(currentSession.id)) {
            currentSession = null;
        }
        refreshSavedSessions();
        updateDisplay();
    }

    private void refreshSavedSessions() {
        refreshingSessions = true;
        savedSessionsPanel.removeAll();
        List<SessionData> sessions = settings.getSessions();
        if (sessions.isEmpty()) {
            JBLabel empty = new JBLabel("No saved sessions");
            empty.setBorder(JBUI.Borders.empty(6));
            savedSessionsPanel.add(empty);
        } else {
            for (SessionData session : sessions) {
                savedSessionsPanel.add(createSavedSessionRow(session));
            }
        }
        savedSessionsPanel.revalidate();
        savedSessionsPanel.repaint();
        refreshingSessions = false;
    }

    private JComponent createSavedSessionRow(SessionData session) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(JBUI.Borders.empty(4, 6));
        JButton loadButton = new JButton(session.name + " (" + SessionMode.fromId(session.modeId).getLabel() + ")");
        loadButton.setHorizontalAlignment(SwingConstants.LEFT);
        loadButton.setToolTipText("Load this saved session");
        loadButton.addActionListener(event -> loadSession(session));

        JButton binButton = new JButton("Bin");
        binButton.setToolTipText("Delete this saved session");
        binButton.addActionListener(event -> deleteSession(session));

        row.add(loadButton, BorderLayout.CENTER);
        row.add(binButton, BorderLayout.EAST);
        return row;
    }

    private void loadLastSessionOrDefault() {
        SessionData last = settings.findSession(settings.getLastSessionId());
        if (last != null) {
            loadSession(last);
        }
    }

    private void loadSession(SessionData session) {
        if (refreshingSessions) {
            return;
        }
        running = false;
        autoPaused = false;
        statusNote = "";
        currentSession = session.copy();
        populateFields(currentSession);
        startPauseButton.setText(currentSession.completed ? "Start" : "Resume");
        updateDisplay();
    }

    private void populateFields(SessionData session) {
        populatingFields = true;
        try {
            modeCombo.setSelectedItem(SessionMode.fromId(session.modeId));
            targetCards.show(targetPanel, session.modeId);
            sessionNameField.setText(session.name);
            speedField.setText(format(session.speedKmh));
            algorithmCombo.setSelectedItem(CalorieAlgorithm.fromId(session.algorithmId));
            calorieTargetField.setText(format(session.targetCalories));
            fatTargetField.setText(format(session.targetFatKg));
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
        if (populatingFields || currentSession == null || currentSession.completed) {
            updateDisplay();
            return;
        }
        currentSession.algorithmId = selectedAlgorithm().name();
        updateRemainingTimeForCurrentSpeed(currentSession);
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

    private boolean shouldAutoPauseForIdleTyping() {
        int idleMinutes = settings.getAutoPauseMinutes();
        if (idleMinutes == 0) {
            return false;
        }
        long idleMillis = System.currentTimeMillis() - lastTypingActivityMillis;
        return idleMillis >= idleMinutes * 60_000L;
    }

    private void installTypingActivityTracker() {
        lastTypingActivityMillis = System.currentTimeMillis();
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof KeyEvent keyEvent
                    && keyEvent.getID() == KeyEvent.KEY_PRESSED
                    && !keyEvent.isActionKey()) {
                lastTypingActivityMillis = System.currentTimeMillis();
                if (autoPaused) {
                    SwingUtilities.invokeLater(this::resumeAfterTyping);
                }
            }
        }, AWTEvent.KEY_EVENT_MASK);
    }

    private void resumeAfterTyping() {
        if (!autoPaused || running || currentSession == null || currentSession.completed) {
            return;
        }
        updateCurrentSpeedFromInput(false);
        resumeSession(false);
    }

    private void installSpeedListener() {
        speedField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                speedChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                speedChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                speedChanged();
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
        double speed = parseDouble(speedField.getText());
        if (speed > 0 && speed <= 25) {
            maybeShowHighSpeedPrompt(speed);
        }
        updateCurrentSpeedFromInput(false);
        if (currentSession == null) {
            updateDisplay();
        }
    }

    private void previewInputsChanged() {
        if (!populatingFields && currentSession == null) {
            updateDisplay();
        }
    }

    private long previewSecondsFromInputs() {
        SessionMode mode = selectedMode();
        if (mode == SessionMode.MARATHON) {
            return 0L;
        }
        double speed = parseDouble(speedField.getText());
        if (speed <= 0 || speed > 25) {
            return 0L;
        }
        double targetCalories;
        if (mode == SessionMode.CALORIE_BURN) {
            targetCalories = parseDouble(calorieTargetField.getText());
        } else {
            double fatKg = parseDouble(fatTargetField.getText());
            targetCalories = fatKg > 0 ? fatKg * FAT_KCAL_PER_KG : -1.0;
        }
        if (targetCalories <= 0) {
            return 0L;
        }
        double caloriesPerMinute = selectedAlgorithm().kcalPerMinute(settings.getProfile(), speed);
        if (caloriesPerMinute <= 0) {
            return 0L;
        }
        return secondsForCalories(targetCalories, caloriesPerMinute);
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
        double fatKg = parseDouble(fatTargetField.getText());
        return fatKg > 0 ? String.format("%.2f kg", fatKg) : "-";
    }

    private static long secondsForCalories(double targetCalories, double caloriesPerMinute) {
        return Math.max(1L, (long) Math.ceil(targetCalories / caloriesPerMinute * 60.0));
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
