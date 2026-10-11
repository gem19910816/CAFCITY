package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.EmptyPlotConfig;
import com.caf.lccustomcity.impl.CityLayout;
import com.caf.lccustomcity.impl.EmptyPlotRule;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Random;

/** LC 8.3.9 uses the six-argument legacy building decision. */
@Mixin(BuildingInfo.class)
public abstract class FillPlotsMixin {
    @Inject(method="checkBuildingPossibility(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;Lmcjty/lostcities/api/MultiPos;ILjava/util/Random;)Z",
            at=@At("RETURN"),cancellable=true,require=0)
    private static void caf$forceBuildingOnEmptyPlots(ChunkCoord coord,IDimensionInfo provider,
            LostCityProfile profile,MultiPos section,int cityLevel,Random random,CallbackInfoReturnable<Boolean> cir) {
        var cell=CityLayout.cell(coord,provider,profile);
        if(cell!=null){cir.setReturnValue(cell.placement()!=null);return;}
        if(coord==null||provider==null||profile==null)return;
        if(EmptyPlotRule.shouldForce(Boolean.TRUE.equals(cir.getReturnValue()),EmptyPlotConfig.enabled(),
                City.getPredefinedStreet(provider.getWorld(),coord)!=null,section!=null&&section.isMulti(),false,
                BuildingInfo.hasHighway(coord,provider,profile),BuildingInfo.hasRailway(coord,provider,profile)))
            cir.setReturnValue(true);
    }
}
