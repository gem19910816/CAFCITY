package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.DynamicStructureConfig;
import com.caf.lccustomcity.convert.DynamicNbt;
import com.caf.lccustomcity.nbt.*;
import mcjty.lostcities.worldgen.lost.cityassets.IBuildingPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.*;
import org.slf4j.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Store raw entity NBT in the generating chunk; vanilla loads/ticks it after generation. */
public final class DynamicStructurePlacer {
    private static final Logger LOG=LoggerFactory.getLogger("CAF-LC-Dynamic");
    private static final Set<String> WARNED=ConcurrentHashMap.newKeySet();
    private DynamicStructurePlacer() {}
    public static CompoundTag decode(String value) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value))));
    }
    private static Map<String,Object> read(String value) throws IOException {
        return NbtReader.asCompound(NbtReader.readRoot(new DataInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(value)))));
    }
    private static void uuids(Object value,String salt) {
        if(value instanceof Map<?,?> raw) {
            @SuppressWarnings("unchecked") var m=(Map<String,Object>)raw;
            for(var e:m.entrySet()) {
                if((e.getKey().equals("UUID") || e.getKey().equals("ContraptionUUID")) && e.getValue() instanceof int[] a && a.length==4) {
                    UUID u=UUID.nameUUIDFromBytes((salt+Arrays.toString(a)).getBytes(StandardCharsets.UTF_8));
                    e.setValue(new int[]{(int)(u.getMostSignificantBits()>>>32),(int)u.getMostSignificantBits(),
                            (int)(u.getLeastSignificantBits()>>>32),(int)u.getLeastSignificantBits()});
                } else uuids(e.getValue(),salt);
            }
        } else if(value instanceof List<?> l)for(Object v:l)uuids(v,salt);
    }
    public static List<CompoundTag> prepare(String payload,int x,int y,int z,String world) throws IOException {
        var root=read(payload); int[] source=(int[])root.get("SourcePart");
        String salt=world+":"+(x-source[0])+":"+(y-source[1])+":"+(z-source[2])+":"+root.getOrDefault("Turns",0)+":";
        List<CompoundTag> result=new ArrayList<>();
        int index=0;
        for(Object v:NbtReader.list(root,"Entities")) {
            var tag=NbtReader.asCompound(v); DynamicNbt.moveEntity(tag,x,y,z); uuids(tag,salt);
            CompoundTag entity=decode(NbtWriter.encode(tag));
            if(!entity.hasUUID("UUID"))entity.putUUID("UUID",UUID.nameUUIDFromBytes(
                    (salt+payload+":"+index).getBytes(StandardCharsets.UTF_8)));
            result.add(entity);index++;
        }
        return result;
    }
    public static void place(WorldGenLevel world,ChunkAccess chunk,IBuildingPart part,int x,int y,int z) {
        if(!DynamicStructureConfig.enabled())return;
        try {
            String value=part.getMetaString(DynamicNbt.ENTITY_KEY); if(value==null)return;
            if(!(chunk instanceof ProtoChunk proto)) {
                warn("chunk","Entity import requires a generating ProtoChunk");return;
            }
            String salt=world.getLevel().dimension().location()+":"+world.getSeed();
            for(CompoundTag tag:prepare(value,x,y,z,salt)) {
                String id=tag.getString("id");
                if(!BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id))) {
                    warn(id,"Missing entity type "+id);continue;
                }
                var p=tag.getList("Pos",Tag.TAG_DOUBLE);
                if(((int)Math.floor(p.getDouble(0))>>4)!=chunk.getPos().x ||
                        ((int)Math.floor(p.getDouble(2))>>4)!=chunk.getPos().z) {
                    warn("bounds","Entity outside its owning chunk: "+id);continue;
                }
                append(proto,tag);
            }
        } catch(Exception e) {warn(part.getName(),"Entity import failed for "+part.getName()+": "+e);}
    }
    public static boolean append(ProtoChunk chunk,CompoundTag tag) {
        UUID uuid=tag.getUUID("UUID");
        if(chunk.getEntities().stream().anyMatch(t->t.hasUUID("UUID") && t.getUUID("UUID").equals(uuid)))return false;
        chunk.addEntity(tag);return true;
    }
    public static CompoundTag restoreBlockEntity(CompoundTag generated,BlockPos pos) throws IOException {
        String value=generated.getString(DynamicNbt.BE_KEY); if(value.isEmpty())return generated;
        var tag=read(value);
        if(!generated.getBoolean("caf_create_nbt_local_v1"))
            DynamicNbt.moveBlockEntity(tag,pos.getX(),pos.getY(),pos.getZ());
        tag.put("x",pos.getX());tag.put("y",pos.getY());tag.put("z",pos.getZ());
        return decode(NbtWriter.encode(tag));
    }
    public static void restore(WorldGenLevel world,BlockPos pos) {
        try {
            ChunkAccess chunk=world.getChunk(pos); CompoundTag tag=chunk.getBlockEntityNbt(pos);
            if(tag!=null && tag.contains(DynamicNbt.BE_KEY))chunk.setBlockEntityNbt(restoreBlockEntity(tag,pos));
        } catch(Exception e) {warn("blockEntity","Create block entity import failed: "+e);}
    }
    private static void warn(String key,String message) {if(WARNED.add(key))LOG.warn("[CAF-LC] {}",message);}
}
