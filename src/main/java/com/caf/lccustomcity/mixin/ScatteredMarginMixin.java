package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.ScatteredMarginConfig;
import com.caf.lccustomcity.impl.ScatteredMargin;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.gen.Scattered;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「野外建筑只在城市 N 格以外生成」—— 离城市太近的地方不放野外原版建筑。
 *
 * <h2>这是干嘛的</h2>
 * 失落城市原版会在<b>非城市区块</b>随机放 5 种野外建筑(radiotower 无线电塔 /
 * cabin 小木屋 / oilrig 钻井平台 / highway_gas_station 加油站 / highway_restaurant
 * 公路餐厅), 它们可能刷在紧贴城市边缘的地方。本 Mixin 让玩家可以要求:
 * 离城市(城心半径 + 额外距离)以内一律不放, 只在城市 N 格以外才允许。
 *
 * <h2>挂点</h2>
 * 挂在 {@code Scattered.avoidScattered} 的 HEAD 上(返回 true = 这个区块不放野外建筑)。
 * 它是 {@code calculateTerrainStats} 对每个候选区块调用的; 我们在原生判定之前先查
 * "离城市是否太近", 太近就直接把返回值改成 true(= 否决放置), 和原生"这是城市/桥/公路"
 * 走的是同一条安静跳过路径。
 *
 * <h2>判定逻辑</h2>
 * 全部在 {@link ScatteredMargin} 里(照抄 {@code City.getCityFactor} 的城心遍历)。
 * {@code feature.provider} / {@code info.profile} / {@code info.coord} 都是 public 字段
 * (已核对: LostCityTerrainFeature.java 第 86 行, BuildingInfo.java 第 41/43 行)。
 *
 * <h2>开关</h2>
 * {@code config/失落城市建筑/野外建筑离城市距离.json} 里的
 * 「野外建筑距城市中心的额外距离」, 默认 200, 0 = 关闭(见 {@code ScatteredMarginConfig})。
 *
 * <h2>确定性 / 兜底</h2>
 * require=0 + 判定内部 try/catch: 注入失败或任何异常都只是"本规则不生效",
 * 退回失落城市原生行为, 不崩游戏。
 */
@Mixin(Scattered.class)
public abstract class ScatteredMarginMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-ScatteredMarginMixin");
    private static boolean caf$loggedOnce = false;

    @Inject(method = "avoidScattered", at = @At("HEAD"), cancellable = true, require = 0)
    private static void caf$avoidNearCity(LostCityTerrainFeature feature, BuildingInfo info,
                                          CallbackInfoReturnable<Boolean> cir) {
        try {
            int margin = ScatteredMarginConfig.distance();
            if (margin > 0 && ScatteredMargin.nearCity(feature.provider, info.profile, info.coord, margin)) {
                if (!caf$loggedOnce) {
                    caf$loggedOnce = true;
                    CAF_LOGGER.info("[CAF-LC] 野外建筑离城市距离已生效: 城市 {} 格以外才允许放野外建筑", margin);
                }
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            if (!caf$loggedOnce) {
                caf$loggedOnce = true;
                CAF_LOGGER.warn("[CAF-LC] 野外建筑离城市距离判定异常, 按失落城市原生处理: {}", t.toString());
            }
        }
    }
}
