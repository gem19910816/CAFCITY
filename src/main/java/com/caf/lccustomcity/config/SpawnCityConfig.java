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
 * 出生点附近城市配置 —— 「保证出生点 N 格以内有一个城市<b>城心</b>」(默认 850)。
 *
 * <p>配置文件: {@code config/CAF失落城市配置/出生点附近城市.json}。
 *
 * <h2>这个功能做什么</h2>
 * 玩家要的是: <b>在出生点半径 850 格处刷一个城心</b>(原话: "将之前的 200 格看到城市边缘
 * 改为在出生点半径 850 格刷新一个城心")。语义是"<b>最近的城市的城心</b>距出生点不超过设定距离":
 * <ul>
 *   <li>出生点 850 格以内本来就有城的<b>城心</b> ⇒ <b>什么都不做</b>,
 *       绝不搬走已有的城、也不动既有的城市分布(只比城心距离, <b>与半径无关</b>);</li>
 *   <li>否则在出生点旁边"补"一座城: 让它的<b>城心</b>落在距出生点约 850 格处
 *       (城心必须按 16 方块对齐, 对齐造成的误差只会让城<b>更近一点</b>, 绝不会超过 850)。</li>
 * </ul>
 *
 * <p><b>为什么口径从"边缘距离"改成"城心距离"</b>: 旧口径要求"看得见的城区边缘"距出生点
 * 不超过 200 格, 于是城心被摆在 {@code 可见半径 + 200} 处 —— 半径 1000 的城就是 1200 格远,
 * 出生点周围两百格仍然是荒野。新口径直接把<b>城心</b>摆到 850 格: 半径 800 的城,
 * 它看得见的城区半径 = 800 × (1 - 城市阈值 0.2) = 640, 于是看得见的城区边缘大约在
 * 850 - 640 = <b>210 格</b>外, 玩家走两百来格就能看到城区。
 *
 * <h2>兼容旧键(重要)</h2>
 * 旧版本这个文件里的键叫 {@code 出生点城市边缘距离}(默认 200, 量的是"城心的边缘"到出生点)。
 * 那个键<b>已经作废, 代码一个字都不读它</b>: 新键 {@code 出生点城市中心距离} 缺失时直接用
 * 内置默认值 850, 旧键留在文件里既不报错也不生效。文件名保持不变
 * (仍然是 {@code 出生点附近城市.json})。
 *
 * <p>实现见 {@code com.caf.lccustomcity.mixin.SpawnCityMixin}(挂在
 * {@code City.getCityFactor} 的 RETURN 上)、
 * {@code com.caf.lccustomcity.impl.SpawnCityRule}(接游戏的那一半)和
 * {@code com.caf.lccustomcity.impl.SpawnCityPlan}(纯几何, 可离线测)。
 *
 * <h2>为什么能"绝不影响既有城市"</h2>
 * Mixin 只在失落城市原生浓度<b>正好是 0</b> 的区块上做叠加(0 → 合成浓度),
 * 已经有城市浓度的区块<b>一个字节都不改</b> —— 见
 * {@link com.caf.lccustomcity.impl.SpawnCityPlan#apply(float, float)}。
 *
 * <h2>容错原则(和 {@link GapConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 用内置默认值(850), 不报错</li>
 *   <li>JSON 语法错 / 类型不对 → 记一条警告, 用内置默认值, 不崩</li>
 *   <li>新键缺失(例如文件里只有旧键) → 静默用内置默认值 850, <b>旧键不读也不报错</b></li>
 *   <li>负数 → 当 0 处理(= 关闭功能)并记警告; 1~15 → 按 16 处理(城心要按区块对齐)</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 * Mixin 在世界生成线程上读 {@link #distance()}, 所以用 volatile。
 */
public final class SpawnCityConfig {

    public static final String FILE_NAME = "出生点附近城市.json";
    /** 配置文件里的键名。<b>这是城心距离</b>(旧键 出生点城市边缘距离 已作废, 不再读取)。 */
    public static final String KEY = "出生点城市中心距离";
    /** 内置默认值: 850 格(城心)。0 = 关闭该功能(完全恢复失落城市原生行为)。 */
    public static final int DEFAULT = 850;
    /**
     * 最小有效值(格)。城心必须落在区块边界上(失落城市的城市浓度是按
     * "城心区块的原点"算距离的), 所以比一个区块还小的目标距离没法精确做到 ——
     * 1~15 一律按 16 处理。
     */
    public static final int MIN_EFFECTIVE = 16;
    /** 最大有效值(格)。挡住明显写错的值(一千万格这种), 量级参考失落城市自己的 spawnCheckRadius 上限。 */
    public static final int MAX_EFFECTIVE = 100000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时取值。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile int distance = DEFAULT;

    private SpawnCityConfig() {
    }

    /** 当前要求的"出生点到最近城市<b>城心</b>的最大距离"(格)。&lt;= 0 表示功能关闭。 */
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
     * @param configDir {@code config/CAF失落城市配置} 目录; 传 null = 跳过文件读写, 用内置默认值
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
                } else if (value > 0 && value < MIN_EFFECTIVE) {
                    warnings.add(FILE_NAME + ": " + KEY + " 太小了(" + value + "), 本次按 " + MIN_EFFECTIVE
                            + " 处理(城心要按区块对齐, 小于一个区块做不到)");
                    value = MIN_EFFECTIVE;
                } else if (value > MAX_EFFECTIVE) {
                    warnings.add(FILE_NAME + ": " + KEY + " 太大了(" + value + "), 本次按 " + MAX_EFFECTIVE + " 处理");
                    value = MAX_EFFECTIVE;
                }
            } else if (root.has(KEY)) {
                warnings.add(FILE_NAME + ": " + KEY + " 应该是一个整数, 该项用内置默认值 " + DEFAULT);
            }
            // 缺失: 静默用默认值 850。文件里只写了作废的旧键(出生点城市边缘距离)也走这条路径 ——
            // 旧键一个字都不读, 也绝不因为它在而报警告。
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
        root.addProperty("_说明", "保证出生点附近有一座城 —— 量的是【城心】距离(默认 " + DEFAULT
                + " 格), 改完重启游戏生效。本文件不存在时会自动重新生成这份模板; "
                + "删掉整个文件 = 用内置默认值 " + DEFAULT + "。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "单位: 格(方块)。⚠ 这是【城心】距离, 不是【边缘】距离: "
                        + "意思是最近的城市的城心距出生点不超过这个数。"
                        + "如果出生点 " + DEFAULT + " 格以内本来就有城心, 什么都不做 —— "
                        + "既不会搬走已有的城, 也不会改动既有的城市分布; "
                        + "否则会在出生点旁边补一座城, 让它的城心落在距出生点约 " + DEFAULT + " 格处"
                        + "(城心必须按 16 方块对齐, 对齐只会让城更近一点, 绝不会超过这个数)。"
                        + "日志里会写一行: 出生点坐标 / 补出来的城的半径和城心坐标 / "
                        + "城心距出生点多少格 / 它看得见的城区边缘大约在多少格外。"
                        + "默认 " + DEFAULT + ": 半径 800 的城(城区配置.json 的默认半径), 看得见的城区半径 = "
                        + "800 x (1 - 城市阈值 0.2) = 640 ⇒ 看得见的城区边缘大约在 "
                        + DEFAULT + " - 640 = " + (DEFAULT - 640) + " 格外, 玩家走两百来格就能看到城区。"
                        + "0 = 关闭该功能, 完全恢复失落城市原生行为(出生点附近可能几百格都是荒野)。"
                        + "负数会被当作 0 处理; 1~" + (MIN_EFFECTIVE - 1) + " 会按 " + MIN_EFFECTIVE
                        + " 处理(城心必须落在区块边界上)。");
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
