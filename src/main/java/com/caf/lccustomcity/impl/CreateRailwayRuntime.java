package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.RailwayAssetLoader;
import com.simibubi.create.content.trains.track.*;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import java.util.*;

/** Only protochunk buffers and saved tick containers are touched during generation.
 * Create graph mutation is performed later by Create's own server block tick. */
public final class CreateRailwayRuntime {
    public static final ImportedBlockEntities MACHINES = ImportedBlockEntities.RAILWAY;
    private CreateRailwayRuntime() {}
    public static boolean enabled() {return RailwayAssetLoader.active();}
    public static String name(IBuildingPart part) {
        String n=part.getName();int colon=n.indexOf(':');return colon<0?n:n.substring(colon+1);
    }
    public static boolean handles(IBuildingPart part) {return enabled()&&RailwayAssetLoader.names().contains(name(part));}
    public static void finish(ChunkDriver driver, BuildingInfo info, IBuildingPart part, Transform transform,int ox,int oy,int oz) {
        if(!handles(part))return;
        ChunkAccess chunk=driver.getPrimer();
        BlockPos origin=new BlockPos(info.coord.chunkX()*16+ox,oy,info.coord.chunkZ()*16+oz);
        String n=name(part);
        // LC only knows vanilla rails. Explicitly place every Create state using the
        // part's LOCAL palette, and transform axes as well as block coordinates.
        var track=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track"));
        if(!(track instanceof TrackBlock)) throw new IllegalStateException("Create track missing");
        var plan=RailwayAssetLoader.plan(n);
        if(plan==null)return;
        carveAir(driver,n,transform,ox,oy,oz);
        for(var cell:plan) {
            int x=transform.rotateX(cell.x(),cell.z()),z=transform.rotateZ(cell.x(),cell.z());
            TrackShape shape=oriented(cell.shape(),transform);
            BlockState state=track.defaultBlockState().setValue(TrackBlock.SHAPE,shape).setValue(TrackBlock.HAS_BE,cell.turn());
            driver.setBlockRange((origin.getX()+x)&15,oy+cell.y(),(origin.getZ()+z)&15,oy+cell.y()+1,state);
            BlockPos pos=origin.offset(x,cell.y(),z);
            // ProtoChunk persists this tick even if the server stops before first load.
            chunk.getBlockTicks().schedule(new ScheduledTick<>(track,pos,0L,TickPriority.NORMAL,0L));
        }
        if(n.equals("rails_bend")||n.equals("rails_3split")) {
            // A compact T junction fits the original shell. Connections belong to BOTH
            // endpoint BEs and are serialized by Create itself, never custom guessed NBT.
            Map<BlockPos,ListTag> links=new LinkedHashMap<>();
            connect(links,chunk,origin,transform,new int[]{0,1,7},new int[]{8,1,0},new Vec3(1,0,0),new Vec3(0,0,1));
            connect(links,chunk,origin,transform,new int[]{0,1,7},new int[]{8,1,15},new Vec3(1,0,0),new Vec3(0,0,-1));
            if(n.equals("rails_3split")) {
                connect(links,chunk,origin,transform,new int[]{15,1,7},new int[]{8,1,0},new Vec3(-1,0,0),new Vec3(0,0,1));
                connect(links,chunk,origin,transform,new int[]{15,1,7},new int[]{8,1,15},new Vec3(-1,0,0),new Vec3(0,0,-1));
            }
            for(var e:links.entrySet()) {
                BlockPos p=e.getKey();CompoundTag tag=new CompoundTag();tag.putString("id","create:track");
                tag.putInt("x",p.getX());tag.putInt("y",p.getY());tag.putInt("z",p.getZ());tag.put("Connections",e.getValue());
                // The current chunk buffer is not flushed yet; retain until actuallyGenerate.
                BlockState expected=track.defaultBlockState();MACHINES.queueResolved(chunk,p,expected,tag);
            }
        }
    }
    /** LC skips ordinary AIR; explicitly carve only authored AIR, never spaces or props. */
    public static void carveAir(ChunkDriver driver,String name,Transform transform,int ox,int oy,int oz) {
        for(var cell:RailwayAssetLoader.airPlan(name)) {
            int x=ox+transform.rotateX(cell.x(),cell.z()),z=oz+transform.rotateZ(cell.x(),cell.z());
            if(x<0||x>15||z<0||z>15)throw new IllegalArgumentException("Rail air outside generating chunk");
            driver.setBlockRange(x,oy+cell.y(),z,oy+cell.y()+1,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }
    private static TrackShape oriented(String shape,Transform t) {
        TrackShape original=TrackShape.valueOf(shape.toUpperCase(Locale.ROOT));
        List<Vec3> axes=original.getAxes().stream().map(v->vector(v,t)).toList();
        Vec3 normal=vector(original.getNormal(),t);
        for(TrackShape candidate:TrackShape.values()) {
            if(candidate.getAxes().size()!=axes.size()||candidate.getNormal().distanceTo(normal)>1.e-6)continue;
            boolean matches=true;
            for(Vec3 a:axes) if(candidate.getAxes().stream().noneMatch(b->a.distanceTo(b)<1.e-6||a.distanceTo(b.scale(-1))<1.e-6))matches=false;
            if(matches)return candidate;
        }
        throw new IllegalArgumentException("Unsupported track transform "+shape+" "+t);
    }
    public static Vec3 vector(Vec3 v,Transform t) {
        int x0=t.rotateX(0,0),z0=t.rotateZ(0,0);
        int xx=t.rotateX(1,0)-x0,zx=t.rotateZ(1,0)-z0;
        int xz=t.rotateX(0,1)-x0,zz=t.rotateZ(0,1)-z0;
        return new Vec3(xx*v.x+xz*v.z,v.y,zx*v.x+zz*v.z);
    }
    private static BlockPos point(BlockPos o,int[] p,Transform t) {return o.offset(t.rotateX(p[0],p[2]),p[1],t.rotateZ(p[0],p[2]));}
    private static void connect(Map<BlockPos,ListTag> tags,ChunkAccess chunk,BlockPos o,Transform t,int[] a,int[] b,Vec3 va,Vec3 vb) {
        BlockPos pa=point(o,a,t),pb=point(o,b,t);Vec3 ax=vector(va,t),bx=vector(vb,t);
        Vec3 startA=Vec3.atBottomCenterOf(pa).add(ax.scale(.5)),startB=Vec3.atBottomCenterOf(pb).add(bx.scale(.5));
        BezierConnection c=new BezierConnection(Couple.create(pa,pb),Couple.create(startA,startB),Couple.create(ax,bx),
            Couple.create(new Vec3(0,1,0),new Vec3(0,1,0)),true,false,TrackMaterial.ANDESITE);
        tags.computeIfAbsent(pa,p->new ListTag()).add(c.write(pa));
        tags.computeIfAbsent(pb,p->new ListTag()).add(c.secondary().write(pb));
    }
}
