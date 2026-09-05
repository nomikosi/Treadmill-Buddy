package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.TreadmillBundle;
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
        return TreadmillBundle.message("settings.displayName");
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
        profilePanel.applyTo(TreadmillSettings.getInstance());
        WorkoutEngine.getInstance().refreshListeners();
    }

    @Override
    public void reset() {
        if (profilePanel == null) {
            return;
        }
        profilePanel.setValues(TreadmillSettings.getInstance());
    }

    @Override
    public void disposeUIResources() {
        profilePanel = null;
    }
}
