package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.calories.CalorieAlgorithm;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SessionMode;
import com.codex.desktreadmill.model.SpeedSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        String csv = SessionTransfer.buildCsv(List.of(original));
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        List<String> header = SessionTransfer.parseCsvLine(lines[0]);
        List<String> fields = SessionTransfer.parseCsvLine(lines[1]);
        return SessionTransfer.parseCsvSession(header, fields, 1);
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
    void parseCsvLineHandlesQuotedFieldsAndDoubledQuotes() {
        List<String> fields = SessionTransfer.parseCsvLine("plain,\"with, comma\",\"with \"\"quotes\"\"\",end");
        assertEquals(List.of("plain", "with, comma", "with \"quotes\"", "end"), fields);
    }

    @Test
    void malformedRowIsSkippedNotThrown() {
        List<String> header = SessionTransfer.parseCsvLine("name,mode,algorithm,created,speed_kmh");
        List<String> fields = SessionTransfer.parseCsvLine("Broken,Marathon,ACSM treadmill (default),not-a-date,abc");
        assertNull(SessionTransfer.parseCsvSession(header, fields, 1));
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

        String track = SessionTransfer.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
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

        String track = SessionTransfer.buildTrackpoints(session, Instant.ofEpochMilli(session.createdMillis));
        Matcher matcher = Pattern.compile("<DistanceMeters>([0-9.]+)</DistanceMeters>").matcher(track);
        List<Double> distances = new ArrayList<>();
        while (matcher.find()) {
            distances.add(Double.parseDouble(matcher.group(1)));
        }
        assertEquals(List.of(0.0, 60.0, 120.0), distances);
    }
}
