package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.ui.ComboHelp;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

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
    private final JBLabel weightLabel = new JBLabel();
    private final JBLabel heightLabel = new JBLabel();
    private final JBLabel goalValueLabel = new JBLabel("Goal value");
    private final JBLabel weeklyGoalValueLabel = new JBLabel("Weekly goal value");
    private final JPanel panel;

    /** Units currently reflected by the field texts, so a combo switch can convert them. */
    private UnitSystem fieldUnits = UnitSystem.METRIC;

    public ProfilePanel() {
        ComboHelp.configureAlgorithmCombo(algorithmCombo, this::getAlgorithm);
        unitsCombo.addActionListener(event -> unitsSelectionChanged());
        goalTypeCombo.addActionListener(event -> goalTypeChanged());
        weeklyGoalTypeCombo.addActionListener(event -> goalTypeChanged());
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Units"), unitsCombo, 1, false)
                .addLabeledComponent(weightLabel, weightField, 1, false)
                .addLabeledComponent(heightLabel, heightField, 1, false)
                .addLabeledComponent(new JBLabel("Default calorie algorithm"), algorithmCombo, 1, false)
                .addLabeledComponent(new JBLabel("Auto-pause idle sessions (minutes, 0 = off)"), autoPauseField, 1, false)
                .addLabeledComponent(new JBLabel("Remind me to move (minutes, 0 = off)"), moveReminderField, 1, false)
                .addLabeledComponent(new JBLabel("Daily goal"), goalTypeCombo, 1, false)
                .addLabeledComponent(goalValueLabel, goalValueField, 1, false)
                .addLabeledComponent(new JBLabel("Weekly goal"), weeklyGoalTypeCombo, 1, false)
                .addLabeledComponent(weeklyGoalValueLabel, weeklyGoalValueField, 1, false)
                .addLabeledComponent(new JBLabel("Streak rest days per week (0-2)"), streakRestDaysField, 1, false)
                .addComponentFillVertically(new JPanel(new BorderLayout()), 0)
                .getPanel();
        updateUnitLabels();
        goalTypeChanged();
    }

    public JComponent getComponent() {
        return panel;
    }

    public void setValues(UserProfile profile, CalorieAlgorithm algorithm) {
        TreadmillSettings settings = TreadmillSettings.getInstance();
        fieldUnits = settings.getUnitSystem();
        unitsCombo.setSelectedItem(fieldUnits);
        weightField.setText(format(fieldUnits.weightFromKg(profile.weightKg)));
        heightField.setText(format(fieldUnits.heightFromCm(profile.heightCm)));
        algorithmCombo.setSelectedItem(algorithm);
        autoPauseField.setText(String.valueOf(settings.getAutoPauseMinutes()));
        moveReminderField.setText(String.valueOf(settings.getMoveReminderMinutes()));
        GoalType goalType = settings.getDailyGoalType();
        goalTypeCombo.setSelectedItem(goalType);
        double goalValue = settings.getDailyGoalValue();
        goalValueField.setText(goalValue > 0
                ? format(goalType == GoalType.DISTANCE ? fieldUnits.distanceFromKm(goalValue) : goalValue)
                : "");
        GoalType weeklyType = settings.getWeeklyGoalType();
        weeklyGoalTypeCombo.setSelectedItem(weeklyType);
        double weeklyValue = settings.getWeeklyGoalValue();
        weeklyGoalValueField.setText(weeklyValue > 0
                ? format(weeklyType == GoalType.DISTANCE ? fieldUnits.distanceFromKm(weeklyValue) : weeklyValue)
                : "");
        streakRestDaysField.setText(String.valueOf(settings.getStreakRestDaysPerWeek()));
        updateUnitLabels();
        goalTypeChanged();
    }

    public UserProfile getProfile() {
        UnitSystem units = getUnitSystem();
        UserProfile profile = new UserProfile();
        profile.weightKg = units.weightToKg(parseDouble(weightField.getText()));
        profile.heightCm = units.heightToCm(parseDouble(heightField.getText()));
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
        GoalType type = getDailyGoalType();
        if (type == GoalType.NONE) {
            return 0.0;
        }
        double value = parseDouble(goalValueField.getText());
        if (value <= 0) {
            return 0.0;
        }
        return type == GoalType.DISTANCE ? getUnitSystem().distanceToKm(value) : value;
    }

    public GoalType getWeeklyGoalType() {
        Object selected = weeklyGoalTypeCombo.getSelectedItem();
        return selected instanceof GoalType ? (GoalType) selected : GoalType.NONE;
    }

    /** Weekly goal value converted to metric terms (steps, km, or kcal). */
    public double getWeeklyGoalValueMetric() {
        GoalType type = getWeeklyGoalType();
        if (type == GoalType.NONE) {
            return 0.0;
        }
        double value = parseDouble(weeklyGoalValueField.getText());
        if (value <= 0) {
            return 0.0;
        }
        return type == GoalType.DISTANCE ? getUnitSystem().distanceToKm(value) : value;
    }

    public int getStreakRestDaysPerWeek() {
        return parseInt(streakRestDaysField.getText());
    }

    public int getAutoPauseMinutes() {
        return parseInt(autoPauseField.getText());
    }

    public int getMoveReminderMinutes() {
        return parseInt(moveReminderField.getText());
    }

    public String validateInput() {
        UnitSystem units = getUnitSystem();
        double weightKg = units.weightToKg(parseDouble(weightField.getText()));
        double heightCm = units.heightToCm(parseDouble(heightField.getText()));
        if (weightKg < 20 || weightKg > 300) {
            return String.format("Enter a weight between %.0f and %.0f %s.",
                    units.weightFromKg(20), units.weightFromKg(300), units.weightUnit());
        }
        if (heightCm < 90 || heightCm > 250) {
            return String.format("Enter a height between %.0f and %.0f %s.",
                    units.heightFromCm(90), units.heightFromCm(250), units.heightUnit());
        }
        int autoPauseMinutes = parseInt(autoPauseField.getText());
        if (autoPauseMinutes < 0 || autoPauseMinutes > 240) {
            return "Enter auto-pause minutes between 0 and 240. Use 0 to disable it.";
        }
        int moveReminderMinutes = parseInt(moveReminderField.getText());
        if (moveReminderMinutes < 0 || moveReminderMinutes > 480) {
            return "Enter move-reminder minutes between 0 and 480. Use 0 to disable it.";
        }
        if (getDailyGoalType() != GoalType.NONE && parseDouble(goalValueField.getText()) <= 0) {
            return "Enter a daily goal value greater than zero, or set the goal to None.";
        }
        if (getWeeklyGoalType() != GoalType.NONE && parseDouble(weeklyGoalValueField.getText()) <= 0) {
            return "Enter a weekly goal value greater than zero, or set the goal to None.";
        }
        int restDays = getStreakRestDaysPerWeek();
        if (restDays < 0 || restDays > 2) {
            return "Enter streak rest days between 0 and 2.";
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
                || getStreakRestDaysPerWeek() != settings.getStreakRestDaysPerWeek();
    }

    private void unitsSelectionChanged() {
        UnitSystem units = getUnitSystem();
        if (units == fieldUnits) {
            return;
        }
        convertField(weightField, fieldUnits::weightToKg, units::weightFromKg);
        convertField(heightField, fieldUnits::heightToCm, units::heightFromCm);
        if (getDailyGoalType() == GoalType.DISTANCE) {
            convertField(goalValueField, fieldUnits::distanceToKm, units::distanceFromKm);
        }
        if (getWeeklyGoalType() == GoalType.DISTANCE) {
            convertField(weeklyGoalValueField, fieldUnits::distanceToKm, units::distanceFromKm);
        }
        fieldUnits = units;
        updateUnitLabels();
    }

    private static void convertField(
            JBTextField field,
            java.util.function.DoubleUnaryOperator toMetric,
            java.util.function.DoubleUnaryOperator fromMetric
    ) {
        double value = parseDouble(field.getText());
        if (value > 0) {
            field.setText(format(fromMetric.applyAsDouble(toMetric.applyAsDouble(value))));
        }
    }

    private void updateUnitLabels() {
        UnitSystem units = getUnitSystem();
        weightLabel.setText("Weight (" + units.weightUnit() + ")");
        heightLabel.setText("Height (" + units.heightUnit() + ")");
        updateGoalValueLabel();
    }

    private void goalTypeChanged() {
        goalValueField.setEnabled(getDailyGoalType() != GoalType.NONE);
        weeklyGoalValueField.setEnabled(getWeeklyGoalType() != GoalType.NONE);
        updateGoalValueLabel();
    }

    private void updateGoalValueLabel() {
        goalValueLabel.setText("Goal value" + goalUnitSuffix(getDailyGoalType()));
        weeklyGoalValueLabel.setText("Weekly goal value" + goalUnitSuffix(getWeeklyGoalType()));
    }

    private String goalUnitSuffix(GoalType type) {
        UnitSystem units = getUnitSystem();
        return switch (type) {
            case STEPS -> " (steps)";
            case DISTANCE -> " (" + units.distanceUnit() + ")";
            case CALORIES -> " (kcal)";
            case NONE -> "";
        };
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String format(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }
}
