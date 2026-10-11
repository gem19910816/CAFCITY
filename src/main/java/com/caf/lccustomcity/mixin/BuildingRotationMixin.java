package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.BuildingRotationRuntime;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LostCityTerrainFeature.class)
public abstract class BuildingRotationMixin {
    @Shadow @Final public IDimensionInfo provider;
    @ModifyVariable(method="generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",
            at=@At("HEAD"),argsOnly=true,ordinal=0,require=1)
    private IBuildingPart caf$rotateBuilding(IBuildingPart part,BuildingInfo info,IBuildingPart original,
            Transform transform,int ox,int oy,int oz,LostCityTerrainFeature.HardAirSetting air){
        return transform==Transform.ROTATE_NONE?BuildingRotationRuntime.orient(part,info,provider):part;
    }
}
