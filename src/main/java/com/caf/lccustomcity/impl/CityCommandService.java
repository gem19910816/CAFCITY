package com.caf.lccustomcity.impl;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏内命令 {@code /cafcity [距离上限]} 接游戏的那一半(纯计算在同包的 {@link CityLocator},
 * 命令注册在 {@code mixin/CityCommandMixin})。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 *
 * <h2>干什么</h2>
 * 玩家敲 {@code /cafcity}(权限等级 2, 服务端执行), 我们:
 * <ol>
 *   <li>拿到玩家所在维度 + 位置 + 失落城市的维度信息(下面"为什么用反射"一段);</li>
 *   <li>在玩家周围<b>扫城心</b>(遍历写法照抄失落城市 {@code City.getCityFactor} 第 338~356 行);
 *       结论按"记忆化格子"缓存, 同一个格子内反复按命令不会重扫;</li>
 *   <li>挑出上限之内最近的那座城, 交给 {@link CityLocator} 拼成三行中文;</li>
 *   <li>三行中文同时发给玩家(聊天栏)和写进日志(INFO), 方便玩家复制给作者看。</li>
 * </ol>
 *
 * <h2>城心怎么判定(为什么不会瞎报)</h2>
 * 合并出生点保证规则的合成城心与失落城市确定性查询 {@code City.isCityCenter}(同一个种子, 每个区块"是不是城心"是
 * 一个由区块坐标决定的伪随机判定, 见 LostCities {@code City.java} 第 180~203 行),
 * 半径用 {@code City.getCityRadius}(第 208~230 行), 并且只算和玩家所在区块<b>同一个配置档</b>的城心
 * (照抄原生写法: 球体世界里有"球内/球外"两个配置档)。全程不碰随机数发生器, 同一个世界
 * 同一个位置永远给同一个答案。窗口内一座城心都没有时, 我们明说"没有找到", 不会拿别的数字凑一个。
 *
 * <h2>搜索窗口多大</h2>
 * 以"记忆化格子"({@link CityLocator#CELL} = 512 格)的中心为圆心, 正方形扫
 * {@link CityLocator#scanWindowChunks(int)} 个区块 —— 它保证"离玩家不超过 N 格的城心"一定被扫到
 * (推导见那个方法的注释)。默认 N = 5000 ⇒ 窗口半径约 338 区块 ⇒ 约 46 万个候选区块,
 * 按失落城市 {@code getCityFactor} 的实测速度(约 22ns/候选区块)算大约 10 毫秒。
 *
 * <h2>为什么用反射拿 IDimensionInfo</h2>
 * 失落城市自己的命令是这么拿的: {@code Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level)}
 * (见 LostCities {@code commands/CommandStats.java} 第 32 行)。但 {@code Registration} 的字段类型
 * {@code net.neoforged.neoforge.registries.DeferredHolder} <b>只在 NeoForge 的 universal jar 里</b>,
 * 而 build.ps1 给 mixin 组(本包 + mixin 包都在这一组编译)的 classpath 里没有它 ——
 * 直接写 import 会编译失败(同 {@code PackFinderBridge} 对 {@code AddPackFindersEvent} 的处理)。
 * 所以这里用 {@code Class.forName} + 反射拿, 拿到之后一切照 IDimensionInfo 接口走(接口在失落城市
 * 的 jar 里, 编译期可见)。这条路径整个包在 try/catch 里: 反射失败只是"查不了", 不会崩。
 *
 * <h2>兜底</h2>
 * {@link #report(CommandSourceStack, String)} 与 {@link #describe} 全包在 try/catch 里:
 * 任何异常都变成聊天栏里的一句话 + 一行日志, <b>绝不把异常抛回命令系统</b>。
 */
public final class CityCommandService {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-CityCmd");

    /** 反射用的类名 / 字段名(见类注释"为什么用反射")。 */
    private static final String CLS_REGISTRATION = "mcjty.lostcities.setup.Registration";
    private static final String FIELD_FEATURE = "LOSTCITY_FEATURE";
    private static final String METHOD_GET = "get";
    private static final String METHOD_GET_DIMENSION_INFO = "getDimensionInfo";

    /** 结论表上限: 一个格子一条, 玩家到处传送也不会涨到没边。 */
    private static final int MAX_ENTRIES = 256;

    /** 记忆化键: 结论只和这些输入有关(种子 + 维度 + 格子 + 上限 + 配置档身份)。 */
    private record Key(Object world, long seed, ResourceKey<Level> dim, int cellX, int cellZ, int limit,
                       LostCityProfile profile, double chance, int minRadius, int maxRadius) {
    }

    /** 键 -> 窗口内扫到的城心, 每项 = {城心X, 城心Z, 名义半径}(方块)。 */
    private static final ConcurrentHashMap<Key, int[][]> CACHE = new ConcurrentHashMap<>();

    private CityCommandService() {
    }

    // ==================================================================
    // 给命令用的入口
    // ==================================================================

    /**
     * 执行一次 {@code /cafcity}: 算 + 回话 + 写日志。<b>本方法不抛异常</b>。
     *
     * @param source   命令来源(玩家 / 控制台 / 命令方块都可以)
     * @param limitRaw 距离上限的原文(null = 玩家没填, 用默认值)
     */
    public static void report(CommandSourceStack source, String limitRaw) {
        List<String> lines;
        try {
            Vec3 pos = source.getPosition();
            lines = describe(source.getLevel(), pos.x, pos.z, limitRaw);
        } catch (Throwable t) {
            CAF_LOGGER.warn("[CAF-LC] 查询最近的城市失败(已忽略): {}", t.toString());
            lines = List.of(CityLocator.PREFIX + "查询最近的城市失败(详见日志): " + t);
        }
        for (String line : lines) {
            try {
                source.sendSystemMessage(Component.literal(line));
            } catch (Throwable t) {
                // 连回话都发不出去(比如来源已经失效)就只能记日志
                CAF_LOGGER.warn("[CAF-LC] 回话失败(已忽略): {}", t.toString());
                break;
            }
        }
    }

    /**
     * 算出这次查询的所有输出行(同时写一份 INFO 日志)。
     *
     * @param level    玩家所在的服务端维度
     * @param playerX  玩家位置 X(可能带小数, 内部向下取整到方块)
     * @param playerZ  玩家位置 Z
     * @param limitRaw 距离上限原文(null = 没填)
     * @return 要逐行发给玩家的中文(每行都带 {@link CityLocator#PREFIX})
     */
    public static List<String> describe(ServerLevel level, double playerX, double playerZ, String limitRaw) {
        IDimensionInfo provider = providerOf(level);
        if (provider == null) {
            return logged(level, List.of(CityLocator.PREFIX + "这个维度没有失落城市生成器, 查不了最近的城市"));
        }
        LostCityProfile profile = provider.getProfile();
        if (profile == null) {
            return logged(level, List.of(CityLocator.PREFIX + "拿不到失落城市配置档, 查不了最近的城市"));
        }
        if (profile.CITY_CHANCE < 0.0) {
            // CITY_CHANCE < 0 = 用 Perlin 噪声决定城区, 根本没有"城心"这个概念, 扫了也是白扫
            return logged(level, List.of(CityLocator.PREFIX
                    + "这个配置档用 Perlin 噪声城区(CITY_CHANCE < 0), 没有城心可以查 —— 只能靠肉眼找城市"));
        }

        int limit = CityLocator.parseLimit(limitRaw);
        int px = (int) Math.floor(playerX);
        int pz = (int) Math.floor(playerZ);

        List<String> lines = new ArrayList<>();
        int[][] centers = centersFor(provider, profile, px, pz, limit);
        int[] nearestOverall = CityLocator.nearest(px, pz, centers);
        int[] hit = CityLocator.nearestWithin(px, pz, centers, limit);
        if (hit == null) {
            for (String line : CityLocator.notFoundLines(limit,
                    nearestOverall == null ? -1 : nearestOverall[3])) {
                lines.add(line);
            }
        } else {
            int edge = CityLocator.visibleEdgeDistance(hit[3], hit[2], profile.CITY_THRESHOLD);
            // 分区只在"人真的站在城里"时才有意义: 荒野区块的城市风格是随机的, 报了就是误导
            int zone = edge < 0
                    ? CityLocator.classifyZone(innerCityOrNull(provider, profile, px, pz))
                    : CityLocator.ZONE_UNKNOWN;
            for (String line : CityLocator.foundLines(px, pz, hit[0], hit[1], hit[2],
                    profile.CITY_MINRADIUS, profile.CITY_MAXRADIUS, profile.CITY_THRESHOLD,
                    limit, centers.length, zone)) {
                lines.add(line);
            }
        }
        String notice = CityLocator.limitNotice(limitRaw, limit);
        if (!notice.isEmpty()) {
            lines.add(CityLocator.PREFIX + notice);
        }
        return logged(level, lines);
    }

    /**
     * 把这次查询的输出写一份 INFO 日志(玩家让作者看问题时直接复制日志就行), 然后原样返回。
     *
     * <p>玩家自己也能在聊天栏看到同样的内容, 日志只是"留一份存档"。
     */
    private static List<String> logged(ServerLevel level, List<String> lines) {
        CAF_LOGGER.info("[CAF-LC] /cafcity: 维度 {} -> {}",
                level.dimension().location(), String.join(" | ", lines));
        return lines;
    }

    // ==================================================================
    // 维度信息(反射, 见类注释)
    // ==================================================================

    /**
     * 拿到这个维度对应的失落城市 {@link IDimensionInfo}; 拿不到(维度不支持 / 反射失败)返回 null。
     *
     * <p>等价于失落城市自己命令里的
     * {@code Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level)}, 只是把
     * "编译期拿不到的 DeferredHolder" 换成反射调用。
     */
    private static IDimensionInfo providerOf(ServerLevel level) {
        try {
            Class<?> registration = Class.forName(CLS_REGISTRATION);
            Object holder = registration.getField(FIELD_FEATURE).get(null);
            Object feature = holder.getClass().getMethod(METHOD_GET).invoke(holder);
            Object info = feature.getClass()
                    .getMethod(METHOD_GET_DIMENSION_INFO, WorldGenLevel.class)
                    .invoke(feature, level);
            return (IDimensionInfo) info;
        } catch (Throwable t) {
            CAF_LOGGER.warn("[CAF-LC] 拿失落城市维度信息失败(已忽略): {}", t.toString());
            return null;
        }
    }

    // ==================================================================
    // 扫城心(带记忆化)
    // ==================================================================

    /** Native and guaranteed spawn centers use the same coordinates as generation. */
    public static int[][] centersFor(IDimensionInfo provider, LostCityProfile profile,
                                     int playerX, int playerZ, int limit) {
        int[][] nativeCenters=centersOfCell(provider,profile,playerX,playerZ,limit);
        int[] synthetic=SpawnCityRule.centerFor(provider,profile);
        if(synthetic==null)return nativeCenters;
        int window=CityLocator.scanWindowChunks(limit);
        int cx=CityLocator.cellCenter(CityLocator.cellIndex(playerX))>>4;
        int cz=CityLocator.cellCenter(CityLocator.cellIndex(playerZ))>>4;
        if(Math.abs((long)(synthetic[0]>>4)-cx)>window
                ||Math.abs((long)(synthetic[1]>>4)-cz)>window)return nativeCenters;
        var merged=new ArrayList<int[]>(List.of(nativeCenters));
        if(merged.stream().noneMatch(c->c[0]==synthetic[0]&&c[1]==synthetic[1]))merged.add(synthetic);
        merged.sort(java.util.Comparator.<int[]>comparingInt(c->c[0]).thenComparingInt(c->c[1]));
        return merged.toArray(new int[0][]);
    }

    /**
     * 玩家所在"记忆化格子"里, 窗口内扫到的所有城心(同一格子只真扫一次)。
     *
     * <p>窗口以格子中心为圆心而不是玩家位置: 这样"格子内任意一点"的查询都能用同一份结果
     * (证明见 {@link CityLocator#scanWindowChunks(int)}), 玩家在格子里走来走去反复按命令不会重扫。
     */
    private static int[][] centersOfCell(IDimensionInfo provider, LostCityProfile profile,
                                         int playerX, int playerZ, int limit) {
        long seed = provider.getSeed();
        ResourceKey<Level> dim = provider.getType();
        int cellX = CityLocator.cellIndex(playerX);
        int cellZ = CityLocator.cellIndex(playerZ);
        Key key = new Key(provider.getWorld().getLevel(), seed, dim, cellX, cellZ, limit, profile,
                profile.CITY_CHANCE,profile.CITY_MINRADIUS,profile.CITY_MAXRADIUS);
        int[][] cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        int window = CityLocator.scanWindowChunks(limit);
        int centerChunkX = CityLocator.cellCenter(cellX) >> 4;
        int centerChunkZ = CityLocator.cellCenter(cellZ) >> 4;
        long start = System.nanoTime();
        int[][] found = scan(provider, profile, centerChunkX, centerChunkZ, window);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        if (CACHE.size() < MAX_ENTRIES) {
            CACHE.putIfAbsent(key, found);
        }
        long candidates = (2L * window + 1L) * (2L * window + 1L);
        CAF_LOGGER.info("[CAF-LC] /cafcity 扫描: 中心区块 ({}, {}), 半径 {} 区块 ({} 个候选区块), "
                        + "找到 {} 座城心, 耗时 {} ms", centerChunkX, centerChunkZ, window, candidates,
                found.length, millis);
        return found;
    }

    /**
     * 扫一个正方形窗口里的城心 —— 遍历写法照抄失落城市 {@code City.getCityFactor}
     * (LostCities {@code City.java} 第 338~356 行):
     * 逐区块问"你是不是城心", 是就取半径、算到目标的平方距离; 这里只是把
     * "距离小于半径才算数" 换成 "全部收集起来, 最后挑最近的那座"。
     *
     * <p>城心坐标按失落城市的算法取"城心区块的原点"({@code 区块坐标 * 16}, 同 {@code City.java} 第 347 行)。
     * 只算<b>同一个配置档</b>的城心(球体/太空世界里同一个维度有球内球外两个配置档)。
     *
     * @return 每项 = {城心X, 城心Z, 名义半径}(方块), 顺序固定(先 cx 后 cz 递增) ⇒ 结果确定
     */
    private static int[][] scan(IDimensionInfo provider, LostCityProfile profile,
                                int centerChunkX, int centerChunkZ, int window) {
        List<int[]> found = new ArrayList<>();
        ResourceKey<Level> dim = provider.getType();
        for (int cx = centerChunkX - window; cx <= centerChunkX + window; cx++) {
            for (int cz = centerChunkZ - window; cz <= centerChunkZ + window; cz++) {
                ChunkCoord coord = new ChunkCoord(dim, cx, cz);
                if (BuildingInfo.getProfile(coord, provider) != profile) {
                    continue;
                }
                if (!City.isCityCenter(coord, provider)) {
                    continue;
                }
                float radius = City.getCityRadius(coord, provider);
                if (!(radius > 0.0f)) {
                    continue;
                }
                found.add(new int[]{cx * SpawnCityPlan.CHUNK, cz * SpawnCityPlan.CHUNK, (int) radius});
            }
        }
        return found.toArray(new int[0][]);
    }

    /**
     * Player district uses the same radial plan as building generation.
     *
     * @return null = 判定失败(调用方按"未知"报, 不猜)
     */
    private static Boolean innerCityOrNull(IDimensionInfo provider, LostCityProfile profile,
                                           int playerX, int playerZ) {
        try {
            ChunkCoord coord = new ChunkCoord(provider.getType(), playerX >> 4, playerZ >> 4);
            var district=CityLayout.district(coord,provider,profile);
            if(district!=null)return !district.outer();
            var style=City.getCityStyle(coord,provider,profile);
            return style==null||!java.util.Objects.equals(profile.CITY_STYLE_ALTERNATIVE,style.getName());
        } catch (Throwable t) {
            CAF_LOGGER.warn("[CAF-LC] 内外城判定失败(分区按未知报): {}", t.toString());
            return null;
        }
    }
}
