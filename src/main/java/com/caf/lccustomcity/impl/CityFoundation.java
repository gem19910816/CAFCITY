package com.caf.lccustomcity.impl;

import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Fill terrain voids before buildings, basements and transit carve their own spaces. */
public final class CityFoundation {
    private CityFoundation(){}
    public static boolean needsFill(BlockState state){
        return state.isAir()||(!state.getFluidState().isEmpty()&&!state.blocksMotion());
    }
    public static void fill(ChunkDriver driver,int ground){
        var chunk=driver.getPrimer();if(chunk==null)return;
        int bottom=chunk.getMinBuildHeight(),top=Math.min(ground,chunk.getMaxBuildHeight());
        if(top<=bottom)return;
        int soil=Math.max(bottom,top-4),deep=Math.min(soil,0);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            if(deep>bottom)fillRange(driver,x,z,bottom,deep,Blocks.DEEPSLATE.defaultBlockState());
            int stone=Math.max(bottom,deep);
            if(soil>stone)fillRange(driver,x,z,stone,soil,Blocks.STONE.defaultBlockState());
            fillRange(driver,x,z,soil,top,Blocks.DIRT.defaultBlockState());
        }
    }
    private static void fillRange(ChunkDriver driver,int x,int z,int from,int to,BlockState filler){
        // In 8.3.9 the predicate range API only examines already-buffered cells.
        // Read natural terrain explicitly so untouched air/water is filled too.
        int run=to;
        for(int y=from;y<to;y++){
            if(needsFill(driver.getBlock(x,y,z))){if(run==to)run=y;}
            else if(run!=to){driver.setBlockRange(x,run,z,y,filler);run=to;}
        }
        if(run!=to)driver.setBlockRange(x,run,z,to,filler);
    }
}
