import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.convert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class CityDistrictConversionAudit {
 public static void main(String[] a)throws Exception {
  Path root=Path.of(a[0]),out=Path.of(a[1]);var warnings=new ArrayList<String>();
  var cfg=CityStageConfig.load(root,warnings);CityStageConfig.install(cfg);
  var log=org.slf4j.LoggerFactory.getLogger("DistrictAudit");
  var result=LcAssetGenerator.generate(root,log);
  if(!result.errors.isEmpty())throw new AssertionError(result.errors);
  CityStylePatcher.patch(Path.of(a[2]),result,log);
  for(String stage:List.of("小城","大城")){
   var buildings=result.footprints.stream().filter(b->stage.equals(b.stage())).toList();
   if(buildings.size()!=(stage.equals("小城")?85:97))throw new AssertionError("Missing source buildings "+stage);
   for(var p:cfg.stages().get(stage).partitions().keySet()){
    String style=CityStageConfig.cityStyleName(stage,"西式风格","zone_"+LcAssetGenerator.shortHash(p));
    var json=JsonParser.parseString(result.files.get("lostcities/lostcities/citystyles/"+style+".json")).getAsJsonObject();
    var selectors=json.getAsJsonObject("selectors");
    Set<String> listed=new HashSet<>();for(String kind:List.of("buildings","multibuildings"))for(var item:selectors.getAsJsonArray(kind))listed.add(item.getAsJsonObject().get("value").getAsString());
    var expected=buildings.stream().filter(b->p.equals(b.zone())).map(LayoutCatalog.Building::id).collect(java.util.stream.Collectors.toSet());
    if(!listed.equals(expected))throw new AssertionError("Wrong district selectors "+p);
    System.out.println(stage+" / "+p+" assets="+expected.size());
   }
  }
  var summary=new JsonObject();summary.addProperty("source_count",result.footprints.size());summary.addProperty("generated_json_count",result.files.size());summary.addProperty("errors",result.errors.size());summary.add("warnings",new Gson().toJsonTree(result.warnings));summary.addProperty("runtime_verified",false);
  Files.writeString(out.resolve("conversion-audit.json"),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(summary));
  Path pack=out.resolve("verified-assets");for(var e:result.files.entrySet()){Path p=pack.resolve("data").resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());}
  System.out.println("PASS source conversion and district selectors; source_count="+result.footprints.size());
 }
}
