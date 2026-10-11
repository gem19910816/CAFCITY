package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.CityFactorCache;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给失落城市的 {@code City.getCityFactor} 加一层记忆化缓存(卡顿事故的主修之一)。
 *
 * <h2>为什么挂在 getCityFactor</h2>
 * 这个方法每次调用都要暴力扫 {@code (CITY_MAXRADIUS + 15) / 16} 区块的窗口
 * (本模组 CITY_MAXRADIUS = 1000 方块 ⇒ 63 区块 ⇒ <b>16129 个候选区块</b>, 实测单次约 0.36ms)。
 * 而失落城市放多格建筑时, 一个 16x16 区块的多格区域会对每个建筑、每次放置尝试的每个占地格
 * 各调一次 —— 上万次调用全压在一个区块的生成里(实测 Max 27809ms)。同一个区域里反复查的是
 * 同样那几百个坐标, 缓存命中率 &gt;99%。
 *
 * <h2>为什么是"命中即返回 + 返回时存"两个注入</h2>
 * 不改失落城市自己的代码, 只在两端加钩子:
 * <ul>
 *   <li>HEAD: 命中缓存就 {@code cir.setReturnValue(...)} 取消原生计算;</li>
 *   <li>RETURN: 把原生算出来的值交给缓存({@link CityFactorCache} 内部只收非零值,
 *       详见那边的类注释 —— 0 可能是"暂时拿不到高度图", 缓存了会让区块永远不成城市)。</li>
 * </ul>
 *
 * <h2>失效</h2>
 * 再挂一个 {@code City.cleanCache()} 的 RETURN: 失落城市改配置档 / 切换世界时清掉自己的
 * 城市风格缓存和预定义城市表, 我们跟着一起清。
 *
 * <h2>兜底</h2>
 * 全部 {@code require = 0} + 缓存内部 try/catch: 注入点对不上、或者缓存出任何错,
 * 都只是"没有缓存", 结果永远等价于失落城市原生行为, 不会崩游戏。
 */
@Mixin(City.class)
public abstract class CityFactorCacheMixin {

    /**
     * 诊断用日志器。⚠️ <b>mixin 类里的静态字段必须是 private</b>
     * (Sponge Mixin 硬性规定, 违反会让整个模组加载失败、游戏崩溃 —— 本项目已崩过一次),
     * 所以这里两个字段都是 private final。这里用全限定名就是为了少两行 import。
     */
    private static final org.slf4j.Logger CAF_LOGGER =
            org.slf4j.LoggerFactory.getLogger("CAF-LC-CityFactorCache");

    /**
     * "只打印一次"开关: 用来在真机上<b>确认这套记忆化到底挂上了没有</b>。
     *
     * <p>动机: 这三个注入点都是 {@code require = 0} —— 最坏情况是"静默不生效"(游戏不崩, 但也没加速),
     * 那时候光看行为分不清"没挂上"还是"挂上了但这里不是瓶颈"。所以第一次走到任一条路径时
     * 就往日志里写一行, 之后不再写(这是个会被调用上万次的热路径, 绝不能每次记日志)。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean CAF_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 只打印一次的小工具。 */
    private static void caf$logOnce(String msg) {
        if (CAF_LOGGED.compareAndSet(false, true)) {
            CAF_LOGGER.info("[CAF-LC] 城市浓度记忆化: {}", msg);
        }
    }

    /** 命中缓存 ⇒ 直接返回, 不跑原生 16129 个候选区块的扫描。 */
    @Inject(method = "getCityFactor(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)F",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void caf$factorFromCache(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                            CallbackInfoReturnable<Float> cir) {
        Float cached = CityFactorCache.lookup(provider, profile, coord);
        if (cached != null) {
            // 能命中 ⇒ 说明"存"和"查"两条路都通了(最理想的一条证据), 而且省下了整圈扫描。
            caf$logOnce("已生效(缓存命中省下整圈扫描), 例: 区块(" + coord.chunkX() + "," + coord.chunkZ() + ") -> " + cached);
            cir.setReturnValue(cached);
        }
    }

    /**
     * 原生算完(任何一条 return 之前)把值交给缓存。
     *
     * <p>{@code @At("RETURN")} 会匹配方法里<b>所有</b> return: 预定义建筑/街道的 1.0f、
     * 高度图判定失败的 0、以及最后那个 {@code Math.min(Math.max(factor,0),1)}。
     */
    @Inject(method = "getCityFactor(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)F",
            at = @At("RETURN"), require = 0)
    private static void caf$factorToCache(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                          CallbackInfoReturnable<Float> cir) {
        // 走了这条 ⇒ 至少"写入缓存"这个钩子挂上了(还没证明"命中"那条)。
        caf$logOnce("写入钩子已生效(还没出现过缓存命中), 例: 区块(" + coord.chunkX() + "," + coord.chunkZ() + ") -> " + cir.getReturnValueF());
        CityFactorCache.store(provider, profile, coord, cir.getReturnValueF());
    }

    /** 失落城市清自己的缓存时(改配置档 / 世界切换), 我们的因子缓存必须跟着清。 */
    @Inject(method = "cleanCache()V", at = @At("RETURN"), require = 0)
    private static void caf$clearFactorCache(CallbackInfo ci) {
        CityFactorCache.clear();
        com.caf.lccustomcity.impl.CityLayout.clear();
        com.caf.lccustomcity.impl.SpawnCityRule.clear();
    }
}
