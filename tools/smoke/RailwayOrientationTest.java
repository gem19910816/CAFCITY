import com.caf.lccustomcity.impl.RailwayBlockOrientation;
import mcjty.lostcities.worldgen.lost.Transform;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
public class RailwayOrientationTest {
 public static void main(String[] args) {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();int checks=0;
  for(Transform t:Transform.values())for(Direction d:Direction.Plane.HORIZONTAL) {
   int x=8,z=8,sx=x-d.getStepX(),sz=z-d.getStepZ();
   var state=Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,d);
   var actual=RailwayBlockOrientation.transform(state,t).getValue(BlockStateProperties.HORIZONTAL_FACING);
   int dx=t.rotateX(x,z)-t.rotateX(sx,sz),dz=t.rotateZ(x,z)-t.rotateZ(sx,sz);
   if(actual.getStepX()!=dx||actual.getStepZ()!=dz)throw new AssertionError("torch backing "+t+" "+d);checks++;
   var stair=Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,d);
   if(RailwayBlockOrientation.transform(stair,t).getValue(BlockStateProperties.HORIZONTAL_FACING)!=actual)throw new AssertionError("stairs "+t);checks++;
  }
  System.out.println("PASS RailwayOrientationTest checks="+checks);
 }
}
