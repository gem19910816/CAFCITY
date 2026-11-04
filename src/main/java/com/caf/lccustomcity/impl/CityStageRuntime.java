package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.StageLayoutConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime selection for the distance based city stages. */
public final class CityStageRuntime {
    public record Geometry(int radiusMinBlocks, int radiusMaxBlocks, StageLayoutConfig.Rules layout,
                           double outerShare,
                           Map<String, Double> zoneWeights,
                           Map<String, com.caf.lccustomcity.config.StagePartitionConfig.Partition> partitions) {
        public Geometry {
            radiusMinBlocks = Math.max(16, radiusMinBlocks);
            radiusMaxBlocks = Math.max(radiusMinBlocks, radiusMaxBlocks);
            outerShare = Math.max(0, Math.min(1, outerShare));
            zoneWeights = zoneWeights == null ? Map.of() : Map.copyOf(zoneWeights);
            partitions = partitions == null ? Map.of() : Map.copyOf(partitions);
            layout = layout == null ? StageLayoutConfig.defaults(radiusMinBlocks, radiusMaxBlocks) : layout;
        }

        public Geometry(int radiusMinBlocks, int radiusMaxBlocks, StageLayoutConfig.Rules layout,
                        double outerShare, Map<String, Double> zoneWeights) {
            this(radiusMinBlocks, radiusMaxBlocks, layout, outerShare, zoneWeights, Map.of());
        }

        public boolean hasPartitions() {
            return !partitions.isEmpty();
        }
    }

    private static volatile Map<String, Geometry> geometries = Map.of();
    private static final int MAX_ELIGIBILITY_CACHE = 16_384;
    private static final int MAX_TERRAIN_CACHE = 65_536;
    private record EligibilityKey(Object level, LostCityProfile profile, long seed,
                                  int spawnX, int spawnZ, int centerX, int centerZ, long revision) {}
    private record EligibilityValue(CityStageConfig.Stage stage) {}
    private record TerrainKey(Object level, long seed, long revision, int x, int z) {}
    private static final Map<EligibilityKey, EligibilityValue> ELIGIBILITY_CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<EligibilityKey, EligibilityValue> eldest) {
                    return size() > MAX_ELIGIBILITY_CACHE;
                }
            };
    private static final Map<TerrainKey, Boolean> TERRAIN_CACHE =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TerrainKey, Boolean> eldest) {
                    return size() > MAX_TERRAIN_CACHE;
                }
            };

    private CityStageRuntime() {
    }

    public static void installGeometries(Map<String, Geometry> values) {
        geometries = values == null ? Map.of() : Map.copyOf(values);
        clearEligibilityCache();
    }

    public static Geometry geometry(CityStageConfig.Stage stage, String style) {
        if (stage == null || style == null) return null;
        return geometries.get(stage.name() + "\u0000" + style);
    }

    public static int maxRadiusBlocks(int fallbackBlocks) {
        if (!CityStageConfig.current().enabled()) return fallbackBlocks;
        int max = 0;
        for (Geometry geometry : geometries.values()) {
            max = Math.max(max, geometry.radiusMaxBlocks());
        }
        return max > 0 ? max : fallbackBlocks;
    }

    /** Largest configured minimum center spacing, in blocks. */
    public static int maxMinimumCenterSpacingBlocks() {
        int max = 0;
        for (Geometry geometry : geometries.values()) {
            StageLayoutConfig.Range spacing = geometry.layout().centerSpacing();
            if (spacing != null && spacing.min() != null) {
                max = Math.max(max, spacing.min());
            }
        }
        return max;
    }

    public static int radiusBlocks(IDimensionInfo provider, CityStageConfig.Stage stage,
                                   String style, int fallbackBlocks, int centerX, int centerZ) {
        Geometry geometry = geometry(stage, style);
        if (geometry == null) return fallbackBlocks;
        int span = geometry.radiusMaxBlocks() - geometry.radiusMinBlocks() + 1;
        long seed = RadialZonePlan.citySeed(provider.getSeed() ^ stage.name().hashCode()
                ^ style.hashCode(), centerX, centerZ);
        return geometry.radiusMinBlocks() + (span <= 1 ? 0 : (int) Math.floorMod(seed, span));
    }

    /**
     * Returns the configured stage for a city center, or {@code null} when the
     * new stage system is disabled, the center is outside every stage, or its
     * deterministic probability roll rejects the city.
     */
    public static CityStageConfig.Stage acceptedStage(IDimensionInfo provider, LostCityProfile profile,
                                                       int centerX, int centerZ) {
        CityStageConfig config = CityStageConfig.current();
        if (!config.enabled() || provider == null || profile == null
                || provider.getWorld() == null || profile != provider.getProfile()) {
            return null;
        }
        WorldGenLevel world = provider.getWorld();
        BlockPos spawn = world.getLevel().getSharedSpawnPos();
        if (spawn == null) {
            return null;
        }
        EligibilityKey key = new EligibilityKey(world.getLevel(), profile, provider.getSeed(),
                spawn.getX(), spawn.getZ(), centerX, centerZ, config.revision());
        synchronized (ELIGIBILITY_CACHE) {
            EligibilityValue cached = ELIGIBILITY_CACHE.get(key);
            if (cached != null) return cached.stage();
        }
        boolean spawnAnchor = centerX == Math.floorDiv(spawn.getX(), 16) * 16
                && centerZ == Math.floorDiv(spawn.getZ(), 16) * 16;
        CityStageConfig.Stage stage = spawnAnchor
                ? computePlannedStage(config, provider, profile, spawn, centerX, centerZ)
                : computeAcceptedStage(config, provider, profile, world, spawn, centerX, centerZ);
        synchronized (ELIGIBILITY_CACHE) {
            ELIGIBILITY_CACHE.put(key, new EligibilityValue(stage));
        }
        return stage;
    }

    /** World-wide stage target derived from the configured style ranges. */
    public static int worldQuota(IDimensionInfo provider, String stageName) {
        CityStageConfig config = CityStageConfig.current();
        long total = 0;
        for (String style : config.stylesForStage(stageName)) {
            CityStageConfig.StyleStage rule = config.styleStage(style, stageName);
            if (rule == null || !rule.enabled()) continue;
            int min = Math.max(0, Math.min(rule.minCities(), rule.maxCities()));
            int max = Math.max(min, rule.maxCities());
            long span = (long) max - min + 1;
            long seed = provider.getSeed() ^ stageName.hashCode() ^ style.hashCode();
            total += min + Math.floorMod(RadialZonePlan.mix(seed), span);
        }
        return (int) Math.min(4096, total);
    }

    /** Applies the configured minimum center spacing to the global selected set. */
    public static boolean worldSpacingAllows(IDimensionInfo provider, CityLayout.Center candidate,
                                             List<CityLayout.Center> accepted) {
        int candidateMinimum = minimumSpacing(provider, candidate);
        for (CityLayout.Center other : accepted) {
            int required = Math.max(candidateMinimum, minimumSpacing(provider, other));
            if (closerThan(candidate, other, required)) return false;
        }
        return true;
    }

    /** Cheap deterministic checks used before searching nearby terrain alternatives. */
    public static CityStageConfig.Stage plannedStage(IDimensionInfo provider, LostCityProfile profile,
                                                      int centerX, int centerZ) {
        CityStageConfig config = CityStageConfig.current();
        if (!config.enabled() || provider == null || profile == null
                || provider.getWorld() == null || profile != provider.getProfile()) return null;
        BlockPos spawn = provider.getWorld().getLevel().getSharedSpawnPos();
        return spawn == null ? null : computePlannedStage(config, provider, profile, spawn, centerX, centerZ);
    }

    private static CityStageConfig.Stage computeAcceptedStage(CityStageConfig config, IDimensionInfo provider,
                                                                LostCityProfile profile, WorldGenLevel world,
                                                                BlockPos spawn, int centerX, int centerZ) {
        CityStageConfig.Stage stage = computePlannedStage(config, provider, profile, spawn, centerX, centerZ);
        if (stage == null) return null;
        String style = selectedStyle(provider, stage, centerX, centerZ);
        Geometry geometry = geometry(stage, style);
        if (!terrainAllowed(config, world, provider.getSeed(), centerX, centerZ)) return null;
        if (geometry != null && geometry.layout().terrainCheckEnabled()
                && !terrainNeighborhoodAllowed(config, world, provider.getSeed(), centerX, centerZ,
                geometry.layout().terrainCheckRadiusBlocks())) return null;
        return stage;
    }

    private static CityStageConfig.Stage computePlannedStage(CityStageConfig config, IDimensionInfo provider,
                                                               LostCityProfile profile, BlockPos spawn,
                                                               int centerX, int centerZ) {
        long dx = (long) centerX - spawn.getX();
        long dz = (long) centerZ - spawn.getZ();
        long squared = dx * dx + dz * dz;
        int distance = (int) Math.min(Integer.MAX_VALUE, Math.ceil(Math.sqrt((double) squared)));
        CityStageConfig.Stage stage = config.stageAtDistance(distance);
        if (stage == null) {
            return null;
        }
        String style = selectedStyle(provider, stage, centerX, centerZ);
        CityStageConfig.StyleStage rule = config.styleStage(style, stage.name());
        if (rule == null || !rule.enabled()) {
            return null;
        }
        double roll = probabilityRoll(provider.getSeed(), centerX, centerZ, stage.name() + ":" + style);
        Geometry geometry = geometry(stage, style);
        double chance = geometry == null || geometry.layout().centerChance() == null
                ? rule.probability() : geometry.layout().centerChance();
        if (chance <= 0 || roll >= chance) {
            return null;
        }
        return stage;
    }

    public static void clearEligibilityCache() {
        synchronized (ELIGIBILITY_CACHE) {
            ELIGIBILITY_CACHE.clear();
        }
        synchronized (TERRAIN_CACHE) {
            TERRAIN_CACHE.clear();
        }
    }

    private static boolean terrainAllowed(CityStageConfig config, WorldGenLevel world, long seed,
                                          int centerX, int centerZ) {
        TerrainKey cacheKey = new TerrainKey(world.getLevel(), seed, config.revision(), centerX, centerZ);
        synchronized (TERRAIN_CACHE) {
            Boolean cached = TERRAIN_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        ServerLevel level = world.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        // Candidate centers are scanned well beyond the current WorldGenRegion.
        // Sample the generator directly so eligibility never requests an ungenerated chunk.
        if (!config.allowedBiomes().isEmpty()) {
            int sampleY = generator.getSeaLevel();
            String biomeId = generator.getBiomeSource().getNoiseBiome(
                            QuartPos.fromBlock(centerX), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(centerZ),
                            randomState.sampler())
                    .unwrapKey().map(key -> key.location().toString()).orElse("");
            if (!config.allowedBiomes().contains(biomeId)) {
                cacheTerrain(cacheKey, false);
                return false;
            }
        }
        int surfaceY = generator.getBaseHeight(centerX, centerZ, Heightmap.Types.WORLD_SURFACE_WG,
                level, randomState);
        boolean allowed = surfaceY >= config.minTerrainHeight() && surfaceY <= config.maxTerrainHeight();
        cacheTerrain(cacheKey, allowed);
        return allowed;
    }

    private static void cacheTerrain(TerrainKey key, boolean allowed) {
        synchronized (TERRAIN_CACHE) {
            TERRAIN_CACHE.put(key, allowed);
        }
    }

    /** Checks a coarse circular neighborhood so a city center cannot sit beside an invalid coast or cliff. */
    private static boolean terrainNeighborhoodAllowed(CityStageConfig config, WorldGenLevel world, long seed,
                                                      int centerX, int centerZ, int radius) {
        if (radius <= 0) return true;
        int step = 16;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) continue;
                if (!terrainAllowed(config, world, seed, centerX + dx, centerZ + dz)) return false;
            }
        }
        // The loop step rarely lands exactly on the configured boundary.
        for (int[] edge : new int[][]{{radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}}) {
            if (!terrainAllowed(config, world, seed, centerX + edge[0], centerZ + edge[1])) return false;
        }
        return true;
    }

    public static String selectedStyle(IDimensionInfo provider, CityStageConfig.Stage stage,
                                       int centerX, int centerZ) {
        if (provider == null || stage == null) return null;
        return CityStageConfig.current().styleForCity(stage.name(), provider.getSeed(), centerX, centerZ);
    }

    /** True when the custom stage layout should handle this chunk. */
    public static boolean handles(IDimensionInfo provider, LostCityProfile profile, CityLayout.Center center) {
        if (!CityStageConfig.current().enabled()) {
            return true;
        }
        return center != null && acceptedStage(provider, profile, center.x(), center.z()) != null;
    }

    /** Applies the world allow-list, then keeps the legacy local cap as a secondary guard. */
    public static List<CityLayout.Center> applyQuotas(IDimensionInfo provider, LostCityProfile profile,
                                                        List<CityLayout.Center> candidates) {
        if (candidates.isEmpty()) return candidates;
        CityStageConfig config = CityStageConfig.current();
        if (!config.enabled()) return candidates;
        candidates = candidates.stream().filter(candidate ->
                CustomCityCenterPlanner.worldCenterAllowed(provider, profile, candidate.x(), candidate.z())).toList();
        if (candidates.isEmpty()) return List.of();
        // Enforce minimum spacing before regional count quotas. If quotas run
        // first, two overlapping scan windows can retain different subsets and
        // let the same close pair both survive in different tile plans.
        candidates = filterCenterSpacing(provider, candidates);
        Map<String, List<CityLayout.Center>> grouped = new LinkedHashMap<>();
        for (CityLayout.Center candidate : candidates) {
            CityStageConfig.Stage stage = acceptedStage(provider, profile, candidate.x(), candidate.z());
            if (stage != null) {
                String style = selectedStyle(provider, stage, candidate.x(), candidate.z());
                grouped.computeIfAbsent(stage.name() + "\u0000" + style, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        List<CityLayout.Center> result = new ArrayList<>();
        for (Map.Entry<String, List<CityLayout.Center>> entry : grouped.entrySet()) {
            List<CityLayout.Center> group = entry.getValue();
            int separator = entry.getKey().indexOf('\u0000');
            String stageName = separator < 0 ? entry.getKey() : entry.getKey().substring(0, separator);
            String styleName = separator < 0 ? null : entry.getKey().substring(separator + 1);
            CityStageConfig.Stage stage = config.stage(stageName);
            CityStageConfig.StyleStage rule = stage == null || styleName == null
                    ? null : config.styleStage(styleName, stage.name());
            if (rule == null || rule.maxCities() == Integer.MAX_VALUE || group.size() <= rule.maxCities()) {
                result.addAll(group);
                continue;
            }
            int min = Math.min(rule.minCities(), rule.maxCities());
            int span = rule.maxCities() - min + 1;
            long seed = provider.getSeed() ^ entry.getKey().hashCode();
            int target = min + (span <= 1 ? 0 : (int) Math.floorMod(RadialZonePlan.mix(seed), span));
            group.sort(Comparator.comparingLong(c -> candidateRank(seed, c)));
            result.addAll(group.subList(0, Math.min(target, group.size())));
        }
        return List.copyOf(result);
    }

    /** Deterministically removes native centers that violate configured center spacing. */
    private static List<CityLayout.Center> filterCenterSpacing(IDimensionInfo provider,
                                                                List<CityLayout.Center> candidates) {
        if (candidates.isEmpty()) return List.of();
        List<CityLayout.Center> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong(c -> candidateRank(provider.getSeed(), c)));
        List<CityLayout.Center> accepted = new ArrayList<>();
        // The spawn guarantee is a real city center too. Keep it as an anchor
        // while filtering native centers, but never add it to the returned list
        // because CityLayout handles the synthetic center separately.
        CityLayout.Center forced = forcedCenter(provider);
        List<CityLayout.Center> anchors = new ArrayList<>();
        if (forced != null) anchors.add(forced);
        for (CityLayout.Center candidate : ordered) {
            CityStageConfig.Stage stage = acceptedStage(provider, provider.getProfile(),
                    candidate.x(), candidate.z());
            if (stage == null) continue;
            String style = selectedStyle(provider, stage, candidate.x(), candidate.z());
            Geometry geometry = geometry(stage, style);
            StageLayoutConfig.Range spacing = geometry == null ? null : geometry.layout().centerSpacing();
            int minimum = spacing == null
                    || spacing.min() == null
                    ? 0 : spacing.min();
            int maximum = spacing == null || spacing.max() == null ? 0 : spacing.max();
            boolean clear = true;
            for (CityLayout.Center other : anchors) {
                long required = Math.max(minimum, minimumSpacing(provider, other));
                if (closerThan(candidate, other, required)) { clear = false; break; }
            }
            // Decide each close pair by stable rank rather than by the order in
            // which a tile-local scan happened to encounter its neighbors.
            // This makes overlapping scan windows agree on which center wins.
            if (clear) for (CityLayout.Center other : ordered) {
                if (sameCenter(candidate, other)) continue;
                long required = Math.max(minimum, minimumSpacing(provider, other));
                if (closerThan(candidate, other, required)
                        && compareCenterPriority(other, candidate, provider.getSeed()) < 0) {
                    clear = false;
                    break;
                }
            }
            if (clear && maximum > 0 && (!accepted.isEmpty() || !anchors.isEmpty())) {
                long maxSquared = (long) maximum * maximum;
                boolean hasNearby = false;
                for (CityLayout.Center other : concat(anchors, accepted)) {
                    long dx = (long) candidate.x() - other.x();
                    long dz = (long) candidate.z() - other.z();
                    if (dx * dx + dz * dz <= maxSquared) { hasNearby = true; break; }
                }
                clear = hasNearby;
            }
            if (clear) accepted.add(candidate);
        }
        return List.copyOf(accepted);
    }

    private static List<CityLayout.Center> concat(List<CityLayout.Center> first,
                                                  List<CityLayout.Center> second) {
        List<CityLayout.Center> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static CityLayout.Center forcedCenter(IDimensionInfo provider) {
        int[] value = SpawnCityRule.centerFor(provider, provider.getProfile());
        if (value == null) return null;
        return acceptedStage(provider, provider.getProfile(), value[0], value[1]) == null
                ? null : new CityLayout.Center(value[0], value[1], value[2]);
    }

    private static int minimumSpacing(IDimensionInfo provider, CityLayout.Center center) {
        CityStageConfig.Stage stage = acceptedStage(provider, provider.getProfile(), center.x(), center.z());
        if (stage == null) return 0;
        String style = selectedStyle(provider, stage, center.x(), center.z());
        Geometry geometry = geometry(stage, style);
        StageLayoutConfig.Range spacing = geometry == null ? null : geometry.layout().centerSpacing();
        return spacing == null || spacing.min() == null ? 0 : spacing.min();
    }

    private static long candidateRank(long seed, CityLayout.Center center) {
        return RadialZonePlan.citySeed(seed, center.x(), center.z());
    }

    private static boolean closerThan(CityLayout.Center first, CityLayout.Center second, long distance) {
        if (distance <= 0) return false;
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz < distance * distance;
    }

    private static boolean sameCenter(CityLayout.Center first, CityLayout.Center second) {
        return first.x() == second.x() && first.z() == second.z();
    }

    private static int compareCenterPriority(CityLayout.Center first, CityLayout.Center second, long seed) {
        int rank = Long.compareUnsigned(candidateRank(seed, first), candidateRank(seed, second));
        if (rank != 0) return rank;
        int x = Integer.compare(first.x(), second.x());
        return x != 0 ? x : Integer.compare(first.z(), second.z());
    }

    private static double probabilityRoll(long seed, int centerX, int centerZ, String stage) {
        long value = RadialZonePlan.citySeed(seed ^ stage.hashCode(), centerX, centerZ);
        return (value >>> 11) * 0x1.0p-53;
    }
}
