package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.CitySurfaceStructures;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructureStart.class)
public abstract class VanillaSurfaceStructureMixin {
    @Inject(method="placeInChunk(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;)V",
            at=@At("HEAD"),cancellable=true,require=0)
    private void caf$noSurfaceStructure(WorldGenLevel world,StructureManager structures,ChunkGenerator generator,
            RandomSource random,BoundingBox box,ChunkPos chunk,CallbackInfo ci){
        if(CitySurfaceStructures.suppress(world,(StructureStart)(Object)this))ci.cancel();
    }
}
