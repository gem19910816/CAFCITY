import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.impl.*;
import com.caf.lccustomcity.schem.SchematicReader;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.api.*;
import mcjty.lostcities.config.*;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.*;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import mcjty.lostcities.worldgen.lost.regassets.MultiBuildingRE;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.objectweb.asm.Type;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

// ASCII only. Uses the real 97 source footprints and the real LC multi-building codec.
public final class CityLayoutSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final String RES="\u4f4f\u5b85\u533a", IND="\u5de5\u4e1a\u533a", COM="\u5546\u4e1a\u533a";
    static final String INNER="\u5185\u57ce",OUTER="\u5916\u57ce";
    static List<LayoutCatalog.Building> real;
    static Map<String,String> names=new HashMap<>();
    static LayoutCatalog.Snapshot catalog;
    static final Map<String,Map<String,BuildingCountLimits.Limit>> realSingleLimits=new HashMap<>(),realMultiLimits=new HashMap<>();
    static Path output,pack;
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        output=Path.of(args[0]);Files.createDirectories(output);pack=Path.of(args[2]);
        repeatSpacing();outerGroups();partitionLayoutRules();sectors();inventory(Path.of(args[1]));industrialSizing();packing();hooks();runtime();reportedCities();currentWorld();maps();counts();surface();
        System.out.println("City layout: PASS");
    }
    static void sectors(){
        Set<Integer> orientations=new HashSet<>();
        for(long seed=1;seed<=30;seed++){
            long city=RadialZonePlan.citySeed(seed,0,0);double axis=RadialZonePlan.axis(city);
            orientations.add((int)(axis*8/Math.PI));
            check(RadialZonePlan.zone(Math.cos(axis),Math.sin(axis),city,.25,.25)==1,"residential axis");
            check(RadialZonePlan.zone(-Math.cos(axis),-Math.sin(axis),city,.25,.25)==1,"opposite residential axis");
            int[] counts=new int[3];
            for(int i=0;i<36000;i++){
                double a=(i+.5)*Math.PI*2/36000;
                counts[RadialZonePlan.zone(Math.cos(a),Math.sin(a),city,.25,.25)]++;
            }
            check(Math.abs(counts[0]-18000)<=2&&Math.abs(counts[1]-9000)<=2&&Math.abs(counts[2]-9000)<=2,"configured angular shares");
        }
        check(orientations.size()>=6,"orientation must vary with seed");
        check(RadialZonePlan.feasible(.5,.2)&&!RadialZonePlan.feasible(.5,.3),"one-side geometric constraint");
        System.out.println("sectors: opposite residential, single industrial side, 2:1:1 shares, varied orientation");
    }
    static void inventory(Path dir) throws Exception {
        List<LayoutCatalog.Building> buildings=new ArrayList<>();
        var idFor=LcAssetGenerator.class.getDeclaredMethod("idFor",Path.class,Path.class);idFor.setAccessible(true);
        var subFor=LcAssetGenerator.class.getDeclaredMethod("subZoneOf",Path.class,Path.class);subFor.setAccessible(true);
        try(var walk=Files.walk(dir)){
            for(Path file:walk.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".nbt")||p.toString().endsWith(".schem")).sorted().toList()){
                var data=SchematicReader.read(file);var bounds=data.nonAirBounds();if(bounds==null)continue;
                int width=(bounds[3]-bounds[0]+16)/16,depth=(bounds[5]-bounds[2]+16)/16;
                Path rel=dir.relativize(file);boolean outer=rel.getNameCount()>=2&&rel.getName(0).toString().equals(OUTER);
                String zone=outer?null:(String)subFor.invoke(null,dir,file);
                String style=outer?"citystyle_border":zone==null?"citystyle_common":"caf_zone_inner_"+LcAssetGenerator.shortHash(zone);
                String bare=(String)idFor.invoke(null,dir,file),id=LcAssetGenerator.NS+":"+bare;
                var b=new LayoutCatalog.Building(id,style,zone,outer,width,depth);buildings.add(b);names.put(id,rel.toString());
                Path asset=pack.resolve("data/"+LcAssetGenerator.NS+"/lostcities/"+(b.multi()?"multibuildings/":"buildings/")+bare+".json");
                check(Files.exists(asset),"converted footprint missing: "+rel);
                if(b.multi()){
                    var json=JsonParser.parseString(Files.readString(asset));
                    var re=MultiBuildingRE.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
                    re.setRegistryName(ResourceLocation.parse(id));var multi=new MultiBuilding(re);
                    check(multi.getDimX()==width&&multi.getDimZ()==depth,"real codec footprint: "+rel);
                    Field field=RegistryAssetRegistry.class.getDeclaredField("assets");field.setAccessible(true);
                    ((Map<ResourceLocation,MultiBuilding>)field.get(AssetRegistries.MULTI_BUILDINGS)).put(ResourceLocation.parse(id),multi);
                    for(int turn=1;turn<4;turn++){
                        var rotated=MultiBuildingRE.CODEC.parse(JsonOps.INSTANCE,
                            JsonParser.parseString(Files.readString(asset.resolveSibling(bare+"_r"+turn+".json")))).getOrThrow();
                        rotated.setRegistryName(ResourceLocation.parse(id+"_r"+turn));
                        ((Map<ResourceLocation,MultiBuilding>)field.get(AssetRegistries.MULTI_BUILDINGS)).put(rotated.getRegistryName(),new MultiBuilding(rotated));
                    }
                }
            }
        }
        var cfg=ZoneConfig.load(dir,new ArrayList<>());Map<String,Double> weights=new HashMap<>();
        for(var b:buildings){
            Path relative=Path.of(names.get(b.id()));
            String sub=relative.getNameCount()>=3?relative.getName(1).toString():null;
            var limit=cfg.countFor(b.outer()?OUTER:INNER,sub,relative.getFileName().toString());
            if(limit!=null)(b.multi()?realMultiLimits:realSingleLimits).computeIfAbsent(b.style(),s->new HashMap<>())
                .put(b.id(),new BuildingCountLimits.Limit(limit.min(),limit.max()));
        }
        var inner=cfg.zones.get(INNER);
        for(var b:buildings)if(!b.outer()){
            var sub=inner==null?null:inner.subZones().get(b.zone());
            weights.put(b.style(),sub==null||sub.ratio()==null?1:(sub.ratio().min()+sub.ratio().max())/2);
        }
        real=List.copyOf(buildings);LayoutCatalog.install(real,weights,(cfg.outerRatio.min()+cfg.outerRatio.max())/2);
        catalog=LayoutCatalog.current();
        check(catalog.spacingX()>=real.stream().mapToInt(LayoutCatalog.Building::width).max().orElse(1)+3,"road X clearance");
        check(catalog.spacingZ()>=real.stream().mapToInt(LayoutCatalog.Building::depth).max().orElse(1)+3,"road Z clearance");
        List<String> report=new ArrayList<>(List.of("file\tchunksX\tchunksZ\tstyle"));
        for(var b:real)report.add(names.get(b.id())+"\t"+b.width()+"\t"+b.depth()+"\t"+b.style());
        Files.write(output.resolve("footprints.tsv"),report);
        System.out.println("source inventory: "+real.size()+" buildings; primary spacing "+catalog.spacingX()+"x"+catalog.spacingZ());
    }
    static boolean[][] rectangle(int width,int depth){boolean[][] mask=new boolean[width][depth];for(var row:mask)Arrays.fill(row,true);return mask;}
    static void industrialSizing()throws Exception {
        BuildingCountLimits.install(realSingleLimits,realMultiLimits);
        String industrial=catalog.styleForZone(IND),residential=catalog.styleForZone(RES);
        if(industrial==null||residential==null||catalog.styleForZone(COM)==null)return;
        var styles=new HashSet<String>();real.stream().filter(b->!b.outer()).forEach(b->styles.add(b.style()));
        double total=styles.stream().mapToDouble(s->catalog.weights().getOrDefault(s,1.0)).sum();
        double r=catalog.weights().getOrDefault(residential,1.0)/total,i=catalog.weights().getOrDefault(industrial,1.0)/total;
        double adjusted=IndustrialZoneSizing.share(catalog,640,industrial,i);
        check(adjusted<=i&&adjusted>0,"industrial sizing only reduces configured area");
        check(IndustrialZoneSizing.share(catalog,640,industrial,.1)==.1,"a smaller user proportion must remain effective");
        var increased=new HashMap<>(realMultiLimits);
        var limits=new HashMap<String,BuildingCountLimits.Limit>();
        for(var b:catalog.forStyle(industrial))if(b.multi())limits.put(b.id(),new BuildingCountLimits.Limit(5,5));
        increased.put(industrial,limits);BuildingCountLimits.install(realSingleLimits,increased);
        check(IndustrialZoneSizing.share(catalog,640,industrial,i)>=adjusted,"quota demand prevents excessive shrinkage");
        BuildingCountLimits.install(realSingleLimits,realMultiLimits);
        int oldLand=0,newLand=0,oldHomes=0,newHomes=0;
        for(long seed=1;seed<=30;seed++)for(int x=-39;x<=39;x++)for(int z=-39;z<=39;z++){
            if(x*x+z*z>=40*40||RadialZonePlan.outer(x*16,z*16,640,catalog.outerShare()))continue;
            int old=RadialZonePlan.zone(x,z,RadialZonePlan.citySeed(seed,0,0),r,i);
            var current=CityLayout.districtAt(new ChunkCoord(Level.OVERWORLD,x,z),seed,new CityLayout.Center(0,0,640),catalog);
            if(old==2)oldLand++;if(current.style().equals(industrial))newLand++;
            if(old==1)oldHomes++;if(current.style().equals(residential))newHomes++;
        }
        check(newLand<=oldLand&&oldHomes==newHomes,"released industry becomes commerce, residential wedges stay the same");
        Files.writeString(output.resolve("industrial-area-audit.txt"),"Configured industrial share="+i+", adjusted="+adjusted+"; 30 seeds: industrial chunks "+oldLand+" -> "+newLand+"; residential unchanged "+newHomes+"; reduced weight and larger quotas respected\n");
        System.out.println("industrial area: "+i+" -> "+adjusted+", 30-seed land "+oldLand+" -> "+newLand+"; quota and residential guards");
    }
    static int area(List<UniquePlotPacking.Placement> placements){return placements.stream().mapToInt(p->p.building().area()).sum();}
    static void packing(){
        List<LayoutCatalog.Building> small=new ArrayList<>();
        for(int i=0;i<4;i++)small.add(new LayoutCatalog.Building("r"+i,"r",RES,false,3,2));
        var packed=UniquePlotPacking.pack(rectangle(6,4),small,7,(b,x,z)->true);
        check(area(packed)==24&&packed.size()==4,"fully fill feasible lot without duplicates");
        var impossible=UniquePlotPacking.pack(rectangle(6,4),small.subList(0,1),7,(b,x,z)->true);
        check(area(impossible)==6&&impossible.size()==1,"no repeat when exact fill is impossible");
        for(var b:real) {
            if(!names.get(b.id()).contains("\u5927\u5b66")&&!names.get(b.id()).contains("\u533b\u9662"))continue;
            var result=UniquePlotPacking.pack(rectangle(catalog.spacingX()-3,catalog.spacingZ()-3),catalog.forStyle(b.style()),5,(a,x,z)->true,b.id());
            check(result.stream().anyMatch(p->p.building().id().equals(b.id())),"landmark must fit "+names.get(b.id()));
        }
        var hole=rectangle(6,4);hole[2][2]=false;
        var result=UniquePlotPacking.pack(hole,small,9,(b,x,z)->!b.id().equals("r0"));
        for(var p:result){check(!p.building().id().equals("r0"),"quota veto");
            for(int x=p.x();x<p.x()+3;x++)for(int z=p.z();z<p.z()+2;z++)check(hole[x][z],"protected hole");}
        Collections.reverse(small);
        check(packed.equals(UniquePlotPacking.pack(rectangle(6,4),small,7,(b,x,z)->true)),"candidate enumeration order");
        System.out.println("packing: exact feasible fill, impossible fill, landmarks, exclusions and determinism");
    }
    static void hooks() throws Exception {
        var json=JsonParser.parseString(Files.readString(Path.of("src/main/resources/caf_lc_customcity.mixins.json"))).getAsJsonObject();
        int checked=0,shadows=0;
        for(var entry:json.getAsJsonArray("mixins")){
            Class<?> mixin=Class.forName("com.caf.lccustomcity.mixin."+entry.getAsString(),false,CityLayoutSmokeTest.class.getClassLoader());
            var node=new org.objectweb.asm.tree.ClassNode();
            try(var in=mixin.getResourceAsStream("/"+mixin.getName().replace('.','/')+".class")){
                new org.objectweb.asm.ClassReader(in).accept(node,org.objectweb.asm.ClassReader.SKIP_CODE);
            }
            var annotation=node.invisibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            int vi=annotation.values.indexOf("value");
            var type=(Type)((List<?>)annotation.values.get(vi+1)).getFirst();
            Class<?> target=Class.forName(type.getClassName(),false,mixin.getClassLoader());
            for(var field:node.fields){
                if(!hasAnnotation(field.visibleAnnotations,field.invisibleAnnotations,"Lorg/spongepowered/asm/mixin/Shadow;"))continue;
                var actual=Arrays.stream(target.getDeclaredFields()).filter(f->f.getName().equals(field.name)
                    &&Type.getDescriptor(f.getType()).equals(field.desc)).findFirst();
                check(actual.isPresent(),"LC shadow field mismatch: "+mixin.getName()+" -> "+target.getName()+" "+field.name+field.desc);
                check(Modifier.isStatic(actual.get().getModifiers())==Modifier.isStatic(field.access),"shadow field staticness: "+field.name);
                if(hasAnnotation(field.visibleAnnotations,field.invisibleAnnotations,"Lorg/spongepowered/asm/mixin/Final;"))
                    check(Modifier.isFinal(actual.get().getModifiers()),"shadow field finalness: "+field.name);
                shadows++;
            }
            for(var method:node.methods){
                if(!hasAnnotation(method.visibleAnnotations,method.invisibleAnnotations,"Lorg/spongepowered/asm/mixin/Shadow;"))continue;
                var actual=Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().equals(method.name)
                    &&Type.getMethodDescriptor(m).equals(method.desc)).findFirst();
                check(actual.isPresent(),"LC shadow method mismatch: "+mixin.getName()+" -> "+target.getName()+" "+method.name+method.desc);
                check(Modifier.isStatic(actual.get().getModifiers())==Modifier.isStatic(method.access),"shadow method staticness: "+method.name);
                shadows++;
            }
            for(Method method:mixin.getDeclaredMethods()){
                Redirect redirect=method.getAnnotation(Redirect.class);
                if(redirect!=null){
                    int invocationCount=0;
                    var targetNode=new org.objectweb.asm.tree.ClassNode();
                    try(var in=target.getResourceAsStream("/"+target.getName().replace('.','/')+".class")){
                        new org.objectweb.asm.ClassReader(in).accept(targetNode,0);
                    }
                    for(String descriptor:redirect.method()){
                        var ownerMethod=targetNode.methods.stream().filter(m->(m.name+m.desc).equals(descriptor)).findFirst().orElseThrow();
                        String call=redirect.at().target();boolean found=false;
                        for(var insn:ownerMethod.instructions)if(insn instanceof org.objectweb.asm.tree.MethodInsnNode m)
                            if(call.equals("L"+m.owner+";"+m.name+m.desc)){found=true;invocationCount++;}
                        check(found,"redirect invocation target mismatch: "+descriptor+" -> "+call);checked++;
                    }
                    check(redirect.require()>=0&&invocationCount>=redirect.require(),"redirect required invocation count");
                }
                var modifier=method.getAnnotation(org.spongepowered.asm.mixin.injection.ModifyVariable.class);
                if(modifier!=null){
                    for(String descriptor:modifier.method()){
                        var actual=Arrays.stream(target.getDeclaredMethods()).filter(m->(m.getName()+Type.getMethodDescriptor(m)).equals(descriptor)).findFirst().orElseThrow();
                        var args=actual.getParameterTypes();var handler=method.getParameterTypes();
                        check(modifier.argsOnly()&&modifier.ordinal()==0&&modifier.at().value().equals("HEAD"),"rotation argument selector");
                        check(method.getReturnType()==args[1]&&handler[0]==args[1]&&Arrays.equals(Arrays.copyOfRange(handler,1,handler.length),args),"rotation modifier capture signature");
                        check(modifier.require()==1,"rotation must not silently skip its target");checked++;
                    }
                }
                Inject inject=method.getAnnotation(Inject.class);if(inject==null)continue;
                for(String descriptor:inject.method()){
                    String methodName=descriptor.contains("(")?descriptor.substring(0,descriptor.indexOf('(')):descriptor;
                    boolean found=methodName.equals("<init>")?Arrays.stream(target.getDeclaredConstructors()).anyMatch(c->descriptor.equals("<init>"+Type.getConstructorDescriptor(c))):
                        Arrays.stream(target.getDeclaredMethods()).anyMatch(m->m.getName().equals(methodName)
                            &&(!descriptor.contains("(")||descriptor.equals(m.getName()+Type.getMethodDescriptor(m))));
                    check(found,"LC hook descriptor mismatch: "+target.getName()+" "+descriptor);
                    if(!methodName.equals("<init>"))
                        check(Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().equals(methodName))
                            .anyMatch(m->Modifier.isStatic(m.getModifiers())==Modifier.isStatic(method.getModifiers())),"hook staticness: "+descriptor);
                    checked++;
                }
                check(inject.require()==0,"optional hook requirement");
            }
            try(var in=mixin.getResourceAsStream("/"+mixin.getName().replace('.','/')+".class")){
                String bytes=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                check(!bytes.contains("worldgen/street/")&&!bytes.contains("lost/StructureAvoidance"),"obsolete type in mixin "+mixin.getName());
            }
        }
        System.out.println("hook/shadow descriptors and obsolete-type audit: "+checked+" hooks, "+shadows+" shadows against LC 8.3.9");
    }
    static boolean hasAnnotation(List<org.objectweb.asm.tree.AnnotationNode> visible,
            List<org.objectweb.asm.tree.AnnotationNode> invisible,String descriptor){
        return visible!=null&&visible.stream().anyMatch(a->a.desc.equals(descriptor))
            ||invisible!=null&&invisible.stream().anyMatch(a->a.desc.equals(descriptor));
    }
    static Object defaultValue(Class<?> type){
        if(!type.isPrimitive())return null;if(type==boolean.class)return false;
        if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;return 0;
    }
    static IDimensionInfo provider(long seed,LostCityProfile profile,WorldGenLevel world){
        var heightmap=new mcjty.lostcities.worldgen.ChunkHeightmap(LandscapeType.DEFAULT,71);heightmap.setHeight(71);
        return (IDimensionInfo)Proxy.newProxyInstance(CityLayoutSmokeTest.class.getClassLoader(),new Class[]{IDimensionInfo.class},(o,m,a)->switch(m.getName()){
            case "getSeed"->seed;case "getType"->Level.OVERWORLD;case "getWorld"->world;case "getProfile","getOutsideProfile"->profile;
            case "getHeightmap"->heightmap;
            case "getWorldStyle"->AssetRegistries.WORLDSTYLES.getOrThrow(world,profile.getWorldStyle());
            default->defaultValue(m.getReturnType());});
    }
    static WorldGenLevel world() throws Exception {
        Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(sun.misc.Unsafe)uf.get(null);
        ServerLevel level=(ServerLevel)unsafe.allocateInstance(ServerLevel.class);
        var data=Proxy.newProxyInstance(CityLayoutSmokeTest.class.getClassLoader(),new Class[]{WritableLevelData.class},
                (o,m,a)->m.getName().equals("getSpawnPos")?new BlockPos(0,71,0):defaultValue(m.getReturnType()));
        for(var e:Map.of("levelData",data,"worldBorder",new WorldBorder()).entrySet()){
            Field f=Level.class.getDeclaredField(e.getKey());unsafe.putObject(level,unsafe.objectFieldOffset(f),e.getValue());
        }
        return (WorldGenLevel)Proxy.newProxyInstance(CityLayoutSmokeTest.class.getClassLoader(),new Class[]{WorldGenLevel.class},
                (o,m,a)->m.getName().equals("getLevel")?level:defaultValue(m.getReturnType()));
    }
    static void runtime() throws Exception {
        Field loaded=AssetRegistries.class.getDeclaredField("loadedPredefined");loaded.setAccessible(true);loaded.setBoolean(null,true);
        for(String name:List.of("predefinedCityMap","predefinedBuildingMap","predefinedStreetMap","OCCUPIED_CHUNKS_BUILDING","OCCUPIED_CHUNKS_STREET")){
            Field f=City.class.getDeclaredField(name);f.setAccessible(true);f.set(null,Map.of());
        }
        LostCityProfile profile=new LostCityProfile("caf-layout-test",false);
        profile.CITY_CHANCE=0;profile.CITY_MINRADIUS=profile.CITY_MAXRADIUS=800;profile.CITY_THRESHOLD=.2f;
        profile.HIGHWAY_DISTANCE_MASK=0;profile.RAILWAYS_ENABLED=false;profile.RAILWAY_STATIONS_ENABLED=false;
        SpawnCityConfig.install(850);
        WorldGenLevel world=world();IDimensionInfo provider=provider(31,profile,world);
        int[] center=SpawnCityRule.centerFor(provider,profile);check(center!=null,"synthetic city center");
        ChunkCoord coord=new ChunkCoord(Level.OVERWORLD,center[0]/16,center[1]/16);
        check(CityLayout.center(coord,provider,profile)!=null,"synthetic center resolution");
        var located=CityCommandService.centersFor(provider,profile,0,0,1000);
        check(located.length==1&&Arrays.equals(located[0],center),"command must include synthetic city center");
        check(CityLocator.nearestWithin(0,0,located,847)==null
            &&CityLocator.nearestWithin(0,0,located,848)!=null,"synthetic center obeys command distance limit");
        SpawnCityConfig.install(0);
        check(CityCommandService.centersFor(provider,profile,0,0,1000).length==0,"disabled synthetic center must not leak through command cache");
        SpawnCityConfig.install(850);
        int outsideCount=0;Set<String> syntheticIds=new HashSet<>();
        Map<ChunkCoord,CityLayout.Cell> cells=new LinkedHashMap<>();
        for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++){
            ChunkCoord c=coord.offset(x,z);var d=CityLayout.district(c,provider,profile);
            if(d!=null&&d.outer())outsideCount++;
            var planned=CityLayout.cell(c,provider,profile);
            if(planned!=null&&planned.placement()!=null)syntheticIds.add(planned.placement().building().id());
            if((x%7==0&&z%7==0))cells.put(c,CityLayout.cell(c,provider,profile));
        }
        check(outsideCount>1000,"synthetic city outer ring");
        check(real.stream().allMatch(b->syntheticIds.contains(b.id())),"synthetic city missing source");
        var largeDefences=new HashSet<String>();int defenceModules=0;
        for(int dx=-40;dx<=40;dx++)for(int dz=-40;dz<=40;dz++){
            var c=coord.offset(dx,dz);
            var group=ChaosDefencePlan.select(provider.getSeed(),c.chunkX(),c.chunkZ(),(x,z)->{
                var cc=new ChunkCoord(c.dimension(),x,z);
                return ChaosRoadRuntime.vacant(cc,provider,profile)&&!ChaosRoadRuntime.frontage(cc,provider,profile);
            });
            if(group!=null){largeDefences.add(group.x()+","+group.z());if(group.decoration()!=null)defenceModules++;}
        }
        check(largeDefences.size()>=6,"actual city must have multiple reachable large defences after alternate-anchor search");
        System.out.println("Actual city large defences: "+largeDefences.size()+" complete groups, "+defenceModules+" modules, building/frontage/transit avoidance");
        CityLayout.clear();List<ChunkCoord> reversed=new ArrayList<>(cells.keySet());Collections.reverse(reversed);
        for(var c:reversed)check(Objects.equals(cells.get(c),CityLayout.cell(c,provider,profile)),"reverse query order "+c);
        var parallel=Executors.newFixedThreadPool(4);
        try{List<Future<?>> jobs=new ArrayList<>();for(var c:reversed)jobs.add(parallel.submit(()->{
                if(c.chunkX()%14==0) CityLayout.clear();
                check(Objects.equals(cells.get(c),CityLayout.cell(c,provider,profile)),"parallel determinism during cache clears");}));
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }finally{parallel.shutdownNow();}
        for(var c:cells.keySet()){
            var cell=cells.get(c);if(cell==null||cell.placement()==null||!cell.placement().building().multi())continue;
            LostChunkCharacteristics characteristics=new LostChunkCharacteristics();
            LayoutPlacement.assign(characteristics,c,provider,cell);
            var b=cell.placement().building();
            check(characteristics.multiPos.w()==b.width()&&characteristics.multiPos.h()==b.depth(),"runtime MultiPos dimensions");
            String id=characteristics.multiBuilding.getBuilding(characteristics.multiPos.x(),characteristics.multiPos.z());
            int inverse=Math.floorMod(-b.turns(),4);
            int sourceX=BuildingRotation.x(characteristics.multiPos.x(),characteristics.multiPos.z(),b.width(),b.depth(),inverse);
            int sourceZ=BuildingRotation.z(characteristics.multiPos.x(),characteristics.multiPos.z(),b.width(),b.depth(),inverse);
            check(id.equals(b.id()+"_c"+sourceX+"_"+sourceZ),"rotated cell registry ID");
        }
        check(!CityLayout.enabled(provider(31,profile,null),profile),"GUI fallback");
        LayoutCatalog.install(List.of(),Map.of(),.4375);
        check(CityLayout.cell(coord,provider,profile)==null,"catalog removal invalidates managed layout");
        LayoutCatalog.install(real,catalog.weights(),catalog.outerShare());catalog=LayoutCatalog.current();
        var independent=provider(31,profile,world());
        check(CityLayout.center(coord,independent,profile)!=null,"independent world center");
        SpawnCityRule.clear();
        check(Arrays.equals(center,SpawnCityRule.centerFor(provider,profile)),"world-cache cleanup preserves synthetic geometry");
        System.out.println("runtime: synthetic outer ring, real LC assets, reverse and parallel queries, fallback and invalidation");
    }
    static CityLayout.Cell[][] planned(long seed,int size,java.util.function.BiPredicate<Integer,Integer> available) {
        var center=new CityLayout.Center(0,0,640);
        return CityPlotPlan.create(size,size,RadialZonePlan.citySeed(seed,-40,-40),catalog,
            (x,z)->Math.hypot((x-40)*16.0,(z-40)*16.0)>=640?null:
                CityLayout.districtAt(new ChunkCoord(Level.OVERWORLD,x-40,z-40),seed,center,catalog),available,(b,x,z)->true);
    }
    static void reportedCities()throws Exception{
        LostCityProfile profile=new LostCityProfile("reported-cities",false);
        profile.CITY_CHANCE=.00003;profile.CITY_MINRADIUS=profile.CITY_MAXRADIUS=800;profile.CITY_THRESHOLD=.2f;
        profile.HIGHWAY_DISTANCE_MASK=0;profile.RAILWAYS_ENABLED=false;profile.RAILWAY_STATIONS_ENABLED=false;
        var p=provider(-8036615920574348110L,profile,world());CityLayout.clear();SpawnCityRule.clear();
        for(int[] c:new int[][]{{48,752},{-1760,496}}){
            var coord=new ChunkCoord(Level.OVERWORLD,c[0]/16,c[1]/16);
            var center=CityLayout.center(coord,p,profile);check(center!=null,"reported city center exists");
            int radius=(int)Math.ceil(center.radius()/16),ox=Math.floorDiv(center.x(),16)-radius,oz=Math.floorDiv(center.z(),16)-radius;
            var cells=new CityLayout.Cell[radius*2+1][radius*2+1];var ids=new HashSet<String>();
            for(int x=0;x<cells.length;x++)for(int z=0;z<cells.length;z++){
                var cell=CityLayout.cell(new ChunkCoord(Level.OVERWORLD,ox+x,oz+z),p,profile);
                cells[x][z]=cell==null?new CityLayout.Cell(null):cell;
                if(cell!=null&&cell.placement()!=null)ids.add(cell.placement().building().id());
            }
            var missing=real.stream().filter(b->!ids.contains(b.id())).map(b->names.get(b.id())).toList();
            check(missing.isEmpty(),"reported city still missing buildings "+Arrays.toString(c)+" "+missing);
            System.out.println("reported world city "+Arrays.toString(c)+": "+ids.size()+" sources, native overlapping-city ownership");
        }
        CityLayout.clear();SpawnCityRule.clear();
    }
    static void currentWorld() throws Exception {
        BuildingCountLimits.install(realSingleLimits,realMultiLimits);
        System.out.println("actual world quantity rules: "+BuildingCountLimits.totalConfigured()+" sources from instance config");
        // Supply in-memory default configs; native timed caches read their TTL here.
        for(var spec:List.of(mcjty.lostcities.setup.Config.COMMON_CONFIG,mcjty.lostcities.setup.Config.SERVER_CONFIG)){
            var cfg=com.electronwill.nightconfig.core.CommentedConfig.inMemory();spec.correct(cfg);
            var constructor=Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            spec.acceptConfig((net.neoforged.fml.config.IConfigSpec.ILoadedConfig)constructor.newInstance(cfg,null,null));
        }
        LostCityProfile profile=new LostCityProfile("current-reported-world",false);
        profile.CITY_CHANCE=.00003;profile.CITY_MINRADIUS=profile.CITY_MAXRADIUS=800;profile.CITY_THRESHOLD=.2f;
        profile.CITY_LEVEL0_HEIGHT=384;profile.USE_AVG_HEIGHTMAP=false;
        profile.RAILWAYS_ENABLED=false;profile.RAILWAY_STATIONS_ENABLED=false;
        long seed=8064008696273212272L;
        var styleRe=mcjty.lostcities.worldgen.lost.regassets.WorldStyleRE.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"outsidestyle\":\"outside\",\"citystyles\":[{\"factor\":1,\"citystyle\":\"citystyle_common\"}]}"))
            .getOrThrow().setRegistryName(ResourceLocation.fromNamespaceAndPath("lostcities",profile.getWorldStyle()));
        Field styleAssets=RegistryAssetRegistry.class.getDeclaredField("assets");styleAssets.setAccessible(true);
        ((Map<ResourceLocation,WorldStyle>)styleAssets.get(AssetRegistries.WORLDSTYLES)).put(styleRe.getRegistryName(),new WorldStyle(styleRe){
            @Override public float getCityChanceMultiplier(IDimensionInfo p,ChunkCoord c){return 1;}
        });
        for(boolean rails:new boolean[]{false,true})for(int mask:new int[]{0,15}){
            profile.RAILWAYS_ENABLED=rails;profile.RAILWAY_STATIONS_ENABLED=rails;
            profile.HIGHWAY_DISTANCE_MASK=mask;Highway.cleanCache();mcjty.lostcities.worldgen.lost.Railway.cleanCache();CityLayout.clear();SpawnCityRule.clear();
            var p=provider(seed,profile,world());
            int[] spawn=SpawnCityRule.centerFor(p,profile);
            check(spawn!=null&&spawn[0]==848&&spawn[1]==0,"actual world synthetic city position");
            var located=CityCommandService.centersFor(p,profile,0,0,5000);
            var nearest=CityLocator.nearest(0,0,located);
            check(nearest!=null&&nearest[0]==848&&nearest[1]==0,"actual command nearest is synthetic city");
            for(int[] c:new int[][]{{848,0},{1552,1984}}){
                var center=CityLayout.center(new ChunkCoord(Level.OVERWORLD,c[0]/16,c[1]/16),p,profile);
                int radius=(int)Math.ceil(center.radius()/16),ox=Math.floorDiv(center.x(),16)-radius,oz=Math.floorDiv(center.z(),16)-radius;
                var cells=new CityLayout.Cell[radius*2+1][radius*2+1];var ids=new HashSet<String>();int blocked=0;
                var complete=new HashSet<UniquePlotPacking.Placement>();int deepTracks=0,surfaceRails=0;
                for(int x=0;x<cells.length;x++)for(int z=0;z<cells.length;z++){
                    var coord=new ChunkCoord(Level.OVERWORLD,ox+x,oz+z);
                    if(BuildingInfo.hasHighway(coord,p,profile))blocked++;
                    boolean railSurface=rails&&BuildingInfo.hasRailwayAtSurface(coord,p,profile);
                    if(railSurface)surfaceRails++;
                    var cell=CityLayout.cell(coord,p,profile);cells[x][z]=cell==null?new CityLayout.Cell(null):cell;
                    if(cell!=null&&cell.placement()!=null){
                        ids.add(cell.placement().building().id());complete.add(cell.placement());
                        check(!railSurface&&!BuildingInfo.hasHighway(coord,p,profile),"building occupies reserved transport surface");
                        if(rails&&BuildingInfo.hasRailway(coord,p,profile))deepTracks++;
                    }
                }
                var missing=real.stream().filter(b->{
                    var limit=(b.multi()?BuildingCountLimits.forStyleMulti(b.style()):BuildingCountLimits.forStyleSingle(b.style())).get(b.id());
                    return (limit==null||limit.max()>0)&&!ids.contains(b.id());
                }).map(b->names.get(b.id())).toList();
                auditOuterGroups(complete);
                var quantities=new HashMap<String,Integer>();for(var building:complete)quantities.merge(building.building().id(),1,Integer::sum);
                for(var building:real){
                    var limit=(building.multi()?BuildingCountLimits.forStyleMulti(building.style()):BuildingCountLimits.forStyleSingle(building.style())).get(building.id());
                    if(limit!=null)check(quantities.getOrDefault(building.id(),0)>=Math.min(limit.max(),Math.max(1,limit.min()))
                        &&quantities.getOrDefault(building.id(),0)<=limit.max(),"actual city configured quantity");
                }
                System.out.println("actual city "+Arrays.toString(c)+" rails="+rails+" highway mask="+mask+" highway chunks="+blocked+" surface rail chunks="+surfaceRails+" buildings over deep rail chunks="+deepTracks+" missing="+missing);
                check(missing.isEmpty(),"actual spawn/natural city missing sources "+missing);
            }
        }
        CityLayout.clear();SpawnCityRule.clear();Highway.cleanCache();
        BuildingCountLimits.install(Map.of(),Map.of());
    }
    static Set<UniquePlotPacking.Placement> audit(CityLayout.Cell[][] cells) {
        int width=cells.length,depth=cells[0].length;
        Set<UniquePlotPacking.Placement> placements=new HashSet<>();
        for(var column:cells)for(var c:column)if(c.placement()!=null)placements.add(c.placement());
        for(var p:placements){
            var b=p.building();
            int frontageCells=0;
            for(int x=Math.max(0,p.x()-1);x<Math.min(width,p.x()+b.width()+1);x++)
                for(int z=Math.max(0,p.z()-1);z<Math.min(depth,p.z()+b.depth()+1);z++)if(BuildingFrontage.at(p,x,z)){
                    check(cells[x][z].placement()==null,"road-facing entrance obstructed");frontageCells++;
                }
            check(frontageCells==((b.turns()&1)==0?b.width():b.depth()),"complete entrance frontage within city");
            for(int x=p.x();x<p.x()+b.width();x++)for(int z=p.z();z<p.z()+b.depth();z++)
                check(p.equals(cells[x][z].placement()),"partial building footprint: "+b.id());
            int gap=b.outer()?0:1;
            for(int x=Math.max(0,p.x()-gap);x<Math.min(width,p.x()+b.width()+gap);x++)
                for(int z=Math.max(0,p.z()-gap);z<Math.min(depth,p.z()+b.depth()+gap);z++)
                    check(cells[x][z].placement()==null||p.equals(cells[x][z].placement()),"insufficient vacant chunks: "+b.id());
        }
        for(var a:placements)for(var b:placements){
            if(a.equals(b)||!a.building().id().equals(b.building().id()))continue;
            int dx=Math.max(b.x()-a.x()-a.building().width(),a.x()-b.x()-b.building().width());
            int dz=Math.max(b.z()-a.z()-a.building().depth(),a.z()-b.z()-b.building().depth());
            check(Math.max(dx,dz)>=10,"same source needs ten vacant chunks between footprints: "+a+" / "+b);
        }
        auditOuterGroups(placements);
        return placements;
    }
    static void auditOuterGroups(Set<UniquePlotPacking.Placement> placements) {
        var remaining=new HashSet<UniquePlotPacking.Placement>();
        for(var p:placements)if(p.building().outer())remaining.add(p);
        while(!remaining.isEmpty()){
            var group=new HashSet<UniquePlotPacking.Placement>();var queue=new ArrayDeque<UniquePlotPacking.Placement>();
            var first=remaining.iterator().next();remaining.remove(first);queue.add(first);
            while(!queue.isEmpty()){
                var a=queue.remove();group.add(a);
                var adjacent=new ArrayList<UniquePlotPacking.Placement>();
                for(var b:remaining){
                    int dx=Math.max(b.x()-a.x()-a.building().width(),a.x()-b.x()-b.building().width());
                    int dz=Math.max(b.z()-a.z()-a.building().depth(),a.z()-b.z()-b.building().depth());
                    if(dx<=0&&dz<=0)adjacent.add(b);
                }
                remaining.removeAll(adjacent);queue.addAll(adjacent);
            }
            var xs=new TreeSet<Integer>();var zs=new TreeSet<Integer>();long area=0;
            for(var p:group){xs.add(p.x());xs.add(p.x()+p.building().width());
                zs.add(p.z());zs.add(p.z()+p.building().depth());area+=p.building().area();}
            check(Math.min(xs.size()-1,zs.size()-1)<=2,"outer connected group wider than two buildings");
            check(Math.max(xs.size()-1,zs.size()-1)<=6,"outer connected group longer than six buildings");
            check(area==(long)(xs.last()-xs.first())*(zs.last()-zs.first()),"outer L/T/diagonal/holey group");
            for(var p:group)check(xs.higher(p.x())==p.x()+p.building().width()
                &&zs.higher(p.z())==p.z()+p.building().depth(),"outer grid counts whole unequal-size buildings");
        }
    }
    static void outerGroups() {
        for(int[] shape:new int[][]{{1,2},{2,2},{2,3},{3,2},{3,3},{1,6},{1,7},{2,6},{2,7},{6,2},{7,2}}){
            var sources=new ArrayList<LayoutCatalog.Building>();
            for(int x=0;x<shape[0];x++)for(int z=0;z<shape[1];z++)
                sources.add(new LayoutCatalog.Building("home_"+x+"_"+z,"homes",null,true,2,2));
            var snapshot=new LayoutCatalog.Snapshot(sources,Map.of(),1,1);
            var cells=CityPlotPlan.create(shape[0]*2+2,shape[1]*2+2,42,snapshot,
                (x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"homes",true),
                (x,z)->true,(b,x,z)->b.width()==2&&b.depth()==2&&x>=1&&z>=1
                    &&b.id().equals("home_"+((x-1)/2)+"_"+((z-1)/2))&&(x-1)%2==0&&(z-1)%2==0);
            var placed=audit(cells);
            int expected=Math.min(shape[0],shape[1])>2?6:Math.min(6,shape[0])*Math.min(6,shape[1]);
            check(placed.size()==expected,"outer rectangular batch "+Arrays.toString(shape)+" got "+placed.size());
        }
        // A four-chunk-wide home still counts as one building, with an unequal neighbour.
        var wide=new LayoutCatalog.Building("wide","homes",null,true,4,2);
        var narrow=new LayoutCatalog.Building("narrow","homes",null,true,2,2);
        var cells=CityPlotPlan.create(8,4,42,new LayoutCatalog.Snapshot(List.of(wide,narrow),Map.of(),1,1),
            (x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"homes",true),
            (x,z)->true,(b,x,z)->b.width()==(b.id().equals("wide")?4:2)&&z==1&&x==(b.id().equals("wide")?1:5));
        check(audit(cells).size()==2,"mixed footprint homes should touch without being cut");
        System.out.println("outer groups: 1x2/2x2/2x3 and rotated, 3x3 blocked, whole unequal footprints");
    }
    static void partitionLayoutRules() {
        var building = new LayoutCatalog.Building("rural_house", "rural", "乡村", false, 1, 1);
        var catalog = new LayoutCatalog.Snapshot(List.of(building), Map.of("rural", 1.0), 0, 1);
        var layout = new StageLayoutConfig.Rules(8, 8, null,
                new StageLayoutConfig.Range(null, null), new StageLayoutConfig.Range(0, 0),
                2, 6, 0, Map.of());
        var district = new CityLayout.District(new CityLayout.Center(0, 0, 128), "rural", false);
        var partition = new StagePartitionConfig.Partition("乡村", true, "圆形", "固定", 0,
                0, 0, 0, 8, 8, 8, 0, 360,
                new StagePartitionConfig.Range(0, 0), 1, 5, false, false);
        var sparse = CityPlotPlan.create(8, 8, 12, catalog, (x, z) -> district,
                (x, z) -> true, (b, x, z) -> true, layout, Map.of("乡村", partition));
        check(audit(sparse).size() == 1, "disabled empty-plot fill keeps only required occurrence");
        var filledPartition = new StagePartitionConfig.Partition("乡村", true, "圆形", "固定", 0,
                0, 0, 0, 8, 8, 8, 0, 360,
                new StagePartitionConfig.Range(0, 0), 1, 5, true, false);
        var filled = CityPlotPlan.create(8, 8, 12, catalog, (x, z) -> district,
                (x, z) -> true, (b, x, z) -> true, layout, Map.of("乡村", filledPartition));
        check(audit(filled).size() > audit(sparse).size(), "enabled empty-plot fill uses optional buildings");
        System.out.println("partition layout: empty-plot fill controls optional repeats; configured partition run bounds apply");
    }
    static void repeatSpacing() {
        // Exact boundary on both axes, all quadrants and negative-world origins.
        // A multi-cell structure's cells must still form one complete occurrence.
        for(boolean outer:new boolean[]{false,true})for(int width:new int[]{1,4})
                for(int depth:new int[]{1,3})for(int dx:new int[]{-1,0,1})for(int dz:new int[]{-1,0,1}){
            if(dx==0&&dz==0)continue;
            var building=new LayoutCatalog.Building("repeat","repeat",null,outer,width,depth);
            var local=new LayoutCatalog.Snapshot(List.of(building),Map.of("repeat",1.0),.4375,1);
            for(int gap:new int[]{9,10}){
                int ax=dx<0?width+gap:0,az=dz<0?depth+gap:0;
                int bx=dx>0?width+gap:0,bz=dz>0?depth+gap:0;
                var cells=CityPlotPlan.create(width*2+gap+2,depth*2+gap+2,11,local,
                    (x,z)->new CityLayout.District(new CityLayout.Center(-1600,-1600,800),"repeat",outer),
                    (x,z)->true,(source,x,z)->source.width()==width&&source.depth()==depth
                        &&((x==ax+1&&z==az+1)||(x==bx+1&&z==bz+1)));
                Set<UniquePlotPacking.Placement> found=new HashSet<>();
                for(var column:cells)for(var cell:column)if(cell.placement()!=null)found.add(cell.placement());
                check(found.size()==(gap==9?1:2),"repeat boundary gap="+gap+" outer="+outer+" size="+width+"x"+depth+" direction="+dx+","+dz);
                audit(cells);
            }
        }
        System.out.println("same-source spacing: single/multi, inner/outer, 9 rejected / 10 accepted, both axes and diagonals");
    }
    static void maps() throws Exception {
        if(catalog.styleForZone(COM)==null||catalog.styleForZone(RES)==null||catalog.styleForZone(IND)==null){
            System.out.println("map audit skipped: fixture does not have all three districts");return;
        }
        Map<String,Integer> generated=new HashMap<>();
        BufferedImage preview=new BufferedImage(820,850,BufferedImage.TYPE_INT_RGB);var graphics=preview.createGraphics();
        graphics.setColor(new Color(20,25,31));graphics.fillRect(0,0,820,850);
        long[] seeds={31,42,77,125,530,987};
        for(long seed:seeds){
            var cells=planned(seed,81,(x,z)->true);var placements=audit(cells);
            Set<String> seen=new HashSet<>();int land=0,buildingCells=0;
            var center=new CityLayout.Center(0,0,640);
            for(int x=0;x<81;x++)for(int z=0;z<81;z++){
                boolean inCity=Math.hypot(x-40,z-40)<40;
                var d=inCity?CityLayout.districtAt(new ChunkCoord(Level.OVERWORLD,x-40,z-40),seed,center,catalog):null;
                var cell=cells[x][z];if(inCity)land++;
                if(cell.placement()!=null){
                    buildingCells++;var b=cell.placement().building();
                    check(d!=null&&d.style().equals(b.style()),"building crosses circle/district: "+b.id());
                }
                if(seed==31){
                    Color color=d==null?new Color(20,25,31):cell.placement()==null?new Color(80,85,93):
                        d.outer()?new Color(226,194,108):d.style().equals(catalog.styleForZone(RES))?new Color(102,191,149):
                        d.style().equals(catalog.styleForZone(IND))?new Color(218,129,79):new Color(94,157,213);
                    graphics.setColor(color);graphics.fillRect(5+x*10,5+z*10,10,10);
                }
            }
            for(var placement:placements){seen.add(placement.building().id());generated.merge(placement.building().id(),1,Integer::sum);}
            var missing=real.stream().filter(b->!seen.contains(b.id())).map(b->names.get(b.id())).toList();
            check(missing.isEmpty(),"missing sources in city seed "+seed+": "+missing);
            System.out.println("city "+seed+": "+seen.size()+"/"+real.size()+" sources, "+placements.size()+" placements, "+buildingCells+"/"+land+" building chunks, inner >=16 / outer >=0 blocks");
            var replay=planned(seed,81,(x,z)->true);check(Arrays.deepEquals(cells,replay),"whole-city determinism");
        }
        var protectedCells=planned(31,81,(x,z)->!(x>=20&&x<=30&&z>=35&&z<=45));
        audit(protectedCells);
        for(int x=20;x<=30;x++)for(int z=35;z<=45;z++)check(protectedCells[x][z].placement()==null,"protected infrastructure");
        for(long seed=1;seed<=128;seed++){
            var cells=planned(seed,81,(x,z)->true);var placements=audit(cells);
            var ids=new HashSet<String>();for(var p:placements)ids.add(p.building().id());
            check(real.stream().allMatch(b->ids.contains(b.id())),"source coverage stress seed "+seed);
        }
        System.out.println("coverage stress: 128 seeds, all sources, full footprints, inner one / outer zero vacant chunks");
        graphics.setColor(Color.WHITE);graphics.drawString("Blue commercial | Green residential | Orange industrial | Yellow homes | Gray vacant stone",5,838);
        graphics.dispose();ImageIO.write(preview,"png",output.resolve("layout-preview.png").toFile());
        List<String> report=new ArrayList<>(List.of("file\tplacements"));for(var b:real)report.add(names.get(b.id())+"\t"+generated.getOrDefault(b.id(),0));
        Files.write(output.resolve("city-map-audit.tsv"),report);
    }
    static void counts() {
        var a=new LayoutCatalog.Building("count_a","count",null,false,3,2);
        var b=new LayoutCatalog.Building("count_b","count",null,false,1,1);
        var c=new LayoutCatalog.Building("count_c","count",null,false,2,2);
        var local=new LayoutCatalog.Snapshot(List.of(a,b,c),Map.of("count",1.0),.4375,1);
        BuildingCountLimits.install(Map.of("count",Map.of("count_b",new BuildingCountLimits.Limit(3,3))),
                Map.of("count",Map.of("count_a",new BuildingCountLimits.Limit(2,4))));
        var cells=CityPlotPlan.create(40,40,17,local,(x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"count",false),
            (x,z)->true,(source,x,z)->true);
        var placements=audit(cells);Map<String,Integer> counts=new HashMap<>();
        for(var placement:placements)counts.merge(placement.building().id(),1,Integer::sum);
        check(counts.getOrDefault("count_a",0)>=2&&counts.get("count_a")<=4,"multi min/max per city");
        check(counts.getOrDefault("count_b",0)==3,"single fixed count per city");
        check(counts.getOrDefault("count_c",0)>=1,"unconfigured source at least once");
        var home=new LayoutCatalog.Building("outer_home","outer",null,true,2,2);
        var otherHome=new LayoutCatalog.Building("other_outer_home","outer",null,true,2,2);
        var outerCatalog=new LayoutCatalog.Snapshot(List.of(home,otherHome),Map.of(),.4375,1);
        var outerCells=CityPlotPlan.create(12,5,1,outerCatalog,
            (x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"outer",true),(x,z)->true,(source,x,z)->true);
        var homes=audit(outerCells);
        check(homes.stream().anyMatch(left->homes.stream().anyMatch(right->!left.building().id().equals(right.building().id())&&left.z()==right.z()
            &&right.x()==left.x()+home.width())),"outer city must allow touching buildings");
        for(boolean outerFirst:new boolean[]{true,false}){
            var first=new LayoutCatalog.Building("gap_first","mixed",null,outerFirst,3,2);
            var second=new LayoutCatalog.Building("gap_second","mixed",null,!outerFirst,1,1);
            var mixed=new LayoutCatalog.Snapshot(List.of(first,second),Map.of("mixed",1.0),.4375,1);
            var mixedCells=CityPlotPlan.create(10,3,1,mixed,
                (x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"mixed",false),
                (x,z)->true,(source,x,z)->source.width()==(source.id().equals(first.id())?3:1)
                    &&source.depth()==(source.id().equals(first.id())?2:1)&&z==0&&(source.id().equals(first.id())?x==0:x==3||x==4));
            var mixedPlacements=audit(mixedCells);
            check(mixedPlacements.size()==2&&mixedPlacements.stream()
                .anyMatch(p->p.building().id().equals(second.id())&&p.x()==4),
                "inner/outer boundary must keep one vacant chunk in both insertion orders");
        }
        LayoutCatalog.install(List.of(a),Map.of("count",1.0),.4375);
        check(LayoutPlacement.fallbackSingleId().equals("count_a_c0_0"),"multi-only legacy fallback must be a cell asset");
        LayoutCatalog.install(List.of(a,b),Map.of("count",1.0),.4375);
        check(LayoutPlacement.fallbackSingleId().equals("count_b"),"prefer complete single asset as legacy fallback");
        LayoutCatalog.install(real,catalog.weights(),catalog.outerShare());catalog=LayoutCatalog.current();
        BuildingCountLimits.install(Map.of(),Map.of());
        System.out.println("counts: multi min/max, single exact quantity, mandatory source, outer touching, mixed boundary one-chunk gap in both insertion orders");
    }
    static void surface() throws Exception {
        var dir=pack.resolve("data/caf_lc_customcity/lostcities");
        var part=JsonParser.parseString(Files.readString(dir.resolve("parts/vacant_surface.json"))).getAsJsonObject();
        check(part.get("xsize").getAsInt()==16&&part.get("zsize").getAsInt()==16,"flat surface dimensions");
        var slices=part.getAsJsonArray("slices");check(slices.size()==1,"surface must be flat");
        for(var row:slices.get(0).getAsJsonArray())check(row.getAsString().equals("l".repeat(16)),"surface must have no curb/divider");
        var palette=JsonParser.parseString(Files.readString(dir.resolve("palettes/road_inner.json"))).getAsJsonObject();
        for(var entry:palette.getAsJsonArray("palette")){
            var e=entry.getAsJsonObject();if(!e.get("char").getAsString().equals("l"))continue;
            int sum=0;Set<String> materials=new HashSet<>();
            for(var v:e.getAsJsonArray("blocks")){var block=v.getAsJsonObject();sum+=block.get("random").getAsInt();
                String id=block.get("block").getAsString().split("\\[")[0];materials.add(id);}
            check(sum==128&&materials.equals(Set.of("minecraft:grass_block","minecraft:cobbled_deepslate",
                "minecraft:deepslate","minecraft:cyan_terracotta","minecraft:blackstone","minecraft:basalt")),"requested six-material weighted surface");
        }
        try(var paths=Files.walk(dir.resolve("parts"))){
            check(paths.noneMatch(path->path.getFileName().toString().startsWith("road_")),"addon street parts generated");
        }
        for(String name:List.of("citystyle_common","citystyle_border")){
            var style=JsonParser.parseString(Files.readString(pack.resolve("data/lostcities/lostcities/citystyles/"+name+".json"))).getAsJsonObject();
            String json=style.toString();check(!json.contains("road_inner_")&&!json.contains("road_outer_"),"street selectors replaced");
        }
        System.out.println("surface: requested six materials, 128 weights, no road assets or selectors");
    }
}
