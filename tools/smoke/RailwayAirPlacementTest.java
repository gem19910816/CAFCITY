import com.caf.lccustomcity.convert.RailwayAssetLoader;
import com.caf.lccustomcity.impl.CreateRailwayRuntime;
import com.google.gson.*;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.lost.Transform;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.file.*;
import java.util.*;
public class RailwayAirPlacementTest {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static class Driver extends ChunkDriver {
  final Set<String> air=new HashSet<>();
  @Override public void setBlockRange(int x,int y,int z,int y2,BlockState state){
   check(x>=0&&x<16&&z>=0&&z<16&&y2==y+1,"outside footprint");
   check(state.isAir(),"non-air carving");check(air.add(x+","+y+","+z),"duplicate write");
  }
 }
 public static void main(String[]args)throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  Path root=Path.of(args[0]);List<String>warnings=new ArrayList<>();
  check(RailwayAssetLoader.load(root,Path.of(args[1]),new LinkedHashMap<>(),warnings)==26,"loader "+warnings);
  for(String name:RailwayAssetLoader.names())for(Transform t:Transform.values()){
   var driver=new Driver();CreateRailwayRuntime.carveAir(driver,name,t,0,40,0);
   check(driver.air.size()==RailwayAssetLoader.airPlan(name).size(),"missing air "+name+" "+t);
   for(var cell:RailwayAssetLoader.airPlan(name))
    check(driver.air.contains(t.rotateX(cell.x(),cell.z())+","+(40+cell.y())+","+t.rotateZ(cell.x(),cell.z())),"wrong air coordinate");
  }
  check(RailwayAssetLoader.airPlan("rails_horizontal").stream().anyMatch(c->c.x()==8&&c.y()==2&&c.z()==7),"tunnel not cleared");
  System.out.println("PASS RailwayAirPlacementTest checks="+checks);
 }
}
