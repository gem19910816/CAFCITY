import com.caf.lccustomcity.config.DynamicStructureConfig;
import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.impl.DynamicStructurePlacer;
import com.caf.lccustomcity.nbt.*;
import com.caf.lccustomcity.schem.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.*;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

// Actual LC metadata codec, actual Minecraft NBT and ProtoChunk persistence.
public final class DynamicStructureSmokeTest {
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path sandbox=Path.of(args[0]);Files.createDirectories(sandbox);
        types();
        Path synthetic=sandbox.resolve("synthetic");Files.createDirectories(synthetic);
        Map<String,Object> c=new LinkedHashMap<>();
        c.put("Type","create:elevator");c.put("Anchor",new int[]{104,202,304});
        c.put("MinContactY",198);c.put("MaxContactY",207);c.put("ContactY",-4);
        c.put("Column",Map.of("X",-1,"Z",2,"Side","NORTH"));
        c.put("Blocks",Map.of("BlockList",List.of(Map.of("Pos",new int[]{-1,-4,0})),"Palette",List.of(Map.of("Name","minecraft:stone"))));
        c.put("Actors",List.of(Map.of("Pos",new int[]{-1,-4,0},"Position",List.of(103.5,198.0,304.5))));
        Map<String,Object> entity=new LinkedHashMap<>();entity.put("id","create:stationary_contraption");
        entity.put("UUID",new int[]{1,2,3,4});entity.put("Pos",List.of(104.0,202.0,304.0));
        entity.put("ControllerRelative",new int[]{0,7,0});entity.put("Contraption",c);
        Map<String,Object> pulley=new LinkedHashMap<>();pulley.put("id","create:elevator_pulley");
        pulley.put("Running",(byte)1);pulley.put("Offset",6.0f);pulley.put("MirrorParent",new int[]{105,209,304});
        pulley.put("Source",new int[]{105,209,304});pulley.put("Owner",new int[]{8,7,6,5});
        pulley.put("MirrorChildren",List.of(Map.of("Pos",new int[]{106,209,304})));
        pulley.put("Network",Map.of("Id",123456789L));
        Map<String,Object> root=Map.of("size",List.of(32,12,16),"palette",List.of(Map.of("Name","minecraft:stone"),Map.of("Name","create:elevator_pulley")),
                "blocks",List.of(Map.of("pos",List.of(2,1,3),"state",0),Map.of("pos",List.of(19,9,5),"state",0),
                        Map.of("pos",List.of(4,9,4),"state",1,"nbt",pulley)),
                "entities",List.of(Map.of("pos",List.of(4.0,2.0,4.0),"nbt",entity)));
        Path sample=synthetic.resolve("elevator.nbt");Files.write(sample,Base64.getDecoder().decode(NbtWriter.encode(root)));
        audit(synthetic);
        // Config off returns before touching world/chunk (no spawn and no queue).
        DynamicStructureConfig.install(false);DynamicStructurePlacer.place(null,null,null,0,0,0);
        DynamicStructureConfig.install(true);
        if(args.length>1 && !args[1].isBlank()) {
            Path real=sandbox.resolve("real");Files.createDirectories(real);
            try(var paths=Files.walk(Path.of(args[1]))) {
                for(Path p:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".nbt")).toList())
                    Files.copy(p,real.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            }
            audit(real);
        }
        System.out.println("Dynamic structures: PASS");
    }
    static void types() throws Exception {
        var raw=Map.<String,Object>of("b",(byte)1,"s",(short)2,"i",3,"l",4L,"f",5f,"d",6d,
                "ia",new int[]{1,2,3,4},"la",new long[]{Long.MAX_VALUE},"ba",new byte[]{1,2},
                "text","Chinese: \u7535\u68af; supplementary: \uD83D\uDE00; nul: \u0000");
        var tag=DynamicStructurePlacer.decode(NbtWriter.encode(raw));
        check(tag.getTagType("b")==1 && tag.getTagType("s")==2 && tag.getTagType("i")==3 &&
                tag.getTagType("l")==4 && tag.getTagType("f")==5 && tag.getTagType("d")==6,"numeric types");
        check(tag.getTagType("ia")==11 && tag.getTagType("la")==12 && tag.getTagType("ba")==7,"array types");
        check(DynamicNbt.copy(raw).get("text").equals(raw.get("text")),"UTF roundtrip");
    }
    static void audit(Path dir) throws Exception {
        var out=LcAssetGenerator.generate(dir,LoggerFactory.getLogger("DynamicTest"));
        check(out.errors.isEmpty(),"conversion errors: "+out.errors);
        int expected=0;
        Map<String,SchematicEntity> originals=new HashMap<>();
        Map<String,SchematicData> byBuilding=new HashMap<>();
        Map<String,Path> paths=new HashMap<>();
        try(var walk=Files.walk(dir)) {
            for(Path p:walk.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".nbt")).toList()) {
                var d=SchematicReader.read(p);String id=out.displayNames.entrySet().stream()
                        .filter(e->e.getValue().equals(dir.relativize(p).toString())).findFirst().orElseThrow().getKey();
                byBuilding.put(id,d);paths.put(id,p);expected+=d.entities.size();
                for(var e:d.entities)originals.put(Arrays.toString((int[])e.nbt.get("UUID")),e);
            }
        }
        int found=0,elevators=0;
        for(var entry:out.files.entrySet()) {
            if(!entry.getKey().contains("/parts/"))continue;
            JsonObject json=JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if(!json.has("meta")||java.util.stream.StreamSupport.stream(json.getAsJsonArray("meta").spliterator(),false)
                    .noneMatch(m->m.getAsJsonObject().get("key").getAsString().equals(DynamicNbt.ENTITY_KEY)))continue;
            // A misspelled metadata field silently vanishes: read through LC's real codec.
            var re=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
            var part=new BuildingPart(re);String payload=part.getMetaString(DynamicNbt.ENTITY_KEY);
            check(payload!=null,"LC must retain entity metadata");
            var encoded=NbtReader.asCompound(NbtReader.readRoot(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))));
            int[] source=(int[])encoded.get("SourcePart");
            int bx=1024,by=80,bz=-2048;
            var tags=DynamicStructurePlacer.prepare(payload,bx+source[0],by+source[1],bz+source[2],"test-world");
            var again=DynamicStructurePlacer.prepare(payload,bx+source[0],by+source[1],bz+source[2],"test-world");
            var other=DynamicStructurePlacer.prepare(payload,bx+source[0]+512,by+source[1],bz+source[2],"test-world");
            var local=NbtReader.list(encoded,"Entities");
            for(int i=0;i<tags.size();i++) {
                CompoundTag tag=tags.get(i);var saved=NbtReader.asCompound(local.get(i));
                var original=originals.get(Arrays.toString((int[])saved.get("UUID")));check(original!=null,"original lookup");
                check(tag.getUUID("UUID").equals(again.get(i).getUUID("UUID")),"stable UUID");
                check(!tag.getUUID("UUID").equals(other.get(i).getUUID("UUID")),"unique UUID per occurrence");
                var pos=tag.getList("Pos",6);
                check(pos.getDouble(0)==bx+original.x && pos.getDouble(1)==by+original.y && pos.getDouble(2)==bz+original.z,"position/crop/padding");
                var biomes=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                        net.minecraft.core.registries.Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
                biomes.register(net.minecraft.world.level.biome.Biomes.PLAINS,
                        new net.minecraft.world.level.biome.Biome.BiomeBuilder().hasPrecipitation(false).temperature(0.5f).downfall(0)
                                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
                                        .fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                                .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY).build(),
                        net.minecraft.core.RegistrationInfo.BUILT_IN);
                biomes.freeze();
                var chunk=new ProtoChunk(new ChunkPos((int)Math.floor(pos.getDouble(0))>>4,(int)Math.floor(pos.getDouble(2))>>4),
                        UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),biomes,null);
                check(DynamicStructurePlacer.append(chunk,tag),"first chunk insertion");
                check(!DynamicStructurePlacer.append(chunk,tag.copy()) && chunk.getEntities().size()==1,"duplicate suppression");
                var cp=tag.getCompound("Contraption");
                if(cp.getString("Type").equals("create:elevator")) {
                    elevators++;var oc=NbtReader.compound(original.nbt,"Contraption");
                    check(cp.getCompound("Blocks").equals(DynamicStructurePlacer.decode(NbtWriter.encode(NbtReader.compound(original.nbt,"Contraption"))).getCompound("Blocks")),"contraption block payload untouched");
                    check(Arrays.equals(tag.getIntArray("ControllerRelative"),(int[])original.nbt.get("ControllerRelative")),"relative pulley link untouched");
                    var ctrl=tag.getIntArray("ControllerRelative");
                    int x=(int)Math.floor(original.x)+ctrl[0],y=(int)Math.floor(original.y)+ctrl[1],z=(int)Math.floor(original.z)+ctrl[2];
                    SchematicData data=byBuilding.values().stream().filter(d->d.entities.contains(original)).findFirst().orElseThrow();
                    var be=data.blockEntityAt(x,y,z);check(be!=null && "create:elevator_pulley".equals(be.get("id")),"controller resolves to imported pulley");
                    var wrapped=DynamicNbt.paletteTag(data,x,y,z);
                    var paletteFile=out.files.entrySet().stream().filter(e->e.getKey().contains("/palettes/") &&
                            e.getValue().contains((String)wrapped.get(DynamicNbt.BE_KEY))).findFirst().orElseThrow();
                    var paletteJson=JsonParser.parseString(paletteFile.getValue()).getAsJsonObject();
                    mcjty.lostcities.worldgen.lost.regassets.PaletteRE.CODEC.parse(JsonOps.INSTANCE,paletteJson).getOrThrow();
                    JsonObject generatedJson=null;
                    for(var pe:paletteJson.getAsJsonArray("palette")) {
                        var item=pe.getAsJsonObject();
                        if(item.has("tag") && item.getAsJsonObject("tag").has(DynamicNbt.BE_KEY) &&
                                item.getAsJsonObject("tag").get(DynamicNbt.BE_KEY).getAsString().equals(wrapped.get(DynamicNbt.BE_KEY)))
                            generatedJson=item.getAsJsonObject("tag");
                    }
                    check(generatedJson!=null,"palette carrier missing");
                    var generated=CompoundTag.CODEC.parse(JsonOps.INSTANCE,generatedJson).getOrThrow();
                    var restored=DynamicStructurePlacer.restoreBlockEntity(generated,new BlockPos(bx+x,by+y,bz+z));
                    check(restored.getBoolean("Running") && restored.getFloat("Offset")==((Number)be.get("Offset")).floatValue(),"pulley assembly state");
                    check(!restored.contains("Network") && restored.getBoolean("NeedsSpeedUpdate"),"fresh kinetic network");
                    check(restored.getTagType("Owner")==11,"owner UUID retains int array");
                    var origin=DynamicNbt.sourceOrigin(data);var oldSource=DynamicNbt.vector(be.get("Source"));
                    var newSource=restored.getIntArray("Source");
                    check(newSource[0]==oldSource[0]-origin[0]+bx && newSource[1]==oldSource[1]-origin[1]+by && newSource[2]==oldSource[2]-origin[2]+bz,"kinetic source relocated");
                    if(be.containsKey("MirrorParent")) {
                        var oldMirror=DynamicNbt.vector(be.get("MirrorParent"));var mirror=restored.getIntArray("MirrorParent");
                        check(mirror[0]==oldMirror[0]-origin[0]+bx && mirror[1]==oldMirror[1]-origin[1]+by && mirror[2]==oldMirror[2]-origin[2]+bz,"mirror parent relocated");
                    }
                    if(be.containsKey("MirrorChildren")) {
                        var oldMirrors=NbtReader.list(be,"MirrorChildren");var mirrors=restored.getList("MirrorChildren",10);
                        check(mirrors.size()==oldMirrors.size(),"mirror child count");
                        for(int m=0;m<mirrors.size();m++) {
                            var oldMirror=DynamicNbt.vector(NbtReader.asCompound(oldMirrors.get(m)).get("Pos"));
                            var mirror=mirrors.getCompound(m).getIntArray("Pos");
                            check(mirror[0]==oldMirror[0]-origin[0]+bx && mirror[1]==oldMirror[1]-origin[1]+by && mirror[2]==oldMirror[2]-origin[2]+bz,"mirror child relocated");
                        }
                    }
                    check(cp.getInt("ContactY")==((Number)oc.get("ContactY")).intValue(),"relative contact unchanged");
                    check(cp.getInt("MaxContactY")==((Number)oc.get("MaxContactY")).intValue()+by-origin[1],"absolute contact relocated");
                    var anchor=cp.getIntArray("Anchor");var oldAnchor=(int[])oc.get("Anchor");
                    check(anchor[0]==oldAnchor[0]+bx-origin[0] && anchor[1]==oldAnchor[1]+by-origin[1] && anchor[2]==oldAnchor[2]+bz-origin[2],"anchor relocated independently of current elevator offset");
                }
                found++;
            }
        }
        check(found==expected,"entity loss: "+found+" / "+expected);check(elevators>0,"elevator fixture missing");
        System.out.println("Audited "+paths.values()+": entities="+found+", elevators="+elevators+" PASS");
    }
}
