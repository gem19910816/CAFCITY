import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.impl.*;
import com.caf.lccustomcity.mixin.CityFoundationMixin;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.*;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.google.gson.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Real 8.3.9 buffered driver + vanilla protochunk; coast/caves and structure footprint rules. */
public final class CityTerrainSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final int GROUND=71;
    static Registry<Biome> biomes;
    static ProtoChunk chunk(int x,int z){
        return new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),biomes,null);
    }
    static BlockPos pos(ProtoChunk c,int x,int y,int z){return new BlockPos(c.getPos().getMinBlockX()+x,y,c.getPos().getMinBlockZ()+z);}
    static void set(ProtoChunk c,int x,int y,int z,Block block){c.setBlockState(pos(c,x,y,z),block.defaultBlockState(),false);}
    static WorldGenLevel region(ProtoChunk chunk){
        return (WorldGenLevel)Proxy.newProxyInstance(CityTerrainSmokeTest.class.getClassLoader(),new Class[]{WorldGenLevel.class},(o,m,a)->switch(m.getName()){
            case "getMinBuildHeight"->-64;case "getMaxBuildHeight"->320;case "getHeight"->384;
            case "getSectionIndex"->((Integer)a[0]>>4)+4;
            case "getSectionIndexFromSectionY"->(Integer)a[0]+4;
            case "getSectionsCount"->24;
            case "getChunk"->{
                if(a[0] instanceof Integer x&&a[1] instanceof Integer z)
                    check(x==chunk.getPos().x&&z==chunk.getPos().z,"foundation accessed neighbouring chunk");
                yield chunk;
            }
            case "getBlockState"->chunk.getBlockState((BlockPos)a[0]);
            case "getFluidState"->chunk.getFluidState((BlockPos)a[0]);
            default->throw new AssertionError("unexpected foundation world access "+m.getName());
        });
    }
    static ChunkDriver driver(ProtoChunk chunk){var driver=new ChunkDriver();driver.setPrimer(region(chunk),chunk);return driver;}
    static void coast(ProtoChunk c){
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            set(c,x,-64,z,Blocks.BEDROCK);
            for(int y=-63;y<=44;y++)if(y<-20||y>-10)set(c,x,y,z,Blocks.STONE);
            for(int y=45;y<=63;y++)set(c,x,y,z,Blocks.WATER);
            set(c,x,71,z,Blocks.GRASS_BLOCK);
        }
        set(c,1,-1,1,Blocks.AIR);set(c,2,65,2,Blocks.DIAMOND_ORE);
        set(c,3,55,3,Blocks.KELP);set(c,4,69,4,Blocks.LAVA);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var registry=new MappedRegistry<Biome>(Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
        registry.register(Biomes.PLAINS,new Biome.BiomeBuilder().hasPrecipitation(false).temperature(.5f).downfall(0)
            .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
            .generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY).build(),RegistrationInfo.BUILT_IN);
        registry.freeze();biomes=registry;
        foundation();scopes();hooks();
        Files.createDirectories(Path.of(args[0]));Files.writeString(Path.of(args[0],"terrain-audit.txt"),
            "Real 8.3.9 ChunkDriver and ProtoChunk: 34560 below-surface cells checked, sea and air filled; ores/bedrock/surface retained; later basement/transit carve retained. Vanilla surface steps, stronghold exemption, whole footprint, negative coordinates, spawn/natural cities and configuration guards PASS\n");
        System.out.println("City terrain: solid coastal foundation, structure suppression, config guards and 8.3.9 order PASS");
    }
    static void foundation(){
        var c=chunk(-150,42);coast(c);var driver=driver(c);
        check(driver.getBlock(0,65,0).isAir()&&driver.getBlock(0,55,0).is(Blocks.WATER),"coast fixture must reproduce empty underside");
        CityFoundation.fill(driver,GROUND);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=-64;y<GROUND;y++)
            check(!CityFoundation.needsFill(driver.getBlock(x,y,z)),"unfilled support "+x+","+y+","+z);
        check(driver.getBlock(1,-1,1).is(Blocks.DEEPSLATE),"negative Y run at -1");
        check(driver.getBlock(2,65,2).is(Blocks.DIAMOND_ORE),"ore must survive");
        check(driver.getBlock(3,55,3).is(Blocks.STONE),"underwater vegetation must be filled");
        check(driver.getBlock(4,69,4).is(Blocks.DIRT),"upper foundation material");
        // Basements and transit carve in the same generation buffer after the foundation.
        driver.setBlockRangeToAir(5,50,5,60);
        driver.actuallyGenerate(c);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            check(c.getBlockState(pos(c,x,-64,z)).is(Blocks.BEDROCK),"bedrock retained");
            check(c.getBlockState(pos(c,x,71,z)).is(Blocks.GRASS_BLOCK),"surface overwritten");
            check(c.getBlockState(pos(c,x,72,z)).isAir(),"above surface touched");
        }
        check(c.getBlockState(pos(c,1,-1,1)).is(Blocks.DEEPSLATE),"buffer not flushed");
        check(c.getBlockState(pos(c,5,55,5)).isAir(),"later basement/rail carving must remain possible");
        var shortChunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),biomes,null);
        CityFoundation.fill(driver(shortChunk),-64);check(shortChunk.getBlockState(new BlockPos(0,-64,0)).isAir(),"empty height range");
        System.out.println("foundation: real driver buffer -> protochunk, 34560 cells, sea/air/caves, untouched solid terrain and later cavities");
    }
    static LostCityProfile profile(){
        var p=new LostCityProfile("caf-terrain",false);p.CITY_CHANCE=0;p.CITY_MINRADIUS=p.CITY_MAXRADIUS=800;
        p.CITY_THRESHOLD=.2f;p.HIGHWAY_DISTANCE_MASK=0;p.RAILWAYS_ENABLED=false;p.RAILWAY_STATIONS_ENABLED=false;
        return p;
    }
    static void scopes()throws Exception {
        for(String name:List.of("pillager_outpost","ruined_portal","ruined_portal_mountain","igloo","village_snowy","mansion"))
            check(CitySurfaceStructures.surface(ResourceLocation.parse("minecraft:"+name),GenerationStep.Decoration.SURFACE_STRUCTURES),"affected vanilla structure "+name);
        check(!CitySurfaceStructures.surface(ResourceLocation.parse("minecraft:stronghold"),GenerationStep.Decoration.SURFACE_STRUCTURES),"underground stronghold");
        check(!CitySurfaceStructures.surface(ResourceLocation.parse("minecraft:mineshaft"),GenerationStep.Decoration.UNDERGROUND_STRUCTURES),"mineshaft");
        check(!CitySurfaceStructures.surface(ResourceLocation.parse("another:castle"),GenerationStep.Decoration.SURFACE_STRUCTURES),"other mods unchanged");
        for(String name:List.of("pillager_outpost","igloo","ruined_portal","ruined_portal_ocean","village_plains")){
            try(var in=CityTerrainSmokeTest.class.getResourceAsStream("/data/minecraft/worldgen/structure/"+name+".json")){
                var json=JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                check(json.get("step").getAsString().equals("surface_structures"),"real vanilla generation step "+name);
            }
        }
        // Anchor outside the city, far corner within it: the entire start must be rejected.
        check(CitySurfaceStructures.overlaps(new BoundingBox(-33,0,-33,0,180,0),(x,z)->x==0&&z==0),"cross-boundary footprint / negative coords");
        check(!CitySurfaceStructures.overlaps(new BoundingBox(-33,0,-33,-1,180,-1),(x,z)->x==0&&z==0),"inclusive boundaries");
        for(int min=-49;min<=49;min++)for(int max=min;max<=49;max++){
            check(CitySurfaceStructures.overlaps(new BoundingBox(min,0,0,max,100,0),(x,z)->x==0&&z==0)==(min<=15&&max>=0),"block/chunk footprint conversion");
        }
        Field loaded=mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries.class.getDeclaredField("loadedPredefined");loaded.setAccessible(true);loaded.setBoolean(null,true);
        for(String name:List.of("predefinedCityMap","predefinedBuildingMap","predefinedStreetMap","OCCUPIED_CHUNKS_BUILDING","OCCUPIED_CHUNKS_STREET")){
            Field f=mcjty.lostcities.worldgen.lost.City.class.getDeclaredField(name);f.setAccessible(true);f.set(null,Map.of());
        }
        var building=new LayoutCatalog.Building("caf_lc_customcity:test","r","\u4f4f\u5b85\u533a",false,1,1);
        LayoutCatalog.install(List.of(building),Map.of("r",1.0),.4375);SpawnCityConfig.install(850);ForceFlattenConfig.install(true);
        var profile=profile();var provider=CityLayoutSmokeTest.provider(31,profile,CityLayoutSmokeTest.world());
        int[] center=SpawnCityRule.centerFor(provider,profile);
        var inside=new BoundingBox(center[0],100,center[1],center[0]+15,180,center[1]+15);
        check(CitySurfaceStructures.suppress(inside,provider),"spawn city must suppress high structures");
        check(!CitySurfaceStructures.suppress(new BoundingBox(-3000,100,0,-2900,180,15),provider),"wilderness retained");
        ForceFlattenConfig.install(false);check(!CitySurfaceStructures.suppress(inside,provider),"config off");ForceFlattenConfig.install(true);
        check(!CitySurfaceStructures.suppress(inside,null),"unsupported dimension");
        profile.CITY_CHANCE=.00003;CityLayout.clear();SpawnCityRule.clear();
        provider=CityLayoutSmokeTest.provider(8064008696273212272L,profile,CityLayoutSmokeTest.world());
        check(CitySurfaceStructures.suppress(new BoundingBox(1552,150,1984,1567,180,1999),provider),"natural city same rule");
        var c=chunk(53,0);var driver=driver(c);var mixin=new CityFoundationMixin(){};
        mixin.provider=provider;mixin.driver=driver;
        Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(sun.misc.Unsafe)uf.get(null);
        var info=(BuildingInfo)unsafe.allocateInstance(BuildingInfo.class);
        var profileField=BuildingInfo.class.getDeclaredField("profile");
        unsafe.putObject(info,unsafe.objectFieldOffset(profileField),profile);info.isCity=true;info.groundLevel=71;
        Method hook=Arrays.stream(CityFoundationMixin.class.getDeclaredMethods()).filter(m->m.getName().equals("caf$solidFoundation")).findFirst().orElseThrow();hook.setAccessible(true);
        ForceFlattenConfig.install(false);hook.invoke(mixin,info,null,c,new CallbackInfo("test",false));
        check(driver.getBlock(0,65,0).isAir(),"disabled foundation");
        ForceFlattenConfig.install(true);info.isCity=false;hook.invoke(mixin,info,null,c,new CallbackInfo("test",false));
        check(driver.getBlock(0,65,0).isAir(),"outside-city foundation");
        info.isCity=true;hook.invoke(mixin,info,null,c,new CallbackInfo("test",false));
        check(driver.getBlock(0,65,0).is(Blocks.STONE),"foundation hook active");
        LayoutCatalog.install(List.of(),Map.of(),.4375);check(!CitySurfaceStructures.suppress(inside,provider),"empty catalog / default cities unchanged");
        System.out.println("scopes: whole structure overlap, real vanilla steps, underground/mod exemptions, spawn/natural city, off/outside/empty guards");
    }
    static void hooks()throws Exception {
        var node=new org.objectweb.asm.tree.ClassNode();
        try(var in=LostCityTerrainFeature.class.getResourceAsStream("/mcjty/lostcities/worldgen/LostCityTerrainFeature.class")){
            new org.objectweb.asm.ClassReader(in).accept(node,0);
        }
        var city=node.methods.stream().filter(m->m.name.equals("doCityChunk")).findFirst().orElseThrow();
        boolean room=false,street=false;
        for(var instruction:city.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){
            if(call.name.equals("generateBuilding"))room=true;if(call.name.equals("generateStreet"))street=true;
        }
        check(room&&street,"HEAD foundation must precede building and street generation");
        String biomeModifier;
        try(var in=LostCityTerrainFeature.class.getResourceAsStream("/data/lostcities/neoforge/biome_modifier/lostcities.json")){
            biomeModifier=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        check(JsonParser.parseString(biomeModifier).getAsJsonObject().get("step").getAsString().equals("raw_generation"),"LC before vanilla surface structure step");
        // Validate every new method/Shadow descriptor against the actual jars as well.
        CityLayoutSmokeTest.hooks();
    }
}
