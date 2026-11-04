import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.impl.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class BuildingTypeLayoutAudit {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),inv=Path.of(args[1]),output=Path.of(args[2]);
  var rows=JsonParser.parseString(Files.readString(inv)).getAsJsonArray();
  var profiles=JsonParser.parseString(Files.readString(Path.of(args[3]))).getAsJsonArray();
  var bySha=new HashMap<String,BuildingTypes.Profile>();
  for(var item:profiles){var p=item.getAsJsonObject();bySha.put(p.get("sha256").getAsString(),new BuildingTypes.Profile(p.get("uses").getAsInt(),p.get("height").getAsInt(),p.get("visualGroup").getAsString()));}
  var warnings=new ArrayList<String>();
  var config=CityStageConfig.load(root,warnings);
  var report=new JsonArray();
  var idFor=com.caf.lccustomcity.convert.LcAssetGenerator.class.getDeclaredMethod("idFor",Path.class,Path.class);idFor.setAccessible(true);
  for(String stage:List.of("小城","大城")){
   var rules=StageLayoutConfig.loadForStage(root,stage,warnings,800,800);
   var buildings=new ArrayList<LayoutCatalog.Building>();
   String style=CityStageConfig.cityStyleName(stage,"西式风格","zone_"+com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(stage));
   Path partitionRoot=root.resolve("西式风格").resolve(stage).resolve(stage);
   for(var item:rows){var r=item.getAsJsonObject();if(!stage.equals(r.get("stage").getAsString()))continue;var size=r.getAsJsonArray("chunks");String file=r.get("file").getAsString();
    String id="caf_lc_customcity:s"+com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(stage+"|西式风格")+"_"+idFor.invoke(null,partitionRoot,partitionRoot.resolve("建筑文件").resolve(file));
    buildings.add(new LayoutCatalog.Building(id,style,stage,false,size.get(0).getAsInt(),size.get(1).getAsInt(),0,stage,file,bySha.get(r.get("sha256").getAsString())));}
   var catalog=new LayoutCatalog.Snapshot(List.copyOf(buildings),Map.of(),0,1);
   for(int rad:new TreeSet<>(List.of(rules.radiusMinBlocks(),rules.radiusMaxBlocks())))for(long seed:new long[]{7,103,20261031}){
    int n=(rad+15)/16,size=n*2+1;var center=new CityLayout.Center(8,8,rad);
    long start=System.nanoTime();
    var cells=CityPlotPlan.create(size,size,seed,catalog,(x,z)->Math.hypot((x-n)*16.0,(z-n)*16.0)>rad?null:new CityLayout.District(center,style,false,false,stage,true,false),(x,z)->true,(b,x,z)->true,rules,config.stages().get(stage).partitions(),1);
    long ms=(System.nanoTime()-start)/1_000_000;
    var placed=new HashSet<UniquePlotPacking.Placement>();for(var column:cells)for(var cell:column)if(cell.placement()!=null)placed.add(cell.placement());
    var counts=new TreeMap<String,Integer>();var map=new JsonArray();int used=0,land=0;
    for(int x=0;x<size;x++)for(int z=0;z<size;z++)if(Math.hypot((x-n)*16.0,(z-n)*16.0)<=rad)land++;
    for(var p:placed){var b=p.building();counts.merge(b.sourceFile(),1,Integer::sum);used+=b.area();
     for(int x=p.x();x<p.x()+b.width();x++)for(int z=p.z();z<p.z()+b.depth();z++){if(!p.equals(cells[x][z].placement()))throw new AssertionError("overlap");if(Math.hypot((x-n)*16.0,(z-n)*16.0)>rad)throw new AssertionError("outside");}
     var value=new JsonObject();value.addProperty("file",b.sourceFile());value.addProperty("x",p.x()-n);value.addProperty("z",p.z()-n);value.addProperty("w",b.width());value.addProperty("d",b.depth());value.addProperty("height",b.profile().heightBlocks());value.addProperty("uses",b.profile().uses());map.add(value);
    }
    for(var b:buildings){var r=rules.building(b.sourceFile());if(r!=null && (counts.getOrDefault(b.sourceFile(),0)<r.minimumCount()||counts.getOrDefault(b.sourceFile(),0)>r.maximumCount()))throw new AssertionError("count "+b.sourceFile());}
    for(var a:placed)for(var b:placed)if(a!=b && a.building().id().equals(b.building().id())){int dx=Math.max(a.x()-b.x()-b.building().width(),b.x()-a.x()-a.building().width());int dz=Math.max(a.z()-b.z()-b.building().depth(),b.z()-a.z()-a.building().depth());var r=rules.building(a.building().sourceFile());int gap=r!=null&&r.hasSpacing()?r.minimumSpacing():rules.sameBuildingSpacing();if(Math.max(dx,dz)<gap)throw new AssertionError("repeat gap");}
    int[][] squares=new int[size+1][size+1];int maxEmpty=0;
    for(int x=1;x<=size;x++)for(int z=1;z<=size;z++)if(cells[x-1][z-1].placement()==null && Math.hypot((x-1-n)*16.0,(z-1-n)*16.0)<rad-16){squares[x][z]=1+Math.min(squares[x-1][z-1],Math.min(squares[x-1][z],squares[x][z-1]));maxEmpty=Math.max(maxEmpty,squares[x][z]);}
    var entry=new JsonObject();entry.addProperty("stage",stage);entry.addProperty("radius",rad);entry.addProperty("seed",seed);entry.addProperty("planner_ms",ms);entry.addProperty("occupied_chunks",used);entry.addProperty("available_chunks",land);entry.addProperty("largest_empty_square_chunks",maxEmpty);entry.addProperty("distinct_sources",counts.size());entry.addProperty("total_placements",placed.size());entry.addProperty("repeated_placements_beyond_first",placed.size()-counts.size());entry.add("counts",new Gson().toJsonTree(counts));entry.add("placements",map);report.add(entry);
    System.out.println(stage+" radius="+rad+" seed="+seed+" time="+ms+"ms buildings="+placed.size()+" distinct="+counts.size()+" occupied="+used+"/"+land+" emptySquare="+maxEmpty);
   }
  }
  Files.writeString(output,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report));
  if(!warnings.isEmpty())throw new AssertionError(warnings);
  if(BuildingTypes.reviewedCount()!=99)throw new AssertionError("bundled profile coverage");
  System.out.println("PASS source coverage / full footprints / counts / repeat spacing; not game runtime.");
 }
}
