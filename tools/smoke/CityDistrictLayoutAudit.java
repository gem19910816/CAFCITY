import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.impl.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the installed planner with measured source footprints. Never opens a game save. */
public final class CityDistrictLayoutAudit {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
  var warnings=new ArrayList<String>();var config=CityStageConfig.load(root,warnings);
  if(!warnings.isEmpty())throw new AssertionError(warnings);
  var rows=JsonParser.parseString(Files.readString(out.resolve("district-assignment.json"))).getAsJsonArray();
  var report=new JsonArray();
  for(String stageName:List.of("小城","大城")){
   var stage=config.stages().get(stageName);
   var layout=StageLayoutConfig.loadForStage(root,stageName,warnings,800,800);
   List<LayoutCatalog.Building> buildings=new ArrayList<>();
   var idFor=com.caf.lccustomcity.convert.LcAssetGenerator.class.getDeclaredMethod("idFor",Path.class,Path.class);idFor.setAccessible(true);
   for(var item:rows){var r=item.getAsJsonObject();if(!r.get("stage").getAsString().equals(stageName))continue;
    var chunks=r.getAsJsonArray("chunks");String zone=r.get("district").getAsString(),file=r.get("file").getAsString();
    String style=CityStageConfig.cityStyleName(stageName,"西式风格","zone_"+com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(zone));
    Path partitionRoot=root.resolve("西式风格").resolve(stageName).resolve(zone);
    String id="caf_lc_customcity:s"+com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(stageName+"|西式风格")+"_"+idFor.invoke(null,partitionRoot,partitionRoot.resolve("建筑文件").resolve(file));
    buildings.add(new LayoutCatalog.Building(id,style,zone,false,chunks.get(0).getAsInt(),chunks.get(1).getAsInt(),0,stageName,file));
   }
   var catalog=new LayoutCatalog.Snapshot(List.copyOf(buildings),Map.of(),0,1);
   for(int radius:new TreeSet<>(List.of(layout.radiusMinBlocks(),layout.radiusMaxBlocks())))for(long seed:new long[]{7,103,20261031}){
    int n=(int)Math.ceil(radius/16.0),size=n*2+1;var center=new CityLayout.Center(8,8,radius);
    String[][] zones=new String[size][size];
    for(int x=0;x<size;x++)for(int z=0;z<size;z++)zones[x][z]=StagePartitionPlanner.select(stage.partitions(),seed,8,8,radius,x-n,z-n);
    for(var b:buildings){boolean fits=false;
     for(int x=0;x+b.width()<=size&&!fits;x++)for(int z=0;z+b.depth()<=size&&!fits;z++){boolean valid=true;
      for(int xx=x;xx<x+b.width()&&valid;xx++)for(int zz=z;zz<z+b.depth();zz++)if(!b.zone().equals(zones[xx][zz])||Math.hypot((xx-n)*16.0,(zz-n)*16.0)>radius){valid=false;break;}
      if(valid)fits=true;
     }
     if(!fits)throw new AssertionError("Source footprint cannot fit its district: "+b.sourceFile());
    }
    long start=System.nanoTime();
    var cells=CityPlotPlan.create(size,size,seed,catalog,(x,z)->{
     if(Math.hypot((x-n)*16.0,(z-n)*16.0)>radius)return null;
     String zone=zones[x][z];var partition=stage.partitions().get(zone);return new CityLayout.District(center,CityStageConfig.cityStyleName(stageName,"西式风格","zone_"+com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(zone)),false,false,zone,partition.fillEmptyPlots(),partition.gapBetweenLargeBuildings());
    },(x,z)->true,(b,x,z)->true,layout,stage.partitions(),1);
    var placements=new HashSet<UniquePlotPacking.Placement>();
    for(var col:cells)for(var cell:col)if(cell.placement()!=null)placements.add(cell.placement());
    var counts=new TreeMap<String,Integer>();var districtCounts=new TreeMap<String,Integer>();var map=new JsonArray();
    for(var p:placements){var b=p.building();counts.merge(b.sourceFile(),1,Integer::sum);districtCounts.merge(b.zone(),1,Integer::sum);
     for(int x=p.x();x<p.x()+b.width();x++)for(int z=p.z();z<p.z()+b.depth();z++){
      if(!b.zone().equals(zones[x][z]))throw new AssertionError("Building crosses district "+b.sourceFile());
      if(!cells[x][z].placement().equals(p))throw new AssertionError("Overlapping building");
     }
     var r=new JsonObject();r.addProperty("file",b.sourceFile());r.addProperty("district",b.zone());r.addProperty("x",p.x()-n);r.addProperty("z",p.z()-n);r.addProperty("w",b.width());r.addProperty("d",b.depth());map.add(r);
    }
    List<String> missing=new ArrayList<>();
    for(var b:buildings){var rule=layout.building(b.sourceFile());int count=counts.getOrDefault(b.sourceFile(),0);
     if(count<rule.minimumCount())missing.add(b.sourceFile());
     if(count>rule.maximumCount())throw new AssertionError("Count limit exceeded "+b.sourceFile());
    }
    boolean[][] reached=new boolean[size][size];var queue=new ArrayDeque<int[]>();
    for(int x=0;x<size;x++)for(int z=0;z<size;z++)if(cells[x][z].placement()==null&&(Math.hypot((x-n)*16.0,(z-n)*16.0)>=radius-16)){reached[x][z]=true;queue.add(new int[]{x,z});}
    while(!queue.isEmpty()){var q=queue.removeFirst();for(var delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){int x=q[0]+delta[0],z=q[1]+delta[1];if(x>=0&&z>=0&&x<size&&z<size&&!reached[x][z]&&cells[x][z].placement()==null){reached[x][z]=true;queue.add(new int[]{x,z});}}}
    int withoutRoute=0;for(var p:placements){boolean accessible=false;for(int x=p.x()-1;x<=p.x()+p.building().width();x++)for(int z=p.z()-1;z<=p.z()+p.building().depth();z++)if(x>=0&&z>=0&&x<size&&z<size&&reached[x][z])accessible=true;if(!accessible)withoutRoute++;}
    for(String zone:stage.partitions().keySet())if(districtCounts.getOrDefault(zone,0)==0)throw new AssertionError("Empty district "+zone);
    var r=new JsonObject();r.addProperty("stage",stageName);r.addProperty("radius",radius);r.addProperty("seed",seed);r.addProperty("planner_ms",(System.nanoTime()-start)/1_000_000.0);r.addProperty("vacant_perimeter_reachable",withoutRoute==0);r.addProperty("buildings_without_vacant_perimeter_route",withoutRoute);r.addProperty("all_source_footprints_have_eligible_space",true);r.addProperty("distinct_templates_selected",counts.size());
    var gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();r.add("missing",gson.toJsonTree(missing));r.add("counts",gson.toJsonTree(counts));r.add("district_counts",gson.toJsonTree(districtCounts));r.add("placements",map);report.add(r);
    System.out.println(stageName+" radius="+radius+" seed="+seed+" buildings="+placements.size()+" missing="+missing);
   }
  }
  Files.writeString(out.resolve("planner-audit.json"),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report));
  if(report.asList().stream().anyMatch(r->!r.getAsJsonObject().getAsJsonArray("missing").isEmpty()))throw new AssertionError("Required buildings cannot fit; revise before installation");
  if(!warnings.isEmpty())throw new AssertionError(warnings);
  System.out.println("PASS installed planner: required sources, whole footprints, districts and maxima; runtime_verified=false");
 }
}
