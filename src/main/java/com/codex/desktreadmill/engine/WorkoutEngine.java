package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.IdeWorkoutFeedback;
import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Application-level workout state. There is exactly one engine per IDE instance,
 * so every project window, floating clock, and status bar widget observes the
 * same session instead of running its own timer.
 *
 * <p>All state mutations happen on the EDT: the {@link WorkoutDriver}'s timers
 * and activity listener, and every UI caller, already run there. What the
 * user sees or hears beyond the listeners goes through {@link WorkoutFeedback};
 * goals, records, and move reminders each have their own small tracker.</p>
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

    private static final long PERSIST_INTERVAL_MILLIS = 30_000L;
    /** A tick gap this large means the machine was suspended, not that the EDT was busy. */
    private static final long SUSPEND_GAP_MILLIS = 60_000L;

    private final TreadmillSettings settings;
    private final LongSupplier clock;
    private final WorkoutFeedback feedback;
    private final GoalTracker goals;
    private final RecordTracker records;
    private final MoveReminder moveReminder;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** The IDE's timers and activity hook; absent when a test drives the engine by hand. */
    private @Nullable WorkoutDriver driver;
    private boolean disposed;

    private SessionData session;
    private boolean running;
    private boolean autoPaused;
    private boolean keepRunningWhenIdle;
    /**
     * True while the session holds changes the store hasn't seen. Shutdown
     * only flushes a dirty session: re-saving a paused one that was already
     * persisted at pause time would overwrite whatever another IDE did to it
     * since, and that walk is the newer one.
     */
    private boolean dirty;
    private boolean knownToStore;
    /** Present only when the unsaved changes are paused form edits, not new activity. */
    private SessionData pausedEditBaseline;
    /** The session whose longest-session guard the last {@link #reset()} cleared, for its undo. */
    private @Nullable String resetRecordGuardId;
    private String statusNote = "";
    private long lastTickMillis;
    private long lastActivityMillis;
    private long lastPersistMillis;
    private long lastWalkMillis;

    public WorkoutEngine() {
        this(TreadmillSettings.getInstance(), System::currentTimeMillis, new IdeWorkoutFeedback());
        driver = new WorkoutDriver(this);
        driver.install();
    }

    WorkoutEngine(TreadmillSettings settings, LongSupplier clock, WorkoutFeedback feedback) {
        this.settings = settings;
        this.clock = clock;
        this.feedback = feedback;
        long now = clock.getAsLong();
        goals = new GoalTracker(settings, feedback);
        records = new RecordTracker(settings, feedback);
        moveReminder = new MoveReminder(settings, feedback, now);
        lastActivityMillis = now;
        lastWalkMillis = now;
    }

    public static WorkoutEngine getInstance() {
        return ApplicationManager.getApplication().getService(WorkoutEngine.class);
    }

    @Override
    public void dispose() {
        if (driver != null) {
            driver.uninstall();
        }
        disposed = true;
        if (session != null && dirty) {
            persist();
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

    /** When set, keyboard inactivity never pauses the session (system sleep still does). */
    public boolean isKeepRunningWhenIdle() {
        return keepRunningWhenIdle;
    }

    public void setKeepRunningWhenIdle(boolean keepRunning) {
        if (keepRunningWhenIdle == keepRunning) {
            return;
        }
        keepRunningWhenIdle = keepRunning;
        // Fold the idle window that already accrued, so enabling the toggle
        // right before the threshold doesn't pause on the next tick anyway.
        lastActivityMillis = clock.getAsLong();
        notifyStateChanged();
    }

    public String getStatusNote() {
        return statusNote;
    }

    public void startSession(SessionData newSession) {
        retireCurrentSession();
        session = newSession;
        knownToStore = false;
        pausedEditBaseline = null;
        beginRunning();
    }

    public void resume() {
        if (running) {
            return;
        }
        reloadSessions();
        if (session == null || session.completed) {
            return;
        }
        beginRunning();
    }

    public void pause() {
        tick(); // Settle time since the last timer event before stopping.
        pauseInternal(false, "");
        if (autoPaused) {
            autoPaused = false;
            statusNote = "";
            notifyStateChanged();
        }
    }

    /** Resume from an editable form only when the complete configuration is valid. */
    public boolean resume(WorkoutInputs inputs) {
        if (!applyInputs(inputs)) {
            return false;
        }
        if (!session.completed) {
            beginRunning();
        }
        return true;
    }

    /** Sets the current session without starting the clock. */
    public void loadSession(SessionData loaded) {
        // History rows are snapshots. Retiring the live session updates the
        // store, but cannot update the row that was already passed in.
        boolean sameSession = session != null && session.id.equals(loaded.id);
        retireCurrentSession();
        if (!sameSession) {
            SessionData stored = settings.findSession(loaded.id);
            session = stored == null ? loaded.copy() : stored;
            knownToStore = stored != null;
            dirty = false;
            pausedEditBaseline = null;
        }
        notifyStateChanged();
    }

    public void clearSession() {
        retireCurrentSession();
        // Retiring saved the walk, which marked it as the one to reopen. An
        // empty clock must stay empty: a tool window opened later, in this
        // project or another, would otherwise load the walk straight back.
        settings.clearLastSessionId();
        discardSession();
    }

    /** Discard is deliberately separate from New, which saves the previous walk. */
    private void discardSession() {
        stopClock();
        session = null;
        dirty = false;
        knownToStore = false;
        pausedEditBaseline = null;
        notifyStateChanged();
    }

    /**
     * Stops the clock before the current session is swapped out. A running
     * session is paused rather than just stopped, because pausing persists:
     * without that, New, a mode switch, or loading another session silently
     * dropped everything walked since the last 30-second autosave.
     */
    private void retireCurrentSession() {
        if (running) {
            pause();
        } else if (dirty) {
            persist();
        }
        stopClock();
    }

    /** Stops crediting time; the driver stops its timer when listeners next hear from the engine. */
    private void stopClock() {
        running = false;
        autoPaused = false;
        statusNote = "";
    }

    /**
     * Takes over a stored copy of the current session that another IDE
     * extended, so Resume continues from the newer walk instead of overwriting
     * it with this clock's stale state. Only applies to a paused session whose
     * stored twin has actually moved on. Paused form edits are merged field by
     * field; unsaved activity and a running session remain authoritative.
     */
    public void adoptStoredProgress(@Nullable SessionData stored) {
        if (session == null || running) {
            return;
        }
        if (stored == null) {
            if (knownToStore) {
                discardSession();
            }
            return;
        }
        if (!stored.id.equals(session.id) || dirty && pausedEditBaseline == null) {
            return;
        }
        SessionData edited = session;
        SessionData baseline = pausedEditBaseline;
        session = stored.copy();
        knownToStore = true;
        dirty = false;
        pausedEditBaseline = null;
        if (baseline != null) {
            WorkoutInputs latest = WorkoutInputs.fromSession(session);
            // Completion or a mode change elsewhere supersedes a stale workout form.
            if (!session.completed && latest.mode() == SessionMode.fromId(baseline.modeId)) {
                WorkoutInputs merged = WorkoutInputs.fromSession(edited)
                        .mergeChanges(WorkoutInputs.fromSession(baseline), latest);
                if (!merged.equals(latest)) {
                    merged.applyConfiguration(session, settings.getProfile());
                    dirty = true;
                }
            }
            if (!edited.name.equals(baseline.name) && !edited.name.equals(session.name)) {
                session.name = edited.name;
                dirty = true;
            }
            if (dirty) {
                pausedEditBaseline = stored.copy();
            }
        }
        notifyStateChanged();
    }

    /** Reconcile paused state both on focus and before Resume/Save. */
    public void reloadSessions() {
        boolean changed = settings.reloadSessions();
        if (session != null) {
            adoptStoredProgress(settings.findSession(session.id));
        }
        if (changed) {
            notifySessionsChanged();
        }
    }

    public record Deletion(List<SessionData> sessions, String lastSessionId, boolean persisted) {
    }

    /** Captures Undo data and discards the clock before deleting from storage. */
    public Deletion deleteSessions(List<String> ids) {
        if (session != null && ids.contains(session.id)) {
            tick();
        }
        List<SessionData> deleted = new ArrayList<>();
        for (String id : ids) {
            SessionData stored = session != null && session.id.equals(id)
                    ? session.copy() : settings.findSession(id);
            if (stored != null) {
                deleted.add(stored);
            }
        }
        String lastId = settings.getLastSessionId();
        if (session != null && ids.contains(session.id)) {
            discardSession();
        }
        boolean persisted = settings.deleteSessions(ids);
        notifySessionsChanged();
        return new Deletion(List.copyOf(deleted), lastId, persisted);
    }

    public boolean undoDeletion(Deletion deletion) {
        boolean persisted = settings.saveSessions(deletion.sessions());
        // Only a marker that pointed at a restored walk comes back. Any other
        // blank marker was cleared on purpose (New) after the deletion.
        String lastId = deletion.lastSessionId();
        if (deletion.sessions().stream().anyMatch(restored -> restored.id.equals(lastId))) {
            settings.restoreLastSessionId(lastId);
        }
        notifySessionsChanged();
        return persisted;
    }

    /**
     * Undo for {@link #reset()}: puts the pre-reset copy back into the history
     * and, if it is still the session on the clock, back onto the clock. A
     * clock restarted since the reset is stopped first - left running, its
     * next save would overwrite the restored walk with the reset one.
     */
    public void restoreSession(SessionData previous) {
        boolean onClock = session != null && session.id.equals(previous.id);
        if (onClock && running) {
            stopClock();
            lastWalkMillis = clock.getAsLong();
        }
        boolean persisted = settings.restoreSession(previous);
        // The pre-reset walk already had its longest-session balloon.
        if (previous.id.equals(resetRecordGuardId) && settings.getLastSessionRecordId().isEmpty()) {
            settings.setLastSessionRecordId(previous.id);
        }
        resetRecordGuardId = null;
        if (onClock) {
            session = previous.copy();
            knownToStore = true;
            dirty = !persisted;
            pausedEditBaseline = null;
            notifyStateChanged();
        }
        notifySessionsChanged();
    }

    public void reset() {
        if (session == null) {
            return;
        }
        stopClock();
        pausedEditBaseline = null;
        session.resetProgress();
        // Reset restarts the walk under the same id, so let it earn the
        // longest-session record again instead of staying suppressed forever.
        resetRecordGuardId = null;
        if (session.id.equals(settings.getLastSessionRecordId())) {
            settings.setLastSessionRecordId("");
            resetRecordGuardId = session.id;
        }
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        persist();
        notifyStateChanged();
    }

    public void setSpeed(double speedKmh) {
        // NaN slips through range checks (every comparison is false) and would
        // poison distance and calories; guard the engine, not just the fields.
        if (session == null || session.completed || !Double.isFinite(speedKmh)) {
            return;
        }
        if (Math.abs(session.speedKmh - speedKmh) < 0.001) {
            return;
        }
        tick();
        if (session == null || session.completed) {
            return;
        }
        rememberPausedEdit();
        session.speedKmh = speedKmh;
        dirty = true;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        notifyStateChanged();
    }

    public void setIncline(double inclinePercent) {
        if (session == null || session.completed || !Double.isFinite(inclinePercent)) {
            return;
        }
        if (Math.abs(session.inclinePercent - inclinePercent) < 0.001) {
            return;
        }
        tick();
        if (session == null || session.completed) {
            return;
        }
        rememberPausedEdit();
        session.inclinePercent = inclinePercent;
        dirty = true;
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
        tick();
        if (session == null || session.completed) {
            return;
        }
        rememberPausedEdit();
        session.algorithmId = algorithm.name();
        dirty = true;
        WorkoutMath.recalcRemaining(session, settings.getProfile());
        notifyStateChanged();
    }

    /** Applies a validated paused form atomically, without discarding walked progress. */
    public boolean applyInputs(WorkoutInputs inputs) {
        if (running || session == null || session.completed
                || SessionMode.fromId(session.modeId) != inputs.mode()
                || inputs.invalidField(settings.getProfile()) != null) {
            return false;
        }
        // Capture the form's baseline before reload notifies UI listeners and refreshes fields.
        WorkoutInputs baseline = WorkoutInputs.fromSession(session);
        SessionData edited = session.copy();
        inputs.applyTo(edited, settings.getProfile());
        reloadSessions();
        if (session == null || session.completed || SessionMode.fromId(session.modeId) != inputs.mode()) {
            return false;
        }
        WorkoutInputs latest = WorkoutInputs.fromSession(session);
        WorkoutInputs merged = WorkoutInputs.fromSession(edited).mergeChanges(baseline, latest);
        if (merged.invalidField(settings.getProfile()) != null) {
            return false;
        }
        if (!merged.equals(latest) || !session.modeId.equals(merged.mode().name())
                || !session.algorithmId.equals(merged.algorithm().name())) {
            rememberPausedEdit();
            dirty = true;
        }
        merged.applyTo(session, settings.getProfile());
        if (inputs.mode().isCountdown() && session.remainingSeconds == 0) {
            completeSession();
        } else {
            notifyStateChanged();
        }
        return true;
    }

    public void setSessionName(String name) {
        if (session == null || name.isBlank() || name.trim().equals(session.name)) {
            return;
        }
        rememberPausedEdit();
        session.name = name.trim();
        dirty = true;
        notifyStateChanged();
    }

    private void rememberPausedEdit() {
        if (!running && !dirty && pausedEditBaseline == null) {
            pausedEditBaseline = session.copy();
        }
    }

    /** Returns whether the session reached disk; on false it stays in memory and is retried later. */
    public boolean persistNow() {
        tick();
        boolean hadSession = session != null;
        reloadSessions();
        if (hadSession && session == null) {
            return false; // A confirmed external deletion is not a successful save.
        }
        boolean persisted = persist();
        notifyStateChanged();
        return persisted;
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
        lastActivityMillis = now;
        lastWalkMillis = now;
        persist();
        notifyStateChanged();
    }

    private void pauseInternal(boolean auto, String note) {
        if (!running) {
            return;
        }
        running = false;
        autoPaused = auto;
        statusNote = note;
        lastWalkMillis = clock.getAsLong();
        persist();
        records.check(session, lastWalkMillis);
        notifyStateChanged();
    }

    void tick() {
        if (!running || session == null) {
            return;
        }
        long now = clock.getAsLong();
        long previousTick = lastTickMillis;
        lastTickMillis = now;
        long delta = now - previousTick;
        if (delta <= 0) {
            return;
        }
        if (delta >= SUSPEND_GAP_MILLIS) {
            // Don't credit walking time that passed while the machine slept.
            pauseInternal(true, TreadmillBundle.message("status.autoPaused.sleep"));
            return;
        }
        // Walking is credited up to the moment the idle threshold was crossed,
        // not up to the previous tick: after a stalled EDT the crossing can sit
        // well inside this delta, and the time before it was real walking.
        long idleDeadline = idleDeadline();
        boolean idle = now >= idleDeadline;
        long creditUntil = idle ? Math.max(previousTick, idleDeadline) : now;
        long secondsAdvanced = advance(creditUntil - previousTick, creditUntil, now);
        if (!running) {
            return; // A countdown completed inside the advance; completeSession did the rest.
        }
        if (idle) {
            pauseInternal(true, TreadmillBundle.message("status.autoPaused.idle", settings.getAutoPauseMinutes()));
            return;
        }
        if (secondsAdvanced == 0) {
            return;
        }
        if (now - lastPersistMillis >= PERSIST_INTERVAL_MILLIS) {
            persistInBackground();
        }
        notifyStateChanged();
    }

    /** Credits {@code millis} of walking to the session; returns the whole seconds it advanced. */
    private long advance(long millis, long creditUntil, long now) {
        if (millis > 0) {
            pausedEditBaseline = null;
            dirty = true;
        }
        long accruedMillis = session.timerRemainderMillis + millis;
        long secondsToAdvance = accruedMillis / 1000L;
        session.timerRemainderMillis = accruedMillis % 1000L;
        if (secondsToAdvance == 0) {
            return 0L;
        }
        lastWalkMillis = now;
        dirty = true;
        SessionMode mode = SessionMode.fromId(session.modeId);
        boolean phaseSwitched = false;
        for (long i = 0; i < secondsToAdvance && running; i++) {
            boolean walking = mode != SessionMode.INTERVAL || session.intervalWalking;
            if (walking && session.activityDays.isEmpty() && session.elapsedSeconds > 0) {
                // Older files have no dated breakdown. Preserve their known
                // totals on the original date before recording new activity.
                session.activityDays.add(new DailyActivity(session.createdMillis, session.elapsedSeconds,
                        session.distanceKm, session.steps, session.calories));
            }
            double previousKm = session.distanceKm;
            double previousCalories = session.calories;
            long previousSteps = session.steps;
            if (mode == SessionMode.INTERVAL) {
                phaseSwitched |= WorkoutMath.advanceIntervalSecond(session, settings.getProfile());
            } else {
                WorkoutMath.advanceOneSecond(session, settings.getProfile());
            }
            if (walking) {
                long secondEnd = creditUntil - session.timerRemainderMillis - (secondsToAdvance - i - 1) * 1000L;
                recordActivity(secondEnd - 1, session.distanceKm - previousKm,
                        session.steps - previousSteps, session.calories - previousCalories);
            }
            if (mode.isCountdown() && session.remainingSeconds == 0L) {
                session.timerRemainderMillis = 0L;
                completeSession();
                return secondsToAdvance;
            }
        }
        if (phaseSwitched) {
            if (session.intervalWalking) {
                // The chime is the prompt to step back on. Idle time piled up
                // during the break, and without a fresh window the first tick
                // of the walk block would auto-pause the session on the spot.
                lastActivityMillis = now;
            }
            announceIntervalPhase();
        }
        return secondsToAdvance;
    }

    private void recordActivity(long atMillis, double km, long steps, double calories) {
        ZoneId zone = ZoneId.systemDefault();
        long dateMillis = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
        DailyActivity day = session.activityDays.stream().filter(value -> value.dateMillis == dateMillis)
                .findFirst().orElse(null);
        if (day == null) {
            day = new DailyActivity(dateMillis, 0L, 0.0, 0L, 0.0);
            session.activityDays.add(day);
        }
        day.elapsedSeconds++;
        day.distanceKm += km;
        day.steps += steps;
        day.calories += calories;
    }

    private void announceIntervalPhase() {
        persistInBackground();
        boolean walking = session.intervalWalking;
        feedback.intervalBlockStarted(walking,
                (walking ? session.intervalWalkSeconds : session.intervalBreakSeconds) / 60L);
    }

    private void completeSession() {
        stopClock();
        session.completed = true;
        pausedEditBaseline = null;
        lastWalkMillis = clock.getAsLong();
        persist();
        records.check(session, lastWalkMillis);
        feedback.sessionCompleted(session, settings.getUnitSystem());
        SessionData completed = session;
        for (Listener listener : listeners) {
            listener.sessionCompleted(completed);
        }
        notifyStateChanged();
    }

    /** Called by the driver's minute timer. */
    void checkMoveReminder() {
        moveReminder.check(clock.getAsLong(), running, lastWalkMillis);
    }

    /** The instant the inactivity auto-pause takes effect, or {@link Long#MAX_VALUE} while it cannot. */
    private long idleDeadline() {
        if (keepRunningWhenIdle) {
            return Long.MAX_VALUE;
        }
        // During an interval break the user has stepped off and stopped typing
        // - exactly what idle detection looks for. Pausing would freeze the
        // break countdown and never chime them back to walking.
        if (session != null
                && SessionMode.fromId(session.modeId) == SessionMode.INTERVAL
                && !session.intervalWalking) {
            return Long.MAX_VALUE;
        }
        int idleMinutes = settings.getAutoPauseMinutes();
        if (idleMinutes == 0) {
            return Long.MAX_VALUE;
        }
        return lastActivityMillis + idleMinutes * 60_000L;
    }

    /** Keyboard or mouse activity seen by the driver: it keeps a running session from idling out. */
    void noteUserActivity() {
        lastActivityMillis = clock.getAsLong();
    }

    /** The driver's response to typing while auto-paused. */
    void resumeAfterTyping() {
        if (!autoPaused || running || session == null || session.completed) {
            return;
        }
        resume();
    }

    private boolean persist() {
        if (!running && pausedEditBaseline != null) {
            reloadSessions();
            if (session == null) {
                return false;
            }
        }
        if (session == null) {
            return true;
        }
        boolean persisted = settings.saveSession(session);
        // Once queued in the store, a failed write must retain the complete pending snapshot.
        pausedEditBaseline = null;
        knownToStore = true;
        // A failed write leaves the session dirty, so shutdown and the next
        // transition try again instead of believing it is safe on disk.
        dirty = !persisted;
        lastPersistMillis = clock.getAsLong();
        afterPersist();
        return persisted;
    }

    /**
     * The 30-second autosave and interval switches, which recur all walk long:
     * the history holds the walk as soon as this returns, and the file is
     * written on the store's thread instead of the EDT. The store owns the
     * write from here, retries included, so the session is no longer dirty.
     * Listeners hear about it once the write is done - reading the history
     * earlier would only wait for that write.
     */
    private void persistInBackground() {
        if (session == null) {
            return;
        }
        CompletableFuture<Boolean> written = settings.saveSessionLater(session);
        pausedEditBaseline = null;
        knownToStore = true;
        dirty = false;
        lastPersistMillis = clock.getAsLong();
        if (written.isDone()) {
            afterPersist();
        } else {
            written.whenComplete((persisted, error) -> SwingUtilities.invokeLater(this::afterPersist));
        }
    }

    private void afterPersist() {
        if (disposed) {
            return;
        }
        for (Listener listener : listeners) {
            listener.sessionsPersisted();
        }
        goals.check(settings::getSessions, clock.getAsLong());
    }

    private void notifyStateChanged() {
        for (Listener listener : listeners) {
            listener.workoutStateChanged();
        }
    }
}
