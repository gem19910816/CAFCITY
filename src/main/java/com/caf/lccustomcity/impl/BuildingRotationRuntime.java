package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingRotation;
import com.caf.lccustomcity.convert.DynamicNbt;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import java.io.*;
import java.util.*;

/** Rotate each shared source floor lazily; rotated multi assets choose the matching source cell. */
public final class BuildingRotationRuntime {
    private record PartKey(IBuildingPart part,int turn) {}
    private record PaletteKey(Palette palette,int turn) {}
    private static final Map<PartKey,IBuildingPart> PARTS=lru(512);
    private static final Map<PaletteKey,Palette> PALETTES=lru(128);
    private BuildingRotationRuntime() {}
    private static <K,V> Map<K,V> lru(int limit){return new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<K,V> e){return size()>limit;}};}
    public static synchronized void clear(){PARTS.clear();PALETTES.clear();}
    public static IBuildingPart orient(IBuildingPart part,BuildingInfo info,IDimensionInfo provider){
        if(part.getMetaString(BuildingRotation.SOURCE_CELL)==null)return part;
        var cell=CityLayout.cell(info.coord,provider,info.profile);
        return cell==null||cell.placement()==null?part:rotate(part,cell.placement().building().turns());
    }
    public static synchronized IBuildingPart rotate(IBuildingPart source,int turn){
        turn=Math.floorMod(turn,4);if(turn==0||source instanceof OrientedPart)return source;
        var key=new PartKey(source,turn);var result=PARTS.get(key);
        if(result==null){result=new OrientedPart(source,turn);PARTS.put(key,result);}return result;
    }
    private static synchronized Palette palette(Palette source,int turn){
        if(source==null)return null;
        var key=new PaletteKey(source,turn);var result=PALETTES.get(key);if(result!=null)return result;
        result=new Palette("caf_rotated_"+source.getId().getPath()+"_r"+turn);
        for(var entry:source.getPalette().entrySet()){
            var pe=entry.getValue();Object blocks=pe.blocks();
            if(blocks instanceof BlockState state)blocks=state.rotate(RotationNbt.rotation(turn));
            else if(blocks instanceof List<?> list){
                var rotated=new ArrayList<Pair<Integer,BlockState>>();
                for(Object value:list){var pair=(Pair<?,?>)value;rotated.add(Pair.of((Integer)pair.getLeft(),((BlockState)pair.getRight()).rotate(RotationNbt.rotation(turn))));}
                blocks=rotated;
            }
            var info=pe.info();result.getPalette().put(entry.getKey(),new Palette.PE(blocks,
                    info==null?null:new Palette.Info(info.mobId(),info.loot(),info.isTorch(),RotationNbt.paletteTag(info.tag(),turn))));
        }
        for(var entry:source.getDamaged().entrySet())result.getDamaged().put(entry.getKey().rotate(RotationNbt.rotation(turn)),entry.getValue().rotate(RotationNbt.rotation(turn)));
        PALETTES.put(key,result);return result;
    }
    private static final class OrientedPart implements IBuildingPart {
        final IBuildingPart source;final int turn;final char[][] columns;final String entities;
        OrientedPart(IBuildingPart source,int turn){
            this.source=source;this.turn=turn;
            if(source.getXSize()!=16||source.getZSize()!=16)throw new IllegalArgumentException("Imported floor must be 16x16");
            columns=new char[256][];
            for(int x=0;x<16;x++)for(int z=0;z<16;z++){
                int rx=BuildingRotation.x(x,z,16,16,turn),rz=BuildingRotation.z(x,z,16,16,turn);
                columns[rz*16+rx]=source.getVSlice(x,z);
            }
            String payload=source.getMetaString(DynamicNbt.ENTITY_KEY+"_r"+turn);
            if(payload==null&&source.getMetaString(BuildingRotation.SOURCE_CELL)==null)payload=source.getMetaString(DynamicNbt.ENTITY_KEY);
            if(payload==null)entities=null;
            else try {
                var root=DynamicStructurePlacer.decode(payload);
                for(Tag entity:root.getList("Entities",Tag.TAG_COMPOUND))RotationNbt.entity((CompoundTag)entity,turn);
                String cell=source.getMetaString(BuildingRotation.SOURCE_CELL);
                if(cell!=null){
                    var values=Arrays.stream(cell.split(",")).mapToInt(Integer::parseInt).toArray();
                    int[] original=root.getIntArray("SourcePart");
                    root.putIntArray("SourcePart",new int[]{16*BuildingRotation.x(values[0],values[1],values[2],values[3],turn),original[1],
                            16*BuildingRotation.z(values[0],values[1],values[2],values[3],turn)});
                }
                root.putInt("Turns",turn);entities=RotationNbt.encode(root);
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
        @Override public String getName(){return source.getName()+"_r"+turn;}
        @Override public Character getMetaChar(String key){return source.getMetaChar(key);}
        @Override public Integer getMetaInteger(String key){return source.getMetaInteger(key);}
        @Override public boolean getMetaBoolean(String key){return source.getMetaBoolean(key);}
        @Override public Float getMetaFloat(String key){return source.getMetaFloat(key);}
        @Override public String getMetaString(String key){return key.equals(DynamicNbt.ENTITY_KEY)?entities:source.getMetaString(key);}
        @Override public int getSliceCount(){return source.getSliceCount();}
        @Override public int getXSize(){return 16;}
        @Override public int getZSize(){return 16;}
        @Override public char[][] getVslices(){return columns;}
        @Override public char[] getVSlice(int x,int z){return columns[z*16+x];}
        @Override public Palette getLocalPalette(CommonLevelAccessor world){return palette(source.getLocalPalette(world),turn);}
    }
}
