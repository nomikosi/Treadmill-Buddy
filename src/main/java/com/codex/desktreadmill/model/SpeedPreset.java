package com.codex.desktreadmill.model;

/** A named treadmill speed the user switches to often. Stored metric (km/h). */
public class SpeedPreset {
    public String name = "";
    public double speedKmh = 0.0;

    public SpeedPreset() {
    }

    public SpeedPreset(String name, double speedKmh) {
        this.name = name;
        this.speedKmh = speedKmh;
    }

    public SpeedPreset copy() {
        return new SpeedPreset(name, speedKmh);
    }
}
