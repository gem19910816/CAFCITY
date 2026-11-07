import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.config.*;
import com.google.gson.*;
import mcjty.lostcities.config.*;
import mcjty.lostcities.worldgen.highway.HighwayInfo;
import mcjty.lostcities.worldgen.lost.Railway;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Real profile mutation and real callback handlers. Does not transform a game or generate terrain. */
public class TransportDisableProbe {
 static int checks;
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static Method handler(String cls)throws Exception{
  Class<?> c=Class.forName("com.caf.lccustomcity.mixin."+cls);
  return Arrays.stream(c.getDeclaredMethods()).filter(m->m.getName().startsWith("caf$")).findFirst().orElseThrow();
 }
 public static void main(String[] args)throws Exception{
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  Path config=Path.of(args[0]);var warnings=new ArrayList<String>();
  var defaults=LcCustomCityMod.forcedProfileDefaults();var parsed=CityGenConfig.loadRoot(config,defaults,warnings);
  check(warnings.isEmpty(),"root config warnings "+warnings);
  var expected=new LinkedHashMap<String,Object>();
  expected.put("BRIDGE_CHANCE",0f);expected.put("PLANNED_PRIMARY_BRIDGE_CHANCE",0f);
  expected.put("HIGHWAY_DISTANCE_MASK",0);expected.put("RAILWAYS_ENABLED",false);
  expected.put("RAILWAY_STATIONS_ENABLED",false);expected.put("RAILWAY_SURFACE_STATIONS_ENABLED",false);
  expected.put("RAILWAY_DUNGEON_CHANCE",0f);
  for(var e:expected.entrySet())check(e.getValue().equals(parsed.get(e.getKey())),"loaded transport field "+e);
  var modes=List.of(HighwayGenerationMode.LEGACY,HighwayGenerationMode.INTERCITY_NETWORK_V1);
  var original=new LinkedHashMap<>(ProfileSetup.STANDARD_PROFILES);
  var profiles=new ArrayList<LostCityProfile>();
  try{
   ProfileSetup.STANDARD_PROFILES.clear();
   for(var mode:modes){
    var p=new LostCityProfile("caf-transport-"+mode,false);p.HIGHWAY_GENERATION_MODE=mode;
    p.HIGHWAY_DISTANCE_MASK=15;p.BRIDGE_CHANCE=.9f;p.PLANNED_PRIMARY_BRIDGE_CHANCE=.9f;
    p.RAILWAYS_ENABLED=p.RAILWAY_STATIONS_ENABLED=p.RAILWAY_SURFACE_STATIONS_ENABLED=true;
    p.RAILWAY_DUNGEON_CHANCE=1;profiles.add(p);ProfileSetup.STANDARD_PROFILES.put(p.getName(),p);
   }
   check(LcCustomCityMod.forceProfileSettings(config)==2,"actual profile override did not finish");
   Method highway=handler("HighwayDisableMixin"),railway=handler("RailwayDisableMixin");
   highway.setAccessible(true);railway.setAccessible(true);
   for(var p:profiles){
    for(var e:expected.entrySet())check(e.getValue().equals(LostCityProfile.class.getField(e.getKey()).get(p)),"actual profile field "+e);
    var cb=new CallbackInfoReturnable<HighwayInfo>("computeHighwayInfo",true);
    highway.invoke(null,null,null,p,cb);
    check(cb.isCancelled()&&cb.getReturnValue()==HighwayInfo.NONE,"disabled highway callback "+p.HIGHWAY_GENERATION_MODE);
    var rc=new CallbackInfoReturnable<Railway.RailChunkInfo>("getRailChunkType",true);
    railway.invoke(null,null,null,p,rc);
    check(rc.isCancelled()&&rc.getReturnValue()==Railway.RailChunkInfo.NOTHING,"disabled rail callback "+p.HIGHWAY_GENERATION_MODE);
   }
   var enabled=profiles.getFirst();
   enabled.HIGHWAY_DISTANCE_MASK=15;
   var cb=new CallbackInfoReturnable<HighwayInfo>("computeHighwayInfo",true);highway.invoke(null,null,null,enabled,cb);
   check(!cb.isCancelled(),"enabled highway must retain original path");
   for(boolean rails:new boolean[]{false,true})for(boolean stations:new boolean[]{false,true}){
    enabled.RAILWAYS_ENABLED=rails;enabled.RAILWAY_STATIONS_ENABLED=stations;
    var rc=new CallbackInfoReturnable<Railway.RailChunkInfo>("getRailChunkType",true);railway.invoke(null,null,null,enabled,rc);
    check(rc.isCancelled()==(!rails&&!stations),"mixed rail toggle state");
   }
   // Match target signatures and callback types in the actual installed Lost Cities jar.
   for(var pair:List.of(new Object[]{mcjty.lostcities.worldgen.lost.Highway.class,"computeHighwayInfo",HighwayInfo.class},
                       new Object[]{Railway.class,"getRailChunkType",Railway.RailChunkInfo.class})){
    Method m=((Class<?>)pair[0]).getDeclaredMethod((String)pair[1],mcjty.lostcities.varia.ChunkCoord.class,mcjty.lostcities.worldgen.IDimensionInfo.class,LostCityProfile.class);
    check(Modifier.isStatic(m.getModifiers())&&m.getReturnType()==pair[2],"Mixin target descriptor");
   }
   var radii=Map.of("乡村",new int[]{190,200},"小镇",new int[]{180,280},"小城",new int[]{500,600},"大城",new int[]{800,800});
   for(var e:radii.entrySet()){
    var rules=StageLayoutConfig.loadForStage(config.getParent(),e.getKey(),new ArrayList<>(),1,1);
    check(rules.radiusMinBlocks()==e.getValue()[0]&&rules.radiusMaxBlocks()==e.getValue()[1],"restored radius "+e.getKey());
   }
   RootCityOptions.load(config,null,new ArrayList<>());
   check(StarterTownConfig.current().enabled()&&StarterTownConfig.current().minDistance()==600&&StarterTownConfig.current().maxDistance()==900,"near town defaults changed");
   var json=new JsonObject();json.addProperty("checks",checks);json.addProperty("profile_modes_checked",2);
   json.addProperty("world_generated",false);json.addProperty("mixin_transform_tested",false);
   json.addProperty("scope","Real installed Lost Cities fields, actual forceProfileSettings and mixin callback handlers; target descriptors checked, no in-game transformation or visual test.");
   Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(json));
   System.out.println("Transport disable PASS checks="+checks);
  }finally{ProfileSetup.STANDARD_PROFILES.clear();ProfileSetup.STANDARD_PROFILES.putAll(original);}
 }
}
