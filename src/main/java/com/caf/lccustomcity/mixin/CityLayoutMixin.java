package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.impl.CityLayout;
import com.caf.lccustomcity.impl.LayoutPlacement;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BuildingInfo.class)
public abstract class CityLayoutMixin {
    @Inject(method="getChunkCharacteristics(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;)Lmcjty/lostcities/api/LostChunkCharacteristics;",
            at=@At("HEAD"),require=0)
    private static void caf$worldContext(ChunkCoord coord,IDimensionInfo provider,
            CallbackInfoReturnable<LostChunkCharacteristics> cir) {
        com.caf.lccustomcity.impl.MixinWorldAccess.record(provider);
    }
    @Inject(method="getChunkCharacteristics(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;)Lmcjty/lostcities/api/LostChunkCharacteristics;",
            at=@At("RETURN"),cancellable=true,require=0)
    private static void caf$finalPlan(ChunkCoord coord,IDimensionInfo provider,
                                      CallbackInfoReturnable<LostChunkCharacteristics> cir) {
        var c=cir.getReturnValue();
        if(c==null||!c.isCity)return;
        if (CityStageConfig.current().enabled() && !CityLayout.enabledFor(coord, provider, provider.getProfile())) {
            // A native Lost Cities center rejected by the active stage rules must
            // not fall back to the original building list.
            c.isCity = false;
            c.couldHaveBuilding = false;
            return;
        }
        var cell=CityLayout.cell(coord,provider,provider.getProfile());
        if(cell==null)return;
        c.couldHaveBuilding=cell.placement()!=null;
        var district=CityLayout.district(coord,provider,provider.getProfile());
        if(district!=null)c.cityStyle=mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries.CITYSTYLES
                .getOrThrow(provider.getWorld(),district.style());
        if(cell.placement()!=null&&!cell.placement().building().multi())
            c.buildingType=mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries.BUILDINGS
                    .getOrThrow(provider.getWorld(),cell.placement().building().id());
    }
    @Inject(method="initMultiBuildingSection(Lmcjty/lostcities/api/LostChunkCharacteristics;Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)V",
            at=@At("HEAD"),cancellable=true,require=0)
    private static void caf$plannedMulti(LostChunkCharacteristics characteristics,ChunkCoord coord,
                                         IDimensionInfo provider,LostCityProfile profile,CallbackInfo ci) {
        if(LayoutPlacement.initialize(characteristics,coord,provider,profile)) ci.cancel();
    }
    @Inject(method="isCityRaw(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Z",
            at=@At("RETURN"),cancellable=true,require=0)
    private static void caf$circularCity(ChunkCoord coord,IDimensionInfo provider,LostCityProfile profile,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (CityLayout.enabled(provider, profile)) {
            if (CityStageConfig.current().enabled() && !CityLayout.enabledFor(coord, provider, profile)) {
                cir.setReturnValue(false);
                return;
            }
            if (CityLayout.enabledFor(coord,provider,profile)) cir.setReturnValue(!BuildingInfo.isVoidChunk(coord,provider)
                    && (CityLayout.predefined(coord,provider) || CityLayout.center(coord,provider,profile)!=null));
        }
    }
}
