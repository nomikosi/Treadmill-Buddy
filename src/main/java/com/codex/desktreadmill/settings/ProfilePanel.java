package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.ui.ComboHelp;
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
import java.util.function.DoubleUnaryOperator;

/**
 * The profile and defaults form shared by the Settings page and the first-run
 * dialog. Both read it through {@link #setValues} and write it back through
 * {@link #applyTo}, so neither can silently drop a field the other saves.
 */
public final class ProfilePanel {
    /**
     * Fields show one decimal, so a displayed value is within this much of
     * the exact conversion of the stored metric value. A field that still
     * shows what it was populated with counts as untouched.
     */
    static final double DISPLAY_TOLERANCE = 0.05 + 1e-9;

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
    /**
     * The stored metric values the fields were populated from. An untouched
     * field hands these back exactly instead of re-converting its rounded
     * text - "154.3 lb" converted back is 69.99 kg, which used to flag the
     * page as modified the moment it opened in imperial mode and nudge the
     * stored weight on every OK.
     */
    private double baselineWeightKg;
    private double baselineHeightCm;
    private GoalType baselineDailyGoalType = GoalType.NONE;
    private double baselineDailyGoalValue;
    private GoalType baselineWeeklyGoalType = GoalType.NONE;
    private double baselineWeeklyGoalValue;

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
        baselineWeightKg = profile.weightKg;
        baselineHeightCm = profile.heightCm;
        baselineDailyGoalType = settings.getDailyGoalType();
        baselineDailyGoalValue = settings.getDailyGoalValue();
        baselineWeeklyGoalType = settings.getWeeklyGoalType();
        baselineWeeklyGoalValue = settings.getWeeklyGoalValue();

        fieldUnits = settings.getUnitSystem();
        unitsCombo.setSelectedItem(fieldUnits);
        weightField.setText(format(fieldUnits.weightFromKg(profile.weightKg)));
        heightField.setText(format(fieldUnits.heightFromCm(profile.heightCm)));
        algorithmCombo.setSelectedItem(settings.getSelectedAlgorithm());
        autoPauseField.setText(String.valueOf(settings.getAutoPauseMinutes()));
        moveReminderField.setText(String.valueOf(settings.getMoveReminderMinutes()));
        goalTypeCombo.setSelectedItem(baselineDailyGoalType);
        goalValueField.setText(goalText(baselineDailyGoalType, baselineDailyGoalValue));
        weeklyGoalTypeCombo.setSelectedItem(baselineWeeklyGoalType);
        weeklyGoalValueField.setText(goalText(baselineWeeklyGoalType, baselineWeeklyGoalValue));
        streakRestDaysField.setText(String.valueOf(settings.getStreakRestDaysPerWeek()));
        streakRiskHourField.setText(String.valueOf(settings.getStreakRiskHour()));
        updateUnitLabels();
        goalTypeChanged();
    }

    private String goalText(GoalType type, double metricValue) {
        if (metricValue <= 0) {
            return "";
        }
        return format(type == GoalType.DISTANCE ? fieldUnits.distanceFromKm(metricValue) : metricValue);
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
        profile.weightKg = resolveMetric(weightField.getText(), baselineWeightKg, units::weightFromKg, units::weightToKg);
        profile.heightCm = resolveMetric(heightField.getText(), baselineHeightCm, units::heightFromCm, units::heightToCm);
        profile.completed = true;
        return profile;
    }

    /**
     * The metric value a field stands for: the baseline when the text still
     * shows the baseline's (rounded) display value, the converted text
     * otherwise. Package-visible for tests.
     */
    static double resolveMetric(
            String text, double baselineMetric, DoubleUnaryOperator fromMetric, DoubleUnaryOperator toMetric) {
        double display = parseDouble(text);
        if (baselineMetric > 0 && Math.abs(display - fromMetric.applyAsDouble(baselineMetric)) <= DISPLAY_TOLERANCE) {
            return baselineMetric;
        }
        return toMetric.applyAsDouble(display);
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
                baselineDailyGoalType, baselineDailyGoalValue);
    }

    public GoalType getWeeklyGoalType() {
        Object selected = weeklyGoalTypeCombo.getSelectedItem();
        return selected instanceof GoalType ? (GoalType) selected : GoalType.NONE;
    }

    /** Weekly goal value converted to metric terms (steps, km, or kcal). */
    public double getWeeklyGoalValueMetric() {
        return goalValueMetric(getWeeklyGoalType(), weeklyGoalValueField.getText(),
                baselineWeeklyGoalType, baselineWeeklyGoalValue);
    }

    private double goalValueMetric(GoalType type, String text, GoalType baselineType, double baselineValue) {
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
        UnitSystem units = getUnitSystem();
        // Only a distance goal is unit-converted, so only it can suffer the
        // rounding round trip; steps and kcal are stored as typed.
        double baseline = baselineType == GoalType.DISTANCE ? baselineValue : 0.0;
        return resolveMetric(text, baseline, units::distanceFromKm, units::distanceToKm);
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
        double weightKg = units.weightToKg(parseDouble(weightField.getText()));
        double heightCm = units.heightToCm(parseDouble(heightField.getText()));
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

    private static void convertField(JBTextField field, DoubleUnaryOperator toMetric, DoubleUnaryOperator fromMetric) {
        double value = parseDouble(field.getText());
        if (value > 0) {
            field.setText(format(fromMetric.applyAsDouble(toMetric.applyAsDouble(value))));
        }
    }

    private void updateUnitLabels() {
        UnitSystem units = getUnitSystem();
        weightLabel.setText(TreadmillBundle.message("settings.weight", units.weightUnit()));
        heightLabel.setText(TreadmillBundle.message("settings.height", units.heightUnit()));
        updateGoalValueLabel();
    }

    private void goalTypeChanged() {
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
