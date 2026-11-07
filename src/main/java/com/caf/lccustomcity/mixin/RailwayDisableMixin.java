package com.caf.lccustomcity.mixin;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stop disabled rail networks before recursive planning, including all underground/sea sections. */
@Mixin(mcjty.lostcities.worldgen.lost.Railway.class)
public abstract class RailwayDisableMixin {
    @Inject(method="getRailChunkType", at=@At("HEAD"), cancellable=true, require=1)
    private static void caf$disabledRailways(ChunkCoord coord, IDimensionInfo provider,
            LostCityProfile profile,
            CallbackInfoReturnable<mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo> callback) {
        if (!profile.RAILWAYS_ENABLED && !profile.RAILWAY_STATIONS_ENABLED)
            callback.setReturnValue(mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo.NOTHING);
    }
}
