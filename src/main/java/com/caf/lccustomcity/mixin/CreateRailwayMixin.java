package com.caf.lccustomcity.mixin;
import com.caf.lccustomcity.impl.CreateRailwayRuntime;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LostCityTerrainFeature.class)
public abstract class CreateRailwayMixin {
    @Shadow public abstract ChunkDriver getDriver();
    @Redirect(method="generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",
        at=@At(value="INVOKE",target="Lmcjty/lostcities/worldgen/LostCityTerrainFeature;transformBlockState(Lmcjty/lostcities/worldgen/lost/Transform;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"),require=1)
    private net.minecraft.world.level.block.state.BlockState caf$orient(LostCityTerrainFeature terrain,Transform t,net.minecraft.world.level.block.state.BlockState state,
        BuildingInfo info,IBuildingPart part,Transform supplied,int x,int y,int z,LostCityTerrainFeature.HardAirSetting air) {
        String name=CreateRailwayRuntime.name(part);
        if(com.caf.lccustomcity.convert.RailwayAssetLoader.active() &&
            (com.caf.lccustomcity.convert.RailwayAssetLoader.names().contains(name) || name.equals("station_staircase") || name.equals("station_staircase_surface")))
            return com.caf.lccustomcity.impl.RailwayBlockOrientation.transform(state,t);
        return terrain.transformBlockState(t,state);
    }
    @Inject(method="generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",at=@At("RETURN"),require=1)
    private void caf$tracks(BuildingInfo info,IBuildingPart part,Transform t,int x,int y,int z,LostCityTerrainFeature.HardAirSetting air,CallbackInfoReturnable<Integer> cir) {
        if(!com.caf.lccustomcity.convert.RailwayAssetLoader.active())return;
        com.caf.lccustomcity.impl.RailwayBlockOrientation.repairEntranceTorches(getDriver(),info,part,t,x,y,z);
        CreateRailwayRuntime.finish(getDriver(),info,part,t,x,y,z);
    }
}
