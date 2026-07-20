package com.codex.desktreadmill.model;

/**
 * One stretch of a session walked at a constant speed. Segments are appended
 * as the session ticks, so a session's segment list is an auditable breakdown
 * of how its distance and calories were accumulated.
 */
public class SpeedSegment {
    public double speedKmh;
    public long seconds;

    public SpeedSegment() {
    }

    public SpeedSegment(double speedKmh, long seconds) {
        this.speedKmh = speedKmh;
        this.seconds = seconds;
    }

    public SpeedSegment copy() {
        return new SpeedSegment(speedKmh, seconds);
    }
}
