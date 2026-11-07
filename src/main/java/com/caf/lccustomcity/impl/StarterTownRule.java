package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.ForceFlattenConfig;
import com.caf.lccustomcity.config.SpawnCityConfig;
import com.caf.lccustomcity.config.StarterTownConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Independent of the world allow-list: stage queries must never recurse into scanWorld. */
public final class StarterTownRule {
    public record Reservation(CityLayout.Center village, CityLayout.Center town, boolean earthworks,
                              List<CityLayout.Center> villages) {}
    private record Key(Object level, LostCityProfile profile, long seed, long revision,
                       int spawnX, int spawnZ, int spawnDistance, float threshold,
                       StarterTownConfig.Settings settings, boolean flatten) {}
    private record Result(Reservation reservation) {}
    private static final Map<Key, Result> CACHE = new LinkedHashMap<>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Result> entry) { return size() > 8; }
    };
    private StarterTownRule() {}
    public static void clear() { synchronized (CACHE) { CACHE.clear(); } }

    public static Reservation reservation(IDimensionInfo provider, LostCityProfile profile) {
        var config = CityStageConfig.current();
        var settings = StarterTownConfig.current();
        if (!settings.enabled() || !config.enabled() || provider == null || profile == null
                || profile != provider.getProfile() || provider.getWorld() == null
                || !Level.OVERWORLD.equals(provider.getType()) || SpawnCityConfig.distance() <= 0) return null;
        var village = config.stage(CityStageConfig.VILLAGE);
        var town = config.stage(CityStageConfig.TOWN);
        if (village == null || !village.enabled() || town == null || !town.enabled()
                || CityStageRuntime.worldQuota(provider, village.name()) <= 0
                || CityStageRuntime.worldQuota(provider, town.name()) <= 0) return null;
        BlockPos spawn = provider.getWorld().getLevel().getSharedSpawnPos();
        if (spawn == null) return null;
        Key key = new Key(provider.getWorld().getLevel(), profile, provider.getSeed(), config.revision(),
                spawn.getX(), spawn.getZ(), SpawnCityConfig.distance(), profile.CITY_THRESHOLD,
                settings, ForceFlattenConfig.enabled());
        synchronized (CACHE) {
            Result hit = CACHE.get(key);
            if (hit != null) return hit.reservation();
            Reservation value = compute(provider, profile, spawn, settings, town);
            CACHE.put(key, new Result(value));
            return value;
        }
    }

    private static Reservation compute(IDimensionInfo provider, LostCityProfile profile, BlockPos spawn,
                                       StarterTownConfig.Settings settings, CityStageConfig.Stage town) {
        // The anchor uses BASE stage queries; it cannot ask this rule for a stage.
        CityLayout.Center anchor = CustomCityCenterPlanner.startingVillage(provider, profile, spawn);
        if (anchor == null) return null;
        List<CityLayout.Center> villages = CustomCityCenterPlanner.baseVillagePlan(provider, profile, spawn, anchor);
        var points = StarterTownPlan.candidates(provider.getSeed(), anchor.x(), anchor.z(),
                settings.minDistance(), settings.maxDistance());
        var selected = StarterTownPlan.select(points,
                p -> CityStageRuntime.stageRuleAllows(provider, town, p.x(), p.z())
                        && villages.stream().allMatch(v -> StarterTownPlan.separated(v.x(), v.z(), v.radius(),
                        p.x(), p.z(), radius(provider, profile, town, p.x(), p.z()))),
                p -> CityStageRuntime.stageTerrainAllows(provider, town, p.x(), p.z()),
                ForceFlattenConfig.enabled());
        if (selected == null) {
            LoggerFactory.getLogger("CAF-LC-StarterTown").warn(
                    "[CAF-LC] 近邻小镇未能选址: 农村({},{}), 城心范围{}–{}格; 检查小镇启用/概率/尺寸和削平设置，不扩大距离",
                    anchor.x(), anchor.z(), settings.minDistance(), settings.maxDistance());
            return null;
        }
        var p = selected.point();
        var center = new CityLayout.Center(p.x(), p.z(), radius(provider, profile, town, p.x(), p.z()));
        LoggerFactory.getLogger("CAF-LC-StarterTown").info(
                "[CAF-LC] 预留近邻小镇({},{}): 距起步农村({}, {})约{}格, 计入小镇总配额, 地形处理={}",
                p.x(), p.z(), anchor.x(), anchor.z(),
                Math.round(Math.hypot((double)p.x() - anchor.x(), (double)p.z() - anchor.z())),
                selected.earthworks() ? "按已启用的城市削平设置兜底" : "通过现有群系和高度检查");
        return new Reservation(anchor, center, selected.earthworks(), villages);
    }

    private static double radius(IDimensionInfo provider, LostCityProfile profile,
                                 CityStageConfig.Stage stage, int x, int z) {
        String style = CityStageRuntime.selectedStyle(provider, stage, x, z);
        return CityStageRuntime.radiusBlocks(provider, stage, style, profile.CITY_MAXRADIUS, x, z)
                * (1 - profile.CITY_THRESHOLD);
    }

    public static boolean isTown(IDimensionInfo provider, LostCityProfile profile, int x, int z) {
        Reservation r = reservation(provider, profile);
        return r != null && same(r.town(), x, z);
    }

    public static boolean isStarterPair(IDimensionInfo provider, CityLayout.Center a, CityLayout.Center b) {
        Reservation r = reservation(provider, provider.getProfile());
        return r != null && (same(r.town(), a.x(), a.z()) && r.villages().stream().anyMatch(v -> same(v, b.x(), b.z()))
                || same(r.town(), b.x(), b.z()) && r.villages().stream().anyMatch(v -> same(v, a.x(), a.z())));
    }

    private static boolean same(CityLayout.Center c, int x, int z) { return c.x() == x && c.z() == z; }
}
