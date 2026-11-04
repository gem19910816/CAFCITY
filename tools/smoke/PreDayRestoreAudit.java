import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.convert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Run the original binary's parser and asset converter against the original config. */
public class PreDayRestoreAudit {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),out=Path.of(args[1]);
  var warnings=new ArrayList<String>();
  var cfg=CityStageConfig.load(root,warnings);CityStageConfig.install(cfg);
  for(String stage:List.of("小城","大城")){
   var rules=StageLayoutConfig.loadForStage(root,stage,warnings,800,800);
   int min=stage.equals("小城")?500:800,max=stage.equals("小城")?600:800;
   if(rules.radiusMinBlocks()!=min||rules.radiusMaxBlocks()!=max)throw new AssertionError("baseline radius");
  }
  var converted=LcAssetGenerator.generate(root,org.slf4j.LoggerFactory.getLogger("PreDayRestoreAudit"));
  if(!converted.errors.isEmpty())throw new AssertionError(converted.errors);
  if(converted.footprints.size()!=255)throw new AssertionError("missing source buildings");
  for(String stage:List.of("乡村","小镇","小城","大城")){
   long count=converted.footprints.stream().filter(b->stage.equals(b.stage())).count();
   int expected=switch(stage){case "乡村"->30;case "小镇"->43;case "小城"->85;default->97;};
   if(count!=expected)throw new AssertionError(stage+" source count "+count);
  }
  if(!warnings.isEmpty())throw new AssertionError(warnings);
  var unexpected=converted.warnings.stream().filter(w->!(w.startsWith("阶段 小镇 / 风格 西式风格 没有单格建筑")||w.startsWith("阶段 乡村 / 风格 西式风格 没有单格建筑")||w.startsWith("内城没有任何 16x16 以内的单格建筑"))).toList();
  if(!unexpected.isEmpty())throw new AssertionError(unexpected);
  var report=new JsonObject();report.addProperty("baseline_version","1.0.204");
  report.addProperty("errors",0);report.addProperty("footprints",converted.footprints.size());
  report.addProperty("generated_assets",converted.files.size());report.addProperty("runtime_tested",false);
  report.add("warnings",new Gson().toJsonTree(converted.warnings));
  Files.writeString(out,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report));
  System.out.println("PASS 1.0.204 config parser / original T3-T4 radii / all 255 original building sources; no game launch");
 }
}
