package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.ForceFlattenConfig;
import com.caf.lccustomcity.config.LayoutCatalog;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.*;
import java.util.function.BiPredicate;

/** Vanilla surface structures run after Lost Cities' RAW_GENERATION flattening. */
public final class CitySurfaceStructures {
    private static volatile boolean warned;
    private CitySurfaceStructures(){}
    public static boolean surface(ResourceLocation id,GenerationStep.Decoration step){
        // Strongholds use SURFACE_STRUCTURES too, despite being underground.
        return id!=null&&id.getNamespace().equals("minecraft")
            &&step==GenerationStep.Decoration.SURFACE_STRUCTURES&&!id.getPath().equals("stronghold");
    }
    public static boolean overlaps(BoundingBox box,BiPredicate<Integer,Integer> city){
        for(int x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxX(),16);x++)
            for(int z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZ(),16);z++)if(city.test(x,z))return true;
        return false;
    }
    public static boolean suppress(WorldGenLevel world,StructureStart start){
        if(!ForceFlattenConfig.enabled()||!LayoutCatalog.current().active()||world==null||!start.isValid())return false;
        var structure=start.getStructure();
        var id=world.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure);
        if(!surface(id,structure.step()))return false;
        IDimensionInfo provider=provider(world);
        return suppress(start.getBoundingBox(),provider);
    }
    public static boolean suppress(BoundingBox box,IDimensionInfo provider){
        if(!ForceFlattenConfig.enabled()||provider==null||!CityLayout.enabled(provider,provider.getProfile()))return false;
        var profile=provider.getProfile();
        return overlaps(box,(x,z)->{
            var c=new ChunkCoord(provider.getType(),x,z);
            return (CityLayout.center(c,provider,profile)!=null||CityLayout.predefined(c,provider))
                &&!BuildingInfo.isVoidChunk(c,provider);
        });
    }
    private static IDimensionInfo provider(WorldGenLevel world){
        try {
            // DeferredHolder is unavailable in the compile-only classpath; use the same
            // registered feature path as LC commands, with THIS world, never a thread's old region.
            Object holder=Class.forName("mcjty.lostcities.setup.Registration").getField("LOSTCITY_FEATURE").get(null);
            var feature=(mcjty.lostcities.worldgen.LostCityFeature)holder.getClass().getMethod("get").invoke(holder);
            return feature.getDimensionInfo(world);
        }catch(ReflectiveOperationException e){
            if(!warned){warned=true;org.slf4j.LoggerFactory.getLogger("CAF-LC-Foundation")
                .warn("[CAF-LC] 无法查询结构所在世界的城市范围，本次保留原版结构",e);}
            return null;
        }
    }
}
