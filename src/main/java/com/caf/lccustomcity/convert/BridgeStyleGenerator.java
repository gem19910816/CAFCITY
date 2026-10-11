package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.config.BridgeStyleConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把失落城市<b>跨水街道桥</b>的路面换成我们自己的路面风格(和内城马路同一套材料)。
 *
 * <h2>一、玩家看到的那座桥到底是哪张图纸(已逐行核实, 附出处)</h2>
 * <ol>
 *   <li><b>候选只有三张</b>: {@code parts/bridge_open.json} / {@code parts/bridge_covered.json} /
 *       {@code parts/bridge_large_open.json}。它们由城市风格的 {@code selectors.bridges}
 *       ({@code bridge_open} + {@code bridge_covered}) 和 {@code selectors.largebridges}
 *       ({@code bridge_large_open}) 挑出来 ——
 *       见 {@code citystyles/citystyle_common.json:122-137} 与 {@code citystyle_border.json:57-66}。
 *       本模组<b>刻意没有动这两个 selector</b>({@code CityStylePatcher} 的类注释明确写着
 *       "bridges/largebridges ... 保持原样不动"), 所以桥还是原版图纸, 材料也就不搭。</li>
 *   <li><b>真正会生成的是 {@code bridge_large_open}</b>。失落城市
 *       {@code BuildingInfo.java:990-998} 只有在街道生成模式是
 *       {@code StreetGenerationMode.HIERARCHICAL_GRID_V1} 时才去取
 *       {@code cs.getRandomLargeBridge(...)} 填 {@code largeBridgeType};
 *       其他模式 {@code largeBridgeType = null}。
 *       而本模组的配置档 {@code caf_flat_sea_profile.json:149} 正是
 *       {@code "streetGenerationMode": "HIERARCHICAL_GRID_V1"}。</li>
 *   <li>模式对上以后, {@code HierarchicalBridgePlanner.getBridgeInfo} 专门为
 *       "主干道路网(primary road)在真实水面上的缺口" 规划桥段(见该类的类注释:
 *       "Deterministically resolves short water gaps along the raw primary-road field"),
 *       然后 {@code BuildingInfo.java:1561}/{@code :1633} 用
 *       {@code endpoint.largeBridgeType != null ? largeBridgeType : bridgeType}
 *       铺整段桥 —— largeBridgeType 非空(选择器里有 {@code bridge_large_open}, factor 1.0),
 *       所以铺的就是 {@code bridge_large_open}。
 *       而 {@code bridge_open}/{@code bridge_covered} 只在旧的
 *       {@code xBridge}/{@code zBridge} 分支里用到, 那个分支在 HIERARCHICAL 模式下
 *       直接就 return 了(见 {@code calculateXBridge}/{@code calculateZBridge} 开头),
 *       所以本模组的城市里它们实际不出现。</li>
 *   <li><b>材料对得上截图</b>: {@code bridge_large_open} 的 16x16 桥面里
 *       {@code l} 192 格 / {@code q} 32 格(正中 z=7、z=8 两行)/ {@code w} 36 格 /
 *       {@code v} 36 格(z=0、z=15 两条边)。
 *       它自带 {@code "refpalette": "street_large"}, 而
 *       {@code palettes/street_large.json} 只有两条:
 *       {@code l = minecraft:smooth_stone_slab[type=double]}(平滑石, <b>浅灰石材</b> —— 玩家说的"浅灰色石材")、
 *       {@code q = minecraft:smooth_quartz}(平滑石英, <b>白色</b> —— 玩家说的"白色虚线/车道分隔线")。
 *       {@code v}/{@code w} 不在这本调色板里, 回落到区块调色板 {@code lostcities:default}:
 *       {@code v} = variant {@code stonebrick}(石砖/裂纹石砖/苔石砖, 已经是我们的石材语言)、
 *       {@code w} = {@code minecraft:cobblestone_wall}(圆石墙 —— 玩家说的"矮墙+立柱")。
 *       桥的支撑柱来自 worldstyle 的 {@code "bridgesupport": "v"}
 *       ({@code worldstyles/standard.json:3}), 用的也是 {@code v} = 石砖, 同样是我们的石材。</li>
 *   <li><b>而它旁边那条内城马路</b>用的是我们自己的 {@code palettes/road_inner.json}
 *       ({@link RoadStyleGenerator#writePalette}): {@code l}/{@code S} = 随机混合路面,
 *       {@code b} = 路缘石砖, {@code q} = 中央分隔带磨制黑石。
 *       所以"桥面平滑石 + 白线" 对 "斑驳深色马路" 的割裂感, 出处就在这里。</li>
 * </ol>
 * <b>结论</b>: 要改的是 {@code palettes/street_large.json} 里的 {@code l}(桥面, 192 格)与
 * {@code q}(正中两行分隔线, 32 格)。两者都<b>只由 refpalette = street_large 的图纸解析</b>,
 * 桥用的 {@code v}/{@code w} 落在共享的 {@code default} 上, 改 {@code default} 会波及全城,
 * 所以那两个字符<b>不动</b>; 桥两侧矮墙 {@code w} 改用"往这本调色板里补一条"的办法单独接管
 * (见下一节的安全性论证)。
 *
 * <h2>二、做法: 整份复制 + 只改命中的字符(+ 补一条 w)</h2>
 * 从失落城市 jar 里读出<b>整份</b> {@code palettes/street_large.json}, 逐条目原样搬过来,
 * 只替换命中的字符:
 * <ul>
 *   <li>{@code l}(桥面主体, 原版 平滑石) → {@link RoadStyleGenerator} 的 {@code INNER_SURFACE}:
 *       内城马路那套"石砖 / 深板岩 / 磨制黑石 随机混合"的路面, <b>和内城马路同一套方块</b>。
 *       权重经 {@link RoadStyleGenerator#scaleTo128} 放大到总和正好 128
 *       —— {@code CompiledPalette.addPalettes} 会往 {@code BlockState[128]} 里塞,
 *       塞不满就抛 "factor should go up to 128"
 *       ({@code CompiledPalette.java:52-62});</li>
 *   <li>{@code q}(正中两行) → {@link RoadStyleGenerator} 的 {@code INNER_DIVIDER}(磨制黑石):
 *       我们自己的宽路拼件正中那两行用的就是 {@code q}({@code RoadStyleGenerator.writeWideParts}
 *       的 {@code road_inner_wide3_center}: z=7/z=8 用 q), 内城大街图纸
 *       {@code road_inner_large_*} 的中线也是 {@code q}
 *       —— 角色和桥的 {@code q} <b>完全对应</b>(都是 z=7/z=8 两行);</li>
 *   <li>{@code w}(两侧矮墙与立柱, 原版 圆石墙) → {@code minecraft:stone_brick_wall}(石砖墙):
 *       桥面两条边(z=0/z=15)是 {@code v} = 石砖, 我们的路缘 {@code INNER_CURB} 也是石砖,
 *       把矮墙也统一到石砖, 整座桥就是一套石材。几何完全不变(墙换墙),
 *       只是方块 id 换了, 矮墙/立柱的连接关系由游戏自身在放置时计算, 和原来完全一致。
 *       <b>这是一条新增条目</b>(原版这本调色板里没有 {@code w}), 所以下面的校验专门为它放宽
 *       "条目数一致" 这一条: 期望条目数 = 原版条目数 + 1。</li>
 * </ul>
 * 桥面边缘那两条 {@code v}(variant stonebrick = 石砖 1000 : 裂纹石砖 9 : 苔石砖 8)
 * <b>故意不动</b>: 它本来就是石砖, 和我们的石材只差一个"斑驳"随机, 而 {@code v} 是
 * {@code default} 里被全城(建筑/高速/桥/支撑柱)大量共用的高频字符, 动它必伤别处。
 *
 * <h2>三、改这几个字符会连带影响到谁(207 张图纸全量统计)</h2>
 * <ul>
 *   <li>{@code l}: 207 张图纸里 33 张用到它。其中只有 {@code bridge_large_open}
 *       和 9 张 {@code street_large_*}(all/bend/connector/end/full/none/stair/straight/t)
 *       的 {@code l} 落在 {@code street_large} 这本调色板上;
 *       另外 22 张 {@code building*_N} 的 {@code l} 是从<b>区块调色板</b>来的
 *       —— {@code palettes/common.json:194} 把 {@code l} 定义成
 *       {@code minecraft:ladder[facing=north]}(<b>梯子</b>), 和这本调色板毫无关系, 不受影响;
 *       {@code oilrig01}/{@code oilrig11} 的 {@code l} 走自己的 {@code refpalette: oilrig},
 *       也不受影响。</li>
 *   <li>{@code q}: 207 张里 11 张用到它。落在 {@code street_large} 上的同样只有
 *       {@code bridge_large_open} + 那 9 张 {@code street_large_*};
 *       {@code oilrig01}(26 格)走 {@code refpalette: oilrig}、
 *       {@code rails_3split}(1 格)走 {@code refpalette: rails}, 都不受影响。
 *       另外 {@code q} <b>没有</b>被 {@code common.json} 或 {@code default.json} 定义,
 *       所以不存在"靠区块调色板拿 q"的图纸。</li>
 *   <li>{@code w}: 用得很多, 但在这本调色板里<b>只被 {@code bridge_large_open} 一张图纸用到</b>
 *       —— 那 9 张 {@code street_large_*} 的字符只有 {@code b}/{@code l}/{@code q}/{@code _},
 *       一张都没有 {@code w}(全量统计)。所以这条新增条目只影响桥的表面。</li>
 *   <li>那 9 张 {@code street_large_*} 大街图纸<b>在我们的城市里不会出现</b>:
 *       {@link CityStylePatcher} 会把 {@code citystyle_common} 与 {@code citystyle_border} 的
 *       {@code streetblocks.largeparts} 全部改写成我们自己的 {@code road_inner_large_*} /
 *       {@code road_outer_large_*} 图纸(见该文件 :173-183); 失落城市里唯一的"默认大街图纸"
 *       兜底 {@code StreetParts} 的 {@code connector}(默认 {@code street_large_connector})
 *       也被我们显式覆盖成 {@code road_*_large_connector}。
 *       构建归档里 grep 过: 除了 {@code citystyle_common.json} 的 {@code largeparts},
 *       没有任何 citystyle/worldstyle 提到 {@code street_large_}。</li>
 * </ul>
 * 退一步说, 即使那 9 张图纸哪天被用上了, 它们的路面也<b>本来就该</b>和我们的大街一样
 * —— 所以这个"副作用"方向和本模组的目标是一致的, 不是意外。
 *
 * <p>⚠ <b>绝不能只写一份只含 l/q/w 的调色板</b>: 少一条就会有图纸字符解析不到
 * ({@code CompiledPalette.get} 返回 null → 铺方块时崩)。所以这里必须
 * <b>先读出整份、只替换命中的条目</b>, 并且在写出前校验
 * "字符集合 == 原版字符集合 ∪ {w} + 条目数 = 原版条目数 + (原版有没有 w ? 0 : 1)",
 * 任何一项不满足就<b>一个字节都不写</b>(宁可桥保持原版外观, 也不能写出残缺调色板)。
 *
 * <h2>四、开关与容错</h2>
 * {@code config/失落城市建筑/桥梁风格.json} 的「桥面换成我们的路面」关掉时
 * ({@link BridgeStyleConfig#enabled()} == false), 一个文件都不生成, 桥保持原版外观。
 *
 * <p>整个生成过程包在 {@code try/catch(Throwable)} 里: 读不到 jar、jar 里没有这本调色板、
 * 调色板 JSON 坏掉、里面没有 l/q —— 任何情况都只是"桥换皮不生效"(记一条警告),
 * <b>绝不让它把数据包生成搞崩</b>。本类只往 {@code Output} 里放文件, 不碰世界生成逻辑。
 */
public final class BridgeStyleGenerator {

    /** 失落城市 jar 里这本调色板的路径(相对 zip 根)。 */
    private static final String LC_PALETTE_ENTRY = "data/lostcities/lostcities/palettes/street_large.json";

    /**
     * 覆盖文件在数据包里的路径(从 {@code data/} 之后算起)。
     *
     * <p>落在这里就是<b>覆盖失落城市自己的同名文件</b> —— 本模组的数据包在数据包栈里是最高优先级
     * (AddPackFindersEvent 用 {@code Pack.Position.TOP}, 见 {@link LcAssetGenerator} 的落盘说明),
     * 所以这份覆盖会赢过失落城市 jar 里的那一份。
     */
    private static final String WRITE_PATH = "lostcities/lostcities/palettes/street_large.json";

    /** 桥面主体字符(原版 {@code minecraft:smooth_stone_slab[type=double]}, 192 格)。 */
    private static final String SURFACE_CHAR = "l";

    /** 桥面正中两行车道的分隔线字符(原版 {@code minecraft:smooth_quartz}, 32 格)。 */
    private static final String DIVIDER_CHAR = "q";

    /** 桥两侧矮墙与立柱字符(原版 {@code minecraft:cobblestone_wall}, 36 格; 原版这本调色板里没有它)。 */
    private static final String PARAPET_CHAR = "w";

    /** 矮墙换成的方块: 和我们的路缘/桥面边缘(石砖)同一套石材。 */
    private static final String PARAPET_BLOCK = "minecraft:stone_brick_wall";

    private BridgeStyleGenerator() {
    }

    /**
     * 生成桥面覆盖(覆盖失落城市的调色板 {@code palettes/street_large.json})。
     *
     * <p>开关关闭 / 找不到失落城市 jar / jar 里没有这本调色板 / 调色板结构不符合预期时,
     * 什么都不生成(桥保持原版外观)。
     *
     * @param lcJar 失落城市 jar 的路径; 允许为 null
     * @param out   建筑转换结果, 覆盖文件会通过 {@code putExternal} 放进去(和其它覆盖同一套落盘逻辑)
     * @param log   日志器
     * @return 实际生成的覆盖文件个数(0 = 没生成)
     */
    public static int generate(Path lcJar, LcAssetGenerator.Output out, Logger log) {
        try {
            if (!BridgeStyleConfig.enabled()) {
                log.info("[CAF-LC] 桥面换成我们的路面: 关(配置里关掉了, 桥保持原版外观)");
                return 0;
            }
            if (lcJar == null || !Files.isRegularFile(lcJar)) {
                log.warn("[CAF-LC] 找不到失落城市 jar, 跳过桥面替换(桥保持原版外观)");
                return 0;
            }
            JsonObject original = readStreetLargePalette(lcJar, log);
            if (original == null) {
                return 0;
            }
            JsonObject patched = patchPalette(original, log);
            if (patched == null) {
                return 0;
            }
            // putExternal 内部是 "先把 JSON 序列化成字符串, 再 put 进 map", 要么整个成功要么什么都没放,
            // 不会在 out.files 里留下半成品。
            out.putExternal(WRITE_PATH, patched);
            log.info("[CAF-LC] 已生成桥面覆盖 {}: 桥面 {} -> {} 种方块随机混合, 分隔线 {} -> {}, 矮墙 {} -> {}",
                    WRITE_PATH, SURFACE_CHAR, RoadStyleGenerator.INNER_SURFACE.length,
                    DIVIDER_CHAR, RoadStyleGenerator.INNER_DIVIDER,
                    PARAPET_CHAR, PARAPET_BLOCK);
            return 1;
        } catch (Throwable t) {
            // 桥换皮失败只是"外观没变", 绝不能连累数据包生成 —— 这里全部吞掉, 只记一条警告。
            log.warn("[CAF-LC] 生成桥面覆盖时出错, 本次不替换(桥保持原版外观, 数据包生成不受影响)", t);
            return 0;
        }
    }

    /**
     * 从失落城市 jar 里读出<b>整份</b> {@code palettes/street_large.json}。
     *
     * <h2>为什么读 jar 而不是在本模组 jar 里放一份副本</h2>
     * 和 {@link HighwayStyleGenerator}(读高速图纸)、{@link RoadStyleGenerator}(从 jar 复制路图纸)、
     * {@link CityStylePatcher}(从 jar 读原版 citystyle / worldstyle)完全一致:
     * jar 路径本来就已经由 {@code LcCustomCityMod.findLostCitiesJar} 找好传进来了, 直接用现成的管道即可。
     * 放副本的话, 玩家一升级失落城市副本就会过时, 而这份调色板是"整份复制、只改命中条目"的 ——
     * 副本过时意味着我们会把旧版调色板整个盖上去, 把失落城市新版里的其他改动一起抹掉。
     *
     * @return 解析好的调色板对象; 任何问题都返回 null(调用方什么都不写)
     */
    private static JsonObject readStreetLargePalette(Path lcJar, Logger log) {
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            ZipEntry entry = zip.getEntry(LC_PALETTE_ENTRY);
            if (entry == null) {
                log.warn("[CAF-LC] 失落城市 jar 里找不到 {}, 跳过桥面替换(桥保持原版外观)", LC_PALETTE_ENTRY);
                return null;
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                log.warn("[CAF-LC] {} 的顶层不是 JSON 对象, 跳过桥面替换", LC_PALETTE_ENTRY);
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            log.warn("[CAF-LC] 读取失落城市调色板 {} 失败, 跳过桥面替换: {}",
                    LC_PALETTE_ENTRY, e.toString());
            return null;
        }
    }

    /**
     * 整份复制原调色板, 只把 {@code l} / {@code q} 两条换成我们的方块, 并补一条 {@code w}(矮墙)。
     *
     * <p>返回 null = 这次不替换(l/q 不各出现一次, 或者结构不是预期的样子)。
     * 这种情况<b>绝不写文件</b> —— 写一份残缺的调色板会让别的字符全解析不到, 直接把游戏搞崩,
     * 比"桥还是原版外观"严重得多。
     *
     * <p>其余条目是把原对象<b>原样搬过去</b>(同一个 JsonObject 实例, 一个字段都不动),
     * 所以除 l/q/w 之外的每一个字符都与原版逐字一致。
     *
     * <p>被替换的那两条是<b>新建的对象</b>, 但会把原条目上的 {@code damaged}(废墟腐蚀时替换成的方块)
     * 原样抄过来 —— 失落城市原版的 street_large 这两条都没有 damaged, 抄过来是 null 就不写这个字段,
     * 效果和我们内城马路调色板(road_inner)里同样不带 damaged 的写法一致。
     */
    static JsonObject patchPalette(JsonObject original, Logger log) {
        JsonElement paletteEl = original.get("palette");
        if (paletteEl == null || !paletteEl.isJsonArray()) {
            log.warn("[CAF-LC] 失落城市调色板 {} 里没有 palette 数组(结构变了?), 跳过桥面替换", LC_PALETTE_ENTRY);
            return null;
        }
        JsonArray source = paletteEl.getAsJsonArray();
        JsonArray result = new JsonArray();
        Set<String> originalChars = new LinkedHashSet<>();
        int surfaceHits = 0;
        int dividerHits = 0;
        int parapetHits = 0;
        for (JsonElement el : source) {
            if (!el.isJsonObject()) {
                log.warn("[CAF-LC] 失落城市调色板 {} 里有非对象条目, 跳过桥面替换", LC_PALETTE_ENTRY);
                return null;
            }
            JsonObject entry = el.getAsJsonObject();
            JsonElement charEl = entry.get("char");
            if (charEl == null || !charEl.isJsonPrimitive() || charEl.getAsString().isEmpty()) {
                log.warn("[CAF-LC] 失落城市调色板 {} 里有缺 char 的条目, 跳过桥面替换", LC_PALETTE_ENTRY);
                return null;
            }
            String ch = charEl.getAsString();
            originalChars.add(ch);
            if (SURFACE_CHAR.equals(ch)) {
                result.add(surfaceEntry(entry, log));
                surfaceHits++;
            } else if (DIVIDER_CHAR.equals(ch)) {
                result.add(dividerEntry(entry, log));
                dividerHits++;
            } else if (PARAPET_CHAR.equals(ch)) {
                result.add(parapetEntry(entry));
                parapetHits++;
            } else {
                // ★ 其余条目原样搬过去: 同一个 JsonObject 实例, 字段名/顺序/取值一个都不动 ★
                result.add(entry);
            }
        }
        // 原版这本调色板里没有 w, 所以正常路径是"补一条"。万一哪天失落城市自己加了 w,
        // 上面那个分支已经把它接管掉了, 这里就不要再补。
        boolean addedParapet = parapetHits == 0;
        if (addedParapet) {
            result.add(parapetEntry(null));
        }
        if (surfaceHits != 1 || dividerHits != 1 || parapetHits > 1) {
            log.warn("[CAF-LC] 失落城市调色板 {} 里 {} 出现 {} 次, {} 出现 {} 次, {} 出现 {} 次"
                            + "(期望 l/q 各 1 次, w 至多 1 次), 跳过桥面替换(绝不写出残缺的调色板)",
                    LC_PALETTE_ENTRY, SURFACE_CHAR, surfaceHits, DIVIDER_CHAR, dividerHits,
                    PARAPET_CHAR, parapetHits);
            return null;
        }
        // ---- 收尾校验: 条目数 + 字符集合 ----
        // 期望: 原版字符集合再并上 w; 条目数 = 原版条目数 + (有没有补充过 w)。
        int expectedSize = source.size() + (addedParapet ? 1 : 0);
        if (result.size() != expectedSize) {
            log.warn("[CAF-LC] 覆盖后条目数 {} 与原版 {} + 新增 {} 不一致, 跳过桥面替换",
                    result.size(), source.size(), addedParapet ? 1 : 0);
            return null;
        }
        Set<String> expectedChars = new LinkedHashSet<>(originalChars);
        expectedChars.add(PARAPET_CHAR);
        Set<String> resultChars = new LinkedHashSet<>();
        for (JsonElement el : result) {
            // 上面已经保证每个条目都是对象且有非空 char, 这里的判空只是防呆
            JsonElement c = el.getAsJsonObject().get("char");
            resultChars.add(c == null ? "?" : c.getAsString());
        }
        if (!resultChars.equals(expectedChars)) {
            log.warn("[CAF-LC] 覆盖后字符集合与原版不一致(期望 {}, 实际 {}), 跳过桥面替换",
                    expectedChars, resultChars);
            return null;
        }
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JsonElement> en : original.entrySet()) {
            if ("palette".equals(en.getKey())) {
                root.add("palette", result);
            } else {
                // 顶层如果还有别的字段(实际只有 palette), 也原样保留, 保证"只改该改的"
                root.add(en.getKey(), en.getValue());
            }
        }
        return root;
    }

    /** 桥面主体条目: 内城马路那套随机混合路面(权重总和正好 128)。 */
    private static JsonObject surfaceEntry(JsonObject source, Logger log) {
        log.info("[CAF-LC] 桥面字符 {}: 原版 {} -> 内城路面随机混合", SURFACE_CHAR, describe(source));
        JsonObject entry = new JsonObject();
        entry.addProperty("char", SURFACE_CHAR);
        int[] weights = RoadStyleGenerator.scaleTo128(RoadStyleGenerator.INNER_SURFACE);
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < RoadStyleGenerator.INNER_SURFACE.length; i++) {
            JsonObject b = new JsonObject();
            b.addProperty("random", weights[i]);
            b.addProperty("block", RoadStyleGenerator.INNER_SURFACE[i][1]);
            blocks.add(b);
        }
        entry.add("blocks", blocks);
        copyDamaged(source, entry);
        return entry;
    }

    /** 分隔线条目: 我们宽路拼件正中那两行用的同一种方块(中央分隔带)。 */
    private static JsonObject dividerEntry(JsonObject source, Logger log) {
        log.info("[CAF-LC] 分隔线字符 {}: 原版 {} -> {}", DIVIDER_CHAR, describe(source),
                RoadStyleGenerator.INNER_DIVIDER);
        JsonObject entry = new JsonObject();
        entry.addProperty("char", DIVIDER_CHAR);
        entry.addProperty("block", RoadStyleGenerator.INNER_DIVIDER);
        copyDamaged(source, entry);
        return entry;
    }

    /** 矮墙/立柱条目: 石砖墙(和我们的路缘、桥面边缘同一套石材)。 */
    private static JsonObject parapetEntry(JsonObject source) {
        JsonObject entry = new JsonObject();
        entry.addProperty("char", PARAPET_CHAR);
        entry.addProperty("block", PARAPET_BLOCK);
        copyDamaged(source, entry);
        return entry;
    }

    /**
     * 把原条目上的 {@code damaged} 原样抄到新条目上(没有就不写这个字段)。
     *
     * <p>失落城市的 {@code Palette.parsePaletteArray} 里 {@code damaged} 是可空字段,
     * 为 null 时那块石头在废墟腐蚀时保持原样 —— 我们内城马路调色板(road_inner)里的条目
     * 也都不带 damaged, 所以"抄过来"既保住了原版行为, 又和我们自己的路面一致。
     */
    private static void copyDamaged(JsonObject source, JsonObject target) {
        if (source == null) {
            return;
        }
        JsonElement damaged = source.get("damaged");
        if (damaged != null && damaged.isJsonPrimitive()) {
            target.add("damaged", damaged);
        }
    }

    /** 把原条目里的方块取值摘成一行文字, 只给日志用(排查"原版材料变了"时很有用)。 */
    private static String describe(JsonObject source) {
        if (source == null) {
            return "<原版没有这一条>";
        }
        for (String key : new String[]{"block", "variant", "frompalette", "blocks"}) {
            JsonElement el = source.get(key);
            if (el != null) {
                return key + "=" + el;
            }
        }
        return "<无方块字段>";
    }
}
