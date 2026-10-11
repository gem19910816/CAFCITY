package com.caf.lccustomcity.impl;

import java.util.*;

/** World-coordinate shrub patches remain continuous across vacant chunk edges. */
public final class VacantVegetationPlan {
    public record Carpet(int x,int z) {}
    private VacantVegetationPlan() {}
    private static long hash(long seed,int x,int z) {
        return RadialZonePlan.mix(seed^((long)x*0x9e3779b97f4a7c15L)^((long)z*0xc2b2ae3d27d4eb4fL));
    }
    public static int height(long seed,int x,int z) {
        return height(seed,x,z,true);
    }
    private static int height(long seed,int x,int z,boolean reduced) {
        int tx=Math.floorDiv(x,32),tz=Math.floorDiv(z,32),height=0;
        for(int i=tx-1;i<=tx+1;i++)for(int j=tz-1;j<=tz+1;j++){
            long h=hash(seed,i,j);if(Long.remainderUnsigned(h,100)>=65)continue;
            // Retain 30% of complete existing patches, rather than punch holes in shrubs.
            if(reduced&&Long.remainderUnsigned(RadialZonePlan.mix(h^0x77c3419361e9baf5L),100)>=30)continue;
            int cx=i*32+4+(int)Long.remainderUnsigned(h>>>7,24),cz=j*32+4+(int)Long.remainderUnsigned(h>>>13,24);
            int rx=5+(int)Long.remainderUnsigned(h>>>19,6),rz=5+(int)Long.remainderUnsigned(h>>>25,6);
            double dx=(double)(x-cx)/rx,dz=(double)(z-cz)/rz,d=dx*dx+dz*dz;
            if(d>=1)continue;
            // Broad one-block fringe and irregular two-block centre; no trees or logs.
            height=Math.max(height,d<.46&&Long.remainderUnsigned(hash(h,x,z),8)!=0?2:1);
        }
        return height;
    }
    public static List<Carpet> carpets(long seed,int chunkX,int chunkZ) {
        long h=hash(seed^0x6a09e667f3bcc909L,chunkX,chunkZ);
        int target=2+(int)Long.remainderUnsigned(h,4);var result=new ArrayList<Carpet>();
        // A seeded permutation visits every cell once, choosing clear ground.
        int start=(int)(h>>>8)&255,step=((int)(h>>>16)&255)|1;
        int found=0;
        for(int i=0;i<256&&found<target;i++){
            int n=(start+i*step)&255,x=n&15,z=n>>>4;
            if(height(seed,chunkX*16+x,chunkZ*16+z,false)==0){
                found++;
                if(Long.remainderUnsigned(hash(h^0x405cbd78a41ed31bL,x,z),100)<30)result.add(new Carpet(x,z));
            }
        }
        return List.copyOf(result);
    }
}
