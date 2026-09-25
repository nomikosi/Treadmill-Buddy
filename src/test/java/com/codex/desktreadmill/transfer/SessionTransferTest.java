package com.codex.desktreadmill.transfer;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.engine.WorkoutMath;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedSegment;
import com.codex.desktreadmill.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionTransferTest {

    private static SessionData sampleSession() {
        SessionData session = new SessionData();
        session.id = "42";
        session.name = "Morning, with comma and \"quotes\"";
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.algorithmId = CalorieAlgorithm.ACSM_FLAT.name();
        session.speedKmh = 4.5;
        session.inclinePercent = 2.0;
        session.elapsedSeconds = 1_800L;
        session.distanceKm = 2.25;
        session.steps = 3_200L;
        session.calories = 150.4;
        session.targetCalories = 300.0;
        session.createdMillis = 1_752_988_800_000L;
        session.completed = true;
        return session;
    }

    private static SessionData roundTrip(SessionData original) {
        String csv = SessionCsvCodec.buildCsv(List.of(original));
        List<SessionData> parsed = SessionCsvCodec.parseCsv(csv);
        assertEquals(1, parsed.size());
        return parsed.getFirst();
    }

    @Test
    void csvRoundTripPreservesCoreFields() {
        SessionData original = sampleSession();
        SessionData parsed = roundTrip(original);
        assertEquals(original.name, parsed.name);
        assertEquals(original.modeId, parsed.modeId);
        assertEquals(original.algorithmId, parsed.algorithmId);
        assertEquals(original.speedKmh, parsed.speedKmh, 0.05);
        assertEquals(original.inclinePercent, parsed.inclinePercent, 0.05);
        assertEquals(original.elapsedSeconds, parsed.elapsedSeconds);
        assertEquals(original.distanceKm, parsed.distanceKm, 0.001);
        assertEquals(original.steps, parsed.steps);
        assertEquals(original.calories, parsed.calories, 0.05);
        assertEquals(original.targetCalories, parsed.targetCalories, 0.05);
        assertEquals(original.completed, parsed.completed);
        // The created column stores minute precision.
        assertEquals(original.createdMillis / 60_000L, parsed.createdMillis / 60_000L);
    }

    @Test
    void csvWritesStableIdsAndStillReadsTheLabelsOlderExportsCarry() {
        String csv = SessionCsvCodec.buildCsv(List.of(sampleSession()));
        assertTrue(csv.contains(",CALORIE_BURN,ACSM_FLAT,"), "display labels are not a file format: " + csv);

        String legacy = "name,mode,algorithm,elapsed_seconds\n"
                + "A,KG burn,Compendium MET active,60\n"
                + "B,interval walk,Distance cost per km,60\n"
                + "C,Marathon,ACSM treadmill (default),60\n";
        List<SessionData> parsed = SessionCsvCodec.parseCsv(legacy);
        assertEquals(SessionMode.FAT_BURN.name(), parsed.get(0).modeId);
        assertEquals(CalorieAlgorithm.COMPENDIUM_MET_ACTIVE.name(), parsed.get(0).algorithmId);
        assertEquals(SessionMode.INTERVAL.name(), parsed.get(1).modeId);
        assertEquals(CalorieAlgorithm.DISTANCE_COST.name(), parsed.get(1).algorithmId);
        assertEquals(CalorieAlgorithm.ACSM_FLAT.name(), parsed.get(2).algorithmId);
    }

    @Test
    void csvRoundTripPreservesActivityDatesMetricsAndPartialSeconds() {
        SessionData original = sampleSession();
        original.timerRemainderMillis = 750;
        original.stepRemainder = -0.123456789;
        original.activityDays.add(new DailyActivity(1_700_000_000_000L, 1000, 1.23456789, 1500, 90.123456));
        original.activityDays.add(new DailyActivity(1_700_086_400_000L, 800, 1.01543211, 1700, 60.276544));
        original.activityDays.getFirst().distanceKm += 0.000123456;
        original.distanceKm = original.activityDays.stream().mapToDouble(day -> day.distanceKm).sum();
        SessionData parsed = roundTrip(original);
        assertEquals(original.distanceKm, parsed.distanceKm);
        assertEquals(750, parsed.timerRemainderMillis);
        assertEquals(original.stepRemainder, parsed.stepRemainder);
        assertEquals(2, parsed.activityDays.size());
        for (int i = 0; i < 2; i++) {
            DailyActivity before = original.activityDays.get(i);
            DailyActivity after = parsed.activityDays.get(i);
            assertEquals(before.dateMillis, after.dateMillis);
            assertEquals(before.elapsedSeconds, after.elapsedSeconds);
            assertEquals(before.distanceKm, after.distanceKm);
            assertEquals(before.steps, after.steps);
            assertEquals(before.calories, after.calories);
        }
    }

    @Test
    void csvRoundTripPreservesIntervalBlockConfig() {
        SessionData original = sampleSession();
        original.modeId = SessionMode.INTERVAL.name();
        original.intervalWalkSeconds = 1_500L;
        original.intervalBreakSeconds = 300L;
        SessionData parsed = roundTrip(original);
        assertEquals(SessionMode.INTERVAL.name(), parsed.modeId);
        assertEquals(1_500L, parsed.intervalWalkSeconds);
        assertEquals(300L, parsed.intervalBreakSeconds);
    }

    @Test
    void csvRoundTripPreservesCountdownAndIntervalPhaseState() {
        SessionData original = sampleSession();
        original.modeId = SessionMode.INTERVAL.name();
        original.intervalWalkSeconds = 1_500L;
        original.intervalBreakSeconds = 300L;
        original.intervalWalking = false;
        original.intervalPhaseSeconds = 90L;
        original.remainingSeconds = 450L;
        original.targetSeconds = 2_250L;
        original.completed = false;

        SessionData parsed = roundTrip(original);
        assertFalse(parsed.intervalWalking, "mid-break state must survive the round trip");
        assertEquals(90L, parsed.intervalPhaseSeconds);
        assertEquals(450L, parsed.remainingSeconds);
        assertEquals(2_250L, parsed.targetSeconds);
    }

    @Test
    void rehydrateRebuildsCountdownMissingFromALegacyCsv() {
        SessionData session = sampleSession();
        session.modeId = SessionMode.CALORIE_BURN.name();
        session.completed = false;
        session.calories = 100.0;
        session.targetCalories = 300.0;
        // A CSV from before the countdown columns existed.
        session.remainingSeconds = 0L;

        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        SessionImport.rehydrateAfterImport(session, profile);
        assertTrue(session.remainingSeconds > 0, "the countdown should be rebuilt from the remaining calories");
    }

    @Test
    void rehydrateLeavesCompletedAndAlreadyPopulatedSessionsAlone() {
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;

        SessionData completed = sampleSession();
        completed.modeId = SessionMode.CALORIE_BURN.name();
        completed.completed = true;
        completed.remainingSeconds = 0L;
        SessionImport.rehydrateAfterImport(completed, profile);
        assertEquals(0L, completed.remainingSeconds);

        SessionData intact = sampleSession();
        intact.modeId = SessionMode.CALORIE_BURN.name();
        intact.completed = false;
        intact.remainingSeconds = 777L;
        SessionImport.rehydrateAfterImport(intact, profile);
        assertEquals(777L, intact.remainingSeconds, "an exported countdown must not be recomputed");
    }

    @Test
    void csvSurvivesADecimalCommaLocale() {
        // On a German IDE, default-locale formatting would write "4,5" for the
        // speed - an extra CSV field that shifts every later column.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            SessionData parsed = roundTrip(sampleSession());
            assertEquals(4.5, parsed.speedKmh, 0.05);
            assertEquals(2.25, parsed.distanceKm, 0.001);
            assertEquals(3_200L, parsed.steps);
            assertFalse(SessionCsvCodec.buildCsv(List.of(sampleSession())).contains("4,5"),
                    "numbers must be dot-decimal regardless of locale");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void tcxNumbersAreDotDecimalRegardlessOfLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            SessionData session = sampleSession();
            session.elapsedSeconds = 120L;
            session.distanceKm = 0.12;
            session.segments = new ArrayList<>();
            String track = SessionTcxCodec.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
            assertTrue(track.contains("<DistanceMeters>60.0</DistanceMeters>"),
                    "TCX must use XML dot-decimal numbers, got: " + track);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void reimportingOurOwnExportRecognisesTheSameSessions() {
        // The created column only stores minutes, so a session saved at
        // 12:00:37 comes back as 12:00:00. Dedupe has to survive that or
        // re-importing your own export duplicates the entire history.
        SessionData original = sampleSession();
        original.createdMillis += 37_000L;
        SessionData parsed = roundTrip(original);

        assertEquals(original.id, parsed.id, "the id column should round trip so dedupe is exact");
        assertEquals(original.createdMillis / 60_000L, parsed.createdMillis / 60_000L,
                "created must match to the minute so the fallback key also matches");
    }

    @Test
    void aLegacyCsvWithoutTheNewColumnsStillImports() {
        // Exactly the header the previous release wrote.
        String legacy = "name,mode,algorithm,created,speed_kmh,incline_percent,elapsed_seconds,distance_km,steps,"
                + "calories,target_calories,target_fat_kg,completed\n"
                + "Old walk,Calorie burn,ACSM treadmill (default),2026-08-10 12:00,4.5,0.0,1800,2.250,3200,"
                + "150.0,300.0,0.00,false\n";
        String[] lines = legacy.split("\n");
        SessionData parsed = SessionCsvCodec.parseCsvSession(
                SessionCsvCodec.parseCsvLine(lines[0]), SessionCsvCodec.parseCsvLine(lines[1]));

        assertNotNull(parsed);
        assertEquals("Old walk", parsed.name);
        assertEquals(1_800L, parsed.elapsedSeconds);
        assertEquals(0L, parsed.remainingSeconds, "a legacy row carries no countdown state");
        assertTrue(parsed.id.isBlank(), "a legacy row has no id of its own");
        List<SessionData> selected = SessionImport.selectNewSessions(new ArrayList<>(List.of(parsed)), List.of());
        assertEquals(1, selected.size());
        assertFalse(selected.get(0).id.isBlank(), "a legacy row gets an id once it is taken in");

        // ...and that is precisely the row rehydration is meant to repair.
        UserProfile profile = new UserProfile();
        profile.weightKg = 70.0;
        profile.heightCm = 170.0;
        SessionImport.rehydrateAfterImport(parsed, profile);
        assertTrue(parsed.remainingSeconds > 0);
    }

    @Test
    void aByteOrderMarkDoesNotEatTheFirstColumn() {
        String withBom = "﻿name,mode,elapsed_seconds\nMorning,Marathon,600\n";
        SessionData parsed = SessionCsvCodec.parseCsv(withBom).getFirst();
        assertNotNull(parsed);
        assertEquals("Morning", parsed.name, "the BOM must not hide the name column");
    }

    @Test
    void multilineNamesRoundTripWithQuotesCommasAndWhitespace() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            SessionData first = sampleSession();
            first.name = "  Morning," + newline + "with \"quotes\"  ";
            SessionData second = sampleSession();
            second.id = "second";
            second.name = "Next walk";
            List<SessionData> imported = SessionCsvCodec.parseCsv(SessionCsvCodec.buildCsv(List.of(first, second)));
            assertEquals(2, imported.size());
            assertEquals(first.name, imported.getFirst().name);
            assertEquals(first.elapsedSeconds, imported.getFirst().elapsedSeconds);
            assertEquals(first.distanceKm, imported.getFirst().distanceKm);
            assertEquals(second.id, imported.getLast().id);
        }
    }

    @Test
    void csvHandlesWindowsRecordEndingsBlankLinesAndMissingFinalNewline() {
        String csv = "name,elapsed_seconds\r\n\r\n\"First\r\nwalk\",600\r\n\r\nSecond,120";
        List<SessionData> imported = SessionCsvCodec.parseCsv(csv);
        assertEquals(2, imported.size());
        assertEquals("First\r\nwalk", imported.getFirst().name);
        assertEquals(600, imported.getFirst().elapsedSeconds);
        assertEquals(120, imported.getLast().elapsedSeconds);
        assertEquals(List.of("Walk", "600", ""), SessionCsvCodec.parseCsvLine("Walk,600,"));
        assertTrue(SessionCsvCodec.parseCsv("").isEmpty());
    }

    @Test
    void malformedCsvQuotesRejectTheFileBeforeAnyRowsCanBeImported() {
        for (String invalid : List.of("\"Unclosed,600", "Bare\"quote,600", "\"Closed\"oops,600")) {
            String csv = "name,elapsed_seconds\nValid,60\n" + invalid;
            assertThrows(IllegalArgumentException.class, () -> SessionCsvCodec.parseCsv(csv), invalid);
        }
    }

    @Test
    void csvImportResumeAndExportRetainsFractionalSteps() {
        SessionData original = sampleSession();
        original.completed = false;
        original.speedKmh = 0.5;
        original.stepRemainder = 0.4;
        SessionData imported = roundTrip(original);
        WorkoutMath.advanceOneSecond(original, new UserProfile());
        WorkoutMath.advanceOneSecond(imported, new UserProfile());
        assertEquals(original.steps, imported.steps);
        assertEquals(original.stepRemainder, imported.stepRemainder);
    }

    @Test
    void tcxAfterResumingCsvUsesTheWholeWorkoutDuration() {
        SessionData original = sampleSession();
        original.completed = false;
        original.modeId = SessionMode.MARATHON.name();
        original.elapsedSeconds = 600;
        original.distanceKm = 1;
        original.speedKmh = 3.6;
        original.segments.add(new SpeedSegment(6, 600));
        SessionData imported = roundTrip(original);
        assertTrue(imported.segments.isEmpty(), "CSV carries totals but no speed history");
        for (int i = 0; i < 60; i++) {
            WorkoutMath.advanceOneSecond(imported, new UserProfile());
        }
        List<Double> distances = trackDistances(imported);
        assertEquals(12, distances.size());
        assertEquals(96.4, distances.get(1), 0.05);
        assertEquals(1060, distances.getLast(), 0.05);
        for (int i = 1; i < distances.size(); i++) {
            assertTrue(distances.get(i) > distances.get(i - 1));
        }
    }

    @Test
    void completeSpeedHistoryStillShapesTcxDistance() {
        SessionData session = sampleSession();
        session.elapsedSeconds = 120;
        session.distanceKm = 0.18;
        session.segments = new ArrayList<>(List.of(new SpeedSegment(3.6, 60), new SpeedSegment(7.2, 60)));
        assertEquals(List.of(0.0, 60.0, 180.0), trackDistances(session));
    }

    private static List<Double> trackDistances(SessionData session) {
        String track = SessionTcxCodec.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
        Matcher matcher = Pattern.compile("<DistanceMeters>([0-9.]+)</DistanceMeters>").matcher(track);
        List<Double> distances = new ArrayList<>();
        while (matcher.find()) {
            distances.add(Double.parseDouble(matcher.group(1)));
        }
        return distances;
    }

    @Test
    void rowsWithDistinctIdsAreBothImportedEvenWithTheSameNameAndMinute() {
        // Two walks started in the same minute under the default name: the
        // minute+name fallback used to swallow the second one.
        SessionData first = sampleSession();
        first.id = "1";
        SessionData second = sampleSession();
        second.id = "2";
        List<SessionData> selected = SessionImport.selectNewSessions(
                new ArrayList<>(List.of(first, second)), List.of());
        assertEquals(2, selected.size());
    }

    @Test
    void aRowWhoseIdIsAlreadyInTheHistoryIsSkipped() {
        SessionData known = sampleSession();
        SessionData reimported = sampleSession();
        reimported.name = "renamed since the export";
        List<SessionData> selected = SessionImport.selectNewSessions(
                new ArrayList<>(List.of(reimported)), List.of(known));
        assertTrue(selected.isEmpty(), "same id means the same session, whatever the name says now");
    }

    @Test
    void legacyRowsWithoutIdsFallBackToMinuteAndName() {
        SessionData known = sampleSession();
        SessionData legacyDuplicate = sampleSession();
        legacyDuplicate.id = "";
        SessionData legacyNew = sampleSession();
        legacyNew.id = "";
        legacyNew.name = "A different walk";
        List<SessionData> selected = SessionImport.selectNewSessions(
                new ArrayList<>(List.of(legacyDuplicate, legacyNew)), List.of(known));
        assertEquals(1, selected.size());
        assertEquals("A different walk", selected.get(0).name);
        assertFalse(selected.get(0).id.isBlank());
    }

    @Test
    void nonFiniteCsvNumbersFallBackToDefaults() {
        List<String> header = SessionCsvCodec.parseCsvLine("name,speed_kmh,distance_km,elapsed_seconds");
        SessionData parsed = SessionCsvCodec.parseCsvSession(header,
                SessionCsvCodec.parseCsvLine("Walk,NaN,Infinity,600"));
        assertNotNull(parsed);
        // Double.parseDouble accepts both; neither may reach the store.
        assertEquals(3.0, parsed.speedKmh, 0.0);
        assertEquals(0.0, parsed.distanceKm, 0.0);
    }

    @Test
    void parseCsvLineHandlesQuotedFieldsAndDoubledQuotes() {
        List<String> fields = SessionCsvCodec.parseCsvLine("plain,\"with, comma\",\"with \"\"quotes\"\"\",end");
        assertEquals(List.of("plain", "with, comma", "with \"quotes\"", "end"), fields);
    }

    @Test
    void malformedRowIsSkippedNotThrown() {
        List<String> header = SessionCsvCodec.parseCsvLine("name,mode,algorithm,created,speed_kmh");
        List<String> fields = SessionCsvCodec.parseCsvLine("Broken,Marathon,ACSM treadmill (default),not-a-date,abc");
        assertNull(SessionCsvCodec.parseCsvSession(header, fields));
    }

    @Test
    void trackpointDistancesAreMonotonicAndEndAtSessionTotal() {
        SessionData session = sampleSession();
        session.elapsedSeconds = 250L;
        session.segments = new ArrayList<>(List.of(
                new SpeedSegment(3.0, 100L),
                new SpeedSegment(6.0, 150L)
        ));
        // Total from segments: 3/3.6*100 + 6/3.6*150 = 333.3 m; session says 340 m.
        session.distanceKm = 0.34;

        String track = SessionTcxCodec.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
        Matcher matcher = Pattern.compile("<DistanceMeters>([0-9.]+)</DistanceMeters>").matcher(track);
        List<Double> distances = new ArrayList<>();
        while (matcher.find()) {
            distances.add(Double.parseDouble(matcher.group(1)));
        }
        // 0s, 60s, 120s, 180s, 240s, plus the final 250s point.
        assertEquals(6, distances.size());
        for (int i = 1; i < distances.size(); i++) {
            assertTrue(distances.get(i) >= distances.get(i - 1),
                    "distances must never decrease: " + distances);
        }
        assertEquals(340.0, distances.get(distances.size() - 1), 0.1);
    }

    @Test
    void trackpointsWithoutSegmentsInterpolateLinearly() {
        SessionData session = sampleSession();
        session.elapsedSeconds = 120L;
        session.distanceKm = 0.12;
        session.segments = new ArrayList<>();

        String track = SessionTcxCodec.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
        Matcher matcher = Pattern.compile("<DistanceMeters>([0-9.]+)</DistanceMeters>").matcher(track);
        List<Double> distances = new ArrayList<>();
        while (matcher.find()) {
            distances.add(Double.parseDouble(matcher.group(1)));
        }
        assertEquals(List.of(0.0, 60.0, 120.0), distances);
    }

    @Test
    void tcxUsesASchemaValidSportAndEscapesTheName() {
        SessionData session = sampleSession();
        session.name = "Lunch <walk> & talk";
        String tcx = SessionTcxCodec.buildTcx(session);
        // The TCX v2 schema only allows Running, Biking, and Other; "Walking"
        // is rejected by strict importers.
        assertTrue(tcx.contains("<Activity Sport=\"Other\">"), tcx);
        assertTrue(tcx.contains("<Notes>Lunch &lt;walk&gt; &amp; talk</Notes>"), tcx);
        assertTrue(tcx.contains("<TotalTimeSeconds>1800</TotalTimeSeconds>"), tcx);
    }
}
