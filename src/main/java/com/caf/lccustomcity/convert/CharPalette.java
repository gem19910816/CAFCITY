package com.caf.lccustomcity.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「方块状态 (+ 方块实体数据)」映射到调色板字符。
 *
 * <p>Lost Cities 的 part 用「字符网格」描述建筑, 每个字符对应调色板里的一项。
 * 要求:
 * <ul>
 *   <li>空气固定用空格 {@code ' '} —— LC 把空格视为空气</li>
 *   <li>每个字符必须是<b>单个 UTF-16 码元</b>, 否则会被 JSON/字符串处理截断/拆散</li>
 *   <li>避开 JSON 需要转义的字符 {@code "} 与 {@code \}</li>
 * </ul>
 *
 * <p>字符分配是<b>确定性的</b>(按调用顺序, 而调用顺序由「坐标遍历 + 排序后的状态」决定),
 * 这样同一个建筑每次转换得到的调色板完全一致, 便于排查问题。
 *
 * <h2>★ 字符的 key = 方块状态 + 方块实体数据 ★</h2>
 * 这是本次最关键的一条: 失落城市的调色板是「一个字符对应一份方块 + 一份可选的
 * {@code tag}(方块实体数据)」。如果只按<b>状态</b>分配字符, 那么<b>两个状态相同、
 * 内容不同的箱子会共用同一个字符 ⇒ 被迫共用同一份 tag ⇒ 全城的箱子内容变成一样</b>。
 * 所以 key 里必须带上方块实体的内容(见 {@link BlockEntityNbt#canonicalKey}),
 * 内容不同的箱子自然分到不同字符。
 *
 * <h2>字符不够了怎么办</h2>
 * 候选字符约 2 万个汉字, 一栋建筑(最大 256x256 方块)通常用不到十分之一。但万一
 * 「状态 + 方块实体内容」的组合真的超过了字符空间, 这里<b>不抛异常也不编造新字符</b>,
 * 而是退化成「同一个方块状态共用该状态已分配的第一个字符」并记一次溢出
 * (由 {@link LcAssetGenerator} 写进警告)。宁可少几个箱子的内容, 也不能让整栋建筑转换失败。
 */
public final class CharPalette {

    /** 空气专用字符。 */
    public static final String AIR_CHAR = " ";

    private static final List<String> CANDIDATES = buildCandidatesChecked();

    /** 一个调色板条目: 字符 + 方块状态 + 可选的方块实体数据(tag)。 */
    public record Entry(String chr, String state, Map<String, Object> tag) {
    }

    /** key(状态 [+ \0 + 方块实体规范化串]) -> 条目。 */
    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    /** 按分配顺序排列的条目(除空气外)。 */
    private final List<Entry> allocated = new ArrayList<>();
    /** 方块状态 -> 该状态最早分配到的字符(字符空间不够时退化用)。 */
    private final Map<String, String> firstCharOfState = new LinkedHashMap<>();

    /** 因为字符空间不够而被迫退化的次数与样例(给上层记警告用)。 */
    private int overflowCount;
    private final List<String> overflowSamples = new ArrayList<>();

    /** 取得(必要时分配)某个方块状态对应的字符(不带方块实体数据)。 */
    public String charFor(String state) {
        return charFor(state, null);
    }

    /**
     * 取得(必要时分配)某个「方块状态 + 方块实体数据」组合对应的字符。
     *
     * @param be 该位置的方块实体 NBT; 没有就是 null(此时等价于 {@link #charFor(String)})
     */
    public String charFor(String state, Map<String, Object> be) {
        if (state == null || state.isEmpty()) {
            return AIR_CHAR;
        }
        String key = keyOf(state, be);
        Entry existing = byKey.get(key);
        if (existing != null) {
            return existing.chr();
        }
        String c;
        if (isAir(state)) {
            // 空气不参与字符分配(LC 把空格当空气), 方块实体数据也一并忽略
            c = AIR_CHAR;
        } else if (allocated.size() < CANDIDATES.size()) {
            c = CANDIDATES.get(allocated.size());
        } else {
            // ---- 字符空间用完了: 退化, 不抛异常 ----
            overflowCount++;
            if (overflowSamples.size() < 5) {
                overflowSamples.add(state);
            }
            String fallback = firstCharOfState.get(state);
            if (fallback == null) {
                fallback = allocated.isEmpty() ? AIR_CHAR : allocated.get(0).chr();
            }
            // 刻意不写进 byKey: 退化不是"正常分配", 下次遇到还是走同一条退化路径(结果稳定)
            return fallback;
        }
        Entry entry = new Entry(c, state, be);
        byKey.put(key, entry);
        allocated.add(entry);
        firstCharOfState.putIfAbsent(state, c);
        return c;
    }

    /**
     * 该状态「不带方块实体数据」的那个字符; 需要时新分配一个。
     *
     * <p>给 filler/rubble 用: 失落城市拿它去填建筑边缘和地下, 那里<b>不能带方块实体数据</b>
     * (否则整条边缘都会被塞满箱子/告示牌的内容)。
     */
    public String charForPlain(String state) {
        return charFor(state, null);
    }

    /** 已分配的 (字符, 方块状态, 方块实体数据) 列表, 含空气。 */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        // 空气永远放第一个, 和失落城市自带调色板的习惯一致
        out.add(new Entry(AIR_CHAR, "minecraft:air", null));
        for (Entry e : allocated) {
            if (AIR_CHAR.equals(e.chr())) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    public boolean isEmpty() {
        return allocated.isEmpty();
    }

    /** 因为字符空间不够而退化的次数(0 = 一切正常)。 */
    public int overflowCount() {
        return overflowCount;
    }

    /** 退化的样例方块状态(最多 5 个), 只给日志用。 */
    public List<String> overflowSamples() {
        return overflowSamples;
    }

    /** key = 状态 [+ \0 + 方块实体规范化串]。 */
    private static String keyOf(String state, Map<String, Object> be) {
        if (be == null || be.isEmpty()) {
            return state;
        }
        return state + '\u0000' + BlockEntityNbt.canonicalKey(be);
    }

    private static boolean isAir(String state) {
        String id = state;
        int br = id.indexOf('[');
        if (br >= 0) {
            id = id.substring(0, br);
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return switch (path) {
            case "air", "cave_air", "void_air", "structure_void" -> true;
            default -> false;
        };
    }

    private static List<String> buildCandidates() {
        // 为什么只用 CJK 汉字, 一个 ASCII 字符都不用?
        //
        // 失落城市的调色板是「字符 -> 方块」的映射, 而街道、边框、墙壁这些不是建筑
        // 用自己的字符, 而是由城市风格按字符引用:
        //     citystyle_border.json: { "border": "y", "wall": "w", "street": "S", ... }
        // 这些字符在 BuildingInfo.getCompiledPalette() 里这样合并:
        //     result = new CompiledPalette(城市风格的调色板);
        //     result = new CompiledPalette(result, 建筑的调色板);   // 建筑在最后 -> 覆盖前面的
        // 而 CompiledPalette.addPalettes() 是后者覆盖前者。所以建筑的调色板优先级最高。
        //
        // 实测: 失落城市自带资源一共用到 88 个字符, 全部是 ASCII, 非 ASCII 一个都没用。
        // 也就是说我们只要不用 ASCII, 就不可能撞上它的 y / w / S / b / B / # ...
        // (撞上的后果实测过: 建筑边缘整条被砌成梯子、地毯 —— 因为那些街道字符被解析成了
        //  我方调色板里恰好排在那个位置的装饰方块。)
        //
        // 汉字区约 2 万个字符, 全是单个 UTF-16 码元, 没有空白/零宽/控制字符, 容量绰绰有余。
        List<String> list = new ArrayList<>();
        for (int cp = 0x4E00; cp <= 0x9FFF; cp++) {
            addIfSafe(list, cp);
        }
        // 兜底: 理论上到不了这里(汉字区不会被过滤掉几个), 留着以防万一。
        // 这里刻意<不>回退到 ASCII —— 宁可少几个字符也不能撞车。
        for (int cp = 0x3400; cp <= 0x4DBF; cp++) {   // CJK 扩展 A
            addIfSafe(list, cp);
        }
        return list;
    }

    /**
     * 包一层自检: 候选字符里绝对不允许出现 ASCII。
     *
     * <p>这不是洁癖式的防御性编程。失落城市自带资源用到的 88 个字符<b>全是 ASCII</b>,
     * 而建筑的调色板在 {@code CompiledPalette} 里优先级最高(最后合并 => 覆盖前面的),
     * 一旦撞车, 它那些街道/边框/墙壁字符就会被解析成我方调色板里恰好排在那个位置的
     * 方块 —— 生成出来的建筑边缘会整条变成梯子、地毯。
     *
     * <p>这种错误离线测试查不出来(JSON 完全合法), 只有进游戏才暴露, 所以宁可在这里直接炸。
     */
    private static List<String> buildCandidatesChecked() {
        List<String> list = buildCandidates();
        for (String c : list) {
            if (c.charAt(0) < 128) {
                throw new IllegalStateException(
                        "调色板候选里混进了 ASCII 字符 " + c + " —— 会撞上失落城市的街道/边框字符");
            }
        }
        if (list.size() < 1024) {
            throw new IllegalStateException(
                    "调色板候选字符太少 (" + list.size() + " 个), 覆盖不了大型建筑");
        }
        return list;
    }

    /** 只接受安全的单码元字符: 非代理对、非控制字符、非空白。 */
    private static void addIfSafe(List<String> list, int cp) {
        char c = (char) cp;
        if (Character.isSurrogate(c)
                || Character.isISOControl(c)
                || Character.isWhitespace(c)
                || Character.isSpaceChar(c)) {
            return;
        }
        list.add(String.valueOf(c));
    }
}
