package com.caf.lccustomcity.impl;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code City.getCityFactor} 的记忆化缓存(2026-10-04 卡顿事故的<b>主修</b>之一)。
 *
 * <h2>为什么需要它(卡顿的确切来源)</h2>
 * {@code City.getCityFactor(coord, ...)} 内部是<b>暴力扫描</b>:
 * {@code (CITY_MAXRADIUS + 15) / 16} 为半径的方块窗口里逐个区块判城心 ——
 * 本模组把 {@code CITY_MAXRADIUS} 配成 1000 方块 ⇒ 半径 63 区块 ⇒ <b>每次调用扫 16129 个候选区块</b>
 * (失落城市原版 128 方块 ⇒ 半径 8 ⇒ 只有 289 个, 差 55.8 倍)。
 *
 * <p>关键是<b>调用次数</b>: 失落城市放多格建筑时
 * ({@code MultiChunk.calculateBuildings} → {@code canPlaceBuilding} → {@code BuildingInfo.isCityRaw})
 * 对"每个建筑的每次放置尝试的每个占地格"都要调一次 —— 一个 16x16 区块的多格区域
 * (本模组把 worldstyle 的 areasize 调成 16, minimum/maximum 40~80, attempts 200)
 * 里是<b>上万次</b>调用, 全落在"第一个进入该区域的区块"那一次区块生成里:
 * 实测 {@code /lostcities stats} 的 Max = <b>27809ms</b>(平均只有 5ms)。
 *
 * <p>而这上万次调用反复查的是同一个 16x16 区域里的同样几百个坐标 ⇒ 记忆化后
 * 命中率 &gt;99%, 单次成本从 0.36ms 掉到一次 ConcurrentHashMap 查表。
 *
 * <h2>只缓存"完整算出来"的非零值</h2>
 * {@code getCityFactor} 里有一段: {@code factor > 0.0001 && provider.getWorld() != null} 时
 * 才查高度图, 高度图拿不到就直接 {@code return 0}。也就是说 <b>0 可能是"暂时算不出来"</b>,
 * 缓存它会让这个区块永远拿不到城市。所以:
 * <ul>
 *   <li>只缓存 {@code factor > 0} 的结果;</li>
 *   <li>{@code provider.getWorld() == null}(GUI 预览等)时既不入缓存也不查缓存 ——
 *       那种场合失落城市会跳过高度图/生物群系乘法, 结果和实机不同。</li>
 * </ul>
 * 缓存值的其它输入(高度图 / 生物群系)在失落城市自己那边同样是"按坐标缓存"的
 * ({@code cachedHeightmaps} / {@code BiomeInfo}, 默认 300 秒 TTL), 所以这里同样给 300 秒有效期,
 * 与失落城市的缓存口径保持一致: 热点期内冻结, 过期后重新计算。
 *
 * <h2>失效</h2>
 * <ul>
 *   <li>失落城市调 {@code City.cleanCache()}(改配置档 / 世界切换)时一并清空, 见
 *       {@code mixin/CityFactorCacheMixin};</li>
 *   <li>条目数超过上限时清空重算(纯函数缓存, 清空只影响性能不影响结果)。</li>
 * </ul>
 *
 * <h2>绝不影响世界生成</h2>
 * 所有方法内部 try/catch: 任何异常都退化成"没有缓存"(调用方走原生计算), 绝不抛回区块生成。
 *
 * <p>本类只用到失落城市的两个接口和 Minecraft 的维度键, 不碰世界方块。
 */
public final class CityFactorCache {

    /** 条目上限(每个配置档): 超了整体清空。16384 个区块的一座城只需要一万多个条目。 */
    private static final int MAX_ENTRIES = 1 << 16;

    /** 条目有效期(毫秒): 与失落城市默认的 cacheCleanupSeconds = 300 一致。 */
    private static final long TTL_MILLIS = 300_000L;

    /** 键 = 世界种子 + 维度 + 区块坐标(全字段参与比较, 不做哈希压缩, 避免撞键串值)。 */
    private record Key(long seed, ResourceKey<Level> dim, int chunkX, int chunkZ) {
    }

    /** 值 = 因子 + 到期时间戳。 */
    private record Hit(float factor, long expiresAt) {
    }

    /** 外层按配置档对象区分(失落城市可能同时有主配置档和"球外配置档"), 内层按坐标区分。 */
    private static final ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Key, Hit>> CACHE =
            new ConcurrentHashMap<>();

    private CityFactorCache() {
    }

    /**
     * 查缓存。
     *
     * @return 命中返回缓存的因子; 未命中 / 不可缓存返回 null(调用方必须继续走原生计算)
     */
    public static Float lookup(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        try {
            Key key = key(provider, profile, coord);
            if (key == null) {
                return null;
            }
            ConcurrentHashMap<Key, Hit> byKey = CACHE.get(profile);
            if (byKey == null) {
                return null;
            }
            Hit hit = byKey.get(key);
            if (hit == null || System.currentTimeMillis() >= hit.expiresAt()) {
                return null;
            }
            return hit.factor();
        } catch (Throwable t) {
            return null;   // 缓存出错 ⇒ 退回原生计算, 绝不影响区块生成
        }
    }

    /**
     * 存缓存: <b>只存非零值</b>(0 可能是"暂时拿不到高度图", 存了会让区块永远不成城市)。
     *
     * @param factor 原生计算出来的因子
     */
    public static void store(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord, float factor) {
        try {
            if (!(factor > 0f)) {
                return;
            }
            Key key = key(provider, profile, coord);
            if (key == null) {
                return;
            }
            ConcurrentHashMap<Key, Hit> byKey = CACHE.get(profile);
            if (byKey == null) {
                ConcurrentHashMap<Key, Hit> fresh = new ConcurrentHashMap<>();
                ConcurrentHashMap<Key, Hit> prev = CACHE.putIfAbsent(profile, fresh);
                byKey = prev == null ? fresh : prev;
            }
            if (byKey.size() >= MAX_ENTRIES) {
                byKey.clear();   // 纯函数缓存: 清空只是重算, 不影响结果
            }
            byKey.put(key, new Hit(factor, System.currentTimeMillis() + TTL_MILLIS));
        } catch (Throwable t) {
            // 静默: 绝不把异常抛回区块生成
        }
    }

    /** 清空(失落城市清理自己的缓存时一并调用: 改配置档 / 世界切换之后)。 */
    public static void clear() {
        try {
            CACHE.clear();
        } catch (Throwable t) {
            // 静默
        }
    }

    /** 组键; 不可缓存(参数为空 / 没有世界)时返回 null。 */
    private static Key key(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        if (provider == null || profile == null || coord == null) {
            return null;
        }
        if (provider.getWorld() == null) {
            return null;
        }
        return new Key(provider.getSeed(), provider.getType(), coord.chunkX(), coord.chunkZ());
    }
}
