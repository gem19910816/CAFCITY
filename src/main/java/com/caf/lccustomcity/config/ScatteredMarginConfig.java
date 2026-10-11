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
 * 野外建筑离城市距离配置 —— 「野外原版建筑只在城市 N 格以外生成」。
 *
 * <p>配置文件: {@code config/失落城市建筑/野外建筑离城市距离.json}。
 * 失落城市原版会在<b>非城市区块</b>随机放 5 种野外建筑(radiotower 无线电塔 /
 * cabin 小木屋 / oilrig 钻井平台 / highway_gas_station 加油站 / highway_restaurant 公路餐厅),
 * 它们可能刷在紧贴城市边缘的地方。打开本功能后, 离任何一座城市的城心
 * (城心半径 + 额外距离)以内的区块一律不放野外建筑。
 *
 * <p>实现见 {@code com.caf.lccustomcity.mixin.ScatteredMarginMixin} 和
 * {@code com.caf.lccustomcity.impl.ScatteredMargin}。
 *
 * <h2>容错原则(和 {@link GapConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 用内置默认值(200), 不报错</li>
 *   <li>JSON 语法错 / 类型不对 → 记一条警告, 用内置默认值, 不崩</li>
 *   <li>负数 → 当 0 处理(= 关闭功能)并记警告</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 * Mixin 在世界生成线程上读 {@link #distance()}, 所以用 volatile。
 */
public final class ScatteredMarginConfig {

    public static final String FILE_NAME = "野外建筑离城市距离.json";
    /** 配置文件里的键名。 */
    public static final String KEY = "野外建筑距城市中心的额外距离";
    /** 内置默认值: 200 方块。0 = 关闭该功能(恢复失落城市原生行为)。 */
    public static final int DEFAULT = 200;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时取值。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile int distance = DEFAULT;

    private ScatteredMarginConfig() {
    }

    /** 当前的额外距离(方块)。&lt;= 0 表示功能关闭。 */
    public static int distance() {
        return distance;
    }

    /** 直接设置取值(主要给测试用)。 */
    public static void install(int value) {
        distance = value;
    }

    /**
     * 读取配置并写进运行时取值; 任何出错都回落到内置默认值并记警告, 绝不抛异常。
     *
     * @param configDir {@code config/失落城市建筑} 目录; 传 null = 跳过文件读写, 用内置默认值
     * @param warnings  警告信息(记日志用)
     * @return 最终生效值
     */
    public static int load(Path configDir, List<String> warnings) {
        int value = DEFAULT;
        if (configDir == null) {
            distance = value;
            return value;
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            writeTemplate(configDir, warnings);
            distance = value;
            return value;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add(FILE_NAME + " 读取失败(" + e.getMessage() + "), 本次用内置默认值 " + DEFAULT);
            distance = value;
            return value;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has(KEY) && root.get(KEY).isJsonPrimitive() && root.get(KEY).getAsJsonPrimitive().isNumber()) {
                value = root.get(KEY).getAsInt();
                if (value < 0) {
                    warnings.add(FILE_NAME + ": " + KEY + " 不能是负数(" + value + "), 本次按 0 处理(功能关闭)");
                    value = 0;
                }
            } else if (root.has(KEY)) {
                warnings.add(FILE_NAME + ": " + KEY + " 应该是一个整数, 该项用内置默认值 " + DEFAULT);
            }
            // 缺失: 静默用默认值
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 不是合法的 JSON(" + e.getMessage() + "), 本次用内置默认值 " + DEFAULT);
            value = DEFAULT;
        }
        distance = value;
        return value;
    }

    /** 写一份带 {@code _说明} 字段的模板(JSON 不支持注释, 说明只能塞在字段里)。 */
    private static void writeTemplate(Path configDir, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "野外原版建筑(无线电塔/小木屋/钻井平台/加油站/公路餐厅)离城市多远才允许生成, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值 " + DEFAULT + "。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "单位: 方块。意思是: 离任何一座城市的城心不超过 (城市半径 + 这个数) 的区块, 一律不放野外建筑。"
                        + "默认 200 = 城市边缘再往外 200 格内都不会出现野外建筑; "
                        + "0 = 关闭该功能, 恢复失落城市原生行为(紧贴城市也可能刷)。"
                        + "负数会被当作 0 处理。");
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
