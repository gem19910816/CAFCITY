package com.caf.lccustomcity.impl;

import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

public final class VacantVegetationPlacer {
    private VacantVegetationPlacer() {}
    public static void place(ChunkDriver driver,BuildingInfo info,long seed,boolean frontage) {
        int cx=info.coord.chunkX(),cz=info.coord.chunkZ(),ground=info.getCityGroundLevel();
        var leaves=Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int height=VacantVegetationPlan.height(seed,cx*16+x,cz*16+z);
            for(int y=1;y<=height;y++)driver.current(x,ground+y,z).block(leaves);
        }
        if(frontage)return; // Only shrubs (and preset sandbags) are allowed in front of doors.
        var carpet=Blocks.MOSS_CARPET.defaultBlockState();
        for(var p:VacantVegetationPlan.carpets(seed,cx,cz))driver.current(p.x(),ground+1,p.z()).block(carpet);
    }
}
