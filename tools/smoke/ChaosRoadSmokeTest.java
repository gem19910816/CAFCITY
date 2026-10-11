import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.impl.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.worldgen.lost.Transform;
import mcjty.lostcities.worldgen.lost.cityassets.*;
import mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;

/** Real 1.21.1 registries, 8.3.9 codec/palettes, all imported scenes and rotations. */
public final class ChaosRoadSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static BlockState parse(String s)throws Exception {
        return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),s,false).blockState();
    }
    static Map<Character,BlockState> states(JsonObject part)throws Exception {
        var states=new HashMap<Character,BlockState>();
        for(var row:part.getAsJsonObject("palette").getAsJsonArray("palette")){
            var e=row.getAsJsonObject();String state=e.get("block").getAsString();
            check(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(ChaosRoadAssets.base(state))),"unknown block "+state);
            states.put(e.get("char").getAsString().charAt(0),parse(state));
        }
        return states;
    }
    static char cell(JsonObject part,int x,int y,int z){
        return part.getAsJsonArray("slices").get(y).getAsJsonArray().get(z).getAsString().charAt(x);
    }
    static BuildingPart decode(String name,JsonObject json){
        var re=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
        re.setRegistryName(ResourceLocation.parse(ChaosRoadAssets.id(name)));return new BuildingPart(re);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        Path output=Path.of(args[0]);Files.createDirectories(output);
        config(output.resolve("config"));connections();density(output);defenceGroups();assets(output,args.length>1?Path.of(args[1]):null);streetScenes(output);frontages();hooks();
        System.out.println("Chaos roads: 33 roads + 6 roadside scenes + 4 defence overlays, orientations, masks, registry fallback and hook PASS");
    }
    static void density(Path output)throws Exception {
        var names=new ArrayList<>(ChaosRoadAssets.STREET_DECORATIONS);
        ChaosRoadAssets.variants().values().forEach(names::addAll);
        int oldPlants=0,newPlants=0,unchanged=0;
        for(String name:names){
            var original=ChaosRoadAssets.prepare(ChaosRoadAssets.raw(name),s->s);
            var reduced=ChaosRoadAssets.prepared(name,s->s);var ids=new HashMap<Character,String>();
            for(var row:original.getAsJsonObject("palette").getAsJsonArray("palette")){
                var e=row.getAsJsonObject();ids.put(e.get("char").getAsString().charAt(0),e.get("block").getAsString());
            }
            int first=ChaosRoadAssets.STREET_DECORATIONS.contains(name)?0:1;
            var removedColumns=new HashSet<Integer>();var keptColumns=new HashSet<Integer>();
            for(int y=0;y<original.getAsJsonArray("slices").size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                char from=cell(original,x,y,z),to=cell(reduced,x,y,z);
                if(y>=first&&ChaosRoadAssets.vegetation(ids.get(from))){
                    oldPlants++;if(ChaosRoadAssets.vegetation(ids.get(to))){newPlants++;keptColumns.add(z*16+x);}
                    else {check(ids.get(to).equals("minecraft:air"),"plant replaced by non-air");removedColumns.add(z*16+x);}
                }else {check(from==to,"thinning changed pavement, sandbags or furniture");unchanged++;}
            }
            check(Collections.disjoint(keptColumns,removedColumns),"vertical plant was split, leaving a floating upper half");
            check(reduced.toString().equals(ChaosRoadAssets.prepare(reduced.deepCopy(),s->s).toString()),
                "final composition preparation must not thin plants a second time");
            check(reduced.toString().equals(ChaosRoadAssets.prepared(name,s->s).toString()),"plant selection replay");
        }
        double retained=(double)newPlants/oldPlants;
        check(retained>.28&&retained<.32,"all real templates must retain about 30% vegetation: "+retained);
        long[] before=new long[2],after=new long[2];
        for(int x=-200;x<200;x++)for(int z=-200;z<200;z++)for(int kind=0;kind<2;kind++){
            if(legacyScene(x,z,kind==0))before[kind]++;
            if(ChaosStreetDecor.select(31,x,z,kind==0,kind==0?0:1)!=null)after[kind]++;
        }
        for(int kind=0;kind<2;kind++)check(after[kind]>before[kind]*1.7&&after[kind]<before[kind]*2.3,
            "scene frequency should increase approximately twofold");
        String report="Template vegetation: "+oldPlants+" -> "+newPlants+" ("+retained+"); "+unchanged
            +" pavement/furniture cells unchanged. Single defence: "+before[0]+" -> "+after[0]
            +"; public facilities: "+before[1]+" -> "+after[1]+" /160000 eligible chunks.\n";
        Files.writeString(output.resolve("density-audit.txt"),report);System.out.print(report);
    }
    // Previous release's 3x3 priority and 12/22% candidates, used as the distribution baseline.
    static boolean legacyScene(int x,int z,boolean open){
        long h=legacySceneHash(x,z);if(Long.remainderUnsigned(h,100)>=(open?12:22))return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if((dx!=0||dz!=0)&&Long.compareUnsigned(legacySceneHash(x+dx,z+dz),h)<0)return false;
        return true;
    }
    static long legacySceneHash(int x,int z){
        return RadialZonePlan.mix(31^0x51897c83a4612b7dL^RadialZonePlan.mix((long)x*0x9e3779b97f4a7c15L)
            ^RadialZonePlan.mix((long)z*0xc2b2ae3d27d4eb4fL));
    }
    static void defenceGroups(){
        var groups=new HashMap<String,Map<String,ChaosDefencePlan.Group>>();var variants=new HashSet<Integer>();
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++){
            var g=ChaosDefencePlan.select(31,x,z,(xx,zz)->true);if(g==null)continue;
            variants.add(g.variant());String key=g.x()+","+g.z();
            groups.computeIfAbsent(key,k->new HashMap<>()).put(x+","+z,g);
            check(g.equals(ChaosDefencePlan.select(31,x,z,(xx,zz)->true)),"group replay");
        }
        check(variants.size()==4,"all connected large defence shapes reachable");
        check(groups.size()==256,"every fully vacant 8x8 tile must contain a complete large group");
        for(int turn=0;turn<2;turn++){
            int direction=turn;
            BiPredicate<Integer,Integer> corridor=(xx,zz)->direction==0?xx>=2&&xx<=4&&zz==5:xx==5&&zz>=2&&zz<=4;
            for(long seed=0;seed<32;seed++){
                var group=ChaosDefencePlan.select(seed,direction==0?2:5,direction==0?5:2,corridor);
                check(group!=null&&group.width()*group.depth()==3,"find narrow three-chunk corridor despite blocked preferred anchor/shape");
                for(int xx=group.x();xx<group.x()+group.width();xx++)for(int zz=group.z();zz<group.z()+group.depth();zz++){
                    var piece=ChaosDefencePlan.select(seed,xx,zz,corridor);
                    check(piece!=null&&piece.x()==group.x()&&piece.z()==group.z(),"all corridor modules agree on whole group");
                }
            }
        }
        check(ChaosDefencePlan.select(31,2,5,(xx,zz)->xx>=2&&xx<=3&&zz==5)==null,"two free chunks cannot host a large group");
        for(var group:groups.values()){
            var g=group.values().iterator().next();check(group.size()==g.width()*g.depth(),"partial group reservation");
            int modules=0;
            for(int x=g.x();x<g.x()+g.width();x++)for(int z=g.z();z<g.z()+g.depth();z++){
                var piece=group.get(x+","+z);if(piece.decoration()!=null)modules++;
                var relocated=ChaosDefencePlan.select(31,x,z,(xx,zz)->xx!=g.x()||zz!=g.z());
                if(relocated!=null)check(g.x()<relocated.x()||g.x()>=relocated.x()+relocated.width()
                    ||g.z()<relocated.z()||g.z()>=relocated.z()+relocated.depth(),"alternate group must exclude blocked cell");
                if(piece.decoration()==null)continue;
                int ports=ChaosRoadPlan.rotate(piece.decoration().name().startsWith("street_end")?1:
                    piece.decoration().name().startsWith("street_straight")?3:piece.decoration().name().startsWith("street_t")?7:15,piece.decoration().turns());
                for(int[] n:new int[][]{{-1,0,1},{1,0,2},{0,-1,4},{0,1,8}}){
                    var neighbour=group.get((x+n[0])+","+(z+n[1]));
                    check(((ports&n[2])!=0)==(neighbour!=null&&neighbour.decoration()!=null),"defence module ports must join whole group");
                }
                check(piece.decoration().connections()==ports,"composition shares actual group port mask");
                var scene=ChaosRoadRuntime.part("street_full1_0_0_0",0,piece.decoration());
                check(scene!=null,"large group composition must decode");
                var palette=new CompiledPalette(scene.getLocalPalette(null));
                for(int[] edge:new int[][]{{0,5,1},{0,10,1},{15,5,2},{15,10,2},{5,0,4},{10,0,4},{5,15,8},{10,15,8}})
                    if((ports&edge[2])!=0)check(palette.get(scene.getC(edge[0],1,edge[1])).getBlock()
                        ==net.minecraft.world.level.block.Blocks.MUD_BRICK_SLAB,"sandbag arm must reach neighbouring chunk");
            }
            check(modules>=3,"large defence must have multiple linked modules");
        }
        System.out.println("Large defence groups: "+groups.size()+", every eligible tile searches alternate anchors/shapes; narrow corridors, unified turns, joined ports and blocked-frontage avoidance");
    }
    static void config(Path dir)throws Exception {
        Files.createDirectories(dir);Files.deleteIfExists(dir.resolve(RoadPresetConfig.FILE_NAME));
        var warnings=new ArrayList<String>();RoadPresetConfig.load(dir,warnings);
        check(RoadPresetConfig.enabled()&&warnings.isEmpty(),"default config");
        check(RoadPresetConfig.decorations(),"street decorations enabled by default");
        var json=new JsonObject();json.addProperty(RoadPresetConfig.KEY,false);
        Files.writeString(dir.resolve(RoadPresetConfig.FILE_NAME),json.toString());RoadPresetConfig.load(dir,warnings);
        check(!RoadPresetConfig.enabled(),"disabled config");
        json.addProperty(RoadPresetConfig.KEY,"false");Files.writeString(dir.resolve(RoadPresetConfig.FILE_NAME),json.toString());
        RoadPresetConfig.load(dir,warnings);check(RoadPresetConfig.enabled()&&!warnings.isEmpty(),"invalid config fallback");
        json.addProperty(RoadPresetConfig.KEY,true);json.addProperty(RoadPresetConfig.DECOR_KEY,false);
        Files.writeString(dir.resolve(RoadPresetConfig.FILE_NAME),json.toString());RoadPresetConfig.load(dir,warnings);
        check(!RoadPresetConfig.decorations()&&RoadPresetConfig.enabled(),"extra scenes can be disabled independently");
        RoadPresetConfig.load(null,warnings);
        check(ChaosRoadAssets.fallback("minecraft:stone[weathering=true]").equals("minecraft:stone"),"legacy only property");
        for(String value:List.of("minecraft:cobblestone_stairs[weathering=true,facing=east,half=bottom]",
                "minecraft:cobblestone_stairs[facing=east,weathering=false,half=bottom]",
                "minecraft:cobblestone_stairs[facing=east,half=bottom,weathering=true]"))
            check(parse(ChaosRoadAssets.fallback(value)).equals(parse("minecraft:cobblestone_stairs[facing=east,half=bottom]")),"weathering property removal");
        check(parse(ChaosRoadRuntime.resolveState("minecraft:oak_stairs[facing=bad,weathering=true]")).getBlock()
            ==net.minecraft.world.level.block.Blocks.OAK_STAIRS,"invalid property value fallback");
        check(ChaosRoadRuntime.resolveState("immersive_weathering:oak_leaf_pile[layers=3]").equals("minecraft:moss_carpet"),"leaf pile -> moss");
    }
    static int edge(int x,int z){return x==0?1:x==15?2:z==0?4:z==15?8:0;}
    static void connections()throws Exception {
        int[][] endpoints={{0,7,1},{15,7,2},{7,0,4},{7,15,8}};
        Map<String,Integer> canonical=Map.of("none",0,"end",1,"straight",3,"bend",5,"t",7,"all",15,"full",15);
        var seen=new HashSet<String>();var results=new LinkedHashMap<String,ChaosRoadPlan.Selection>();
        for(int i=0;i<400;i++)for(int mask=0;mask<16;mask++)for(boolean open:List.of(false,true)){
            var s=ChaosRoadPlan.select(31,-i,i*17,mask,open);int ports=0;
            Transform t=ChaosRoadRuntime.transform(s.turns());
            for(var e:endpoints)if((canonical.get(s.shape())&e[2])!=0)ports|=edge(t.rotateX(e[0],e[1]),t.rotateZ(e[0],e[1]));
            check(ports==mask,"actual LC rotation mismatches ports "+mask+" "+s);
            check(s.connections()==mask&&s.turns()>=0&&s.turns()<4,"valid selection");
            seen.add(s.name());results.put(i+":"+mask+":"+open,s);
        }
        check(seen.size()==33,"all 33 variants reachable");
        var keys=new ArrayList<>(results.keySet());Collections.reverse(keys);
        var pool=Executors.newFixedThreadPool(4);
        try {var jobs=new ArrayList<Future<?>>();
            for(String key:keys)jobs.add(pool.submit(()->{
                var a=key.split(":");int i=Integer.parseInt(a[0]),mask=Integer.parseInt(a[1]);
                check(results.get(key).equals(ChaosRoadPlan.select(31,-i,i*17,mask,Boolean.parseBoolean(a[2]))),"order/thread determinism");
            }));for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        check(ChaosRoadRuntime.select(1,0,0,(x,z)->true).shape().equals("full"),"open area");
        check(ChaosRoadRuntime.select(1,0,0,(x,z)->z==0).shape().equals("straight"),"narrow channel");
        check(ChaosRoadRuntime.select(1,0,0,(x,z)->(x<=0&&z==0)||(z<=0&&x==0)).shape().equals("bend"),"corner");
        check(ChaosRoadRuntime.select(1,0,0,(x,z)->x<=0&&z==0).shape().equals("end"),"dead end");
        check(ChaosRoadRuntime.select(1,0,0,(x,z)->x==0&&z==0).shape().equals("none"),"isolated vacancy");
        BiPredicate<Integer,Integer> vacant=(x,z)->!(x>=1&&x<=4&&z>=1&&z<=3)&&x!=8&&z!=-7;
        for(int x=-15;x<15;x++)for(int z=-15;z<15;z++)if(vacant.test(x,z)){
            var s=ChaosRoadRuntime.select(51,x,z,vacant);
            if(vacant.test(x+1,z))check((s.connections()&2)!=0&&(ChaosRoadRuntime.select(51,x+1,z,vacant).connections()&1)!=0,"E/W symmetry");
            else check((s.connections()&2)==0,"no connection into reserved cells");
            if(vacant.test(x,z+1))check((s.connections()&8)!=0&&(ChaosRoadRuntime.select(51,x,z+1,vacant).connections()&4)!=0,"N/S symmetry");
        }
        System.out.println("connections: actual LC edge rotations, all variants, open/corner/channel/dead ends and parallel replay");
    }
    static void assets(Path output,Path startup)throws Exception {
        JsonObject manifest;
        try(var in=ChaosRoadAssets.class.getResourceAsStream("/caf_lc_customcity/chaos_roads/manifest.json")){
            manifest=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var out=new LcAssetGenerator.Output();check(ChaosRoadAssets.generate(out,org.slf4j.LoggerFactory.getLogger("RoadTest"))==39,"atomic generation");
        int cells=0,leaves=0,decorations=0,tags=0;
        for(var variants:ChaosRoadAssets.variants().values())for(String name:variants){
            try(var in=ChaosRoadAssets.class.getResourceAsStream("/caf_lc_customcity/chaos_roads/"+name+".json")){
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                check(hash.equals(manifest.getAsJsonObject("original_part_sha256").get(name).getAsString()),"original scene hash "+name);
            }
            String file="caf_lc_customcity/lostcities/parts/chaos_"+name+".json";
            var portable=JsonParser.parseString(out.files.get(file)).getAsJsonObject();
            states(portable);var compiled=new CompiledPalette(decode(name,portable).getLocalPalette(null));
            var original=ChaosRoadAssets.prepared(name,s->s); // Installed mod decorations must not be universally substituted.
            for(var row:original.getAsJsonObject("palette").getAsJsonArray("palette")){
                var e=row.getAsJsonObject();if(!e.get("block").getAsString().startsWith("minecraft:"))decorations++;
                if(e.has("tag")){tags++;check(!e.getAsJsonObject("tag").has("x")&&!e.getAsJsonObject("tag").has("z"),"source-world BE coordinates retained");}
            }
            var prepared=ChaosRoadRuntime.prepared(name);var baseStates=states(prepared);
            for(int turn=0;turn<4;turn++){
                var rotated=ChaosRoadRuntime.prepared(name,turn);var oriented=states(rotated);
                var part=ChaosRoadRuntime.part(name,turn);check(part!=null&&part==ChaosRoadRuntime.part(name,turn),"runtime cache "+name);
                var palette=new CompiledPalette(part.getLocalPalette(null));var t=ChaosRoadRuntime.transform(turn);
                for(var e:baseStates.entrySet())check(oriented.get(e.getKey()).equals(e.getValue().rotate(t.getMcRotation())),"state facing rotation "+name);
                for(int y=0;y<6;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    char symbol=cell(prepared,x,y,z);int rx=t.rotateX(x,z),rz=t.rotateZ(x,z);
                    check(cell(rotated,rx,y,rz)==symbol,"scene geometry rotation "+name);
                    check(palette.get(part.getC(rx,y,rz))!=null,"undefined runtime palette char");
                    var state=oriented.get(symbol);String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if(id.endsWith("_leaves")){leaves++;check(y<=2,"leaves exceed two blocks");}
                    if(id.endsWith("_carpet")&&y>0){
                        String below=BuiltInRegistries.BLOCK.getKey(oriented.get(cell(rotated,rx,y-1,rz)).getBlock()).toString();
                        check(!below.equals("minecraft:air")&&!below.equals("minecraft:structure_void"),"floating substitute carpet "+name);
                    }
                    check(compiled.get(cell(portable,x,y,z))!=null,"undefined portable palette char");cells++;
                }
            }
            if(startup!=null)check(Files.exists(startup.resolve("data").resolve(file)),"startup resource missing "+name);
        }
        check(decorations>100&&tags==2&&leaves>0,"preserve installed decorations, sanitized BE tags and shrub presence");
        for(String name:ChaosRoadAssets.STREET_DECORATIONS){
            try(var in=ChaosRoadAssets.class.getResourceAsStream("/caf_lc_customcity/chaos_roads/"+name+".json")){
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                check(hash.equals(manifest.getAsJsonObject("original_part_sha256").get(name).getAsString()),"street scene source hash");
            }
            String file="caf_lc_customcity/lostcities/parts/chaos_"+name+".json";
            var portable=JsonParser.parseString(out.files.get(file)).getAsJsonObject();states(portable);
            new CompiledPalette(decode(name,portable).getLocalPalette(null));
            check(ChaosRoadAssets.raw(name).toString().contains("parkbenches"),"authored seating missing");
            if(startup!=null)check(Files.exists(startup.resolve("data").resolve(file)),"startup seating asset missing");
        }
        LcAssetGenerator.writeTo(out,output.resolve("pack"));
        Files.writeString(output.resolve("audit.txt"),"33 original hashes; 132 oriented palettes; "+cells+" cells; "+leaves+" leaf cells; "+decorations+" original decoration palette rows; 2 sanitized tags\n");
        System.out.println("assets: all hashes, real codec and compiled palettes, "+cells+" transformed cells; leaves <=2, no floating replacement carpets");
    }
    static void streetScenes(Path output)throws Exception {
        var seen=new HashSet<String>();int selected=0;
        for(int x=-90;x<90;x++)for(int z=-90;z<90;z++)for(boolean open:List.of(false,true))for(int mask:List.of(0,1,2,4,8,15)){
            var selection=ChaosStreetDecor.select(31,x,z,open,mask);
            if(!open&&mask==0)check(selection==null,"seating needs an actual neighbouring building");
            if(selection==null)continue;selected++;seen.add(selection.name());
            check(selection.equals(ChaosStreetDecor.select(31,x,z,open,mask)),"extra scene replay");
            check(selection.defence()==open,"defence belongs in open vacant area");
            if(!open){int side=selection.name().startsWith("bus_stop")?1:2;
                check((ChaosRoadPlan.rotate(side,selection.turns())&mask)!=0,"seating must face into vacant land from the building edge");
            }
        }
        check(seen.size()==10&&selected>100,"all six seating scenes and four defences reachable");
        int composed=0;
        var names=new ArrayList<>(ChaosRoadAssets.STREET_DECORATIONS);names.addAll(ChaosRoadAssets.DEFENCES);
        for(String name:names)for(int turn=0;turn<4;turn++)for(int roadTurn=0;roadTurn<4;roadTurn++){
            var selection=new ChaosStreetDecor.Selection(name,turn,ChaosRoadAssets.DEFENCES.contains(name));
            var base=ChaosRoadRuntime.prepared("street_full1_0_0_0",roadTurn);
            var combined=ChaosStreetComposer.compose(base,selection);var stateMap=states(combined);
            var part=ChaosRoadRuntime.part("street_full1_0_0_0",roadTurn,selection);check(part!=null,"composed real part");
            var palette=new CompiledPalette(part.getLocalPalette(null));
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                check(cell(base,x,0,z)==cell(combined,x,0,z),"decoration must preserve pavement and its connections");
            boolean bench=false,defence=false;
            for(int y=0;y<combined.getAsJsonArray("slices").size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                char symbol=cell(combined,x,y,z);var state=stateMap.get(symbol);check(state!=null&&palette.get(part.getC(x,y,z))!=null,"composite palette collision");
                String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                bench|=id.equals("minecraft:dark_oak_stairs");defence|=id.equals("minecraft:mud_brick_slab");
                if(id.endsWith("_leaves"))check(y<=2,"composed shrub too high");
                if(id.endsWith("_carpet")&&y>0){var below=stateMap.get(cell(combined,x,y-1,z));
                    check(!below.isAir()&&!below.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID),"unsupported composed substitute carpet");
                }
            }
            check(selection.defence()?defence:bench,"missing defence / seat after fallback");composed++;
        }
        Files.writeString(output.resolve("street-scenes-audit.txt"),"6 seating originals + 4 road defence overlays; "+composed+" composed rotations; all source hashes, real palettes, preserved road surface, shrub cap, support and seeded selection PASS\n");
        System.out.println("street scenes: 10 variants, 160 compositions, facing, sparse seeded selection and original pavement preserved");
    }
    static void frontages()throws Exception {
        var b=new LayoutCatalog.Building("front-test","commercial","commercial",false,5,8);
        var placed=new UniquePlotPacking.Placement(b,-3,-11);
        for(int x=-6;x<6;x++)for(int z=-15;z<2;z++)
            check(BuildingFrontage.southFront(placed,x,z)==(x>=-3&&x<=1&&z==-3),
                    "complete south facade only, including negative coordinates");
        check(!BuildingFrontage.southFront(null,0,0),"no building, no frontage");
        var single=new UniquePlotPacking.Placement(new LayoutCatalog.Building("home","outer","home",true,1,1),0,0);
        check(BuildingFrontage.southFront(single,0,1)&&!BuildingFrontage.southFront(single,1,1),"single chunk outer entrance");
        int leaves=0,sandbags=0,removed=0;
        for(var variants:ChaosRoadAssets.variants().values())for(String name:variants){
            var raw=ChaosRoadAssets.raw(name);String before=raw.toString();
            var filtered=ChaosStreetComposer.frontageOnly(raw);var ids=new HashMap<Character,String>();
            for(var row:raw.getAsJsonObject("palette").getAsJsonArray("palette")){
                var e=row.getAsJsonObject();ids.put(e.get("char").getAsString().charAt(0),ChaosRoadAssets.base(e.get("block").getAsString()));
            }
            for(int y=0;y<raw.getAsJsonArray("slices").size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                char from=cell(raw,x,y,z),to=cell(filtered,x,y,z);String id=ids.get(from);
                if(y==0){check(from==to,"all ground cells preserved");continue;}
                if(id.endsWith("_leaves")){check(from==to,"front shrubs retained");leaves++;}
                else if(id.contains("sandbag")){check(from==to,"front sandbags retained before fallback");sandbags++;}
                else {check(ids.get(to).equals("minecraft:air"),"other front decorations must be absent");if(!id.equals("minecraft:air"))removed++;}
            }
            check(raw.toString().equals(before),"filter mutated shared source");
            for(int turn=0;turn<4;turn++){
                var normal=ChaosRoadRuntime.part(name,turn);
                var front=ChaosRoadRuntime.part(name,turn,null,true);check(front!=null&&front!=normal,"front cache distinct");
                check(front==ChaosRoadRuntime.part(name,turn,null,true)&&normal==ChaosRoadRuntime.part(name,turn),"cache replay unaffected");
                verifyFront(front);
            }
        }
        for(String name:ChaosRoadAssets.DEFENCES)for(int turn=0;turn<4;turn++)
            verifyFront(ChaosRoadRuntime.part("street_full1_0_0_0",0,new ChaosStreetDecor.Selection(name,turn,true),true));
        for(String name:ChaosRoadAssets.STREET_DECORATIONS)for(int turn=0;turn<4;turn++)
            check(ChaosRoadRuntime.part("street_full1_0_0_0",0,new ChaosStreetDecor.Selection(name,turn,false),true)
                    ==ChaosRoadRuntime.part("street_full1_0_0_0",0,null,true),"front seating must be suppressed completely");
        check(leaves>0&&sandbags>0&&removed>0,"front filter exercised shrubs, sandbags and unwanted scenery");
        System.out.println("frontages: south facade; 132 real road rotations + 16 filtered defences, seating suppression, preserved shrubs/sandbags/ground and separate caches PASS");
    }
    static void verifyFront(BuildingPart part){
        check(part!=null,"front part must decode");var palette=new CompiledPalette(part.getLocalPalette(null));
        for(int y=1;y<part.getSliceCount();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            var state=palette.get(part.getC(x,y,z));String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            check(state.isAir()||id.endsWith("_leaves")||id.contains("sandbag")||id.equals("minecraft:mud_brick_slab"),"unwanted front decoration "+id);
        }
    }
    static void hooks()throws Exception {
        var node=new org.objectweb.asm.tree.ClassNode();
        try(var in=com.caf.lccustomcity.mixin.VacantSurfaceMixin.class.getResourceAsStream("/com/caf/lccustomcity/mixin/VacantSurfaceMixin.class")){
            new org.objectweb.asm.ClassReader(in).accept(node,0);
        }
        var hook=node.methods.stream().filter(m->m.name.equals("caf$vacantLand")).findFirst().orElseThrow();
        boolean oriented=false,cancel=false,generate=false,enabled=false,vacant=false,front=false,fallback=false;int returns=0;
        for(var instruction:hook.instructions){
            if(instruction.getOpcode()==org.objectweb.asm.Opcodes.RETURN)returns++;
            if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){
                if(call.owner.endsWith("ChaosRoadRuntime")&&call.name.equals("part")&&call.desc.contains(";ILcom/caf/lccustomcity/impl/ChaosStreetDecor$Selection;"))oriented=true;
                if(call.name.equals("generatePart"))generate=true;
                if(call.name.equals("cancel"))cancel=true;
                if(call.owner.endsWith("RoadPresetConfig")&&call.name.equals("enabled"))enabled=true;
                if(call.owner.endsWith("ChaosRoadRuntime")&&call.name.equals("vacant"))vacant=true;
                if(call.owner.endsWith("ChaosRoadRuntime")&&call.name.equals("frontage"))front=true;
                if(call.owner.endsWith("VacantVegetationPlacer")&&call.name.equals("place")&&call.desc.endsWith("JZ)V"))fallback=true;
            }
        }
        check(oriented&&generate&&cancel&&enabled&&vacant&&front&&fallback&&returns>=3,"preset branch and entrance-aware fallback not connected");
        System.out.println("hook: configurable vacancy guard, oriented runtime scene, generation, cancellation and early return");
    }
}
