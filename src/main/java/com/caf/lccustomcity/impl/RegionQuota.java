package com.caf.lccustomcity.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * 一个 (区域 x 风格) 的配额查询结果: 每栋受控建筑的 槽位 -> 名次。
 *
 * <p>原本是 {@code BuildingCountMixin} 的私有嵌套类, 已抽成顶层类移出 mixin 包
 * (Sponge Mixin 规定 mixin 包里的类只能作为 Mixin 加载, 嵌套辅助类被直接引用会
 * 抛 IllegalClassLoadError)。
 *
 * <p><b>2026-10-04 起已不再被调用(死代码)</b>: 建这张表要给每栋建筑扫 16384 个槽位并装箱排序,
 * 是卡顿事故的次要原因之一。数量上下限已改成 O(1) 的抽签判定, 见 {@link SlotQuota}
 * (单格下限)与 {@link MultiQuotaGate}(多格上限)。留在这里只为不破坏已有构建/引用,
 * 可以随时删除。
 */
public final class RegionQuota {

    /** 建筑id -> (区域内槽位号 -> 名次)。 */
    public final Map<String, Map<Integer, Integer>> positionBySlot = new HashMap<>();

    /** @return 该槽位在这栋楼候选里的名次; 槽位不属于候选返回 -1 */
    public int rankPosition(String buildingId, int slot) {
        Map<Integer, Integer> m = positionBySlot.get(buildingId);
        if (m == null) {
            return -1;
        }
        Integer p = m.get(slot);
        return p == null ? -1 : p;
    }
}
