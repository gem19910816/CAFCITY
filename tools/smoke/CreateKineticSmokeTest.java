import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.schem.*;
import com.caf.lccustomcity.nbt.NbtWriter;
import com.caf.lccustomcity.impl.DynamicStructurePlacer;
import com.caf.lccustomcity.impl.ImportedBlockEntities;
import com.caf.lccustomcity.mixin.DynamicStructureMixin;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

// Real source payloads, LC palette codecs, typed NBT, buffered placement and pending chunk data.
public final class CreateKineticSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static int checked,unlocated,speeds,directions;
    static final Set<String> MOVED=Set.of("x","y","z","Source","Controller","MirrorParent","MirrorChildren",
        "LastKnownPos","LastKnownPosition","Network","NeedsSpeedUpdate","north","east","south","west","up","down");
    public static void main(String[] args)throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        synthetic();placement();
        Path root=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        var out=LcAssetGenerator.generate(root,org.slf4j.LoggerFactory.getLogger("CreateKineticTest"));
        check(out.errors.isEmpty(),"conversion errors "+out.errors);
        var palettes=new HashMap<String,JsonObject>();
        for(var e:out.files.entrySet())if(e.getKey().contains("/palettes/")){
            var json=JsonParser.parseString(e.getValue()).getAsJsonObject();
            mcjty.lostcities.worldgen.lost.regassets.PaletteRE.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
            for(var row:json.getAsJsonArray("palette")){
                var item=row.getAsJsonObject();if(!item.has("tag"))continue;
                var tag=item.getAsJsonObject("tag");
                if(tag.has(DynamicNbt.BE_KEY))palettes.put(tag.get(DynamicNbt.BE_KEY).getAsString()+"\0"+item.get("block").getAsString(),item);
            }
        }
        try(var walk=Files.walk(root)){
            for(var file:walk.filter(p->p.toString().endsWith(".nbt")).toList()){
                var d=SchematicReader.read(file);var origin=DynamicNbt.sourceOrigin(d);
                for(var entry:d.blockEntities.entrySet()){
                    var raw=entry.getValue();if(!String.valueOf(raw.get("id")).startsWith("create:"))continue;
                    int i=entry.getKey(),x=i%d.sizeX,z=i/d.sizeX%d.sizeZ,y=i/d.sizeX/d.sizeZ;
                    var wrapped=DynamicNbt.paletteTag(d,x,y,z);
                    var item=palettes.get(wrapped.get(DynamicNbt.BE_KEY)+"\0"+d.blockAt(x,y,z));
                    check(item!=null,"missing typed carrier "+file+" "+i);
                    check(item.get("block").getAsString().equals(d.blockAt(x,y,z)),"block properties changed "+file+" "+i);
                    var carrier=CompoundTag.CODEC.parse(JsonOps.INSTANCE,item.get("tag")).getOrThrow();
                    var pos=new BlockPos(1024+x,80+y,-2048+z);
                    var restored=DynamicStructurePlacer.restoreBlockEntity(carrier,pos);
                    var original=DynamicStructurePlacer.decode(NbtWriter.encode(raw));
                    for(String key:original.getAllKeys())if(!MOVED.contains(key))
                        check(original.get(key).equals(restored.get(key)),"NBT value/type lost "+file+" "+i+" "+key);
                    check(restored.getString("id").equals(raw.get("id")),"block entity id lost");
                    check(!restored.contains("Network"),"foreign network retained");
                    if(raw.containsKey("Speed")){speeds++;check(restored.getBoolean("NeedsSpeedUpdate"),"no network rebuild");}
                    if(raw.containsKey("Direction")||raw.containsKey("Backwards"))directions++;
                    if(origin==null){unlocated++;check(!restored.contains("Source"),"unknown foreign source retained");}
                    else for(String key:List.of("Source","Controller","MirrorParent","LastKnownPos")){
                        var old=DynamicNbt.vector(raw.get(key));if(old==null)continue;
                        var now=restored.getIntArray(key);
                        check(now.length==3&&now[0]==old[0]-origin[0]+1024&&now[1]==old[1]-origin[1]+80&&now[2]==old[2]-origin[2]-2048,
                            "world link not relocated "+file+" "+i+" "+key);
                    }
                    for(String side:List.of("north","east","south","west","up","down"))if(original.contains(side)){
                        var expectedConnection=original.getCompound(side).copy();
                        var location=expectedConnection.getCompound("OpenEnd").getCompound("Location");
                        if(origin!=null&&location.contains("Pos")){
                            var old=location.getIntArray("Pos");
                            if(old.length==3)location.putIntArray("Pos",new int[]{(int)(old[0]-origin[0]+1024),
                                (int)(old[1]-origin[1]+80),(int)(old[2]-origin[2]-2048)});
                        }
                        check(expectedConnection.equals(restored.getCompound(side)),"pipe connection data/position lost "+file+" "+i+" "+side);
                    }
                    checked++;
                }
            }
        }
        String report="Create NBT: "+checked+" payloads, "+speeds+" speeds, "+directions+" direction settings, "+unlocated+" without world origin; all source states and typed settings preserved\n";
        Files.writeString(output.resolve("create-nbt-audit.txt"),report);
        System.out.print(report);System.out.println("Create kinetics: PASS");
    }
    static void synthetic()throws Exception{
        var raw=new LinkedHashMap<String,Object>();raw.put("id","create:powered_shaft");raw.put("Speed",-32.0f);
        raw.put("Direction",-1);raw.put("EnginePos",new int[]{-2,0,0});raw.put("Owner",new int[]{1,2,3,4});
        raw.put("Source",new int[]{400,50,-600});raw.put("Network",Map.of("Id",123L));
        var d=new SchematicData(1,1,1,List.of("create:powered_shaft[axis=z,waterlogged=false]"),new int[]{0},Map.of(0,raw),List.of());
        var wrapped=DynamicNbt.paletteTag(d,0,0,0);
        var carrier=CompoundTag.CODEC.parse(JsonOps.INSTANCE,BlockEntityNbt.toJson(BlockEntityNbt.forPalette(wrapped))).getOrThrow();
        var restored=DynamicStructurePlacer.restoreBlockEntity(carrier,new BlockPos(100,80,-200));
        check(restored.getFloat("Speed")==-32&&restored.getInt("Direction")==-1,"speed and direction without entities");
        check(restored.getTagType("Owner")==11&&Arrays.equals(restored.getIntArray("EnginePos"),new int[]{-2,0,0}),"typed UUID and relative engine link");
        check(!restored.contains("Source")&&!restored.contains("Network"),"unresolved world links must rebuild");
        var tank1=Map.<String,Object>of("id","create:fluid_tank","LastKnownPos",new int[]{500,60,-700});
        var tank2=Map.<String,Object>of("id","create:item_vault","LastKnownPos",new int[]{501,60,-700});
        var noEntities=new SchematicData(2,1,1,List.of("create:fluid_tank"),new int[]{0,0},Map.of(0,tank1,1,tank2),List.of());
        check(Arrays.equals(DynamicNbt.sourceOrigin(noEntities),new double[]{500,60,-700}),"origin from consistent stored positions");
        var inconsistent=new SchematicData(2,1,1,List.of("create:fluid_tank"),new int[]{0,0},Map.of(0,tank1,1,tank1),List.of());
        check(DynamicNbt.sourceOrigin(inconsistent)==null,"reject ambiguous stored origin");
        var packed=new LinkedHashMap<String,Object>();packed.put("LastKnownPosition",new BlockPos(-500,-60,700).asLong());
        DynamicNbt.moveBlockEntity(packed,600,140,-900);
        check(BlockPos.of((Long)packed.get("LastKnownPosition")).equals(new BlockPos(100,80,-200)),"packed position relocation");
    }
    static final class Driver extends ChunkDriver {
        int adds,writes,steps;BlockState written;
        @Override public ChunkDriver add(BlockState state){adds++;return this;}
        @Override public void setBlockRange(int x,int y,int z,int to,BlockState state){check(to==y+1,"one cell, exclusive range end");writes++;written=state;}
        @Override public void incY(){steps++;}
    }
    static void placement()throws Exception{
        var method=Arrays.stream(DynamicStructureMixin.class.getDeclaredMethods()).filter(m->m.getName().equals("caf$importedState")).findFirst().orElseThrow();
        method.setAccessible(true);var mixin=new DynamicStructureMixin(){};var driver=new Driver();
        var state=Blocks.OAK_WALL_SIGN.defaultBlockState();
        for(String name:List.of("caf_lc_customcity:sample","lostcities:sample")){
            var part=(IBuildingPart)Proxy.newProxyInstance(CreateKineticSmokeTest.class.getClassLoader(),new Class[]{IBuildingPart.class},
                (o,m,a)->m.getName().equals("getName")?name:null);
            method.invoke(mixin,driver,state,null,part,Transform.ROTATE_NONE,0,80,0,LostCityTerrainFeature.HardAirSetting.VOID);
        }
        check(driver.writes==1&&driver.steps==1&&driver.adds==1&&driver.written==state,"imported state must bypass premature neighbour correction and advance once");
        var biomes=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(net.minecraft.core.registries.Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
        biomes.register(net.minecraft.world.level.biome.Biomes.PLAINS,
            new net.minecraft.world.level.biome.Biome.BiomeBuilder().hasPrecipitation(false).temperature(.5f).downfall(0)
                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY).build(),net.minecraft.core.RegistrationInfo.BUILT_IN);
        biomes.freeze();
        var chunk=new ProtoChunk(new ChunkPos(6,-13),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),biomes,null);
        // Use the actual 8.3.9 driver: block() also calls correct(), which the old mock hid.
        var region=(WorldGenLevel)Proxy.newProxyInstance(CreateKineticSmokeTest.class.getClassLoader(),new Class[]{WorldGenLevel.class},(o,m,a)->switch(m.getName()){
            case "getBlockState"->chunk.getBlockState((BlockPos)a[0]);
            case "getFluidState"->chunk.getFluidState((BlockPos)a[0]);
            case "getChunk"->chunk;
            case "getMinBuildHeight"->-64;case "getMaxBuildHeight"->320;case "getHeight"->384;
            case "getSectionIndex"->((Integer)a[0]>>4)+4;
            case "getSectionsCount"->24;
            default->throw new AssertionError("early imported neighbour access "+m.getName());
        });
        var actual=new ChunkDriver();actual.setPrimer(region,chunk);actual.current(4,80,4);
        var re=mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"xsize\":16,\"zsize\":16,\"slices\":[[\"                \"]]}")).getOrThrow();
        re.setRegistryName(ResourceLocation.parse("caf_lc_customcity:sample"));
        var imported=new BuildingPart(re);
        check(imported.getName().equals("caf_lc_customcity:sample"),"real LC part namespace guard");
        var fence=Blocks.OAK_FENCE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceBlock.EAST,true);
        method.invoke(mixin,actual,fence,null,imported,Transform.ROTATE_NONE,0,80,0,LostCityTerrainFeature.HardAirSetting.VOID);
        check(actual.getBlock(4,80,4)==fence,"real 8.3.9 driver changed imported connection before its neighbour was placed");
        check(actual.getY()==81,"real driver must advance exactly once");
        var pending=new ImportedBlockEntities();
        var carrier=new CompoundTag();carrier.putString(DynamicNbt.BE_KEY,NbtWriter.encode(Map.of("id","minecraft:chest","Speed",-32f,"CustomName","\"Imported\"")));
        var pos=new BlockPos(100,80,-200);
        var chest=Blocks.CHEST.defaultBlockState();
        check(pending.queue(chunk,pos,chest,carrier),"typed machine queue");
        check(chunk.getBlockEntityNbt(pos)==null&&chunk.getBlockState(pos).isAir(),"NBT must stay private until blocks are flushed");
        actual.current(4,80,8);
        method.invoke(mixin,actual,chest,null,imported,Transform.ROTATE_NONE,0,80,0,LostCityTerrainFeature.HardAirSetting.VOID);
        // Complete the fence support using the same imported write path.
        actual.current(5,80,4);
        method.invoke(mixin,actual,Blocks.STONE.defaultBlockState(),null,imported,Transform.ROTATE_NONE,0,80,0,LostCityTerrainFeature.HardAirSetting.VOID);
        actual.actuallyGenerate(chunk);pending.flush(chunk);
        check(chunk.getBlockState(new BlockPos(100,80,-204))==fence,"flushed source connection retained");
        var saved=chunk.getBlockEntityNbt(pos);check(saved!=null&&saved.getFloat("Speed")==-32,"typed NBT retained after block flush");
        var loaded=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,chunk.getBlockState(pos),saved,net.minecraft.core.RegistryAccess.EMPTY);
        check(loaded instanceof net.minecraft.world.level.block.entity.ChestBlockEntity,"pending data must instantiate against the correct block");
        check(((net.minecraft.world.level.block.entity.ChestBlockEntity)loaded).getName().getString().equals("Imported"),"machine settings survive load");
        check(!pending.queue(chunk,pos,chest,new CompoundTag()),"non-imported data uses LC path");
        pending.flush(chunk);check(chunk.getBlockEntityNbt(pos)==saved,"repeat flush must be harmless");
        var removed=pos.above();pending.queue(chunk,removed,chest,carrier);pending.flush(chunk);
        check(chunk.getBlockEntityNbt(removed)==null,"carved/overwritten machine must not leave ghost NBT");
        System.out.println("placement: real LC part + 8.3.9 driver, exact states, delayed typed NBT, correct block entity loading, repeat/carving guards");
    }
}
