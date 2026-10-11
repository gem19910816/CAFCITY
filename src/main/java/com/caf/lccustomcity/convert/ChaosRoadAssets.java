package com.caf.lccustomcity.convert;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;

/** Imported ChaosZPack 1.4.1 street scenes; raw originals stay in bundled resources. */
public final class ChaosRoadAssets {
    public static final List<String> STREET_DECORATIONS=List.of("bus_stop1","bus_stop2","bus_stop3",
        "building_front1","building_front2","building_front3");
    public static final List<String> DEFENCES=List.of("street_all3_0_0_0","street_end3_0_0_0",
        "street_straight11_0_0_0","street_t3_0_0_0");
    private static final Map<String,List<String>> VARIANTS;
    static {
        var variants=new LinkedHashMap<String,List<String>>();
        variants.put("straight",names("straight",1,2,4,5,6,7,8,9,10,11,12));
        variants.put("bend",names("bend",1,2,3,4,5));
        variants.put("end",names("end",1,2,3));
        variants.put("full",names("full",1,2));
        variants.put("none",names("none",1,2));
        variants.put("t",names("t",1,2,3,4,5,6,7));
        variants.put("all",names("all",1,2,3));
        VARIANTS=Collections.unmodifiableMap(variants);
    }
    private ChaosRoadAssets() {}
    private static List<String> names(String shape,int... numbers){
        var names=new ArrayList<String>();for(int n:numbers)names.add("street_"+shape+n+"_0_0_0");return List.copyOf(names);
    }
    public static Map<String,List<String>> variants(){return VARIANTS;}
    public static String id(String name){return "caf_lc_customcity:chaos_"+name;}
    public static JsonObject raw(String name) {
        if(!STREET_DECORATIONS.contains(name)&&VARIANTS.values().stream().noneMatch(list->list.contains(name)))throw new IllegalArgumentException("Unknown road preset "+name);
        try(var in=ChaosRoadAssets.class.getResourceAsStream("/caf_lc_customcity/chaos_roads/"+name+".json")){
            if(in==null)throw new IOException("Missing bundled road preset "+name);
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public static JsonObject prepared(String name,UnaryOperator<String> resolve) {
        var part=raw(name);
        if(!STREET_DECORATIONS.contains(name)&&part.getAsJsonArray("slices").size()!=6)
            throw new IllegalStateException("Unexpected road scene height "+name);
        return prepare(thinVegetation(part,STREET_DECORATIONS.contains(name)?0:1),resolve);
    }
    public static boolean vegetation(String state){
        String id=base(state);
        return id.endsWith("_leaves")||id.endsWith("_leaf_pile")||Set.of("minecraft:fern","minecraft:large_fern",
            "minecraft:short_grass","minecraft:tall_grass","minecraft:dead_bush","minecraft:vine",
            "minecraft:moss_carpet","minecraft:moss_block","immersive_weathering:ivy").contains(id);
    }
    /** Apply once to original IDs, before compatibility substitutions or geometry rotation.
     * Keep whole vertical plant columns and nearby 2x2 patches; do not trim street furniture. */
    public static JsonObject thinVegetation(JsonObject part,int firstLayer){
        var plants=new HashSet<Character>();var leaves=new HashSet<Character>();char air=0;
        for(var row:part.getAsJsonObject("palette").getAsJsonArray("palette")){
            var e=row.getAsJsonObject();String id=e.get("block").getAsString();char c=e.get("char").getAsString().charAt(0);
            if(vegetation(id))plants.add(c);
            if(base(id).endsWith("_leaves"))leaves.add(c);
            if(id.equals("minecraft:air"))air=c;
        }
        var slices=part.getAsJsonArray("slices");var weights=new HashMap<Integer,Integer>();
        for(int y=firstLayer;y<slices.size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            char c=slices.get(y).getAsJsonArray().get(z).getAsString().charAt(x);
            if(plants.contains(c)&&(!leaves.contains(c)||y<=2))weights.merge(z*16+x,1,Integer::sum);
        }
        var columns=new ArrayList<>(weights.keySet());String name=part.has("name")?part.get("name").getAsString():"street";
        columns.sort(Comparator.comparingLong((Integer n)->plantHash(name.hashCode(),(n%16)/2,(n/16)/2)).thenComparingInt(n->n));
        int target=(int)Math.round(weights.values().stream().mapToInt(Integer::intValue).sum()*.30),retained=0;
        var keep=new HashSet<Integer>();
        for(int n:columns){int weight=weights.get(n);
            if(Math.abs(target-retained-weight)>Math.abs(target-retained))continue;
            keep.add(n);retained+=weight;
        }
        for(int y=firstLayer;y<slices.size();y++)for(int z=0;z<16;z++){
            var layer=slices.get(y).getAsJsonArray();char[] row=layer.get(z).getAsString().toCharArray();
            for(int x=0;x<16;x++)if(plants.contains(row[x])&&!keep.contains(z*16+x))row[x]=air;
            layer.set(z,new JsonPrimitive(new String(row)));
        }
        return part;
    }
    private static long plantHash(long seed,int x,int z){
        long h=seed^((long)x*0x9e3779b97f4a7c15L)^((long)z*0xc2b2ae3d27d4eb4fL);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return h^(h>>>31);
    }
    public static JsonObject prepare(JsonObject part,UnaryOperator<String> resolve) {
        var palette=part.getAsJsonObject("palette").getAsJsonArray("palette");
        Set<Character> leaves=new HashSet<>(),thinReplacements=new HashSet<>();Character air=null;
        Map<Character,String> blocks=new HashMap<>();
        for(var row:palette){var entry=row.getAsJsonObject();String source=entry.get("block").getAsString();
            if(source.contains("_leaves"))leaves.add(entry.get("char").getAsString().charAt(0));
            if(source.equals("minecraft:air"))air=entry.get("char").getAsString().charAt(0);
            entry.addProperty("block",resolve.apply(source));
            char symbol=entry.get("char").getAsString().charAt(0);
            String resolved=entry.get("block").getAsString();blocks.put(symbol,base(resolved));
            if(!base(source).equals(base(resolved))&&base(resolved).endsWith("_carpet"))thinReplacements.add(symbol);
            // Legacy block entity tags cannot follow a block substituted with another type.
            if(!base(source).equals(base(resolved)))entry.remove("tag");
            else if(entry.has("tag")){
                var tag=entry.getAsJsonObject("tag");tag.remove("x");tag.remove("y");tag.remove("z");
            }
        }
        if(air==null)throw new IllegalStateException("Preset has no air");
        var slices=part.getAsJsonArray("slices");
        if(part.get("xsize").getAsInt()!=16||part.get("zsize").getAsInt()!=16||slices.isEmpty()||slices.size()>8)
            throw new IllegalStateException("Unexpected street scene dimensions");
        for(int y=0;y<slices.size();y++){
            var layer=slices.get(y).getAsJsonArray();if(layer.size()!=16)throw new IllegalStateException("Invalid road row count");
            for(int z=0;z<16;z++){
                char[] row=layer.get(z).getAsString().toCharArray();if(row.length!=16)throw new IllegalStateException("Invalid road width");
                if(y>2)for(int x=0;x<16;x++)if(leaves.contains(row[x]))row[x]=air;
                // Small missing-mod models can become carpets. Do not leave those floating
                // above template air (or leaves removed by the height limit).
                if(y>0)for(int x=0;x<16;x++)if(thinReplacements.contains(row[x])){
                    char below=slices.get(y-1).getAsJsonArray().get(z).getAsString().charAt(x);
                    String support=blocks.get(below);
                    if("minecraft:air".equals(support)||"minecraft:structure_void".equals(support))row[x]=air;
                }
                layer.set(z,new JsonPrimitive(new String(row)));
            }
        }
        return part;
    }
    public static String base(String state){int bracket=state.indexOf('[');return bracket<0?state:state.substring(0,bracket);}
    /** Portable assets contain vanilla states only; runtime can retain installed decorations. */
    public static String fallback(String state) {
        String id=base(state);
        if(id.endsWith("oak_leaf_pile"))return "minecraft:moss_carpet";
        if(id.startsWith("minecraft:"))return state.replaceAll("(?<=\\[|,)weathering=[^,\\]]+,?","")
            .replace("[,","[").replace(",]","]").replace("[]","");
        if(id.startsWith("immersive_weathering:")){
            if(id.endsWith(":ivy"))return onlyProperties(state,"minecraft:vine",Set.of("up","east","west","north","south"));
            if(id.contains("wall"))return onlyProperties(state,"minecraft:mossy_cobblestone_wall",Set.of("up","east","west","north","south","waterlogged"));
            if(id.contains("stairs"))return withProperties(state,"minecraft:mossy_stone_brick_stairs");
            if(id.contains("slab"))return withProperties(state,"minecraft:mossy_stone_brick_slab");
            if(id.contains("mossy_stone"))return "minecraft:mossy_cobblestone";
            return "minecraft:moss_block";
        }
        if(id.contains("window_pane"))return onlyProperties(state,"minecraft:iron_bars",Set.of("east","west","north","south","waterlogged"));
        if(id.contains("parkbenches"))return onlyProperties(state,"minecraft:dark_oak_stairs",Set.of("facing","waterlogged"));
        if(id.contains("wiremesh")||id.contains("ironnetting"))return "minecraft:iron_bars";
        if(id.contains("sandbag"))return "minecraft:mud_brick_slab[type=bottom]";
        if(id.contains("crate")||id.contains("ammunitionbox"))return "minecraft:barrel";
        if(id.contains("trafficcone"))return "minecraft:orange_carpet";
        if(id.contains("glassshards"))return "minecraft:light_gray_carpet";
        if(id.contains("blood")||id.contains("remains")||id.endsWith("body"))return "minecraft:red_carpet";
        if(id.contains("hollowbrick"))return "minecraft:brick_slab[type=bottom]";
        if(id.contains("ironrod"))return "minecraft:chain[axis=x]";
        if(id.contains("oiltank"))return "minecraft:cauldron";
        if(id.contains("door"))return "minecraft:structure_void";
        return "minecraft:moss_carpet";
    }
    private static String withProperties(String state,String id){
        int bracket=state.indexOf('[');return id+(bracket<0?"":state.substring(bracket));
    }
    private static String onlyProperties(String state,String id,Set<String> allowed){
        int bracket=state.indexOf('[');if(bracket<0)return id;
        var properties=new ArrayList<String>();
        for(String pair:state.substring(bracket+1,state.length()-1).split(","))
            if(allowed.contains(pair.split("=",2)[0]))properties.add(pair);
        return properties.isEmpty()?id:id+"["+String.join(",",properties)+"]";
    }
    public static int generate(LcAssetGenerator.Output out,org.slf4j.Logger log) {
        try{
            var staged=new LcAssetGenerator.Output();
            for(var list:VARIANTS.values())for(String name:list)
                staged.putAsset("lostcities/parts/chaos_"+name+".json",prepared(name,ChaosRoadAssets::fallback));
            for(String name:STREET_DECORATIONS)
                staged.putAsset("lostcities/parts/chaos_"+name+".json",prepared(name,ChaosRoadAssets::fallback));
            out.files.putAll(staged.files);out.partCount+=staged.files.size();return staged.files.size();
        }catch(Exception e){log.warn("[CAF-LC] 路面预设加载失败，保留混合铺面",e);return 0;}
    }
}
