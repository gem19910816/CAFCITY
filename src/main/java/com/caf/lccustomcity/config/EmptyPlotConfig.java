package com.caf.lccustomcity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 地皮填充配置 —— 「空地也要放建筑」开关(默认开)。
 *
 * <p>配置文件: {@code config/失落城市建筑/地皮填充.json}。
 * 打开时(默认), 单格(1x1)地皮只要<b>不在</b>道路 / 预定义街道 / 公路净空 / 铁路净空 上,
 * 就不再被"建筑几率掷骰"挡掉, 强制放上建筑 —— 也就是消除城里那些莫名其妙的小空地。
 * 街区内的大楼(多格建筑)本规则完全不管。
 * 实现见 {@code com.caf.lccustomcity.mixin.FillPlotsMixin} 和
 * {@code com.caf.lccustomcity.impl.EmptyPlotRule}。
 *
 * <h2>为什么单独一个小文件而不是 {@link CityGenConfig}</h2>
 * {@link CityGenConfig} 的每一项都对应 {@code LostCityProfile} 里的一个真实字段,
 * 靠反射写进失落城市的配置档; 而这个开关是本模组自己的行为, 失落城市没有对应字段,
 * 所以单独一个小文件, 顺便承担<b>运行时开关的存放点</b>(见 {@link #enabled()})。
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
public final class EmptyPlotConfig {

    public static final String FILE_NAME = "地皮填充.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "空地也要放建筑";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private EmptyPlotConfig() {
    }

    /** 当前是否启用"空地也要放建筑"。 */
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
        root.addProperty("_说明", "地皮填充设置, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=单格(1x1)地皮只要不在道路/预定义街道/公路净空/铁路净空上, "
                        + "就强制放上建筑(消除城里的小空地); "
                        + "false=完全恢复失落城市原生行为(建筑几率掷骰没过就留空)。"
                        + "注意: 开启后城市会明显变密(几乎没有空地), 大楼(多格建筑)不受影响。");
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
