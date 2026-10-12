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
 * 「生成建筑自带的动态结构」开关(默认开)。
 *
 * <p>配置文件: {@code config/CAF失落城市配置/动态结构.json}。
 *
 * <h2>这个开关管什么</h2>
 * 建筑文件里除了方块, 还可能有<b>实体</b> —— 物品展示框、盔甲架, 以及机械动力(Create)的
 * <b>动态结构</b>({@code create:contraption} / {@code create:stationary_contraption},
 * 也就是"图纸里会转的机器")。失落城市自己<b>一个实体都不生成</b>(全源码没有
 * {@code spawnEntity}/{@code addFreshEntity}), 所以这些实体以前全丢。
 *
 * <ul>
 *   <li><b>true(默认)</b>: 建筑生成时把自带实体一并放回世界(动态结构会转起来);</li>
 *   <li><b>false</b>: 只放建筑方块，不生成自带实体；电梯轿厢等动态方块也不会出现。</li>
 * </ul>
 * 实体 NBT 存入生成区块，由游戏在区块加载后读取；不使用内存待办队列。
 *
 * <h2>没装 Create 会怎样</h2>
 * 实体是用<b>字符串 id</b> 从注册表查的, 查不到就跳过并记一条警告(每种 id 只警告一次),
 * 不会报错、不会崩。所以玩家没装某个模组时, 只是那部分实体不出现。
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 */
public final class DynamicStructureConfig {

    public static final String FILE_NAME = "动态结构.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "生成建筑自带的动态结构";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没停过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private DynamicStructureConfig() {
    }

    /** 当前是否"生成建筑自带的动态结构(实体)"。 */
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
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 不是合法的 JSON(" + e.getMessage() + "), 本次用内置默认值 " + DEFAULT);
        }
        enabled = value;
        return value;
    }

    /** 写一份带 {@code _说明} 字段的模板(JSON 不支持注释, 说明只能塞在字段里)。 */
    private static void writeTemplate(Path configDir, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "建筑自带的实体(尤其是机械动力的动态结构)要不要跟建筑一起放进世界。"
                + "改完重启游戏生效。本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=建筑里的实体照原样放回世界: 物品展示框、盔甲架, 以及 Create 的动态结构"
                        + "(图纸里会转的机器)。false=只放建筑方块不放实体，电梯轿厢等动态方块也不会出现。"
                        + "注意: 建筑里的方块实体数据(箱子内容、伪装方块外观)由 建筑自带箱子内容.json 管, "
                        + "和这个开关无关。");
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
