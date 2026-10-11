package com.caf.lccustomcity.impl;

/**
 * 「空地也要放建筑」的判定核心 —— 纯函数, 不引用任何 Minecraft / 失落城市的类, 也不做任何 I/O。
 *
 * <h2>背景</h2>
 * 失落城市的 {@code BuildingInfo.checkBuildingPossibility} 用一串 if/else 决定
 * "这块地皮能不能有建筑"(顺序就是源码里的顺序):
 * <pre>
 *   预定义建筑                    -&gt; true  (直接放过)
 *   预定义街道                    -&gt; false (硬性否决)
 *   section.isMulti()             -&gt; true  (多格建筑的一部分, 不参与掷骰)
 *   plannedRoadType != NONE       -&gt; false (道路, 硬性否决)
 *   bc &gt;= buildingChance         -&gt; false (建筑几率掷骰没过)   &lt;-- 本次要消灭的分支
 *   hasHighway(...)               -&gt; 公路净空够不够
 *   hasRailway(...)               -&gt; 铁路净空够不够
 *   以上都不是                    -&gt; true
 * </pre>
 *
 * 1x1 的单格地皮永远放得下建筑(不存在"塞不进"), 所以它留空的唯一原因就是上面那条
 * "建筑几率掷骰没过"。本规则就是把这个 false 改成 true。
 *
 * <h2>判据(口径) —— 白名单式排除法</h2>
 * <ol>
 *   <li>原值已经是 true -&gt; 原样返回(什么都不做);</li>
 *   <li>配置开关关掉 -&gt; 原样返回(完全恢复失落城市原生行为);</li>
 *   <li>预定义街道 / 多格地皮 / 道路 / 公路净空 / 铁路净空 里任意一条成立 -&gt; 保持 false;</li>
 *   <li>以上一条都不成立, 且原值是 false -&gt; 返回 true。</li>
 * </ol>
 *
 * 第 3 步之所以能推出第 4 步, 是因为上面那张表把 checkBuildingPossibility
 * <b>所有</b>能返回 false 的分支都列全了: 预定义街道、道路、掷骰、公路净空、铁路净空。
 * 前四条都被排除之后, 剩下的 false 只可能来自掷骰。
 *
 * <h2>为什么道路 / 公园 / 桥 / 铁路不会被误伤(红线)</h2>
 * 判据是"只有能证明否决来自掷骰时才动手":
 * <ul>
 *   <li>道路({@code plannedRoadType})、预定义街道、公路/铁路净空这些否决由调用方原样传进来,
 *       任何一条成立就一个字节都不改 ⇒ 这些格子永远保持失落城市的原判;</li>
 *   <li>道路/公园/桥的真正落地发生在失落城市另外的代码路径里(路网规划、街道样式),
 *       本规则只改"这一格能不能有建筑"这一个布尔量, 不碰那些路径;</li>
 *   <li>判据不全(传 null)时同样按"原样"处理 —— 宁可留空, 也绝不越红线。</li>
 * </ul>
 *
 * <p>本类无状态、无随机、可重复调用, 可以被离线测试直接跑。
 */
public final class EmptyPlotRule {

    private EmptyPlotRule() {
    }

    /**
     * 一次判定用到的全部"硬性否决"信息(全是布尔量, 方便离线构造与断言)。
     *
     * @param predefinedStreet 这一格是数据包里的预定义街道(失落城市原判就是"不许有建筑")
     * @param multiSection     这一格属于某栋多格建筑(多格地皮不归本规则管)
     * @param plannedRoad      规划路网要在这一格铺路({@code plannedRoadType != NONE})
     * @param highwayAbove     这一格下面是公路(有净空问题, 保守起见一律不强制)
     * @param railwayAbove     这一格下面是铁路(有净空问题, 保守起见一律不强制)
     */
    public record Vetoes(boolean predefinedStreet, boolean multiSection, boolean plannedRoad,
                         boolean highwayAbove, boolean railwayAbove) {

        /** 一条否决都没有的快照。 */
        public static Vetoes none() {
            return new Vetoes(false, false, false, false, false);
        }

        /** 是否至少有一条硬性否决。 */
        public boolean any() {
            return predefinedStreet || multiSection || plannedRoad || highwayAbove || railwayAbove;
        }
    }

    /**
     * 判定"要不要把这次结果强制改成 true(有建筑)"。
     *
     * @param actual  失落城市原本的返回值({@code checkBuildingPossibility} 的结果)
     * @param enabled 配置开关
     * @param vetoes  硬性否决快照; 传 null = 判据不明, 一律原样返回
     * @return 最终应该返回的值
     */
    public static boolean shouldForce(boolean actual, boolean enabled, Vetoes vetoes) {
        if (vetoes == null) {
            return actual;
        }
        return shouldForce(actual, enabled, vetoes.predefinedStreet(), vetoes.multiSection(),
                vetoes.plannedRoad(), vetoes.highwayAbove(), vetoes.railwayAbove());
    }

    /**
     * 同上, 但把各条否决直接拆成布尔量(世界生成热路径用, 免去一次小对象分配)。
     *
     * @param actual           失落城市原本的返回值
     * @param enabled          配置开关
     * @param predefinedStreet 预定义街道
     * @param multiSection     属于多格建筑
     * @param plannedRoad      规划路网铺路
     * @param highwayAbove     下面是公路
     * @param railwayAbove     下面是铁路
     * @return 最终应该返回的值
     */
    public static boolean shouldForce(boolean actual, boolean enabled, boolean predefinedStreet,
                                      boolean multiSection, boolean plannedRoad,
                                      boolean highwayAbove, boolean railwayAbove) {
        if (!enabled) {
            return actual;      // 开关关掉: 完全恢复失落城市原生行为
        }
        if (actual) {
            return true;        // 已经是"能有建筑": 原样返回
        }
        if (predefinedStreet || multiSection || plannedRoad || highwayAbove || railwayAbove) {
            return false;       // 有任何一条硬性否决: 保持 false(红线, 绝不越)
        }
        return true;            // 排除法: false 只可能来自"建筑几率掷骰没过" ⇒ 强制放建筑
    }
}
