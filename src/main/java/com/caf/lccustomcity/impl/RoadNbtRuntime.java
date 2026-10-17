package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.RoadConfig;
import com.caf.lccustomcity.schem.SchematicData;
import com.caf.lccustomcity.schem.SchematicReader;
import com.caf.lccustomcity.convert.DynamicNbt;
import com.caf.lccustomcity.nbt.NbtWriter;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Loads player-authored road structures from the selected style/stage/partition folder. */
public final class RoadNbtRuntime {
    private static volatile Path configRoot;
    private static final Map<Path, SchematicData> CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, List<Path>> FILES = new ConcurrentHashMap<>();
    private static final Set<Path> WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("CAF-LC-RoadNbt");
    private static final int WEST = 1, EAST = 2, NORTH = 4, SOUTH = 8;

    private RoadNbtRuntime() {}

    public static void install(Path root) {
        configRoot = root == null ? null : root.toAbsolutePath().normalize();
        CACHE.clear();
        FILES.clear();
        WARNED.clear();
        DIAGNOSTICS.clear();
    }

    /** Returns false if no matching authored road variant exists. */
    public static boolean place(ChunkDriver driver, ChunkCoord coord, IDimensionInfo provider,
                                LostCityProfile profile, int groundY) {
        Path root = configRoot;
        if (root == null || !CityStageConfig.current().enabled()) {
            diagnose("disabled", "道路 NBT 未尝试：阶段配置未启用或配置根目录为空");
            return false;
        }
        // Roads are stage-wide and do not require a partition or a custom plot cell.
        // Resolve only the nearest city center, then use that center's stage/style.
        CityLayout.Center center = CityLayout.center(coord, provider, profile);
        if (center == null) {
            diagnose("center", "道路 NBT 未尝试：区块 " + coord.chunkX() + "," + coord.chunkZ() + " 没有城市中心");
            return false;
        }
        CityStageConfig.Stage stage = CityStageRuntime.acceptedStage(provider, profile,
                center.x(), center.z());
        if (stage == null) {
            diagnose("stage", "道路 NBT 未尝试：区块所属阶段为空");
            return false;
        }
        String style = CityStageRuntime.selectedStyle(provider, stage,
                center.x(), center.z());
        if (style == null) {
            diagnose("style", "道路 NBT 未尝试：阶段 " + stage.name() + " 没有城市风格");
            return false;
        }
        // A single-partition stage (the current village layout) is unambiguous even
        // when the border/outer-ring classifier does not attach the partition name.
        // Use that only-partition fallback so those streets still resolve to authored NBT.
        RoadConfig road = stage.road() == null ? RoadConfig.defaults() : stage.road();
        int connections = connectionMask(coord, provider, profile);
        Shape shape = shape(connections);
        // Roads are stage-wide resources.  Partition selection only controls buildings;
        // every partition in a stage uses the same authored road set.
        Path folder = root.resolve(style).resolve(stage.name())
                .resolve("道路文件").resolve(shape.name).normalize();
        if (!folder.startsWith(root) || !Files.isDirectory(folder)) {
            diagnose("folder:" + folder, "道路 NBT 目录不存在：" + folder);
            return false;
        }
        List<Path> variants = files(folder);
        if (variants.isEmpty()) {
            diagnose("empty:" + folder, "道路 NBT 目录没有 .nbt：" + folder);
            return false;
        }
        int tileSize = road.width();
        int tileX = Math.floorDiv(coord.chunkX(), tileSize) * tileSize;
        int tileZ = Math.floorDiv(coord.chunkZ(), tileSize) * tileSize;
        long hash = mix(provider.getSeed() ^ ((long) tileX << 32) ^ tileZ);
        Path file = weightedVariant(variants, road.weights(), null, shape.name, hash);
        SchematicData data = load(file);
        if (data == null) return false;
        int turns = shape.turns;
        int rotatedX = (turns & 1) == 0 ? data.sizeX : data.sizeZ;
        int rotatedZ = (turns & 1) == 0 ? data.sizeZ : data.sizeX;
        if (rotatedX % 16 != 0 || rotatedZ % 16 != 0) {
            warn(file, "道路 NBT 的 X/Z 尺寸必须是 16 的倍数", null);
            return false;
        }
        int originX = Math.floorDiv(coord.chunkX(), rotatedX / 16) * (rotatedX / 16);
        int originZ = Math.floorDiv(coord.chunkZ(), rotatedZ / 16) * (rotatedZ / 16);
        boolean placed = placeStructure(driver, coord, provider, file, data, originX, originZ, turns, groundY, 0, 0);
        if (placed) diagnose("placed:" + folder, "道路 NBT 已生效：" + stage.name() + "/" + style + "/"
                + shape.name + " -> " + file.getFileName());
        return placed;
    }

    private static void diagnose(String key, String message) {
        if (DIAGNOSTICS.add(key)) LOG.info("[CAF-LC-RoadNbt] " + message);
    }

    private static Path weightedVariant(List<Path> variants, Map<String, Double> weights,
                                        String partition, String shape, long hash) {
        if (weights == null || weights.isEmpty()) {
            return variants.get((int) Long.remainderUnsigned(hash, variants.size()));
        }
        double total = 0;
        double[] values = new double[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            String file = variants.get(i).getFileName().toString();
            double weight = partition == null
                    ? weight(weights, shape + "/" + file, file)
                    : weight(weights, partition + "/" + shape + "/" + file,
                    shape + "/" + file, file);
            values[i] = weight;
            total += weight;
        }
        if (!(total > 0)) return variants.get((int) Long.remainderUnsigned(hash, variants.size()));
        double pick = (Long.remainderUnsigned(hash, 1_000_000L) / 1_000_000.0) * total;
        for (int i = 0; i < values.length; i++) {
            pick -= values[i];
            if (pick < 0) return variants.get(i);
        }
        return variants.get(variants.size() - 1);
    }

    private static double weight(Map<String, Double> weights, String... keys) {
        for (String key : keys) {
            Double value = weights.get(key);
            if (value != null) return Math.max(0, value);
        }
        return 1.0;
    }

    private static boolean placeStructure(ChunkDriver driver, ChunkCoord coord, IDimensionInfo provider,
                                          Path file, SchematicData data,
                                          int originChunkX, int originChunkZ, int turns, int groundY,
                                          int offsetX, int offsetZ) {
        int rotatedX = (turns & 1) == 0 ? data.sizeX : data.sizeZ;
        int rotatedZ = (turns & 1) == 0 ? data.sizeZ : data.sizeX;
        int chunksX = (rotatedX + 15) / 16, chunksZ = (rotatedZ + 15) / 16;
        int chunkX = coord.chunkX() - originChunkX, chunkZ = coord.chunkZ() - originChunkZ;
        if (chunkX < 0 || chunkZ < 0 || chunkX >= chunksX || chunkZ >= chunksZ) return false;
        Rotation rotation = rotation(turns);
        for (int y = 0; y < data.sizeY; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int rx = chunkX * 16 + x - offsetX, rz = chunkZ * 16 + z - offsetZ;
            int sx, sz;
            switch (turns) {
                case 1 -> { sx = rz; sz = data.sizeZ - 1 - rx; }
                case 2 -> { sx = data.sizeX - 1 - rx; sz = data.sizeZ - 1 - rz; }
                case 3 -> { sx = data.sizeX - 1 - rz; sz = rx; }
                default -> { sx = rx; sz = rz; }
            }
            String stateId = data.blockAt(sx, y, sz);
            if (SchematicData.isAirState(stateId)) continue;
            BlockState state = parseState(stateId);
            if (state == null) continue;
            int worldX = originChunkX * 16 + rx + offsetX;
            int worldZ = originChunkZ * 16 + rz + offsetZ;
            int worldY = groundY + y;
            driver.current(x, worldY, z).block(state.rotate(rotation));
            Map<String, Object> blockEntity = DynamicNbt.paletteTag(data, sx, y, sz);
            if (blockEntity != null) {
                try {
                    BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                    CompoundTag restored;
                    if (blockEntity.containsKey(DynamicNbt.BE_KEY)) {
                        CompoundTag carrier = new CompoundTag();
                        carrier.putString(DynamicNbt.BE_KEY, String.valueOf(blockEntity.get(DynamicNbt.BE_KEY)));
                        carrier.putBoolean("caf_create_nbt_local_v1", blockEntity.containsKey("caf_create_nbt_local_v1"));
                        restored = DynamicStructurePlacer.restoreBlockEntity(carrier, pos);
                    } else {
                        restored = DynamicStructurePlacer.decode(NbtWriter.encode(blockEntity));
                        restored.putInt("x", worldX);
                        restored.putInt("y", worldY);
                        restored.putInt("z", worldZ);
                    }
                    provider.getWorld().getChunk(pos).setBlockEntityNbt(restored);
                } catch (IOException | RuntimeException e) {
                    warn(file, "道路 NBT 方块实体放置失败", e);
                }
            }
        }
        return true;
    }

    private static List<Path> files(Path folder) {
        List<Path> found = FILES.computeIfAbsent(folder.toAbsolutePath().normalize(), path -> {
            if (!Files.isDirectory(path)) return List.of();
            try (var stream = Files.list(path)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".nbt"))
                        .sorted().toList();
            } catch (IOException e) {
                warn(path, "读取道路 NBT 目录失败", e);
                return List.of();
            }
        });
        if (found.isEmpty()) return found;
        return found;
    }

    private static int connectionMask(ChunkCoord c, IDimensionInfo p, LostCityProfile profile) {
        int mask = 0;
        if (vacant(c, p, profile, -1, 0)) mask |= WEST;
        if (vacant(c, p, profile, 1, 0)) mask |= EAST;
        if (vacant(c, p, profile, 0, -1)) mask |= NORTH;
        if (vacant(c, p, profile, 0, 1)) mask |= SOUTH;
        return mask;
    }

    private static boolean vacant(ChunkCoord c, IDimensionInfo p, LostCityProfile profile, int dx, int dz) {
        ChunkCoord n = new ChunkCoord(c.dimension(), c.chunkX() + dx, c.chunkZ() + dz);
        return CityLayout.enabledFor(n, p, profile) && CityLayout.cell(n, p, profile) != null
                && CityLayout.cell(n, p, profile).placement() == null
                && !ChaosRoadRuntime.protectedSurface(n, p, profile);
    }

    private record Shape(String name, int turns) {}
    private static Shape shape(int mask) {
        for (int turns = 0; turns < 4; turns++) {
            int rotated = rotateMask(mask, -turns);
            if (rotated == (WEST | EAST)) return new Shape("直道", turns);
            if (rotated == (WEST | NORTH)) return new Shape("弯道", turns);
            if (rotated == WEST) return new Shape("尽头", turns);
            if (rotated == (WEST | EAST | NORTH)) return new Shape("三岔路口", turns);
            if (rotated == 15) return new Shape("十字路口", turns);
        }
        if (mask == 0) return new Shape("直道", 0);
        return new Shape("尽头", 0);
    }

    private static int rotateMask(int mask, int turns) {
        int result = mask;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            result = ((result & WEST) != 0 ? NORTH : 0) | ((result & EAST) != 0 ? SOUTH : 0)
                    | ((result & NORTH) != 0 ? EAST : 0) | ((result & SOUTH) != 0 ? WEST : 0);
        }
        return result;
    }

    private static SchematicData load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(key, p -> {
            try { return SchematicReader.read(p); }
            catch (IOException | RuntimeException e) {
                warn(p, "道路 NBT 读取失败", e);
                return null;
            }
        });
    }

    private static BlockState parseState(String text) {
        try {
            int bracket = text.indexOf('[');
            String id = bracket < 0 ? text : text.substring(0, bracket);
            var location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) return null;
            BlockState state = BuiltInRegistries.BLOCK.get(location).defaultBlockState();
            if (bracket < 0) return state;
            String properties = text.substring(bracket + 1, text.length() - 1);
            for (String property : properties.split(",")) {
                int equals = property.indexOf('=');
                if (equals < 1) continue;
                Property<?> key = state.getBlock().getStateDefinition().getProperty(property.substring(0, equals));
                if (key != null) state = apply(state, key, property.substring(equals + 1));
            }
            return state;
        } catch (RuntimeException e) { return null; }
    }

    private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static Rotation rotation(int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void warn(Path path, String message, Throwable error) {
        if (WARNED.add(path)) {
            if (error == null) LOG.warn("[CAF-LC] {}: {}", message, path);
            else LOG.warn("[CAF-LC] {}: {}", message, path, error);
        }
    }
}
