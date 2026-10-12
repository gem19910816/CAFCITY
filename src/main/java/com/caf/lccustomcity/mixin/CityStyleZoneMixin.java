package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.config.ZoneWeights;
import com.caf.lccustomcity.impl.MixinWorldAccess;
import com.caf.lccustomcity.impl.ZoneNoise;
import com.caf.lccustomcity.impl.ZoneNormalize;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.CustomRegistries;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.CityStyle;
import mcjty.lostcities.worldgen.lost.regassets.CityStyleRE;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.WorldGenLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阶段 2a: 连续城区分区。
 *
 * <h2>失落城市原本的机制(源码 City.getCityStyleInt)</h2>
 * 每个区块算一个 factor(0=城市最外圈, 1=正中心):
 * <ul>
 *   <li>{@code factor < CITY_STYLE_THRESHOLD} → 用 {@code CITY_STYLE_ALTERNATIVE}(我们的外城 citystyle_border)</li>
 *   <li>否则 → 用 citystyle_common(我们的内城)</li>
 * </ul>
 * 也就是说原版<b>只认"离中心远近"这一刀</b>, 内城里只有一种风格, 谈不上再分商业区/住宅区。
 *
 * <h2>本 Mixin 的做法</h2>
 * 在 {@code getCityStyleInt} 返回后再判一次:
 * <ol>
 *   <li>返回的风格 == 外城风格(CITY_STYLE_ALTERNATIVE) → <b>不动</b>, 外城维持原样;</li>
 *   <li>否则(内城) → 用一张<b>以世界种子为种子的二维 Perlin 噪声图</b>把内城切成 N 片
 *       (N = 数据包里子分区城市风格的个数), 每片锁定一个子分区城市风格。</li>
 * </ol>
 * 噪声值是连续的 ⇒ 同一个子分区自然"连成一片", 这就是需求文档定的"连续分区"。
 *
 * <h2>子分区城市风格从哪来</h2>
 * 数据层(CityStylePatcher)会为 {@code CAF失落城市配置\内城\<子分区>\} 的每个子分区生成一个
 * 名为 {@code caf_zone_inner_<哈希>} 的城市风格(复制 citystyle_common, 只换建筑列表)。
 * 本 Mixin <b>直接从失落城市的 citystyles 注册表里按前缀枚举</b>这些风格,
 * 所以新增/删除子分区文件夹后只需重启游戏, Mixin 代码不用改。
 *
 * <h2>确定性</h2>
 * 噪声只用世界种子 + 区块坐标, 不碰任何 {@code new Random()}(无种子);
 * 同一世界同一区块永远分到同一个子分区, 符合失落城市"确定性生成"的要求。
 *
 * <h2>比例控制(阶段 2b: 按占比切不等宽段)</h2>
 * 噪声值域 [0,1) 按各子分区的"占比"切成不等宽段(见 {@link ZoneWeights#pickIndex}):
 * 占比是相对权重 —— 各城区占比 ÷ 总和 = 实际面积比例, 写 3:1 和 0.75:0.25 等价。
 * 某个子分区没配占比时权重按 1.0(均分); 所有子分区都没配时自然退化成完全均分。
 * 占比区间 [min,max] 在转换期已取中点折叠成单个 double 装进 {@link ZoneWeights},
 * 这里只做纯查表, 分区结果 = (世界种子, 区块坐标, 占比配置) 的纯函数。
 */
@Mixin(City.class)
public abstract class CityStyleZoneMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-ZoneMixin");

    /** 子分区城市风格的名字前缀, 必须和 CityStylePatcher 生成时用的前缀一致。 */
    private static final String ZONE_PREFIX = "caf_zone_inner_";

    /**
     * 噪声波长(区块数): 大致相当于"一片分区的直径"。
     * 城市半径配置是 50~128 区块, 取 24 ⇒ 一座城大约出现 2~5 片分区, 不会太碎。
     */
    private static final double ZONE_WAVELENGTH = 24.0;

    /** fBm 叠加层数: 越多分区边界越曲折。 */
    private static final int ZONE_OCTAVES = 3;

    private static boolean caf$loggedOnce = false;

    @Inject(method = "getCityStyleInt(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)Lmcjty/lostcities/worldgen/lost/cityassets/CityStyle;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void caf$continuousZones(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                            CallbackInfoReturnable<CityStyle> cir) {
        // 顺手把世界上下文转交给"建筑数量上下限" Mixin(getRandomBuilding 的签名里拿不到 provider)。
        // 失落城市选建筑前一定先算城市风格, 所以这里记录后 BuildingCountMixin 必定能读到。
        MixinWorldAccess.record(provider);
        if (com.caf.lccustomcity.impl.CityLayout.enabled(provider, profile)) {
            var district = com.caf.lccustomcity.impl.CityLayout.district(coord, provider, profile);
            if (district != null) cir.setReturnValue(AssetRegistries.CITYSTYLES.getOrThrow(provider.getWorld(), district.style()));
            return;
        }
        CityStyle original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        WorldGenLevel world = provider.getWorld();
        if (world == null) {
            return;   // GUI 预览等没有世界的场合, 不动
        }

        // 外城(factor < 阈值 → CITY_STYLE_ALTERNATIVE)直接放行, 不参与内城再分区。
        String outerStyle = profile.CITY_STYLE_ALTERNATIVE;
        if (outerStyle != null && !outerStyle.isEmpty() && outerStyle.equals(original.getName())) {
            return;
        }

        // 枚举数据包里所有子分区城市风格(名字带我们的前缀)。没有 ⇒ 玩家没用子分区文件夹,
        // 一切照旧(等价于本 Mixin 不存在)。
        List<String> zoneStyles = caf$findZoneStyles(world);
        if (zoneStyles.isEmpty()) {
            return;
        }
        // 原返回值如果已经是某个子分区风格(理论上不会发生), 也别再套一层。
        if (original.getName() != null && original.getName().startsWith(ZONE_PREFIX)) {
            return;
        }

        int idx = caf$pickZoneIndex(provider.getSeed(), coord.chunkX(), coord.chunkZ(), zoneStyles);
        CityStyle zoned = AssetRegistries.CITYSTYLES.get(world, zoneStyles.get(idx));
        if (zoned == null) {
            return;   // 注册表里没看到(理论上不该发生), 保守起见不动
        }
        if (!caf$loggedOnce) {
            caf$loggedOnce = true;
            CAF_LOGGER.info("[CAF-LC] 连续城区分区已启用: 内城按噪声图分成 {} 片 ({})",
                    zoneStyles.size(), String.join(", ", zoneStyles));
        }
        cir.setReturnValue(zoned);
    }

    /** 从失落城市的 citystyles 注册表里找出所有子分区风格名(排序, 保证下标稳定)。 */
    private static List<String> caf$findZoneStyles(WorldGenLevel world) {
        List<String> names = new ArrayList<>();
        try {
            Registry<CityStyleRE> reg = world.registryAccess().registryOrThrow(CustomRegistries.CITYSTYLES_REGISTRY_KEY);
            for (ResourceKey<CityStyleRE> key : reg.registryKeySet()) {
                String path = key.location().getPath();
                if (path.startsWith(ZONE_PREFIX)) {
                    // getName() 对 lostcities 命名空间只返回路径部分(DataTools.toName),
                    // 所以这里记录路径即可, 后面 AssetRegistries.CITYSTYLES.get 也是按这个名字找。
                    names.add(path);
                }
            }
        } catch (Throwable t) {
            // 注册表不可用(极早期调用等) ⇒ 静默退回原版行为
            return List.of();
        }
        Collections.sort(names);
        return names;
    }

    /**
     * 把噪声值映射成子分区下标。
     *
     * <p>按 {@link ZoneWeights} 里各子分区的占比权重把 [0,1) 切成不等宽段,
     * 噪声值落在哪段就选哪个城区 —— 权重越大的城区面积越大。
     * 没配占比的子分区权重按 1.0(均分), 全部没配时等价于均分。
     */
    private static int caf$pickZoneIndex(long seed, int chunkX, int chunkZ, List<String> zoneStyles) {
        double raw = ZoneNoise.forSeed(seed)
                .fbm01(chunkX / ZONE_WAVELENGTH, chunkZ / ZONE_WAVELENGTH, ZONE_OCTAVES);
        // 阶段 2b 补丁(第二版): fBm 噪声实测挤在约 [0.19, 0.78] 之间(不是均匀分布),
        // 直接切段会让排在尾巴的城区(工业区/军事区)几乎摸不到, 先过一遍按当前世界种子
        // 自校准的经验 CDF 均匀化映射(详见 ZoneNormalize 类注释)。
        // 送进 pickIndex 的就是这个 v, 全程只有这一处用到它, 不存在两处不一致。
        double v = ZoneNormalize.uniform(seed, raw);
        return ZoneWeights.pickIndex(v, zoneStyles);
    }
}
