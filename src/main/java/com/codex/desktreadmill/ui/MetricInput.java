package com.codex.desktreadmill.ui;

import com.codex.desktreadmill.model.UnitSystem;

import java.math.BigDecimal;
import java.math.MathContext;

/** Retains an exact metric value behind a rounded, editable display. */
public final class MetricInput {
    public enum Quantity { SPEED, WEIGHT, HEIGHT, DISTANCE }

    private final Quantity quantity;
    private final int decimals;
    private double metricValue;
    private double displayedValue;
    private UnitSystem displayedUnits;

    public MetricInput(Quantity quantity, int decimals) {
        this.quantity = quantity;
        this.decimals = decimals;
    }

    public void clear() {
        displayedUnits = null;
    }

    public String display(double metricValue, UnitSystem units) {
        this.metricValue = metricValue;
        displayedUnits = units;
        double value = switch (quantity) {
            case SPEED -> units.speedFromKmh(metricValue);
            case WEIGHT -> units.weightFromKg(metricValue);
            case HEIGHT -> units.heightFromCm(metricValue);
            case DISTANCE -> units.distanceFromKm(metricValue);
        };
        String text = metricValue > 0 ? NumericInput.format(value, decimals) : "";
        if (value > 0 && NumericInput.parse(text) == 0) {
            // Fixed decimal places can turn a valid small goal into an invalid zero.
            text = BigDecimal.valueOf(value).round(new MathContext(2)).stripTrailingZeros().toPlainString();
        }
        displayedValue = NumericInput.parse(text);
        return text;
    }

    public double read(String text, UnitSystem units) {
        double value = NumericInput.parse(text);
        if (value <= 0) {
            return -1;
        }
        if (units == displayedUnits && value == displayedValue) {
            return metricValue;
        }
        return switch (quantity) {
            case SPEED -> units.speedToKmh(value);
            case WEIGHT -> units.weightToKg(value);
            case HEIGHT -> units.heightToCm(value);
            case DISTANCE -> units.distanceToKm(value);
        };
    }
}
