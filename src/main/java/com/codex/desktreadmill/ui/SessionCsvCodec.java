package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** CSV conversion without file dialogs, notifications, or storage writes. */
final class SessionCsvCodec {
    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SessionCsvCodec() {
    }

    /** Package-visible for round-trip tests. Always metric columns, locale-independent. */
    static String buildCsv(List<SessionData> sessions) {
        StringBuilder csv = new StringBuilder(
                "name,mode,algorithm,created,speed_kmh,incline_percent,elapsed_seconds,distance_km,steps,calories,"
                        + "target_calories,target_fat_kg,completed,interval_walk_seconds,interval_break_seconds,"
                        + "interval_walking,interval_phase_seconds,remaining_seconds,target_seconds,id,"
                        + "timer_remainder_millis,activity_days,step_remainder\n");
        for (SessionData session : sessions) {
            csv.append(csvField(session.name)).append(',')
                    .append(SessionMode.fromId(session.modeId).getLabel()).append(',')
                    .append(CalorieAlgorithm.fromId(session.algorithmId).getLabel()).append(',')
                    .append(session.createdMillis > 0
                            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(session.createdMillis), ZoneId.systemDefault())
                            .format(CSV_DATE_FORMAT)
                            : "").append(',')
                    // Double.toString is locale-independent and lossless, so
                    // aggregate totals still agree with the dated breakdown.
                    .append(Double.toString(session.speedKmh)).append(',')
                    .append(Double.toString(session.inclinePercent)).append(',')
                    .append(session.elapsedSeconds).append(',')
                    .append(Double.toString(session.distanceKm)).append(',')
                    .append(session.steps).append(',')
                    .append(Double.toString(session.calories)).append(',')
                    .append(Double.toString(session.targetCalories)).append(',')
                    .append(Double.toString(session.targetFatKg)).append(',')
                    .append(session.completed).append(',')
                    .append(session.intervalWalkSeconds).append(',')
                    .append(session.intervalBreakSeconds).append(',')
                    .append(session.intervalWalking).append(',')
                    .append(session.intervalPhaseSeconds).append(',')
                    // Countdown state is exported so a re-imported open session
                    // doesn't need rebuilding from the importing machine's profile.
                    .append(session.remainingSeconds).append(',')
                    .append(session.targetSeconds).append(',')
                    // Appended columns let older parsers ignore them. Re-importing
                    // uses it to recognise sessions you already have; the
                    // created column only has minute precision, so matching on
                    // that alone would duplicate the whole history.
                    .append(csvField(session.id)).append(',')
                    .append(session.timerRemainderMillis).append(',')
                    .append(csvField(SessionJsonCodec.writeActivityDays(session.activityDays))).append(',')
                    .append(Double.toString(session.stepRemainder))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }

    /**
     * One CSV row as a session, or null when the row is unusable. The id stays
     * blank for rows from exports that predate the id column; the import
     * assigns one once the row is accepted, see {@link SessionTransfer#selectNewSessions}.
     */
    static @Nullable SessionData parseCsvSession(List<String> header, List<String> fields) {
        SessionData session = new SessionData();
        session.id = "";
        try {
            for (int i = 0; i < header.size() && i < fields.size(); i++) {
                String value = fields.get(i).trim();
                switch (header.get(i).trim()) {
                    case "name" -> session.name = fields.get(i);
                    case "mode" -> session.modeId = modeFromLabel(value).name();
                    case "algorithm" -> session.algorithmId = algorithmFromLabel(value).name();
                    case "created" -> session.createdMillis = value.isEmpty() ? 0L
                            : LocalDateTime.parse(value, CSV_DATE_FORMAT)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    case "speed_kmh" -> session.speedKmh = Double.parseDouble(value);
                    case "incline_percent" -> session.inclinePercent = Double.parseDouble(value);
                    case "elapsed_seconds" -> session.elapsedSeconds = Long.parseLong(value);
                    case "distance_km" -> session.distanceKm = Double.parseDouble(value);
                    case "steps" -> session.steps = Long.parseLong(value);
                    case "calories" -> session.calories = Double.parseDouble(value);
                    case "target_calories" -> session.targetCalories = Double.parseDouble(value);
                    case "target_fat_kg" -> session.targetFatKg = Double.parseDouble(value);
                    case "completed" -> session.completed = Boolean.parseBoolean(value);
                    case "interval_walk_seconds" -> session.intervalWalkSeconds = Long.parseLong(value);
                    case "interval_break_seconds" -> session.intervalBreakSeconds = Long.parseLong(value);
                    case "interval_walking" -> session.intervalWalking = Boolean.parseBoolean(value);
                    case "interval_phase_seconds" -> session.intervalPhaseSeconds = Long.parseLong(value);
                    case "remaining_seconds" -> session.remainingSeconds = Long.parseLong(value);
                    case "target_seconds" -> session.targetSeconds = Long.parseLong(value);
                    case "id" -> session.id = value;
                    case "timer_remainder_millis" -> session.timerRemainderMillis = Long.parseLong(value);
                    case "step_remainder" -> session.stepRemainder = Double.parseDouble(value);
                    case "activity_days" -> session.activityDays = SessionJsonCodec.readActivityDays(value);
                    default -> {
                    }
                }
            }
        } catch (RuntimeException malformedRow) {
            return null;
        }
        // "NaN" and "Infinity" parse as numbers; they must not reach the store.
        session.sanitize();
        if (session.name.isBlank() && session.elapsedSeconds == 0) {
            return null;
        }
        if (session.createdMillis <= 0) {
            // Old exports may lack the created column; without a date the
            // session can't appear in daily stats, so anchor it to import time.
            session.createdMillis = System.currentTimeMillis();
        }
        return session;
    }

    private static SessionMode modeFromLabel(String label) {
        for (SessionMode mode : SessionMode.values()) {
            if (mode.getLabel().equalsIgnoreCase(label) || mode.name().equalsIgnoreCase(label)) {
                return mode;
            }
        }
        return SessionMode.MARATHON;
    }

    private static CalorieAlgorithm algorithmFromLabel(String label) {
        for (CalorieAlgorithm algorithm : CalorieAlgorithm.values()) {
            if (algorithm.getLabel().equalsIgnoreCase(label) || algorithm.name().equalsIgnoreCase(label)) {
                return algorithm;
            }
        }
        return CalorieAlgorithm.ACSM_FLAT;
    }

    /** Parses complete records before accepting any sessions, including quoted line breaks. */
    static List<SessionData> parseCsv(String csv) {
        List<List<String>> records = parseCsvRecords(stripBom(csv));
        List<SessionData> sessions = new ArrayList<>();
        if (records.isEmpty()) {
            return sessions;
        }
        List<String> header = records.getFirst();
        for (int i = 1; i < records.size(); i++) {
            SessionData session = parseCsvSession(header, records.get(i));
            if (session != null) {
                sessions.add(session);
            }
        }
        return sessions;
    }

    /** Single-record convenience for callers that already have one complete CSV row. */
    static List<String> parseCsvLine(String line) {
        List<List<String>> records = parseCsvRecords(line);
        if (records.size() > 1) {
            throw new IllegalArgumentException("Expected one CSV record");
        }
        return records.isEmpty() ? List.of("") : records.getFirst();
    }

    private static List<List<String>> parseCsvRecords(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                if (!current.isEmpty() || closedQuote) {
                    throw new IllegalArgumentException("Unexpected quote in CSV field");
                }
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
                closedQuote = false;
            } else if (c == '\r' || c == '\n') {
                if (!fields.isEmpty() || !current.isEmpty() || closedQuote) {
                    fields.add(current.toString());
                    records.add(fields);
                    fields = new ArrayList<>();
                }
                current.setLength(0);
                closedQuote = false;
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                if (closedQuote) {
                    throw new IllegalArgumentException("Unexpected character after quoted CSV field");
                }
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quoted CSV field");
        }
        if (!fields.isEmpty() || !current.isEmpty() || closedQuote) {
            fields.add(current.toString());
            records.add(fields);
        }
        return records;
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

}
