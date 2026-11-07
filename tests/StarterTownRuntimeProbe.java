package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.*;
import com.google.gson.*;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.WritableLevelData;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual planner/layout/API methods on a deliberately fixed-terrain fixture, no world generation. */
public final class StarterTownRuntimeProbe {
    static sun.misc.Unsafe unsafe;
    static boolean terrain = true;
    static int terrainReads;
    static int assertions;
    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return 0;
    }
    static WorldGenLevel world(int x, int z) throws Exception {
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Object data = Proxy.newProxyInstance(StarterTownRuntimeProbe.class.getClassLoader(),
                new Class[]{WritableLevelData.class}, (o,m,a) -> m.getName().equals("getSpawnPos")
                ? new BlockPos(x,71,z) : defaultValue(m.getReturnType()));
        for (var entry : Map.of("levelData",data,"worldBorder",new WorldBorder()).entrySet()) {
            Field f = Level.class.getDeclaredField(entry.getKey());
            unsafe.putObject(level,unsafe.objectFieldOffset(f),entry.getValue());
        }
        return (WorldGenLevel) Proxy.newProxyInstance(StarterTownRuntimeProbe.class.getClassLoader(),
                new Class[]{WorldGenLevel.class}, (o,m,a) -> {
                    if (m.getName().equals("getLevel")) return level;
                    if (m.getName().contains("Chunk")) throw new AssertionError("chunk access forbidden");
                    return defaultValue(m.getReturnType());
                });
    }
    static IDimensionInfo provider(long seed, LostCityProfile profile, WorldGenLevel world) {
        return (IDimensionInfo)Proxy.newProxyInstance(StarterTownRuntimeProbe.class.getClassLoader(),
                new Class[]{IDimensionInfo.class},(o,m,a)->switch(m.getName()) {
                    case "getSeed" -> seed;
                    case "getProfile","getOutsideProfile" -> profile;
                    case "getWorld" -> world;
                    case "getType" -> Level.OVERWORLD;
                    default -> defaultValue(m.getReturnType());
                });
    }
    static Map<String,Integer> counts(IDimensionInfo p, List<CityLayout.Center> centers) {
        Map<String,Integer> result = new TreeMap<>();
        Set<CityLayout.Center> unique = new HashSet<>(centers);
        check(unique.size()==centers.size(),"duplicate centers");
        for (var c : centers) {
            var stage = CityStageRuntime.acceptedStage(p,p.getProfile(),c.x(),c.z());
            check(stage!=null,"every globally selected center must have a stage");
            result.merge(stage.name(),1,Integer::sum);
        }
        for (var entry : result.entrySet()) check(entry.getValue()<=CityStageRuntime.worldQuota(p,entry.getKey()),
                "world quota exceeded: "+entry);
        return result;
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);unsafe=(sun.misc.Unsafe)uf.get(null);
        Field loaded=AssetRegistries.class.getDeclaredField("loadedPredefined");loaded.setAccessible(true);loaded.setBoolean(null,true);
        for (String name:List.of("predefinedCityMap","predefinedBuildingMap","predefinedStreetMap","OCCUPIED_CHUNKS_BUILDING","OCCUPIED_CHUNKS_STREET")) {
            Field f=City.class.getDeclaredField(name);f.setAccessible(true);f.set(null,Map.of());
        }
        Field tf=CityStageRuntime.class.getDeclaredField("TERRAIN_CACHE");tf.setAccessible(true);
        Object original=tf.get(null);
        // Only terrain acceptance is stubbed. Stage rules, quotas, spacing, local plans and navigation are production code.
        Map<Object,Boolean> fixedTerrain=new LinkedHashMap<>() {
            @Override public Boolean get(Object key) { terrainReads++; return terrain; }
        };
        unsafe.putObject(unsafe.staticFieldBase(tf),unsafe.staticFieldOffset(tf),fixedTerrain);
        try {
            var warnings=new ArrayList<String>();
            var config=CityStageConfig.load(Path.of(args[0]),warnings);CityStageConfig.install(config);
            RootCityOptions.load(Path.of(args[0]),null,warnings);
            Map<String,CityStageRuntime.Geometry> geometries=new HashMap<>();
            for(String name:config.stageNames()) {
                var rules=StageLayoutConfig.loadForStage(Path.of(args[0]),name,warnings,800,800);
                for(String style:config.stylesForStage(name)) geometries.put(name+"\0"+style,
                        new CityStageRuntime.Geometry(rules.radiusMinBlocks(),rules.radiusMaxBlocks(),rules,0,Map.of()));
            }
            CityStageRuntime.installGeometries(geometries);
            LayoutCatalog.install(List.of(new LayoutCatalog.Building("caf_lc_customcity:fixture","fixture","fixture",false,1,1)),Map.of("fixture",1d),0);
            var profile=new LostCityProfile("caf-starter-probe",false);
            profile.CITY_CHANCE=0;profile.CITY_MINRADIUS=profile.CITY_MAXRADIUS=800;profile.CITY_THRESHOLD=.2f;
            profile.RAILWAYS_ENABLED=false;profile.RAILWAY_STATIONS_ENABLED=false;
            JsonArray runs=new JsonArray();
            for(int i=0;i<32;i++) {
                long seed=i==0?8062975820948862819L:i*0x9e3779b97f4a7c15L;
                int sx=i%2==0?0:-117,sz=i%3==0?-33:0;
                var p=provider(seed,profile,world(sx,sz));
                StarterTownConfig.install(false,600,900);
                var baselinePlan=CustomCityCenterPlanner.scanWorld(p,profile);
                var baseline=counts(p,baselinePlan);
                Set<CityLayout.Center> originalVillages=new HashSet<>();
                for(var c:baselinePlan) if(CityStageRuntime.acceptedStage(p,profile,c.x(),c.z()).name().equals(CityStageConfig.VILLAGE)) originalVillages.add(c);
                StarterTownConfig.install(true,600,900);
                var near=StarterTownRule.reservation(p,profile);
                check(near!=null,"enabled eligible T2 must be reserved; stages="+config.stageNames()
                        +", spawn="+p.getWorld().getLevel().getSharedSpawnPos()+", spawnDistance="+SpawnCityConfig.distance()
                        +", anchor="+CustomCityCenterPlanner.startingVillage(p,profile,p.getWorld().getLevel().getSharedSpawnPos())
                        +", synthetic="+Arrays.toString(SpawnCityRule.centerFor(p,profile))
                        +", base="+CityStageRuntime.basePlannedStage(p,profile,Math.floorDiv(sx,16)*16,Math.floorDiv(sz,16)*16)
                        +", quotas="+CityStageRuntime.worldQuota(p,CityStageConfig.VILLAGE)+"/"+CityStageRuntime.worldQuota(p,CityStageConfig.TOWN));
                double distance=Math.hypot(near.town().x()-near.village().x(),near.town().z()-near.village().z());
                check(distance>=600&&distance<=900,"reservation left desired distance range");
                var worldPlan=CustomCityCenterPlanner.scanWorld(p,profile);
                var after=counts(p,worldPlan);
                check(after.getOrDefault(CityStageConfig.VILLAGE,0).equals(baseline.getOrDefault(CityStageConfig.VILLAGE,0)),
                        "near town consumed an existing village slot");
                check(new HashSet<>(near.villages()).equals(originalVillages),"near town moved existing village centers");
                check(after.getOrDefault(CityStageConfig.CITY,0).equals(baseline.getOrDefault(CityStageConfig.CITY,0)),"T3 count changed");
                check(after.getOrDefault(CityStageConfig.METROPOLIS,0).equals(baseline.getOrDefault(CityStageConfig.METROPOLIS,0)),"T4 count changed");
                check(worldPlan.contains(near.town()),"reserved center missing from world allow-list");
                check(after.getOrDefault(CityStageConfig.TOWN,0)==3,"near town must count within existing three");
                check(CityStageRuntime.acceptedStage(p,profile,near.town().x(),near.town().z()).name().equals(CityStageConfig.TOWN),"close town misclassified");
                check(CityStageRuntime.basePlannedStage(p,profile,near.town().x(),near.town().z()).name().equals(CityStageConfig.VILLAGE),"fixture must exercise radial T1 override");
                check(StarterTownPlan.separated(near.village().x(),near.village().z(),near.village().radius(),near.town().x(),near.town().z(),near.town().radius()),"visible cities overlap");
                for(var v:near.villages()) check(StarterTownPlan.separated(v.x(),v.z(),v.radius(),near.town().x(),near.town().z(),near.town().radius()),"town overlaps another original village");
                int tx=Math.floorDiv(near.town().x(),1024),tz=Math.floorDiv(near.town().z(),1024);
                var coord=new ChunkCoord(Level.OVERWORLD,Math.floorDiv(near.town().x(),16),Math.floorDiv(near.town().z(),16));
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                    var local=CityLayout.centersForTile(coord,p,profile,tx+dx,tz+dz);
                    check(local.contains(near.town()),"tile overlap lost guaranteed town");
                }
                check(CityLayout.center(coord,p,profile).equals(near.town()),"real layout lookup lost close town");
                Method nearest=CitySurvivalNavigation.class.getDeclaredMethod("nearest",IDimensionInfo.class,LostCityProfile.class,int.class,int.class,String.class);
                nearest.setAccessible(true);Object destination=nearest.invoke(null,p,profile,sx,sz,CityStageConfig.TOWN);
                Method destX=destination.getClass().getDeclaredMethod("x"),destZ=destination.getClass().getDeclaredMethod("z");destX.setAccessible(true);destZ.setAccessible(true);
                check((int)destX.invoke(destination)==near.town().x()&&(int)destZ.invoke(destination)==near.town().z(),"survival navigation selected a farther town");
                CityStageRuntime.clearEligibilityCache();CityLayout.clear();
                check(worldPlan.equals(CustomCityCenterPlanner.scanWorld(p,profile)),"clearing caches changed city plan");
                JsonObject row=new JsonObject();row.addProperty("seed",seed);row.addProperty("distance",distance);
                row.add("before",new Gson().toJsonTree(baseline));row.add("after",new Gson().toJsonTree(after));runs.add(row);
            }
            // Exercise the existing-native-village path separately from the usual synthetic anchor.
            profile.CITY_CHANCE=1;SpawnCityRule.clear();CityStageRuntime.clearEligibilityCache();CityLayout.clear();
            var nativeProvider=provider(999,profile,world(0,0));
            check(SpawnCityRule.centerFor(nativeProvider,profile)==null,"native fixture unexpectedly created synthetic village");
            var nativeTown=StarterTownRule.reservation(nativeProvider,profile);
            check(nativeTown!=null,"native starting village did not receive a nearby town");
            check(CustomCityCenterPlanner.scanWorld(nativeProvider,profile).contains(nativeTown.town()),"native companion missing globally");
            var nativeCoord=new ChunkCoord(Level.OVERWORLD,nativeTown.town().x()/16,nativeTown.town().z()/16);
            check(CityLayout.center(nativeCoord,nativeProvider,profile).equals(nativeTown.town()),"native companion lost in local layout");
            var pool=Executors.newFixedThreadPool(4,task->{var t=new Thread(task);t.setDaemon(true);return t;});
            try {
                List<Callable<Boolean>> tasks=new ArrayList<>();
                for(int i=0;i<12;i++) tasks.add(()->CustomCityCenterPlanner.scanWorld(nativeProvider,profile).contains(nativeTown.town())
                        && CityStageRuntime.acceptedStage(nativeProvider,profile,nativeTown.town().x(),nativeTown.town().z()).name().equals(CityStageConfig.TOWN));
                for(var result:pool.invokeAll(tasks,10,TimeUnit.SECONDS)) check(!result.isCancelled()&&result.get(),"concurrent plan/stage query did not finish consistently");
            } finally { pool.shutdownNow(); }
            profile.CITY_CHANCE=0;SpawnCityRule.clear();CityStageRuntime.clearEligibilityCache();CityLayout.clear();
            var p=provider(77,profile,world(0,0));
            terrain=false;CityStageRuntime.clearEligibilityCache();StarterTownConfig.install(true,600,900);ForceFlattenConfig.install(false);
            check(StarterTownRule.reservation(p,profile)==null,"no valid terrain and flatten off must not claim success");
            terrainReads=0;
            var fallbackAnchor=CustomCityCenterPlanner.startingVillage(p,profile,p.getWorld().getLevel().getSharedSpawnPos());
            CustomCityCenterPlanner.baseVillagePlan(p,profile,p.getWorld().getLevel().getSharedSpawnPos(),fallbackAnchor);
            int originalVillageWork=terrainReads;
            terrainReads=0;ForceFlattenConfig.install(true);
            var earthworks=StarterTownRule.reservation(p,profile);
            check(earthworks!=null&&earthworks.earthworks(),"existing flatten setting must allow explicit fallback");
            check(terrainReads<=originalVillageWork+StarterTownPlan.MAX_TERRAIN_ATTEMPTS,"added fallback terrain work not bounded");
            check(CityStageRuntime.acceptedStage(p,profile,earthworks.town().x(),earthworks.town().z())!=null,"flatten fallback rejected again by terrain");
            StarterTownConfig.install(false,600,900);
            check(StarterTownRule.reservation(p,profile)==null,"disabled nearby town returned old cached result");
            check(CityStageRuntime.acceptedStage(p,profile,earthworks.town().x(),earthworks.town().z())==null,"stage cache ignored changed settings");
            StarterTownConfig.install(true,16,16);terrain=true;
            check(StarterTownRule.reservation(p,profile)==null,"too-small ring must not create overlapping town");
            StarterTownConfig.install(true,600,900);SpawnCityConfig.install(0);
            check(StarterTownRule.reservation(p,profile)==null,"disabled spawn village must disable companion town");
            SpawnCityConfig.install(100);
            Path rootFile=Path.of(args[0]).resolve(CityStageConfig.FILE_NAME);
            String originalConfig=Files.readString(rootFile);
            try {
                var changed=JsonParser.parseString(originalConfig).getAsJsonObject();
                changed.getAsJsonObject("阶段").getAsJsonObject(CityStageConfig.TOWN).addProperty("启用",false);
                Files.writeString(rootFile,new Gson().toJson(changed));CityStageConfig.install(CityStageConfig.load(Path.of(args[0]),new ArrayList<>()));
                check(StarterTownRule.reservation(p,profile)==null,"disabled T2 stage still reserved town");
                changed=JsonParser.parseString(originalConfig).getAsJsonObject();
                var quota=changed.getAsJsonObject("风格阶段生成").getAsJsonObject("西式风格").getAsJsonObject(CityStageConfig.TOWN);
                quota.addProperty("生成数量下限",0);quota.addProperty("生成数量上限",0);
                Files.writeString(rootFile,new Gson().toJson(changed));CityStageConfig.install(CityStageConfig.load(Path.of(args[0]),new ArrayList<>()));
                check(StarterTownRule.reservation(p,profile)==null,"zero T2 quota still reserved town");
            } finally { Files.writeString(rootFile,originalConfig);CityStageConfig.install(config); }
            JsonObject report=new JsonObject();report.addProperty("assertions",assertions);report.add("runs",runs);
            report.addProperty("terrain_fixture","constant allowed/denied acceptance cache; actual noise generation and visual world not tested");
            report.addProperty("game_launched",false);
            Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("Starter town runtime: PASS assertions="+assertions+", fixed-terrain seeds="+runs.size());
        } finally { unsafe.putObject(unsafe.staticFieldBase(tf),unsafe.staticFieldOffset(tf),original); }
    }
}
