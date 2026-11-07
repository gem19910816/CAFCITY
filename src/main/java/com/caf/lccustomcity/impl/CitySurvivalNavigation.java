package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.CityStageConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Read-only planning lookup. No command permission changes, teleport or chunk loading. */
public final class CitySurvivalNavigation {
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        final AtomicBoolean inFlight = new AtomicBoolean();
        final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CAF-city-survey"); thread.setDaemon(true); return thread;
        });
        volatile boolean active = true;
        void check() {
            if (!active || Thread.currentThread().isInterrupted()) throw new CancellationException("City survey expired");
        }
    }
    private record Destination(int x, int z, String name) {}
    private CitySurvivalNavigation() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> {
            Session session = SESSIONS.remove(event.getServer());
            if (session != null) { session.active = false; session.executor.shutdownNow(); }
        });
    }
    public static void request(ServerPlayer player, ItemStack map, int tier) {
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) {
            player.sendSystemMessage(Component.literal("城市勘察图只在主世界使用。")); return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || player.getCooldowns().isOnCooldown(map.getItem())) return;
        IDimensionInfo provider = CityCommandService.providerOf(level);
        if (provider == null || !CityStageConfig.current().enabled()) {
            player.sendSystemMessage(Component.literal("这个世界没有启用CAF分级城市。")); return;
        }
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        if (!session.inFlight.compareAndSet(false, true)) {
            player.sendSystemMessage(Component.literal("正在整理城市路线，请稍后再查。")); return;
        }
        long revision = CityStageConfig.current().revision();
        int fromX = player.blockPosition().getX(), fromZ = player.blockPosition().getZ();
        UUID owner = player.getUUID();
        LostCityProfile profile = provider.getProfile();
        IDimensionInfo snapshot = new SurveyDimensionInfo(provider, level, session::check);
        ItemStack requestedCompass = player.getOffhandItem();
        String stage = stageName(tier);
        player.getCooldowns().addCooldown(map.getItem(), 200);
        player.sendSystemMessage(Component.literal("正在查阅" + CitySurveyMapItem.tierName(tier) + "的路线……"));
        // scanWorld is the existing synchronized, seed-based world plan. Its
        // terrain eligibility samples noise/base height and never calls getChunk.
        CompletableFuture.supplyAsync(() -> {
                    session.check();
                    Destination result = nearest(snapshot, profile, fromX, fromZ, stage);
                    session.check(); return result;
                }, session.executor)
                .whenComplete((destination, error) -> {
                    session.inFlight.set(false);
                    if (!session.active || !server.isRunning() || server.isStopped()) return;
                    server.execute(() -> {
                        if (!session.active || SESSIONS.get(server) != session || !server.isRunning() || server.isStopped()) return;
                        ServerPlayer current = server.getPlayerList().getPlayer(owner);
                        if (current == null || current.serverLevel() != level) return;
                        if (error != null) {
                            LoggerFactory.getLogger("CAF-LC-Navigation").warn("生存城市路线查询失败", error);
                            current.sendSystemMessage(Component.literal("这次路线查询失败，勘察图已保留，请稍后重试。")); return;
                        }
                        if (revision != CityStageConfig.current().revision()) {
                            current.sendSystemMessage(Component.literal("城市配置已更新，请重新查阅勘察图。")); return;
                        }
                        show(current, destination, tier, requestedCompass);
                    });
                });
    }
    private static String stageName(int tier) {
        return switch(tier) {case 2 -> CityStageConfig.TOWN;case 3 -> CityStageConfig.CITY;case 4 -> CityStageConfig.METROPOLIS;default -> CityStageConfig.VILLAGE;};
    }
    private static Destination nearest(IDimensionInfo provider, LostCityProfile profile, int x, int z, String requested) {
        return CustomCityCenterPlanner.scanWorld(provider, profile).stream()
                .filter(c -> {var s = CityStageRuntime.acceptedStage(provider, profile, c.x(), c.z());return s != null && s.name().equals(requested);})
                .min(Comparator.comparingLong((CityLayout.Center c) -> squareDistance(x,z,c.x(),c.z()))
                        .thenComparingInt(CityLayout.Center::x).thenComparingInt(CityLayout.Center::z))
                .map(c -> new Destination(c.x(), c.z(), requested)).orElse(null);
    }
    private static long squareDistance(int x, int z, int a, int b) {long dx=(long)a-x,dz=(long)b-z;return dx*dx+dz*dz;}
    private static void show(ServerPlayer player, Destination target, int tier, ItemStack requestedCompass) {
        if (target == null) {
            player.sendSystemMessage(Component.literal("当前城市计划没有找到" + CitySurveyMapItem.tierName(tier) + "。地形或城市配置可能未形成该级城市。")); return;
        }
        int x = player.blockPosition().getX(), z = player.blockPosition().getZ();
        int distance = CityLocator.distance(x,z,target.x(),target.z());
        String direction = distance <= 16 ? "已到城心附近" : CityLocator.directionText(target.x()-x,target.z()-z);
        player.sendSystemMessage(Component.literal(CitySurveyMapItem.tierName(tier) + "：" + direction + "，距城心约" + distance + "格。"));
        String coordinates = "X=" + target.x() + " Z=" + target.z();
        player.sendSystemMessage(Component.literal("城心坐标：").append(Component.literal(coordinates)
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,coordinates))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal("复制坐标，在地图上手动添加航点"))))));
        ItemStack offhand = player.getOffhandItem();
        if (offhand == requestedCompass && offhand.is(Items.COMPASS)) {
            offhand.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD,new BlockPos(target.x(),71,target.z()))),false));
            player.sendSystemMessage(Component.literal("副手指南针已指向这座城市；下次查询可重新标定。"));
        }
        player.sendSystemMessage(Component.literal("指向的是城心方向，先在外围侦察。地图不判断道路、水域和安全路线，高阶城市可以找到但危险更高。"));
    }
}
