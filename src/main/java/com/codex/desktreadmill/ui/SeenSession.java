package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.SessionData;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * What one tool window panel last saw of the shared session. The engine is
 * application-wide, so a change made in another project window has to be
 * mirrored into this panel's fields - without clobbering an edit in progress
 * here. Comparing with the values last seen, rather than with the field
 * texts, is what tells a change from elsewhere apart from the user's typing.
 */
final class SeenSession {
    enum Field { SPEED, INCLINE, ALGORITHM, NAME, CALORIE_TARGET, FAT_TARGET, INTERVALS }

    private @Nullable String id;
    private double speedKmh;
    private double inclinePercent;
    private String algorithmId = "";
    private String name = "";
    private double targetCalories;
    private double targetFatKg;
    private long walkSeconds;
    private long breakSeconds;

    /** Remembers every field, as after the panel has shown all of them. */
    void remember(SessionData session) {
        id = session.id;
        speedKmh = session.speedKmh;
        inclinePercent = session.inclinePercent;
        algorithmId = session.algorithmId;
        name = session.name;
        targetCalories = session.targetCalories;
        targetFatKg = session.targetFatKg;
        walkSeconds = session.intervalWalkSeconds;
        breakSeconds = session.intervalBreakSeconds;
    }

    /** Forgets the session that left the clock; true when there was one. */
    boolean forget() {
        boolean hadSession = id != null;
        id = null;
        return hadSession;
    }

    /** Whether this is the session the panel shows, as opposed to one started or loaded elsewhere. */
    boolean isShowing(SessionData session) {
        return session.id.equals(id);
    }

    /** The fields whose engine value moved since the last look; the new values become the baseline. */
    Set<Field> update(SessionData session) {
        Set<Field> changed = EnumSet.noneOf(Field.class);
        if (session.speedKmh != speedKmh) {
            changed.add(Field.SPEED);
        }
        if (session.inclinePercent != inclinePercent) {
            changed.add(Field.INCLINE);
        }
        if (!session.algorithmId.equals(algorithmId)) {
            changed.add(Field.ALGORITHM);
        }
        if (!session.name.equals(name)) {
            changed.add(Field.NAME);
        }
        if (session.targetCalories != targetCalories) {
            changed.add(Field.CALORIE_TARGET);
        }
        if (session.targetFatKg != targetFatKg) {
            changed.add(Field.FAT_TARGET);
        }
        if (session.intervalWalkSeconds != walkSeconds || session.intervalBreakSeconds != breakSeconds) {
            changed.add(Field.INTERVALS);
        }
        remember(session);
        return changed;
    }
}
