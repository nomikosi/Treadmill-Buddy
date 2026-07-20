package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedPreset;
import com.codex.desktreadmill.model.UnitSystem;
import com.codex.desktreadmill.model.UserProfile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service(Service.Level.APP)
@State(name = "DeskTreadmillStopwatch", storages = @Storage("deskTreadmillStopwatch.xml"))
public final class TreadmillSettings implements PersistentStateComponent<TreadmillSettings.StateData> {
    private StateData state = new StateData();
    private final SessionStore sessionStore;

    public TreadmillSettings() {
        this(Paths.get(System.getProperty("user.home"), ".treadmill-buddy", "sessions.json"));
    }

    /** Test constructor: keeps session history out of the real user home. */
    public TreadmillSettings(Path sessionsFile) {
        sessionStore = new SessionStore(sessionsFile);
    }

    public static TreadmillSettings getInstance() {
        return ApplicationManager.getApplication().getService(TreadmillSettings.class);
    }

    @Override
    public @Nullable StateData getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateData loadedState) {
        XmlSerializerUtil.copyBean(loadedState, state);
        if (state.profile == null) {
            state.profile = new UserProfile();
        }
        if (state.sessions == null) {
            state.sessions = new ArrayList<>();
        }
        if (state.speedPresets == null) {
            state.speedPresets = new ArrayList<>();
        }
        for (SessionData session : state.sessions) {
            if (session.createdMillis <= 0L) {
                session.createdMillis = parseLegacyCreatedMillis(session.id);
            }
        }
        // Sessions used to live in this per-IDE XML file; move them to the
        // shared home-directory store so history survives IDE reinstalls
        // and is visible from every JetBrains IDE.
        if (!state.sessions.isEmpty()) {
            sessionStore.migrate(state.sessions);
            state.sessions.clear();
        }
        if (state.selectedAlgorithmId == null || state.selectedAlgorithmId.isBlank()) {
            state.selectedAlgorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        }
        if (state.autoPauseMinutes < 0) {
            state.autoPauseMinutes = 10;
        }
    }

    private static long parseLegacyCreatedMillis(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public UserProfile getProfile() {
        return state.profile;
    }

    public void setProfile(UserProfile profile) {
        state.profile = profile.copy();
    }

    public CalorieAlgorithm getSelectedAlgorithm() {
        return CalorieAlgorithm.fromId(state.selectedAlgorithmId);
    }

    public void setSelectedAlgorithm(CalorieAlgorithm algorithm) {
        state.selectedAlgorithmId = algorithm.name();
    }

    public List<SessionData> getSessions() {
        return sessionStore.getSessions();
    }

    public void saveSession(SessionData session) {
        SessionData copy = session.copy();
        if (copy.id == null || copy.id.isBlank()) {
            copy.id = String.valueOf(System.currentTimeMillis());
        }
        sessionStore.saveSession(copy);
        state.lastSessionId = copy.id;
    }

    public void deleteSession(String id) {
        sessionStore.deleteSession(id);
        if (id.equals(state.lastSessionId)) {
            state.lastSessionId = "";
        }
    }

    public void deleteSessions(List<String> ids) {
        sessionStore.deleteSessions(ids);
        if (ids.contains(state.lastSessionId)) {
            state.lastSessionId = "";
        }
    }

    public @Nullable SessionData findSession(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return sessionStore.findSession(id);
    }

    public String getLastSessionId() {
        return state.lastSessionId;
    }

    public List<SpeedPreset> getSpeedPresets() {
        List<SpeedPreset> copies = new ArrayList<>();
        for (SpeedPreset preset : state.speedPresets) {
            copies.add(preset.copy());
        }
        copies.sort(Comparator.comparingDouble(preset -> preset.speedKmh));
        return copies;
    }

    public void addSpeedPreset(SpeedPreset preset) {
        removeSpeedPreset(preset.name);
        state.speedPresets.add(preset.copy());
    }

    public void removeSpeedPreset(String name) {
        state.speedPresets.removeIf(preset -> preset.name.equals(name));
    }

    public int getAutoPauseMinutes() {
        return state.autoPauseMinutes;
    }

    public void setAutoPauseMinutes(int autoPauseMinutes) {
        state.autoPauseMinutes = Math.max(0, autoPauseMinutes);
    }

    public int getMoveReminderMinutes() {
        return state.moveReminderMinutes;
    }

    public void setMoveReminderMinutes(int moveReminderMinutes) {
        state.moveReminderMinutes = Math.max(0, moveReminderMinutes);
    }

    public UnitSystem getUnitSystem() {
        return UnitSystem.fromId(state.unitSystemId);
    }

    public void setUnitSystem(UnitSystem unitSystem) {
        state.unitSystemId = unitSystem.name();
    }

    public GoalType getDailyGoalType() {
        return GoalType.fromId(state.dailyGoalTypeId);
    }

    public void setDailyGoalType(GoalType goalType) {
        state.dailyGoalTypeId = goalType.name();
    }

    /** Goal value in metric terms: steps, km, or kcal depending on the goal type. */
    public double getDailyGoalValue() {
        return state.dailyGoalValue;
    }

    public void setDailyGoalValue(double dailyGoalValue) {
        state.dailyGoalValue = Math.max(0.0, dailyGoalValue);
    }

    public long getLastGoalAchievedDay() {
        return state.lastGoalAchievedDay;
    }

    public void setLastGoalAchievedDay(long epochDay) {
        state.lastGoalAchievedDay = epochDay;
    }

    public GoalType getWeeklyGoalType() {
        return GoalType.fromId(state.weeklyGoalTypeId);
    }

    public void setWeeklyGoalType(GoalType goalType) {
        state.weeklyGoalTypeId = goalType.name();
    }

    /** Weekly goal value in metric terms: steps, km, or kcal depending on the goal type. */
    public double getWeeklyGoalValue() {
        return state.weeklyGoalValue;
    }

    public void setWeeklyGoalValue(double weeklyGoalValue) {
        state.weeklyGoalValue = Math.max(0.0, weeklyGoalValue);
    }

    /** Epoch day of the Monday of the last week whose weekly goal was celebrated. */
    public long getLastWeeklyGoalAchievedWeek() {
        return state.lastWeeklyGoalAchievedWeek;
    }

    public void setLastWeeklyGoalAchievedWeek(long epochDayOfWeekStart) {
        state.lastWeeklyGoalAchievedWeek = epochDayOfWeekStart;
    }

    /** Rest days per calendar week that don't break the walking streak (0-2). */
    public int getStreakRestDaysPerWeek() {
        return state.streakRestDaysPerWeek;
    }

    public void setStreakRestDaysPerWeek(int restDays) {
        state.streakRestDaysPerWeek = Math.max(0, Math.min(2, restDays));
    }

    /** Hour of day (1-23) from which a walk-free day shows "streak at risk"; 0 disables the hint. */
    public int getStreakRiskHour() {
        return state.streakRiskHour;
    }

    public void setStreakRiskHour(int hour) {
        state.streakRiskHour = Math.max(0, Math.min(23, hour));
    }

    public long getBestSessionSeconds() {
        return state.bestSessionSeconds;
    }

    public void setBestSessionSeconds(long seconds) {
        state.bestSessionSeconds = seconds;
    }

    public double getBestDayDistanceKm() {
        return state.bestDayDistanceKm;
    }

    public void setBestDayDistanceKm(double km) {
        state.bestDayDistanceKm = km;
    }

    public long getBestDaySteps() {
        return state.bestDaySteps;
    }

    public void setBestDaySteps(long steps) {
        state.bestDaySteps = steps;
    }

    /** True once stored record baselines were seeded from the full history. */
    public boolean isRecordsSeeded() {
        return state.recordsSeeded;
    }

    public void setRecordsSeeded(boolean seeded) {
        state.recordsSeeded = seeded;
    }

    /** Picks up sessions another IDE instance wrote since our last read. */
    public void reloadSessions() {
        sessionStore.reload();
    }

    public boolean hasFloatingClockLocation() {
        return state.floatingClockX != Integer.MIN_VALUE && state.floatingClockY != Integer.MIN_VALUE;
    }

    public int getFloatingClockX() {
        return state.floatingClockX;
    }

    public int getFloatingClockY() {
        return state.floatingClockY;
    }

    public void setFloatingClockLocation(int x, int y) {
        state.floatingClockX = x;
        state.floatingClockY = y;
    }

    public boolean isFloatingClockPinned() {
        return state.floatingClockPinned;
    }

    public void setFloatingClockPinned(boolean pinned) {
        state.floatingClockPinned = pinned;
    }

    public static class StateData {
        public UserProfile profile = new UserProfile();
        public String selectedAlgorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        /** Legacy storage; sessions now live in the shared home-directory store. */
        public List<SessionData> sessions = new ArrayList<>();
        public List<SpeedPreset> speedPresets = new ArrayList<>();
        public String lastSessionId = "";
        public int autoPauseMinutes = 10;
        public int moveReminderMinutes = 0;
        public int floatingClockX = Integer.MIN_VALUE;
        public int floatingClockY = Integer.MIN_VALUE;
        public boolean floatingClockPinned = false;
        public String unitSystemId = UnitSystem.METRIC.name();
        public String dailyGoalTypeId = GoalType.NONE.name();
        public double dailyGoalValue = 0.0;
        public long lastGoalAchievedDay = 0L;
        public String weeklyGoalTypeId = GoalType.NONE.name();
        public double weeklyGoalValue = 0.0;
        public long lastWeeklyGoalAchievedWeek = 0L;
        public int streakRestDaysPerWeek = 0;
        public int streakRiskHour = 17;
        public long bestSessionSeconds = 0L;
        public double bestDayDistanceKm = 0.0;
        public long bestDaySteps = 0L;
        public boolean recordsSeeded = false;
    }
}
