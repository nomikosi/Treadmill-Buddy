package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.TreadmillBundle;
import com.codex.desktreadmill.TreadmillNotifications;
import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UserProfile;
import com.codex.desktreadmill.settings.TreadmillSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Import and export of session history: CSV both ways, JSON out, and TCX out
 * (per session) so a walk can be imported into Garmin Connect, Strava, and
 * similar fitness services.
 */
public final class SessionTransfer {
    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SessionTransfer() {
    }

    public static void exportCsv(@Nullable Project project, TreadmillSettings settings) {
        List<SessionData> sessions = settings.getSessions();
        if (notifyIfEmpty(project, sessions)) {
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.title"),
                TreadmillBundle.message("transfer.export.csv.description"), "csv", "treadmill-sessions.csv");
        if (wrapper == null) {
            return;
        }
        writeFile(project, wrapper, buildCsv(sessions), sessions.size());
    }

    /** Package-visible for round-trip tests. Always metric columns, locale-independent. */
    static String buildCsv(List<SessionData> sessions) {
        StringBuilder csv = new StringBuilder(
                "name,mode,algorithm,created,speed_kmh,incline_percent,elapsed_seconds,distance_km,steps,calories,"
                        + "target_calories,target_fat_kg,completed,interval_walk_seconds,interval_break_seconds,"
                        + "interval_walking,interval_phase_seconds,remaining_seconds,target_seconds,id\n");
        for (SessionData session : sessions) {
            csv.append(csvField(session.name)).append(',')
                    .append(SessionMode.fromId(session.modeId).getLabel()).append(',')
                    .append(CalorieAlgorithm.fromId(session.algorithmId).getLabel()).append(',')
                    .append(session.createdMillis > 0
                            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(session.createdMillis), ZoneId.systemDefault())
                            .format(CSV_DATE_FORMAT)
                            : "").append(',')
                    // Locale.ROOT throughout: a decimal-comma locale would emit
                    // "4,5", which is an extra CSV field and unparseable on import.
                    .append(String.format(Locale.ROOT, "%.1f", session.speedKmh)).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", session.inclinePercent)).append(',')
                    .append(session.elapsedSeconds).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", session.distanceKm)).append(',')
                    .append(session.steps).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", session.calories)).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", session.targetCalories)).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", session.targetFatKg)).append(',')
                    .append(session.completed).append(',')
                    .append(session.intervalWalkSeconds).append(',')
                    .append(session.intervalBreakSeconds).append(',')
                    .append(session.intervalWalking).append(',')
                    .append(session.intervalPhaseSeconds).append(',')
                    // Countdown state is exported so a re-imported open session
                    // doesn't need rebuilding from the importing machine's profile.
                    .append(session.remainingSeconds).append(',')
                    .append(session.targetSeconds).append(',')
                    // Exported last so older parsers ignore it. Re-importing
                    // uses it to recognise sessions you already have; the
                    // created column only has minute precision, so matching on
                    // that alone would duplicate the whole history.
                    .append(csvField(session.id))
                    .append('\n');
        }
        return csv.toString();
    }

    public static void exportJson(@Nullable Project project, TreadmillSettings settings) {
        List<SessionData> sessions = settings.getSessions();
        if (notifyIfEmpty(project, sessions)) {
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.title"),
                TreadmillBundle.message("transfer.export.json.description"), "json", "treadmill-sessions.json");
        if (wrapper == null) {
            return;
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(sessions);
        writeFile(project, wrapper, json, sessions.size());
    }

    public static void exportTcx(@Nullable Project project, @Nullable SessionData session) {
        if (session == null || session.elapsedSeconds == 0 || session.createdMillis <= 0) {
            Messages.showInfoMessage(project,
                    TreadmillBundle.message("transfer.export.tcx.noSelection"),
                    TreadmillBundle.message("transfer.export.tcx.dialogTitle"));
            return;
        }
        VirtualFileWrapper wrapper = pickSaveFile(project, TreadmillBundle.message("transfer.export.tcx.title"),
                TreadmillBundle.message("transfer.export.tcx.description"),
                "tcx", safeFileName(session.name) + ".tcx");
        if (wrapper == null) {
            return;
        }
        writeFile(project, wrapper, buildTcx(session), 1);
    }

    /**
     * The TCX document for one session. The sport is {@code Other}: the TCX v2
     * schema only allows Running, Biking, and Other, and a made-up value such
     * as "Walking" fails validation in strict importers. Services show it as a
     * generic workout that can be relabelled as a walk after import.
     */
    static String buildTcx(SessionData session) {
        Instant start = Instant.ofEpochMilli(session.createdMillis);
        // String.format with Locale.ROOT, not "...".formatted(...): the latter
        // uses the default locale, and %d renders Eastern Arabic digits under
        // ar/fa/bn, which no fitness service will parse as XML numbers.
        return String.format(Locale.ROOT, """
                <?xml version="1.0" encoding="UTF-8"?>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities>
                    <Activity Sport="Other">
                      <Id>%s</Id>
                      <Lap StartTime="%s">
                        <TotalTimeSeconds>%d</TotalTimeSeconds>
                        <DistanceMeters>%.1f</DistanceMeters>
                        <Calories>%d</Calories>
                        <Intensity>Active</Intensity>
                        <TriggerMethod>Manual</TriggerMethod>
                        <Track>
                %s        </Track>
                      </Lap>
                      <Notes>%s</Notes>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """,
                start, start,
                session.elapsedSeconds,
                session.distanceKm * 1000.0,
                Math.max(0, Math.round(session.calories)),
                buildTrackpoints(session, start),
                xmlEscape(session.name));
    }

    /**
     * One trackpoint per minute plus the final second. Some services (notably
     * Strava) reject TCX laps without a track. Distances follow the recorded
     * speed segments when present, scaled so the last point lands exactly on
     * the session total; sessions without segments interpolate linearly.
     */
    static String buildTrackpoints(SessionData session, Instant start) {
        StringBuilder track = new StringBuilder();
        long elapsed = session.elapsedSeconds;
        double totalMeters = session.distanceKm * 1000.0;
        double segmentTotalMeters = 0.0;
        long segmentTotalSeconds = 0L;
        for (SpeedSegment segment : session.segments) {
            segmentTotalMeters += segment.speedKmh / 3.6 * segment.seconds;
            segmentTotalSeconds += segment.seconds;
        }
        boolean useSegments = segmentTotalMeters > 0 && segmentTotalSeconds > 0;
        double scale = useSegments ? totalMeters / segmentTotalMeters : 1.0;
        for (long t = 0; ; t += 60) {
            boolean last = t >= elapsed;
            long at = last ? elapsed : t;
            double meters;
            if (last) {
                meters = totalMeters;
            } else if (useSegments) {
                meters = distanceFromSegments(session, at) * scale;
            } else {
                meters = elapsed > 0 ? totalMeters * at / elapsed : 0.0;
            }
            track.append(String.format(Locale.ROOT,
                    "          <Trackpoint><Time>%s</Time><DistanceMeters>%.1f</DistanceMeters></Trackpoint>%n",
                    start.plusSeconds(at), meters));
            if (last) {
                return track.toString();
            }
        }
    }

    private static double distanceFromSegments(SessionData session, long atSeconds) {
        double meters = 0.0;
        long consumed = 0L;
        for (SpeedSegment segment : session.segments) {
            long inSegment = Math.min(segment.seconds, atSeconds - consumed);
            if (inSegment <= 0) {
                break;
            }
            meters += segment.speedKmh / 3.6 * inSegment;
            consumed += inSegment;
        }
        return meters;
    }

    /** Returns the number of imported sessions, or -1 when the dialog was cancelled. */
    public static int importCsv(@Nullable Project project, TreadmillSettings settings) {
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("csv")
                        .withTitle(TreadmillBundle.message("transfer.import.title"))
                        .withDescription(TreadmillBundle.message("transfer.import.csv.description")),
                project, null);
        if (file == null) {
            return -1;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toNioPath());
        } catch (IOException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.import.csv.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("transfer.import.errorTitle"));
            return -1;
        }
        if (lines.isEmpty()) {
            return 0;
        }
        // "Save as CSV UTF-8" in Excel prefixes a BOM, which would otherwise
        // make the first column name unrecognisable and drop every name.
        List<String> header = parseCsvLine(stripBom(lines.get(0)));
        Set<String> existingIds = new HashSet<>();
        Set<String> existingKeys = new HashSet<>();
        for (SessionData session : settings.getSessions()) {
            existingIds.add(session.id);
            existingKeys.add(dedupeKey(session));
        }
        List<SessionData> toImport = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            SessionData session = parseCsvSession(header, parseCsvLine(lines.get(i)), i);
            if (session == null || existingIds.contains(session.id) || existingKeys.contains(dedupeKey(session))) {
                continue;
            }
            rehydrateAfterImport(session, settings.getProfile());
            existingIds.add(session.id);
            existingKeys.add(dedupeKey(session));
            toImport.add(session);
        }
        // One store write for the whole file: saving row by row rewrites the
        // JSON once per session, which freezes the EDT on a real backup.
        settings.saveSessions(toImport);
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.import.done", toImport.size()));
        return toImport.size();
    }

    /**
     * Imports sessions from a JSON export. Unlike CSV, this round-trips the
     * full model (speed segments, interval config), so it's the backup/restore
     * path. Returns the number of imported sessions, or -1 when cancelled.
     */
    public static int importJson(@Nullable Project project, TreadmillSettings settings) {
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle(TreadmillBundle.message("transfer.import.title"))
                        .withDescription(TreadmillBundle.message("transfer.import.json.description")),
                project, null);
        if (file == null) {
            return -1;
        }
        List<SessionData> imported;
        try {
            Type listType = new TypeToken<List<SessionData>>() {
            }.getType();
            imported = new Gson().fromJson(Files.readString(file.toNioPath()), listType);
        } catch (IOException | JsonSyntaxException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.import.json.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("transfer.import.errorTitle"));
            return -1;
        }
        if (imported == null) {
            imported = List.of();
        }
        Set<String> existingIds = new HashSet<>();
        Set<String> existingKeys = new HashSet<>();
        for (SessionData session : settings.getSessions()) {
            existingIds.add(session.id);
            existingKeys.add(dedupeKey(session));
        }
        List<SessionData> toImport = new ArrayList<>();
        for (SessionData session : imported) {
            if (session == null || session.id == null || session.id.isBlank()) {
                continue;
            }
            // A hand-edited file can carry explicit nulls; the store repairs
            // the same way when it reads its own file.
            session.sanitize();
            if (existingIds.contains(session.id) || existingKeys.contains(dedupeKey(session))) {
                continue;
            }
            existingIds.add(session.id);
            existingKeys.add(dedupeKey(session));
            toImport.add(session);
        }
        // One store write for the whole file, matching the CSV path.
        settings.saveSessions(toImport);
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.import.done", toImport.size()));
        return toImport.size();
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
    static void rehydrateAfterImport(SessionData session, UserProfile profile) {
        if (session.completed || session.remainingSeconds > 0) {
            return;
        }
        if (SessionMode.fromId(session.modeId).isCountdown()) {
            WorkoutMath.recalcRemaining(session, profile);
        }
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
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

    static @Nullable SessionData parseCsvSession(List<String> header, List<String> fields, int rowIndex) {
        SessionData session = new SessionData();
        session.id = System.currentTimeMillis() + "-import-" + rowIndex;
        try {
            for (int i = 0; i < header.size() && i < fields.size(); i++) {
                String value = fields.get(i).trim();
                switch (header.get(i).trim()) {
                    case "name" -> session.name = value;
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
                    case "id" -> session.id = value.isBlank() ? session.id : value;
                    default -> {
                    }
                }
            }
        } catch (RuntimeException malformedRow) {
            return null;
        }
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

    /** Minimal RFC-4180 field splitting: handles quoted fields and doubled quotes. */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static boolean notifyIfEmpty(@Nullable Project project, List<SessionData> sessions) {
        if (!sessions.isEmpty()) {
            return false;
        }
        TreadmillNotifications.info(project,
                TreadmillBundle.message("notification.title"),
                TreadmillBundle.message("notification.export.none"));
        return true;
    }

    private static @Nullable VirtualFileWrapper pickSaveFile(
            @Nullable Project project, String title, String description, String extension, String defaultName) {
        FileSaverDescriptor descriptor = createSaveDescriptor(title, description);
        descriptor.withExtensionFilter(extension);
        return FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((VirtualFile) null, defaultName);
    }

    private static void writeFile(@Nullable Project project, VirtualFileWrapper wrapper, String content, int count) {
        try {
            Files.writeString(wrapper.getFile().toPath(), content);
            TreadmillNotifications.info(project,
                    TreadmillBundle.message("notification.title"),
                    TreadmillBundle.message("notification.export.done", count, wrapper.getFile().getName()));
        } catch (IOException exception) {
            Messages.showErrorDialog(project,
                    TreadmillBundle.message("transfer.write.error", String.valueOf(exception.getMessage())),
                    TreadmillBundle.message("notification.title"));
        }
    }

    /**
     * 2024.3 ships only the varargs FileSaverDescriptor constructor, which 2025.1+
     * deprecates in favor of the two-arg one that 2024.3 lacks. A direct call to
     * either breaks one side (deprecation warning vs. NoSuchMethodError), so pick
     * the constructor reflectively at runtime.
     */
    private static FileSaverDescriptor createSaveDescriptor(String title, String description) {
        try {
            try {
                return FileSaverDescriptor.class
                        .getConstructor(String.class, String.class)
                        .newInstance(title, description);
            } catch (NoSuchMethodException onlyVarargsAvailable) {
                return FileSaverDescriptor.class
                        .getConstructor(String.class, String.class, String[].class)
                        .newInstance(title, description, new String[0]);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No usable FileSaverDescriptor constructor", exception);
        }
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeFileName(String name) {
        String safe = name.replaceAll("[^\\w\\-. ]", "_").trim();
        return safe.isBlank() ? "treadmill-session" : safe;
    }
}
