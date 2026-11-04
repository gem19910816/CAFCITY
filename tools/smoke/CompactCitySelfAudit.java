import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.impl.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Real source conversion followed by independent full-footprint and configured-limit checks. */
public class CompactCitySelfAudit {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
 static String signature(Set<UniquePlotPacking.Placement> all){
  return all.stream().map(p->p.building().id()+":"+p.x()+":"+p.z()+":"+p.building().turns()).sorted().toList().toString();
 }
 static int emptySquare(CityLayout.Cell[][] cells,int n,int rad){
  int size=cells.length,best=0;int[][] squares=new int[size+1][size+1];
  for(int x=1;x<=size;x++)for(int z=1;z<=size;z++)
   if(cells[x-1][z-1].placement()==null && Math.hypot((x-1-n)*16.0,(z-1-n)*16.0)<rad-16){
    squares[x][z]=1+Math.min(squares[x-1][z-1],Math.min(squares[x-1][z],squares[x][z-1]));best=Math.max(best,squares[x][z]);
   }
  return best;
 }
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]),lcJar=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  var warnings=new ArrayList<String>();var cfg=CityStageConfig.load(root,warnings);CityStageConfig.install(cfg);
  JsonObject stagePools=args.length>3?JsonParser.parseString(Files.readString(Path.of(args[3]))).getAsJsonObject():null;
  var log=org.slf4j.LoggerFactory.getLogger("CompactCitySelfAudit");
  var converted=LcAssetGenerator.generate(root,log);
  if(!converted.errors.isEmpty())throw new AssertionError(converted.errors);
  CityStylePatcher.patch(lcJar,converted,log);
  BuildingCountLimits.install(converted.countLimitsSingle,converted.countLimitsMulti);
  var results=new JsonArray();int checkedBuildings=0;int countEntries=0;
  for(String stage:args.length>4?List.of(args[4]):List.of("小城","大城")){
   var buildings=converted.footprints.stream().filter(b->stage.equals(b.stage())).toList();
   if(buildings.size()!=(stage.equals("小城")?85:97))throw new AssertionError("conversion lost sources "+stage);
   var rules=StageLayoutConfig.loadForStage(root,stage,warnings,800,800);
   if(!rules.uniqueFirstBuildingRound() || rules.sameBuildingSpacing()!=10)throw new AssertionError("layout flags");
   var geometry=CityStageRuntime.geometry(cfg.stages().get(stage),"西式风格");
   if(geometry==null || geometry.radiusMinBlocks()!=rules.radiusMinBlocks() || geometry.radiusMaxBlocks()!=rules.radiusMaxBlocks() || !geometry.layout().buildings().equals(rules.buildings()))throw new AssertionError("runtime geometry lost config");
   for(var style:buildings.stream().map(LayoutCatalog.Building::style).distinct().toList()){
    var styleJson=JsonParser.parseString(converted.files.get("lostcities/lostcities/citystyles/"+style+".json")).getAsJsonObject();
    var selectors=styleJson.getAsJsonObject("selectors");var listed=new HashSet<String>();
    for(String kind:List.of("buildings","multibuildings"))for(var item:selectors.getAsJsonArray(kind))listed.add(item.getAsJsonObject().get("value").getAsString());
    var expected=buildings.stream().filter(b->style.equals(b.style())).map(LayoutCatalog.Building::id).collect(java.util.stream.Collectors.toSet());
    if(!listed.equals(expected))throw new AssertionError("style source selector mismatch");
   }
   for(var b:buildings){
    var rule=rules.building(b.sourceFile());if(rule==null)throw new AssertionError("missing rule "+b.sourceFile());
    var registered=(b.multi()?BuildingCountLimits.forStyleMulti(b.style()):BuildingCountLimits.forStyleSingle(b.style())).get(b.id());
    if(rule.hasCount() && (registered==null || registered.min()!=rule.minimumCount() || registered.max()!=rule.maximumCount()))throw new AssertionError("cap registration "+b.sourceFile());
    String asset="caf_lc_customcity/lostcities/"+(b.multi()?"multibuildings/":"buildings/")+b.id().substring(b.id().indexOf(':')+1)+".json";
    if(!converted.files.containsKey(asset))throw new AssertionError("asset missing "+asset);
    if(rule.hasCount())countEntries++;checkedBuildings++;
   }
   var snapshot=new LayoutCatalog.Snapshot(buildings,Map.of(),0,1);String style=buildings.getFirst().style();
   for(int rad:new TreeSet<>(List.of(rules.radiusMinBlocks(),(rules.radiusMinBlocks()+rules.radiusMaxBlocks())/2,rules.radiusMaxBlocks())))
    for(long seed:new long[]{7,103,20261031})for(boolean blocked:new boolean[]{false,true}){
     int n=(rad+15)/16,size=n*2+1;var center=new CityLayout.Center(8,8,rad);
     java.util.function.BiPredicate<Integer,Integer> available=(x,z)->!blocked || (x!=n && z!=n+5 && !(x>=n+7&&x<n+10&&z>=n-4&&z<n+1));
     java.util.function.BiFunction<Integer,Integer,CityLayout.District> district=(x,z)->Math.hypot((x-n)*16.0,(z-n)*16.0)>rad?null:new CityLayout.District(center,style,false,false,stage,true,false);
     long start=System.nanoTime();
     var cells=CityPlotPlan.create(size,size,seed,snapshot,district,available,(b,x,z)->true,rules,cfg.stages().get(stage).partitions(),1);
     long ms=(System.nanoTime()-start)/1_000_000;var all=new HashSet<UniquePlotPacking.Placement>();
     for(var row:cells)for(var cell:row)if(cell.placement()!=null)all.add(cell.placement());
     var counts=new TreeMap<String,Integer>();
     for(var p:all){
      var b=p.building();counts.merge(b.sourceFile(),1,Integer::sum);
      for(int x=p.x();x<p.x()+b.width();x++)for(int z=p.z();z<p.z()+b.depth();z++)
       if(!p.equals(cells[x][z].placement())||!available.test(x,z)||district.apply(x,z)==null)throw new AssertionError("overlap/outside/reserved "+p);
     }
     for(var b:buildings){var rule=rules.building(b.sourceFile());int count=counts.getOrDefault(b.sourceFile(),0);
      if(count<rule.minimumCount()||count>rule.maximumCount())throw new AssertionError("count "+b.sourceFile()+":"+count);
     }
     if(stagePools!=null){
      for(var name:stagePools.getAsJsonObject("disabled").getAsJsonArray(stage))
       if(counts.getOrDefault(name.getAsString()+".nbt",0)!=0)throw new AssertionError("disabled stage source placed");
      int active=stagePools.getAsJsonObject("pool_sizes").get(stage).getAsInt();
      if(counts.size()>active)throw new AssertionError("active pool exceeded");
     }
     for(var a:all)for(var b:all)if(a!=b && a.building().id().equals(b.building().id())){
      int dx=Math.max(a.x()-b.x()-b.building().width(),b.x()-a.x()-a.building().width());
      int dz=Math.max(a.z()-b.z()-b.building().depth(),b.z()-a.z()-a.building().depth());
      var rule=rules.building(a.building().sourceFile());int gap=rule.hasSpacing()?rule.minimumSpacing():rules.sameBuildingSpacing();
      if(Math.max(dx,dz)<gap)throw new AssertionError("same source spacing");
     }
     int maxEmpty=emptySquare(cells,n,rad);if(!blocked && maxEmpty>3)throw new AssertionError("new blank square "+maxEmpty);
     if(seed==7 && rad==rules.radiusMinBlocks()){
      var repeat=CityPlotPlan.create(size,size,seed,snapshot,district,available,(b,x,z)->true,rules,cfg.stages().get(stage).partitions(),1);
      var repeated=new HashSet<UniquePlotPacking.Placement>();for(var row:repeat)for(var cell:row)if(cell.placement()!=null)repeated.add(cell.placement());
      if(!signature(all).equals(signature(repeated)))throw new AssertionError("nondeterministic plan");
     }
     var details=new JsonArray();int occupied=0;
     for(var p:all.stream().sorted(Comparator.comparingInt(UniquePlotPacking.Placement::x).thenComparingInt(UniquePlotPacking.Placement::z)).toList()){
      var b=p.building();occupied+=b.area();var detail=new JsonObject();
      detail.addProperty("file",b.sourceFile());detail.addProperty("x",p.x()-n);detail.addProperty("z",p.z()-n);
      detail.addProperty("w",b.width());detail.addProperty("d",b.depth());detail.addProperty("height",b.profile().heightBlocks());detail.addProperty("uses",b.profile().uses());details.add(detail);
     }
     var result=new JsonObject();result.addProperty("stage",stage);result.addProperty("radius",rad);result.addProperty("seed",seed);result.addProperty("reserved_bands",blocked);result.addProperty("planner_ms",ms);result.addProperty("placements",all.size());result.addProperty("distinct_sources",counts.size());result.addProperty("occupied_chunks",occupied);result.addProperty("largest_empty_square_chunks",maxEmpty);result.add("counts",JSON.toJsonTree(counts));result.add("placements_detail",details);results.add(result);
     System.out.println(stage+" radius="+rad+" seed="+seed+" reserved="+blocked+" buildings="+all.size()+" types="+counts.size()+" ms="+ms);
    }
  }
  if(!warnings.isEmpty())throw new AssertionError(warnings);
  warnings.addAll(converted.warnings);
  var unexpected=warnings.stream().filter(w->!(w.startsWith("阶段 小镇 / 风格 西式风格 没有单格建筑") || w.startsWith("阶段 乡村 / 风格 西式风格 没有单格建筑") || w.startsWith("内城没有任何 16x16 以内的单格建筑"))).toList();
  if(converted.footprints.stream().anyMatch(b->b.id().contains("fallback_empty")))throw new AssertionError("fallback entered plot catalogue");
  var report=new JsonObject();report.addProperty("source_conversion_count",converted.footprints.size());report.addProperty("t3_t4_sources_checked",checkedBuildings);report.addProperty("registered_cap_entries_checked",countEntries);report.addProperty("generated_asset_count",converted.files.size());report.addProperty("game_runtime_tested",false);report.add("warnings",JSON.toJsonTree(warnings));report.add("scenarios",results);
  report.add("unexpected_warnings",JSON.toJsonTree(unexpected));report.addProperty("fallback_not_in_plot_catalogue",true);report.addProperty("runtime_geometry_and_style_selectors_verified",true);
  Files.writeString(out.resolve("真实资产扩展自检.json"),JSON.toJson(report));
  if(!unexpected.isEmpty())throw new AssertionError(unexpected);
  System.out.println("PASS real conversion / registered count tables / full footprints / reserved bands / same-source spacing / deterministic layout; scenarios="+results.size());
 }
}
