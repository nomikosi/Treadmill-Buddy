package com.codex.desktreadmill.engine;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;

/**
 * Connects the engine to the running IDE: a Swing timer that ticks while a
 * session runs, a minute timer for move reminders, and an AWT listener that
 * feeds keyboard and mouse activity into the idle auto-pause. Unit tests
 * leave it out and drive the engine by hand.
 */
final class WorkoutDriver implements WorkoutEngine.Listener {
    private static final int TICK_INTERVAL_MILLIS = 250;
    private static final int REMINDER_CHECK_INTERVAL_MILLIS = 60_000;

    private final WorkoutEngine engine;
    private final Timer tickTimer;
    private final Timer reminderTimer;
    private final AWTEventListener activityListener = this::onUserActivity;

    WorkoutDriver(WorkoutEngine engine) {
        this.engine = engine;
        tickTimer = new Timer(TICK_INTERVAL_MILLIS, event -> engine.tick());
        reminderTimer = new Timer(REMINDER_CHECK_INTERVAL_MILLIS, event -> engine.checkMoveReminder());
    }

    void install() {
        engine.addListener(this);
        Toolkit.getDefaultToolkit().addAWTEventListener(
                activityListener,
                AWTEvent.KEY_EVENT_MASK
                        | AWTEvent.MOUSE_EVENT_MASK
                        | AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.MOUSE_WHEEL_EVENT_MASK
        );
        reminderTimer.start();
    }

    void uninstall() {
        engine.removeListener(this);
        tickTimer.stop();
        reminderTimer.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener);
    }

    /** Ticks exactly while a session runs; every start and stop is announced to listeners. */
    @Override
    public void workoutStateChanged() {
        if (!engine.isRunning()) {
            tickTimer.stop();
        } else if (!tickTimer.isRunning()) {
            tickTimer.start();
        }
    }

    private void onUserActivity(AWTEvent event) {
        engine.noteUserActivity();
        // Any activity keeps the session alive, but only typing resumes an
        // auto-paused one: scrolling to read code doesn't mean you're walking again.
        if (engine.isAutoPaused() && event instanceof KeyEvent keyEvent && isTypingKey(keyEvent)) {
            SwingUtilities.invokeLater(engine::resumeAfterTyping);
        }
    }

    /**
     * A key press that means "typing": not an action key (F-keys, arrows,
     * Page Up) and not a lone modifier or lock key - a stray Shift or Ctrl
     * while reaching for the mouse is not evidence of walking again.
     */
    static boolean isTypingKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED || event.isActionKey()) {
            return false;
        }
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH,
                    KeyEvent.VK_META, KeyEvent.VK_WINDOWS, KeyEvent.VK_CONTEXT_MENU,
                    KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK -> false;
            default -> true;
        };
    }
}
