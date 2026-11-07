package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.ForceFlattenConfig;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.StructureAvoidance;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In 8.4.x structure avoidance is planned separately from the terrain feature. */
@Mixin(StructureAvoidance.class)
public abstract class StructureAvoidanceMixin {
    @Inject(method="check(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/api/MultiPos;Lnet/minecraft/world/level/WorldGenLevel;)Lmcjty/lostcities/worldgen/lost/StructureAvoidance$Result;",
            at=@At("HEAD"),cancellable=true,require=0)
    private static void caf$forceFlatten(ChunkCoord coord, MultiPos multiPos, WorldGenLevel world,
            CallbackInfoReturnable<StructureAvoidance.Result> cir) {
        if(ForceFlattenConfig.enabled())cir.setReturnValue(StructureAvoidance.Result.NONE);
    }
}
