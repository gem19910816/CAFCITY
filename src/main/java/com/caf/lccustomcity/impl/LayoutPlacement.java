package com.caf.lccustomcity.impl;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import com.caf.lccustomcity.stats.BuildingStats;

/** Adapter kept outside the mixin package so the offline suite exercises the real asset path. */
public final class LayoutPlacement {
    private LayoutPlacement() {}
    public static String fallbackSingleId() {
        var buildings=com.caf.lccustomcity.config.LayoutCatalog.current().buildings();
        var b=buildings.stream().filter(source->!source.multi()).findFirst().orElse(buildings.getFirst());
        return b.id()+(b.multi()?"_c0_0":"");
    }
    public static boolean initialize(LostChunkCharacteristics c,ChunkCoord coord,IDimensionInfo p,LostCityProfile profile) {
        CityLayout.Cell cell=CityLayout.cell(coord,p,profile);
        if(cell==null) return false;
        return assign(c,coord,p,cell);
    }
    public static boolean assign(LostChunkCharacteristics c,ChunkCoord coord,IDimensionInfo p,CityLayout.Cell cell) {
        c.multiPos=MultiPos.SINGLE; c.multiBuilding=null;
        var placement=cell.placement();
        if(placement==null || !placement.building().multi()) return true;
        var b=placement.building();
        c.multiBuilding=AssetRegistries.MULTI_BUILDINGS.getOrThrow(p.getWorld(),b.assetId());
        c.multiPos=new MultiPos(coord.chunkX()-placement.x(),coord.chunkZ()-placement.z(),b.width(),b.depth());
        if(c.multiPos.isTopLeft()) BuildingStats.record(b.id(),true);
        return true;
    }
}
