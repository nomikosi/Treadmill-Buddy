package com.codex.desktreadmill.model;

/**
 * Display unit system. All values are stored metric internally; conversion
 * happens only at the UI boundary.
 */
public enum UnitSystem {
    METRIC("Metric (km, kg, cm)"),
    IMPERIAL("Imperial (mi, lb, in)");

    private static final double KM_PER_MILE = 1.609344;
    private static final double KG_PER_POUND = 0.45359237;
    private static final double CM_PER_INCH = 2.54;

    private final String label;

    UnitSystem(String label) {
        this.label = label;
    }

    public double distanceFromKm(double km) {
        return this == METRIC ? km : km / KM_PER_MILE;
    }

    public double distanceToKm(double value) {
        return this == METRIC ? value : value * KM_PER_MILE;
    }

    public double speedFromKmh(double kmh) {
        return this == METRIC ? kmh : kmh / KM_PER_MILE;
    }

    public double speedToKmh(double value) {
        return this == METRIC ? value : value * KM_PER_MILE;
    }

    public double weightFromKg(double kg) {
        return this == METRIC ? kg : kg / KG_PER_POUND;
    }

    public double weightToKg(double value) {
        return this == METRIC ? value : value * KG_PER_POUND;
    }

    public double heightFromCm(double cm) {
        return this == METRIC ? cm : cm / CM_PER_INCH;
    }

    public double heightToCm(double value) {
        return this == METRIC ? value : value * CM_PER_INCH;
    }

    public String distanceUnit() {
        return this == METRIC ? "km" : "mi";
    }

    public String speedUnit() {
        return this == METRIC ? "km/h" : "mph";
    }

    public String weightUnit() {
        return this == METRIC ? "kg" : "lb";
    }

    public String heightUnit() {
        return this == METRIC ? "cm" : "in";
    }

    public static UnitSystem fromId(String id) {
        for (UnitSystem system : values()) {
            if (system.name().equals(id)) {
                return system;
            }
        }
        return METRIC;
    }

    @Override
    public String toString() {
        return label;
    }
}
