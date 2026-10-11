package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingCountLimits;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;

import java.util.Map;

/**
 * 多格建筑「数量上限」的 <b>O(1) 拦截点</b>(卡顿事故的整改之一)。
 *
 * <h2>为什么拦在 canPlaceBuilding 里</h2>
 * 失落城市选多格建筑是"先在区域里随机挑一栋楼({@code getRandomMultiBuilding}),
 * 再随机找个位置试着放下({@code canPlaceBuilding})"。要在"挑楼"那一步超上限时换成别的楼,
 * 就只能自己再调一次挑楼方法 —— 那正是旧实现里那段重试(在被注入的方法里再进被注入的方法),
 * 热路径上非常危险。
 *
 * <p>所以上限改成在 <b>放置判定</b> 这一步拦: 位置不合法就直接否决, 失落城市自己会继续
 * 试下一个位置/下一栋楼(它本来就有 attempts 上限的循环)。我们自己<b>不循环、不递归、不调失落城市</b>。
 *
 * <h2>上限是怎么变成"硬上限"的</h2>
 * 每个区块格位对这栋楼有一个固定的抽签值({@link SlotQuota#ticket}), 只有
 * {@code 抽签值 x 候选格位数 < max} 的格位才允许放 ⇒ 一个配额区域(128x128 区块)里
 * 允许的格位数期望正好是 max。放置时用的格位是"占地左上角", 而 buildingGrid 不允许重叠,
 * 所以这栋楼在一个区域里<b>最多</b>放 max 次 —— 期望值也就成了硬上限。
 *
 * <p>代价: 每次调用一次 Map 查表 + 一次哈希(约几十纳秒), 而且被否决的尝试<b>省掉了</b>
 * 失落城市原生那一整圈占地扫描。绝大多数建筑的上限是"完全随机"(Integer.MAX_VALUE),
 * {@link SlotQuota#within} 直接返回 true, 等于没拦。
 *
 * <h2>兜底</h2>
 * 任何异常 / 拿不到占比 ⇒ 返回 false(不拦), 结果等价于失落城市原生行为, 不崩世界生成。
 */
public final class MultiQuotaGate {

    private MultiQuotaGate() {
    }

    /**
     * 这栋多格建筑在这个位置是否已经超出数量上限(超了就否决放置)。
     *
     * @param provider         维度信息(取世界种子用; 为空 ⇒ 不拦)
     * @param buildingCityStyle 这栋楼是从哪个城市风格的选择列表里挑出来的(取该风格的数量配置和面积占比)
     * @param buildingId       建筑资产 id({@code MultiBuilding.getName()})
     * @param chunkX           本次放置的占地左上角区块 X(绝对坐标)
     * @param chunkZ           本次放置的占地左上角区块 Z(绝对坐标)
     * @return true = 超上限, 调用方应否决这次放置; false = 不拦
     */
    public static boolean overQuota(IDimensionInfo provider, CityStyle buildingCityStyle, String buildingId,
                                   int chunkX, int chunkZ) {
        try {
            if (provider == null || buildingCityStyle == null || buildingId == null) {
                return false;
            }
            String styleName = buildingCityStyle.getName();
            Map<String, BuildingCountLimits.Limit> limits = BuildingCountLimits.forStyleMulti(styleName);
            if (limits.isEmpty()) {
                return false;
            }
            BuildingCountLimits.Limit limit = limits.get(buildingId);
            if (limit == null) {
                return false;   // 这栋楼没配数量 ⇒ 原生行为
            }
            double share = ZoneShare.shareOf(provider.getWorld(), styleName);
            if (!(share > 0)) {
                return false;   // 拿不到占比(注册表未就绪等) ⇒ 不拦, 绝不能当成占比 0
            }
            int slots = SlotQuota.effectiveSlots(share);
            double ticket = SlotQuota.ticket(provider.getSeed(), buildingId, chunkX, chunkZ);
            return !SlotQuota.within(ticket, slots, limit.max());
        } catch (Throwable t) {
            return false;   // 任何异常都不拦: 数量限制不该影响世界生成
        }
    }
}
