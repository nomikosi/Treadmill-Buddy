package com.codex.desktreadmill.transfer;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** CSV conversion without file dialogs, notifications, or storage writes. */
public final class SessionCsvCodec {
    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * The English labels exports carried before they switched to ids. Fixed
     * here, as data: matching against the live, translatable labels would
     * break old files the moment a label changed.
     */
    private static final Map<String, SessionMode> LEGACY_MODE_LABELS = Map.of(
            "marathon", SessionMode.MARATHON,
            "calorie burn", SessionMode.CALORIE_BURN,
            "kg burn", SessionMode.FAT_BURN,
            "interval walk", SessionMode.INTERVAL);
    private static final Map<String, CalorieAlgorithm> LEGACY_ALGORITHM_LABELS = Map.of(
            "acsm treadmill", CalorieAlgorithm.ACSM_FLAT,
            "acsm treadmill (default)", CalorieAlgorithm.ACSM_FLAT,
            "compendium met gross", CalorieAlgorithm.COMPENDIUM_MET_GROSS,
            "compendium met active", CalorieAlgorithm.COMPENDIUM_MET_ACTIVE,
            "distance cost per km", CalorieAlgorithm.DISTANCE_COST);
    /** A number in scientific notation, as Excel saves a long id it displayed that way. */
    private static final Pattern SCIENTIFIC = Pattern.compile("\\d+(?:[.,]\\d+)?[eE][+-]?\\d+");
    /** The words spreadsheets write for true, in the locales most likely to re-save an export. */
    private static final Set<String> TRUE_WORDS = Set.of("true", "wahr", "vrai", "verdadero", "vero", "waar", "1");

    private SessionCsvCodec() {
    }

    /** Always metric columns and stable ids, locale-independent. */
    public static String buildCsv(List<SessionData> sessions) {
        StringBuilder csv = new StringBuilder(
                "name,mode,algorithm,created,speed_kmh,incline_percent,elapsed_seconds,distance_km,steps,calories,"
                        + "target_calories,target_fat_kg,completed,interval_walk_seconds,interval_break_seconds,"
                        + "interval_walking,interval_phase_seconds,remaining_seconds,target_seconds,id,"
                        + "timer_remainder_millis,activity_days,step_remainder\n");
        for (SessionData session : sessions) {
            // Mode and algorithm are written as their stable ids, not the
            // display labels: a relabelled or translated UI must not change
            // what an export says. Imports still read the old English labels.
            csv.append(csvField(session.name)).append(',')
                    .append(SessionMode.fromId(session.modeId).name()).append(',')
                    .append(CalorieAlgorithm.fromId(session.algorithmId).name()).append(',')
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
     * The text of a CSV file: UTF-8, with or without a byte order mark, and
     * otherwise Windows-1252 - what Excel on Windows writes for a plain "CSV"
     * save, which strict UTF-8 decoding rejects at the first umlaut.
     */
    public static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** What a CSV import found: the usable sessions, and how many rows it had to leave out. */
    public record CsvRead(List<SessionData> sessions, int skippedRows, @Nullable String firstProblem) {
    }

    /** A file whose header has none of the columns an export writes. */
    public static final class NotAnExportException extends IllegalArgumentException {
        private NotAnExportException() {
            super("no Treadmill Buddy columns in the header");
        }
    }

    /**
     * Parses complete records before accepting any sessions, including quoted
     * line breaks; a malformed quote rejects the whole file. Files re-saved
     * by a spreadsheet read too: the separator is whatever the header uses
     * (Excel writes semicolons in locales with a decimal comma), numbers may
     * carry a decimal comma, and dates and booleans may come back localized.
     * A row that still can't be read is counted rather than silently dropped.
     */
    public static CsvRead read(String csv) {
        String text = stripBom(csv);
        List<List<String>> records = parseCsvRecords(text, separatorOf(text));
        List<SessionData> sessions = new ArrayList<>();
        if (records.isEmpty()) {
            return new CsvRead(sessions, 0, null);
        }
        List<String> header = records.getFirst().stream().map(String::trim).toList();
        if (!header.contains("name") && !header.contains("elapsed_seconds")) {
            throw new NotAnExportException();
        }
        List<DateTimeFormatter> dateFormats = createdFormats();
        int skipped = 0;
        String firstProblem = null;
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            if (fields.stream().allMatch(String::isBlank)) {
                continue; // Spreadsheets pad files with empty rows.
            }
            try {
                SessionData session = parseRow(header, fields, dateFormats);
                if (session != null) {
                    sessions.add(session);
                }
            } catch (IllegalArgumentException unreadable) {
                skipped++;
                if (firstProblem == null) {
                    // Row numbers as a spreadsheet shows them: the header is row 1.
                    firstProblem = "row " + (i + 1) + ": " + unreadable.getMessage();
                }
            }
        }
        return new CsvRead(sessions, skipped, firstProblem);
    }

    /** One CSV row as a session, or null when it is unusable or describes nothing. */
    static @Nullable SessionData parseCsvSession(List<String> header, List<String> fields) {
        try {
            return parseRow(header, fields, createdFormats());
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * One CSV row as a session, or null when it describes nothing (no name,
     * no walked time). Throws, naming the column and value, when a field
     * can't be read. The id stays blank for rows from exports that predate
     * the id column; the import assigns one once the row is accepted, see
     * {@link SessionImport#selectNewSessions}.
     */
    private static @Nullable SessionData parseRow(List<String> header, List<String> fields,
                                                  List<DateTimeFormatter> dateFormats) {
        SessionData session = new SessionData();
        session.id = "";
        for (int i = 0; i < header.size() && i < fields.size(); i++) {
            String column = header.get(i).trim();
            String value = fields.get(i).trim();
            try {
                switch (column) {
                    case "name" -> session.name = fields.get(i);
                    case "mode" -> session.modeId = modeFromLabel(value).name();
                    case "algorithm" -> session.algorithmId = algorithmFromLabel(value).name();
                    case "created" -> session.createdMillis = value.isEmpty() ? 0L : parseCreated(value, dateFormats);
                    case "speed_kmh" -> session.speedKmh = decimal(value);
                    case "incline_percent" -> session.inclinePercent = decimal(value);
                    case "elapsed_seconds" -> session.elapsedSeconds = Long.parseLong(value);
                    case "distance_km" -> session.distanceKm = decimal(value);
                    case "steps" -> session.steps = Long.parseLong(value);
                    case "calories" -> session.calories = decimal(value);
                    case "target_calories" -> session.targetCalories = decimal(value);
                    case "target_fat_kg" -> session.targetFatKg = decimal(value);
                    case "completed" -> session.completed = bool(value);
                    case "interval_walk_seconds" -> session.intervalWalkSeconds = Long.parseLong(value);
                    case "interval_break_seconds" -> session.intervalBreakSeconds = Long.parseLong(value);
                    case "interval_walking" -> session.intervalWalking = bool(value);
                    case "interval_phase_seconds" -> session.intervalPhaseSeconds = Long.parseLong(value);
                    case "remaining_seconds" -> session.remainingSeconds = Long.parseLong(value);
                    case "target_seconds" -> session.targetSeconds = Long.parseLong(value);
                    // Excel shows a 13-digit id as 1,72725E+12 and saves what it shows.
                    // Rows sharing that mangled id would collapse into one on import,
                    // so treat it as missing: such rows match by minute and name.
                    case "id" -> session.id = SCIENTIFIC.matcher(value).matches() ? "" : value;
                    case "timer_remainder_millis" -> session.timerRemainderMillis = Long.parseLong(value);
                    case "step_remainder" -> session.stepRemainder = decimal(value);
                    case "activity_days" -> session.activityDays = SessionJsonCodec.readActivityDays(value);
                    default -> {
                    }
                }
            } catch (RuntimeException unreadable) {
                throw new IllegalArgumentException(column + " '" + value + "'", unreadable);
            }
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

    /** A decimal with a point, or - from a spreadsheet in a decimal-comma locale - a comma. */
    private static double decimal(String value) {
        return Double.parseDouble(value.indexOf('.') < 0 ? value.replace(',', '.') : value);
    }

    /** "true", or the word a localized spreadsheet writes for it: WAHR, VRAI, VERDADERO, VERO, WAAR. */
    private static boolean bool(String value) {
        return TRUE_WORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    /**
     * The export's own format first, then what spreadsheets turn it into:
     * day.month.year, and day/month or month/day by the system locale - the
     * same regional setting Excel on that machine follows.
     */
    private static List<DateTimeFormatter> createdFormats() {
        String pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, Locale.getDefault(Locale.Category.FORMAT));
        String slashed = pattern.indexOf('d') < pattern.indexOf('M') ? "d/M/yyyy" : "M/d/yyyy";
        return List.of(
                CSV_DATE_FORMAT,
                DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T']HH:mm[:ss]"),
                DateTimeFormatter.ofPattern("d.M.yyyy H:mm[:ss]"),
                DateTimeFormatter.ofPattern(slashed + " H:mm[:ss]"),
                new DateTimeFormatterBuilder().parseCaseInsensitive()
                        .appendPattern(slashed + " h:mm[:ss] a").toFormatter(Locale.US));
    }

    private static long parseCreated(String value, List<DateTimeFormatter> formats) {
        for (DateTimeFormatter format : formats) {
            try {
                return LocalDateTime.parse(value, format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException tryTheNext) {
                // Fall through to the next format.
            }
        }
        throw new IllegalArgumentException("not a date and time");
    }

    private static SessionMode modeFromLabel(String label) {
        for (SessionMode mode : SessionMode.values()) {
            if (mode.name().equalsIgnoreCase(label)) {
                return mode;
            }
        }
        return LEGACY_MODE_LABELS.getOrDefault(label.toLowerCase(Locale.ROOT), SessionMode.MARATHON);
    }

    private static CalorieAlgorithm algorithmFromLabel(String label) {
        for (CalorieAlgorithm algorithm : CalorieAlgorithm.values()) {
            if (algorithm.name().equalsIgnoreCase(label)) {
                return algorithm;
            }
        }
        return LEGACY_ALGORITHM_LABELS.getOrDefault(label.toLowerCase(Locale.ROOT), CalorieAlgorithm.ACSM_FLAT);
    }

    /** The sessions {@link #read} accepts. */
    public static List<SessionData> parseCsv(String csv) {
        return read(csv).sessions();
    }

    /** Single-record convenience for callers that already have one complete CSV row. */
    static List<String> parseCsvLine(String line) {
        List<List<String>> records = parseCsvRecords(line, ',');
        if (records.size() > 1) {
            throw new IllegalArgumentException("Expected one CSV record");
        }
        return records.isEmpty() ? List.of("") : records.getFirst();
    }

    /** The separator the header line uses most outside quotes: comma, semicolon, or tab. */
    private static char separatorOf(String csv) {
        int commas = 0;
        int semicolons = 0;
        int tabs = 0;
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted && c == ',') {
                commas++;
            } else if (!quoted && c == ';') {
                semicolons++;
            } else if (!quoted && c == '\t') {
                tabs++;
            }
        }
        if (semicolons > commas && semicolons >= tabs) {
            return ';';
        }
        return tabs > commas ? '\t' : ',';
    }

    private static List<List<String>> parseCsvRecords(String csv, char separator) {
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
            } else if (c == separator) {
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
