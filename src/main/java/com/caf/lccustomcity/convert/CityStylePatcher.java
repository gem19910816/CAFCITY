package com.caf.lccustomcity.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 生成 citystyle 覆盖, 让城市里只会出现我们自己的建筑。
 *
 * <h2>为什么不能简单写一个只含建筑的 citystyle</h2>
 * 数据包覆盖是<b>整文件替换</b>, 不是合并。如果直接写
 * {@code {"selectors":{"buildings":[...]}}}, 就会把失落城市原本 {@code citystyle_common}
 * 里的 {@code streetblocks}/{@code parkblocks}/{@code corridorblocks}/{@code generalblocks}
 * 全部抹掉 —— 马路、公园、走廊、铁路都会生成异常。
 *
 * <h2>为什么不能靠 inherit 继承</h2>
 * {@code CityStyle} 解析 inherit 时, 列表字段用的是 {@code addAll}(并集),
 * 继承 {@code citystyle_common} 会把它的 8 个原版建筑一并带进来, 达不到"只有我的建筑"。
 *
 * <h2>正确做法</h2>
 * 从失落城市的 jar 里<b>读出原始的 citystyle JSON</b>, 只替换其中
 * {@code selectors.buildings} 与 {@code selectors.multibuildings} 两个列表, 其余原样输出。
 * 这样既保留街道/公园等全部配置, 又能随失落城市版本升级自动适应 —— 不需要在本模组里
 * 硬编码任何失落城市的资产内容。
 *
 * <h2>为什么只处理"列表非空"的城市风格</h2>
 * 纯靠 inherit 的风格(如 {@code citystyle_standard}/{@code citystyle_desert})自己没有建筑列表,
 * 它们的建筑来自被我们覆盖过的 {@code citystyle_common}, 所以无需重复处理。
 * 而 {@code citystyle_border} 自带 5 个原版建筑, 必须处理, 否则原版建筑一定会漏出来。
 *
 * <h2>为什么要额外清空 fronts 与 parks</h2>
 * 失落城市的 citystyle 一共九个 selectors, 其中两个会让"原版内容"直接出现在城市里:
 * <ul>
 *   <li>{@code fronts} → 原版门脸部件, 会贴在玩家建筑的临街面上
 *       (见 {@code BuildingInfo:1111}, 由 profile 的 {@code buildingFrontChance} 触发)</li>
 *   <li>{@code parks} → 公园, 其中 {@code park_building}/{@code park_building2}
 *       是<b>完整的原版建筑</b></li>
 * </ul>
 * 其余七个({@code bridges}/{@code largebridges}/{@code stairs}/{@code fountains}/
 * {@code raildungeons})属于街道设施, 按用户要求<b>保持原样不动</b>。
 *
 * <p>注意 {@code citystyle_border} <b>也自带</b> {@code fronts} 与 {@code parks},
 * 所以只清空 {@code citystyle_common} 是不够的 —— 城市外围那一圈照样会漏出原版内容。
 * 好在这两个文件本来就是本类要处理的对象, 不需要额外扩大范围。
 *
 * <p>清空成空数组是安全的: {@code Tools.getRandomFromList} 对空列表返回 {@code null},
 * {@code RegistryAssetRegistry.getOrWarn} 对 {@code null} 直接返回且不打警告,
 * 而 {@code frontType}/{@code parkType} 本来就会被失落城市自己赋成 {@code null}
 * (用到的地方都显式判空)。效果等同于"这个地块没有门脸 / 没有公园"。
 */
public final class CityStylePatcher {

    /** 在失落城市 jar 里读取 citystyle 的路径。 */
    private static final String CITYSTYLE_DIR = "data/lostcities/lostcities/citystyles/";
    /** 写进我们数据包时的路径 —— 注意要去掉开头的 {@code data/}, 因为 Output 的键已经是从 data/ 之后算起。 */
    private static final String CITYSTYLE_WRITE_DIR = "lostcities/lostcities/citystyles/";

    /**
     * 失落城市 jar 里 worldstyle 的路径(相对 zip 根)。我们的平坦城市配置档用
     * {@code "worldStyle": "standard"}(caf_flat_sea_profile.json), 所以只需要覆盖
     * {@code standard.json}; {@code standard_everywhere.json} 也有 areasize, 但我们的
     * 配置档不会用到它, 不动。
     */
    private static final String WORLDSTYLE_ENTRY = "data/lostcities/lostcities/worldstyles/standard.json";
    /** 写进数据包时的路径(Output 的键从 data/ 之后算起)。 */
    private static final String WORLDSTYLE_WRITE_PATH = "lostcities/lostcities/worldstyles/standard.json";

    /**
     * 内城子分区城市风格的名字前缀(如 {@code caf_zone_inner_a1b2c3d4})。
     * 必须和 CityStyleZoneMixin 枚举注册表时用的前缀一致 —— Mixin 就靠这个前缀找到它们。
     */
    public static final String ZONE_STYLE_PREFIX = "caf_zone_inner_";

    /**
     * 子分区风格(以及基座兜底)用的 style 名 —— 失落城市 jar 里确实存在的
     * {@code data/lostcities/lostcities/styles/standard.json}。
     *
     * <h2>为什么必须显式写 style</h2>
     * 失落城市的 {@code citystyle_common} 是个"基座", 本身<b>没有 style 字段</b>
     * (靠被 {@code citystyle_standard}/{@code citystyle_border} 继承来用)。
     * 而 {@code BuildingInfo.createPalette} 会对建筑的城市风格执行
     * {@code AssetRegistries.STYLES.getOrThrow(world, getCityStyle().getStyle())},
     * style 为 null 会直接抛异常 → 区块生成失败。
     * 所以凡是被 {@code City.getCityStyleInt}(含我们的 Mixin)返回给建筑用的风格,
     * 都必须带一个非空 style。建筑自己的 refpalette 会盖在 style 调色板的上层
     * ({@code new CompiledPalette(stylePalette, buildingPalette)}), 写 style 不影响建筑外观。
     */
    private static final String DEFAULT_STYLE = "standard";

    private CityStylePatcher() {
    }

    /**
     * @param lcJar 失落城市模组 jar 的路径
     * @param out   建筑转换结果(提供建筑 id 列表, 并且接收覆盖后的 citystyle)
     * @return 被覆盖的城市风格文件名列表
     */
    public static List<String> patch(Path lcJar, LcAssetGenerator.Output out, Logger log) throws IOException {
        List<String> patched = new ArrayList<>();
        JsonObject patchedCommon = null;   // 覆盖后的 citystyle_common, 留作子分区风格的模板
        if (lcJar == null || !java.nio.file.Files.isRegularFile(lcJar)) {
            log.error("[CAF-LC] 找不到失落城市的 jar ({}), 无法覆盖城市风格 —— 城市里仍会出现原版建筑", lcJar);
            return patched;
        }

        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            // ZipFile.stream() 的签名是 Stream<? extends ZipEntry>, 所以这里不能用 List<ZipEntry>
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().startsWith(CITYSTYLE_DIR) && e.getName().endsWith(".json"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();

            for (ZipEntry entry : entries) {
                String text;
                try (InputStream in = zip.getInputStream(entry)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }

                JsonObject root;
                try {
                    root = JsonParser.parseString(text).getAsJsonObject();
                } catch (Exception e) {
                    log.warn("[CAF-LC] 解析 {} 失败, 已跳过: {}", entry.getName(), e.getMessage());
                    continue;
                }

                JsonObject selectors = root.getAsJsonObject("selectors");
                if (selectors == null) {
                    continue; // 纯继承, 无需处理
                }
                boolean patchBuildings = isNonEmptyArray(selectors, "buildings");
                boolean patchMultis = isNonEmptyArray(selectors, "multibuildings");
                if (!patchBuildings && !patchMultis) {
                    continue; // 列表本来就是空的
                }

                String fileName = entry.getName().substring(CITYSTYLE_DIR.length());
                // 城区路由: 外城(border)用外城建筑, 内城(common)用内城建筑。
                // 外城没建筑时回退用内城的 —— 这样"没建外城文件夹"的玩家行为完全不变。
                boolean outerZone = fileName.equals("citystyle_border.json");
                List<String> singleIds = outerZone && !out.outerBuildingIds.isEmpty() ? out.outerBuildingIds : out.buildingIds;
                List<String> multiIdList = outerZone && !out.outerMultiIds.isEmpty() ? out.outerMultiIds : out.multiIds;

                if (singleIds.isEmpty()) {
                    log.warn("[CAF-LC] 没有单格建筑, {} 的 buildings 列表将被清空", entry.getName());
                }
                selectors.add("buildings", toSelectorArray(singleIds));
                // multibuildings 必须【无条件】写入, 不能只在"原版非空"时才替换:
                // 外城 citystyle_border 原版的 multibuildings 就是空数组,
                // 若按"只换非空"的旧逻辑, 外城的多格建筑(如仓库)永远进不了 border。
                selectors.add("multibuildings", toSelectorArray(multiIdList));

                // 去掉原版门脸和公园(见类注释)。这两个列表在 common 和 border 里都存在,
                // 所以两个文件都会被清一遍。其余 selectors 原样保留。
                selectors.add("fronts", new JsonArray());
                selectors.add("parks", new JsonArray());

                // No street selector replacement: VacantSurfaceMixin disables native streets.

                // 兜底: 基座 citystyle_common 本身没有 style 字段(见 DEFAULT_STYLE 注释)。
                // 凡是可能被 getCityStyleInt 返回给建筑用的风格, 都必须带非空 style;
                // 原版自带的(如 citystyle_border 的 standard_border)原样保留, 不覆盖。
                if (!root.has("style") || root.get("style").isJsonNull()
                        || root.get("style").getAsString().isBlank()) {
                    root.addProperty("style", DEFAULT_STYLE);
                }

                out.putExternal(CITYSTYLE_WRITE_DIR + fileName, root);
                patched.add(fileName);
                if (fileName.equals("citystyle_common.json")) {
                    patchedCommon = root;   // 注意: 后面生成子分区风格用 deepCopy, 这里不用先拷
                }
            }

            // ---- worldstyle 覆盖: 放大建筑占地上限 ----
            patchWorldStyleAreaSize(zip, out, log);
        }

        // ---- 内城子分区: 每个子分区复制一套城市风格, 供"连续分区" Mixin 按噪声图挑选 ----
        // 只换 selectors.buildings / multibuildings 为该子分区的建筑, 其余(街道路图纸、
        // 调色板、清空过的 fronts/parks)全部继承覆盖后的 citystyle_common。
        patched.addAll(generateSubZoneStyles(patchedCommon, out, log));

        if (patched.isEmpty()) {
            log.warn("[CAF-LC] 没有任何城市风格被覆盖 —— 城市里仍会出现原版建筑");
        } else {
            log.info("[CAF-LC] 已覆盖 {} 个城市风格的建筑列表: {}", patched.size(), String.join(", ", patched));
        }
        return patched;
    }

    /** 多格建筑密度: 每个大区域(areasize x areasize 区块)至少放几个多格建筑。原版 1。 */
    private static final int MULTI_MINIMUM = 40;
    /** 多格建筑密度: 每个大区域最多放几个。原版 5。 */
    private static final int MULTI_MAXIMUM = 80;
    /** 每个多格建筑的放置尝试次数(找不到合法位置就跳过这栋)。原版 50。 */
    private static final int MULTI_ATTEMPTS = 200;
    /** 多格建筑要有多少比例的格子在"正确分区"才允许放置。原版 0.8。 */
    private static final double MULTI_CORRECT_STYLE_FACTOR = 0.6;

    /**
     * 覆盖 worldstyle {@code standard.json}: <b>逐字复制失落城市原版</b>, 只改
     * {@code multisettings} 里的五个字段(areasize/minimum/maximum/attempts/correctstylefactor),
     * 其余字段一律不动。
     *
     * <h2>为什么必须连同 worldstyle 一起改 areasize</h2>
     * 失落城市 {@code MultiChunk} 里 {@code rand.nextInt(areasize - dimX + 1)} ——
     * 多格建筑的 dimX/dimZ 一旦超过 areasize 会直接崩(区块生成失败)。所以转换器把
     * 上限放大到 16 格(256x256)的同时, worldstyle 的 areasize 也必须放大到 16,
     * 两者必须永远一致(见 {@link LcAssetGenerator#MAX_CELLS} 的注释)。
     *
     * <h2>为什么还要调 minimum/maximum/attempts/correctstylefactor</h2>
     * {@code MultiChunk.calculateBuildings} 的逻辑: 每个 areasize x areasize 的大区域
     * (16x16=256 区块) 只放 minimum~maximum 个多格建筑, 其余区块全是单格。
     * 原版 1~5 个 → 250 来个区块全刷单格 —— 而本模组只有 1 个单格建筑(卫星楼),
     * 于是"大片卫星楼"。调成 40~80 个(以 2x2/3x3 建筑为主), 多格建筑就能尽量
     * 铺满整个区域, 单格变成少数。minimum 只是"目标数量",
     * 找不到合法位置会静默跳过, 不会报错, 所以定高一点是安全的。
     * (注意: 配合"大楼之间强制留一格空隙"规则后, 一个 16x16 区域实际放下的数量
     * 远低于 80, 多余的名额只是给尝试留余地。)
     * attempts 200: 区域里建筑多了以后空位难找(尤其留了空隙以后), 多给点尝试机会。
     * <b>correctstylefactor 0.8 -> 0.6 的权衡</b>: 原值要求多格建筑 80% 的格子落在
     * 同一个子分区(连续分区)里才放; 调低到 0.6 能放下更多, 但代价是多格建筑更容易
     * 跨分区边界(商业区边缘可能出现住宅楼的一角)。玩家可在游戏里看效果再调。
     *
     * <p>以上全是配置值, 不涉及随机数用法, 同一世界种子的生成结果仍然确定。
     *
     * <h2>为什么是"完整复制原版 + 只改这几个字段"</h2>
     * 数据包覆盖是<b>整文件替换</b>, 不是合并。自己拼一个只含 multisettings 的文件
     * 会把原版 standard.json 里的 scattered(散点建筑)/citybiomemultipliers/citystyles
     * 等全部抹掉, 失落城市用默认值填后会破坏城市生成 —— 和本类处理 citystyle 是同一个道理。
     *
     * <p>只覆盖 {@code standard.json}: 我们的配置档(caf_flat_sea_profile.json)用
     * {@code "worldStyle": "standard"}; {@code standard_everywhere.json} 虽然也有
     * areasize, 但配置档不会用到它, 保持原样。
     */
    private static void patchWorldStyleAreaSize(ZipFile zip, LcAssetGenerator.Output out, Logger log) {
        ZipEntry entry = zip.getEntry(WORLDSTYLE_ENTRY);
        if (entry == null) {
            // jar 里没有 standard.json(失落城市大改版?)——不覆盖, 上限仍是 160x160,
            // 超过的多格建筑会让区块生成失败, 所以必须响亮地警告。
            log.error("[CAF-LC] 失落城市 jar 里找不到 {}, 无法放大建筑占地上限 —— "
                    + "超过 160x160 的多格建筑仍会让区块生成失败", WORLDSTYLE_ENTRY);
            return;
        }
        String text;
        try (InputStream in = zip.getInputStream(entry)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[CAF-LC] 读取 {} 失败: {}", WORLDSTYLE_ENTRY, e.getMessage());
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            log.error("[CAF-LC] 解析 {} 失败, 已跳过 worldstyle 覆盖: {}", WORLDSTYLE_ENTRY, e.getMessage());
            return;
        }
        JsonObject multi = root.getAsJsonObject("multisettings");
        if (multi == null) {
            log.error("[CAF-LC] {} 里没有 multisettings, 已跳过 worldstyle 覆盖", WORLDSTYLE_ENTRY);
            return;
        }
        int oldSize = multi.has("areasize") ? multi.get("areasize").getAsInt() : -1;
        multi.addProperty("areasize", LcAssetGenerator.MAX_CELLS);
        multi.addProperty("minimum", MULTI_MINIMUM);
        multi.addProperty("maximum", MULTI_MAXIMUM);
        multi.addProperty("attempts", MULTI_ATTEMPTS);
        multi.addProperty("correctstylefactor", MULTI_CORRECT_STYLE_FACTOR);
        out.putExternal(WORLDSTYLE_WRITE_PATH, root);
        log.info("[CAF-LC] 已覆盖 worldstyle standard.json: multisettings.areasize {} -> {} (占地上限 {}x{} 方块), "
                        + "minimum={}, maximum={}, attempts={}, correctstylefactor={} (多格建筑密度, 治\"大片单格\")",
                oldSize, LcAssetGenerator.MAX_CELLS,
                LcAssetGenerator.MAX_CELLS * 16, LcAssetGenerator.MAX_CELLS * 16,
                MULTI_MINIMUM, MULTI_MAXIMUM, MULTI_ATTEMPTS, MULTI_CORRECT_STYLE_FACTOR);
    }

    private static boolean isNonEmptyArray(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            return false;
        }
        return !parent.getAsJsonArray(key).isEmpty();
    }

    /**
     * 为内城的每个子分区生成一套城市风格, 返回生成的文件名列表。
     *
     * <p>命名: {@code caf_zone_inner_<子分区名的短哈希>} —— 中文子分区名不能进资源路径,
     * 用短哈希既合法又稳定(同一子分区名永远得到同一个风格名, Mixin 枚举时按名字排序后
     * 下标稳定, 保证同一世界同一分区布局)。
     *
     * <p>建筑列表为空时回退用整个内城的列表, 防止某个子分区只有多格建筑(或反过来)时
     * 失落城市因 buildings 列表为空而崩("Invalid building for multibuilding")。
     */
    private static List<String> generateSubZoneStyles(JsonObject patchedCommon, LcAssetGenerator.Output out, Logger log) {
        List<String> generated = new ArrayList<>();
        // 门槛: 两张子分区表【都】为空才不生成。只查单格表(innerSubBuildingIds)会把
        // "全是多格建筑"的配置整体挡死 —— 分区城市风格一个都不生成, 运行时分区逻辑失效
        // (全城内城共用一个混池, 玩家看到"满城工业楼"就是这个原因)。
        if (patchedCommon == null || (out.innerSubBuildingIds.isEmpty() && out.innerSubMultiIds.isEmpty())) {
            return generated;   // 没有子分区文件夹(或 citystyle_common 没被覆盖) ⇒ 不生成
        }
        // 子分区集合 = 有单格建筑的子分区 ∪ 有多格建筑的子分区(见 Output.innerSubZoneNames)。
        // 只循环 innerSubBuildingIds 会漏掉"整个子分区全是多格建筑"的情况(那种子分区只在
        // innerSubMultiIds 里), 导致它们拿不到分区风格 —— 整个内城退化成只有一个分区,
        // 所有内城区块都刷那一个分区的建筑(用户看到"全是卫星楼"就是这个原因)。
        List<String> subZones = out.innerSubZoneNames();   // 已排序 ⇒ 生成结果稳定
        for (String subZone : subZones) {
            JsonObject zoneStyle = patchedCommon.deepCopy();
            // 深拷贝自 citystyle_common(基座, 无 style), 必须显式补 style,
            // 否则 BuildingInfo.createPalette 里 getOrThrow(null) 直接崩(见 DEFAULT_STYLE 注释)。
            zoneStyle.addProperty("style", DEFAULT_STYLE);
            JsonObject selectors = zoneStyle.getAsJsonObject("selectors");
            List<String> singles = out.innerSubBuildingIds.getOrDefault(subZone, List.of());
            List<String> multis = out.innerSubMultiIds.getOrDefault(subZone, List.of());
            if (singles.isEmpty()) {
                singles = out.buildingIds;      // 回退: 至少保证 buildings 非空
            }
            if (multis.isEmpty()) {
                multis = out.multiIds;
            }
            selectors.add("buildings", toSelectorArray(singles));
            selectors.add("multibuildings", toSelectorArray(multis));

            String fileName = ZONE_STYLE_PREFIX + LcAssetGenerator.shortHash(subZone) + ".json";
            out.putExternal(CITYSTYLE_WRITE_DIR + fileName, zoneStyle);
            generated.add(fileName);
            log.info("[CAF-LC] 内城子分区 '{}' -> 城市风格 {} (单格 {} 栋, 多格 {} 栋)",
                    subZone, fileName, singles.size(), multis.size());
        }
        return generated;
    }

    /** 构造 [{"factor":1.0,"value":"命名空间:id"}, ...]。 */
    private static JsonArray toSelectorArray(List<String> ids) {
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            JsonObject o = new JsonObject();
            o.addProperty("factor", 1.0f);
            o.addProperty("value", LcAssetGenerator.NS + ":" + id);
            arr.add(o);
        }
        return arr;
    }
}
