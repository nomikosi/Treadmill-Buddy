package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.ui.ComboHelp;
import com.codex.desktreadmill.ui.NumericInput;
import com.codex.desktreadmill.ui.MetricInput;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;

/**
 * The profile and defaults form shared by the Settings page and the first-run
 * dialog. Both read it through {@link #setValues} and write it back through
 * {@link #applyTo}, so neither can silently drop a field the other saves.
 */
public final class ProfilePanel {
    private final ComboBox<UnitSystem> unitsCombo = new ComboBox<>(UnitSystem.values());
    private final JBTextField weightField = new JBTextField();
    private final JBTextField heightField = new JBTextField();
    private final JBTextField autoPauseField = new JBTextField();
    private final JBTextField moveReminderField = new JBTextField();
    private final ComboBox<CalorieAlgorithm> algorithmCombo = new ComboBox<>(CalorieAlgorithm.values());
    private final ComboBox<GoalType> goalTypeCombo = new ComboBox<>(GoalType.values());
    private final JBTextField goalValueField = new JBTextField();
    private final ComboBox<GoalType> weeklyGoalTypeCombo = new ComboBox<>(GoalType.values());
    private final JBTextField weeklyGoalValueField = new JBTextField();
    private final JBTextField streakRestDaysField = new JBTextField();
    private final JBTextField streakRiskHourField = new JBTextField();
    private final JBLabel weightLabel = new JBLabel();
    private final JBLabel heightLabel = new JBLabel();
    private final JBLabel goalValueLabel = new JBLabel();
    private final JBLabel weeklyGoalValueLabel = new JBLabel();
    private final JPanel panel;

    /** Units currently reflected by the field texts, so a combo switch can convert them. */
    private UnitSystem fieldUnits = UnitSystem.METRIC;
    private final MetricInput weightInput = new MetricInput(MetricInput.Quantity.WEIGHT, 1);
    private final MetricInput heightInput = new MetricInput(MetricInput.Quantity.HEIGHT, 1);
    private final MetricInput dailyDistanceInput = new MetricInput(MetricInput.Quantity.DISTANCE, 1);
    private final MetricInput weeklyDistanceInput = new MetricInput(MetricInput.Quantity.DISTANCE, 1);
    private GoalType fieldDailyGoalType = GoalType.NONE;
    private GoalType fieldWeeklyGoalType = GoalType.NONE;

    public ProfilePanel() {
        ComboHelp.configureAlgorithmCombo(algorithmCombo, this::getAlgorithm);
        unitsCombo.addActionListener(event -> unitsSelectionChanged());
        goalTypeCombo.addActionListener(event -> goalTypeChanged());
        weeklyGoalTypeCombo.addActionListener(event -> goalTypeChanged());
        // GoalType.NONE renders as "No daily goal" by default; the weekly
        // dropdown must say weekly instead.
        goalTypeCombo.setRenderer(goalTypeRenderer("settings.goal.none.daily"));
        weeklyGoalTypeCombo.setRenderer(goalTypeRenderer("settings.goal.none.weekly"));
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.units")), unitsCombo, 1, false)
                .addLabeledComponent(weightLabel, weightField, 1, false)
                .addLabeledComponent(heightLabel, heightField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.algorithm")), algorithmCombo, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.autoPause")), autoPauseField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.moveReminder")), moveReminderField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.dailyGoal")), goalTypeCombo, 1, false)
                .addLabeledComponent(goalValueLabel, goalValueField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.weeklyGoal")), weeklyGoalTypeCombo, 1, false)
                .addLabeledComponent(weeklyGoalValueLabel, weeklyGoalValueField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.restDays")), streakRestDaysField, 1, false)
                .addLabeledComponent(new JBLabel(TreadmillBundle.message("settings.streakRiskHour")), streakRiskHourField, 1, false)
                .addComponentFillVertically(new JPanel(new BorderLayout()), 0)
                .getPanel();
        updateUnitLabels();
        goalTypeChanged();
    }

    public JComponent getComponent() {
        return panel;
    }

    private static DefaultListCellRenderer goalTypeRenderer(String noneLabelKey) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == GoalType.NONE) {
                    setText(TreadmillBundle.message(noneLabelKey));
                }
                return component;
            }
        };
    }

    /** Populates every field from the settings and remembers them as the untouched baseline. */
    public void setValues(TreadmillSettings settings) {
        UserProfile profile = settings.getProfile();
        fieldUnits = settings.getUnitSystem();
        unitsCombo.setSelectedItem(fieldUnits);
        weightField.setText(weightInput.display(profile.weightKg, fieldUnits));
        heightField.setText(heightInput.display(profile.heightCm, fieldUnits));
        algorithmCombo.setSelectedItem(settings.getSelectedAlgorithm());
        autoPauseField.setText(String.valueOf(settings.getAutoPauseMinutes()));
        moveReminderField.setText(String.valueOf(settings.getMoveReminderMinutes()));
        goalTypeCombo.setSelectedItem(settings.getDailyGoalType());
        goalValueField.setText(goalText(settings.getDailyGoalType(), settings.getDailyGoalValue(), dailyDistanceInput));
        weeklyGoalTypeCombo.setSelectedItem(settings.getWeeklyGoalType());
        weeklyGoalValueField.setText(goalText(settings.getWeeklyGoalType(), settings.getWeeklyGoalValue(), weeklyDistanceInput));
        streakRestDaysField.setText(String.valueOf(settings.getStreakRestDaysPerWeek()));
        streakRiskHourField.setText(String.valueOf(settings.getStreakRiskHour()));
        updateUnitLabels();
        goalTypeChanged();
    }

    private String goalText(GoalType type, double metricValue, MetricInput distanceInput) {
        if (metricValue <= 0) {
            return "";
        }
        return type == GoalType.DISTANCE ? distanceInput.display(metricValue, fieldUnits) : format(metricValue);
    }

    /**
     * Writes every field into the settings. The one place that knows the full
     * list, so the first-run dialog and the Settings page cannot drift apart.
     * Callers validate first via {@link #validateInput}.
     */
    public void applyTo(TreadmillSettings settings) {
        settings.setProfile(getProfile());
        settings.setSelectedAlgorithm(getAlgorithm());
        settings.setAutoPauseMinutes(getAutoPauseMinutes());
        settings.setMoveReminderMinutes(getMoveReminderMinutes());
        settings.setUnitSystem(getUnitSystem());
        settings.setDailyGoalType(getDailyGoalType());
        settings.setDailyGoalValue(getDailyGoalValueMetric());
        settings.setWeeklyGoalType(getWeeklyGoalType());
        settings.setWeeklyGoalValue(getWeeklyGoalValueMetric());
        settings.setStreakRestDaysPerWeek(getStreakRestDaysPerWeek());
        settings.setStreakRiskHour(getStreakRiskHour());
    }

    public UserProfile getProfile() {
        UnitSystem units = getUnitSystem();
        UserProfile profile = new UserProfile();
        profile.weightKg = weightInput.read(weightField.getText(), units);
        profile.heightCm = heightInput.read(heightField.getText(), units);
        profile.completed = true;
        return profile;
    }

    public CalorieAlgorithm getAlgorithm() {
        Object selected = algorithmCombo.getSelectedItem();
        return selected instanceof CalorieAlgorithm ? (CalorieAlgorithm) selected : CalorieAlgorithm.ACSM_FLAT;
    }

    public UnitSystem getUnitSystem() {
        Object selected = unitsCombo.getSelectedItem();
        return selected instanceof UnitSystem ? (UnitSystem) selected : UnitSystem.METRIC;
    }

    public GoalType getDailyGoalType() {
        Object selected = goalTypeCombo.getSelectedItem();
        return selected instanceof GoalType ? (GoalType) selected : GoalType.NONE;
    }

    /** Goal value converted to metric terms (steps, km, or kcal). */
    public double getDailyGoalValueMetric() {
        return goalValueMetric(getDailyGoalType(), goalValueField.getText(),
                dailyDistanceInput);
    }

    public GoalType getWeeklyGoalType() {
        Object selected = weeklyGoalTypeCombo.getSelectedItem();
        return selected instanceof GoalType ? (GoalType) selected : GoalType.NONE;
    }

    /** Weekly goal value converted to metric terms (steps, km, or kcal). */
    public double getWeeklyGoalValueMetric() {
        return goalValueMetric(getWeeklyGoalType(), weeklyGoalValueField.getText(),
                weeklyDistanceInput);
    }

    private double goalValueMetric(GoalType type, String text, MetricInput distanceInput) {
        if (type == GoalType.NONE) {
            return 0.0;
        }
        double value = parseDouble(text);
        if (value <= 0) {
            return 0.0;
        }
        if (type != GoalType.DISTANCE) {
            return value;
        }
        return distanceInput.read(text, getUnitSystem());
    }

    public int getStreakRestDaysPerWeek() {
        return parseInt(streakRestDaysField.getText());
    }

    public int getStreakRiskHour() {
        return parseInt(streakRiskHourField.getText());
    }

    public int getAutoPauseMinutes() {
        return parseInt(autoPauseField.getText());
    }

    public int getMoveReminderMinutes() {
        return parseInt(moveReminderField.getText());
    }

    public String validateInput() {
        UnitSystem units = getUnitSystem();
        double weightKg = getProfile().weightKg;
        double heightCm = getProfile().heightCm;
        if (weightKg < 20 || weightKg > 300) {
            return TreadmillBundle.message("settings.validation.weight",
                    String.format("%.0f", units.weightFromKg(20)),
                    String.format("%.0f", units.weightFromKg(300)), units.weightUnit());
        }
        if (heightCm < 90 || heightCm > 250) {
            return TreadmillBundle.message("settings.validation.height",
                    String.format("%.0f", units.heightFromCm(90)),
                    String.format("%.0f", units.heightFromCm(250)), units.heightUnit());
        }
        int autoPauseMinutes = parseInt(autoPauseField.getText());
        if (autoPauseMinutes < 0 || autoPauseMinutes > 240) {
            return TreadmillBundle.message("settings.validation.autoPause");
        }
        int moveReminderMinutes = parseInt(moveReminderField.getText());
        if (moveReminderMinutes < 0 || moveReminderMinutes > 480) {
            return TreadmillBundle.message("settings.validation.moveReminder");
        }
        if (getDailyGoalType() != GoalType.NONE && parseDouble(goalValueField.getText()) <= 0) {
            return TreadmillBundle.message("settings.validation.dailyGoal");
        }
        if (getWeeklyGoalType() != GoalType.NONE && parseDouble(weeklyGoalValueField.getText()) <= 0) {
            return TreadmillBundle.message("settings.validation.weeklyGoal");
        }
        int restDays = getStreakRestDaysPerWeek();
        if (restDays < 0 || restDays > 6) {
            return TreadmillBundle.message("settings.validation.restDays");
        }
        int riskHour = getStreakRiskHour();
        if (riskHour < 0 || riskHour > 23) {
            return TreadmillBundle.message("settings.validation.streakRiskHour");
        }
        return null;
    }

    public boolean isModified(TreadmillSettings settings) {
        UserProfile profile = settings.getProfile();
        UserProfile edited = getProfile();
        return Math.abs(edited.weightKg - profile.weightKg) > 0.001
                || Math.abs(edited.heightCm - profile.heightCm) > 0.001
                || getAlgorithm() != settings.getSelectedAlgorithm()
                || getAutoPauseMinutes() != settings.getAutoPauseMinutes()
                || getMoveReminderMinutes() != settings.getMoveReminderMinutes()
                || getUnitSystem() != settings.getUnitSystem()
                || getDailyGoalType() != settings.getDailyGoalType()
                || Math.abs(getDailyGoalValueMetric() - settings.getDailyGoalValue()) > 0.001
                || getWeeklyGoalType() != settings.getWeeklyGoalType()
                || Math.abs(getWeeklyGoalValueMetric() - settings.getWeeklyGoalValue()) > 0.001
                || getStreakRestDaysPerWeek() != settings.getStreakRestDaysPerWeek()
                || getStreakRiskHour() != settings.getStreakRiskHour();
    }

    private void unitsSelectionChanged() {
        UnitSystem units = getUnitSystem();
        if (units == fieldUnits) {
            return;
        }
        convertField(weightField, weightInput, fieldUnits, units);
        convertField(heightField, heightInput, fieldUnits, units);
        if (getDailyGoalType() == GoalType.DISTANCE) {
            convertField(goalValueField, dailyDistanceInput, fieldUnits, units);
        }
        if (getWeeklyGoalType() == GoalType.DISTANCE) {
            convertField(weeklyGoalValueField, weeklyDistanceInput, fieldUnits, units);
        }
        fieldUnits = units;
        updateUnitLabels();
    }

    private static void convertField(JBTextField field, MetricInput input, UnitSystem previous, UnitSystem next) {
        double value = input.read(field.getText(), previous);
        if (value > 0) {
            field.setText(input.display(value, next));
        }
    }

    private void updateUnitLabels() {
        UnitSystem units = getUnitSystem();
        weightLabel.setText(TreadmillBundle.message("settings.weight", units.weightUnit()));
        heightLabel.setText(TreadmillBundle.message("settings.height", units.heightUnit()));
        updateGoalValueLabel();
    }

    private void goalTypeChanged() {
        if (getDailyGoalType() != fieldDailyGoalType) {
            dailyDistanceInput.clear();
            fieldDailyGoalType = getDailyGoalType();
        }
        if (getWeeklyGoalType() != fieldWeeklyGoalType) {
            weeklyDistanceInput.clear();
            fieldWeeklyGoalType = getWeeklyGoalType();
        }
        goalValueField.setEnabled(getDailyGoalType() != GoalType.NONE);
        weeklyGoalValueField.setEnabled(getWeeklyGoalType() != GoalType.NONE);
        updateGoalValueLabel();
    }

    private void updateGoalValueLabel() {
        goalValueLabel.setText(TreadmillBundle.message("settings.goalValue", goalUnitSuffix(getDailyGoalType())));
        weeklyGoalValueLabel.setText(TreadmillBundle.message("settings.weeklyGoalValue", goalUnitSuffix(getWeeklyGoalType())));
    }

    private String goalUnitSuffix(GoalType type) {
        return switch (type) {
            case STEPS -> TreadmillBundle.message("settings.goalUnit.steps");
            case DISTANCE -> TreadmillBundle.message("settings.goalUnit.distance", getUnitSystem().distanceUnit());
            case CALORIES -> TreadmillBundle.message("settings.goalUnit.kcal");
            case NONE -> "";
        };
    }

    /** The field's number, or -1 when it is not a usable one: "NaN" parses but passes every range check. */
    private static double parseDouble(String text) {
        return NumericInput.parse(text);
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String format(double value) {
        return NumericInput.format(value, 1);
    }
}
