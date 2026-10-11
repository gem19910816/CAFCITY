package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.convert.ChaosRoadAssets;
import com.google.gson.*;
import java.util.*;

/** Compose before placement, so template air cannot clear terrain or a neighbouring building. */
public final class ChaosStreetComposer {
    private ChaosStreetComposer() {}
    public static JsonObject compose(JsonObject road,ChaosStreetDecor.Selection selected){
        return compose(road,selected,false);
    }
    /** The caller has already filtered the road when this is a building frontage. */
    public static JsonObject compose(JsonObject road,ChaosStreetDecor.Selection selected,boolean frontage){
        if(frontage&&selected!=null&&!selected.defence())selected=null;
        var result=road.deepCopy();if(selected==null)return result;
        var raw=ChaosRoadAssets.raw(selected.name());
        ChaosRoadAssets.thinVegetation(raw,selected.defence()?1:0);
        if(frontage)raw=frontageOnly(raw);
        var overlay=ChaosRoadRuntime.rotate(ChaosRoadAssets.prepare(raw,ChaosRoadRuntime::resolveState),selected.turns());
        var palette=result.getAsJsonObject("palette").getAsJsonArray("palette");
        Map<Character,String> sourceStates=new HashMap<>();Map<Character,Character> symbols=new HashMap<>();
        Set<Character> used=new HashSet<>();char air=0,next=256;
        for(var value:palette){var e=value.getAsJsonObject();char symbol=e.get("char").getAsString().charAt(0);used.add(symbol);
            if(e.get("block").getAsString().equals("minecraft:air"))air=symbol;
        }
        for(var value:overlay.getAsJsonObject("palette").getAsJsonArray("palette")){
            var e=value.getAsJsonObject();char symbol=e.get("char").getAsString().charAt(0);sourceStates.put(symbol,e.get("block").getAsString());
            if(e.get("block").getAsString().equals("minecraft:air")){symbols.put(symbol,air);continue;}
            while(used.contains(next))next++;var entry=e.deepCopy();entry.addProperty("char",String.valueOf(next));
            palette.add(entry);symbols.put(symbol,next);used.add(next++);
        }
        int first=selected.defence()?1:0,offset=selected.defence()?0:1;
        int minX=16,minZ=16,maxX=-1,maxZ=-1;
        var layers=overlay.getAsJsonArray("slices");
        for(int y=first;y<layers.size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            char c=layers.get(y).getAsJsonArray().get(z).getAsString().charAt(x);
            if(solid(sourceStates.get(c))){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);}
        }
        var slices=result.getAsJsonArray("slices");
        while(slices.size()<layers.size()+offset){var layer=new JsonArray();for(int z=0;z<16;z++)layer.add(String.valueOf(air).repeat(16));slices.add(layer);}
        for(int y=first;y<layers.size();y++)for(int z=minZ;z<=maxZ;z++){
            char[] row=slices.get(y+offset).getAsJsonArray().get(z).getAsString().toCharArray();
            String from=layers.get(y).getAsJsonArray().get(z).getAsString();
            for(int x=minX;x<=maxX;x++)row[x]=symbols.getOrDefault(from.charAt(x),air);
            slices.get(y+offset).getAsJsonArray().set(z,new JsonPrimitive(new String(row)));
        }
        if(selected.connections()!=0)connectDefence(result,selected.connections());
        // Cap leaves and remove unsupported compatibility carpets in the final composition.
        return ChaosRoadAssets.prepare(result,s->s);
    }
    /** Shared sandbag arms cross chunk edges; the group reads as one emplacement. */
    private static void connectDefence(JsonObject result,int ports){
        var palette=result.getAsJsonObject("palette").getAsJsonArray("palette");
        char symbol=512;var used=new HashSet<Character>();
        for(var entry:palette)used.add(entry.getAsJsonObject().get("char").getAsString().charAt(0));
        while(used.contains(symbol))symbol++;
        var sandbag=new JsonObject();sandbag.addProperty("char",String.valueOf(symbol));
        sandbag.addProperty("block",ChaosRoadRuntime.resolveState("doomsday_decoration:sandbag"));palette.add(sandbag);
        var layer=result.getAsJsonArray("slices").get(1).getAsJsonArray();
        for(int z=0;z<16;z++){
            char[] row=layer.get(z).getAsString().toCharArray();
            for(int x=0;x<16;x++)if(((ports&1)!=0&&x<=4&&(z==5||z==10))
                ||((ports&2)!=0&&x>=11&&(z==5||z==10))
                ||((ports&4)!=0&&z<=4&&(x==5||x==10))
                ||((ports&8)!=0&&z>=11&&(x==5||x==10)))row[x]=symbol;
            layer.set(z,new JsonPrimitive(new String(row)));
        }
    }
    /** Filter original IDs before registry fallback: a sandbag slab must stay identifiable. */
    public static JsonObject frontageOnly(JsonObject source){
        var result=source.deepCopy();var allowed=new HashSet<Character>();Character air=null;
        for(var value:result.getAsJsonObject("palette").getAsJsonArray("palette")){
            var entry=value.getAsJsonObject();String id=ChaosRoadAssets.base(entry.get("block").getAsString());
            char symbol=entry.get("char").getAsString().charAt(0);
            if(id.equals("minecraft:air"))air=symbol;
            if(id.endsWith("_leaves")||id.contains("sandbag"))allowed.add(symbol);
        }
        if(air==null)throw new IllegalArgumentException("Street scene has no air");
        var slices=result.getAsJsonArray("slices");
        for(int y=1;y<slices.size();y++)for(int z=0;z<16;z++){
            var layer=slices.get(y).getAsJsonArray();char[] row=layer.get(z).getAsString().toCharArray();
            for(int x=0;x<16;x++)if(!allowed.contains(row[x]))row[x]=air;
            layer.set(z,new JsonPrimitive(new String(row)));
        }
        return result;
    }
    private static boolean solid(String state){return state!=null&&!state.equals("minecraft:air")&&!state.equals("minecraft:structure_void");}
}
