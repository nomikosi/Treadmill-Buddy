package com.codex.desktreadmill.settings;

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
        TreadmillSettings settings = TreadmillSettings.getInstance();
        return profilePanel.isModified(
                settings.getProfile(),
                settings.getSelectedAlgorithm(),
                settings.getAutoPauseMinutes()
        );
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
