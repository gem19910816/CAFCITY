package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.ChaosRoadAssets;

/** Small, seeded street scenes. No building plots or native front generation are reserved. */
public final class ChaosStreetDecor {
    public record Selection(String name,int turns,boolean defence,int connections) {
        public Selection(String name,int turns,boolean defence){this(name,turns,defence,0);}
    }
    private ChaosStreetDecor() {}
    public static Selection select(long seed,int x,int z,boolean openArea,int buildingNeighbours) {
        long h=hash(seed,x,z);
        int chance=openArea?24:buildingNeighbours!=0?44:0;
        if(Long.remainderUnsigned(h,100)>=chance)return null;
        // Keep nearby extras sparse without depending on the order chunks generate.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if((dx!=0||dz!=0)&&Long.compareUnsigned(hash(seed,x+dx,z+dz),h)<0)return null;
        int variant=(int)Long.remainderUnsigned(RadialZonePlan.mix(h),openArea?4:6);
        if(openArea)return new Selection(ChaosRoadAssets.DEFENCES.get(variant),(int)((h>>>32)&3),true);
        String name=ChaosRoadAssets.STREET_DECORATIONS.get(variant);
        int canonical=name.startsWith("bus_stop")?ChaosRoadPlan.WEST:ChaosRoadPlan.EAST;
        var turns=new int[4];int count=0;
        for(int turn=0;turn<4;turn++)if((ChaosRoadPlan.rotate(canonical,turn)&buildingNeighbours)!=0)turns[count++]=turn;
        return new Selection(name,turns[(int)Long.remainderUnsigned(h,count)],false);
    }
    private static long hash(long seed,int x,int z){
        return RadialZonePlan.mix(seed^0x51897c83a4612b7dL^RadialZonePlan.mix((long)x*0x9e3779b97f4a7c15L)
            ^RadialZonePlan.mix((long)z*0xc2b2ae3d27d4eb4fL));
    }
}
