package com.caf.lccustomcity;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.impl.CityLayout;
import com.caf.lccustomcity.impl.CityStageRuntime;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Bridges CAF character spawn requests to the configured Lost Cities stages. */
final class CharacterSpawnIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("CAF-LC-CharacterSpawn");
    private static final String EVENT_CLASS = "com.chaosz.tarkovstamina.character.CharacterSpawnRequestedEvent";

    private CharacterSpawnIntegration() {}

    static void register() {
        try {
            Class<?> neoForge = Class.forName("net.neoforged.neoforge.common.NeoForge");
            Object eventBus = neoForge.getField("EVENT_BUS").get(null);
            Class<?> eventType = Class.forName(EVENT_CLASS);
            eventBus.getClass().getMethod("addListener", Class.class, Consumer.class)
                    .invoke(eventBus, eventType, (Consumer<Event>) CharacterSpawnIntegration::onEvent);
        } catch (Throwable failure) {
            LOGGER.error("无法注册 CAF 角色出生事件监听器", failure);
        }
    }

    static void onEvent(Event event) {
        if (!event.getClass().getName().equals(EVENT_CLASS)) return;
        try {
            Object requestedType = event.getClass().getMethod("spawnType").invoke(event);
            String type = String.valueOf(requestedType);
            Object player = event.getClass().getMethod("player").invoke(event);
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
            // Character fallback spawns belong to the overworld, including deaths in other dimensions.
            BlockPos destination = findDestination(serverPlayer.getServer().overworld(), type);
            if (destination != null) {
                event.getClass().getMethod("setDestination", BlockPos.class).invoke(event, destination);
                LOGGER.info("角色出生选点 {} -> {}", type, destination);
            } else {
                LOGGER.warn("没有找到 {} 的安全出生位置，保留选角界面供玩家重试", type);
            }
        } catch (Throwable failure) {
            LOGGER.warn("无法为 CAF 角色选择出生位置", failure);
        }
    }

    private static BlockPos findDestination(ServerLevel level, String spawnType) throws Exception {
        if (!Level.OVERWORLD.equals(level.dimension())) return null;
        if ("WILDERNESS".equals(spawnType)) return findWildernessDestination(level);
        CityStageConfig config = CityStageConfig.current();
        if (!config.enabled()) return null;
        String stageName = switch (spawnType) {
            case "RURAL" -> CityStageConfig.VILLAGE;
            case "TOWN" -> CityStageConfig.TOWN;
            case "CITY" -> CityStageConfig.CITY;
            case "METROPOLIS" -> CityStageConfig.METROPOLIS;
            default -> null;
        };
        CityStageConfig.Stage targetStage = stageName == null ? null : config.stage(stageName);
        if (targetStage == null || !targetStage.enabled()) return null;

        IDimensionInfo provider = dimensionInfo(level);
        if (provider == null || provider.getProfile() == null) return null;
        LostCityProfile profile = provider.getProfile();
        BlockPos worldSpawn = level.getSharedSpawnPos();
        int minTileX = Math.floorDiv(worldSpawn.getX() - targetStage.maxDistance(), 1024) - 1;
        int maxTileX = Math.floorDiv(worldSpawn.getX() + targetStage.maxDistance(), 1024) + 1;
        int minTileZ = Math.floorDiv(worldSpawn.getZ() - targetStage.maxDistance(), 1024) - 1;
        int maxTileZ = Math.floorDiv(worldSpawn.getZ() + targetStage.maxDistance(), 1024) + 1;
        ChunkCoord origin = new ChunkCoord(provider.getType(), worldSpawn.getX() >> 4, worldSpawn.getZ() >> 4);
        List<CityLayout.Center> centers = new java.util.ArrayList<>();
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                centers.addAll(CityLayout.centersForTile(origin, provider, profile, tileX, tileZ));
            }
        }
        return centers.stream()
                .filter(center -> {
                    CityStageConfig.Stage stage = CityStageRuntime.acceptedStage(provider, profile, center.x(), center.z());
                    return stage != null && stage.name().equals(stageName);
                })
                .sorted(Comparator.comparingLong(center -> distanceSquared(worldSpawn, center.x(), center.z())))
                .map(center -> surfacePosition(level, center))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static BlockPos findWildernessDestination(ServerLevel level) {
        IDimensionInfo provider = dimensionInfo(level);
        if (provider == null || provider.getProfile() == null) return null;
        LostCityProfile profile = provider.getProfile();
        BlockPos spawn = level.getSharedSpawnPos();

        int generatedCandidates = 0;
        for (int radius = 256; radius <= 4096; radius += 128) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * (Math.PI / 8.0);
                int x = spawn.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * radius);
                if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))
                        || !outsideCities(provider, profile, x, z)) continue;
                // Geometry is checked before generating terrain, with a fixed work budget.
                if (++generatedCandidates > 48) return null;
                level.getChunk(x >> 4, z >> 4);
                BlockPos safe = safeSurfacePosition(level, x, z);
                if (safe != null) return safe;
            }
        }
        LOGGER.warn("出生点附近未找到安全野外位置，不回退到乡村出生点");
        return null;
    }

    private static boolean outsideCities(IDimensionInfo provider, LostCityProfile profile, int x, int z) {
        return outsideCityFootprints(x, z, (cx, cz) -> {
            ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
            return CityLayout.center(coord, provider, profile) != null || CityLayout.predefined(coord, provider);
        });
    }

    static boolean outsideCityFootprints(int x, int z, java.util.function.BiPredicate<Integer, Integer> occupied) {
        // CityLayout.center() only reports a city inside its footprint. Query every
        // chunk in the surrounding 64-block margin, including predefined buildings.
        for (int cx = Math.floorDiv(x - 64, 16); cx <= Math.floorDiv(x + 64, 16); cx++) {
            for (int cz = Math.floorDiv(z - 64, 16); cz <= Math.floorDiv(z + 64, 16); cz++) {
                if (occupied.test(cx, cz)) return false;
            }
        }
        return true;
    }

    private static BlockPos safeSurfacePosition(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isSolid()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.below()).isEmpty()
                && level.getFluidState(feet.above()).isEmpty()
                && !level.getBlockState(feet.below()).is(net.minecraft.tags.BlockTags.LEAVES)) {
            return feet;
        }
        return null;
    }

    private static BlockPos surfacePosition(ServerLevel level, CityLayout.Center center) {
        int radius = (int) Math.max(8, Math.min(center.radius() * 0.75, 96));
        long seed = level.getSeed() ^ ((long) center.x() << 32) ^ center.z();
        for (int attempt = 0; attempt < 24; attempt++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int x = center.x() + (int) Math.floorMod(seed >>> 16, radius * 2L + 1) - radius;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int z = center.z() + (int) Math.floorMod(seed >>> 16, radius * 2L + 1) - radius;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && level.getBlockState(feet.below()).isSolid()) return feet;
        }
        return null;
    }

    private static long distanceSquared(BlockPos origin, int x, int z) {
        long dx = (long) origin.getX() - x;
        long dz = (long) origin.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static IDimensionInfo dimensionInfo(ServerLevel level) {
        try {
            Class<?> registration = Class.forName("mcjty.lostcities.setup.Registration");
            Object holder = registration.getField("LOSTCITY_FEATURE").get(null);
            Object feature = holder.getClass().getMethod("get").invoke(holder);
            Method getter = feature.getClass().getMethod("getDimensionInfo", WorldGenLevel.class);
            return (IDimensionInfo) getter.invoke(feature, level);
        } catch (Throwable failure) {
            LOGGER.warn("读取失落城市维度信息失败: {}", failure.toString());
            return null;
        }
    }
}
