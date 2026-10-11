package com.caf.lccustomcity.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 生成"内城 / 外城两套不同路面"的资产: 两套调色板 + 两套路图纸。
 *
 * <h2>原理(已从失落城市源码验证)</h2>
 * 路图纸(part)可以自带 {@code refpalette}(调色板)。失落城市在铺图纸时
 * ({@code LostCityTerrainFeature.computePalette})会把 part 自己的调色板
 * <b>合并盖在</b>区块默认调色板上面 —— part 里定义了的字符优先, 没定义的回落到区块默认。
 * 所以只要给内城/外城各做一套带自定义 refpalette 的路图纸, 两边路面材料就不同,
 * 而且不影响建筑(建筑用的是自己图纸的调色板)。
 *
 * <h2>错乱混合(末世感)</h2>
 * 调色板用 {@code {"char":"S","blocks":[{"random":权重,"block":"..."}, ...]}} 这种写法
 * (失落城市 {@code Palette} 原生支持), 路面上每个位置都独立随机挑一种方块。
 *
 * <h2>图纸形状</h2>
 * 路图纸的"形状"(直路/弯道/T字/十字/尽头...)直接从失落城市 jar 里复制现成的,
 * 保证路形绝对正确, 我们只换掉它引用的调色板。
 *
 * <p>本类只生成资产; 把 citystyle_common(内城)/citystyle_border(外城) 的
 * {@code streetblocks.parts/largeparts/tertiaryparts} 指向这些图纸, 由 {@link CityStylePatcher} 完成。
 */
public final class RoadStyleGenerator {

    /** 失落城市 jar 里路图纸的目录。 */
    private static final String LC_PARTS_DIR = "data/lostcities/lostcities/parts/";

    public static final String INNER = "inner";   // 内城 = citystyle_common
    public static final String OUTER = "outer";   // 外城 = citystyle_border

    // ---- 路面方块清单 [权重, 方块id] —— 权重越大越常见 ----
    /** 历史街道路面；当前城市空地和高速路使用 VACANT_SURFACE。 */
    static final String[][] INNER_SURFACE = {
            {"3", "minecraft:stone_bricks"}, {"3", "minecraft:polished_deepslate"}, {"3", "minecraft:polished_blackstone"},
            {"2", "minecraft:cracked_stone_bricks"}, {"2", "minecraft:deepslate_bricks"}, {"2", "minecraft:deepslate_tiles"},
            {"2", "minecraft:polished_blackstone_bricks"}, {"2", "minecraft:tuff_bricks"},
            {"1", "minecraft:blackstone"}, {"1", "minecraft:deepslate"}, {"1", "minecraft:cracked_polished_blackstone_bricks"},
            {"1", "minecraft:mossy_cobblestone"}, {"1", "minecraft:gravel"}
    };
    /** 玩家要求所有城区道路统一使用深板岩、石砖这一套。 */
    private static final String[][] OUTER_SURFACE = INNER_SURFACE;

    // Counts of the six requested materials in sy.nbt's ground layer (569 total).
    public static final String[][] VACANT_SURFACE = {
            {"74", "minecraft:grass_block[snowy=false]"},
            {"108", "minecraft:cobbled_deepslate"},
            {"112", "minecraft:deepslate[axis=y]"},
            {"112", "minecraft:cyan_terracotta"},
            {"76", "minecraft:blackstone"},
            {"87", "minecraft:basalt[axis=x]"}
    };

    private static final String INNER_CURB = "minecraft:stone_bricks";           // 内城路基/边
    static final String INNER_DIVIDER = "minecraft:polished_blackstone"; // 历史街道中央分隔带
    private static final String OUTER_CURB = INNER_CURB;
    private static final String OUTER_DIVIDER = INNER_DIVIDER;

    // 要从失落城市复制的路图纸后缀(普通街 / 大街)
    private static final String[] NORMAL = {"full", "straight", "end", "bend", "t", "none", "all", "stair"};
    private static final String[] LARGE = {"full", "straight", "end", "bend", "t", "none", "all", "connector", "stair"};

    private RoadStyleGenerator() {
    }

    /** Flat vacant land only: no road shapes, lanes, borders or intersections. */
    public static void generateVacantSurface(LcAssetGenerator.Output out) {
        writePalette(out, INNER, VACANT_SURFACE, "minecraft:cyan_terracotta", "minecraft:cyan_terracotta");
        writeWidePart(out, "vacant_surface", z -> "l");
    }

    /**
     * 生成两套路面资产。必须在 {@link CityStylePatcher#patch} 之前调用
     * (因为 citystyle 会引用这里生成的路图纸)。
     */
    public static void generate(Path lcJar, LcAssetGenerator.Output out, Logger log) {
        writePalette(out, INNER, INNER_SURFACE, INNER_CURB, INNER_DIVIDER);
        writePalette(out, OUTER, OUTER_SURFACE, OUTER_CURB, OUTER_DIVIDER);

        // 宽路零件(阶段3): 不依赖失落城市 jar, 无条件生成。
        // 宽路只在内城生效, 所以只做 inner 一套; 渲染层 Mixin(WideRoadRenderMixin)
        // 按 "road_inner_" + 零件后缀 去注册表里找它们。
        writeWideParts(out);

        if (lcJar == null || !Files.isRegularFile(lcJar)) {
            log.warn("[CAF-LC] 找不到失落城市 jar, 跳过自定义路面(城市会用失落城市默认路面)");
            return;
        }
        int copied = 0;
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            for (String zone : new String[]{INNER, OUTER}) {
                for (String suffix : NORMAL) {
                    copied += copyPart(zip, out, log, "street_" + suffix, zone, suffix);
                }
                for (String suffix : LARGE) {
                    copied += copyPart(zip, out, log, "street_large_" + suffix, zone, "large_" + suffix);
                }
            }
        } catch (IOException e) {
            log.error("[CAF-LC] 读取失落城市路图纸失败", e);
        }
        log.info("[CAF-LC] 已生成内城/外城两套路面: 2 本调色板 + {} 张路图纸", copied);
    }

    /** 复制一张失落城市路图纸, 换成我们对应城区的调色板。返回 1(成功)/0(跳过)。 */
    private static int copyPart(ZipFile zip, LcAssetGenerator.Output out, Logger log,
                                String lcName, String zone, String outSuffix) {
        ZipEntry entry = zip.getEntry(LC_PARTS_DIR + lcName + ".json");
        if (entry == null) {
            log.warn("[CAF-LC] 失落城市里找不到路图纸 {}, 已跳过", lcName);
            return 0;
        }
        JsonObject part;
        try (InputStream in = zip.getInputStream(entry)) {
            part = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            log.warn("[CAF-LC] 解析路图纸 {} 失败, 已跳过: {}", lcName, e.getMessage());
            return 0;
        }
        part.remove("palette"); // 去掉内联调色板, 改用 refpalette
        part.addProperty("refpalette", LcAssetGenerator.NS + ":road_" + zone);
        out.putAsset("lostcities/parts/road_" + zone + "_" + outSuffix + ".json", part);
        return 1;
    }

    /** 写一本路面调色板。字符 S/l = 路面随机混合, b = 路基, q = 中央分隔带。 */
    private static void writePalette(LcAssetGenerator.Output out, String zone,
                                     String[][] surface, String curb, String divider) {
        JsonArray arr = new JsonArray();
        arr.add(weightedEntry("S", surface));
        arr.add(weightedEntry("l", surface));
        arr.add(singleEntry("b", curb));
        arr.add(singleEntry("q", divider));
        JsonObject root = new JsonObject();
        root.add("palette", arr);
        out.putAsset("lostcities/palettes/road_" + zone + ".json", root);
    }

    static JsonObject weightedEntry(String ch, String[][] blocks) {
        JsonObject e = new JsonObject();
        e.addProperty("char", ch);
        int[] weights = scaleTo128(blocks);
        JsonArray list = new JsonArray();
        for (int i = 0; i < blocks.length; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("random", weights[i]);
            o.addProperty("block", blocks[i][1]);
            list.add(o);
        }
        e.add("blocks", list);
        return e;
    }

    /**
     * 把相对权重等比放大到总和<b>正好 128</b>。
     *
     * <p>失落城市 CompiledPalette.addPalettes 会把每个 random 权重当成"往
     * BlockState[128] 数组里塞几份", 塞不满 128 格就直接抛
     * {@code "Invalid palette entry ... factor should go up to 128"}。
     * 这里清单里的 3/2/1 只是相对比例(内城合计 25, 外城合计 26), 直接写进去必炸,
     * 所以统一按 128/合计 等比放大: 先取下整, 再用最大余数法把零头补到 128。
     * 完全确定: 同一输入永远得到同一组权重, 与生成顺序/机器无关。
     */
    static int[] scaleTo128(String[][] blocks) {
        int n = blocks.length;
        int total = 0;
        for (String[] b : blocks) {
            total += Integer.parseInt(b[0]);
        }
        int[] result = new int[n];
        long[] remainder = new long[n];   // 每项 w*128/total 的余数分子, 最大余数法用
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            long scaled = (long) Integer.parseInt(blocks[i][0]) * 128;
            result[i] = (int) (scaled / total);
            remainder[i] = scaled % total;
            assigned += result[i];
        }
        // 零头个数一定 < n(每项零头不足 1), 按余数从大到小逐个 +1。
        // Arrays.sort 对对象数组是稳定排序, 余数相同保持原顺序, 保证确定性。
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(remainder[b], remainder[a]));
        for (int k = assigned; k < 128; k++) {
            result[order[k - assigned]]++;
        }
        return result;
    }

    private static JsonObject singleEntry(String ch, String block) {
        JsonObject e = new JsonObject();
        e.addProperty("char", ch);
        e.addProperty("block", block);
        return e;
    }

    // ------------------------------------------------------------------
    // 宽路零件(阶段3, 配合 mixin 包里的 WideRoadRenderMixin 使用)
    // ------------------------------------------------------------------
    // 基准朝向一律是"路沿 X 走向(东西向)、路缘在 zmin(北)侧",
    // 和失落城市 street_large_straight 的基准一致, 渲染层用旋转适配南北向和左右侧。
    // 字符沿用内城路面调色板 road_inner: l=路面随机混合, q=中央分隔带, b=路缘/路基。
    // ------------------------------------------------------------------

    /** 生成宽路的 3 张拼件图纸(全 16×16 单层)。 */
    private static void writeWideParts(LcAssetGenerator.Output out) {
        // 轴心块: 纯路面, 中央两行是分隔带 q
        writeWidePart(out, "road_inner_wide3_center", z -> (z == 7 || z == 8) ? "q" : "l");
        // 边缘块: 靠外 3 行是路缘 b, 其余路面 l(次干道的两个半幅也复用它)
        writeWidePart(out, "road_inner_wide3_edge", z -> z < 3 ? "b" : "l");
        // 交叉口块: 整铺路面(纵带×横带 / 路带×次干道), 没有路缘和分隔带
        writeWidePart(out, "road_inner_wide3_cross", z -> "l");
    }

    /**
     * 写一张宽路拼件。rowChar 给出第 z 行(0..15)用的字符, 整行 16 格同一个字符。
     * 注意: 这里全程只用字符串, 不出现 char 字面量。
     */
    private static void writeWidePart(LcAssetGenerator.Output out, String name,
                                      java.util.function.IntFunction<String> rowChar) {
        JsonArray slice = new JsonArray();
        for (int z = 0; z < 16; z++) {
            slice.add(rowChar.apply(z).repeat(16));
        }
        JsonArray slices = new JsonArray();
        slices.add(slice);
        JsonObject part = new JsonObject();
        part.addProperty("xsize", 16);
        part.addProperty("zsize", 16);
        part.addProperty("refpalette", LcAssetGenerator.NS + ":road_" + INNER);
        part.add("slices", slices);
        out.putAsset("lostcities/parts/" + name + ".json", part);
    }

    // ------------------------------------------------------------------
    // 给 CityStylePatcher 用: 生成 streetblocks 里的 parts / largeparts / tertiaryparts
    // ------------------------------------------------------------------

    /** 普通街(次级路)的 parts; 小巷(tertiary)默认也用普通街图纸。 */
    public static JsonObject normalPartsJson(String zone) {
        return partsJson(zone, false);
    }

    /** 大街(主路)的 largeparts。 */
    public static JsonObject largePartsJson(String zone) {
        return partsJson(zone, true);
    }

    private static JsonObject partsJson(String zone, boolean large) {
        JsonObject o = new JsonObject();
        String ns = LcAssetGenerator.NS + ":";
        String prefix = "road_" + zone + "_" + (large ? "large_" : "");
        String[] fields = {"full", "straight", "end", "bend", "t", "none", "all", "connector", "stair"};
        for (String f : fields) {
            if (!large && f.equals("connector")) {
                // 失落城市里普通街的 connector 默认就用大街的 connector 图纸
                o.addProperty(f, ns + "road_" + zone + "_large_connector");
            } else {
                o.addProperty(f, ns + prefix + f);
            }
        }
        return o;
    }
}
