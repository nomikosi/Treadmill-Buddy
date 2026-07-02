package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Application-level workout state. There is exactly one engine per IDE instance,
 * so every project window, floating clock, and status bar widget observes the
 * same session instead of running its own timer.
 *
 * <p>All state mutations happen on the EDT: the Swing timer, the AWT activity
 * listener, and every UI caller already run there.</p>
 */
@Service(Service.Level.APP)
public final class WorkoutEngine implements Disposable {

    public interface Listener {
        /** Running state, current session, or its metrics changed. */
        void workoutStateChanged();

        /** A countdown session reached its target. */
        default void sessionCompleted(SessionData session) {
        }

        /** The saved-session list in settings changed. */
        default void sessionsPersisted() {
        }
    }

    private static final int TICK_INTERVAL_MILLIS = 250;
    private static final int REMINDER_CHECK_INTERVAL_MILLIS = 60_000;
    private static final long PERSIST_INTERVAL_MILLIS = 30_000L;
    /** A tick gap this large means the machine was suspended, not that the EDT was busy. */
    private static final long SUSPEND_GAP_MILLIS = 60_000L;

    private final TreadmillSettings settings;
    private final LongSupplier clock;
    /** False in unit tests: skips AWT hooks, notifications, and the beep. */
    private final boolean interactive;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Timer timer = new Timer(TICK_INTERVAL_MILLIS, event -> tick());
    private final Timer reminderTimer = new Timer(REMINDER_CHECK_INTERVAL_MILLIS, event -> maybeRemindToMove());
    private final AWTEventListener activityListener = this::onUserActivity;

    private SessionData session;
    private boolean running;
    private boolean autoPaused;
    private String statusNote = "";
    private long lastTickMillis;
    private long carryMillis;
    private long lastActivityMillis;
    private long lastPersistMillis;
    private long lastWalkMillis;
    private long lastReminderMillis;

    public WorkoutEngine() {
        this(TreadmillSettings.getInstance(), System::currentTimeMillis, true);
    }

    WorkoutEngine(TreadmillSettings settings, LongSupplier clock, boolean interactive) {
        this.settings = settings;
        this.clock = clock;
        this.interactive = interactive;
        long now = clock.getAsLong();
        lastActivityMillis = now;
        lastWalkMillis = now;
        lastReminderMillis = now;
        if (interactive) {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    activityListener,
                    AWTEvent.KEY_EVENT_MASK
                            | AWTEvent.MOUSE_EVENT_MASK
                            | AWTEvent.MOUSE_MOTION_EVENT_MASK
                            | AWTEvent.MOUSE_WHEEL_EVENT_MASK
            );
            reminderTimer.start();
        }
    }

    public static WorkoutEngine getInstance() {
        return ApplicationManager.getApplication().getService(WorkoutEngine.class);
    }

    @Override
    public void dispose() {
        timer.stop();
        reminderTimer.stop();
        if (interactive) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener);
        }
        if (session != null) {
            settings.saveSession(session);
        }
        listeners.clear();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public @Nullable SessionData getSession() {
        return session;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAutoPaused() {
        return autoPaused;
    }

    public String getStatusNote() {
        return statusNote;
    }

    public void startSession(SessionData newSession) {
        session = newSession;
        beginRunning();
    }

    public void resume() {
        if (session == null || session.completed) {
            return;
        }
        session.completed = false;
        beginRunning();
    }

    public void pause() {
        pauseInternal(false, "");
    }

    /** Sets the current session without starting the clock. */
    public void loadSession(SessionData loaded) {
        timer.stop();
        running = false;
        autoPaused = false;
        statusNote = "";
        session = loaded;
        notifyStateChanged();
    }

    public void clearSession() {
        timer.stop();
        running = false;
        autoPaused = false;
        statusNote = "";
        session = null;
        notifyStateChanged();
    }

    /** Clears the current session if it matches the given id (e.g. after deletion). */
    public void clearSessionIf(String id) {
        if (session != null && session.id.equals(id)) {
            clearSession();
        }
    }

    public void reset() {
        if (session == null) {
            return;
        }
        timer.stop();
        running = false;
        autoPaused = false;
        statusNote = "";
        session.elapsedSeconds = 0L;
        session.remainingSeconds = session.targetSeconds;
        session.distanceKm = 0.0;
        session.steps = 0L;
        session.calories = 0.0;
        session.completed = false;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        persist();
        notifyStateChanged();
    }

    public void setSpeed(double speedKmh) {
        if (session == null || session.completed) {
            return;
        }
        if (Math.abs(session.speedKmh - speedKmh) < 0.001) {
            return;
        }
        session.speedKmh = speedKmh;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        notifyStateChanged();
    }

    public void setIncline(double inclinePercent) {
        if (session == null || session.completed) {
            return;
        }
        if (Math.abs(session.inclinePercent - inclinePercent) < 0.001) {
            return;
        }
        session.inclinePercent = inclinePercent;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        notifyStateChanged();
    }

    public void setAlgorithm(CalorieAlgorithm algorithm) {
        if (session == null || session.completed) {
            return;
        }
        if (session.algorithmId.equals(algorithm.name())) {
            return;
        }
        session.algorithmId = algorithm.name();
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        notifyStateChanged();
    }

    public void setSessionName(String name) {
        if (session == null || name.isBlank()) {
            return;
        }
        session.name = name.trim();
    }

    public void persistNow() {
        persist();
        notifyStateChanged();
    }

    /** Tells all listeners that the saved-session list changed outside the engine (delete, undo). */
    public void notifySessionsChanged() {
        for (Listener listener : listeners) {
            listener.sessionsPersisted();
        }
    }

    /** Re-renders all observers, e.g. after settings (units, goal) changed. */
    public void refreshListeners() {
        notifyStateChanged();
        notifySessionsChanged();
    }

    private void beginRunning() {
        long now = clock.getAsLong();
        running = true;
        autoPaused = false;
        statusNote = "";
        lastTickMillis = now;
        carryMillis = 0L;
        lastActivityMillis = now;
        lastWalkMillis = now;
        timer.start();
        persist();
        notifyStateChanged();
    }

    private void pauseInternal(boolean auto, String note) {
        if (!running) {
            return;
        }
        timer.stop();
        running = false;
        autoPaused = auto;
        statusNote = note;
        carryMillis = 0L;
        lastWalkMillis = clock.getAsLong();
        persist();
        notifyStateChanged();
    }

    void tick() {
        if (!running || session == null) {
            return;
        }
        long now = clock.getAsLong();
        long delta = now - lastTickMillis;
        lastTickMillis = now;
        if (delta <= 0) {
            return;
        }
        if (delta >= SUSPEND_GAP_MILLIS) {
            // Don't credit walking time that passed while the machine slept.
            pauseInternal(true, "Auto-paused after system sleep");
            return;
        }
        if (shouldAutoPauseForIdle(now)) {
            pauseInternal(true, "Auto-paused after " + settings.getAutoPauseMinutes() + " minutes without activity");
            return;
        }
        carryMillis += delta;
        long secondsToAdvance = carryMillis / 1000L;
        carryMillis %= 1000L;
        if (secondsToAdvance == 0) {
            return;
        }
        lastWalkMillis = now;
        SessionMode mode = SessionMode.fromId(session.modeId);
        for (long i = 0; i < secondsToAdvance && running; i++) {
            WorkoutMath.advanceOneSecond(session, settings.getProfile());
            if (mode != SessionMode.MARATHON && session.remainingSeconds == 0L) {
                completeSession();
                return;
            }
        }
        if (now - lastPersistMillis >= PERSIST_INTERVAL_MILLIS) {
            persist();
        }
        notifyStateChanged();
    }

    private void completeSession() {
        timer.stop();
        running = false;
        autoPaused = false;
        statusNote = "";
        session.completed = true;
        lastWalkMillis = clock.getAsLong();
        persist();
        if (interactive) {
            Toolkit.getDefaultToolkit().beep();
            TreadmillNotifications.info(
                    TreadmillBundle.message("notification.session.complete.title"),
                    TreadmillBundle.message("notification.session.complete.content",
                            session.name,
                            String.format("%.2f", session.distanceKm),
                            String.format("%.0f", session.calories))
            );
        }
        SessionData completed = session;
        for (Listener listener : listeners) {
            listener.sessionCompleted(completed);
        }
        notifyStateChanged();
    }

    private void maybeRemindToMove() {
        int reminderMinutes = settings.getMoveReminderMinutes();
        if (reminderMinutes <= 0 || running) {
            return;
        }
        long now = clock.getAsLong();
        long reminderMillis = reminderMinutes * 60_000L;
        if (now - lastWalkMillis < reminderMillis || now - lastReminderMillis < reminderMillis) {
            return;
        }
        lastReminderMillis = now;
        NotificationGroupManager.getInstance()
                .getNotificationGroup(TreadmillNotifications.GROUP_ID)
                .createNotification(
                        TreadmillBundle.message("notification.move.title"),
                        TreadmillBundle.message("notification.move.content", reminderMinutes),
                        NotificationType.INFORMATION
                )
                .addAction(NotificationAction.createSimpleExpiring(
                        TreadmillBundle.message("notification.move.action"), WorkoutEngine::openToolWindow))
                .notify(null);
    }

    private static void openToolWindow() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) {
                continue;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(com.codex.desktreadmill.TreadmillToolWindowFactory.TOOL_WINDOW_ID);
            if (toolWindow != null) {
                toolWindow.activate(null);
                return;
            }
        }
    }

    private boolean shouldAutoPauseForIdle(long now) {
        int idleMinutes = settings.getAutoPauseMinutes();
        if (idleMinutes == 0) {
            return false;
        }
        return now - lastActivityMillis >= idleMinutes * 60_000L;
    }

    private void onUserActivity(AWTEvent event) {
        lastActivityMillis = clock.getAsLong();
        // Any activity keeps the session alive, but only typing resumes an
        // auto-paused one: scrolling to read code doesn't mean you're walking again.
        if (autoPaused
                && event instanceof KeyEvent keyEvent
                && keyEvent.getID() == KeyEvent.KEY_PRESSED
                && !keyEvent.isActionKey()) {
            SwingUtilities.invokeLater(this::resumeAfterTyping);
        }
    }

    private void resumeAfterTyping() {
        if (!autoPaused || running || session == null || session.completed) {
            return;
        }
        resume();
    }

    private void persist() {
        if (session == null) {
            return;
        }
        settings.saveSession(session);
        lastPersistMillis = clock.getAsLong();
        for (Listener listener : listeners) {
            listener.sessionsPersisted();
        }
        maybeCelebrateDailyGoal();
    }

    private void maybeCelebrateDailyGoal() {
        if (!interactive) {
            return;
        }
        GoalType goalType = settings.getDailyGoalType();
        double target = settings.getDailyGoalValue();
        if (goalType == GoalType.NONE || target <= 0) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
        if (settings.getLastGoalAchievedDay() == today.toEpochDay()) {
            return;
        }
        long startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli();
        SessionStats.Totals totals = SessionStats.totalsSince(settings.getSessions(), startOfToday);
        double progress = switch (goalType) {
            case STEPS -> totals.steps;
            case DISTANCE -> totals.distanceKm;
            case CALORIES -> totals.calories;
            case NONE -> 0.0;
        };
        if (progress >= target) {
            settings.setLastGoalAchievedDay(today.toEpochDay());
            TreadmillNotifications.info(
                    TreadmillBundle.message("notification.goal.title"),
                    TreadmillBundle.message("notification.goal.content",
                            goalType.formatValue(target, settings.getUnitSystem()))
            );
        }
    }

    private void notifyStateChanged() {
        for (Listener listener : listeners) {
            listener.workoutStateChanged();
        }
    }
}
