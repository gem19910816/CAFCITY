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
 * 「建筑自带的箱子内容」开关(默认开)。
 *
 * <p>配置文件: {@code config/CAF失落城市配置/建筑自带箱子内容.json}。
 *
 * <h2>这个开关在解决什么冲突</h2>
 * 失落城市生成方块实体时, 优先级是 <b>{@code loot} &gt; {@code mob} &gt; {@code tag}</b>
 * ({@code LostCityTerrainFeature.java:1816-1822}, 顺序写在 {@code if/else if} 里)。
 * 而战利品({@code loot})是挂在<b>调色板字符</b>上的 —— 也就是说:
 * 如果一个字符既要挂战利品条件、又要带建筑自带的 {@code tag}(箱子内容), 那么
 * <b>战利品会赢, 建筑自带的内容会被整份丢掉</b>(连一句提示都没有)。
 *
 * <p>所以这里必须让玩家选:
 * <ul>
 *   <li><b>true(默认)</b>: 建筑自带内容优先 ⇒ 那些字符<b>不挂 loot</b>,
 *       箱子里的东西就是作者放的(丢失城市战利品池只在没有自带内容的箱子上生效);</li>
 *   <li><b>false</b>: 保持旧行为 ⇒ 挂着战利品条件的字符不写 tag, 建筑自带的箱子内容被丢掉,
 *       箱子内容由战利品池决定。</li>
 * </ul>
 * 无论选哪个, 转换时都会把「冲突了多少个字符」记进日志。
 *
 * <h2>容错原则(和 {@link GapConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 用内置默认值(开), 不报错</li>
 *   <li>JSON 语法错 / 类型不对 → 记一条警告, 用内置默认值, 不崩</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 */
public final class BlockEntityContentConfig {

    public static final String FILE_NAME = "建筑自带箱子内容.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "建筑自带的箱子内容优先";
    /** 内置默认值: 开(建筑自带的箱子内容优先, 冲突的字符不挂战利品)。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private BlockEntityContentConfig() {
    }

    /** 当前是否"建筑自带的箱子内容优先"。 */
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
        root.addProperty("_说明", "建筑自带的箱子内容(以及其它方块实体数据)与失落城市战利品池冲突时, 谁优先。"
                + "改完重启游戏生效。本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=建筑自带的内容优先: 作者在建筑里放好的箱子内容会原样保留, "
                        + "这些箱子不挂失落城市的战利品(否则战利品会把自带内容整份盖掉, "
                        + "因为失落城市的优先级是 loot > mob > tag)。"
                        + "false=战利品优先: 挂了战利品条件的容器会丢掉建筑自带的内容, "
                        + "箱子内容完全由 城区配置.json 的战利品池决定(这是本次改动之前的行为)。"
                        + "无论选哪个, 转换时都会在日志里写明冲突了多少个调色板字符。");
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
