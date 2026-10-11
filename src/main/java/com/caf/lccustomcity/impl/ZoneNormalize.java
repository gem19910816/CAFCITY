package com.caf.lccustomcity.impl;

import java.util.Arrays;

/**
 * 分区噪声"均匀化"映射(阶段 2b 补丁, 第二版: 运行时自校准经验 CDF)。
 *
 * <h2>解决什么问题</h2>
 * {@link ZoneNoise#fbm01} 的理论值域是 [0,1), 但实测分布<b>不是均匀的</b>,
 * 挤在中间: 用真实噪声工具对约 3.5 万个区块采样, 实测值域只有约 [0.193, 0.784],
 * 样本集中在 0.5 附近(近似钟形)。而 {@code ZoneWeights.pickIndex} 按权重把
 * [0,1) 切成不等宽段, 权重小的城区(工业区/军事区)排在尾巴段, 挤在中间的噪声
 * 根本摸不到尾巴: 实测占比 商业 38.5% / 住宅 60.8% / 工业 0.09% / 军事 0.6%,
 * 与目标 47.6% / 23.8% / 23.8% / 4.8% 严重不符。
 *
 * <h2>第一版为什么力度不够</h2>
 * 旧版是固定分段线性映射 (0,0)-&gt;(0.20,0.14)-&gt;(0.80,0.86)-&gt;(1,1),
 * 中间段斜率只有 1.2: 工业区勉强能抽到, 军事区(段起点 0.952)基本仍摸不到。
 * 根本原因是当时的离线冒烟测试喂均匀分布样本、要求输出也均匀,
 * 等于要求映射约等于恒等变换, 把拉伸力度锁死了。
 *
 * <h2>本版做法: 按当前世界种子自校准的经验累积分布(CDF)</h2>
 * 概率积分变换: 若随机变量 V 的累积分布函数是 F, 则 F(V) 服从 [0,1] 均匀分布。
 * 所以把噪声值 V 喂进它自己的 CDF, 输出就是(近似)均匀的, 四段占比天然对齐权重。
 * <ol>
 *   <li>首次需要某个种子时(惰性, 每个种子只做一次), 用 {@link ZoneNoise#forSeed}
 *       在<b>真实采样尺度</b>上采样: 坐标 {@code chunkX / 24.0, chunkZ / 24.0},
 *       {@code fbm01(..., 3)}, 采样 128x128 = 16384 个点
 *       (覆盖以原点为中心正负 256 区块、步长 4 的网格, 远大于 fBm 最长波长,
 *       分布统计已收敛);</li>
 *   <li>把 16384 个样本排序, 等距取 33 个分位点(含最小/最大值)存成查找表;</li>
 *   <li>{@code uniform(seed, v)} = 在这张 33 点表上做线性插值的经验 CDF:
 *       v 小于等于表头返回 0, 大于等于表尾返回 1, 中间二分查找所在分段后线性插值,
 *       整体单调不减、输出恒在 [0,1];</li>
 *   <li>换世界种子时自动重新校准(表里记着对应的种子号, 对不上就重建)。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * 校准每个世界种子只做一次: 16384 次 fBm(每次 3 层 Perlin)+ 一次排序,
 * 毫秒级, 发生在该世界第一个内城区块选风格时。之后每次调用只有一次二分查找
 * (33 格, 最多 5 次比较)+ 一次除法, 比一次 fBm 本身便宜几个数量级,
 * 可以放心在区块生成的高频路径上调用。
 *
 * <h2>并发与缓存</h2>
 * 查找表放在 {@code volatile} 字段里; 校准用类级 {@code synchronized} 双检锁,
 * 多个工作线程同时首次进入只会建一次表; 建表完成后读路径完全无锁。
 *
 * <h2>兜底(绝不让异常进世界生成)</h2>
 * 采样/排序/建表过程中发生任何异常, 或 {@link ZoneNoise#forSeed} 拿不到实例,
 * 都静默回退到旧版的固定分段线性映射 {@link #fallback(double)}
 * (保留在 {@link #uniform(double)} 里)。若之前已为别的种子建过表,
 * 失败时继续沿用旧表(不同种子的 fBm 分布形状几乎相同, 旧表远好于裸噪声)。
 *
 * <p>本类是辅助类, 按 Sponge Mixin 规定必须放在 impl 包而不是 mixin 包。
 */
public final class ZoneNormalize {

    // ---- 校准参数(必须与 CityStyleZoneMixin 的实际用法一致) ----

    /** 噪声波长(区块数), 与 CityStyleZoneMixin.ZONE_WAVELENGTH 一致。 */
    private static final double WAVELENGTH = 24.0;
    /** fBm 叠加层数, 与 CityStyleZoneMixin.ZONE_OCTAVES 一致。 */
    private static final int OCTAVES = 3;
    /** 校准网格边长: 128x128 = 16384 个采样点。 */
    private static final int SAMPLE_SIDE = 128;
    /** 校准采样点总数。 */
    private static final int SAMPLE_COUNT = SAMPLE_SIDE * SAMPLE_SIDE;
    /** 网格步长(区块): 128 点乘步长 4, 覆盖正负 256 区块。 */
    private static final int SAMPLE_STEP = 4;
    /** CDF 查找表点数: 33 个分位点 = 32 个等概率分段。 */
    private static final int TABLE_POINTS = 33;

    // ---- 旧版固定分段线性映射常量(只给 fallback 用) ----

    /** 低拐点(输入侧)。 */
    private static final double EDGE_LO_X = 0.20;
    /** 低拐点(输出侧)。 */
    private static final double EDGE_LO_Y = 0.14;
    /** 高拐点(输入侧)。 */
    private static final double EDGE_HI_X = 0.80;
    /** 高拐点(输出侧)。 */
    private static final double EDGE_HI_Y = 0.86;

    /** 当前缓存的 CDF 查找表(33 个升序分位点); null 表示还没校准过。 */
    private static volatile double[] table = null;
    /** 当前查找表对应的世界种子; 对不上就要重新校准。 */
    private static volatile long tableSeed = Long.MIN_VALUE;

    private ZoneNormalize() {
    }

    /**
     * 把原始 fBm 噪声值映射成"统计上接近均匀分布"的值(按世界种子自校准)。
     *
     * <p>单调不减; {@code v <= 0} 返回 0, {@code v >= 1} 返回 1, 输出恒在 [0,1]。
     * 首次见到某个种子时会惰性采样建表(每个种子一次); 建表失败回退固定映射。
     *
     * @param seed 世界种子(与喂给 {@link ZoneNoise#forSeed} 的同一个)
     * @param v    原始噪声值(理论上 [0,1), 越界也安全)
     * @return 均匀化后的值, 可直接喂给 {@code ZoneWeights.pickIndex}
     */
    public static double uniform(long seed, double v) {
        if (v <= 0) {
            return 0;
        }
        if (v >= 1) {
            return 1;
        }
        double[] t = table;
        if (t == null || tableSeed != seed) {
            t = calibrate(seed);
        }
        if (t == null) {
            return fallback(v);
        }
        return lookup(t, v);
    }

    /**
     * 兼容旧接口: 拿不到种子时的固定分段线性映射(力度有限, 仅作兜底)。
     * 新代码请用 {@link #uniform(long, double)}。
     */
    public static double uniform(double v) {
        return fallback(v);
    }

    /** 双检锁校准: 每个种子只建一次表; 任何异常都静默回退, 绝不抛出。 */
    private static double[] calibrate(long seed) {
        double[] t = table;
        if (t != null && tableSeed == seed) {
            return t;
        }
        synchronized (ZoneNormalize.class) {
            if (table != null && tableSeed == seed) {
                return table;
            }
            try {
                double[] built = buildTable(seed);
                if (built != null) {
                    table = built;
                    tableSeed = seed;
                    return built;
                }
            } catch (Throwable ignored) {
                // 静默: 绝不能让校准异常炸进区块生成
            }
            // 失败: 沿用旧表(可能为 null, 调用方会走 fallback)
            return table;
        }
    }

    /** 采样 16384 个点, 排序后取 33 个等距分位点。 */
    private static double[] buildTable(long seed) {
        ZoneNoise noise = ZoneNoise.forSeed(seed);
        if (noise == null) {
            return null;
        }
        double[] samples = new double[SAMPLE_COUNT];
        int base = -(SAMPLE_SIDE / 2) * SAMPLE_STEP;   // -256: 网格以原点为中心
        int k = 0;
        for (int i = 0; i < SAMPLE_SIDE; i++) {
            double x = (base + i * SAMPLE_STEP) / WAVELENGTH;
            for (int j = 0; j < SAMPLE_SIDE; j++) {
                double z = (base + j * SAMPLE_STEP) / WAVELENGTH;
                samples[k++] = noise.fbm01(x, z, OCTAVES);
            }
        }
        Arrays.sort(samples);
        double[] t = new double[TABLE_POINTS];
        for (int q = 0; q < TABLE_POINTS; q++) {
            t[q] = samples[q * (SAMPLE_COUNT - 1) / (TABLE_POINTS - 1)];
        }
        return t;
    }

    /** 在 33 点分位表上二分定位 + 线性插值, 即经验 CDF。 */
    private static double lookup(double[] t, double v) {
        int last = t.length - 1;
        if (v <= t[0]) {
            return 0;
        }
        if (v >= t[last]) {
            return 1;
        }
        // 二分: 找 lo 使 t[lo] <= v < t[lo + 1]
        int lo = 0;
        int hi = last;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] <= v) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = t[lo + 1] - t[lo];
        double frac = span > 0 ? (v - t[lo]) / span : 0;
        return (lo + frac) / last;
    }

    /** 旧版固定分段线性映射: (0,0)-&gt;(0.20,0.14)-&gt;(0.80,0.86)-&gt;(1,1)。 */
    private static double fallback(double v) {
        if (v <= 0) {
            return 0;
        }
        if (v >= 1) {
            return 1;
        }
        if (v < EDGE_LO_X) {
            return v * (EDGE_LO_Y / EDGE_LO_X);
        }
        if (v <= EDGE_HI_X) {
            return EDGE_LO_Y + (v - EDGE_LO_X) * ((EDGE_HI_Y - EDGE_LO_Y) / (EDGE_HI_X - EDGE_LO_X));
        }
        return EDGE_HI_Y + (v - EDGE_HI_X) * ((1 - EDGE_HI_Y) / (1 - EDGE_HI_X));
    }
}
