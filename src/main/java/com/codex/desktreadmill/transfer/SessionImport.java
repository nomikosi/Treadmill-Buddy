package com.codex.desktreadmill.transfer;

import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.UserProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deciding which parsed sessions an import takes in, without dialogs or storage writes. */
public final class SessionImport {
    private SessionImport() {
    }

    /**
     * The candidates the history doesn't have yet. A row that carries an id
     * is matched on that id alone: two walks started in the same minute under
     * the default name are distinct sessions, and matching them on minute and
     * name used to drop the second one. Rows from exports written before the
     * id column existed have no id; minute and name are the only identity
     * such a row has, so they fall back to that and get an id here.
     */
    public static List<SessionData> selectNewSessions(List<SessionData> candidates, List<SessionData> existing) {
        Set<String> knownIds = new HashSet<>();
        Set<String> knownKeys = new HashSet<>();
        for (SessionData session : existing) {
            knownIds.add(session.id);
            knownKeys.add(dedupeKey(session));
        }
        List<SessionData> selected = new ArrayList<>();
        int generated = 0;
        for (SessionData candidate : candidates) {
            boolean legacy = candidate.id == null || candidate.id.isBlank();
            if (legacy) {
                if (!knownKeys.add(dedupeKey(candidate))) {
                    continue;
                }
                candidate.id = System.currentTimeMillis() + "-import-" + (++generated);
            } else {
                if (!knownIds.add(candidate.id)) {
                    continue;
                }
                knownKeys.add(dedupeKey(candidate));
            }
            selected.add(candidate);
        }
        return selected;
    }

    /**
     * Rebuilds derived state a CSV written by an older version doesn't carry.
     * Current exports include the countdown columns, so this only fires for
     * legacy files; without it an open Calorie/KG-burn row loads with a dead
     * 00:00:00 clock. It can only use the importing machine's profile, which
     * is an approximation - and when even that yields no burn rate (profile
     * never filled in) the clock falls back to counting up, see
     * {@link WorkoutMath#displaySeconds}.
     */
    public static void rehydrateAfterImport(SessionData session, UserProfile profile) {
        if (session.completed || session.remainingSeconds > 0) {
            return;
        }
        if (SessionMode.fromId(session.modeId).isCountdown()) {
            WorkoutMath.recalcRemaining(session, profile);
        }
    }

    /**
     * Fallback identity for rows from a CSV written before the id column
     * existed. Truncated to whole minutes because that is all the created
     * column stores - comparing raw millis would never match the session the
     * row came from, and re-importing your own export would duplicate
     * everything.
     */
    private static String dedupeKey(SessionData session) {
        return (session.createdMillis / 60_000L) + "|" + session.name;
    }
}
