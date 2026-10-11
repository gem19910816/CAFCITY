package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.DynamicNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import java.io.*;
import java.util.*;

/** Rotate only coordinates with known semantics; inventories, UUIDs and machine settings stay typed. */
public final class RotationNbt {
    private RotationNbt() {}
    public static Rotation rotation(int turn) {return switch(Math.floorMod(turn,4)){
        case 1->Rotation.CLOCKWISE_90;case 2->Rotation.CLOCKWISE_180;case 3->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;};}
    public static String encode(CompoundTag tag) {
        try {var bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));return Base64.getEncoder().encodeToString(bytes.toByteArray());}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static Tag number(Tag type,double value) {
        return switch(type.getId()){
            case Tag.TAG_BYTE->ByteTag.valueOf((byte)value);case Tag.TAG_SHORT->ShortTag.valueOf((short)value);
            case Tag.TAG_INT->IntTag.valueOf((int)value);case Tag.TAG_LONG->LongTag.valueOf((long)value);
            case Tag.TAG_FLOAT->FloatTag.valueOf((float)value);default->DoubleTag.valueOf(value);};
    }
    private static double[] turn(double x,double z,double offset,int turns) {
        for(int i=0;i<Math.floorMod(turns,4);i++){double old=x;x=offset-z;z=old;}
        return new double[]{x,z};
    }
    public static void position(CompoundTag tag,String key,double offset,int turns) {
        Tag value=tag.get(key);if(value==null)return;
        if(value instanceof IntArrayTag a&&a.getAsIntArray().length==3){
            int[] p=a.getAsIntArray();var r=turn(p[0],p[2],offset,turns);tag.putIntArray(key,new int[]{(int)r[0],p[1],(int)r[1]});
        } else if(value instanceof LongTag l){
            var p=BlockPos.of(l.getAsLong());var r=turn(p.getX(),p.getZ(),offset,turns);
            tag.putLong(key,BlockPos.asLong((int)r[0],p.getY(),(int)r[1]));
        } else if(value instanceof ListTag l&&l.size()==3&&l.get(0) instanceof NumericTag){
            var r=turn(((NumericTag)l.get(0)).getAsDouble(),((NumericTag)l.get(2)).getAsDouble(),offset,turns);
            var result=l.copy();result.set(0,number(l.get(0),r[0]));result.set(2,number(l.get(2),r[1]));tag.put(key,result);
        } else if(value instanceof CompoundTag p&&p.contains("X")&&p.contains("Z")){
            var r=turn(p.getInt("X"),p.getInt("Z"),offset,turns);var result=p.copy();
            result.putInt("X",(int)r[0]);result.putInt("Z",(int)r[1]);tag.put(key,result);
        }
    }
    private static void glueBounds(CompoundTag tag,double offset,int turns){
        var from=tag.getList("From",Tag.TAG_DOUBLE);var to=tag.getList("To",Tag.TAG_DOUBLE);
        if(from.size()!=3||to.size()!=3)return;
        var a=turn(from.getDouble(0),from.getDouble(2),offset,turns);
        var b=turn(to.getDouble(0),to.getDouble(2),offset,turns);
        var min=from.copy();var max=to.copy();
        min.set(0,DoubleTag.valueOf(Math.min(a[0],b[0])));min.set(2,DoubleTag.valueOf(Math.min(a[1],b[1])));
        max.set(0,DoubleTag.valueOf(Math.max(a[0],b[0])));max.set(2,DoubleTag.valueOf(Math.max(a[1],b[1])));
        tag.put("From",min);tag.put("To",max);
    }
    private static void direction(CompoundTag tag,String key,int turns,boolean twoD) {
        Tag value=tag.get(key);
        if(value instanceof StringTag s){
            Direction d=Direction.byName(s.getAsString().toLowerCase(Locale.ROOT));if(d==null)return;
            String name=rotation(turns).rotate(d).getName();
            tag.putString(key,s.getAsString().equals(s.getAsString().toUpperCase(Locale.ROOT))?name.toUpperCase(Locale.ROOT):name);
        } else if(value instanceof NumericTag n){
            Direction d=twoD?Direction.from2DDataValue(n.getAsInt()):Direction.from3DDataValue(n.getAsInt());
            d=rotation(turns).rotate(d);tag.put(key,number(value,twoD?d.get2DDataValue():d.get3DDataValue()));
        }
    }
    private static void sides(CompoundTag tag,int turns){
        var moved=new LinkedHashMap<String,Tag>();
        for(String side:List.of("north","east","south","west"))if(tag.contains(side))
            moved.put(rotation(turns).rotate(Direction.byName(side)).getName(),tag.get(side));
        for(String side:List.of("north","east","south","west"))tag.remove(side);
        moved.forEach(tag::put);
    }
    public static CompoundTag state(CompoundTag original,int turns) {
        var result=original.copy();var properties=result.getCompound("Properties");
        direction(properties,"facing",turns,false);direction(properties,"horizontal_facing",turns,false);
        sides(properties,turns);
        for(String axis:List.of("axis","horizontal_axis"))if((turns&1)!=0&&properties.contains(axis)){
            String old=properties.getString(axis);if(old.equals("x")||old.equals("z"))properties.putString(axis,old.equals("x")?"z":"x");
        }
        if(properties.contains("rotation"))try{properties.putString("rotation",String.valueOf(Math.floorMod(Integer.parseInt(properties.getString("rotation"))+turns*4,16)));}catch(NumberFormatException ignored){}
        // Installed blocks define their own rotation (rail shapes, Create orientation enums, etc.).
        var id=ResourceLocation.tryParse(original.getString("Name"));
        if(id!=null&&BuiltInRegistries.BLOCK.containsKey(id)){
            var rotated=NbtUtils.writeBlockState(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),original).rotate(rotation(turns)));
            var known=rotated.getCompound("Properties");for(String key:known.getAllKeys())properties.put(key,known.get(key));
        }
        if(!properties.isEmpty())result.put("Properties",properties);
        return result;
    }
    private static void blockEntity(CompoundTag tag,int turns) {
        for(String key:List.of("Source","Controller","MirrorParent","LastKnownPos","LastKnownPosition","EnginePos"))position(tag,key,0,turns);
        for(Tag child:tag.getList("MirrorChildren",Tag.TAG_COMPOUND))position((CompoundTag)child,"Pos",0,turns);
        if(tag.getString("id").equals("create:table_cloth"))direction(tag,"Facing",turns,true);
        for(String side:List.of("north","east","south","west","up","down")){
            var location=tag.getCompound(side).getCompound("OpenEnd").getCompound("Location");position(location,"Pos",0,turns);
        }
        sides(tag,turns);
        if(tag.contains("Material",Tag.TAG_COMPOUND))tag.put("Material",state(tag.getCompound("Material"),turns));
    }
    public static CompoundTag paletteTag(CompoundTag source,int turns) {
        if(source==null)return null;var tag=source.copy();
        if(tag.contains(DynamicNbt.BE_KEY))try {
            var data=DynamicStructurePlacer.decode(tag.getString(DynamicNbt.BE_KEY));blockEntity(data,turns);
            tag.putString(DynamicNbt.BE_KEY,encode(data));
        }catch(IOException e){throw new UncheckedIOException(e);}
        else blockEntity(tag,turns);
        return tag;
    }
    /** Bounds of local block geometry rotate about its (0.5, *, 0.5) centre. */
    private static void bounds(CompoundTag tag,String key,int turns){
        var box=tag.getList(key,Tag.TAG_FLOAT);if(box.size()!=6)return;
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(int xi:List.of(0,3))for(int zi:List.of(2,5)){
            var p=turn(box.getFloat(xi),box.getFloat(zi),1,turns);minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);minZ=Math.min(minZ,p[1]);maxZ=Math.max(maxZ,p[1]);
        }
        var result=box.copy();result.set(0,FloatTag.valueOf((float)minX));result.set(2,FloatTag.valueOf((float)minZ));
        result.set(3,FloatTag.valueOf((float)maxX));result.set(5,FloatTag.valueOf((float)maxZ));tag.put(key,result);
    }
    public static void entity(CompoundTag tag,int turns){
        boolean controlled=tag.contains("Contraption",Tag.TAG_COMPOUND);
        position(tag,"Pos",controlled?15:16,turns);position(tag,"Motion",0,turns);position(tag,"ControllerRelative",0,turns);
        if(tag.contains("TileX")&&tag.contains("TileZ")){
            var p=turn(tag.getInt("TileX"),tag.getInt("TileZ"),15,turns);tag.putInt("TileX",(int)p[0]);tag.putInt("TileZ",(int)p[1]);
        }
        direction(tag,"Facing",turns,false);direction(tag,"facing",turns,tag.getString("id").equals("minecraft:painting"));
        if(tag.getString("id").equals("create:super_glue"))glueBounds(tag,0,turns);
        var yaw=tag.getList("Rotation",Tag.TAG_FLOAT);if(yaw.size()==2){var result=yaw.copy();result.set(0,FloatTag.valueOf(yaw.getFloat(0)+turns*90));tag.put("Rotation",result);}
        if(controlled){
            String axis=tag.getString("Axis");
            if(axis.equals("X")||axis.equals("Z")){
                double[] r=turn(axis.equals("X")?1:0,axis.equals("Z")?1:0,0,turns);
                tag.putString("Axis",r[0]==0?"Z":"X");
                if(r[0]+r[1]<0&&tag.contains("Angle"))tag.putFloat("Angle",-tag.getFloat("Angle"));
            }
            var c=tag.getCompound("Contraption");position(c,"Anchor",15,turns);direction(c,"Facing",turns,false);
            var blocks=c.getCompound("Blocks");
            for(Tag value:blocks.getList("BlockList",Tag.TAG_COMPOUND)){
                var block=(CompoundTag)value;position(block,"Pos",0,turns);
                if(block.contains("Data",Tag.TAG_COMPOUND))blockEntity(block.getCompound("Data"),turns);
                if(block.contains("UpdateTag",Tag.TAG_COMPOUND))blockEntity(block.getCompound("UpdateTag"),turns);
            }
            var palette=blocks.getList("Palette",Tag.TAG_COMPOUND);for(int i=0;i<palette.size();i++)palette.set(i,state(palette.getCompound(i),turns));
            for(Tag value:c.getList("Actors",Tag.TAG_COMPOUND)){
                var actor=(CompoundTag)value;position(actor,"Pos",0,turns);position(actor,"Position",16,turns);
                position(actor,"Motion",0,turns);position(actor,"RelativeMotion",0,turns);position(actor.getCompound("Data"),"lastContact",15,turns);
            }
            for(String key:List.of("Seats","Interactors"))for(Tag value:c.getList(key,Tag.TAG_COMPOUND))position((CompoundTag)value,"Pos",0,turns);
            for(Tag value:c.getList("CapturedMultiblocks",Tag.TAG_COMPOUND)){
                var multi=(CompoundTag)value;position(multi,"Controller",0,turns);
                for(Tag part:multi.getList("Parts",Tag.TAG_COMPOUND))position((CompoundTag)part,"Pos",0,turns);
            }
            for(Tag value:c.getList("SubContraptions",Tag.TAG_COMPOUND)){
                var location=((CompoundTag)value).getCompound("Location");position(location,"Pos",0,turns);direction(location,"Face",turns,false);
            }
            var column=c.getCompound("Column");if(column.contains("X")&&column.contains("Z")){
                var p=turn(column.getInt("X"),column.getInt("Z"),0,turns);column.putInt("X",(int)p[0]);column.putInt("Z",(int)p[1]);direction(column,"Side",turns,false);
            }
            bounds(c,"BoundsFront",turns);
            for(Tag value:c.getList("Superglue",Tag.TAG_COMPOUND))glueBounds((CompoundTag)value,1,turns);
        }
        for(Tag value:tag.getList("Passengers",Tag.TAG_COMPOUND))entity((CompoundTag)value,turns);
    }
}
