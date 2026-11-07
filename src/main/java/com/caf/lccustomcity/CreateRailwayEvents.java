package com.caf.lccustomcity;
import com.caf.lccustomcity.convert.RailwayAssetLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import java.util.concurrent.ConcurrentLinkedQueue;

/** No world access in ChunkEvent.Load. Bounded scan on server thread, no chunk forcing. */
final class CreateRailwayEvents {
    private record Job(ServerLevel level,int x,int z){}
    private static final ConcurrentLinkedQueue<Job> jobs=new ConcurrentLinkedQueue<>();
    static void register(){
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class,e->{
            if(RailwayAssetLoader.active()&&e.getLevel() instanceof ServerLevel level) {
                var p=e.getChunk().getPos();jobs.add(new Job(level,p.x,p.z));
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{
            for(int i=0;i<2;i++){
                Job job=jobs.poll();if(job==null)break;
                if(job.level().getServer()!=e.getServer())continue;
                var chunk=job.level().getChunkSource().getChunkNow(job.x(),job.z());if(chunk==null)continue;
                var track=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track"));
                for(int section=0;section<chunk.getSections().length;section++){
                    var states=chunk.getSections()[section];
                    if(!states.maybeHas(state->state.is(track)))continue;
                    int yBase=chunk.getMinBuildHeight()+section*16;
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                        if(!states.getBlockState(x,y,z).is(track))continue;
                        var pos=new BlockPos(job.x()*16+x,yBase+y,job.z()*16+z);
                        job.level().scheduleTick(pos,track,1);
                    }
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class,e->jobs.clear());
    }
}
