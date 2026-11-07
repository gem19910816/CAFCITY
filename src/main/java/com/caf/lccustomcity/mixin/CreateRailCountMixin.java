package com.caf.lccustomcity.mixin;
import com.caf.lccustomcity.impl.CreateRailwayRuntime;
import mcjty.lostcities.worldgen.gen.Railways;
import mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Prevent LC's two-track variant from deleting the replacement's middle mainline. */
@Mixin(Railways.class)
public abstract class CreateRailCountMixin {
    @Redirect(method="generateRailways",at=@At(value="INVOKE",target="Lmcjty/lostcities/worldgen/lost/Railway$RailChunkInfo;getRails()I"),require=1)
    private static int caf$singleLine(RailChunkInfo info) {return com.caf.lccustomcity.convert.RailwayAssetLoader.active()?3:info.getRails();}
}
