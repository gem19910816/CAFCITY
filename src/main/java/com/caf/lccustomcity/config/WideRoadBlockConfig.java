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
 * 建筑不压马路配置 —— 「建筑不能压到宽路上」开关(默认开)。
 *
 * <p>配置文件: {@code config/失落城市建筑/建筑不压马路.json}。
 * 打开时(默认), 多格建筑(大楼)的整个占地里只要有一格落在"宽路带"上
 * (内城 3 宽主路带 / 次干道 2 宽带, 见 {@code WideRoadLogic}),
 * 这个位置就被否决, 失落城市会另找位置放楼。
 *
 * <h2>为什么需要它</h2>
 * 宽路系统只在"落地层"({@code BuildingInfo.getEffectivePlannedRoadType})把
 * 路带抬成路, 并没有改原始街道规划器; 而失落城市放多格建筑前查的是原始规划器
 * ({@code MultiChunk.canPlaceBuilding} 里的
 * {@code provider.getStreetPlanner().getRoadType(...)}), 路带两侧加宽出来的
 * 区块在规划器眼里还是空地, 于是楼会被放到 3 宽主路中间。本开关补上这道检查。
 *
 * <h2>写法模板</h2>
 * 与 {@link GapConfig} 完全同构: 文件缺失 → 写模板 + 用默认值;
 * JSON 坏 / 类型错 → 警告 + 默认值, 绝不抛异常。
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 * Mixin 在世界生成线程上读 {@link #enabled()}, 所以用 volatile。
 */
public final class WideRoadBlockConfig {

    public static final String FILE_NAME = "建筑不压马路.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "建筑不能压到宽路上";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private WideRoadBlockConfig() {
    }

    /** 当前是否启用"建筑不能压到宽路上"。 */
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
        root.addProperty("_说明", "多格建筑(大楼)与宽路的关系设置, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=多格建筑的整个占地只要有一格落在宽路带上(内城 3 宽主路 / 2 宽次干道), "
                        + "就不允许放在那里, 楼不会插到路中间; "
                        + "false=不检查宽路带(可能出现大楼盖在宽路上的情况)。");
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
