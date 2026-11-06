package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.DynamicNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Per terrain-feature queue; never expose machine NBT against unflushed terrain. */
public final class ImportedBlockEntities {
    private record Entry(BlockState state,CompoundTag tag) {}
    public static final ImportedBlockEntities ROAD = new ImportedBlockEntities();
    public static final ImportedBlockEntities RAILWAY = new ImportedBlockEntities();
    private final Map<ChunkAccess,Map<BlockPos,Entry>> pending=new WeakHashMap<>();
    public synchronized boolean queue(ChunkAccess chunk,BlockPos pos,BlockState state,CompoundTag carrier) {
        if(carrier==null||!carrier.contains(DynamicNbt.BE_KEY))return false;
        try {
            if(!chunk.getPos().equals(new net.minecraft.world.level.ChunkPos(pos)))
                throw new IllegalArgumentException("machine outside generating chunk: "+pos);
            var tag=ImportedBlockEntityNbt.prepare(DynamicStructurePlacer.restoreBlockEntity(carrier,pos),pos,state);
            pending.computeIfAbsent(chunk,c->new LinkedHashMap<>()).put(pos.immutable(),new Entry(state,tag));
            return true;
        } catch(Exception e) {
            LoggerFactory.getLogger("CAF-LC-Dynamic").warn("[CAF-LC] Cannot queue imported machine at {}",pos,e);
            // A broken carrier must not fall through to LC as an id-less machine.
            return true;
        }
    }
    public synchronized void queueResolved(ChunkAccess chunk,BlockPos pos,BlockState state,CompoundTag tag) {
        if(tag==null)return;
        if(!chunk.getPos().equals(new net.minecraft.world.level.ChunkPos(pos)))
            throw new IllegalArgumentException("block entity outside generating chunk: "+pos);
        pending.computeIfAbsent(chunk,c->new LinkedHashMap<>()).put(pos.immutable(),
                new Entry(state,ImportedBlockEntityNbt.prepare(tag,pos,state)));
    }
    public synchronized void flush(ChunkAccess chunk) {
        var entries=pending.remove(chunk);if(entries==null)return;
        for(var item:entries.entrySet()) {
            var pos=item.getKey();var entry=item.getValue();
            // Later carving/overlays may deliberately remove a machine. Do not leave ghost NBT.
            if(chunk.getBlockState(pos).getBlock()!=entry.state().getBlock())continue;
            chunk.setBlockEntityNbt(entry.tag());
        }
    }
}
