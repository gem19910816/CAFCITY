package com.caf.lccustomcity.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方块实体数据的两件小事(纯函数, 不依赖 Minecraft, 可以被离线测试直接跑):
 * <ol>
 *   <li><b>规范化串</b> {@link #canonicalKey}: 把一份方块实体 NBT 变成一个稳定、可比较的
 *       字符串, 用来当「字符分配」的 key —— 这样<b>状态相同但内容不同的两个箱子会被分到
 *       不同的调色板字符</b>(否则全城的箱子内容会变成同一份)。</li>
 *   <li><b>转成 JSON</b> {@link #toJson}: 失落城市只认 JSON 里的 {@code tag} 字段
 *       ({@code PaletteEntry.tag} 是 {@code CompoundTag.CODEC}, 由 JSON 解析而来)。</li>
 * </ol>
 *
 * <h2>为什么 key 里要排除坐标和 id</h2>
 * 两份除了坐标以外完全一样的箱子, 内容就是一样的, 应该共用同一个字符(省字符空间)。
 * 而 {@code id} 是失落城市自己按方块类型补的(见 {@code LostCityTerrainFeature.handleBlockEntity}:
 * 它 copy 我们的 tag 之后自己 put 了 x/y/z 和 id), 所以留着反而会让两个本该相同的条目
 * 变成两个字符。
 */
public final class BlockEntityNbt {

    /** 写进调色板 tag 之前必须剥掉的字段。 */
    private static final Set<String> STRIP = Set.of("x", "y", "z", "id", "Id", "Pos");

    /** 规范化串的长度上限: 超过就退化成哈希(现实中到不了, 纯粹防内存爆)。 */
    private static final int MAX_CANONICAL_LENGTH = 1 << 20;

    private BlockEntityNbt() {
    }

    /**
     * 规范化串: <b>与坐标/id 无关, 只反映内容</b>。
     *
     * <p>同一份 NBT 永远得到同一个串(字段名排序), 不同的内容永远得到不同的串
     * (每个值都带类型前缀, 字符串带长度前缀, 不会有歧义拼接)。
     */
    public static String canonicalKey(Map<String, Object> be) {
        StringBuilder sb = new StringBuilder(128);
        appendMap(be, sb, 0);
        if (sb.length() > MAX_CANONICAL_LENGTH) {
            return "hash:" + LcAssetGenerator.shortHash(sb.toString());
        }
        return sb.toString();
    }

    /**
     * 剥掉坐标与 id 的副本 —— 真正写进调色板 {@code tag} 的就是它。
     *
     * <p>失落城市拿到 tag 之后会自己补上正确的 {@code x}/{@code y}/{@code z} 和
     * {@code id}({@code LostCityTerrainFeature.java:1877-1881}), 我们留着不但没用,
     * 还会让 JSON 变大、并且留下「坐标是建筑内的相对坐标」这种会误导排查的数据。
     */
    public static Map<String, Object> forPalette(Map<String, Object> be) {
        java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : be.entrySet()) {
            if (STRIP.contains(e.getKey())) {
                continue;
            }
            copy.put(e.getKey(), e.getValue());
        }
        return copy;
    }

    /**
     * NBT 对象模型 -> Gson JSON。
     *
     * <p><b>类型信息</b>: 数值按原类型写(byte/short/int/long/float/double 都是 JSON 数字),
     * 字符串写字符串, 列表写数组, 复合写对象。{@code byte[]}/{@code int[]}/{@code long[]}
     * 写成数字数组 —— 失落城市把 JSON 转回 NBT 时, 数字数组会变成
     * {@code ListTag<Tag>}(不是 IntArrayTag)。实测玩家那批方块实体里只有
     * {@code UUID}(int[4])是数组, Minecraft 的 {@code Codec.INT_STREAM} 两种写法都收,
     * 所以这里不额外处理; 真要遇到只认 IntArrayTag 的第三方方块实体, 那是失落城市
     * 的 JSON 通道本身的限制(它自己的调色板就是这么写的)。
     */
    public static JsonObject toJson(Map<String, Object> be) {
        JsonObject out = new JsonObject();
        if (be == null) {
            return out;
        }
        List<String> keys = new ArrayList<>(be.keySet());
        Collections.sort(keys);     // 固定顺序 ⇒ 生成的 JSON 可复现(便于比对)
        for (String k : keys) {
            Object v = be.get(k);
            if (v == null) {
                continue;
            }
            out.add(k, toJsonValue(v));
        }
        return out;
    }

    private static com.google.gson.JsonElement toJsonValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            List<String> keys = new ArrayList<>(typed.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                Object child = typed.get(k);
                if (child != null) {
                    o.add(k, toJsonValue(child));
                }
            }
            return o;
        }
        if (v instanceof List<?> l) {
            JsonArray arr = new JsonArray();
            for (Object o : l) {
                if (o != null) {
                    arr.add(toJsonValue(o));
                }
            }
            return arr;
        }
        if (v instanceof byte[] a) {
            JsonArray arr = new JsonArray();
            for (byte b : a) {
                arr.add(new JsonPrimitive(b));
            }
            return arr;
        }
        if (v instanceof int[] a) {
            JsonArray arr = new JsonArray();
            for (int i : a) {
                arr.add(new JsonPrimitive(i));
            }
            return arr;
        }
        if (v instanceof long[] a) {
            JsonArray arr = new JsonArray();
            for (long x : a) {
                arr.add(new JsonPrimitive(x));
            }
            return arr;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    // ------------------------------------------------------------------
    // 规范化串的构造
    // ------------------------------------------------------------------

    private static void appendMap(Map<String, Object> m, StringBuilder sb, int depth) {
        sb.append('{');
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            if (STRIP.contains(k)) {
                continue;       // 坐标/id 不参与"内容"的比较
            }
            sb.append(k.length()).append(':').append(k).append('=');
            appendValue(m.get(k), sb, depth);
            sb.append(';');
        }
        sb.append('}');
    }

    private static void appendValue(Object v, StringBuilder sb, int depth) {
        if (v == null) {
            sb.append('~');
            return;
        }
        if (depth > 64) {
            sb.append("...");
            return;
        }
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            appendMap(typed, sb, depth + 1);
            return;
        }
        if (v instanceof List<?> l) {
            sb.append('[');
            for (Object o : l) {
                appendValue(o, sb, depth + 1);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        if (v instanceof String s) {
            // 带长度前缀: 内容里有什么字符都不会有歧义
            sb.append('s').append(s.length()).append(':').append(s);
            return;
        }
        if (v instanceof Byte b) {
            sb.append('b').append(b);
            return;
        }
        if (v instanceof Short s) {
            sb.append('h').append(s);
            return;
        }
        if (v instanceof Integer i) {
            sb.append('i').append(i);
            return;
        }
        if (v instanceof Long l) {
            sb.append('l').append(l);
            return;
        }
        if (v instanceof Float f) {
            sb.append('f').append(f);
            return;
        }
        if (v instanceof Double d) {
            sb.append('d').append(d);
            return;
        }
        if (v instanceof Boolean bool) {
            sb.append('z').append(bool);
            return;
        }
        if (v instanceof byte[] a) {
            sb.append("B[").append(a.length).append(']');
            for (byte x : a) {
                sb.append(x).append(',');
            }
            return;
        }
        if (v instanceof int[] a) {
            sb.append("I[");
            for (int x : a) {
                sb.append(x).append(',');
            }
            sb.append(']');
            return;
        }
        if (v instanceof long[] a) {
            sb.append("L[");
            for (long x : a) {
                sb.append(x).append(',');
            }
            sb.append(']');
            return;
        }
        sb.append('?').append(v.getClass().getSimpleName());
    }
}
