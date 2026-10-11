import com.caf.lccustomcity.config.HighwayStyleConfig;
import com.caf.lccustomcity.convert.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Actual 8.3.9 highway parts retain all geometry and receive the city's palette. */
public final class HighwaySurfaceSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        Path jar=Path.of(args[0]),sandbox=Path.of(args[1]);Files.createDirectories(sandbox);
        var log=org.slf4j.LoggerFactory.getLogger("HighwaySurfaceTest");
        HighwayStyleConfig.install(false);var off=new LcAssetGenerator.Output();
        check(HighwayStyleGenerator.generate(jar,off,log)==0&&off.files.isEmpty(),"config off");
        HighwayStyleConfig.install(true);var out=new LcAssetGenerator.Output();
        check(HighwayStyleGenerator.generate(jar,out,log)==6&&out.files.size()==6,"six actual highway overrides");
        var surface=new LcAssetGenerator.Output();RoadStyleGenerator.generateVacantSurface(surface);
        var palette=JsonParser.parseString(surface.files.get("caf_lc_customcity/lostcities/palettes/road_inner.json")).getAsJsonObject();
        JsonArray expected=null;
        for(var row:palette.getAsJsonArray("palette"))if(row.getAsJsonObject().get("char").getAsString().equals("l"))
            expected=row.getAsJsonObject().getAsJsonArray("blocks");
        try(var zip=new ZipFile(jar.toFile())){
            for(var file:out.files.entrySet()){
                check(file.getKey().contains("/parts/highway_"),"no shared palette override");
                JsonObject original;
                try(var in=zip.getInputStream(zip.getEntry("data/"+file.getKey()))){
                    original=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                }
                var part=JsonParser.parseString(file.getValue()).getAsJsonObject();
                var decoded=BuildingPartRE.CODEC.parse(JsonOps.INSTANCE,part).getOrThrow();
                check(decoded!=null,"real part codec");
                var local=part.getAsJsonObject("palette").getAsJsonArray("palette");
                check(local.size()==2,"only R and u are replaced");
                Set<String> chars=new HashSet<>();
                for(var row:local){var entry=row.getAsJsonObject();chars.add(entry.get("char").getAsString());
                    check(entry.getAsJsonArray("blocks").equals(expected),"highway ground mix differs from city surface");}
                check(chars.equals(Set.of("R","u")),"pavement and median use requested palette");
                var geometry=part.deepCopy();geometry.remove("palette");
                check(geometry.equals(original),"road shape, support and metadata must be preserved");
            }
        }
        LcAssetGenerator.writeTo(out,sandbox.resolve("pack"));
        for(String file:out.files.keySet())check(Files.exists(sandbox.resolve("pack/data").resolve(file)),"override missing on disk");
        var missing=new LcAssetGenerator.Output();
        check(HighwayStyleGenerator.generate(null,missing,log)==0&&missing.files.isEmpty(),"missing jar fallback");
        Path incomplete=sandbox.resolve("incomplete.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(incomplete))){
            zip.putNextEntry(new ZipEntry("data/lostcities/lostcities/parts/highway_open.json"));
            zip.write("{\"xsize\":16,\"zsize\":16,\"slices\":[]}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        check(HighwayStyleGenerator.generate(incomplete,missing,log)==0&&missing.files.isEmpty(),"missing part must publish no partial overrides");
        if(args.length>2){
            Path pack=Path.of(args[2]);
            for(String file:out.files.keySet())check(Files.exists(pack.resolve("data").resolve(file)),"startup did not generate highway override");
        }
        HighwayStyleConfig.install(HighwayStyleConfig.DEFAULT);
        System.out.println("Highway surface: six open/tunnel/bridge parts, identical city weights, real codec, intact geometry, atomic fallback PASS");
    }
}
