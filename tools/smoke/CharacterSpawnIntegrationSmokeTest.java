package com.caf.lccustomcity;

import java.util.concurrent.atomic.AtomicInteger;

/** Regression tests for wilderness spawn exclusion without generating a world. */
public final class CharacterSpawnIntegrationSmokeTest {
    public static void main(String[] args) {
        check(CharacterSpawnIntegration.outsideCityFootprints(0, 0, (x, z) -> false), "open terrain accepted");
        check(!CharacterSpawnIntegration.outsideCityFootprints(0, 0, (x, z) -> x == 0 && z == 0), "city itself rejected");
        check(!CharacterSpawnIntegration.outsideCityFootprints(0, 0, (x, z) -> x == 4 && z == 0), "64-block edge rejected");
        check(!CharacterSpawnIntegration.outsideCityFootprints(0, 0, (x, z) -> x == -4 && z == -4), "diagonal edge rejected");
        check(CharacterSpawnIntegration.outsideCityFootprints(0, 0, (x, z) -> x == 5 && z == 0), "terrain beyond buffer accepted");
        check(!CharacterSpawnIntegration.outsideCityFootprints(-1, -1, (x, z) -> x == -5 && z == -5), "negative coordinates use floor division");
        AtomicInteger count = new AtomicInteger();
        CharacterSpawnIntegration.outsideCityFootprints(513, -513, (x, z) -> {count.incrementAndGet(); return false;});
        check(count.get() == 81, "complete 9 by 9 margin checked");
        System.out.println("CharacterSpawnIntegrationSmokeTest: 7 checks passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
