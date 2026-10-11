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
 * 桥梁风格配置 —— 「桥面换成我们的路面」开关(默认开)。
 *
 * <p>配置文件名: {@code config/失落城市建筑/桥梁风格.json}。
 * 打开时(默认), 模组会在生成的数据包里覆盖失落城市的调色板
 * {@code palettes/street_large.json}, 把<b>跨水街道桥</b>用到的三个字符换成我们自己的路面风格:
 * {@code l}(桥面主体, 原版平滑石) → 内城马路那套随机混合路面;
 * {@code q}(正中两行车道分隔线, 原版平滑石英 = 白色) → 内城马路的中央分隔带(磨制黑石);
 * {@code w}(桥两侧矮墙与立柱, 原版圆石墙) → 石砖墙(和我们的路缘/桥面边缘同一套石材)。
 * 关掉 = 不生成这份覆盖, 保持原版桥梁外观。
 * 实现见 {@code com.caf.lccustomcity.convert.BridgeStyleGenerator}。
 *
 * <h2>为什么这是一个和"高速路风格"分开的开关</h2>
 * 高速路那套({@link HighwayStyleConfig})改的是六张高速图纸的内联调色板
 * 里的 {@code R}/{@code u}, 使用城市空地材料和比例;
 * 而桥这套改的是另一本调色板 {@code palettes/street_large.json}, 只影响桥(和那 9 张
 * 我们已经在 citystyle 里换掉的 {@code street_large_*} 大街图纸)。
 * 两件事的<b>作用范围、副作用、失败面</b>都不一样, 合成一个开关会让玩家没法只留一个:
 * 想保留原版桥、只要高速换皮, 或者反过来, 都做不到。所以这里<b>新加一个独立开关</b>。
 * 两个开关的生成器各自独立 try/catch, 任何一边校验不过都只是"那一边不换皮", 互不牵连。
 *
 * <h2>容错原则(和 {@link HighwayStyleConfig}/{@link GapConfig} 一致)</h2>
 * <ul>
 *   <li>文件不存在 → 写一份带 {@code _说明} 字段的模板, 用内置默认值(开), 不报错</li>
 *   <li>JSON 语法错 / 类型不对 → 记一条警告, 用内置默认值, 不崩</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft / 失落城市的类, 可以被离线测试直接跑。
 */
public final class BridgeStyleConfig {

    public static final String FILE_NAME = "桥梁风格.json";
    /** 配置文件里的开关键名。 */
    public static final String KEY = "桥面换成我们的路面";
    /** 内置默认值: 开。 */
    public static final boolean DEFAULT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 运行时开关。启动时由 {@link #load} 写入; 没启动过(离线测试)就是默认值。 */
    private static volatile boolean enabled = DEFAULT;

    private BridgeStyleConfig() {
    }

    /** 当前是否启用"桥面换成我们的路面"。 */
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
        root.addProperty("_说明", "失落城市跨水街道桥的路面风格设置, 改完重启游戏生效。"
                + "本文件不存在时会自动重新生成这份模板; 删掉整个文件 = 用内置默认值(开)。");
        root.addProperty(KEY, DEFAULT);
        root.addProperty("_说明_" + KEY,
                "true(默认)=把失落城市跨越水面的街道桥(桥面/车道分隔线/两侧矮墙)换成我们自己的路面风格"
                        + "(和城内马路同一套方块: 石砖/深板岩/磨制黑石随机混合的桥面 + 磨制黑石中央分隔线, "
                        + "两侧矮墙改成石砖墙)。"
                        + "做法是覆盖失落城市的调色板 palettes/street_large.json, "
                        + "桥的走向/高度/支撑柱完全不变, 只换外观; "
                        + "false=不生成这份覆盖, 保持原版桥面(平滑石 + 白色平滑石英中线 + 圆石矮墙)。"
                        + "注意: 这本调色板的 l/q 也被原版的 9 张街道大街图纸 street_large_* 用到, "
                        + "但我们的城市风格把大街已经换成自己的路图纸了, 所以城市里只有桥会变。");
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
