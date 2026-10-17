package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.StagePartitionConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic geometry resolver for the stage partition system.
 * Coordinates and sizes are expressed in chunks at the configuration boundary.
 */
public final class StagePartitionPlanner {
    private static final int CHUNK = 16;

    private record Resolved(String name, StagePartitionConfig.Partition partition,
                            double centerX, double centerZ) {
    }
    private record CacheKey(Map<String, StagePartitionConfig.Partition> partitions,
                            long seed, int centerX, int centerZ,
                            long radiusBits, int chunkX, int chunkZ) {
    }

    private static final Map<CacheKey, String> CACHE = new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, String> eldest) {
            return size() > 32768;
        }
    };

    private StagePartitionPlanner() {
    }

    /**
     * Selects the partition for one chunk. Fixed partitions are evaluated first,
     * random partitions use a stable center chosen outside the fixed masks, and
     * uncovered cells inherit from the nearest adjacent partition.
     */
    public static String select(Map<String, StagePartitionConfig.Partition> definitions,
                                long worldSeed, int cityCenterX, int cityCenterZ,
                                double cityRadiusBlocks, int chunkX, int chunkZ) {
        if (definitions == null || definitions.isEmpty()) return null;
        CacheKey key = new CacheKey(definitions, worldSeed, cityCenterX, cityCenterZ,
                Double.doubleToLongBits(cityRadiusBlocks), chunkX, chunkZ);
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
        }
        List<Resolved> resolved = resolve(definitions, worldSeed, cityCenterX, cityCenterZ,
                cityRadiusBlocks);
        if (resolved.isEmpty()) return cache(key, null);

        List<Resolved> direct = containing(resolved, cityCenterX, cityCenterZ,
                chunkX * CHUNK + CHUNK / 2.0, chunkZ * CHUNK + CHUNK / 2.0);
        if (!direct.isEmpty()) return cache(key, choose(direct, worldSeed, chunkX, chunkZ));

        // A gap between shapes belongs to the connected partition around it.
        for (int distance = 1; distance <= 8; distance++) {
            List<Resolved> adjacent = new ArrayList<>();
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                    double x = (chunkX + dx) * CHUNK + CHUNK / 2.0;
                    double z = (chunkZ + dz) * CHUNK + CHUNK / 2.0;
                    List<Resolved> owners = containing(resolved, cityCenterX, cityCenterZ, x, z);
                    if (!owners.isEmpty()) {
                        String owner = choose(owners, worldSeed, chunkX + dx, chunkZ + dz);
                        Resolved candidate = owners.stream().filter(value -> value.name().equals(owner))
                                .findFirst().orElse(null);
                        if (candidate != null && adjacent.stream()
                                .noneMatch(existing -> existing.name().equals(candidate.name()))) {
                            adjacent.add(candidate);
                        }
                    }
                }
            }
            if (!adjacent.isEmpty()) return cache(key, choose(adjacent, worldSeed, chunkX, chunkZ));
        }

        // This only applies to a completely uncovered outer corner. It still gets
        // a deterministic owner instead of becoming an unclassified hole.
        double x = chunkX * CHUNK + CHUNK / 2.0;
        double z = chunkZ * CHUNK + CHUNK / 2.0;
        double nearestDistance = resolved.stream().mapToDouble(candidate -> distanceToShape(candidate, x, z))
                .min().orElse(Double.POSITIVE_INFINITY);
        List<Resolved> nearest = resolved.stream()
                .filter(candidate -> Math.abs(distanceToShape(candidate, x, z) - nearestDistance) < 1.0e-6)
                .toList();
        return cache(key, nearest.isEmpty() ? null : choose(nearest, worldSeed, chunkX, chunkZ));
    }

    private static String cache(CacheKey key, String value) {
        synchronized (CACHE) { CACHE.put(key, value); }
        return value;
    }

    private static List<Resolved> resolve(Map<String, StagePartitionConfig.Partition> definitions,
                                           long worldSeed, int cityCenterX, int cityCenterZ,
                                           double cityRadiusBlocks) {
        List<StagePartitionConfig.Partition> fixed = new ArrayList<>();
        List<StagePartitionConfig.Partition> random = new ArrayList<>();
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            StagePartitionConfig.Partition partition = entry.getValue();
            if (partition != null && partition.enabled()) {
                if (isRandom(partition.placement())) random.add(partition);
                else fixed.add(partition);
            }
        });
        List<Resolved> result = new ArrayList<>();
        for (StagePartitionConfig.Partition partition : fixed) {
            result.add(new Resolved(partition.name(), partition,
                    cityCenterX + partition.centerX() * CHUNK,
                    cityCenterZ + partition.centerZ() * CHUNK));
        }
        for (StagePartitionConfig.Partition partition : random) {
            double[] center = randomCenter(partition, result, worldSeed, cityCenterX,
                    cityCenterZ, cityRadiusBlocks);
            result.add(new Resolved(partition.name(), partition, center[0], center[1]));
        }
        return result;
    }

    private static double[] randomCenter(StagePartitionConfig.Partition partition,
                                         List<Resolved> fixed, long worldSeed,
                                         int cityCenterX, int cityCenterZ,
                                         double cityRadiusBlocks) {
        int radius = Math.max(1, partition.outerRadius()) * CHUNK;
        double limit = Math.max(0, cityRadiusBlocks - radius - CHUNK / 2.0);
        long seed = RadialZonePlan.citySeed(worldSeed ^ partition.name().hashCode()
                ^ 0x7f4a7c159e3779b9L, cityCenterX, cityCenterZ);
        double bestScore = Double.POSITIVE_INFINITY;
        long bestTie = -1L;
        double bestX = cityCenterX;
        double bestZ = cityCenterZ;
        int attempts = 96;
        for (int attempt = 0; attempt < attempts; attempt++) {
            long value = RadialZonePlan.mix(seed + attempt * 0x9e3779b97f4a7c15L);
            double ux = unit(value);
            double uz = unit(RadialZonePlan.mix(value ^ 0xc2b2ae3d27d4eb4fL));
            double x = cityCenterX + Math.rint((ux * 2 - 1) * limit / CHUNK) * CHUNK;
            double z = cityCenterZ + Math.rint((uz * 2 - 1) * limit / CHUNK) * CHUNK;
            if (limit > 0 && Math.hypot(x - cityCenterX, z - cityCenterZ) > cityRadiusBlocks - radius) {
                continue;
            }
            double score = overlapScore(partition, x, z, fixed, cityCenterX, cityCenterZ);
            long tie = RadialZonePlan.mix(value ^ 0x6a09e667f3bcc909L);
            if (score < bestScore || score == bestScore && Long.compareUnsigned(tie, bestTie) < 0) {
                bestScore = score;
                bestTie = tie;
                bestX = x;
                bestZ = z;
            }
        }
        return new double[]{bestX, bestZ};
    }

    private static double overlapScore(StagePartitionConfig.Partition random,
                                       double centerX, double centerZ, List<Resolved> fixed,
                                       int cityCenterX, int cityCenterZ) {
        if (fixed.isEmpty()) return 0;
        int radius = Math.max(1, random.outerRadius()) * CHUNK;
        double score = 0;
        for (int ix = -2; ix <= 2; ix++) {
            for (int iz = -2; iz <= 2; iz++) {
                double x = centerX + ix * radius * .45;
                double z = centerZ + iz * radius * .45;
                if (fixed.stream().anyMatch(f -> contains(f, cityCenterX, cityCenterZ, x, z))) score++;
            }
        }
        return score;
    }

    private static List<Resolved> containing(List<Resolved> resolved, int cityCenterX,
                                              int cityCenterZ, double x, double z) {
        List<Resolved> result = new ArrayList<>();
        for (Resolved candidate : resolved) {
            if (contains(candidate, cityCenterX, cityCenterZ, x, z)) result.add(candidate);
        }
        return result;
    }

    private static boolean contains(Resolved resolved, int cityCenterX, int cityCenterZ,
                                    double x, double z) {
        StagePartitionConfig.Partition partition = resolved.partition();
        double dx = x - resolved.centerX();
        double dz = z - resolved.centerZ();
        double distance = Math.hypot(dx, dz);
        double inner = partition.innerRadius() * CHUNK;
        double outer = partition.outerRadius() * CHUNK;
        String shape = normalized(partition.shape());
        if (shape.equals("方形") || shape.equals("矩形")) {
            return Math.abs(dx) <= partition.width() * CHUNK / 2.0
                    && Math.abs(dz) <= partition.length() * CHUNK / 2.0;
        }
        if (distance < inner || distance > outer) return false;
        if (shape.equals("扇形")) {
            double angle = Math.toDegrees(Math.atan2(dx, dz));
            if (angle < 0) angle += 360;
            double start = partition.angleStart() % 360;
            if (start < 0) start += 360;
            double delta = (angle - start + 360) % 360;
            return delta <= partition.angleRange() || partition.angleRange() >= 360;
        }
        return true;
    }

    private static double distanceToShape(Resolved resolved, double x, double z) {
        if (contains(resolved, 0, 0, x, z)) return 0;
        StagePartitionConfig.Partition partition = resolved.partition();
        double dx = x - resolved.centerX();
        double dz = z - resolved.centerZ();
        if (normalized(partition.shape()).equals("方形") || normalized(partition.shape()).equals("矩形")) {
            double hx = partition.width() * CHUNK / 2.0;
            double hz = partition.length() * CHUNK / 2.0;
            return Math.hypot(Math.max(0, Math.abs(dx) - hx), Math.max(0, Math.abs(dz) - hz));
        }
        double inner = partition.innerRadius() * CHUNK;
        double outer = partition.outerRadius() * CHUNK;
        double radius = Math.hypot(dx, dz);
        if (!normalized(partition.shape()).equals("扇形")) {
            return radius < inner ? inner - radius : Math.max(0, radius - outer);
        }
        double angle = Math.toRadians(partition.angleStart());
        double end = angle + Math.toRadians(partition.angleRange());
        double best = Double.POSITIVE_INFINITY;
        for (int sample = 0; sample <= partition.angleRange(); sample += 5) {
            double a = angle + Math.toRadians(Math.min(sample, partition.angleRange()));
            best = Math.min(best, segmentDistance(dx, dz,
                    inner * Math.sin(a), inner * Math.cos(a), outer * Math.sin(a), outer * Math.cos(a)));
            if (sample < partition.angleRange()) {
                double next = angle + Math.toRadians(Math.min(sample + 5, partition.angleRange()));
                best = Math.min(best, segmentDistance(dx, dz,
                        outer * Math.sin(a), outer * Math.cos(a), outer * Math.sin(next), outer * Math.cos(next)));
            }
        }
        return Math.min(best, segmentDistance(dx, dz,
                inner * Math.sin(end), inner * Math.cos(end), outer * Math.sin(end), outer * Math.cos(end)));
    }

    private static double segmentDistance(double x, double z, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
                ((x - x1) * dx + (z - z1) * dz) / lengthSquared));
        return Math.hypot(x - (x1 + t * dx), z - (z1 + t * dz));
    }

    private static String choose(List<Resolved> candidates, long seed, int chunkX, int chunkZ) {
        int highest = candidates.stream().mapToInt(c -> c.partition().priority()).max().orElse(Integer.MIN_VALUE);
        List<Resolved> eligible = candidates.stream()
                .filter(c -> c.partition().priority() == highest)
                .sorted(Comparator.comparing(Resolved::name)).toList();
        if (eligible.size() == 1) return eligible.getFirst().name();
        long tie = RadialZonePlan.citySeed(seed ^ 0x51ed270bL, chunkX, chunkZ);
        return eligible.get((int) Math.floorMod(tie, eligible.size())).name();
    }

    private static boolean isRandom(String placement) {
        return normalized(placement).equals("随机") || normalized(placement).equals("随机摆放")
                || normalized(placement).equals("随机生成");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replace(" ", "").replace("　", "");
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }
}
