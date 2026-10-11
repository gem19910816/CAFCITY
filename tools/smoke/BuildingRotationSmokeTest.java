import com.caf.lccustomcity.config.BuildingRotation;
import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.impl.*;
import com.caf.lccustomcity.schem.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import mcjty.lostcities.worldgen.lost.regassets.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

/** Geometry, entity ownership, typed machine links and actual LC shared palettes. */
public final class BuildingRotationSmokeTest {
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
    static double[] vector(Tag value){
        if(value instanceof IntArrayTag a){var p=a.getAsIntArray();return new double[]{p[0],p[1],p[2]};}
        if(value instanceof ListTag p&&p.size()==3)return new double[]{((NumericTag)p.get(0)).getAsDouble(),((NumericTag)p.get(1)).getAsDouble(),((NumericTag)p.get(2)).getAsDouble()};
        return new double[]{0,0,0};
    }
    static double[] rotated(double x,double z,double w,double d,int turn){return switch(turn){
        case 1->new double[]{d-z,x};case 2->new double[]{w-x,d-z};case 3->new double[]{z,w-x};default->new double[]{x,z};};}
    public static void main(String[] args)throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        synthetic();
        Path pack=Path.of(args[0]),sources=Path.of(args[1]);
        var data=new HashMap<String,SchematicData>();
        var idFor=LcAssetGenerator.class.getDeclaredMethod("idFor",Path.class,Path.class);idFor.setAccessible(true);
        try(var walk=Files.walk(sources)){
            for(var file:walk.filter(p->p.toString().endsWith(".nbt")).toList())
                data.put((String)idFor.invoke(null,sources,file),SchematicReader.read(file));
        }
        var bounds=new HashMap<String,int[]>();var originals=new HashMap<String,Map<UUID,SchematicEntity>>();
        for(var entry:data.entrySet()){
            bounds.put(entry.getKey(),entry.getValue().nonAirBounds());var byUuid=new HashMap<UUID,SchematicEntity>();
            for(var entity:entry.getValue().entities){var tag=DynamicStructurePlacer.decode(com.caf.lccustomcity.nbt.NbtWriter.encode(entity.nbt));
                if(tag.hasUUID("UUID"))byUuid.put(tag.getUUID("UUID"),entity);}
            originals.put(entry.getKey(),byUuid);
        }
        var totals=new HashMap<String,int[]>();int entities=0,elevators=0,cells=0;
        try(var walk=Files.walk(pack.resolve("data/caf_lc_customcity/lostcities/parts"))){
            for(var file:walk.filter(p->p.toString().endsWith(".json")).toList()){
                var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(!json.has("meta"))continue;
                var re=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
                re.setRegistryName(ResourceLocation.fromNamespaceAndPath(LcAssetGenerator.NS,file.getFileName().toString().replace(".json","")));
                var part=new BuildingPart(re);String cell=part.getMetaString(BuildingRotation.SOURCE_CELL);if(cell==null)continue;
                String id=file.getFileName().toString().replaceFirst("(?:_c[0-9]+_[0-9]+)?_(?:f[0-9]+|top)\\.json$","");
                var d=data.get(id);if(d==null)continue;
                int[] b=bounds.get(id),cv=Arrays.stream(cell.split(",")).mapToInt(Integer::parseInt).toArray();
                int gx=b[0]-(cv[2]*16-(b[3]-b[0]+1))/2,gz=b[2]-(cv[3]*16-(b[5]-b[2]+1))/2;
                var original=originals.get(id);
                for(int turn=0;turn<4;turn++){
                    var oriented=BuildingRotationRuntime.rotate(part,turn);
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++){
                        int rx=BuildingRotation.x(x,z,16,16,turn),rz=BuildingRotation.z(x,z,16,16,turn);
                        check(Arrays.equals(part.getVSlice(x,z),oriented.getVSlice(rx,rz)),"floor geometry "+file+" "+turn);cells++;
                    }
                    var payload=oriented.getMetaString(DynamicNbt.ENTITY_KEY);if(payload==null)continue;
                    var root=DynamicStructurePlacer.decode(payload);int[] source=root.getIntArray("SourcePart");
                    for(Tag value:root.getList("Entities",Tag.TAG_COMPOUND)){
                        var tag=(CompoundTag)value;var entity=original.get(tag.getUUID("UUID"));check(entity!=null,"unknown entity");
                        boolean controlled=tag.contains("Contraption");
                        double[] expected=rotated(entity.x-gx,entity.z-gz,cv[2]*16-(controlled?1:0),cv[3]*16-(controlled?1:0),turn);
                        var pos=tag.getList("Pos",Tag.TAG_DOUBLE);
                        double actualX=pos.getDouble(0)+(turn==0?source[0]-gx:source[0]);
                        double actualZ=pos.getDouble(2)+(turn==0?source[2]-gz:source[2]);
                        check(Math.abs(actualX-expected[0])<1e-6&&Math.abs(actualZ-expected[1])<1e-6,"whole entity geometry "+file+" turn="+turn);
                        check(pos.getDouble(0)>=0&&pos.getDouble(0)<16&&pos.getDouble(2)>=0&&pos.getDouble(2)<16,"entity assigned to wrong chunk "+file);
                        totals.computeIfAbsent(id,k->new int[4])[turn]++;entities++;
                        if(controlled&&tag.getCompound("Contraption").getString("Type").equals("create:elevator")){
                            elevators++;
                            var controller=vector(tag.get("ControllerRelative"));
                            var oldController=DynamicNbt.vector(entity.nbt.get("ControllerRelative"));
                            check(oldController!=null,"elevator controller vector missing");
                            int cx=(int)Math.floor(entity.x+oldController[0]);
                            int cy=(int)Math.floor(entity.y+oldController[1]);
                            int cz=(int)Math.floor(entity.z+oldController[2]);
                            check(d.blockAt(cx,cy,cz)!=null&&d.blockAt(cx,cy,cz).startsWith("create:elevator_pulley"),"source controller must resolve to pulley");
                            var expectedController=rotated(cx-gx,cz-gz,cv[2]*16-1,cv[3]*16-1,turn);
                            check(Math.abs(actualX+controller[0]-expectedController[0])<1e-6
                                &&Math.abs(actualZ+controller[2]-expectedController[1])<1e-6,"rotated controller must resolve to rotated pulley");
                        }
                    }
                }
            }
        }
        for(var entry:data.entrySet())if(entry.getValue().hasEntities()){
            int[] count=totals.get(entry.getKey());check(count!=null,"missing all entities "+entry.getKey());
            for(int turn=0;turn<4;turn++)check(count[turn]==entry.getValue().entities.size(),"entity loss "+entry.getKey()+" turn="+turn+" "+count[turn]+"/"+entry.getValue().entities.size());
        }
        check(elevators==16,"four actual elevators must survive all four directions");
        System.out.println("Rotation: "+cells+" floor columns, "+entities+" entity placements, "+elevators+" elevator placements; all four directions and complete source counts");
        BuildingRotationRuntime.clear();System.out.println("Building rotation: PASS");
    }
    static void synthetic()throws Exception{
        var be=new CompoundTag();be.putString("id","create:powered_shaft");be.putFloat("Speed",-32);be.putInt("Direction",-1);
        be.putIntArray("EnginePos",new int[]{-2,0,0});be.putIntArray("Source",new int[]{3,1,-4});
        var carrier=new CompoundTag();carrier.putString(DynamicNbt.BE_KEY,RotationNbt.encode(be));
        for(int turn=0;turn<4;turn++){
            var result=DynamicStructurePlacer.decode(RotationNbt.paletteTag(carrier,turn).getString(DynamicNbt.BE_KEY));
            check(result.getFloat("Speed")==-32&&result.getInt("Direction")==-1,"kinetic settings changed");
            var expected=rotated(-2,0,0,0,turn);var engine=result.getIntArray("EnginePos");
            check(engine[0]==expected[0]&&engine[2]==expected[1],"relative engine link");
        }
        check(be.getIntArray("EnginePos")[0]==-2,"mutated source NBT");
        var painting=new CompoundTag();painting.putString("id","minecraft:painting");painting.putByte("facing",(byte)0);
        RotationNbt.entity(painting,1);check(painting.getByte("facing")==1,"painting uses horizontal direction encoding");
        var state=new CompoundTag();state.putString("Name","create:shaft");var props=new CompoundTag();props.putString("axis","z");state.put("Properties",props);
        check(RotationNbt.state(state,1).getCompound("Properties").getString("axis").equals("x"),"absent Create state properties lost");
        String raw="{\"xsize\":16,\"zsize\":16,\"slices\":[["+String.join(",",Collections.nCopies(16,"\""+"a".repeat(16)+"\""))+" ]],\"palette\":{\"palette\":[{\"char\":\"a\",\"block\":\"minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]\"}]}}";
        var re=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(raw)).getOrThrow();re.setRegistryName(ResourceLocation.parse("caf_lc_customcity:rotation_fixture"));
        var part=new BuildingPart(re);var source=part.getLocalPalette(null);
        for(int turn=1;turn<4;turn++){
            var oriented=BuildingRotationRuntime.rotate(part,turn);var actual=new CompiledPalette(oriented.getLocalPalette(null)).get('a');
            check(actual.equals(new CompiledPalette(source).get('a').rotate(RotationNbt.rotation(turn))),"actual LC palette state rotation");
            check(oriented==BuildingRotationRuntime.rotate(part,turn),"part cache");
        }
        check(new CompiledPalette(source).get('a').getValue(net.minecraft.world.level.block.StairBlock.FACING)==net.minecraft.core.Direction.SOUTH,"mutated source palette");
    }
}
