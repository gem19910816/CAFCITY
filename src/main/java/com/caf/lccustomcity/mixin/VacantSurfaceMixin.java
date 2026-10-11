package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.LayoutCatalog;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Transform;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace the complete legacy street path, including parks, fountains and borders. */
@Mixin(LostCityTerrainFeature.class)
public abstract class VacantSurfaceMixin {
    @Shadow @Final public IDimensionInfo provider;
    @Shadow @Final public ChunkDriver driver;
    @Shadow public abstract int generatePart(BuildingInfo info,IBuildingPart part,Transform transform,
            int ox,int oy,int oz,LostCityTerrainFeature.HardAirSetting air);
    @Inject(method="generateStreet(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/ChunkHeightmap;)V",
            at=@At("HEAD"),cancellable=true,require=0)
    private void caf$vacantLand(BuildingInfo info,ChunkHeightmap heightmap,CallbackInfo ci) {
        if(!LayoutCatalog.current().active()||!info.isCity||info.hasBuilding)return;
        boolean frontage=com.caf.lccustomcity.impl.ChaosRoadRuntime.frontage(info.coord,provider,info.profile);
        if(com.caf.lccustomcity.config.RoadPresetConfig.enabled()
                &&com.caf.lccustomcity.impl.CityLayout.enabled(provider,info.profile)
                &&com.caf.lccustomcity.impl.ChaosRoadRuntime.vacant(info.coord,provider,info.profile)){
            var selected=com.caf.lccustomcity.impl.ChaosRoadRuntime.select(info.coord,provider,info.profile);
            var decor=com.caf.lccustomcity.impl.ChaosRoadRuntime.decoration(info.coord,provider,info.profile,selected);
            var scene=com.caf.lccustomcity.impl.ChaosRoadRuntime.part(selected.name(),selected.turns(),decor,frontage);
            if(scene!=null){
                generatePart(info,scene,Transform.ROTATE_NONE,
                    0,info.getCityGroundLevel(),0,LostCityTerrainFeature.HardAirSetting.VOID);
                ci.cancel();return;
            }
        }
        var part=AssetRegistries.PARTS.getOrThrow(provider.getWorld(),"caf_lc_customcity:vacant_surface");
        generatePart(info,part,Transform.ROTATE_NONE,0,info.getCityGroundLevel(),0,
                LostCityTerrainFeature.HardAirSetting.VOID);
        if(!BuildingInfo.hasHighway(info.coord,provider,info.profile)
                && (!(info.profile.RAILWAYS_ENABLED||info.profile.RAILWAY_STATIONS_ENABLED)
                    ||!BuildingInfo.hasRailwayAtSurface(info.coord,provider,info.profile)))
            com.caf.lccustomcity.impl.VacantVegetationPlacer.place(driver,info,provider.getSeed(),frontage);
        ci.cancel();
    }
    @Inject(method="generateStreetDecorations(Lmcjty/lostcities/worldgen/lost/BuildingInfo;)V",
            at=@At("HEAD"),cancellable=true,require=0)
    private void caf$noStreetDecorations(BuildingInfo info,CallbackInfo ci) {
        if(LayoutCatalog.current().active()&&info.isCity&&!info.hasBuilding)ci.cancel();
    }
}
