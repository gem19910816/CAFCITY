package com.caf.lccustomcity.impl;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

/** Fixed planning view: never follow DefaultDimensionInfo's mutable WorldGenRegion. */
final class SurveyDimensionInfo implements IDimensionInfo {
    private final ServerLevel level;
    private final long seed;
    private final ResourceKey<Level> type;
    private final LostCityProfile profile, outside;
    private final WorldStyle style;
    private final Runnable check;
    SurveyDimensionInfo(IDimensionInfo source, ServerLevel level, Runnable check) {
        this.level = level; this.check = check;
        seed = source.getSeed(); type = source.getType();
        profile = source.getProfile(); outside = source.getOutsideProfile(); style = source.getWorldStyle();
    }
    public WorldGenLevel getWorld() { check.run(); return level; }
    public long getSeed() { check.run(); return seed; }
    public ResourceKey<Level> getType() { check.run(); return type; }
    public ResourceKey<Level> dimension() { return getType(); }
    public LostCityProfile getProfile() { check.run(); return profile; }
    public LostCityProfile getOutsideProfile() { check.run(); return outside; }
    public WorldStyle getWorldStyle() { check.run(); return style; }
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos pos) {
        check.run();
        return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                net.minecraft.core.QuartPos.fromBlock(pos.getX()), net.minecraft.core.QuartPos.fromBlock(pos.getY()),
                net.minecraft.core.QuartPos.fromBlock(pos.getZ()), level.getChunkSource().randomState().sampler());
    }
    private UnsupportedOperationException unsupported() {
        check.run(); return new UnsupportedOperationException("City survey only supports deterministic planning; no chunk or mutable feature access");
    }
    public void setWorld(WorldGenLevel world) { throw unsupported(); }
    public mcjty.lostcities.config.StreetGenerationMode getStreetGenerationMode() { throw unsupported(); }
    public mcjty.lostcities.config.HighwayGenerationMode getHighwayGenerationMode() { throw unsupported(); }
    public mcjty.lostcities.worldgen.highway.IntercityHighwayPlanner getHighwayPlanner() { throw unsupported(); }
    public mcjty.lostcities.worldgen.highway.HighwayPlanningService getHighwayPlanningService() { throw unsupported(); }
    public mcjty.lostcities.worldgen.street.HierarchicalStreetPlanner getStreetPlanner() { throw unsupported(); }
    public java.util.Random getRandom() { throw unsupported(); }
    public mcjty.lostcities.worldgen.LostCityTerrainFeature getFeature() { throw unsupported(); }
    public mcjty.lostcities.worldgen.ChunkHeightmap getHeightmap(int x, int z) { throw unsupported(); }
    public mcjty.lostcities.worldgen.ChunkHeightmap getHeightmap(mcjty.lostcities.varia.ChunkCoord coord) { throw unsupported(); }
}
