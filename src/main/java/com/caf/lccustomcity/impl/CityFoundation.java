package com.caf.lccustomcity.impl;

import mcjty.lostcities.worldgen.ChunkDriver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Fill terrain voids before buildings, basements and transit carve their own spaces. */
public final class CityFoundation {
    private CityFoundation(){}
    public static boolean needsFill(BlockState state){
        // Water and lava are terrain fluids, including source and flowing states.
        // Keep waterlogged solid blocks intact; they are part of authored terrain.
        return state.isAir()||state.is(Blocks.WATER)||state.is(Blocks.LAVA)
                ||(!state.getFluidState().isEmpty()&&!state.blocksMotion());
    }
    /** Remove exposed liquid blocks at/above the city plane before roads/buildings. */
    public static void clearSurfaceFluids(ChunkDriver driver,int ground){
        var chunk=driver.getPrimer();if(chunk==null)return;
        int from=Math.max(ground,chunk.getMinBuildHeight()),to=chunk.getMaxBuildHeight();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int run=-1;
            for(int y=from;y<to;y++){
                BlockState state=driver.getBlock(x,y,z);
                boolean liquid=state.is(Blocks.WATER)||state.is(Blocks.LAVA);
                if(liquid){if(run<0)run=y;}
                else if(run>=0){driver.setBlockRangeToAir(x,run,z,y);run=-1;}
            }
            if(run>=0)driver.setBlockRangeToAir(x,run,z,to);
        }
    }

    /**
     * Remove the natural terrain at and above the city plane before Lost Cities
     * writes a building or an authored road.  Road NBT files are sparse: an
     * omitted/air cell means that the preset owns the empty space.  Leaving the
     * generated terrain in that space is what produced grass/dirt patches and
     * terrain columns poking through otherwise complete road plots.
     */
    public static void clearAbove(ChunkDriver driver,int ground){
        var chunk=driver.getPrimer();if(chunk==null)return;
        int from=Math.max(ground,chunk.getMinBuildHeight()),to=chunk.getMaxBuildHeight();
        if(from>=to)return;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)
            driver.setBlockRangeToAir(x,from,z,to);
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
