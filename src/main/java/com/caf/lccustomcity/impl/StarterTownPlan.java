package com.caf.lccustomcity.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Bounded, deterministic geometry; no world/chunk access. */
public final class StarterTownPlan {
    public static final int MAX_TERRAIN_ATTEMPTS = 128;
    public static final int EDGE_GAP = 64;
    public record Point(int x, int z) {}
    public record Selection(Point point, boolean earthworks) {}
    private StarterTownPlan() {}

    public static List<Point> candidates(long seed, int anchorX, int anchorZ, int min, int max) {
        if (min < 16 || max < min || max > 2048) return List.of();
        List<Point> points = new ArrayList<>();
        long min2 = (long) min * min, max2 = (long) max * max;
        for (int x = Math.floorDiv(anchorX - max, 16) * 16; x <= anchorX + max; x += 16) {
            for (int z = Math.floorDiv(anchorZ - max, 16) * 16; z <= anchorZ + max; z += 16) {
                long dx = (long) x - anchorX, dz = (long) z - anchorZ;
                long d2 = dx * dx + dz * dz;
                if (d2 >= min2 && d2 <= max2) points.add(new Point(x, z));
            }
        }
        points.sort(Comparator.comparingLong((Point p) ->
                RadialZonePlan.citySeed(seed ^ 0x43414654324e4541L, p.x(), p.z()))
                .thenComparingInt(Point::x).thenComparingInt(Point::z));
        return List.copyOf(points);
    }

    public static Selection select(List<Point> points, Predicate<Point> eligible,
                                   Predicate<Point> terrain, boolean allowEarthworks) {
        Point fallback = null;
        int attempts = 0;
        for (Point point : points) {
            if (!eligible.test(point)) continue;
            if (fallback == null) fallback = point;
            if (terrain.test(point)) return new Selection(point, false);
            if (++attempts >= MAX_TERRAIN_ATTEMPTS) break;
        }
        return allowEarthworks && fallback != null ? new Selection(fallback, true) : null;
    }

    public static boolean separated(int ax, int az, double ar, int bx, int bz, double br) {
        long dx = (long) ax - bx, dz = (long) az - bz;
        double required = ar + br + EDGE_GAP;
        return dx * dx + dz * dz >= required * required;
    }
}
