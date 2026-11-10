package com.snowempress;

/** Flight speed in blocks per tick, firing duration in ticks, and height above the aim point. */
public record GunshipSettings(double flightSpeed, int durationTicks, double ascentHeight) {
    public static final GunshipSettings DEFAULT = new GunshipSettings(0.58, 120, 6.0);

    public GunshipSettings {
        if (!Double.isFinite(flightSpeed) || flightSpeed <= 0 || durationTicks <= 0
                || !Double.isFinite(ascentHeight) || ascentHeight <= 0) {
            throw new IllegalArgumentException("Gunship flight settings must be positive and finite");
        }
    }
}
