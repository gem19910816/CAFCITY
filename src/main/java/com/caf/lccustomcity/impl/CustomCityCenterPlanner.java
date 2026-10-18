package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.StageLayoutConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable, seed-based candidate city centers for the distance stage system.
 * The reference grid is only a planning aid; it never creates blocks or visible
 * borders. Candidates are validated by CityStageRuntime before they are used.
 */
public final class CustomCityCenterPlanner {
    private static final int CHUNK = 16;
    private static final int TILE_BLOCKS = 64 * CHUNK;
    private static final long VERSION = 0x43414643454e5441L;
    /** Search up to four chunks around a hashed point so valid terrain nearby is not missed. */
    private static final int LOCAL_SEARCH_CHUNKS = 4;
    private static final int MAX_RESOLVED_CANDIDATES = 65_536;
    private record CandidateKey(Object level, long seed, long revision, String stageName,
                                int originX, int originZ) {}
    private record CandidateResolution(int x, int z) {}
    private static final Map<CandidateKey, CandidateResolution> RESOLVED_CANDIDATES =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CandidateKey, CandidateResolution> eldest) {
                    return size() > MAX_RESOLVED_CANDIDATES;
                }
            };
    private record SpawnCandidate(int x, int z, int radius, long distanceSquared) {}
    private record PlannedPoint(int x, int z, String stageName, long distanceSquared) {}

    private CustomCityCenterPlanner() {}

    public static List<CityLayout.Center> scan(ChunkCoord coord, IDimensionInfo provider,
                                               LostCityProfile profile, int tileX, int tileZ) {
        if (!CityStageConfig.current().enabled() || coord == null || provider == null || profile == null) {
            return List.of();
        }
        CityStageConfig config = CityStageConfig.current();
        int margin = Math.max(CityStageRuntime.maxRadiusBlocks(profile.CITY_MAXRADIUS),
                CityStageRuntime.maxMinimumCenterSpacingBlocks()) + 64;
        int minX = tileX * TILE_BLOCKS - margin;
        int maxX = (tileX + 1) * TILE_BLOCKS + margin;
        int minZ = tileZ * TILE_BLOCKS - margin;
        int maxZ = (tileZ + 1) * TILE_BLOCKS + margin;
        Set<Long> seen = new HashSet<>();
        List<CityLayout.Center> result = new ArrayList<>();

        for (String stageName : config.stageNames()) {
            CityStageConfig.Stage stage = config.stage(stageName);
            if (stage == null || !stage.enabled()) continue;
            int grid = gridSize(stage);
            int count = candidateCount(stage);
            int gx0 = Math.floorDiv(minX, grid) - 1;
            int gx1 = Math.floorDiv(maxX, grid) + 1;
            int gz0 = Math.floorDiv(minZ, grid) - 1;
            int gz1 = Math.floorDiv(maxZ, grid) + 1;
            for (int gx = gx0; gx <= gx1; gx++) {
                for (int gz = gz0; gz <= gz1; gz++) {
                    long cellSeed = RadialZonePlan.citySeed(provider.getSeed() ^ VERSION
                            ^ stageName.hashCode(), gx, gz);
                    for (int index = 0; index < count; index++) {
                        long pointSeed = RadialZonePlan.mix(cellSeed + index * 0x9e3779b97f4a7c15L);
                        int localX = 16 + (int) Math.floorMod(pointSeed, Math.max(16, grid - 32));
                        int localZ = 16 + (int) Math.floorMod(RadialZonePlan.mix(pointSeed), Math.max(16, grid - 32));
                        int x = Math.floorDiv(gx * grid + localX, CHUNK) * CHUNK;
                        int z = Math.floorDiv(gz * grid + localZ, CHUNK) * CHUNK;
                        if (x < minX - 32 || x > maxX + 32 || z < minZ - 32 || z > maxZ + 32) continue;
                        int[] eligible = resolveCandidate(provider, profile, stageName, x, z);
                        if (eligible == null) continue;
                        x = eligible[0];
                        z = eligible[1];
                        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                        if (!seen.add(key)) continue;
                        CityStageConfig.Stage accepted = CityStageRuntime.acceptedStage(provider, profile, x, z);
                        if (accepted == null || !accepted.name().equals(stageName)) continue;
                        String style = CityStageRuntime.selectedStyle(provider, accepted, x, z);
                        if (style == null) continue;
                        CityStageRuntime.Geometry geometry = CityStageRuntime.geometry(accepted, style);
                        int fallback = Math.max(16, profile.CITY_MAXRADIUS);
                        int radius = CityStageRuntime.radiusBlocks(provider, accepted, style, fallback, x, z);
                        result.add(new CityLayout.Center(x, z, radius * (1 - profile.CITY_THRESHOLD)));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static int[] nearestEligible(IDimensionInfo provider, LostCityProfile profile,
                                         String stageName, int originX, int originZ) {
        for (int ring = 0; ring <= LOCAL_SEARCH_CHUNKS; ring++) {
            if (ring == 0) {
                if (isEligible(provider, profile, stageName, originX, originZ)) {
                    return new int[]{originX, originZ};
                }
                continue;
            }
            for (int offset = -ring; offset <= ring; offset++) {
                int[] candidate = new int[]{originX + offset * CHUNK, originZ - ring * CHUNK};
                if (isEligible(provider, profile, stageName, candidate[0], candidate[1])) return candidate;
                candidate = new int[]{originX + offset * CHUNK, originZ + ring * CHUNK};
                if (isEligible(provider, profile, stageName, candidate[0], candidate[1])) return candidate;
            }
            for (int offset = -ring + 1; offset < ring; offset++) {
                int[] candidate = new int[]{originX - ring * CHUNK, originZ + offset * CHUNK};
                if (isEligible(provider, profile, stageName, candidate[0], candidate[1])) return candidate;
                candidate = new int[]{originX + ring * CHUNK, originZ + offset * CHUNK};
                if (isEligible(provider, profile, stageName, candidate[0], candidate[1])) return candidate;
            }
        }
        return null;
    }

    /** Neighboring tile scans overlap heavily; resolve each deterministic grid point once. */
    private static int[] resolveCandidate(IDimensionInfo provider, LostCityProfile profile,
                                          String stageName, int originX, int originZ) {
        CandidateKey key = new CandidateKey(provider.getWorld().getLevel(), provider.getSeed(),
                CityStageConfig.current().revision(), stageName, originX, originZ);
        synchronized (RESOLVED_CANDIDATES) {
            CandidateResolution cached = RESOLVED_CANDIDATES.get(key);
            if (cached != null) {
                return cached.x() == Integer.MIN_VALUE ? null : new int[]{cached.x(), cached.z()};
            }
        }
        int[] resolved = nearestEligible(provider, profile, stageName, originX, originZ);
        synchronized (RESOLVED_CANDIDATES) {
            RESOLVED_CANDIDATES.put(key, resolved == null
                    ? new CandidateResolution(Integer.MIN_VALUE, Integer.MIN_VALUE)
                    : new CandidateResolution(resolved[0], resolved[1]));
        }
        return resolved;
    }

    private static boolean isEligible(IDimensionInfo provider, LostCityProfile profile,
                                      String stageName, int x, int z) {
        CityStageConfig.Stage planned = CityStageRuntime.plannedStage(provider, profile, x, z);
        if (planned == null || !planned.name().equals(stageName)) return false;
        CityStageConfig.Stage accepted = CityStageRuntime.acceptedStage(provider, profile, x, z);
        return accepted != null && accepted.name().equals(stageName);
    }

    /** Finds the nearest eligible planned center at or beyond the configured spawn distance. */
    public static int[] nearestOutside(IDimensionInfo provider, LostCityProfile profile,
                                       int spawnX, int spawnZ, int minimumDistance) {
        return nearestOutside(provider, profile, spawnX, spawnZ, minimumDistance, new int[0][], 0);
    }

    public static int[] nearestOutside(IDimensionInfo provider, LostCityProfile profile,
                                       int spawnX, int spawnZ, int minimumDistance,
                                       int[][] forbiddenCenters, int minimumSpacing) {
        if (provider == null || profile == null || minimumDistance < 0) return null;
        int searchRadius = Math.min(100000, minimumDistance + 8192);
        long minimumSquared = (long) minimumDistance * minimumDistance;
        long maximumSquared = (long) searchRadius * searchRadius;
        int candidateMargin = (int) Math.ceil(Math.sqrt(2) * LOCAL_SEARCH_CHUNKS * CHUNK);
        List<PlannedPoint> points = new ArrayList<>();
        for (String stageName : CityStageConfig.current().stageNames()) {
            CityStageConfig.Stage stage = CityStageConfig.current().stage(stageName);
            if (stage == null || !stage.enabled()) continue;
            int grid = gridSize(stage);
            int count = candidateCount(stage);
            int gx0 = Math.floorDiv(spawnX - searchRadius - candidateMargin, grid);
            int gx1 = Math.floorDiv(spawnX + searchRadius + candidateMargin, grid);
            int gz0 = Math.floorDiv(spawnZ - searchRadius - candidateMargin, grid);
            int gz1 = Math.floorDiv(spawnZ + searchRadius + candidateMargin, grid);
            for (int gx = gx0; gx <= gx1; gx++) {
                for (int gz = gz0; gz <= gz1; gz++) {
                    long cellSeed = RadialZonePlan.citySeed(provider.getSeed() ^ VERSION
                            ^ stageName.hashCode(), gx, gz);
                    for (int index = 0; index < count; index++) {
                        long pointSeed = RadialZonePlan.mix(cellSeed + index * 0x9e3779b97f4a7c15L);
                        int localX = 16 + (int) Math.floorMod(pointSeed, Math.max(16, grid - 32));
                        int localZ = 16 + (int) Math.floorMod(RadialZonePlan.mix(pointSeed), Math.max(16, grid - 32));
                        int originX = Math.floorDiv(gx * grid + localX, CHUNK) * CHUNK;
                        int originZ = Math.floorDiv(gz * grid + localZ, CHUNK) * CHUNK;
                        long dx = (long) originX - spawnX;
                        long dz = (long) originZ - spawnZ;
                        points.add(new PlannedPoint(originX, originZ, stageName, dx * dx + dz * dz));
                    }
                }
            }
        }
        points.sort(java.util.Comparator.comparingLong(PlannedPoint::distanceSquared)
                .thenComparing(PlannedPoint::stageName).thenComparingInt(PlannedPoint::x)
                .thenComparingInt(PlannedPoint::z));
        Set<Long> seen = new HashSet<>();
        SpawnCandidate best = null;
        for (PlannedPoint point : points) {
            if (best != null) {
                double minimumPossibleDistance = Math.max(0,
                        Math.sqrt(point.distanceSquared()) - candidateMargin);
                if (minimumPossibleDistance * minimumPossibleDistance > best.distanceSquared()) break;
            }
            int[] eligible = resolveCandidate(provider, profile, point.stageName(), point.x(), point.z());
            if (eligible == null) continue;
            long key = (((long) eligible[0]) << 32) ^ (eligible[1] & 0xffffffffL);
            if (!seen.add(key)) continue;
            long dx = (long) eligible[0] - spawnX;
            long dz = (long) eligible[1] - spawnZ;
            long squared = dx * dx + dz * dz;
            if (squared < minimumSquared || squared > maximumSquared
                    || (best != null && squared >= best.distanceSquared())) continue;
            if (minimumSpacing > 0 && tooClose(eligible[0], eligible[1], forbiddenCenters, minimumSpacing)) continue;
            if (minimumSpacing > 0 && hasNativeCenterNear(provider, profile,
                    eligible[0], eligible[1], minimumSpacing)) continue;
            CityStageConfig.Stage stage = CityStageRuntime.acceptedStage(provider, profile, eligible[0], eligible[1]);
            if (stage == null) continue;
            int radius = CityStageRuntime.radiusBlocks(provider, stage,
                    CityStageRuntime.selectedStyle(provider, stage, eligible[0], eligible[1]),
                    Math.max(16, profile.CITY_MAXRADIUS), eligible[0], eligible[1]);
            best = new SpawnCandidate(eligible[0], eligible[1], radius, squared);
        }
        return best == null ? null : new int[]{best.x(), best.z(), best.radius()};
    }

    private static boolean tooClose(int x, int z, int[][] centers, int spacing) {
        long limit = (long) spacing * spacing;
        for (int[] center : centers) {
            if (center == null || center.length < 2) continue;
            long dx = (long) x - center[0], dz = (long) z - center[1];
            if (dx * dx + dz * dz < limit) return true;
        }
        return false;
    }

    private static boolean hasNativeCenterNear(IDimensionInfo provider, LostCityProfile profile,
                                               int x, int z, int spacing) {
        int chunkX = Math.floorDiv(x, CHUNK), chunkZ = Math.floorDiv(z, CHUNK);
        int range = (spacing + CHUNK - 1) / CHUNK;
        long limit = (long) spacing * spacing;
        for (int dx = -range; dx <= range; dx++) for (int dz = -range; dz <= range; dz++) {
            int cx = chunkX + dx, cz = chunkZ + dz;
            long bx = (long) cx * CHUNK - x, bz = (long) cz * CHUNK - z;
            if (bx * bx + bz * bz >= limit) continue;
            ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
            if (BuildingInfo.getProfile(coord, provider) == profile && City.isCityCenter(coord, provider)) return true;
        }
        return false;
    }

    private static int candidateCount(CityStageConfig.Stage stage) {
        int value = 0;
        for (String style : CityStageConfig.current().stylesForStage(stage.name())) {
            CityStageRuntime.Geometry geometry = CityStageRuntime.geometry(stage, style);
            if (geometry != null) value = Math.max(value, geometry.layout().candidateCenterCount());
        }
        return value == 0 ? 10 : value;
    }

    private static int gridSize(CityStageConfig.Stage stage) {
        int value = 0;
        for (String style : CityStageConfig.current().stylesForStage(stage.name())) {
            CityStageRuntime.Geometry geometry = CityStageRuntime.geometry(stage, style);
            if (geometry != null) value = Math.max(value, geometry.layout().candidateGridSize());
        }
        return value == 0 ? 1024 : value;
    }
}
