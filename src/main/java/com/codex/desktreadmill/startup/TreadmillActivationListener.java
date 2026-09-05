package com.codex.desktreadmill.startup;

import com.codex.desktreadmill.engine.WorkoutEngine;
import com.codex.desktreadmill.model.SessionData;
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
        TreadmillSettings settings = TreadmillSettings.getInstance();
        // The store only re-reads the file when it actually changed, so an
        // alt-tab with nothing new costs one stat call and no re-rendering.
        if (!settings.reloadSessions()) {
            return;
        }
        WorkoutEngine engine = WorkoutEngine.getInstance();
        SessionData current = engine.getSession();
        if (current != null) {
            // The paused walk on this clock may have been continued elsewhere;
            // Resume must pick up from that newer state, not overwrite it.
            engine.adoptStoredProgress(settings.findSession(current.id));
        }
        engine.notifySessionsChanged();
    }
}
