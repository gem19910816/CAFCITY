import com.simibubi.create.content.trains.track.*;
import com.caf.lccustomcity.impl.CreateRailwayRuntime;
import mcjty.lostcities.worldgen.lost.Transform;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
public class CreateRailGeometryTest {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  Method orient=CreateRailwayRuntime.class.getDeclaredMethod("oriented",String.class,Transform.class);orient.setAccessible(true);
  for(Transform t:Transform.values())for(String shape:new String[]{"xo","zo","ae","aw","cr_o"}) {
   var result=(TrackShape)orient.invoke(null,shape,t);var original=TrackShape.valueOf(shape.toUpperCase());
   check(result.getNormal().distanceTo(CreateRailwayRuntime.vector(original.getNormal(),t))<1e-6,"normal "+t);
   for(Vec3 v:original.getAxes()){var a=CreateRailwayRuntime.vector(v,t);check(result.getAxes().stream().anyMatch(b->b.distanceTo(a)<1e-6||b.distanceTo(a.scale(-1))<1e-6),"axis "+t);}
  }
  for(int side:new int[]{0,15})for(int end:new int[]{0,15}) {
   var a=new BlockPos(side,1,7);var b=new BlockPos(8,1,end);var ax=new Vec3(side==0?1:-1,0,0);var bx=new Vec3(0,0,end==0?1:-1);
   var start=Vec3.atBottomCenterOf(a).add(ax.scale(.5));var finish=Vec3.atBottomCenterOf(b).add(bx.scale(.5));
   var c=new BezierConnection(Couple.create(a,b),Couple.create(start,finish),Couple.create(ax,bx),Couple.create(new Vec3(0,1,0),new Vec3(0,1,0)),true,false,null);
   check(c.getLength()>8&&c.getLength()<20,"bad curve length");
   check(c.getPosition(0).distanceTo(start)<1e-5&&c.getPosition(1).distanceTo(finish)<1e-5,"curve endpoints");
   check(c.secondary().getKey().equals(a),"secondary backlink");
   for(int i=0;i<=100;i++){var v=c.getPosition(i/100.0);check(Double.isFinite(v.x)&&v.x>=0&&v.x<=16&&v.z>=0&&v.z<=16,"curve leaves module");}
  }
  System.out.println("PASS CreateRailGeometryTest checks="+checks);
 }
}
