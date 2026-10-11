package mcjty.lostcities.varia;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.lang.reflect.Field;

/** Test-only bridge: real LC Tools/Palette need a server world to obtain block lookup.
 * Offline vanilla jars lack NeoForge access transformations and an actual running server.
 * This fixture supplies only that lookup, and is never on other tests or in the mod jar. */
public final class WorldTools {
    private static ServerLevel world;
    public static synchronized ServerLevel getOverworld(){
        if(world!=null)return world;
        try {
            Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
            var unsafe=(sun.misc.Unsafe)uf.get(null);
            world=(ServerLevel)unsafe.allocateInstance(LookupWorld.class);
            Field registry=Level.class.getDeclaredField("registryAccess");
            unsafe.putObject(world,unsafe.objectFieldOffset(registry),RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            return world;
        }catch(Exception e){throw new IllegalStateException("Offline registry world",e);}
    }
    private static final class LookupWorld extends ServerLevel {
        private LookupWorld(){super(null,null,null,null,null,null,null,false,0L,java.util.List.of(),false,null);}
        @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures(){return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;}
    }
}
