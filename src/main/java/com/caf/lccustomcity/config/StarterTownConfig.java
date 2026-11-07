package com.caf.lccustomcity.config;

/** One nearby town occupies an existing T2 quota slot, rather than adding a city. */
public final class StarterTownConfig {
    public record Settings(boolean enabled, int minDistance, int maxDistance) {}
    public static final Settings DEFAULT = new Settings(true, 600, 900);
    private static volatile Settings current = DEFAULT;
    private StarterTownConfig() {}
    public static Settings current() { return current; }
    public static void install(boolean enabled, int min, int max) {
        min = Math.max(16, Math.min(2048, min));
        max = Math.max(min, Math.min(2048, max));
        current = new Settings(enabled, min, max);
    }
}
