package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.ForceFlattenConfig;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/** Prevents registered structure starts from placing any part inside a managed city. */
public final class CitySurfaceStructures {
    private static volatile boolean warned;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("CAF-LC-StructureGuard");
    private CitySurfaceStructures(){}
    public static boolean overlaps(BoundingBox box,BiPredicate<Integer,Integer> city){
        for(int x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxX(),16);x++)
            for(int z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZ(),16);z++)if(city.test(x,z))return true;
        return false;
    }
    public static boolean suppress(WorldGenLevel world,StructureStart start){
        if(!ForceFlattenConfig.enabled()||world==null||!start.isValid())return false;
        IDimensionInfo provider=provider(world);
        if(!suppress(start.getBoundingBox(),provider))return false;
        var structure=start.getStructure();
        var id=world.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure);
        var box=start.getBoundingBox();
        String structureId=String.valueOf(id == null ? structure : id);
        String key=structureId+"@"+box.minX()+","+box.minY()+","+box.minZ()+":"+box.maxX()+","+box.maxY()+","+box.maxZ();
        if(REPORTED.add(key))LOG.info("[CAF-LC] 已阻止结构与城市占地重叠: {} [{}..{}, {}..{}, {}..{}]",
            structureId,box.minX(),box.maxX(),box.minY(),box.maxY(),box.minZ(),box.maxZ());
        return true;
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
