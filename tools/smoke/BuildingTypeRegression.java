import com.caf.lccustomcity.config.*;
import com.caf.lccustomcity.impl.*;
import java.nio.file.*;
import java.util.*;

public class BuildingTypeRegression {
 public static void main(String[] args)throws Exception {
  for(int gap:new int[]{9,10})for(boolean outer:new boolean[]{false,true})for(int w:new int[]{1,3}){
   var item=new LayoutCatalog.Building("repeat","s",null,outer,w,2);
   var cells=CityPlotPlan.create(2*w+gap+2,5,41,new LayoutCatalog.Snapshot(List.of(item),Map.of(),0,1),
    (x,z)->new CityLayout.District(new CityLayout.Center(0,0,800),"s",outer),(x,z)->true,
    (source,x,z)->source.width()==w&&source.depth()==2&&z==1&&(x==0||x==w+gap));
   var all=new HashSet<UniquePlotPacking.Placement>();for(var row:cells)for(var cell:row)if(cell.placement()!=null)all.add(cell.placement());
   if(all.size()!=(gap==9?1:2))throw new AssertionError("repeat boundary");
  }
  Path base=Path.of(args[0]);
  Path courthouse=base.resolve("西式风格/小城/小城/建筑文件/法外之地.nbt");
  var profile=BuildingTypes.forSource(courthouse,13);
  if(!profile.known() || (profile.uses()&BuildingTypes.SERVICE)==0)throw new AssertionError("actual content classification");
  if(BuildingTypes.forSource(courthouse,999).known())throw new AssertionError("changed dimensions must not inherit old profile");
  Path changed=Files.createTempFile("caf-unknown-",".nbt");
  try { Files.write(changed,new byte[]{1,2,3});if(BuildingTypes.forSource(changed,13).known())throw new AssertionError("unknown content must remain unknown"); }finally{Files.delete(changed);}
  var b=new LayoutCatalog.Building("x","s",null,false,4,2,0,"小城","法外之地.nbt",profile);
  if(!b.oriented(1).profile().equals(profile)||b.oriented(1).width()!=2)throw new AssertionError("rotation lost profile");
  for(boolean enabled:new boolean[]{false,true}) {
   var house=new LayoutCatalog.Building("home","rural","乡村",false,2,2);
   var partition=new StagePartitionConfig.Partition("乡村",true,"圆形","固定",0,0,0,0,8,8,8,0,360,new StagePartitionConfig.Range(0,0),1,5,enabled,false);
   var layout=new StageLayoutConfig.Rules(128,128,null,10,1024,false,0,new StageLayoutConfig.Range(null,null),new StageLayoutConfig.Range(0,0),2,6,0,Map.of());
   var planned=CityPlotPlan.create(8,8,12,new LayoutCatalog.Snapshot(List.of(house),Map.of(),0,1),
    (x,z)->new CityLayout.District(new CityLayout.Center(0,0,128),"rural",false),(x,z)->true,(source,x,z)->true,layout,Map.of("乡村",partition));
   int occupied=0;for(var row:planned)for(var cell:row)if(cell.placement()!=null)occupied++;
   if(enabled!=(occupied>0))throw new AssertionError("rural fill flag changed");
  }
  System.out.println("PASS building type integration / source hash / rotation / unchanged spacing / rural optional-fill flags / gap flags.");
 }
}
