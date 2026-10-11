package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.ForceFlattenConfig;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In 8.3.9 structure avoidance belongs to the terrain feature. */
@Mixin(LostCityTerrainFeature.class)
public abstract class StructureAvoidanceMixin {
    @Inject(method="hasBlacklistedStructure(Lnet/minecraft/world/level/WorldGenLevel;II)Lmcjty/lostcities/worldgen/LostCityTerrainFeature$AvoidChunk;",
            at=@At("HEAD"),cancellable=true,require=0)
    private static void caf$forceFlatten(WorldGenLevel world,int x,int z,
            CallbackInfoReturnable<LostCityTerrainFeature.AvoidChunk> cir) {
        if(ForceFlattenConfig.enabled())cir.setReturnValue(LostCityTerrainFeature.AvoidChunk.NO);
    }
}
