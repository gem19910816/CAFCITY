package com.caf.lccustomcity.radio;

/** Shared reception policy; receiving a recording alone never advances exploration. */
public final class RadioReceptionRules {
    private RadioReceptionRules() {}
    public static int maximumSignalTier(int reachedTier) {
        return reachedTier>=3?4:reachedTier>=1?3:2;
    }
    public static boolean canHear(int reachedTier,int signalTier) {return signalTier>=1&&signalTier<=maximumSignalTier(reachedTier);}
    public static int arrive(int reachedTier,int siteTier,boolean city) {
        return city&&canHear(reachedTier,siteTier)?Math.max(reachedTier,siteTier):reachedTier;
    }
    public static boolean scanSucceeds(int rollOutOf100) {return rollOutOf100>=0&&rollOutOf100<60;}
    public static boolean scanSucceeds(int rollOutOf100,int previousFailures) {
        return previousFailures>=2||scanSucceeds(rollOutOf100);
    }
    public static boolean regionalTurn(long receivedCount) {return receivedCount%3==0;}
    public static long automaticDelay(int jitter) {return 36000L+Math.floorMod(jitter,18001);}
}
