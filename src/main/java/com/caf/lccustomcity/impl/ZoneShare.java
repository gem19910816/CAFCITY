package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.ZoneWeights;
import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.worldgen.lost.regassets.CityStyleRE;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.WorldGenLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内城子分区风格在世界里占多大面积(占比)的运行时查询表。
 *
 * <h2>给谁用</h2>
 * {@code BuildingCountMixin}(建筑数量上下限)需要把"每座城最多 N 次"换算成
 * "这个风格覆盖的区块里每 16384 个格位允许 N 个" —— 见 {@link SlotQuota#effectiveSlots}。
 * 子分区风格的占比来自 {@code 城区配置.json} 的占比(权重), 由 {@link ZoneWeights} 装表;
 * 这里只负责"世界 → 当前有哪些子分区风格 + 权重总和", 并按风格名返回占比。
 *
 * <h2>成本</h2>
 * 列表 + 权重总和按世界缓存一次(切世界重建), 之后每次查询 = 一次 volatile 读 + 两次字符串比较
 * (最多一次 indexOf)。<b>绝不</b>每次调用都去遍历注册表 —— 这是上一个版本卡顿的教训之一。
 *
 * <h2>拿不到就返回负数</h2>
 * 注册表还没准备好 / 这个风格不在列表里 / 权重总和异常 ⇒ 返回 {@code -1},
 * 调用方<b>必须</b>当作"不判定"直接放行原生行为, 不能当成占比 0 处理
 * (占比 0 会让"候选格位 = 1", 反而把所有区块都判成指定槽位)。
 */
public final class ZoneShare {

    /** 子分区风格名的前缀(与 {@code CityStyleZoneMixin} / 转换器一致, 改一处必须同步改)。 */
    public static final String ZONE_PREFIX = "caf_zone_inner_";

    /** "拿不到占比"的返回值。 */
    public static final double UNKNOWN = -1.0;

    /** 缓存所属世界 + 该世界的子分区风格名(已排序, 顺序稳定) + 权重总和。 */
    private static volatile WorldGenLevel cachedWorld;
    private static volatile List<String> cachedNames = List.of();
    private static volatile double cachedTotal = 1.0;

    private ZoneShare() {
    }

    /**
     * 这个城市风格占整片城区多大比例。
     *
     * @param world     世界(可为 null ⇒ 返回 {@link #UNKNOWN})
     * @param styleName 城市风格名(可为 null ⇒ 返回 {@link #UNKNOWN})
     * @return {@code (0,1]} 的占比; 非子分区风格返回 1.0(整片城区都是候选);
     *         子分区风格但拿不到占比时返回 {@link #UNKNOWN}
     */
    public static double shareOf(WorldGenLevel world, String styleName) {
        if (world == null || styleName == null) {
            return UNKNOWN;
        }
        if (!styleName.startsWith(ZONE_PREFIX)) {
            return 1.0;   // 兜底风格(内外城): 只被自分区风格之外的区块用, 面积按整片城区算
        }
        List<String> names = namesOf(world);
        if (names.isEmpty()) {
            return UNKNOWN;
        }
        double total = cachedTotal;
        if (!(total > 0)) {
            return UNKNOWN;
        }
        double weight = ZoneWeights.weightOf(styleName);
        if (!(weight > 0)) {
            return UNKNOWN;
        }
        double share = weight / total;
        if (!(share > 0)) {
            return UNKNOWN;
        }
        return share > 1 ? 1 : share;
    }

    /** 当前世界的子分区风格名列表(空列表 = 拿不到, 调用方按"不判定"处理)。 */
    public static List<String> namesOf(WorldGenLevel world) {
        if (world == null) {
            return List.of();
        }
        if (cachedWorld == world) {
            return cachedNames;
        }
        List<String> names = new ArrayList<>();
        double total = 0;
        try {
            Registry<CityStyleRE> registry = world.registryAccess()
                    .registryOrThrow(CustomRegistries.CITYSTYLES_REGISTRY_KEY);
            for (ResourceKey<CityStyleRE> key : registry.registryKeySet()) {
                String path = key.location().getPath();
                if (path.startsWith(ZONE_PREFIX)) {
                    names.add(path);
                }
            }
            Collections.sort(names);   // 顺序必须稳定(和 CityStyleZoneMixin 的取值口径一致)
            for (String name : names) {
                total += ZoneWeights.weightOf(name);
            }
        } catch (Throwable t) {
            return List.of();   // 注册表没准备好等场合: 不判定, 绝不抛回区块生成
        }
        if (names.isEmpty()) {
            return List.of();
        }
        cachedTotal = total > 0 ? total : 1.0;
        cachedNames = names;
        cachedWorld = world;   // 最后写世界: 读方先看到列表再看到世界, 最坏只是多重建一次
        return names;
    }
}
