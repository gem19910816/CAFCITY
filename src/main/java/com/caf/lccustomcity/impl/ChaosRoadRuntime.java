package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.ChaosRoadAssets;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.Transform;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;
import java.util.function.BiPredicate;

/** Registry-aware scene preparation happens after mod blocks have registered. */
public final class ChaosRoadRuntime {
    private static final Map<String,BuildingPart> PARTS=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,BuildingPart> entry){return size()>512;}
    };
    private static final Set<String> WARNED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("CAF-LC-RoadPresets");
    private ChaosRoadRuntime(){}
    public static boolean protectedSurface(ChunkCoord c,IDimensionInfo p,LostCityProfile profile){
        return CityLayout.predefined(c,p)||BuildingInfo.hasHighway(c,p,profile)
            ||((profile.RAILWAYS_ENABLED||profile.RAILWAY_STATIONS_ENABLED)&&BuildingInfo.hasRailwayAtSurface(c,p,profile));
    }
    public static boolean vacant(ChunkCoord c,IDimensionInfo p,LostCityProfile profile){
        var cell=CityLayout.cell(c,p,profile);
        return cell!=null&&cell.placement()==null&&!protectedSurface(c,p,profile);
    }
    public static boolean frontage(ChunkCoord c,IDimensionInfo p,LostCityProfile profile){
        if(!CityLayout.enabled(p,profile))return false;
        for(var offset:new int[][]{{0,-1},{0,1},{-1,0},{1,0}}){
            var neighbour=CityLayout.cell(new ChunkCoord(c.dimension(),c.chunkX()+offset[0],c.chunkZ()+offset[1]),p,profile);
            if(neighbour!=null&&BuildingFrontage.at(neighbour.placement(),c.chunkX(),c.chunkZ()))return true;
        }
        return false;
    }
    public static ChaosRoadPlan.Selection select(ChunkCoord c,IDimensionInfo p,LostCityProfile profile){
        return select(p.getSeed(),c.chunkX(),c.chunkZ(),(x,z)->vacant(new ChunkCoord(c.dimension(),x,z),p,profile));
    }
    public static ChaosRoadPlan.Selection select(long seed,int x,int z,BiPredicate<Integer,Integer> vacant){
        int mask=(vacant.test(x-1,z)?ChaosRoadPlan.WEST:0)|(vacant.test(x+1,z)?ChaosRoadPlan.EAST:0)
            |(vacant.test(x,z-1)?ChaosRoadPlan.NORTH:0)|(vacant.test(x,z+1)?ChaosRoadPlan.SOUTH:0);
        boolean open=mask==15&&vacant.test(x-1,z-1)&&vacant.test(x+1,z-1)
            &&vacant.test(x-1,z+1)&&vacant.test(x+1,z+1);
        return ChaosRoadPlan.select(seed,x,z,mask,open);
    }
    public static Transform transform(int turns){
        return switch(turns){case 1->Transform.ROTATE_90;case 2->Transform.ROTATE_180;
            case 3->Transform.ROTATE_270;default->Transform.ROTATE_NONE;};
    }
    public static BuildingPart part(String name){return part(name,0);}
    public static synchronized BuildingPart part(String name,int turns){
        return part(name,turns,null);
    }
    public static synchronized BuildingPart part(String name,int turns,ChaosStreetDecor.Selection decor){
        return part(name,turns,decor,false);
    }
    public static synchronized BuildingPart part(String name,int turns,ChaosStreetDecor.Selection decor,boolean frontage){
        if(frontage&&decor!=null&&!decor.defence())decor=null;
        String key=name+"_r"+Math.floorMod(turns,4)+(decor==null?"":"_"+decor.name()+"_r"+decor.turns()+"_p"+decor.connections())
                +(frontage?"_front":"");
        var cached=PARTS.get(key);if(cached!=null)return cached;
        try{
            var re=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,
                    ChaosStreetComposer.compose(prepared(name,turns,frontage),decor,frontage)).getOrThrow();
            re.setRegistryName(ResourceLocation.parse(ChaosRoadAssets.id(key)));
            var part=new BuildingPart(re);PARTS.put(key,part);return part;
        }catch(Exception e){
            if(WARNED.add(name))LOG.warn("[CAF-LC] 路面模板 {} 不可用，回退混合地面",name,e);
            return null;
        }
    }
    public static JsonObject prepared(String name){return prepared(name,0);}
    /** Pre-rotate geometry AND states: LC's rotatable tag does not cover every mod decoration. */
    public static JsonObject prepared(String name,int turns){
        return prepared(name,turns,false);
    }
    public static JsonObject prepared(String name,int turns,boolean frontage){
        var part=frontage
                ?ChaosRoadAssets.prepare(ChaosStreetComposer.frontageOnly(ChaosRoadAssets.thinVegetation(ChaosRoadAssets.raw(name),1)),ChaosRoadRuntime::resolveState)
                :ChaosRoadAssets.prepared(name,ChaosRoadRuntime::resolveState);
        return rotate(part,turns);
    }
    public static JsonObject rotate(JsonObject part,int turns){
        var rotation=transform(Math.floorMod(turns,4));
        if(rotation==Transform.ROTATE_NONE)return part;
        for(var row:part.getAsJsonObject("palette").getAsJsonArray("palette")){
            var entry=row.getAsJsonObject();
            var state=parseState(entry.get("block").getAsString());
            entry.addProperty("block",stateString(state.rotate(rotation.getMcRotation())));
        }
        var slices=part.getAsJsonArray("slices");
        for(int y=0;y<slices.size();y++){
            var layer=slices.get(y).getAsJsonArray();char[][] rotated=new char[16][16];
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                rotated[rotation.rotateZ(x,z)][rotation.rotateX(x,z)]=layer.get(z).getAsString().charAt(x);
            for(int z=0;z<16;z++)layer.set(z,new com.google.gson.JsonPrimitive(new String(rotated[z])));
        }
        return part;
    }
    public static ChaosStreetDecor.Selection decoration(ChunkCoord c,IDimensionInfo p,LostCityProfile profile,ChaosRoadPlan.Selection road){
        if(!com.caf.lccustomcity.config.RoadPresetConfig.decorations())return null;
        var group=ChaosDefencePlan.select(p.getSeed(),c.chunkX(),c.chunkZ(),(x,z)->{
            var coord=new ChunkCoord(c.dimension(),x,z);
            return vacant(coord,p,profile)&&!frontage(coord,p,profile);
        });
        if(group!=null)return group.decoration();
        int mask=0;int[][] neighbours={{-1,0,1},{1,0,2},{0,-1,4},{0,1,8}};
        for(var n:neighbours){
            var coord=new ChunkCoord(c.dimension(),c.chunkX()+n[0],c.chunkZ()+n[1]);
            var cell=CityLayout.cell(coord,p,profile);
            if(cell!=null&&cell.placement()!=null&&!protectedSurface(coord,p,profile))mask|=n[2];
        }
        return ChaosStreetDecor.select(p.getSeed(),c.chunkX(),c.chunkZ(),road.shape().equals("full"),mask);
    }
    public static String resolveState(String source){
        String id=ChaosRoadAssets.base(source);
        if(id.endsWith("oak_leaf_pile"))source="minecraft:moss_carpet";
        else if(!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id))){
            if(WARNED.add(id))LOG.info("[CAF-LC] 路面装饰方块 {} 未安装，使用兼容替代",id);
            source=ChaosRoadAssets.fallback(source);
        }
        return stateString(parseState(source));
    }
    private static BlockState parseState(String source){
        BlockState state=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(ChaosRoadAssets.base(source))).defaultBlockState();
        int bracket=source.indexOf('[');
        if(bracket>=0)for(String pair:source.substring(bracket+1,source.length()-1).split(",")){
            int equals=pair.indexOf('=');if(equals<0)continue;
            var property=state.getBlock().getStateDefinition().getProperty(pair.substring(0,equals));
            if(property!=null)state=apply(state,property,pair.substring(equals+1));
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState apply(BlockState state,Property<T> property,String value){
        return property.getValue(value).map(v->state.setValue(property,v)).orElse(state);
    }
    private static <T extends Comparable<T>> String value(BlockState state,Property<T> property){
        return property.getName(state.getValue(property));
    }
    private static String stateString(BlockState state){
        String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var properties=new TreeMap<String,String>();
        for(var property:state.getProperties())properties.put(property.getName(),value(state,property));
        return properties.isEmpty()?name:name+"["+String.join(",",properties.entrySet().stream()
            .map(e->e.getKey()+"="+e.getValue()).toList())+"]";
    }
}
