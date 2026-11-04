package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.CityLayout;
import com.caf.lccustomcity.impl.UndergroundStationPlots;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.gen.Railways;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Railways.class)
public abstract class UndergroundStationAccessMixin {
    @ModifyVariable(method="generateRailways(Lmcjty/lostcities/worldgen/LostCityTerrainFeature;Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/Railway$RailChunkInfo;Lmcjty/lostcities/worldgen/ChunkHeightmap;)V",
            at=@At("HEAD"),argsOnly=true,ordinal=0,require=1)
    private static RailChunkInfo caf$keepStationBelowLandmark(RailChunkInfo rail,
            LostCityTerrainFeature feature,BuildingInfo info,RailChunkInfo original,ChunkHeightmap heightmap) {
        if(!info.hasBuilding||!UndergroundStationPlots.deepStation(rail,
                feature.provider.getWorldStyle().getWorldSettings().railPartHeight6()))return rail;
        // Generation runs after the complete plot plan exists. Do not inject this
        // lookup in Railway.getRailChunkType, which the planner itself must call.
        var cell=CityLayout.cell(info.coord,feature.provider,info.profile);
        return UndergroundStationPlots.withoutSurfaceAccess(rail,
                cell!=null&&cell.suppressUndergroundStationAccess());
    }
}
