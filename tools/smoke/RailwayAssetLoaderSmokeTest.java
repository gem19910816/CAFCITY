import com.caf.lccustomcity.convert.RailwayAssetLoader;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

public final class RailwayAssetLoaderSmokeTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void rejected(Runnable r,String why){boolean rejected=false;try{r.run();}catch(IllegalArgumentException ex){rejected=true;}check(rejected,why);}
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]),jar=Path.of(args[1]);Files.createDirectories(base);
        RailwayAssetLoader.initialize(base);Path root=base.resolve(RailwayAssetLoader.DIRECTORY);
        check(Files.isDirectory(root.resolve("弯道与岔道")),"directory missing");
        Map<String,String> out=new LinkedHashMap<>();out.put("existing","preserved");List<String>w=new ArrayList<>();
        check(RailwayAssetLoader.load(base,jar,out,w)==0,"default must stay inactive");
        check(out.equals(Map.of("existing","preserved")),"disabled loader changed pack");check(w.isEmpty(),"unexpected default error");
        Path part=root.resolve("隧道/rails_horizontal.json"),palette=root.resolve("隧道/rails_horizontal.palette.json");
        JsonObject d=JsonParser.parseString(Files.readString(part)).getAsJsonObject(),pal=JsonParser.parseString(Files.readString(palette)).getAsJsonObject(),original;
        try(ZipFile z=new ZipFile(jar.toFile())){original=JsonParser.parseString(new String(z.getInputStream(z.getEntry("data/lostcities/lostcities/parts/rails_horizontal.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
        RailwayAssetLoader.validate("rails_horizontal",d,pal,original);check(true,"valid candidate");
        try(ZipFile zip=new ZipFile(jar.toFile());var files=Files.walk(root)){
            for(Path file:files.filter(f->f.toString().endsWith(".palette.json")).toList()){
                String name=file.getFileName().toString().replace(".palette.json","");
                JsonObject template=JsonParser.parseString(Files.readString(file.resolveSibling(name+".json"))).getAsJsonObject();
                JsonObject color=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonObject orig=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("data/lostcities/lostcities/parts/"+name+".json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                RailwayAssetLoader.validate(name,template,color,orig);check(true,name+" valid");
            }
        }
        JsonObject reversed=pal.deepCopy();for(JsonElement e:reversed.getAsJsonArray("palette")){var v=e.getAsJsonObject();if(v.get("block").getAsString().startsWith("minecraft:wall_torch["))v.addProperty("block","minecraft:wall_torch[facing=north]");}
        rejected(()->RailwayAssetLoader.validate("rails_horizontal",d,reversed,original),"unsupported torch accepted");
        JsonObject bad=d.deepCopy();bad.addProperty("xsize",32);rejected(()->RailwayAssetLoader.validate("rails_horizontal",bad,pal,original),"oversize accepted");
        JsonObject badHeight=d.deepCopy();badHeight.getAsJsonArray("slices").remove(5);rejected(()->RailwayAssetLoader.validate("rails_horizontal",badHeight,pal,original),"height accepted");
        JsonObject mask=d.deepCopy();String row=mask.getAsJsonArray("slices").get(2).getAsJsonArray().get(0).getAsString();mask.getAsJsonArray("slices").get(2).getAsJsonArray().set(0,new JsonPrimitive("X"+row.substring(1)));rejected(()->RailwayAssetLoader.validate("rails_horizontal",mask,pal,original),"write mask accepted");
        JsonObject noRail=d.deepCopy();String rr=noRail.getAsJsonArray("slices").get(1).getAsJsonArray().get(7).getAsString();String air="";for(JsonElement el:pal.getAsJsonArray("palette")){JsonObject v=el.getAsJsonObject();if(v.get("block").getAsString().equals("minecraft:air"))air=v.get("char").getAsString();}noRail.getAsJsonArray("slices").get(1).getAsJsonArray().set(7,new JsonPrimitive(air+rr.substring(1)));rejected(()->RailwayAssetLoader.validate("rails_horizontal",noRail,pal,original),"broken main line accepted");
        String custom=Files.readString(part);Files.writeString(part,custom+"\n");RailwayAssetLoader.initialize(base);check(Files.readString(part).equals(custom+"\n"),"user template overwritten");
        Files.writeString(root.resolve("铁路配置.json"),"{\"格式版本\":1,\"启用铁路替换\":true}");w.clear();check(RailwayAssetLoader.load(base,jar,out,w)==26,"enabled resources missing: "+w);check(out.size()==27,"output resource count");check(w.isEmpty(),"valid network rejected");check(RailwayAssetLoader.active(),"runtime plan missing");check(RailwayAssetLoader.plan("rails_down2").size()==16,"slope track discontinuous");
        Path missing=root.resolve("弯道与岔道/rails_bend.json");String valid=Files.readString(missing);Files.writeString(missing,"{}");
        Map<String,String> isolated=new LinkedHashMap<>();isolated.put("existing","preserved");w.clear();
        check(RailwayAssetLoader.load(base,jar,isolated,w)==0,"partial set activated");check(isolated.size()==1&&!RailwayAssetLoader.active(),"partial plans leaked");
        Files.writeString(missing,valid);
        Files.writeString(root.resolve("铁路配置.json"),"{ broken");w.clear();check(RailwayAssetLoader.load(base,jar,out,w)==0&&!w.isEmpty(),"malformed config did not fail safely");
        if(args.length>2){Path live=Path.of(args[2]);RailwayAssetLoader.initialize(live);w.clear();check(RailwayAssetLoader.load(live,jar,new LinkedHashMap<>(),w)==26,"live railway pack failed: "+w);check(w.isEmpty(),"live initialization failed");}
        System.out.println("PASS RailwayAssetLoaderSmokeTest checks="+checks);
    }
}
