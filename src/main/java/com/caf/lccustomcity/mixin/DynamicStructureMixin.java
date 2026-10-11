package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.DynamicStructurePlacer;
import com.caf.lccustomcity.impl.ImportedBlockEntities;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LostCityTerrainFeature.class)
public abstract class DynamicStructureMixin {
    @Shadow @Final public IDimensionInfo provider;
    @Shadow @Final public ChunkDriver driver;
    @Unique private final ImportedBlockEntities caf$pendingMachines=new ImportedBlockEntities();
    @Inject(method="generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",at=@At("RETURN"),require=0)
    private void caf$entities(BuildingInfo info,IBuildingPart part,Transform transform,int ox,int oy,int oz,
                              LostCityTerrainFeature.HardAirSetting air,CallbackInfoReturnable<Integer> cir) {
        // Our imported floors are already rotated by BuildingRotationMixin, including metadata.
        if(transform!=Transform.ROTATE_NONE)return;
        DynamicStructurePlacer.place(provider.getWorld(),driver.getPrimer(),part,
                info.coord.chunkX()*16+ox,oy,info.coord.chunkZ()*16+oz);
    }
    @Redirect(method="generatePart(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/cityassets/IBuildingPart;Lmcjty/lostcities/worldgen/lost/Transform;IIILmcjty/lostcities/worldgen/LostCityTerrainFeature$HardAirSetting;)I",
            at=@At(value="INVOKE",target="Lmcjty/lostcities/worldgen/ChunkDriver;add(Lnet/minecraft/world/level/block/state/BlockState;)Lmcjty/lostcities/worldgen/ChunkDriver;"),require=0)
    private ChunkDriver caf$importedState(ChunkDriver target,BlockState state,BuildingInfo info,IBuildingPart part,
            Transform transform,int ox,int oy,int oz,LostCityTerrainFeature.HardAirSetting air) {
        if(!part.getName().startsWith("caf_lc_customcity:"))return target.add(state);
        // In 8.3.9 BOTH add() and block() call correct()/updateShape.
        // A one-cell range writes the buffer without touching incomplete neighbours.
        var pos=target.getCurrentCopy();
        target.setBlockRange(pos.getX()&15,pos.getY(),pos.getZ()&15,pos.getY()+1,state);
        target.incY();return target;
    }
    @Inject(method="handleBlockEntity(Lmcjty/lostcities/worldgen/lost/BuildingInfo;ILnet/minecraft/world/level/WorldGenLevel;IIILnet/minecraft/world/level/block/state/BlockState;Lmcjty/lostcities/worldgen/lost/cityassets/Palette$Info;)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"),cancellable=true,require=0)
    private void caf$createData(BuildingInfo info,int oy,WorldGenLevel world,int x,int z,int sy,
                                BlockState state,Palette.Info paletteInfo,CallbackInfoReturnable<BlockState> cir) {
        if(caf$pendingMachines.queue(driver.getPrimer(),info.getRelativePos(x,oy+sy,z),state,paletteInfo.tag()))
            cir.setReturnValue(state);
    }
    @Redirect(method={"generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            "generateBuilding(Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/ChunkHeightmap;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"},
            at=@At(value="INVOKE",target="Lmcjty/lostcities/worldgen/ChunkDriver;actuallyGenerate(Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),require=2)
    private void caf$flushMachines(ChunkDriver target,ChunkAccess chunk) {
        target.actuallyGenerate(chunk);
        caf$pendingMachines.flush(chunk);
    }
}
