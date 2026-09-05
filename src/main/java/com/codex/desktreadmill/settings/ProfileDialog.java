package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.TreadmillBundle;
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
        setTitle(TreadmillBundle.message("profile.dialog.title"));
        profilePanel.setValues(TreadmillSettings.getInstance());
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
        // Same writer as the Settings page, so the first-run dialog can't
        // quietly drop the weekly goal, rest days, or risk hour it displays.
        profilePanel.applyTo(TreadmillSettings.getInstance());
        WorkoutEngine.getInstance().refreshListeners();
    }
}
