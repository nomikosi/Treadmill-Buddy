package com.codex.desktreadmill;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.util.Alarm;

/**
 * Owns the plugin's delayed UI callbacks so pending work dies with the plugin.
 *
 * <p>A bare {@link javax.swing.Timer} would keep firing into a disposing IDE and
 * would pin the plugin classloader for the length of the delay after a dynamic
 * unload. An {@link Alarm} registered against a disposable service is what the
 * platform itself uses for balloon fade-outs, and it cancels pending requests
 * on disposal.</p>
 */
@Service(Service.Level.APP)
public final class TreadmillScheduler implements Disposable {

    /**
     * Defaults to the EDT, and is cancelled when this service is disposed.
     * Alarm carries {@code @ApiStatus.Obsolete} in 2024.3, but its coroutine
     * replacement is {@code @ApiStatus.Internal} there - this is the correct
     * public API for a Java plugin on this baseline, so leave it be.
     */
    private final Alarm alarm = new Alarm(this);

    public static TreadmillScheduler getInstance() {
        return ApplicationManager.getApplication().getService(TreadmillScheduler.class);
    }

    /** Runs {@code task} on the EDT after {@code millis}, or never if the IDE shuts down first. */
    public void onEdtAfter(int millis, Runnable task) {
        // Scheduling onto a disposed Alarm is itself logged as an IDE error.
        if (alarm.isDisposed()) {
            return;
        }
        // any(): closing a balloon shouldn't queue up behind a modal dialog.
        alarm.addRequest(task, millis, ModalityState.any());
    }

    @Override
    public void dispose() {
        // The Alarm is a child of this service and is cancelled with it.
    }
}
