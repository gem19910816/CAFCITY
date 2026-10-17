package com.caf.lccustomcity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
 * 解析根配置 {@code 城市风格配置.json/城市生成配置} —— 强制覆盖失落城市配置档的那 43 项设置。
 *
 * <p>以前这些值写死在 {@code LcCustomCityMod.FORCED_PROFILE_VALUES} 里, 玩家想改只能找作者。
 * 现在它们由本文件控制; {@code FORCED_PROFILE_VALUES} 仍保留, 作为"内置默认值"
 * (配置文件缺失 / JSON 坏掉 / 某个字段写错时的回退来源)。
 *
 * <h2>容错原则(和 {@link ZoneConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 然后全部用内置默认值, 不报错</li>
 *   <li>JSON 语法错 → 记一条警告, 全部用内置默认值, 不崩</li>
 *   <li>某个字段缺失 → 该字段用内置默认值(不警告, 允许玩家只改想改的);
 *       类型不对 → 该字段用内置默认值并记警告, 其余字段照常生效</li>
 *   <li>不认识的键 → 记一条警告(多半是笔误), 忽略</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 * 反射写字段时的类型匹配由调用方({@code LcCustomCityMod.forceProfileSettings})负责。
 */
public final class CityGenConfig {

    public static final String FILE_NAME = "城市生成配置.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 配置值的目标类型, 对应 LostCityProfile 里字段的类型。 */
    public enum Type { FLOAT, DOUBLE, INT, BOOLEAN, STRING }

    /**
     * 一项配置: 配置键 → (LostCityProfile 字段名, 类型, 说明)。
     *
     * @param section 配置文件里的分组名(顶层对象键)
     * @param key     分组里的键(中文, 给玩家看的)
     * @param field   LostCityProfile 的字段名, 必须一字不差(注意 RUBBLELAYER / GROUNDLEVEL 没有下划线)
     * @param type    字段类型, 反射写入时严格匹配
     * @param note    写进模板里该项旁边的 {@code _说明_<键>} 字段
     */
    public record Entry(String section, String key, String field, Type type, String note) {
    }

    // ==================================================================
    // 映射表: 配置键 -> (字段名, 类型)。默认值不在这里, 由调用方传入
    // (内置默认值唯一来源是 LcCustomCityMod.FORCED_PROFILE_VALUES, 避免两处写死漂移)。
    // ==================================================================

    public static final String SEC_RUIN = "破坏与废墟";
    public static final String SEC_HEIGHT = "城市高度";
    public static final String SEC_TERRAIN = "地形过渡";
    public static final String SEC_DENSITY = "城市大小与密度";
    public static final String SEC_BUILDING = "建筑生成";

    public static final List<Entry> ENTRIES = List.of(
            // ---- 破坏与废墟 ----
            new Entry(SEC_RUIN, "废墟出现几率", "RUIN_CHANCE", Type.FLOAT, "0=关。废墟会把建筑腐蚀得只剩框架"),
            new Entry(SEC_RUIN, "爆炸出现几率", "EXPLOSION_CHANCE", Type.FLOAT, "0=关。爆炸会在建筑上炸出洞"),
            new Entry(SEC_RUIN, "小爆炸出现几率", "MINI_EXPLOSION_CHANCE", Type.FLOAT, "0=关"),
            new Entry(SEC_RUIN, "碎砖层", "RUBBLELAYER", Type.BOOLEAN, "true=城市地面下垫一层碎砖"),
            new Entry(SEC_RUIN, "藤蔓出现几率", "VINE_CHANCE", Type.FLOAT, "0=关"),
            // ---- 城市高度 ----
            new Entry(SEC_HEIGHT, "零级高度上限", "CITY_LEVEL0_HEIGHT", Type.INT, "大于所有地形高度=全城锁 0 级(平坦城市的核心)"),
            new Entry(SEC_HEIGHT, "一级高度", "CITY_LEVEL1_HEIGHT", Type.INT, "兜底值, 平坦城市实际用不到"),
            new Entry(SEC_HEIGHT, "二级高度", "CITY_LEVEL2_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "三级高度", "CITY_LEVEL3_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "四级高度", "CITY_LEVEL4_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "五级高度", "CITY_LEVEL5_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "六级高度", "CITY_LEVEL6_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "七级高度", "CITY_LEVEL7_HEIGHT", Type.INT, "兜底值"),
            new Entry(SEC_HEIGHT, "地面高度", "GROUNDLEVEL", Type.INT, "城市一楼地板所在的 Y 高度, 71=海平面上一层"),
            new Entry(SEC_HEIGHT, "使用平均高度图", "USE_AVG_HEIGHTMAP", Type.BOOLEAN, "true=城市层级参考周边平均高度(平坦城市下没用, 双保险)"),
            new Entry(SEC_HEIGHT, "城市最低生成高度", "CITY_MINHEIGHT", Type.INT, "地面低于此不生成城市。已放开到 -64, 否则深河/深海会在城里留洞"),
            new Entry(SEC_HEIGHT, "城市最高生成高度", "CITY_MAXHEIGHT", Type.INT, "地面高于此不生成城市。已放开到 384, 否则原版高山会杵在城市中间不被铲平"),
            // ---- 地形过渡 ----
            new Entry(SEC_TERRAIN, "下缘修正最小", "TERRAIN_FIX_LOWER_MIN_OFFSET", Type.INT, "城市边缘地形向下过渡的随机幅度下限"),
            new Entry(SEC_TERRAIN, "下缘修正最大", "TERRAIN_FIX_LOWER_MAX_OFFSET", Type.INT, "上限"),
            new Entry(SEC_TERRAIN, "上缘修正最小", "TERRAIN_FIX_UPPER_MIN_OFFSET", Type.INT, "向上过渡的随机幅度下限"),
            new Entry(SEC_TERRAIN, "上缘修正最大", "TERRAIN_FIX_UPPER_MAX_OFFSET", Type.INT, "上限"),
            new Entry(SEC_TERRAIN, "海洋修正边界", "OCEAN_CORRECTION_BORDER", Type.INT, "城市边缘向海面过渡的宽度"),
            // ---- 城市大小与密度 ----
            new Entry(SEC_DENSITY, "城市出现几率", "CITY_CHANCE", Type.DOUBLE, "每个区块成为城心的几率, 0.01=1%"),
            new Entry(SEC_DENSITY, "城市阈值", "CITY_THRESHOLD", Type.FLOAT, "城市因子高于此才算城里"),
            new Entry(SEC_DENSITY, "柏林噪声缩放", "CITY_PERLIN_SCALE", Type.DOUBLE, "城市分布噪声(城市出现几率为 -1 时才用)"),
            new Entry(SEC_DENSITY, "柏林噪声偏移", "CITY_PERLIN_OFFSET", Type.DOUBLE, "同上"),
            new Entry(SEC_DENSITY, "柏林噪声内部缩放", "CITY_PERLIN_INNERSCALE", Type.DOUBLE, "同上"),
            // ---- 建筑生成 ----
            new Entry(SEC_BUILDING, "建筑出现几率", "BUILDING_CHANCE", Type.FLOAT, "每个可建格放建筑的几率"),
            new Entry(SEC_BUILDING, "最少楼层", "BUILDING_MINFLOORS", Type.INT, ""),
            new Entry(SEC_BUILDING, "最多楼层", "BUILDING_MAXFLOORS", Type.INT, ""),
            new Entry(SEC_BUILDING, "最少楼层几率权重", "BUILDING_MINFLOORS_CHANCE", Type.INT, "楼层数随机时的权重"),
            new Entry(SEC_BUILDING, "最多楼层几率权重", "BUILDING_MAXFLOORS_CHANCE", Type.INT, "同上"),
            new Entry(SEC_BUILDING, "最少地下室", "BUILDING_MINCELLARS", Type.INT, ""),
            new Entry(SEC_BUILDING, "最多地下室", "BUILDING_MAXCELLARS", Type.INT, ""),
            new Entry(SEC_BUILDING, "公园几率", "PARK_CHANCE", Type.FLOAT, "本模组已清空公园内容, 此项实际无影响"),
            new Entry(SEC_BUILDING, "走廊几率", "CORRIDOR_CHANCE", Type.FLOAT, "地铁走廊"),
            new Entry(SEC_BUILDING, "桥梁几率", "BRIDGE_CHANCE", Type.FLOAT, "高架桥"),
            new Entry(SEC_BUILDING, "喷泉几率", "FOUNTAIN_CHANCE", Type.FLOAT, "街道喷泉")
    );

    /** 每个分组的说明, 写进模板的 {@code _说明} 字段。 */
    private static final Map<String, String> SECTION_NOTES = Map.of(
            SEC_RUIN, "废墟/爆炸/藤蔓等破坏效果。全部关掉后你的建筑一个方块都不会被腐蚀。",
            SEC_HEIGHT, "城市地面高度。默认: 全城锁 0 级、地基统一在 Y=71, 不随地形起伏(平坦城市)。",
            SEC_TERRAIN, "城市边缘的自然地形向城市地面过渡的幅度(nodamage 档同款)。",
            SEC_DENSITY, "城市多大、多密(nodamage 档同款, 保证任何配置档选出来观感一致)。",
            SEC_BUILDING, "建筑密度、楼层数、街道设施(nodamage 档同款)。"
    );

    private CityGenConfig() {
    }

    /** 按字段名查配置键(测试用)。找不到返回 null。 */
    public static String keyFor(String field) {
        for (Entry e : ENTRIES) {
            if (e.field().equals(field)) {
                return e.key();
            }
        }
        return null;
    }

    /** 按字段名查分组名(测试用)。找不到返回 null。 */
    public static String sectionFor(String field) {
        for (Entry e : ENTRIES) {
            if (e.field().equals(field)) {
                return e.section();
            }
        }
        return null;
    }

    /** 全内置默认值: 字段名 -> 默认值。 */
    public static Map<String, Object> defaultsOnly(Map<String, Object> defaults) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            out.put(e.field(), defaults.get(e.field()));
        }
        return out;
    }

    /**
     * 读取配置; 任何出错都回落到内置默认值并记警告, 绝不抛异常。
     * 文件不存在时会先写一份带说明的模板, 方便玩家照着改。
     *
     * @param configDir {@code config/CAF失落城市配置} 目录; 传 null = 跳过文件读写, 直接用内置默认值
     * @param defaults  内置默认值(字段名 -&gt; 值), 来源是 {@code LcCustomCityMod.FORCED_PROFILE_VALUES}
     * @return 字段名 -&gt; 最终生效值(永远覆盖全部 43 项), 顺序与 {@link #ENTRIES} 一致
     */
    public static Map<String, Object> load(Path configDir, Map<String, Object> defaults, List<String> warnings) {
        if (configDir == null) {
            return defaultsOnly(defaults);
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            writeTemplate(configDir, defaults, warnings);
            return defaultsOnly(defaults);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add(FILE_NAME + " 读取失败(" + e.getMessage() + "), 本次用内置默认值");
            return defaultsOnly(defaults);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 不是合法的 JSON(" + e.getMessage() + "), 本次用内置默认值, 请检查语法");
            return defaultsOnly(defaults);
        }
        try {
            return parse(root, defaults, warnings);
        } catch (Exception e) {
            // 双保险: 任何没预料到的解析错误都不允许把强制覆盖流程带崩
            warnings.add(FILE_NAME + " 解析时出错(" + e.getMessage() + "), 本次用内置默认值");
            return defaultsOnly(defaults);
        }
    }

    /** Reads shared Lost Cities profile settings from the root city configuration. */
    public static Map<String, Object> loadRoot(Path configDir, Map<String, Object> defaults,
                                               List<String> warnings) {
        JsonObject root = RootConfigFile.read(configDir, warnings);
        JsonElement section = root.get("城市生成配置");
        if (section == null || !section.isJsonObject()) return defaultsOnly(defaults);
        try {
            return parse(section.getAsJsonObject(), defaults, warnings);
        } catch (RuntimeException e) {
            warnings.add(CityStageConfig.FILE_NAME + ".城市生成配置 解析失败，使用内置默认值: " + e.getMessage());
            return defaultsOnly(defaults);
        }
    }

    /** Profile-only template for the root document; stage layout is stored per stage. */
    public static JsonObject profileTemplate(Map<String, Object> defaults) {
        JsonObject root = templateJson(defaults);
        root.addProperty("_说明", "城市生成参数会强制写入每一个失落城市配置档。删掉某一项即使用该项的内置默认值；配置统一保存在根目录 城市风格配置.json 中。");
        root.remove("城市");
        root.remove("建筑布局");
        root.remove("建筑");
        return root;
    }

    private static Map<String, Object> parse(JsonObject root, Map<String, Object> defaults, List<String> w) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            Object def = defaults.get(e.field());
            JsonElement v = null;
            JsonElement secEl = root.get(e.section());
            if (secEl != null && secEl.isJsonObject()) {
                v = secEl.getAsJsonObject().get(e.key());
            }
            String at = e.section() + "." + e.key();
            if (v == null) {
                out.put(e.field(), def);   // 缺失: 静默用默认值, 允许玩家只写想改的项
                continue;
            }
            Object parsed = convert(v, e.type());
            if (parsed == null) {
                w.add(FILE_NAME + ": " + at + " 应该是" + typeName(e.type()) + ", 实际是 "
                        + jsonType(v) + ", 该项用内置默认值 " + def);
                out.put(e.field(), def);
            } else {
                out.put(e.field(), parsed);
            }
        }
        // 不认识的键: 提醒玩家(多半是笔误), 忽略
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonElement> sec : root.entrySet()) {
            if (sec.getKey().startsWith("_")) {
                continue;
            }
            if (sec.getKey().equals("城市") || sec.getKey().equals("建筑布局") || sec.getKey().equals("建筑")) {
                continue; // Parsed by StageLayoutConfig; these are stage-specific layout rules.
            }
            if (!sec.getValue().isJsonObject()) {
                unknown.add(sec.getKey());
                continue;
            }
            for (String key : sec.getValue().getAsJsonObject().keySet()) {
                if (key.startsWith("_")) {
                    continue;
                }
                boolean known = false;
                for (Entry e : ENTRIES) {
                    if (e.section().equals(sec.getKey()) && e.key().equals(key)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    unknown.add(sec.getKey() + "." + key);
                }
            }
        }
        for (String u : unknown) {
            w.add(FILE_NAME + ": 不认识 " + u + ", 已忽略(检查是不是笔误)");
        }
        return out;
    }

    /** 把 JSON 值转成目标类型; 转不了返回 null。数值允许 int 写给 float/double。 */
    private static Object convert(JsonElement v, Type type) {
        switch (type) {
            case FLOAT:
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    return v.getAsFloat();
                }
                return null;
            case DOUBLE:
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    return v.getAsDouble();
                }
                return null;
            case INT:
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double d = v.getAsDouble();
                    if (d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return v.getAsInt();
                    }
                }
                return null;
            case BOOLEAN:
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                    return v.getAsBoolean();
                }
                return null;
            case STRING:
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    return v.getAsString();
                }
                return null;
            default:
                return null;
        }
    }

    private static String typeName(Type t) {
        switch (t) {
            case FLOAT:
            case DOUBLE:
                return "数字";
            case INT:
                return "整数";
            case BOOLEAN:
                return "true/false";
            case STRING:
                return "字符串";
            default:
                return "?";
        }
    }

    private static String jsonType(JsonElement e) {
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
    private static void writeTemplate(Path configDir, Map<String, Object> defaults, List<String> warnings) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            try (Writer wr = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                wr.write(GSON.toJson(templateJson(defaults)));
            }
        } catch (IOException e) {
            warnings.add("无法写入 " + FILE_NAME + " 模板: " + e.getMessage());
        }
    }

    static JsonObject templateJson(Map<String, Object> defaults) {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "城市生成配置: 强制写进【每一个】失落城市配置档的值, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板。删掉某一项 = 该项用内置默认值;"
                + "整个文件删掉 = 全部用内置默认值。"
                + "这里的值会盖掉创建世界时 Cities 按钮选的配置档 —— 选哪个档结果都一样。");
        // 保持 ENTRIES 的顺序分组
        Map<String, JsonObject> sections = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            JsonObject sec = sections.computeIfAbsent(e.section(), k -> {
                JsonObject o = new JsonObject();
                String note = SECTION_NOTES.get(k);
                if (note != null) {
                    o.addProperty("_说明", note);
                }
                return o;
            });
            Object def = defaults.get(e.field());
            if (def instanceof Boolean b) {
                sec.addProperty(e.key(), b);
            } else if (def instanceof Number n) {
                sec.addProperty(e.key(), n);
            } else {
                sec.addProperty(e.key(), String.valueOf(def));
            }
            if (!e.note().isEmpty()) {
                sec.addProperty("_说明_" + e.key(), e.note());
            }
        }
        for (Map.Entry<String, JsonObject> e : sections.entrySet()) {
            root.add(e.getKey(), e.getValue());
        }
        root.add("城市", layoutCityTemplate());
        root.add("建筑布局", layoutBuildingTemplate());
        root.add("建筑", new JsonObject());
        return root;
    }

    private static JsonObject layoutCityTemplate() {
        JsonObject city = new JsonObject();
        city.addProperty("_说明", "阶段城市布局。半径单位为区块；间隔的 null 表示不限制。");
        JsonArray radius = new JsonArray(); radius.add(50); radius.add(50); city.add("城市半径", radius);
        city.addProperty("城心生成概率", 1.0);
        JsonObject spacing = new JsonObject(); spacing.add("最小", JsonNull.INSTANCE); spacing.add("最大", JsonNull.INSTANCE);
        city.add("城心间隔", spacing);
        return city;
    }

    private static JsonObject layoutBuildingTemplate() {
        JsonObject layout = new JsonObject();
        JsonObject spacing = new JsonObject(); spacing.addProperty("最小", 1); spacing.addProperty("最大", 1);
        layout.add("建筑间隔", spacing);
        layout.addProperty("连续生成最大宽度", 2);
        layout.addProperty("连续生成最大长度", 6);
        layout.addProperty("相同建筑间隔", 10);
        return layout;
    }
}
