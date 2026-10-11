package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.ChaosRoadAssets;

/** Pure, deterministic connection/rotation selection; does not allocate building land. */
public final class ChaosRoadPlan {
    public static final int WEST=1,EAST=2,NORTH=4,SOUTH=8;
    public record Selection(String name,String shape,int turns,int connections){}
    private ChaosRoadPlan(){}
    public static Selection select(long seed,int x,int z,int mask,boolean openArea){
        if(mask<0||mask>15)throw new IllegalArgumentException("Invalid road connections");
        String shape;int canonical;
        switch(Integer.bitCount(mask)){
            case 0->{shape="none";canonical=0;}
            case 1->{shape="end";canonical=WEST;}
            case 2->{boolean straight=mask==(WEST|EAST)||mask==(NORTH|SOUTH);
                shape=straight?"straight":"bend";canonical=straight?WEST|EAST:WEST|NORTH;}
            case 3->{shape="t";canonical=WEST|EAST|NORTH;}
            default->{shape=openArea?"full":"all";canonical=15;}
        }
        long h=RadialZonePlan.mix(seed^((long)x*0x9e3779b97f4a7c15L)^((long)z*0xc2b2ae3d27d4eb4fL));
        int[] validTurns=new int[4];int count=0;
        for(int turn=0;turn<4;turn++)if(rotate(canonical,turn)==mask)validTurns[count++]=turn;
        int turns=validTurns[(int)Long.remainderUnsigned(RadialZonePlan.mix(h),count)];
        var variants=ChaosRoadAssets.variants().get(shape);
        String name=variants.get((int)Long.remainderUnsigned(h,variants.size()));
        return new Selection(name,shape,turns,mask);
    }
    /** LC ROTATE_90: (x,z)->(15-z,x), so W->N->E->S->W. */
    public static int rotate(int mask,int turns){
        for(int i=0;i<turns;i++)mask=((mask&WEST)!=0?NORTH:0)|((mask&NORTH)!=0?EAST:0)
            |((mask&EAST)!=0?SOUTH:0)|((mask&SOUTH)!=0?WEST:0);
        return mask;
    }
}
