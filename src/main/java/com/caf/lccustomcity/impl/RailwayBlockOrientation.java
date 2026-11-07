package com.caf.lccustomcity.impl;

import mcjty.lostcities.worldgen.lost.Transform;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Native mirror/rotation preserves handed states; facing fallback supports mod decorations. */
public final class RailwayBlockOrientation {
    private RailwayBlockOrientation() {}
    /** Stair shafts use inherited T palette entries, not our template-local palette. */
    public static void repairEntranceTorches(mcjty.lostcities.worldgen.ChunkDriver driver,
        mcjty.lostcities.worldgen.lost.BuildingInfo info,mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart part,
        Transform t,int ox,int oy,int oz) {
        String name=part.getName();if(name.contains(":"))name=name.substring(name.indexOf(':')+1);
        if(!name.equals("station_staircase")&&!name.equals("station_staircase_surface"))return;
        for(int x=0;x<part.getXSize();x++)for(int z=0;z<part.getZSize();z++) {
            char[] column=part.getVSlice(x,z);if(column==null)continue;
            for(int y=0;y<column.length;y++)if(column[y]=='T') {
                int px=ox+t.rotateX(x,z),pz=oz+t.rotateZ(x,z),py=oy+y;
                if(px<0||px>15||pz<0||pz>15)continue;
                var old=driver.getBlock(px,py,pz);
                if(!(old.getBlock() instanceof net.minecraft.world.level.block.TorchBlock))continue;
                BlockState chosen=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                for(Direction facing:Direction.Plane.HORIZONTAL) {
                    int sx=px-facing.getStepX(),sz=pz-facing.getStepZ();
                    if(sx<0||sx>15||sz<0||sz>15)continue;
                    var backing=driver.getBlock(sx,py,sz);
                    if(backing.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,facing)) {
                        chosen=net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING,facing);break;
                    }
                }
                driver.setBlockRange(px,py,pz,py+1,chosen);
                var absolute=new net.minecraft.core.BlockPos(info.coord.chunkX()*16+px,py,info.coord.chunkZ()*16+pz);
                info.getTorchTodo().remove(absolute);
            }
        }
    }
    public static BlockState transform(BlockState original, Transform t) {
        BlockState result=switch(t) {
            case ROTATE_NONE -> original;
            case ROTATE_90 -> original.rotate(Rotation.CLOCKWISE_90);
            case ROTATE_180 -> original.rotate(Rotation.CLOCKWISE_180);
            case ROTATE_270 -> original.rotate(Rotation.COUNTERCLOCKWISE_90);
            case MIRROR_X -> original.mirror(Mirror.FRONT_BACK);
            case MIRROR_Z -> original.mirror(Mirror.LEFT_RIGHT);
            case MIRROR_90_X -> original.mirror(Mirror.FRONT_BACK).rotate(Rotation.COUNTERCLOCKWISE_90);
        };
        for(Property<?> property:original.getProperties()) {
            if(property.getName().equals("facing") && original.getValue(property) instanceof Direction direction)
                result=withFacing(result,property,orient(direction,t));
        }
        return result;
    }
    public static Direction orient(Direction d,Transform t) {
        if(d.getAxis()==Direction.Axis.Y)return d;
        int dx=t.rotateX(d.getStepX(),d.getStepZ())-t.rotateX(0,0);
        int dz=t.rotateZ(d.getStepX(),d.getStepZ())-t.rotateZ(0,0);
        return dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH;
    }
    private static <T extends Comparable<T>> BlockState withFacing(BlockState s,Property<T> p,Direction d) {
        return p.getValue(d.getName()).map(v->s.setValue(p,v)).orElse(s);
    }
}
