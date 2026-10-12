package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.MultiGapRule;
import com.caf.lccustomcity.impl.MultiQuotaGate;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 多格建筑放置的<b>前置否决</b>(性能修复版): 「大楼之间留一格空隙」+
 * 「建筑数量上限」。
 *
 * <h2>为什么从 RETURN 改成 HEAD(卡顿事故的关键整改)</h2>
 * 原来挂在 {@code canPlaceBuilding} 的 RETURN 上: 失落城市原生已经把整个占地
 * (本模组平均 10.92 个区块) 扫完了 —— 每个占地格都会调
 * {@code BuildingInfo.isCityRaw} → {@code City.getCityFactor}(一次要扫 16129 个候选区块,
 * 实测 0.36ms) —— 我们才把结果改成 false。也就是说<b>每一次注定被否决的尝试, 都白付了
 * 一整圈占地扫描</b>(约 4ms); 一个区块里这种尝试上万次, 这就是实测 27809ms 里最大的一块。
 *
 * <p>现在挂在 HEAD: 先做我方判定, 要否决就直接 {@code setReturnValue(false)}, 原生那一圈
 * 扫描<b>一点都不跑</b>。结果与原生完全等价 —— 原版这个方法只读状态、无副作用
 * (只读 buildingGrid / 占用表 / 路网 / 风格, 不写任何东西),
 * 我方判定又不看原生结果(两款规则都只可能否决), 所以"先否决"和"后否决"结果一样, 只是快得多。
 *
 * <h2>判定顺序(从便宜到贵)</h2>
 * <ol>
 *   <li>数量上限({@link MultiQuotaGate}): 一次 Map 查表 + 一次哈希;</li>
 *   <li>大楼之间留一格空隙({@link MultiGapRule}): 只读占地外圈一环的 buildingGrid;</li>
 * </ol>
 *
 * <h2>开关</h2>
 * {@code config/CAF失落城市配置/多格建筑间距.json} 里的「大楼之间强制留一格空隙」默认 true;
 * 数量上限来自 {@code config/CAF失落城市配置/建筑物数量.json}(见 {@code BuildingCountLimits})。
 *
 * <h2>确定性 / 兜底</h2>
 * 只读已放下的格子(calculateBuildings 单线程顺序放置), 不碰随机数, 结果确定。
 * require=0 + 判定内部 try/catch: 注入失败或任何异常都只是"本规则不生效",
 * 退回失落城市原生行为, 不崩游戏。
 */
@Mixin(MultiChunk.class)
public abstract class MultiChunkGapMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-GapMixin");
    private static boolean caf$loggedOnce = false;
    private static boolean caf$loggedQuotaOnce = false;

    @Inject(method = "canPlaceBuilding(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;Lmcjty/lostcities/worldgen/lost/cityassets/CityStyle;Lmcjty/lostcities/worldgen/lost/cityassets/MultiBuilding;IIII)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void caf$beforePlacement(ChunkCoord topleft, IDimensionInfo provider, LostCityProfile profile,
                                     CityStyle buildingCityStyle, MultiBuilding building,
                                     int cityLevel, int maxCellars, int x, int z,
                                     CallbackInfoReturnable<Boolean> cir) {
        try {
            // x / z 是"多格区域内的偏移", 数量上限的抽签要用绝对区块坐标
            int absX = topleft.chunkX() + x;
            int absZ = topleft.chunkZ() + z;

            // 1) 建筑数量上限(硬上限): 超了直接否决, 省掉原生整圈占地扫描
            if (MultiQuotaGate.overQuota(provider, buildingCityStyle, building.getName(), absX, absZ)) {
                if (!caf$loggedQuotaOnce) {
                    caf$loggedQuotaOnce = true;
                    CAF_LOGGER.info("[CAF-LC] 建筑数量上限已生效: 超出名额的位置直接否决(不换楼, 也不跑原生占地扫描)");
                }
                cir.setReturnValue(false);
                return;
            }

            // 2) 大楼之间强制留一格空隙
            if (MultiGapRule.ringOccupied(this, x, z, building.getDimX(), building.getDimZ())) {
                if (!caf$loggedOnce) {
                    caf$loggedOnce = true;
                    CAF_LOGGER.info("[CAF-LC] 大楼之间强制留一格空隙已生效: 多格建筑之间至少隔一个区块");
                }
                cir.setReturnValue(false);
                return;
            }

        } catch (Throwable t) {
            if (!caf$loggedOnce) {
                caf$loggedOnce = true;
                CAF_LOGGER.warn("[CAF-LC] 放置前置判定异常, 按失落城市原生处理: {}", t.toString());
            }
        }
    }
}
