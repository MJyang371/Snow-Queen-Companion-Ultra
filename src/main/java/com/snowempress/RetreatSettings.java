package com.snowempress;

/** Backward speed in blocks per tick, maximum duration in ticks, and maximum travel in blocks. */
public record RetreatSettings(double flightSpeed, int durationTicks, double maxDistance) {
    public static final RetreatSettings DEFAULT = new RetreatSettings(0.34, 50, 12.0);

    public RetreatSettings {
        if (!Double.isFinite(flightSpeed) || flightSpeed <= 0 || durationTicks <= 0
                || !Double.isFinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException("Retreat settings must be positive and finite");
        }
    }
}
