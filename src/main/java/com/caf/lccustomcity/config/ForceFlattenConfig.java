package com.caf.lccustomcity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 强制削平配置 —— 「城市范围内一律削平」开关(默认开)。
 *
 * <p>配置文件: {@code config/CAF失落城市配置/强制削平.json}。
 * 失落城市默认开着"结构回避": 村庄/丛林神庙等原版结构所在的区块
 * (以及周围 3x3 区块)会被强制踢出城市, 于是<b>不削平</b>,
 * 城里就出现长满树的小山。打开本开关后(默认), 结构回避被整体关掉,
 * 城市范围内一律按城市处理、一律削平；附属管理的城市还会先补实地基，
 * 并拦截之后才放置、占地与城市相交的原版地表结构。
 *
 * <p>实现见 {@code com.caf.lccustomcity.mixin.StructureAvoidanceMixin}、
 * {@code CityFoundationMixin} 与 {@code VanillaSurfaceStructureMixin}。
 *
 * <h2>容错原则(和 {@link GapConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 用内置默认值(开), 不报错</li>
 *   <li>JSON 语法错 / 类型不对 → 记一条警告, 用内置默认值, 不崩</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 * Mixin 在世界生成线程上读 {@link #enabled()}, 所以用 volatile。
 */
public final class ForceFlattenConfig {

    public static final String FILE_NAME = "强制削平.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "城市范围内一律削平";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private ForceFlattenConfig() {
    }

    /** 当前是否启用"城市范围内一律削平"。 */
    public static boolean enabled() {
        return enabled;
    }

    /** 直接设置开关(主要给测试用)。 */
    public static void install(boolean value) {
        enabled = value;
    }

    /**
     * 读取配置并写进运行时开关; 任何出错都回落到内置默认值(开)并记警告, 绝不抛异常。
     *
     * @param configDir {@code config/CAF失落城市配置} 目录; 传 null = 跳过文件读写, 用内置默认值
     * @param warnings  警告信息(记日志用)
     * @return 最终生效值
     */
    public static boolean load(Path configDir, List<String> warnings) {
        boolean value = DEFAULT;
        if (configDir == null) {
            enabled = value;
            return value;
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            writeTemplate(configDir, warnings);
            enabled = value;
            return value;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add(FILE_NAME + " 读取失败(" + e.getMessage() + "), 本次用内置默认值 " + DEFAULT);
            enabled = value;
            return value;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has(KEY) && root.get(KEY).isJsonPrimitive() && root.get(KEY).getAsJsonPrimitive().isBoolean()) {
                value = root.get(KEY).getAsBoolean();
            } else if (root.has(KEY)) {
                warnings.add(FILE_NAME + ": " + KEY + " 应该是 true/false, 该项用内置默认值 " + DEFAULT);
            }
            // 缺失: 静默用默认值
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 不是合法的 JSON(" + e.getMessage() + "), 本次用内置默认值 " + DEFAULT);
        }
        enabled = value;
        return value;
    }

    /** 写一份带 {@code _说明} 字段的模板(JSON 不支持注释, 说明只能塞在字段里)。 */
    private static void writeTemplate(Path configDir, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "城市范围内是否一律削平(关掉失落城市的结构回避，并补实地基、阻止原版地表结构悬空), 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=村庄/丛林神庙等原版结构不再把城市区块踢出去, 城市范围内一律削平, "
                        + "附属城市先填实地面以下的空气与水等空隙；相交的原版地表结构整座停止放置，防止削平后悬空；"
                        + "地下结构仍按原版生成；"
                        + "false=恢复失落城市原生行为, 结构所在区块及周围一圈不削平、会长出原版小山。");
        try {
            Files.createDirectories(configDir);
            try (Writer wr = new OutputStreamWriter(
                    Files.newOutputStream(configDir.resolve(FILE_NAME)), StandardCharsets.UTF_8)) {
                wr.write(GSON.toJson(root));
            }
        } catch (IOException e) {
            warnings.add("无法写入 " + FILE_NAME + " 模板: " + e.getMessage());
        }
    }
}
