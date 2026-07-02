package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.engine.WorkoutEngine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public final class ProfileDialog extends DialogWrapper {
    private final ProfilePanel profilePanel = new ProfilePanel();

    public ProfileDialog(@Nullable Project project) {
        super(project);
        setTitle("Treadmill Buddy Profile");
        profilePanel.setValues(
                TreadmillSettings.getInstance().getProfile(),
                TreadmillSettings.getInstance().getSelectedAlgorithm()
        );
        init();
    }

    public static void showIfNeeded(Project project) {
        TreadmillSettings settings = TreadmillSettings.getInstance();
        if (settings.getProfile().isComplete()) {
            return;
        }
        ProfileDialog dialog = new ProfileDialog(project);
        if (dialog.showAndGet()) {
            dialog.applyValues();
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return profilePanel.getComponent();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String validation = profilePanel.validateInput();
        return validation == null ? null : new ValidationInfo(validation);
    }

    private void applyValues() {
        TreadmillSettings settings = TreadmillSettings.getInstance();
        settings.setProfile(profilePanel.getProfile());
        settings.setSelectedAlgorithm(profilePanel.getAlgorithm());
        settings.setAutoPauseMinutes(profilePanel.getAutoPauseMinutes());
        settings.setMoveReminderMinutes(profilePanel.getMoveReminderMinutes());
        settings.setUnitSystem(profilePanel.getUnitSystem());
        settings.setDailyGoalType(profilePanel.getDailyGoalType());
        settings.setDailyGoalValue(profilePanel.getDailyGoalValueMetric());
        WorkoutEngine.getInstance().refreshListeners();
    }
}
