package com.codex.desktreadmill.transfer;

import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Shared JSON serializers for full backups and the CSV activity column. */
public final class SessionJsonCodec {
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SESSIONS = new TypeToken<List<SessionData>>() { }.getType();
    private static final Type ACTIVITY_DAYS = new TypeToken<List<DailyActivity>>() { }.getType();

    private SessionJsonCodec() {
    }

    public static String buildJson(List<SessionData> sessions) {
        return PRETTY_GSON.toJson(sessions);
    }

    public static List<SessionData> parseJson(String json) {
        List<SessionData> parsed = GSON.fromJson(json, SESSIONS);
        List<SessionData> sessions = new ArrayList<>();
        if (parsed != null) {
            for (SessionData session : parsed) {
                if (session != null && session.id != null && !session.id.isBlank()) {
                    sessions.add(session.sanitize());
                }
            }
        }
        return sessions;
    }

    static String writeActivityDays(List<DailyActivity> days) {
        return GSON.toJson(days);
    }

    static List<DailyActivity> readActivityDays(String json) {
        return GSON.fromJson(json, ACTIVITY_DAYS);
    }
}
