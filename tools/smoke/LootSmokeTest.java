import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.slf4j.LoggerFactory;

/**
 * 分区/单建筑战利品池离线自检。
 *
 * <p>自己生成三栋带容器的小建筑(不依赖 TestBuildings 的合成建筑 —— 它们没有容器),
 * 配上 城区配置.json, 跑真实转换, 然后检查:
 * <ol>
 *   <li>有战利品池的分区: 建筑里的容器调色板条目挂了正确的 loot 条件(且朝向等 blockstate 不变)</li>
 *   <li>单建筑覆盖: 单写的战利品池赢过分区池</li>
 *   <li>没配战利品池的分区: 容器不挂 loot</li>
 *   <li>条件文件和战利品表确实生成到了对的路径(loot_table 单数)</li>
 *   <li>配置文件缺失 → 自动生成模板, 不挂 loot; 配置文件语法坏 → 警告 + 默认, 不崩</li>
 * </ol>
 *
 * 用法: java LootSmokeTest &lt;沙盒目录&gt;
 */
public class LootSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]);
        var log = LoggerFactory.getLogger("loot");

        System.out.println("=== 战利品池离线自检 ===");

        // ============================================================
        // A. 正常路径: 分区池 + 单建筑覆盖 + 无池分区
        // ============================================================
        Path cfg = sandbox.resolve("a/失落城市建筑");
        Files.createDirectories(cfg.resolve("内城/商业区"));
        Files.createDirectories(cfg.resolve("内城/住宅区"));
        Files.createDirectories(cfg.resolve("外城/民房"));

        // 商店: 地板 + 箱子(朝北) + 木桶 —— 分区池是面包, 但它自己有铁锭覆盖池
        writeSchem(cfg.resolve("内城/商业区/商店.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest[facing=north]", new int[]{1, 1, 1, 1, 1, 1},
                "minecraft:barrel[facing=up]", new int[]{2, 1, 2, 2, 1, 2}));
        // 商店2: 建筑级池和 商店 一字不差 —— 专门盯住"多栋楼共用一个池"的缓存 bug。
        // 池的去重缓存里以前存的是简称, 于是第 2 栋楼起拿到的就是简称, 游戏里区块生成报错。
        writeSchem(cfg.resolve("内城/商业区/商店2.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest[facing=east]", new int[]{1, 1, 1, 1, 1, 1}));
        // 住宅: 分区没写战利品池 —— 箱子不该挂 loot
        writeSchem(cfg.resolve("内城/住宅区/住宅.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest[facing=south]", new int[]{1, 1, 1, 1, 1, 1}));
        // 民房: 外城分区池是金锭
        writeSchem(cfg.resolve("外城/民房/民房.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest", new int[]{1, 1, 1, 1, 1, 1}));

        String configJson = """
                {
                  "城市": { "最小半径": 60, "最大半径": 110, "外城占比": [0.30, 0.45] },
                  "城区": {
                    "内城": { "分区": {
                      "商业区": {
                        "占比": [0.40, 0.60],
                        "战利品池": { "物品": [ {"id":"minecraft:bread","数量":[1,3],"权重":10} ] },
                        "建筑": { "商店.schem": { "数量": "完全随机",
                            "战利品池": { "物品": [ {"id":"minecraft:iron_ingot","数量":[2,4],"权重":5} ] } },
                          "商店2.schem": { "数量": "完全随机",
                            "战利品池": { "物品": [ {"id":"minecraft:iron_ingot","数量":[2,4],"权重":5} ] } } }
                      },
                      "住宅区": { "占比": [0.15, 0.30] }
                    } },
                    "外城": { "分区": {
                      "民房": { "占比": 1.0,
                        "战利品池": { "物品": [ {"id":"minecraft:gold_ingot","数量":[1,2],"权重":3} ] } }
                    } }
                  }
                }
                """;
        Files.writeString(cfg.resolve("城区配置.json"), configJson, StandardCharsets.UTF_8);

        LcAssetGenerator.Output out = LcAssetGenerator.generate(cfg, log);
        check(out.errors.isEmpty(), "转换没有错误: " + out.errors);

        // ---- 收集所有调色板里"箱子"条目的 loot 情况 ----
        List<JsonObject> chestWithLoot = new ArrayList<>();
        List<JsonObject> chestWithoutLoot = new ArrayList<>();
        List<JsonObject> barrelWithLoot = new ArrayList<>();
        for (Map.Entry<String, String> f : out.files.entrySet()) {
            if (!f.getKey().startsWith("caf_lc_customcity/lostcities/palettes/")) {
                continue;
            }
            JsonObject pal = JsonParser.parseString(f.getValue()).getAsJsonObject();
            for (JsonElement e : pal.getAsJsonArray("palette")) {
                JsonObject entry = e.getAsJsonObject();
                String block = entry.get("block").getAsString();
                if (block.startsWith("minecraft:chest")) {
                    (entry.has("loot") ? chestWithLoot : chestWithoutLoot).add(entry);
                } else if (block.startsWith("minecraft:barrel")) {
                    if (entry.has("loot")) {
                        barrelWithLoot.add(entry);
                    }
                }
            }
        }

        System.out.println("--- 调色板统计: 挂loot的箱子 " + chestWithLoot.size()
                + ", 没挂loot的箱子 " + chestWithoutLoot.size()
                + ", 挂loot的木桶 " + barrelWithLoot.size());

        check(chestWithLoot.size() == 3, "正好 3 栋建筑的箱子挂了 loot(商店+商店2+民房) [实际 " + chestWithLoot.size() + "]");
        check(chestWithoutLoot.size() == 1, "住宅区(没配池)的箱子没挂 loot [实际 " + chestWithoutLoot.size() + "]");
        check(barrelWithLoot.size() == 1, "商店里的木桶也挂了 loot [实际 " + barrelWithLoot.size() + "]");

        // 朝向保留: 商店的箱子必须还是 facing=north, 不是被掰成固定朝向
        boolean facingKept = chestWithLoot.stream()
                .anyMatch(e -> e.get("block").getAsString().equals("minecraft:chest[facing=north]"));
        check(facingKept, "挂 loot 的箱子保留原 blockstate(chest[facing=north])");
        check(chestWithoutLoot.get(0).get("block").getAsString().equals("minecraft:chest[facing=south]"),
                "没挂 loot 的箱子 blockstate 也没被动过");

        // ---- 条件文件 / 战利品表 ----
        Map<String, String> tables = new LinkedHashMap<>();      // 条件名 -> 战利品表内容
        for (Map.Entry<String, String> f : out.files.entrySet()) {
            if (f.getKey().startsWith("caf_lc_customcity/lostcities/conditions/")) {
                String condName = f.getKey().substring("caf_lc_customcity/lostcities/conditions/".length(),
                        f.getKey().length() - ".json".length());
                JsonObject cond = JsonParser.parseString(f.getValue()).getAsJsonObject();
                String tableId = cond.getAsJsonArray("values").get(0).getAsJsonObject().get("value").getAsString();
                // 条件里的 value 指到 loot_table/<path>
                String rel = tableId.substring(tableId.indexOf(':') + 1);
                String tableKey = "caf_lc_customcity/loot_table/" + rel + ".json";
                String table = out.files.get(tableKey);
                check(table != null, "条件 " + condName + " 指向的战利品表存在: " + tableKey);
                if (table != null) {
                    tables.put(condName, table);
                }
                check(tableId.startsWith("caf_lc_customcity:"), "战利品表 id 带 caf_lc_customcity 命名空间: " + tableId);
            }
        }
        // 商业区的分区池(面包)被商店的单建筑覆盖池顶替了, 没有任何建筑引用它,
        // 所以它根本不会生成 —— 只有 商店覆盖池(铁锭) 和 民房池(金锭) 两套。
        check(tables.size() == 2, "正好生成了 2 套战利品池(商店覆盖池 + 民房池) [实际 " + tables.size() + "]");

        // loot 字段必须写全命名空间(不带的话失落城市会补 lostcities: 前缀, 找不到)
        for (JsonObject e : chestWithLoot) {
            check(e.get("loot").getAsString().startsWith("caf_lc_customcity:"),
                    "调色板 loot 写全命名空间: " + e.get("loot").getAsString());
        }

        // ---- 单建筑覆盖生效: 找出商店调色板挂的条件, 其战利品表里要有铁锭、不能有面包 ----
        boolean overrideOk = false;
        for (JsonObject e : chestWithLoot) {
            if (!e.get("block").getAsString().equals("minecraft:chest[facing=north]")) {
                continue;   // 这是民房那栋
            }
            String loot = e.get("loot").getAsString();
            String condName = loot.substring(loot.indexOf(':') + 1);
            String table = tables.get(condName);
            if (table != null) {
                overrideOk = table.contains("minecraft:iron_ingot") && !table.contains("minecraft:bread");
            }
        }
        check(overrideOk, "单建筑覆盖生效: 商店的战利品表是铁锭而不是分区的面包");

        // 民房: 金锭
        boolean outerOk = false;
        for (JsonObject e : chestWithLoot) {
            if (!e.get("block").getAsString().equals("minecraft:chest")) {
                continue;
            }
            String loot = e.get("loot").getAsString();
            String table = tables.get(loot.substring(loot.indexOf(':') + 1));
            if (table != null) {
                outerOk = table.contains("minecraft:gold_ingot");
            }
        }
        check(outerOk, "外城民房的分区战利品池生效(金锭)");

        // 战利品表格式抽查: set_count 的数量区间对上了
        String ironTable = null;
        for (String t : tables.values()) {
            if (t.contains("minecraft:iron_ingot")) {
                ironTable = t;
            }
        }
        check(ironTable != null && ironTable.contains("\"min\": 2") && ironTable.contains("\"max\": 4"),
                "铁锭的 set_count 是配置里的 [2,4]");
        check(ironTable != null && ironTable.contains("\"weight\": 5"), "铁锭的权重是配置里的 5");

        // ============================================================
        // B. 配置文件缺失 -> 自动生成模板, 不挂 loot
        // ============================================================
        Path cfgB = sandbox.resolve("b/失落城市建筑");
        Files.createDirectories(cfgB);
        writeSchem(cfgB.resolve("默认.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest", new int[]{1, 1, 1, 1, 1, 1}));
        LcAssetGenerator.Output outB = LcAssetGenerator.generate(cfgB, log);
        check(outB.errors.isEmpty(), "B: 没配置文件也不报错");
        check(Files.isRegularFile(cfgB.resolve("城区配置.json")), "B: 自动生成了城区配置.json 模板");
        check(!anyLoot(outB), "B: 没配置文件时容器不挂 loot");

        // ============================================================
        // C. 配置文件语法坏 -> 警告 + 默认, 不崩
        // ============================================================
        Path cfgC = sandbox.resolve("c/失落城市建筑");
        Files.createDirectories(cfgC);
        Files.writeString(cfgC.resolve("城区配置.json"), "{ 这不是合法 JSON ,,,", StandardCharsets.UTF_8);
        writeSchem(cfgC.resolve("默认.schem"), Map.of(
                "minecraft:stone", new int[]{0, 0, 0, 3, 0, 3},
                "minecraft:chest", new int[]{1, 1, 1, 1, 1, 1}));
        LcAssetGenerator.Output outC = LcAssetGenerator.generate(cfgC, log);
        check(outC.errors.isEmpty(), "C: 坏 JSON 也没让转换报错");
        check(outC.warnings.stream().anyMatch(w -> w.contains("城区配置.json")),
                "C: 坏 JSON 记了一条警告");
        check(!anyLoot(outC), "C: 坏 JSON 时容器不挂 loot(回落默认)");

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + passed + " 项, 失败 " + failed + " 项");
        if (failed == 0) {
            System.out.println("LOOT-TEST-ALL-PASS");
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    /** 生成结果里有没有任何调色板条目挂了 loot。 */
    private static boolean anyLoot(LcAssetGenerator.Output out) {
        for (Map.Entry<String, String> f : out.files.entrySet()) {
            if (f.getKey().startsWith("caf_lc_customcity/lostcities/palettes/")
                    && f.getValue().contains("\"loot\"")) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 极简 .schem 写入器(Sponge v3, gzip NBT)
    //
    // 只写转换器需要的字段: Version/DataVersion/宽高长 + Blocks{Palette,Data,BlockEntities}。
    // blocks: 方块id(可带 [状态]) -> [x0,y0,z0,x1,y1,z1] 填充范围。
    // ==================================================================

    private static final String AIR = "minecraft:air";

    private static void writeSchem(Path file, Map<String, int[]> blocks) throws Exception {
        int w = 4, h = 2, d = 4;
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(AIR, 0);
        String[] cells = new String[w * h * d];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = AIR;
        }
        for (Map.Entry<String, int[]> e : blocks.entrySet()) {
            palette.computeIfAbsent(e.getKey(), k -> palette.size());
            int[] r = e.getValue();
            for (int y = r[1]; y <= r[4]; y++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    for (int x = r[0]; x <= r[3]; x++) {
                        cells[(y * d + z) * w + x] = e.getKey();
                    }
                }
            }
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (String c : cells) {
            writeVarint(data, palette.get(c));
        }

        ByteArrayOutputStream paletteTag = new ByteArrayOutputStream();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            writeIntTag(paletteTag, e.getKey(), e.getValue());
        }
        paletteTag.write(0);

        ByteArrayOutputStream blocksTag = new ByteArrayOutputStream();
        writeCompoundHeader(blocksTag, "Palette");
        blocksTag.write(paletteTag.toByteArray());
        writeByteArrayTag(blocksTag, "Data", data.toByteArray());
        writeListHeader(blocksTag, "BlockEntities", 10, 0);
        blocksTag.write(0);

        ByteArrayOutputStream root = new ByteArrayOutputStream();
        writeIntTag(root, "Version", 3);
        writeIntTag(root, "DataVersion", 3955);
        writeShortTag(root, "Width", w);
        writeShortTag(root, "Height", h);
        writeShortTag(root, "Length", d);
        writeCompoundHeader(root, "Blocks");
        root.write(blocksTag.toByteArray());
        root.write(0);

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.write(10);        // TAG_Compound
        fileBytes.write(0);
        fileBytes.write(0);         // 空根名
        fileBytes.write(root.toByteArray());
        try (OutputStream os = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(os)) {
            gz.write(fileBytes.toByteArray());
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static void writeName(ByteArrayOutputStream out, int type, String name) {
        out.write(type);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        out.write((n.length >>> 8) & 0xFF);
        out.write(n.length & 0xFF);
        out.write(n, 0, n.length);
    }

    private static void writeIntTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 3, name);
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeShortTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 2, name);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeByteArrayTag(ByteArrayOutputStream out, String name, byte[] v) {
        writeName(out, 7, name);
        int len = v.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(v, 0, v.length);
    }

    private static void writeListHeader(ByteArrayOutputStream out, String name, int elemType, int len) {
        writeName(out, 9, name);
        out.write(elemType);
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    private static void writeCompoundHeader(ByteArrayOutputStream out, String name) {
        writeName(out, 10, name);
    }
}
