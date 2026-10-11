package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingRotation;
import java.util.*;
import java.util.function.BiPredicate;

/** Assemble connected original street modules as one deterministic reservation. */
public final class ChaosDefencePlan {
    public record Group(int x,int z,int width,int depth,int turns,int variant,ChaosStreetDecor.Selection decoration) {}
    private ChaosDefencePlan() {}
    private static final int TILE=8;
    public static Group select(long seed,int x,int z,BiPredicate<Integer,Integer> usable){
        int tx=Math.floorDiv(x,TILE),tz=Math.floorDiv(z,TILE);
        int lx=Math.floorMod(x,TILE),lz=Math.floorMod(z,TILE);
        if(lx==0||lz==0||lx==TILE-1||lz==TILE-1)return null;
        long h=RadialZonePlan.mix(seed^0x2d3b8c539671acdfL^((long)tx*0x9e3779b97f4a7c15L)^((long)tz*0xc2b2ae3d27d4eb4fL));
        // Every tile now searches its actual free interior. A blocked random anchor must
        // not hide a usable three-chunk corridor elsewhere in the same tile.
        long available=0;
        for(int dz=1;dz<TILE-1;dz++)for(int dx=1;dx<TILE-1;dx++)
            if(usable.test(tx*TILE+dx,tz*TILE+dz))available|=1L<<(dz*TILE+dx);
        int variant=0,turn=0,w=0,rw=0,rd=0,ax=0,az=0;boolean found=false;
        search:for(int v=0;v<4;v++){
            variant=(((int)(h>>>32)&3)+v)&3;w=variant==1?4:3;
            int d=variant<2?1:variant==2?2:3;
            for(int t=0;t<(w==d?1:2);t++){
                turn=(((int)(h>>>34)&3)+t)&3;
                rw=(turn&1)==0?w:d;rd=(turn&1)==0?d:w;
                int nx=TILE-rw-1,nz=TILE-rd-1,count=nx*nz;
                int start=(int)Long.remainderUnsigned(RadialZonePlan.mix(h^((long)variant<<16)^turn),count);
                for(int i=0;i<count;i++){
                    int n=(start+i)%count,cx=1+n%nx,cz=1+n/nx;long reservation=0;
                    for(int dz=0;dz<rd;dz++)reservation|=((1L<<rw)-1)<<((cz+dz)*TILE+cx);
                    if((available&reservation)!=reservation)continue;
                    ax=tx*TILE+cx;az=tz*TILE+cz;found=true;break search;
                }
            }
        }
        if(!found)return null;
        if(x<ax||z<az||x>=ax+rw||z>=az+rd)return null;
        // One complete, non-overlapping reservation per tile, independent of chunk order.
        int inverse=Math.floorMod(-turn,4);
        int sx=BuildingRotation.x(x-ax,z-az,rw,rd,inverse),sz=BuildingRotation.z(x-ax,z-az,rw,rd,inverse);
        if(!occupied(variant,sx,sz,w))return new Group(ax,az,rw,rd,turn,variant,null);
        int mask=(occupied(variant,sx-1,sz,w)?1:0)|(occupied(variant,sx+1,sz,w)?2:0)
            |(occupied(variant,sx,sz-1,w)?4:0)|(occupied(variant,sx,sz+1,w)?8:0);
        String name;int canonical;
        if(Integer.bitCount(mask)==1){name="street_end3_0_0_0";canonical=1;}
        else if(mask==3||mask==12){name="street_straight11_0_0_0";canonical=3;}
        else if(mask==15){name="street_all3_0_0_0";canonical=15;}
        else {name="street_t3_0_0_0";canonical=7;}
        int orientation=0;while(orientation<4&&ChaosRoadPlan.rotate(canonical,orientation)!=mask)orientation++;
        if(orientation==4)throw new IllegalStateException("Unsupported defence ports "+mask);
        return new Group(ax,az,rw,rd,turn,variant,new ChaosStreetDecor.Selection(name,(orientation+turn)&3,true,ChaosRoadPlan.rotate(mask,turn)));
    }
    private static boolean occupied(int variant,int x,int z,int width){
        if(x<0||z<0||x>=width)return false;
        return variant<2?z==0:variant==2?(z==0||z==1&&x==1):z<3&&(x==1||z==1);
    }
}
