package com.codex.desktreadmill.engine;

import com.codex.desktreadmill.model.SessionData;

import java.util.List;
import java.util.Objects;

/** Age-based cleanup follows the latest walked day and excludes the session on the clock. */
public final class HistoryCleanup {
    private HistoryCleanup() {
    }

    public static List<String> candidates(List<SessionData> sessions, String currentSessionId, long cutoffMillis) {
        return sessions.stream()
                .filter(session -> !Objects.equals(session.id, currentSessionId))
                .filter(session -> {
                    long latest = session.latestActivityMillis();
                    return latest > 0 && latest < cutoffMillis;
                })
                .map(session -> session.id).toList();
    }
}
