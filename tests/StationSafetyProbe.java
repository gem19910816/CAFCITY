import com.caf.lccustomcity.impl.*;
import com.caf.lccustomcity.config.*;
import mcjty.lostcities.api.RailChunkType;
import mcjty.lostcities.worldgen.lost.Railway.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public class StationSafetyProbe {
 static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
 public static void main(String[] a)throws Exception{
  int cases=0;
  for(var type:RailChunkType.values())for(var direction:RailDirection.values())for(int level:new int[]{-3,-1,0,1})for(int rails=1;rails<=3;rails++){
   var old=new RailChunkInfo(type,direction,level,rails);var kept=UndergroundStationPlots.withoutSurfaceAccess(old,false);check(kept==old,"unsuppressed identity");
   var converted=UndergroundStationPlots.withoutSurfaceAccess(old,true);
   check(converted.getDirection()==direction&&converted.getLevel()==level&&converted.getRails()==rails,"route parameters changed");
   check(type==RailChunkType.STATION_UNDERGROUND?converted.getType()==RailChunkType.STATION_EXTENSION_UNDERGROUND:converted==old,"wrong conversion domain");
   boolean deep=UndergroundStationPlots.deepStation(old,1);check(deep==((type==RailChunkType.STATION_UNDERGROUND||type==RailChunkType.STATION_EXTENSION_UNDERGROUND)&&level<0),"clearance guard");cases++;
  }
  var big=new LayoutCatalog.Building("big","s",null,false,14,25,0,"large","big.nbt");
  var small=new LayoutCatalog.Building("small","s",null,false,14,15,0,"large","small.nbt");
  BuildingCountLimits.install(Map.of(),Map.of("s",Map.of("big",new BuildingCountLimits.Limit(1,1),"small",new BuildingCountLimits.Limit(1,1))));
  check(UndergroundStationPlots.requiredLandmark(big,null,10,10),"required landmark rejected");check(UndergroundStationPlots.requiredLandmark(big.oriented(1),null,10,10),"orientation changed eligibility");
  check(!UndergroundStationPlots.requiredLandmark(small,null,10,10),"small building got exception");
  BuildingCountLimits.install(Map.of(),Map.of());check(!UndergroundStationPlots.requiredLandmark(big,null,10,10),"optional landmark got exception");
  String owner="mcjty/lostcities/worldgen/gen/Railways";ClassNode target=new ClassNode();try(JarFile jar=new JarFile(a[0])){new ClassReader(jar.getInputStream(jar.getJarEntry(owner+".class"))).accept(target,0);}
  String expected="(Lmcjty/lostcities/worldgen/LostCityTerrainFeature;Lmcjty/lostcities/worldgen/lost/BuildingInfo;Lmcjty/lostcities/worldgen/lost/Railway$RailChunkInfo;Lmcjty/lostcities/worldgen/ChunkHeightmap;)V";
  var methods=target.methods.stream().filter(m->m.name.equals("generateRailways")).toList();check(methods.size()==1,"ambiguous method");var method=methods.get(0);check(method.desc.equals(expected),"target descriptor changed");check((method.access&Opcodes.ACC_STATIC)!=0,"target no longer static");
  Type[] args=Type.getArgumentTypes(method.desc);check(args[2].getInternalName().equals("mcjty/lostcities/worldgen/lost/Railway$RailChunkInfo"),"wrong argument slot");long ordinals=Arrays.stream(args).filter(t->t.equals(args[2])).count();check(ordinals==1,"RailChunkInfo ordinal ambiguous");
  System.out.println("PASS route conversion/identity/clearance cases="+cases+"; required/optional/orientation guards; ASM static target exact descriptor, RailChunkInfo slot=2 ordinal=0 unique");
 }
}
