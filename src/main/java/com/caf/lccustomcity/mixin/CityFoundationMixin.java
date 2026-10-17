package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.ForceFlattenConfig;
import com.caf.lccustomcity.impl.CityFoundation;
import com.caf.lccustomcity.impl.CityLayout;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LostCityTerrainFeature.class)
public abstract class CityFoundationMixin {
    @Shadow @Final public IDimensionInfo provider;
    @Shadow @Final public ChunkDriver driver;
    @Inject(method="doCityChunk(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/ChunkHeightmap;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at=@At("HEAD"),require=0)
    private void caf$solidFoundation(BuildingInfo info,ChunkHeightmap heightmap,ChunkAccess chunk,CallbackInfo ci){
        if(ForceFlattenConfig.enabled()&&info.isCity&&CityLayout.enabledFor(info.coord,provider,info.profile))
            CityFoundation.fill(driver,info.getCityGroundLevel());
    }
}
