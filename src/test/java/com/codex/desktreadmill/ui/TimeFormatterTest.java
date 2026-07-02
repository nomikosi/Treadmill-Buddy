package com.codex.desktreadmill.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatterTest {
    @Test
    void zeroSeconds() {
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(0);
        assertEquals("", time.getDayPrefix());
        assertEquals("00:00:00", time.getTimeText());
    }

    @Test
    void hoursMinutesSeconds() {
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(3661);
        assertEquals("", time.getDayPrefix());
        assertEquals("01:01:01", time.getTimeText());
    }

    @Test
    void daysGetPrefix() {
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(90_061);
        assertEquals("1d", time.getDayPrefix());
        assertEquals("01:01:01", time.getTimeText());
    }

    @Test
    void negativeClampsToZero() {
        TimeFormatter.DisplayTime time = TimeFormatter.displayTime(-5);
        assertEquals("", time.getDayPrefix());
        assertEquals("00:00:00", time.getTimeText());
    }
}
