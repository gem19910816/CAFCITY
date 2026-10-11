package com.caf.lccustomcity.config;

import java.util.Map;

/**
 * 建筑数量上下限的【运行时】查询表(阶段 3)。
 *
 * <h2>为什么单独有这么一个类</h2>
 * {@link ZoneConfig} 是"配置文件的解析结果", 键是 城区名/子分区名/文件名 这些人类概念;
 * 而 Mixin 在失落城市选建筑的那一刻, 手里只有两样东西:
 * <ul>
 *   <li>城市风格名(如 {@code caf_zone_inner_a1b2c3d4} / {@code citystyle_border})</li>
 *   <li>建筑资产 id(如 {@code caf_lc_customcity:内城_商业区_便利店_1a2b3c4d})</li>
 * </ul>
 * 所以转换器({@code LcAssetGenerator})在转换每一栋建筑时, 顺手把
 * "这栋建筑的配置文件数量上下限"翻译成 "城市风格名 -&gt; (建筑资产 id -&gt; 上下限)"
 * 的形式, 装进本类。Mixin 直接按风格名查, 不需要再懂配置文件的结构。
 *
 * <h2>单格 / 多格分两张表(崩溃修复)</h2>
 * 失落城市选建筑分两条路: {@code getRandomBuilding}(单格, 去 buildings 注册表查)
 * 和 {@code getRandomMultiBuilding}(多格, 去 multibuildings 注册表查)。
 * 如果单格路径的下限逻辑强制返回了一个【多格】建筑 id, 失落城市会拿着它去
 * buildings 注册表找, 找到 null → 区块生成直接崩
 * (Building.&lt;init&gt;: BuildingRE.getRegistryName() NPE)。
 * 所以本表从一开始就按 单格/多格 分开存: 单格路径只能看到单格的限制,
 * 多格路径只能看到多格的限制, 结构上杜绝串台。
 *
 * <h2>确定性</h2>
 * 本表在模组构造阶段(进世界之前)一次性装好, 之后只读;
 * Mixin 的一切决策 = 本表 + 世界种子 + 区块坐标 的纯函数, 与生成顺序无关。
 *
 * <p>本类不引用 Minecraft 类, 可以被离线测试直接跑。
 */
public final class BuildingCountLimits {

    /** 一栋建筑的数量上下限。min/max 都已经过 ZoneConfig 校验(min &lt;= max)。 */
    public record Limit(int min, int max) {
    }

    /** 城市风格名 -&gt; (单格建筑资产 id(含命名空间) -&gt; 上下限)。 */
    private static volatile Map<String, Map<String, Limit>> byStyleSingle = Map.of();

    /** 城市风格名 -&gt; (多格建筑资产 id(含命名空间) -&gt; 上下限)。 */
    private static volatile Map<String, Map<String, Limit>> byStyleMulti = Map.of();

    private BuildingCountLimits() {
    }

    /**
     * 由转换器在启动时装入; 这里做深拷贝, 之后传入方怎么改都不影响运行时查询。
     *
     * @param single 单格建筑的数量限制表(可为 null/空)
     * @param multi  多格建筑的数量限制表(可为 null/空)
     */
    public static void install(Map<String, Map<String, Limit>> single,
                               Map<String, Map<String, Limit>> multi) {
        byStyleSingle = deepCopy(single);
        byStyleMulti = deepCopy(multi);
    }

    private static Map<String, Map<String, Limit>> deepCopy(Map<String, Map<String, Limit>> table) {
        if (table == null || table.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Limit>> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Limit>> e : table.entrySet()) {
            copy.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Map.copyOf(copy);
    }

    /**
     * 查某个城市风格下所有"配了数量上下限"的<b>单格</b>建筑。
     *
     * @return 资产 id(含命名空间) -&gt; 上下限; 这个风格没有任何单格限制时返回空 Map(不为 null)
     */
    public static Map<String, Limit> forStyleSingle(String styleName) {
        if (styleName == null) {
            return Map.of();
        }
        Map<String, Limit> m = byStyleSingle.get(styleName);
        return m == null ? Map.of() : m;
    }

    /**
     * 查某个城市风格下所有"配了数量上下限"的<b>多格</b>建筑。
     *
     * @return 资产 id(含命名空间) -&gt; 上下限; 这个风格没有任何多格限制时返回空 Map(不为 null)
     */
    public static Map<String, Limit> forStyleMulti(String styleName) {
        if (styleName == null) {
            return Map.of();
        }
        Map<String, Limit> m = byStyleMulti.get(styleName);
        return m == null ? Map.of() : m;
    }

    /** 全表一共配了多少栋建筑的数量限制(单格+多格合计, 给日志用)。 */
    public static int totalConfigured() {
        int n = 0;
        for (Map<String, Limit> m : byStyleSingle.values()) {
            n += m.size();
        }
        for (Map<String, Limit> m : byStyleMulti.values()) {
            n += m.size();
        }
        return n;
    }
}
