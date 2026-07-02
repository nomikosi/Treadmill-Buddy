package com.codex.desktreadmill.settings;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.GoalType;
import com.codex.desktreadmill.model.SessionData;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service(Service.Level.APP)
@State(name = "DeskTreadmillStopwatch", storages = @Storage("deskTreadmillStopwatch.xml"))
public final class TreadmillSettings implements PersistentStateComponent<TreadmillSettings.StateData> {
    private StateData state = new StateData();

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
        for (SessionData session : state.sessions) {
            if (session.createdMillis <= 0L) {
                session.createdMillis = parseLegacyCreatedMillis(session.id);
            }
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
        List<SessionData> copies = new ArrayList<>();
        for (SessionData session : state.sessions) {
            copies.add(session.copy());
        }
        return copies;
    }

    public void saveSession(SessionData session) {
        SessionData copy = session.copy();
        if (copy.id == null || copy.id.isBlank()) {
            copy.id = String.valueOf(System.currentTimeMillis());
        }
        for (int i = 0; i < state.sessions.size(); i++) {
            if (copy.id.equals(state.sessions.get(i).id)) {
                state.sessions.set(i, copy);
                state.lastSessionId = copy.id;
                return;
            }
        }
        state.sessions.add(copy);
        state.lastSessionId = copy.id;
    }

    public void deleteSession(String id) {
        Iterator<SessionData> iterator = state.sessions.iterator();
        while (iterator.hasNext()) {
            if (id.equals(iterator.next().id)) {
                iterator.remove();
            }
        }
        if (id.equals(state.lastSessionId)) {
            state.lastSessionId = "";
        }
    }

    public @Nullable SessionData findSession(String id) {
        for (SessionData session : state.sessions) {
            if (id.equals(session.id)) {
                return session.copy();
            }
        }
        return null;
    }

    public String getLastSessionId() {
        return state.lastSessionId;
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
        public List<SessionData> sessions = new ArrayList<>();
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
    }
}
