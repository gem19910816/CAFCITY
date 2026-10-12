package com.caf.lccustomcity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code config/CAF失落城市配置/城区配置.json}(城市 → 城区 → 分区 → 建筑 嵌套)。
 *
 * <h2>容错原则(需求文档阶段 4 提前到这里做)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 然后全部用默认值, 不报错</li>
 *   <li>JSON 语法错 → 记一条警告, 全部用默认值, 不崩</li>
 *   <li>某个字段缺失/类型不对 → 该字段用默认值并记警告, 其余字段照常生效</li>
 * </ul>
 *
 * <h2>取值规则</h2>
 * <ul>
 *   <li>"占比": 单个数字 = 固定值; [min,max] = 区间(本阶段只解析存起来)</li>
 *   <li>"数量": [min,max] = 上下限; "完全随机" 或缺省 = 不限制(本阶段只解析存起来)</li>
 *   <li>"战利品池": 现在就生效 —— 由转换器生成战利品表 + 条件并挂到容器调色板条目上</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft 类, 可以被离线测试直接跑。
 */
public final class ZoneConfig {

    public static final String FILE_NAME = "城区配置.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ---- 默认值 ----
    // 半径单位是区块的格子数: 50/50 区块 = 800/800 方块(方块值在 LcCustomCityMod 里 *16 换算; 玩家要的"半径 800 方块")。
    // 外城占比区间中点 0.4375 换算成外城阈值恰好是 0.4(换算公式见 LcCustomCityMod.outerThresholdFromShare)。
    public static final int DEFAULT_RADIUS_MIN = 50;
    public static final int DEFAULT_RADIUS_MAX = 50;
    public static final Range DEFAULT_OUTER_RATIO = new Range(0.40, 0.475);

    /** 数值区间(占比等): min == max 表示固定值。 */
    public record Range(double min, double max) {
    }

    /** 数量上下限; null 表示"完全随机"(不限制)。 */
    public record CountRange(int min, int max) {
    }

    /** 一件战利品: 物品 id, 数量区间, 权重。 */
    public record LootItem(String id, int countMin, int countMax, int weight) {
    }

    /** 一套战利品池。 */
    public record LootPool(List<LootItem> items) {
        public boolean isEmpty() {
            return items.isEmpty();
        }

        /**
         * 池内容的稳定签名, 用来去重(两个分区写了完全相同的池, 只生成一份资产)
         * 以及给生成的条件/战利品表起名字(短哈希)。
         */
        public String signature() {
            StringBuilder sb = new StringBuilder();
            for (LootItem it : items) {
                sb.append(it.id()).append('|').append(it.countMin()).append('-')
                        .append(it.countMax()).append('x').append(it.weight()).append(';');
            }
            return sb.toString();
        }
    }

    /** 单栋建筑的配置。 */
    public record BuildingCfg(CountRange count, LootPool loot) {
    }

    /** 一个子分区(如 内城\商业区)。 */
    public record SubZone(Range ratio, LootPool loot, Map<String, BuildingCfg> buildings) {
    }

    /** 一个城区(内城/外城)。 */
    public record Zone(Map<String, SubZone> subZones) {
    }

    public final int radiusMin;
    public final int radiusMax;
    public final Range outerRatio;
    /** 城区名(内城/外城) -> 城区配置。 */
    public final Map<String, Zone> zones;

    private ZoneConfig(int radiusMin, int radiusMax, Range outerRatio, Map<String, Zone> zones) {
        this.radiusMin = radiusMin;
        this.radiusMax = radiusMax;
        this.outerRatio = outerRatio;
        this.zones = zones;
    }

    /** 全默认值(文件不存在/读不出时用)。 */
    public static ZoneConfig defaults() {
        return new ZoneConfig(DEFAULT_RADIUS_MIN, DEFAULT_RADIUS_MAX, DEFAULT_OUTER_RATIO, Map.of());
    }

    /**
     * 查某栋建筑该用哪套战利品池: 单建筑覆盖 &gt; 分区池; 都没有返回 null(用失落城市默认)。
     *
     * @param zone     城区名(内城/外城)
     * @param subZone  子分区名, 可能没有(null)
     * @param fileName 建筑文件名(带扩展名), 用于匹配配置里 "建筑" 节的键
     */
    public LootPool lootFor(String zone, String subZone, String fileName) {
        Zone z = zones.get(zone);
        if (z == null || subZone == null) {
            return null;
        }
        SubZone sub = z.subZones().get(subZone);
        if (sub == null) {
            return null;
        }
        BuildingCfg b = sub.buildings().get(fileName);
        if (b != null && b.loot() != null) {
            return b.loot();
        }
        return sub.loot();
    }

    /** 数量的缺省值: 至少生成 1 次(下限 1), 上限不限。玩家的建筑默认都会被生成到, 不会"放进文件夹却一栋没刷出来"。 */
    public static final CountRange AT_LEAST_ONE = new CountRange(1, Integer.MAX_VALUE);

    /**
     * 查某栋建筑的数量上下限。
     *
     * @param zone     城区名(内城/外城)
     * @param subZone  子分区名, 可能没有(null)
     * @param fileName 建筑文件名(带扩展名), 用于匹配配置里 "建筑" 节的键
     * @return 上下限; 没配 = AT_LEAST_ONE(至少生成1次); 明确配"完全随机" = null(不限制); 配了 [min,max] = 那个范围
     */
    public CountRange countFor(String zone, String subZone, String fileName) {
        Zone z = zones.get(zone);
        if (z == null || subZone == null) {
            return AT_LEAST_ONE;
        }
        SubZone sub = z.subZones().get(subZone);
        if (sub == null) {
            return AT_LEAST_ONE;
        }
        BuildingCfg b = sub.buildings().get(fileName);
        return b == null ? AT_LEAST_ONE : b.count();
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /**
     * 读取配置; 任何出错都回落到默认值并记警告, 绝不抛异常。
     * 文件不存在时会先写一份带说明的模板, 方便玩家照着改。
     */
    public static ZoneConfig load(Path configDir, List<String> warnings) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            writeTemplate(configDir, warnings);
            // Apply the newly written ratios and loot immediately, so the first startup
            // generates the same assets as the second startup with an unchanged file.
            if (!Files.isRegularFile(file)) return defaults();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add(FILE_NAME + " 读取失败(" + e.getMessage() + "), 本次用默认配置");
            return defaults();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 不是合法的 JSON(" + e.getMessage() + "), 本次用默认配置, 请检查语法");
            return defaults();
        }
        try {
            return parse(root, warnings);
        } catch (Exception e) {
            // 双保险: 任何没预料到的解析错误都不允许把转换流程带崩
            warnings.add(FILE_NAME + " 解析时出错(" + e.getMessage() + "), 本次用默认配置");
            return defaults();
        }
    }

    private static ZoneConfig parse(JsonObject root, List<String> w) {
        int radiusMin = DEFAULT_RADIUS_MIN;
        int radiusMax = DEFAULT_RADIUS_MAX;
        Range outerRatio = DEFAULT_OUTER_RATIO;

        JsonObject city = obj(root, "城市", w);
        if (city != null) {
            radiusMin = intField(city, "最小半径", DEFAULT_RADIUS_MIN, w);
            radiusMax = intField(city, "最大半径", DEFAULT_RADIUS_MAX, w);
            outerRatio = rangeField(city, "外城占比", DEFAULT_OUTER_RATIO, w);
        }
        if (radiusMin > radiusMax) {
            w.add(FILE_NAME + ": 城市.最小半径(" + radiusMin + ") 大于 最大半径(" + radiusMax + "), 两个都用默认值");
            radiusMin = DEFAULT_RADIUS_MIN;
            radiusMax = DEFAULT_RADIUS_MAX;
        }

        Map<String, Zone> zones = new LinkedHashMap<>();
        JsonObject zonesObj = obj(root, "城区", w);
        if (zonesObj != null) {
            for (Map.Entry<String, JsonElement> e : zonesObj.entrySet()) {
                if (e.getKey().startsWith("_")) {
                    continue;   // 说明字段
                }
                if (!e.getValue().isJsonObject()) {
                    w.add(FILE_NAME + ": 城区." + e.getKey() + " 不是对象, 已忽略该城区");
                    continue;
                }
                zones.put(e.getKey(), parseZone(e.getKey(), e.getValue().getAsJsonObject(), w));
            }
        }
        return new ZoneConfig(radiusMin, radiusMax, outerRatio, zones);
    }

    private static Zone parseZone(String zoneName, JsonObject obj, List<String> w) {
        Map<String, SubZone> subs = new LinkedHashMap<>();
        JsonObject subObj = obj(obj, "分区", w);
        if (subObj != null) {
            for (Map.Entry<String, JsonElement> e : subObj.entrySet()) {
                if (e.getKey().startsWith("_")) {
                    continue;
                }
                if (!e.getValue().isJsonObject()) {
                    w.add(FILE_NAME + ": 城区." + zoneName + ".分区." + e.getKey() + " 不是对象, 已忽略");
                    continue;
                }
                subs.put(e.getKey(), parseSubZone(zoneName, e.getKey(), e.getValue().getAsJsonObject(), w));
            }
        }
        return new Zone(subs);
    }

    private static SubZone parseSubZone(String zoneName, String subName, JsonObject obj, List<String> w) {
        String at = "城区." + zoneName + ".分区." + subName;
        Range ratio = rangeField(obj, "占比", null, w);   // 占比缺失 = 后面 Mixin 阶段再均分, 这里允许 null
        LootPool loot = poolField(obj, "战利品池", at, w);
        Map<String, BuildingCfg> buildings = new LinkedHashMap<>();
        JsonObject bObj = obj(obj, "建筑", w);
        if (bObj != null) {
            for (Map.Entry<String, JsonElement> e : bObj.entrySet()) {
                if (e.getKey().startsWith("_")) {
                    continue;
                }
                if (!e.getValue().isJsonObject()) {
                    w.add(FILE_NAME + ": " + at + ".建筑." + e.getKey() + " 不是对象, 已忽略");
                    continue;
                }
                JsonObject one = e.getValue().getAsJsonObject();
                CountRange count = countField(one, "数量", at + ".建筑." + e.getKey(), w);
                LootPool bLoot = poolField(one, "战利品池", at + ".建筑." + e.getKey(), w);
                buildings.put(e.getKey(), new BuildingCfg(count, bLoot));
            }
        }
        return new SubZone(ratio, loot, buildings);
    }

    // ------------------------------------------------------------------
    // 字段级容错解析
    // ------------------------------------------------------------------

    /** 取子对象; 缺失返回 null(不警告 —— 有些节本来就是可选的), 类型不对记警告。 */
    private static JsonObject obj(JsonObject parent, String key, List<String> w) {
        if (!parent.has(key)) {
            return null;
        }
        JsonElement e = parent.get(key);
        if (e.isJsonObject()) {
            return e.getAsJsonObject();
        }
        w.add(FILE_NAME + ": " + key + " 应该是对象, 实际是 " + typeName(e) + ", 已忽略");
        return null;
    }

    private static int intField(JsonObject parent, String key, int def, List<String> w) {
        if (!parent.has(key)) {
            return def;
        }
        JsonElement e = parent.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        w.add(FILE_NAME + ": " + key + " 应该是整数, 实际是 " + typeName(e) + ", 用默认值 " + def);
        return def;
    }

    /** "占比": 数字 = 固定值; [min,max] = 区间; 缺失返回 def(可为 null)。 */
    private static Range rangeField(JsonObject parent, String key, Range def, List<String> w) {
        if (!parent.has(key)) {
            return def;
        }
        JsonElement e = parent.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double v = e.getAsDouble();
            return new Range(v, v);
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 2 && a.get(0).isJsonPrimitive() && a.get(1).isJsonPrimitive()
                    && a.get(0).getAsJsonPrimitive().isNumber() && a.get(1).getAsJsonPrimitive().isNumber()) {
                double lo = a.get(0).getAsDouble();
                double hi = a.get(1).getAsDouble();
                if (lo > hi) {
                    w.add(FILE_NAME + ": " + key + " 区间下限大于上限, 已自动交换");
                    double t = lo;
                    lo = hi;
                    hi = t;
                }
                return new Range(lo, hi);
            }
        }
        w.add(FILE_NAME + ": " + key + " 应该是数字或 [最小,最大], 实际是 " + typeName(e) + ", 已忽略");
        return def;
    }

    /** "数量": [min,max] = 上下限; "完全随机" = null(不限制); 缺省 = AT_LEAST_ONE(至少生成1次)。 */
    private static CountRange countField(JsonObject parent, String key, String at, List<String> w) {
        if (!parent.has(key)) {
            return AT_LEAST_ONE;
        }
        JsonElement e = parent.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                && e.getAsString().equals("完全随机")) {
            return null;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 2 && a.get(0).isJsonPrimitive() && a.get(1).isJsonPrimitive()
                    && a.get(0).getAsJsonPrimitive().isNumber() && a.get(1).getAsJsonPrimitive().isNumber()) {
                int lo = a.get(0).getAsInt();
                int hi = a.get(1).getAsInt();
                if (lo > hi) {
                    w.add(FILE_NAME + ": " + at + "." + key + " 下限大于上限, 已自动交换");
                    int t = lo;
                    lo = hi;
                    hi = t;
                }
                return new CountRange(lo, hi);
            }
        }
        w.add(FILE_NAME + ": " + at + "." + key + " 应该是 [最少,最多] 或 \"完全随机\", 已按默认(至少1次)处理");
        return AT_LEAST_ONE;
    }

    /** "战利品池": {"物品":[{id,数量:[a,b],权重:w}, ...]}; 缺失 = null(用失落城市默认)。 */
    private static LootPool poolField(JsonObject parent, String key, String at, List<String> w) {
        if (!parent.has(key)) {
            return null;
        }
        JsonElement e = parent.get(key);
        if (!e.isJsonObject()) {
            w.add(FILE_NAME + ": " + at + "." + key + " 应该是对象, 已忽略");
            return null;
        }
        JsonObject pool = e.getAsJsonObject();
        List<LootItem> items = new ArrayList<>();
        JsonElement arr = pool.get("物品");
        if (arr != null && arr.isJsonArray()) {
            int i = 0;
            for (JsonElement item : arr.getAsJsonArray()) {
                i++;
                if (!item.isJsonObject()) {
                    w.add(FILE_NAME + ": " + at + "." + key + " 第 " + i + " 件物品不是对象, 已跳过");
                    continue;
                }
                JsonObject io = item.getAsJsonObject();
                JsonElement idEl = io.get("id");
                if (idEl == null || !idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isString()
                        || idEl.getAsString().isBlank()) {
                    w.add(FILE_NAME + ": " + at + "." + key + " 第 " + i + " 件物品缺少 id, 已跳过");
                    continue;
                }
                int cMin = 1;
                int cMax = 1;
                JsonElement cnt = io.get("数量");
                if (cnt != null) {
                    if (cnt.isJsonPrimitive() && cnt.getAsJsonPrimitive().isNumber()) {
                        cMin = cMax = cnt.getAsInt();
                    } else if (cnt.isJsonArray() && cnt.getAsJsonArray().size() == 2
                            && cnt.getAsJsonArray().get(0).isJsonPrimitive()
                            && cnt.getAsJsonArray().get(1).isJsonPrimitive()) {
                        cMin = cnt.getAsJsonArray().get(0).getAsInt();
                        cMax = cnt.getAsJsonArray().get(1).getAsInt();
                        if (cMin > cMax) {
                            int t = cMin;
                            cMin = cMax;
                            cMax = t;
                        }
                    } else {
                        w.add(FILE_NAME + ": " + at + "." + key + " 第 " + i + " 件物品的 数量 格式不对, 已按 1 处理");
                    }
                }
                int weight = 1;
                JsonElement wt = io.get("权重");
                if (wt != null && wt.isJsonPrimitive() && wt.getAsJsonPrimitive().isNumber()) {
                    weight = Math.max(1, wt.getAsInt());
                }
                items.add(new LootItem(idEl.getAsString(), Math.max(0, cMin), Math.max(0, cMax), weight));
            }
        }
        if (items.isEmpty()) {
            w.add(FILE_NAME + ": " + at + "." + key + " 的物品列表是空的, 该池不生效(容器保持失落城市默认战利品)");
            return null;
        }
        return new LootPool(List.copyOf(items));
    }

    private static String typeName(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonObject()) {
            return "对象";
        }
        if (e.isJsonArray()) {
            return "数组";
        }
        return e.getAsJsonPrimitive().isString() ? "字符串" : "数字";
    }

    // ==================================================================
    // 首次运行: 写模板
    // ==================================================================

    /** 写一份带 {@code _说明} 字段的模板(JSON 不支持注释, 说明只能塞在字段里)。 */
    private static void writeTemplate(Path configDir, List<String> warnings) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            try (Writer wr = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                wr.write(GSON.toJson(templateJson()));
            }
        } catch (IOException e) {
            warnings.add("无法写入 " + FILE_NAME + " 模板: " + e.getMessage());
        }
    }

    private static JsonObject templateJson() {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "城区配置。改完重启游戏生效。本文件不存在时会自动重新生成这份模板。"
                + "规则: 占比填一个数=固定值, 填 [最小,最大]=每座城随机取一个值;"
                + "数量填 [最少,最多]=上下限, 填 \"完全随机\" 或不写=不限制;"
                + "战利品池写在分区层=该分区所有建筑共用, 写在某个建筑里=这栋楼单独覆盖;"
                + "没写战利品池的分区, 容器用失落城市默认战利品。");

        JsonObject city = new JsonObject();
        city.addProperty("_说明", "城市整体大小与外城占比。半径单位是区块的格子数(不是方块), 默认 "
                + DEFAULT_RADIUS_MIN + "/" + DEFAULT_RADIUS_MAX + " 区块 = "
                + (DEFAULT_RADIUS_MIN * 16) + "/" + (DEFAULT_RADIUS_MAX * 16) + " 方块。"
                + "外城占比指可见城市面积里属于外城的比例, 会换算成外城阈值: 中点 0.4375 约等于阈值 0.4, 数值越大外城越宽。"
                + "删掉某个键 = 用默认值(半径 " + DEFAULT_RADIUS_MIN + "/" + DEFAULT_RADIUS_MAX
                + " 区块, 外城占比 [0.40,0.475])。");
        city.addProperty("最小半径", DEFAULT_RADIUS_MIN);
        city.addProperty("最大半径", DEFAULT_RADIUS_MAX);
        JsonArray outer = new JsonArray();
        outer.add(DEFAULT_OUTER_RATIO.min());
        outer.add(DEFAULT_OUTER_RATIO.max());
        city.add("外城占比", outer);
        root.add("城市", city);

        JsonObject zones = new JsonObject();

        JsonObject inner = new JsonObject();
        JsonObject innerSubs = new JsonObject();
        JsonObject shop = new JsonObject();
        shop.addProperty("_说明", "示例: 商业区占比写 2(面积是占比 1 的城区的 2 倍, 占比是相对大小); 容器里刷面包。"
                + "把 建筑 节里的 示例建筑.schem 换成你真实的文件名, 就能给那一栋楼单独配数量和战利品。");
        shop.addProperty("占比", 2);
        shop.add("战利品池", poolJson(itemJson("minecraft:bread", 1, 3, 10)));
        JsonObject shopBuildings = new JsonObject();
        JsonObject sample = new JsonObject();
        sample.addProperty("数量", "完全随机");
        sample.add("战利品池", poolJson(itemJson("minecraft:iron_ingot", 1, 4, 5)));
        shopBuildings.add("示例建筑.schem", sample);
        shop.add("建筑", shopBuildings);
        innerSubs.add("商业区", shop);
        inner.add("分区", innerSubs);
        zones.add("内城", inner);

        JsonObject outerZone = new JsonObject();
        JsonObject outerSubs = new JsonObject();
        JsonObject houses = new JsonObject();
        houses.addProperty("_说明", "外城只有一个分区时占比写 1.0。物品列表留空 = 不配战利品, 用失落城市默认。");
        houses.addProperty("占比", 1.0);
        JsonObject emptyPool = new JsonObject();
        emptyPool.add("物品", new JsonArray());
        houses.add("战利品池", emptyPool);
        outerSubs.add("民房", houses);
        outerZone.add("分区", outerSubs);
        zones.add("外城", outerZone);

        root.add("城区", zones);
        return root;
    }

    private static JsonObject itemJson(String id, int min, int max, int weight) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        JsonArray cnt = new JsonArray();
        cnt.add(min);
        cnt.add(max);
        o.add("数量", cnt);
        o.addProperty("权重", weight);
        return o;
    }

    private static JsonObject poolJson(JsonObject... items) {
        JsonObject pool = new JsonObject();
        JsonArray arr = new JsonArray();
        for (JsonObject it : items) {
            arr.add(it);
        }
        pool.add("物品", arr);
        return pool;
    }
}
