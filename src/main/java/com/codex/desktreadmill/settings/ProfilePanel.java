package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
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
    private final JBTextField weightField = new JBTextField();
    private final JBTextField ageField = new JBTextField();
    private final JBTextField heightField = new JBTextField();
    private final JBTextField autoPauseField = new JBTextField();
    private final ComboBox<CalorieAlgorithm> algorithmCombo = new ComboBox<>(CalorieAlgorithm.values());
    private final JPanel panel;

    public ProfilePanel() {
        ComboHelp.configureAlgorithmCombo(algorithmCombo, this::getAlgorithm);
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Weight (kg)"), weightField, 1, false)
                .addLabeledComponent(new JBLabel("Age"), ageField, 1, false)
                .addLabeledComponent(new JBLabel("Height (cm)"), heightField, 1, false)
                .addLabeledComponent(new JBLabel("Default calorie algorithm"), algorithmCombo, 1, false)
                .addLabeledComponent(new JBLabel("Auto-pause idle typing (minutes)"), autoPauseField, 1, false)
                .addComponentFillVertically(new JPanel(new BorderLayout()), 0)
                .getPanel();
    }

    public JComponent getComponent() {
        return panel;
    }

    public void setValues(UserProfile profile, CalorieAlgorithm algorithm) {
        weightField.setText(format(profile.weightKg));
        ageField.setText(String.valueOf(profile.age));
        heightField.setText(format(profile.heightCm));
        algorithmCombo.setSelectedItem(algorithm);
        autoPauseField.setText(String.valueOf(TreadmillSettings.getInstance().getAutoPauseMinutes()));
    }

    public UserProfile getProfile() {
        UserProfile profile = new UserProfile();
        profile.weightKg = parseDouble(weightField.getText());
        profile.age = parseInt(ageField.getText());
        profile.heightCm = parseDouble(heightField.getText());
        profile.completed = true;
        return profile;
    }

    public CalorieAlgorithm getAlgorithm() {
        Object selected = algorithmCombo.getSelectedItem();
        return selected instanceof CalorieAlgorithm ? (CalorieAlgorithm) selected : CalorieAlgorithm.ACSM_FLAT;
    }

    public int getAutoPauseMinutes() {
        return parseInt(autoPauseField.getText());
    }

    public String validateInput() {
        double weight = parseDouble(weightField.getText());
        int age = parseInt(ageField.getText());
        double height = parseDouble(heightField.getText());
        if (weight < 20 || weight > 300) {
            return "Enter a weight between 20 and 300 kg.";
        }
        if (age < 10 || age > 120) {
            return "Enter an age between 10 and 120.";
        }
        if (height < 90 || height > 250) {
            return "Enter a height between 90 and 250 cm.";
        }
        int autoPauseMinutes = parseInt(autoPauseField.getText());
        if (autoPauseMinutes < 0 || autoPauseMinutes > 240) {
            return "Enter auto-pause minutes between 0 and 240. Use 0 to disable it.";
        }
        return null;
    }

    public boolean isModified(UserProfile profile, CalorieAlgorithm algorithm, int autoPauseMinutes) {
        UserProfile edited = getProfile();
        return Math.abs(edited.weightKg - profile.weightKg) > 0.001
                || edited.age != profile.age
                || Math.abs(edited.heightCm - profile.heightCm) > 0.001
                || getAlgorithm() != algorithm
                || getAutoPauseMinutes() != autoPauseMinutes;
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
