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

/** 高速路铺面开关：六张高速图纸使用城市空地的六种材料和比例。 */
public final class HighwayStyleConfig {

    public static final String FILE_NAME = "高速路风格.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "高速路换成我们的路面";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private HighwayStyleConfig() {
    }

    /** 当前是否启用"高速路换成我们的路面"。 */
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
     * @param configDir {@code config/失落城市建筑} 目录; 传 null = 跳过文件读写, 用内置默认值
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
        root.addProperty("_说明", "失落城市高速路的路面风格设置, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=高速路铺面使用城市空地的草方块、深板岩圆石、深板岩、青色陶瓦、黑石、玄武岩，"
                + "比例参考 sy.nbt；仅覆盖高速图纸的路面字符。false=保留原版高速铺面。重启后新生成区域生效。");
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
