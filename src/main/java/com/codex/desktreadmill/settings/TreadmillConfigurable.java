package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.engine.WorkoutEngine;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public final class TreadmillConfigurable implements Configurable {
    private ProfilePanel profilePanel;

    @Override
    public @Nls String getDisplayName() {
        return "Treadmill Buddy";
    }

    @Override
    public @Nullable JComponent createComponent() {
        profilePanel = new ProfilePanel();
        reset();
        return profilePanel.getComponent();
    }

    @Override
    public boolean isModified() {
        if (profilePanel == null) {
            return false;
        }
        return profilePanel.isModified(TreadmillSettings.getInstance());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (profilePanel == null) {
            return;
        }
        String validation = profilePanel.validateInput();
        if (validation != null) {
            throw new ConfigurationException(validation);
        }
        TreadmillSettings settings = TreadmillSettings.getInstance();
        settings.setProfile(profilePanel.getProfile());
        settings.setSelectedAlgorithm(profilePanel.getAlgorithm());
        settings.setAutoPauseMinutes(profilePanel.getAutoPauseMinutes());
        settings.setMoveReminderMinutes(profilePanel.getMoveReminderMinutes());
        settings.setUnitSystem(profilePanel.getUnitSystem());
        settings.setDailyGoalType(profilePanel.getDailyGoalType());
        settings.setDailyGoalValue(profilePanel.getDailyGoalValueMetric());
        settings.setWeeklyGoalType(profilePanel.getWeeklyGoalType());
        settings.setWeeklyGoalValue(profilePanel.getWeeklyGoalValueMetric());
        settings.setStreakRestDaysPerWeek(profilePanel.getStreakRestDaysPerWeek());
        WorkoutEngine.getInstance().refreshListeners();
    }

    @Override
    public void reset() {
        if (profilePanel == null) {
            return;
        }
        TreadmillSettings settings = TreadmillSettings.getInstance();
        profilePanel.setValues(settings.getProfile(), settings.getSelectedAlgorithm());
    }

    @Override
    public void disposeUIResources() {
        profilePanel = null;
    }
}
