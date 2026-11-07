package com.caf.lccustomcity.mixin;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apply the existing disabled mask to the new intercity network as well as legacy highways. */
@Mixin(mcjty.lostcities.worldgen.lost.Highway.class)
public abstract class HighwayDisableMixin {
    @Inject(method="computeHighwayInfo", at=@At("HEAD"), cancellable=true, require=1)
    private static void caf$disabledHighways(ChunkCoord coord, IDimensionInfo provider,
            LostCityProfile profile,
            CallbackInfoReturnable<mcjty.lostcities.worldgen.highway.HighwayInfo> callback) {
        if (profile.HIGHWAY_DISTANCE_MASK <= 0)
            callback.setReturnValue(mcjty.lostcities.worldgen.highway.HighwayInfo.NONE);
    }
}
