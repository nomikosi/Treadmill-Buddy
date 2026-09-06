package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.DailyActivity;
import com.codex.desktreadmill.model.SessionData;
import com.codex.desktreadmill.model.SpeedSegment;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionJsonCodecTest {
    @Test
    void backupsPreserveProgressFractionsAndBreakdown() {
        SessionData session = new SessionData();
        session.id = "backup";
        session.name = "Line one\nLine two, with \"quotes\"";
        session.elapsedSeconds = 60;
        session.timerRemainderMillis = 750;
        session.stepRemainder = -0.123456789;
        session.intervalWalking = false;
        session.intervalWalkSeconds = 60;
        session.intervalBreakSeconds = 120;
        session.intervalPhaseSeconds = 12;
        session.segments.add(new SpeedSegment(3.6, 60));
        session.activityDays.add(new DailyActivity(1_800_000_000_000L, 60, 0.06, 90, 2.5));
        SessionData read = SessionJsonCodec.parseJson(SessionJsonCodec.buildJson(List.of(session))).getFirst();
        assertEquals(session.name, read.name);
        assertEquals(750, read.timerRemainderMillis);
        assertEquals(session.stepRemainder, read.stepRemainder);
        assertFalse(read.intervalWalking);
        assertEquals(12, read.intervalPhaseSeconds);
        assertEquals(120, read.intervalBreakSeconds);
        assertEquals(60, read.segments.getFirst().seconds);
        assertEquals(0.06, read.activityDays.getFirst().distanceKm);
    }

    @Test
    void foreignJsonStillFiltersUnidentifiedRowsAndRepairsNulls() {
        var read = SessionJsonCodec.parseJson("[null,{}, {\"id\":\"ok\",\"name\":null,\"segments\":null}]");
        assertEquals(1, read.size());
        assertEquals("", read.getFirst().name);
        assertTrue(read.getFirst().segments.isEmpty());
        assertTrue(SessionJsonCodec.parseJson("null").isEmpty());
        assertThrows(JsonSyntaxException.class, () -> SessionJsonCodec.parseJson("{broken"));
    }
}
