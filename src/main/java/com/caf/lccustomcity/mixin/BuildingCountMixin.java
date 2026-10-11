package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.impl.MixinWorldAccess;
import com.caf.lccustomcity.impl.SlotQuota;
import com.caf.lccustomcity.impl.ZoneShare;
import com.caf.lccustomcity.stats.BuildingStats;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import net.minecraft.world.level.WorldGenLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 建筑数量上下限 + 建筑生成统计(挂在"挑建筑"这一步)。
 *
 * <h2>2026-10-04 整改: 旧的"16384 格位区域配额表"已删除</h2>
 * 旧实现为每个 (128x128 区块配额区域, 城市风格) 现建一张表: 扫 16384 个格位跑分区噪声,
 * 再对每栋配了数量的建筑各扫一遍 16384 格位、<b>装箱排序</b>候选槽位、建 16384 级别的 HashMap。
 * 玩家给 83 栋楼配了数量 ⇒ 一次就是几百毫秒, 缓存(64 条)满了还整体清空重算。
 * 更糟的是超上限时会<b>用派生随机再调一次被注入的方法</b>(换一栋楼), 虽然在热路径上有重入保护,
 * 但那种"在被注入的方法里再进被注入的方法"本身就不该留在区块生成里。
 *
 * <p>现在这里只剩两件 O(1) 的事:
 * <ol>
 *   <li><b>下限(软)</b>: 单格路径 —— 用 {@link SlotQuota} 判断"这个区块是不是被指定给某栋楼"的槽位
 *       ({@code 抽签值 x 候选格位数 < min}), 命中就直接返回那栋楼。
 *       判定 = 每个候选建筑一次哈希 + 两次 double 比较, 不建表、不排序、不缓存;</li>
 *   <li><b>统计</b>: 把最终选中的建筑 id 记一次数(见 {@link BuildingStats}, 一次哈希表查表 + LongAdder 自增)。</li>
 * </ol>
 *
 * <h2>上限去哪了</h2>
 * <ul>
 *   <li><b>多格建筑(玩家真正配了上限的那批)</b>: 移到 {@code MultiQuotaGate}, 在
 *       {@code MultiChunk.canPlaceBuilding} 的 HEAD 处拦 —— 超名额的位置直接否决,
 *       失落城市自己的尝试循环会继续试别的; 不重入、不递归、不建表, 而且是<b>硬上限</b>
 *       (一个配额区域里允许的格位数期望正好是 max)。</li>
 *   <li><b>单格建筑</b>: 这里是<b>软上限</b> —— 超出名额也不换楼(换楼必须再调一次被注入的方法,
 *       宁可功能弱一点也不能卡)。玩家当前配置里单格建筑只有 1 栋且没配上限, 不受影响。</li>
 * </ul>
 *
 * <h2>与生成顺序无关(确定性红线)</h2>
 * 抽签值 = mix64(世界种子, 建筑 id, 区块坐标) 的纯函数, 不用任何无种子随机,
 * 不看生成顺序, 不写任何跨区块共享计数器。同一世界同一区块永远得到同一个结果。
 *
 * <h2>世界上下文从哪来</h2>
 * {@code getRandomBuilding} 的签名里没有 world/provider, 由 {@link MixinWorldAccess} 转发
 * (选建筑之前失落城市一定先算过这个区块的城市风格, 那里能拿到 provider)。
 * 拿不到(如 GUI 预览)或这个风格没配数量时, 直接放行失落城市原生行为。
 *
 * <h2>兜底</h2>
 * require=0 + 全流程 try/catch: 注入点对不上、注册表没就绪、任何异常都只是"本功能不生效",
 * 结果等价于失落城市原生行为, 绝不把异常抛回区块生成。
 */
@Mixin(CityStyle.class)
public abstract class BuildingCountMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-CountMixin");

    /** 只打一次日志, 免得每区块刷屏。 */
    private static boolean caf$loggedForceOnce = false;
    private static boolean caf$loggedErrorOnce = false;

    // ==================================================================
    // 注入点
    // ==================================================================

    /** 单格建筑: 先判下限(可能改派), 再记统计。 */
    @Inject(method = "getRandomBuilding(Ljava/util/Random;Lmcjty/lostcities/varia/ChunkCoord;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void caf$countLimitSingle(java.util.Random random, ChunkCoord pos,
                                      CallbackInfoReturnable<String> cir) {
        var provider = MixinWorldAccess.provider();
        if (provider != null) {
            var cell = com.caf.lccustomcity.impl.CityLayout.cell(pos, provider, provider.getProfile());
            if (cell != null) {
                if (cell.placement() != null && !cell.placement().building().multi()) {
                    cir.setReturnValue(cell.placement().building().id());
                    caf$recordStats(cir.getReturnValue(), false);
                }
                // Legacy LC requests a building asset even when couldHaveBuilding is false.
                // Multi-only residential/outer styles therefore need a valid unused cell asset.
                else if (cir.getReturnValue() == null) {
                    cir.setReturnValue(com.caf.lccustomcity.impl.LayoutPlacement.fallbackSingleId());
                }
                return;
            }
        }
        if(cir.getReturnValue()==null&&com.caf.lccustomcity.config.LayoutCatalog.current().active()){
            // LC also queries an unused building type outside the city circle.
            cir.setReturnValue(com.caf.lccustomcity.impl.LayoutPlacement.fallbackSingleId());
            return;
        }
        caf$applyQuota(pos, cir);
        caf$recordStats(cir.getReturnValue(), false);
    }

    /**
     * 多格建筑: 只记统计。
     *
     * <p>不改返回值(所以不需要 cancellable): 多格建筑的"下限/上限"都不在这里做 ——
     * 多格是在"多格块"层面一次性挑一批楼再逐个找位置, 在这层改返回值会把整块填成同一栋楼;
     * 上限改由放置判定处否决(见类注释)。
     */
    @Inject(method = "getRandomMultiBuilding(Ljava/util/Random;Lmcjty/lostcities/varia/ChunkCoord;)Ljava/lang/String;",
            at = @At("RETURN"), require = 0)
    private void caf$countStatsMulti(java.util.Random random, ChunkCoord pos,
                                     CallbackInfoReturnable<String> cir) {
        caf$recordStats(cir.getReturnValue(), true);
    }

    /**
     * 建筑生成次数统计的挂钩点(见 {@link BuildingStats})。
     *
     * <p>为什么挂在这里: 失落城市"挑建筑"只有这两条路(单格 / 多格), 返回值就是最终选中的
     * 建筑 id; 放在配额逻辑<b>之后</b>才能记到"换过楼"之后的真实结果。
     *
     * <p>成本: 一次 ConcurrentHashMap 查表 + 一次 LongAdder 自增, 无字符串拼接、无日志、
     * 无排序({@link BuildingStats#record} 内部 try/catch 兜底, 出错不会影响区块生成)。
     */
    private static void caf$recordStats(String buildingId, boolean multi) {
        BuildingStats.record(buildingId, multi);
    }

    // ==================================================================
    // 下限(软): 指定槽位
    // ==================================================================

    /**
     * 单格建筑的下限判定: 这个区块是不是"被指定"给某栋楼的槽位, 是就直接返回它。
     *
     * <p>成本: 配了数量的建筑个数次哈希(玩家当前配置下, 单格风格里只有个位数的建筑),
     * 每次哈希约几十纳秒 —— 相比旧实现的"16384 格位扫描 + 装箱排序"是常量级。
     */
    private void caf$applyQuota(ChunkCoord pos, CallbackInfoReturnable<String> cir) {
        try {
            CityStyle self = (CityStyle) (Object) this;
            String styleName = self.getName();
            // ★ 单格路径只查单格限制表 ★
            // 混着查会把一个多格建筑 id 塞进 getRandomBuilding 的返回值, 失落城市随后拿它去
            // 单格注册表查 → null → BuildingRE NPE 崩区块生成(旧版踩过)。
            Map<String, BuildingCountLimits.Limit> limits = BuildingCountLimits.forStyleSingle(styleName);
            if (limits.isEmpty()) {
                return;   // 这个风格没有单格数量配置 ⇒ 原生行为
            }
            WorldGenLevel world = MixinWorldAccess.world();
            if (world == null) {
                return;   // GUI 预览等没有世界的场合
            }
            double share = ZoneShare.shareOf(world, styleName);
            if (!(share > 0)) {
                return;   // 拿不到占比 ⇒ 不判定(绝不能当成占比 0)
            }
            String designated = caf$designated(limits, MixinWorldAccess.seed(),
                    pos.chunkX(), pos.chunkZ(), SlotQuota.effectiveSlots(share));
            if (designated != null) {
                if (!caf$loggedForceOnce) {
                    caf$loggedForceOnce = true;
                    CAF_LOGGER.info("[CAF-LC] 建筑数量下限已生效: 指定槽位按配额选楼(O(1) 判定, 不再建区域配额表)");
                }
                cir.setReturnValue(designated);
            }
        } catch (Throwable t) {
            if (!caf$loggedErrorOnce) {
                caf$loggedErrorOnce = true;
                CAF_LOGGER.warn("[CAF-LC] 建筑数量下限判定异常, 本区块按失落城市原生处理: {}", t.toString());
            }
        }
    }

    /**
     * 找这个区块被指定的那栋楼(可能没有)。
     *
     * <p>判据: 对每栋 {@code min > 0} 的楼算一次抽签值, 落在名额内
     * ({@code 抽签值 x 候选格位数 < min})的楼都是"指定候选"; 有多个时取抽签值最小的那个
     * (越大名额越"靠前"越优先), 抽签值完全相同才用 id 比大小定序 —— 保证与遍历顺序无关,
     * 也就是与区块生成顺序无关。
     *
     * @return 被指定的建筑 id; 没有返回 null
     */
    private static String caf$designated(Map<String, BuildingCountLimits.Limit> limits, long seed,
                                        int chunkX, int chunkZ, int slots) {
        String best = null;
        double bestClaim = Double.MAX_VALUE;
        for (Map.Entry<String, BuildingCountLimits.Limit> entry : limits.entrySet()) {
            BuildingCountLimits.Limit limit = entry.getValue();
            if (limit == null || limit.min() <= 0) {
                continue;   // 只有下限 > 0 的楼才会"指定槽位"
            }
            String id = entry.getKey();
            if (id == null) {
                continue;
            }
            double ticket = SlotQuota.ticket(seed, id, chunkX, chunkZ);
            if (!SlotQuota.within(ticket, slots, limit.min())) {
                continue;
            }
            double claim = ticket * slots;   // 越小 = 名次越靠前
            if (best == null || claim < bestClaim || (claim == bestClaim && id.compareTo(best) < 0)) {
                best = id;
                bestClaim = claim;
            }
        }
        return best;
    }
}
