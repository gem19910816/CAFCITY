package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.nbt.NbtReader;
import com.caf.lccustomcity.nbt.NbtWriter;
import com.caf.lccustomcity.schem.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/** Only documented world coordinates move; contraption-local geometry stays intact. */
public final class DynamicNbt {
    public static final String ENTITY_KEY = "caf_entities_v1";
    public static final String BE_KEY = "caf_create_nbt_v1";
    private static final Map<SchematicData,double[]> ORIGINS=Collections.synchronizedMap(new WeakHashMap<>());
    private DynamicNbt() {}
    public static Map<String,Object> copy(Map<String,Object> tag) {
        try { return NbtReader.asCompound(NbtReader.readRoot(new DataInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(NbtWriter.encode(tag)))))); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public static double[] vector(Object v) {
        if(v instanceof int[] a && a.length==3)return new double[]{a[0],a[1],a[2]};
        if(v instanceof List<?> a && a.size()==3 && a.stream().allMatch(Number.class::isInstance))
            return new double[]{((Number)a.get(0)).doubleValue(),((Number)a.get(1)).doubleValue(),((Number)a.get(2)).doubleValue()};
        return null;
    }
    public static double[] sourceOrigin(SchematicData d) {
        double[] cached=ORIGINS.get(d);if(cached!=null)return cached.length==0?null:cached.clone();
        double[] origin=detectOrigin(d);
        ORIGINS.put(d,origin==null?new double[0]:origin);
        return origin==null?null:origin.clone();
    }
    private static double[] detectOrigin(SchematicData d) {
        for(var e:d.entities) {
            double[] p=vector(e.nbt.get("Pos"));
            if(p!=null)return new double[]{p[0]-e.x,p[1]-e.y,p[2]-e.z};
        }
        // Create also records the exporting position on tanks and vaults. Require
        // multiple independent entries to agree; stale/disagreeing values are ignored.
        double[] origin=null;int matches=0;
        for(var e:d.blockEntities.entrySet()){
            if(!String.valueOf(e.getValue().get("id")).startsWith("create:"))continue;
            double[] p=vector(e.getValue().get("LastKnownPos"));if(p==null)continue;
            int index=e.getKey(),x=index%d.sizeX,z=index/d.sizeX%d.sizeZ,y=index/d.sizeX/d.sizeZ;
            double[] candidate={p[0]-x,p[1]-y,p[2]-z};
            if(origin==null)origin=candidate;
            else if(!Arrays.equals(origin,candidate))return null;
            matches++;
        }
        return matches>=2?origin:null;
    }
    private static void moveVector(Map<String,Object> tag,String key,double x,double y,double z) {
        Object v=tag.get(key); double[] p=vector(v); if(p==null)return;
        tag.put(key,v instanceof int[]?new int[]{(int)(p[0]+x),(int)(p[1]+y),(int)(p[2]+z)}:
                List.of(p[0]+x,p[1]+y,p[2]+z));
    }
    public static void moveEntity(Map<String,Object> tag,double x,double y,double z) {
        moveVector(tag,"Pos",x,y,z);
        for(var e:Map.of("TileX",x,"TileY",y,"TileZ",z).entrySet())
            if(tag.get(e.getKey()) instanceof Number n)tag.put(e.getKey(),n.intValue()+(int)Math.round(e.getValue()));
        var c=NbtReader.compound(tag,"Contraption");
        if(c!=null) {
            moveVector(c,"Anchor",x,y,z);
            for(String key:List.of("MinContactY","MaxContactY"))
                if(c.get(key) instanceof Number n)c.put(key,n.intValue()+(int)Math.round(y));
            var actors=NbtReader.list(c,"Actors");
            if(actors!=null)for(Object a:actors){var m=NbtReader.asCompound(a);if(m!=null){
                moveVector(m,"Position",x,y,z);
                var data=NbtReader.compound(m,"Data");if(data!=null)moveVector(data,"lastContact",x,y,z);
            }}
        }
        var passengers=NbtReader.list(tag,"Passengers");
        if(passengers!=null)for(Object a:passengers){var m=NbtReader.asCompound(a);if(m!=null)moveEntity(m,x,y,z);}
    }
    public static void moveBlockEntity(Map<String,Object> tag,double x,double y,double z) {
        for(String key:List.of("Source","Controller","MirrorParent","LastKnownPos"))moveVector(tag,key,x,y,z);
        var children=NbtReader.list(tag,"MirrorChildren");
        if(children!=null)for(Object a:children){var m=NbtReader.asCompound(a);if(m!=null)moveVector(m,"Pos",x,y,z);}
        for(String side:List.of("north","east","south","west","up","down")){
            var connection=NbtReader.compound(tag,side);if(connection==null)continue;
            var open=NbtReader.compound(connection,"OpenEnd");if(open==null)continue;
            var location=NbtReader.compound(open,"Location");if(location!=null)moveVector(location,"Pos",x,y,z);
        }
        if(tag.get("LastKnownPosition") instanceof Long position){
            long px=(position>>38)+(long)x,py=(position<<52>>52)+(long)y,pz=(position<<26>>38)+(long)z;
            tag.put("LastKnownPosition",((px&0x3ffffffL)<<38)|((pz&0x3ffffffL)<<12)|(py&0xfffL));
        }
        // Old kinetic network ids and speed sources belong to the exporting world.
        boolean kinetic=tag.containsKey("Network")||tag.containsKey("Source")
                ||tag.containsKey("Speed")||tag.containsKey("NeedsSpeedUpdate");
        tag.remove("Network");
        if(kinetic)tag.put("NeedsSpeedUpdate",(byte)1);
    }
    public static Map<String,Object> paletteTag(SchematicData d,int x,int y,int z) {
        var raw=d.blockEntityAt(x,y,z); if(raw==null)return null;
        if(!String.valueOf(raw.get("id")).startsWith("create:"))return raw;
        double[] o=sourceOrigin(d);
        var tag=copy(raw);
        if(o!=null)moveBlockEntity(tag,-o[0]-x,-o[1]-y,-o[2]-z);
        else {
            // No reliable world origin: retain machine settings and typed arrays,
            // but force kinetic links to rebuild instead of reusing foreign sources.
            boolean kinetic=tag.containsKey("Network")||tag.containsKey("Source")
                    ||tag.containsKey("Speed")||tag.containsKey("NeedsSpeedUpdate");
            tag.remove("Network");tag.remove("Source");
            if(kinetic)tag.put("NeedsSpeedUpdate",(byte)1);
        }
        var result=new LinkedHashMap<>(raw);
        result.put(BE_KEY,NbtWriter.encode(tag));
        if(o==null)result.put("caf_create_nbt_local_v1",(byte)1);
        return result;
    }
    public static void addEntities(JsonObject part,SchematicData d,int sx,int sy,int sz,int slices) {
        addEntities(part,d,sx,sy,sz,slices,0,0,0,0,0,0,0);
    }
    /** Each rotation routes boundary entities to the part that will actually own their position. */
    public static void addEntities(JsonObject part,SchematicData d,int sx,int sy,int sz,int slices,
            int gridX,int gridZ,int cellsX,int cellsZ,int cellX,int cellZ,int turn) {
        List<Object> tags=new ArrayList<>();
        for(var e:d.entities) {
            if(e.y<sy || e.y>=sy+slices)continue;
            if(turn==0){if(e.x<sx || e.x>=sx+16 || e.z<sz || e.z>=sz+16)continue;}
            else {
                double px=e.x-gridX,pz=e.z-gridZ;
                boolean contraption=e.nbt.containsKey("Contraption");
                int adjustment=contraption?1:0;double w=cellsX*16-adjustment,h=cellsZ*16-adjustment;
                double rx=switch(turn){case 1->h-pz;case 2->w-px;case 3->pz;default->px;};
                double rz=switch(turn){case 1->px;case 2->h-pz;case 3->w-px;default->pz;};
                int destinationX=com.caf.lccustomcity.config.BuildingRotation.x(cellX,cellZ,cellsX,cellsZ,turn);
                int destinationZ=com.caf.lccustomcity.config.BuildingRotation.z(cellX,cellZ,cellsX,cellsZ,turn);
                if(Math.floor(rx/16)!=destinationX||Math.floor(rz/16)!=destinationZ)continue;
            }
            var tag=copy(e.nbt); double[] old=vector(tag.get("Pos"));
            if(old!=null)moveEntity(tag,e.x-sx-old[0],e.y-sy-old[1],e.z-sz-old[2]);
            tag.put("Pos",List.of(e.x-sx,e.y-sy,e.z-sz));
            tags.add(tag);
        }
        if(tags.isEmpty())return;
        JsonObject meta=new JsonObject(); meta.addProperty("key",turn==0?ENTITY_KEY:ENTITY_KEY+"_r"+turn);
        meta.addProperty("string",NbtWriter.encode(Map.of("Entities",tags,"SourcePart",new int[]{sx,sy,sz})));
        JsonArray metadata=part.has("meta")?part.getAsJsonArray("meta"):new JsonArray();metadata.add(meta);part.add("meta",metadata);
    }
}
