package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.LayoutCatalog.Building;
import com.caf.lccustomcity.config.StageLayoutConfig;
import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo;

/** Constant-time footprint checks; no world, characteristics or city-plan queries. */
public final class UndergroundStationPlots {
    private final int[][] prefix;
    public UndergroundStationPlots(boolean[][] stations) {
        int width=stations.length, depth=stations[0].length;
        prefix=new int[width+1][depth+1];
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++)
            prefix[x+1][z+1]=prefix[x][z+1]+prefix[x+1][z]-prefix[x][z]+(stations[x][z]?1:0);
    }
    public boolean intersects(Building building,int x,int z) {
        int endX=x+building.width(),endZ=z+building.depth();
        return prefix[endX][endZ]-prefix[x][endZ]-prefix[endX][z]+prefix[x][z]>0;
    }
    public static boolean requiredLandmark(Building building,StageLayoutConfig.Rules rules,
                                           int spacingX,int spacingZ) {
        // Both orientations must remain eligible. No optional source gets this exception.
        if(Math.max(building.width(),building.depth())<=2L*Math.max(spacingX,spacingZ))return false;
        var rule=rules==null?null:rules.building(building.sourceFile());
        if(rule!=null&&rule.hasCount())return rule.minimumCount()>0;
        var limit=(building.multi()?BuildingCountLimits.forStyleMulti(building.style()):
                BuildingCountLimits.forStyleSingle(building.style())).get(building.id());
        return limit!=null&&Math.min(limit.max(),Math.max(0,limit.min()))>0;
    }
    public static boolean deepStation(RailChunkInfo rail,int partLevels) {
        // Converted buildings have no cellars and normal city levels are >= 0.
        // Refuse stations whose entire part cannot fit below their ground plane.
        return (rail.getType()==RailChunkType.STATION_UNDERGROUND
                ||rail.getType()==RailChunkType.STATION_EXTENSION_UNDERGROUND)
                &&rail.getLevel()+Math.max(1,partLevels)<=0;
    }
    public static RailChunkInfo withoutSurfaceAccess(RailChunkInfo rail,boolean suppress) {
        if(!suppress||rail.getType()!=RailChunkType.STATION_UNDERGROUND)return rail;
        // Native extension uses the same connected platform, without any shaft,
        // staircase or surface entrance. Direction, depth and rail count stay intact.
        return new RailChunkInfo(RailChunkType.STATION_EXTENSION_UNDERGROUND,
                rail.getDirection(),rail.getLevel(),rail.getRails());
    }
}
