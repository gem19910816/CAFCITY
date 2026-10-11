package com.caf.lccustomcity.config;

import java.util.List;
import java.util.Map;

/**
 * 内城子分区"占比"的【运行时】查询表(阶段 2b)。
 *
 * <h2>为什么单独有这么一个类</h2>
 * 和 {@link BuildingCountLimits} 同一套路: {@link ZoneConfig} 的键是人类概念
 * (城区名/子分区名), 而 Mixin 分区时手里只有城市风格名({@code caf_zone_inner_<哈希>})。
 * 转换器({@code LcAssetGenerator})在转换期把 "子分区占比" 翻译成
 * "城市风格名 -&gt; 权重(double)" 装进本类, Mixin 按风格名直查。
 *
 * <h2>占比的语义: 相对权重</h2>
 * 各城区的占比会<b>除以总和</b>归一化, 所以写 3:1 和写 0.75:0.25 效果完全一样
 * (都是 3/4 和 1/4)。某个子分区没配占比时权重按 {@link #DEFAULT_WEIGHT}(1.0) 算
 * —— 即"没配的就均分"; 所有城区都没配时自然退化成完全均分。
 *
 * <h2>确定性</h2>
 * 本表在模组构造阶段(进世界之前)一次性装好, 之后只读; 占比区间 [min,max]
 * 在装表时就已经取中点折叠成单个 double, Mixin 的一切决策 =
 * 本表 + 世界种子 + 区块坐标 的纯函数, 与区块生成顺序无关, 不含无种子随机。
 *
 * <p>本类不引用 Minecraft 类, 可以被离线测试直接跑。
 */
public final class ZoneWeights {

    /** 没配占比 / 占比非法 时的默认权重(= 均分)。 */
    public static final double DEFAULT_WEIGHT = 1.0;

    /** 城市风格名(caf_zone_inner_<哈希>) -&gt; 占比权重(已保证 &gt; 0)。 */
    private static volatile Map<String, Double> byStyle = Map.of();

    private ZoneWeights() {
    }

    /**
     * 由转换器在启动时装入。
     *
     * <p>权重 &lt;= 0 或 NaN 的条目在这里直接丢弃(调用方应已警告过),
     * 丢弃后该风格走 {@link #DEFAULT_WEIGHT}。
     */
    public static void install(Map<String, Double> table) {
        if (table == null || table.isEmpty()) {
            byStyle = Map.of();
            return;
        }
        Map<String, Double> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Double> e : table.entrySet()) {
            Double w = e.getValue();
            if (w != null && w > 0 && !Double.isNaN(w)) {
                copy.put(e.getKey(), w);
            }
        }
        byStyle = Map.copyOf(copy);
    }

    /**
     * 查某个子分区城市风格的占比权重。
     *
     * @return 配了占比返回配置值(&gt; 0); 没配 / 风格名不是子分区风格 返回 {@link #DEFAULT_WEIGHT}
     */
    public static double weightOf(String styleName) {
        if (styleName == null) {
            return DEFAULT_WEIGHT;
        }
        Double w = byStyle.get(styleName);
        return w == null ? DEFAULT_WEIGHT : w;
    }

    /** 配了占比的子分区风格个数(给日志用)。 */
    public static int configured() {
        return byStyle.size();
    }

    /**
     * 按权重把噪声值 v∈[0,1) 映射成子分区下标 —— 占比功能的纯函数核心。
     *
     * <p>把 [0,1) 切成 length 段, 第 i 段宽度 = weights[i] ÷ 总和; v 落在哪段就返回哪个下标。
     * 权重越大的城区分到的段越宽, 面积越大。
     *
     * <p>容错: 权重 &lt;= 0 / NaN 按 {@link #DEFAULT_WEIGHT} 算; 全部权重相等时
     * 自然等价于均分; v 越界时钳回 [0,1)。
     *
     * <p>纯函数: 同样的 (v, weights) 永远返回同样的下标。
     */
    public static int pickIndex(double v, double[] weights) {
        if (weights == null || weights.length == 0) {
            return 0;
        }
        if (weights.length == 1) {
            return 0;
        }
        double total = 0;
        double[] eff = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            double w = weights[i];
            if (!(w > 0) || Double.isNaN(w)) {
                w = DEFAULT_WEIGHT;
            }
            eff[i] = w;
            total += w;
        }
        double x = v;
        if (x < 0) {
            x = 0;
        } else if (x >= 1) {
            x = Math.nextDown(1.0);
        }
        x *= total;
        for (int i = 0; i < eff.length; i++) {
            if (x < eff[i]) {
                return i;
            }
            x -= eff[i];
        }
        return eff.length - 1;   // 浮点兜底: 落进最后一个区间
    }

    /**
     * 同上, 但权重按城市风格名从运行时表里查(没配的用 {@link #DEFAULT_WEIGHT})。
     *
     * @param styleNames 排序后的子分区城市风格名列表(顺序必须稳定, Mixin 里已排序)
     */
    public static int pickIndex(double v, List<String> styleNames) {
        double[] weights = new double[styleNames.size()];
        for (int i = 0; i < styleNames.size(); i++) {
            weights[i] = weightOf(styleNames.get(i));
        }
        return pickIndex(v, weights);
    }
}
