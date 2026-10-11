package com.caf.lccustomcity.impl;

/** Angles in radians; opposite residential wedges, one perpendicular industrial wedge. */
public final class RadialZonePlan {
    private RadialZonePlan() {}
    public static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }
    public static long citySeed(long seed, int x, int z) {
        return mix(seed ^ mix(x * 0x9e3779b97f4a7c15L) ^ mix(z * 0xc2b2ae3d27d4eb4fL));
    }
    public static double axis(long citySeed) { return (mix(citySeed) >>> 11) * 0x1.0p-53 * Math.PI; }
    public static double delta(double a, double b) { return Math.atan2(Math.sin(a-b), Math.cos(a-b)); }
    public static boolean feasible(double residential, double industrial) {
        return residential >= 0 && industrial >= 0 && residential + 2 * industrial <= 1 + 1e-9;
    }
    /** 0 = commercial, 1 = residential, 2 = industrial. */
    public static int zone(double dx, double dz, long citySeed, double residential, double industrial) {
        if (!feasible(residential, industrial)) throw new IllegalArgumentException("Industrial sector exceeds one side gap");
        double angle = Math.atan2(dz, dx), axis = axis(citySeed);
        if (Math.abs(delta(angle, axis)) < Math.PI * residential / 2
                || Math.abs(delta(angle, axis + Math.PI)) < Math.PI * residential / 2) return 1;
        double industryAxis = axis + ((mix(citySeed ^ 0x12341L) & 1) == 0 ? Math.PI/2 : -Math.PI/2);
        return Math.abs(delta(angle, industryAxis)) < Math.PI * industrial ? 2 : 0;
    }
    public static boolean outer(double dx, double dz, double radius, double share) {
        return dx*dx + dz*dz >= radius*radius*(1-share);
    }
}
