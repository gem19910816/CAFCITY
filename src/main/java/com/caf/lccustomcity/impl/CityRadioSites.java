package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.ForceFlattenConfig;
import com.caf.lccustomcity.config.LayoutCatalog;
import com.caf.lccustomcity.config.SpawnCityConfig;
import com.caf.lccustomcity.config.StarterTownConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Radio facts only: no chunk requests, map markers, commands or player interactions. */
public final class CityRadioSites {
    public record Site(String id, String name, String kind, int tier, int x, int z, boolean city) {}
    private record Version(ServerLevel level, LostCityProfile profile, long seed, long catalog,
                           long stage, StarterTownConfig.Settings starter, boolean flatten,
                           int spawnDistance, int spawnX, int spawnZ, long epoch) {}
    private static final int MAX_WAITERS = 32;
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final class Session {
        final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CAF-city-radio-survey");
            thread.setDaemon(true);
            return thread;
        });
        final List<Consumer<List<Site>>> waiters = new ArrayList<>();
        volatile boolean active = true;
        Version cachedVersion;
        List<Site> cached = List.of();
        boolean running;
        void check() {
            if (!active || Thread.currentThread().isInterrupted())
                throw new CancellationException("Radio survey session closed");
        }
    }

    private CityRadioSites() {}

    /** Register once from the mod initializer, alongside the existing navigation lifecycle. */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> {
            Session session = SESSIONS.remove(event.getServer());
            if (session != null) {
                session.active = false;
                session.waiters.clear();
                session.cached = List.of();
                session.cachedVersion = null;
                session.executor.shutdownNow();
            }
        });
    }

    /** Server-thread snapshot of already completed building plans, never a cold lookup. */
    public static List<Site> cachedSites(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Radio sites require the server thread");
        if (!Level.OVERWORLD.equals(level.dimension()) || !CityStageConfig.current().enabled()) return List.of();
        IDimensionInfo provider = CityCommandService.providerOf(level);
        if (provider == null) return List.of();
        var catalog = LayoutCatalog.current();
        Map<String, Site> result = new LinkedHashMap<>();
        for (var entry : CityLayout.completedPlacements(provider)) {
            var placement = entry.placement();
            var building = placement.building();
            String source = building.sourceFile();
            if (source == null || source.isBlank()) continue;
            String name = catalog.displayName(building.id());
            if (name == null || name.isBlank()) {
                String normalized = source.replace('\\', '/');
                name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
            }
            String kind = kind(source + " " + name);
            int tier = tier(entry.stage());
            if (kind == null || tier == 0 || name.isBlank()) continue;
            String id = level.dimension().location() + "/building/" + entry.center().x() + "/"
                    + entry.center().z() + "/" + building.id() + "/r" + building.turns()
                    + "/" + placement.x() + "/" + placement.z();
            // A nearby footprint coordinate, not a promised entrance or standable Y.
            result.putIfAbsent(id, new Site(id, name, kind, tier,
                    placement.x() * 16 + building.width() * 8,
                    placement.z() * 16 + building.depth() * 8, false));
        }
        return result.values().stream().sorted(Comparator.comparing(Site::id)).toList();
    }

    /** All callbacks run on the server thread; cities are actual selected centers, not buildings. */
    public static void requestCities(ServerLevel level, Consumer<List<Site>> callback) {
        var server = level.getServer();
        if (!server.isSameThread()) {
            server.execute(() -> requestCities(level, callback));
            return;
        }
        if (!Level.OVERWORLD.equals(level.dimension()) || !CityStageConfig.current().enabled()) {
            callback.accept(List.of());
            return;
        }
        IDimensionInfo provider = CityCommandService.providerOf(level);
        if (provider == null) {
            callback.accept(List.of());
            return;
        }
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        Version version = version(level, provider);
        if (version.equals(session.cachedVersion)) {
            callback.accept(session.cached);
            return;
        }
        if (session.waiters.size() >= MAX_WAITERS) {
            callback.accept(List.of());
            return;
        }
        session.waiters.add(callback);
        if (session.running) return;
        session.running = true;
        IDimensionInfo snapshot = new SurveyDimensionInfo(provider, level, session::check);
        String dimensionId = level.dimension().location().toString();
        CompletableFuture.supplyAsync(() -> {
            session.check();
            List<Site> sites = new ArrayList<>();
            for (var center : CustomCityCenterPlanner.scanWorld(snapshot, version.profile())) {
                session.check();
                var stage = CityStageRuntime.acceptedStage(snapshot, version.profile(), center.x(), center.z());
                int tier = stage == null ? 0 : tier(stage.name());
                if (tier == 0) continue;
                String id = dimensionId + "/city/" + stage.name() + "/" + center.x() + "/" + center.z();
                sites.add(new Site(id, stage.name(), "city", tier, center.x(), center.z(), true));
            }
            session.check();
            return sites.stream().sorted(Comparator.comparing(Site::id)).toList();
        }, session.executor).whenComplete((sites, error) -> {
            if (!session.active || !server.isRunning() || server.isStopped()) return;
            server.execute(() -> {
                if (!session.active || SESSIONS.get(server) != session || !server.isRunning() || server.isStopped()) return;
                session.running = false;
                List<Consumer<List<Site>>> listeners = List.copyOf(session.waiters);
                session.waiters.clear();
                List<Site> accepted = List.of();
                if (error == null && level == server.getLevel(level.dimension()) && version.equals(version(level, provider))) {
                    accepted = sites;
                    session.cached = sites;
                    session.cachedVersion = version;
                } else if (error != null) {
                    LoggerFactory.getLogger("CAF-LC-Radio").warn("城市广播位置查询失败", error);
                }
                for (var listener : listeners) {
                    try { listener.accept(accepted); }
                    catch (RuntimeException failure) {
                        LoggerFactory.getLogger("CAF-LC-Radio").warn("城市广播接收方处理失败", failure);
                    }
                }
            });
        });
    }

    private static Version version(ServerLevel level, IDimensionInfo provider) {
        var spawn = level.getSharedSpawnPos();
        return new Version(level, provider.getProfile(), provider.getSeed(), LayoutCatalog.current().revision(),
                CityStageConfig.current().revision(), StarterTownConfig.current(), ForceFlattenConfig.enabled(),
                SpawnCityConfig.distance(), spawn.getX(), spawn.getZ(), CityLayout.radioEpoch());
    }

    private static int tier(String stage) {
        if (CityStageConfig.VILLAGE.equals(stage)) return 1;
        if (CityStageConfig.TOWN.equals(stage)) return 2;
        if (CityStageConfig.CITY.equals(stage)) return 3;
        if (CityStageConfig.METROPOLIS.equals(stage)) return 4;
        return 0;
    }

    private static String kind(String text) {
        text = text.toLowerCase(Locale.ROOT);
        if (contains(text, "医院", "诊所", "医疗", "hospital", "clinic")) return "medical";
        if (contains(text, "幸存者", "营地", "避难", "survivor", "shelter")) return "camp";
        if (contains(text, "军", "military", "barrack", "airbase")) return "military";
        if (contains(text, "警", "police")) return "police";
        if (contains(text, "工厂", "厂房", "车间", "修车", "汽修", "factory", "workshop", "garage")) return "industrial";
        if (contains(text, "民居", "住宅", "公寓", "别墅", "农舍", "residential", "apartment", "farmhouse")) return "residential";
        return null;
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
