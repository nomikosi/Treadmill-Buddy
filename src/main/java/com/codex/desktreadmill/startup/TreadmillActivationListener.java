package com.codex.desktreadmill.startup;

import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

/**
 * Refreshes session history when this IDE regains focus, so walks saved in
 * another JetBrains IDE (they share the home-directory store) show up without
 * waiting for this IDE's next write.
 */
public final class TreadmillActivationListener implements ApplicationActivationListener {

    @Override
    public void applicationActivated(@NotNull IdeFrame ideFrame) {
        TreadmillSettings.getInstance().reloadSessions();
        WorkoutEngine.getInstance().notifySessionsChanged();
    }
}
