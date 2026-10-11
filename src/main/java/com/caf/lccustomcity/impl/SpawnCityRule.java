package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.SpawnCityConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「出生点附近城市」接游戏的那一半(供 {@code SpawnCityMixin} 调用)。
 * 纯几何在同包的 {@link SpawnCityPlan}。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 *
 * <h2>干什么</h2>
 * 配置见 {@code config/失落城市建筑/出生点附近城市.json}(键: 出生点城市中心距离, 默认 850)。
 * 对主世界来说: 先看出生点 N 格以内<b>本来</b>有没有一座城的<b>城心</b>
 * —— 有就什么都不做(绝不动既有的城市分布); 没有就按 {@link SpawnCityPlan#planGeometry}
 * 定一座"合成城市", 让它的<b>城心</b>落在距出生点约 N 格处(判定与摆放同一个口径),
 * 之后凡是落在它里面的区块, 由 {@code SpawnCityMixin} 把城市浓度从 0 提到
 * {@link SpawnCityPlan#factorAt}(宽度上就是一座真城: 建筑、马路、地形削平全部照失落城市
 * 自己的流程走, 因为它们全都只看城市浓度)。
 *
 * <p><b>口径是城心距离, 不是边缘距离</b>(2026-10 玩家要求: "将之前的 200 格看到城市边缘
 * 改为在出生点半径 850 格刷新一个城心"): 判定只比"城心到出生点"的距离, <b>与半径无关</b>,
 * 也不再乘 {@code (1 - CITY_THRESHOLD)} 折算可见半径。旧口径(可见边缘 ≤ 200)会把城心摆到
 * {@code 可见半径 + 200} 处 —— 半径 1000 的城就是 1200 格外, 出生点两百格内还是荒野。
 *
 * <p>半径和可见边缘仍然有用, 但只用在<b>给玩家报参考值的日志</b>里: 名义半径 800、
 * 城市阈值 0.2 ⇒ 可见城区半径 640 ⇒ 城心在 850 处时, 看得见的城区边缘大约在 210 格外。
 *
 * <h2>出生点从哪来</h2>
 * {@code IDimensionInfo.getWorld()} 拿到 {@code WorldGenLevel}, 它的
 * {@code getLevel()} 是 {@code ServerLevel}, 再调 {@code getSharedSpawnPos()} ——
 * 这是服务端 level.dat 里那个世界出生点(失落城市自己的
 * {@code ForgeEventHandlers#onCreateSpawnPoint} 也是这么读 / 写它的)。
 *
 * <h2>确定性</h2>
 * 全程只用确定性输入: 世界种子(选方向)、出生点坐标(level.dat 里存的, 一进世界就固定)、
 * 配置档的半径与阈值。不用随机数、不看时间。结论按
 * (种子, 维度, 出生点, 距离, 半径, 扫描窗口) 记忆化, 同一世界每次都一样。
 *
 * <h2>绝不影响既有城市</h2>
 * <ol>
 *   <li>判定"已满足"时<b>只读</b>失落城市自己的 {@code City.isCityCenter} /
 *       {@code City.getCityRadius}, 不写任何东西;</li>
 *   <li>补出来的城只在原生浓度为 0 的区块上叠加, 见
 *       {@link SpawnCityPlan#apply(float, float)};</li>
 *   <li>本类不做任何全局状态修改, 只在自己的一张表里缓存结论。</li>
 * </ol>
 *
 * <h2>兜底</h2>
 * 每个入口都 try/catch: 任何异常都退化成"这个功能不生效"(返回 0 = 不改浓度), 记一条警告,
 * <b>绝不把异常抛回区块生成</b>。
 */
public final class SpawnCityRule {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-SpawnCityRule");

    /** 结论表上限: 正常一个世界一条; 超了就不再记(纯函数, 重算最多几十毫秒, 且只发生一次)。 */
    private static final int MAX_ENTRIES = 64;

    /**
     * 一座"合成城市"的结论。
     *
     * @param needed   false = 出生点 N 格以内本来就有城的<b>城心</b>(城心距离口径), 什么都不做
     * @param centerX  城心 X(方块, 16 的整数倍)
     * @param centerZ  城心 Z(方块, 16 的整数倍)
     * @param radius   名义半径(方块)
     */
    private record Result(boolean needed, int centerX, int centerZ, int radius) {
    }

    /** 记忆化键: 结论只和这些输入有关。 */
    private record Key(Object world, LostCityProfile profile, double chance, long seed, ResourceKey<Level> dim, int spawnX, int spawnZ,
                       int centerDistance, int radius, int windowChunks) {
    }

    /** 键 -> 结论。 */
    private static final ConcurrentHashMap<Key, Result> RESULTS = new ConcurrentHashMap<>();

    // ---- 热路径单槽缓存: getCityFactor 每次调用都会走 factorFor, 不能每次都查表 ----
    private record Cached(Key key, Result result) {}
    private static volatile Cached cafLast = null;

    /** 异常只提醒一次(热路径上不能刷屏)。 */
    private static boolean cafLoggedFail = false;

    private SpawnCityRule() {
    }

    public static void clear() {
        RESULTS.clear();
        cafLast = null;
    }

    /**
     * 这个区块从"合成城市"里能拿到多少城市浓度。
     *
     * @param coord    目标区块
     * @param provider 维度信息
     * @param profile  目标区块生效的配置档(必须是主配置档, 见下)
     * @return 0.0 = 什么都不加(功能关闭 / 不需要补 / 不在合成城市范围内 / 出了任何错)
     */
    public static float factorFor(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        try {
            int centerDistance = SpawnCityConfig.distance();
            if (centerDistance <= 0) {
                return 0.0f;        // 关掉 = 完全恢复原样, 连出生点都不读
            }
            if (coord == null || provider == null || profile == null) {
                return 0.0f;
            }
            // 出生点是"世界出生点", 只有主世界那一个, 其它维度不参与
            if (!Level.OVERWORLD.equals(provider.getType())) {
                return 0.0f;
            }
            LostCityProfile main = provider.getProfile();
            if (main == null || profile != main) {
                return 0.0f;        // 球体世界的外圈配置档不参与(半径/阈值口径会和主档打架)
            }
            Result r = result(provider, main, centerDistance);
            if (r == null || !r.needed()) {
                return 0.0f;
            }
            return SpawnCityPlan.factorAt(r.centerX(), r.centerZ(), r.radius(), coord.chunkX(), coord.chunkZ());
        } catch (Throwable t) {
            logFailOnce(t);
            return 0.0f;
        }
    }

    /**
     * 取当前世界的结论(记忆化)。返回 null = 这次拿不到出生点/半径, 不做任何事。
     */
    /** Same synthetic circle used by factorFor, also needed by radial zoning. */
    public static int[] centerFor(IDimensionInfo provider, LostCityProfile profile) {
        if (provider == null || profile == null || profile != provider.getProfile()
                || !Level.OVERWORLD.equals(provider.getType()) || SpawnCityConfig.distance() <= 0) return null;
        Result r = result(provider, profile, SpawnCityConfig.distance());
        return r == null || !r.needed() ? null : new int[]{r.centerX(), r.centerZ(), r.radius()};
    }

    private static Result result(IDimensionInfo provider, LostCityProfile profile, int centerDistance) {
        WorldGenLevel world = provider.getWorld();
        if (world == null) {
            return null;            // GUI 预览等场合没有世界
        }
        BlockPos spawn = world.getLevel().getSharedSpawnPos();
        if (spawn == null) {
            return null;
        }
        int spawnX = spawn.getX();
        int spawnZ = spawn.getZ();

        int radius = SpawnCityPlan.cityRadius(profile.CITY_MINRADIUS, profile.CITY_MAXRADIUS);
        long seed = provider.getSeed();
        ResourceKey<Level> dim = provider.getType();

        // 单槽缓存: 一个世界正常只有一条结论, 这里挡掉绝大多数查表
        int window = scanWindow(centerDistance);
        Key key = new Key(world.getLevel(), profile, profile.CITY_CHANCE, seed, dim, spawnX, spawnZ, centerDistance, radius, window);
        Cached cached = cafLast;
        if (cached != null && cached.key().equals(key)) return cached.result();
        Result hit = RESULTS.get(key);
        if (hit == null) {
            // 只在真的要算一次结论时才扫既有城心(每个世界一次); 结果顺手留给日志用
            int[][] cities = nearbyCities(provider, profile, spawnX, spawnZ, window);
            hit = compute(provider, spawnX, spawnZ, radius, centerDistance, cities);
            if (RESULTS.size() < MAX_ENTRIES) {
                RESULTS.putIfAbsent(key, hit);
            }
            logPlan(spawnX, spawnZ, hit, radius, profile, cities);
        }
        cafLast = new Cached(key, hit);
        return hit;
    }

    /**
     * 扫描窗口(区块): 判定口径是"<b>城心</b>距出生点不超过 N 格", <b>和城市半径完全无关</b>,
     * 所以窗口只需要覆盖"城心离出生点不超过 N"的区块 —— 不用再加半径(旧口径要加可见半径,
     * 窗口大得多, 是白扫)。
     *
     * <p>半径 = {@code ceil(N / 16)} 个区块。
     *
     * <p><b>覆盖性证明</b>: 城心坐标取的是"城心区块的原点"({@code 区块 * 16})。
     * 设出生点 {@code sx = scx * 16 + r}({@code 0 <= r <= 15}), 城心区块 {@code ccx}。
     * 若这座城的城心被判成"已满足", 则
     * <pre>|ccx * 16 - sx| <= N
     * ⇒ |16 * (ccx - scx) - r| <= N
     * ⇒ 16 * |ccx - scx| <= N + r <= N + 15
     * ⇒ |ccx - scx| <= ceil(N / 16)</pre>
     * 对 Z 轴同理(出生点在 Z 上的偏移各算各的, 两个方向都 <= ceil(N/16))。
     * 窗口正好是 {@code spawnChunk ± ceil(N/16)} ⇒ 任何"已满足"的城心一定被扫到,
     * 不会漏(漏掉会在"已经满足"的情况下多补一座城)。✓
     */
    private static int scanWindow(int centerDistance) {
        if (centerDistance <= 0) {
            return 0;
        }
        return (centerDistance + SpawnCityPlan.CHUNK - 1) / SpawnCityPlan.CHUNK;
    }

    /**
     * 真正算一次结论: 先看附近既有的城的<b>城心</b>是不是已经在 N 格内
     * (只比城心距离, 与半径无关, 见 {@link SpawnCityPlan#satisfied});
     * 不满足就按同一个口径定一座合成城市 —— 摆放用的也是城心距离, 两边完全一致。
     */
    private static Result compute(IDimensionInfo provider,
                                  int spawnX, int spawnZ, int radius, int centerDistance, int[][] cities) {
        if (SpawnCityPlan.satisfied(spawnX, spawnZ, centerDistance, cities)) {
            return new Result(false, 0, 0, radius);
        }
        int[] plan = SpawnCityPlan.planGeometry(provider.getSeed(), spawnX, spawnZ, radius, centerDistance);
        return new Result(true, plan[0], plan[1], plan[2]);
    }

    /**
     * 出生点周围 {@code ceil(N/16)} 个区块内的所有城心(照抄 {@code City.getCityFactor} 的遍历:
     * 只算同一个配置档的城心)。城心坐标按失落城市的算法取"城心区块的原点"({@code 区块 * 16})。
     *
     * <p><b>第三个数是名义半径</b>({@code City.getCityRadius} 的原值): 现在判定只比城心距离,
     * 半径<b>不参与比较</b>, 收进来只是为了日志里能写"补一座半径 R 的城"; 所以这里也不再折算
     * 可见半径(旧口径才需要 {@code 半径 * (1 - 阈值)})。
     *
     * <p>只读失落城市的确定性查询, 不改任何东西; 城心很少, 所以返回的数组基本是空的。
     */
    private static int[][] nearbyCities(IDimensionInfo provider, LostCityProfile profile,
                                        int spawnX, int spawnZ, int window) {
        List<int[]> found = new ArrayList<>();
        int spawnChunkX = spawnX >> 4;
        int spawnChunkZ = spawnZ >> 4;
        for (int cx = spawnChunkX - window; cx <= spawnChunkX + window; cx++) {
            for (int cz = spawnChunkZ - window; cz <= spawnChunkZ + window; cz++) {
                ChunkCoord c = new ChunkCoord(provider.getType(), cx, cz);
                // 只算同一个配置档的城心(照抄原生写法: 球体世界里有"球内/球外"两个配置档)
                if (BuildingInfo.getProfile(c, provider) != profile) {
                    continue;
                }
                if (!City.isCityCenter(c, provider)) {
                    continue;
                }
                float r = City.getCityRadius(c, provider);
                if (!(r > 0.0f)) {
                    continue;
                }
                // 【城心距离口径】这里收的是<b>名义半径</b>(City.getCityRadius 原值):
                // 判定只比"城心到出生点"的距离, 半径不参与比较 —— 旧口径才需要
                // visibleRadius(阈值, r) 折算成可见半径。
                found.add(new int[]{cx * SpawnCityPlan.CHUNK, cz * SpawnCityPlan.CHUNK, (int) r});
            }
        }
        return found.toArray(new int[0][]);
    }

    /**
     * 算出一条新结论时写一行日志(每个世界最多几条, 玩家能照着它去验证)。
     *
     * <p>口径是<b>城心距离</b>:
     * <ul>
     *   <li>"本来就有城" ⇒ 报最近那座城的<b>城心</b>距出生点多少格(要求 &lt;= N);</li>
     *   <li>"补了一座城" ⇒ 报补出来那座城的<b>城心</b>距出生点多少格, 另附一个参考值
     *       ——它看得见的城区边缘大约在多少格外(城心距离 - 可见半径,
     *       可见半径 = 名义半径 * (1 - 城市阈值), 本模组 = 800 * 0.8 = 640)。</li>
     * </ul>
     * <p><b>不再</b>报"走到 N 格就能看到城市边缘"那种话 —— 旧口径的数学保证已经不成立。
     */
    private static void logPlan(int spawnX, int spawnZ, Result r, int radius, LostCityProfile profile,
                                int[][] cities) {
        if (!r.needed()) {
            CAF_LOGGER.info("[CAF-LC] 出生点附近城市: 出生点({}, {}) 附近本来就有城 —— "
                            + "最近的城心距出生点约 {} 格(要求 <= {} 格), 不做任何改动",
                    spawnX, spawnZ, nearestCenterDistance(spawnX, spawnZ, cities), SpawnCityConfig.distance());
            return;
        }
        int distance = SpawnCityPlan.centerDistance(spawnX, spawnZ, r.centerX(), r.centerZ());
        int visible = SpawnCityPlan.visibleRadius(profile.CITY_THRESHOLD, radius);
        // 看得见的城区边缘的参考距离 = 城心距离 - 可见半径(出生点落在城里时该函数返回 0)
        int edge = SpawnCityPlan.edgeDistance(spawnX, spawnZ, r.centerX(), r.centerZ(), visible);
        CAF_LOGGER.info("[CAF-LC] 出生点附近城市: 出生点({}, {}), 补一座半径 {} 的城(城心 {}, {}), "
                        + "城心距出生点约 {} 格 —— 它的看得见的城区边缘大约在 {} 格外(城心在出生点的{}侧)",
                spawnX, spawnZ, r.radius(), r.centerX(), r.centerZ(), distance, edge,
                directionText(r.centerX() - spawnX, r.centerZ() - spawnZ));
    }

    /**
     * 最近一座城的<b>城心</b>距出生点多少格(方块); 没有城时返回 -1。
     * {@code cities} 里每项 = {城心X, 城心Z, 半径}, 半径不参与这条口径(见 {@link #nearbyCities})。
     */
    private static int nearestCenterDistance(int spawnX, int spawnZ, int[][] cities) {
        int best = -1;
        if (cities == null) {
            return best;
        }
        for (int[] c : cities) {
            if (c == null || c.length < 3) {
                continue;
            }
            int d = SpawnCityPlan.centerDistance(spawnX, spawnZ, c[0], c[1]);
            if (best < 0 || d < best) {
                best = d;
            }
        }
        return best;
    }

    /** 往哪个方向(只给日志用)。Minecraft 里 -Z 是北, +Z 是南。 */
    private static String directionText(int dx, int dz) {
        if (dx > 0) {
            return "东";
        }
        if (dx < 0) {
            return "西";
        }
        return dz > 0 ? "南" : "北";
    }

    private static void logFailOnce(Throwable t) {
        if (!cafLoggedFail) {
            cafLoggedFail = true;
            CAF_LOGGER.warn("[CAF-LC] 出生点附近城市判定异常, 本次按失落城市原生处理: {}", t.toString());
        }
    }
}
