package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.SpawnCityPlan;
import com.caf.lccustomcity.impl.SpawnCityRule;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「保证出生点 N 格以内有一个城市<b>城心</b>」—— 玩家要的"在出生点半径 850 格刷新一个城心"
 * (旧口径"出生点 200 格内看到城市边缘"已废弃: 判定与摆放现在都量城心距离)。
 *
 * <p>本次只改了这行注释, <b>注入逻辑一个字都没动</b>(口径切换全在 impl/SpawnCityRule 里)。
 *
 * <h2>为什么挂在 {@code City.getCityFactor}</h2>
 * 失落城市里"这个区块算不算城市"只有一个判据: {@code BuildingInfo.isCityRaw} 比较
 * {@code City.getCityFactor(...) > CITY_THRESHOLD}。建筑、马路、地形削平、野外建筑回避
 * 全都从这一条推出来 —— 所以只要在浓度上做叠加, 补出来的城就和天然长出来的一模一样
 * (不需要另外去动 {@code isCityCenter}, 也就不可能改变既有城市的判定)。
 *
 * <h2>叠加规则(绝不影响既有城市)</h2>
 * <ul>
 *   <li>原生浓度<b>非 0</b> ⇒ <b>直接返回, 什么都不做</b>(连出生点都不读);</li>
 *   <li>原生浓度正好 0(荒野) ⇒ 才问 {@link SpawnCityRule} 要合成浓度, 有就提升它。</li>
 * </ul>
 * 判定本身也只看配置和失落城市的只读查询, 不写任何全局状态。
 *
 * <h2>与城市浓度记忆化的关系</h2>
 * {@code CityFactorCacheMixin} 也挂在同一个 RETURN 上, 但它<b>只缓存非 0 值</b>
 * (0 可能是"暂时拿不到高度图")。而这里只改"原生正好是 0"的区块 ——
 * 也就是缓存里永远没有条目的那些区块, 所以 HEAD 不会提前返回、这个 RETURN 一定会走到,
 * 两条注入谁先谁后结果都一样。
 *
 * <h2>兜底</h2>
 * {@code require = 0} + 内部 try/catch: 注入点对不上(失落城市换版本)或者判定出任何异常,
 * 都只是"这个功能不生效", 结果永远等价于失落城市原生行为, 不崩游戏。
 */
@Mixin(City.class)
public abstract class SpawnCityMixin {

    /**
     * 诊断用日志器。⚠️ <b>mixin 类里的静态字段必须是 private</b>
     * (Sponge Mixin 硬性规定, 违反会让整个模组加载失败、游戏崩溃), 所以下面三个字段都是 private。
     */
    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-SpawnCityMixin");

    /** "生效一次"和"出错一次"各只写一行日志(热路径上绝不能每次记日志)。 */
    private static final AtomicBoolean CAF_LOGGED_OK = new AtomicBoolean(false);
    private static final AtomicBoolean CAF_LOGGED_FAIL = new AtomicBoolean(false);

    /**
     * 原生浓度算完(任何一条 return 之前)之后叠加"合成城市"的浓度。
     *
     * <p>{@code @At("RETURN")} 会匹配方法里<b>所有</b> return(预定义建筑/街道的 1.0f、
     * 高度图判定失败的 0、最后的 {@code Math.min(Math.max(factor,0),1)}),
     * 所以这里只要盯住"返回值正好是 0"就够了。
     */
    @Inject(method = "getCityFactor(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)F",
            // ⚠️ cancellable = true 绝不能少: 这个注入要在 RETURN 处调用 cir.setReturnValue(...),
            // 没声明 cancellable 时那一句会抛 CancellationException("The call getCityFactor is not
            // cancellable."), 被下面的 catch 吞掉 ⇒ 整块区域的合成城浓度叠加静默失效(城缺一块)。
            // 真机日志(2026-10-09)抓到的就是这个。RETURN 上只 getReturnValue() 不改返回值的注入
            // (如 CityFactorCacheMixin 的 RETURN 分支)才不需要 cancellable。
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void caf$spawnCityFactor(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                            CallbackInfoReturnable<Float> cir) {
        try {
            float nativeFactor = cir.getReturnValueF();
            if (nativeFactor != 0.0f) {
                return;         // 这里已经有城市在起作用 ⇒ 一个字都不改
            }
            float extra = SpawnCityRule.factorFor(coord, provider, profile);
            float merged = SpawnCityPlan.apply(nativeFactor, extra);
            if (merged != nativeFactor) {
                if (CAF_LOGGED_OK.compareAndSet(false, true)) {
                    CAF_LOGGER.info("[CAF-LC] 出生点附近城市已生效: 区块({}, {}) 的城市浓度 {} -> {}",
                            coord.chunkX(), coord.chunkZ(), nativeFactor, merged);
                }
                cir.setReturnValue(merged);
            }
        } catch (Throwable t) {
            if (CAF_LOGGED_FAIL.compareAndSet(false, true)) {
                CAF_LOGGER.warn("[CAF-LC] 出生点附近城市叠加异常, 按失落城市原生处理: {}", t.toString());
            }
        }
    }
}
