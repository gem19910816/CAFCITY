package com.caf.lccustomcity.nbt;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简 NBT 读取器。
 *
 * <p>只用 JDK 标准库, <b>不依赖 Minecraft</b> —— 这样转换逻辑可以脱离游戏单独测试。
 *
 * <p>返回的对象模型:
 * <ul>
 *   <li>TAG_Compound   -&gt; {@code Map<String,Object>} (LinkedHashMap, 保留字段顺序)</li>
 *   <li>TAG_List       -&gt; {@code List<Object>}</li>
 *   <li>TAG_Byte/Short/Int/Long/Float/Double -&gt; 对应的包装类型</li>
 *   <li>TAG_Byte_Array -&gt; {@code byte[]}</li>
 *   <li>TAG_String     -&gt; {@code String}</li>
 *   <li>TAG_Int_Array  -&gt; {@code int[]}</li>
 *   <li>TAG_Long_Array -&gt; {@code long[]}</li>
 * </ul>
 */
public final class NbtReader {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private NbtReader() {
    }

    /**
     * 读取 NBT 文件, 自动识别 gzip 压缩(魔数 0x1f8b)或未压缩。
     * WorldEdit 的 .schem 与结构方块导出的 .nbt 都是 gzip 压缩的。
     */
    public static Object readFile(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             BufferedInputStream bin = new BufferedInputStream(raw, 1 << 16)) {
            bin.mark(2);
            int b0 = bin.read();
            int b1 = bin.read();
            bin.reset();
            InputStream in = (b0 == 0x1F && b1 == 0x8B) ? new GZIPInputStream(bin, 1 << 16) : bin;
            try (DataInputStream din = new DataInputStream(in)) {
                return readRoot(din);
            }
        }
    }

    /** 读根标签: 类型字节 + 根名 + 负载。返回负载(根名丢弃)。 */
    public static Object readRoot(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == TAG_END) {
            return null;
        }
        readString(in); // 根名, 忽略
        return readPayload(in, type);
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int n = in.readInt();
                byte[] a = new byte[n];
                in.readFully(a);
                return a;
            }
            case TAG_STRING:
                return readString(in);
            case TAG_LIST: {
                int elemType = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elemType));
                }
                return list;
            }
            case TAG_COMPOUND:
                return readCompoundBody(in);
            case TAG_INT_ARRAY: {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case TAG_LONG_ARRAY: {
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("未知的 NBT 标签类型: " + type);
        }
    }

    /**
     * 读一个 compound 的<b>负载</b>: 一串「类型 + 名字 + 值」直到结束字节 {@code TAG_End},
     * 不读类型字节和名字。{@link #readPayload} 的 {@code TAG_Compound} 分支走的就是这里。
     *
     * <p>公开它的理由: 有些第三方工具写出的 .schem 把方块实体列表写坏了 ——
     * {@code Blocks.BlockEntities} 被写成 compound 而不是 list, 后面的方块实体
     * 干脆以「裸负载」的形式排在文件里(连类型字节和名字都没有), 标准读取器读到根标签
     * 结束就停了, 剩下的一整段方块实体数据被丢掉。要用它们就只能在根标签之后
     * 继续按「一个 compound 负载」为单位往下读, 所以这里把它单独暴露出来。
     *
     * <p>注意: 数据是坏的/截断的时会抛 {@link java.io.EOFException}, 由调用方兜底。
     */
    public static Map<String, Object> readCompoundBody(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int t = in.readUnsignedByte();
            if (t == TAG_END) {
                break;
            }
            String name = readString(in);
            map.put(name, readPayload(in, t));
        }
        return map;
    }

    /** NBT 字符串使用 Java modified UTF-8，与 Minecraft DataInput.readUTF 一致。 */
    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    // ------------------------------------------------------------------
    // 安全取值辅助
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asCompound(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    /** 从复合标签里取子复合标签。 */
    public static Map<String, Object> compound(Object parent, String key) {
        return (parent instanceof Map) ? asCompound(((Map<?, ?>) parent).get(key)) : null;
    }

    /** 从复合标签里取列表。 */
    public static List<Object> list(Object parent, String key) {
        return (parent instanceof Map) ? asList(((Map<?, ?>) parent).get(key)) : null;
    }

    public static String string(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) parent).get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static int intValue(Object o, int def) {
        return (o instanceof Number) ? ((Number) o).intValue() : def;
    }

    public static int intValue(Object parent, String key, int def) {
        if (!(parent instanceof Map)) {
            return def;
        }
        return intValue(((Map<?, ?>) parent).get(key), def);
    }

    public static int[] intArray(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) parent).get(key);
        return (v instanceof int[]) ? (int[]) v : null;
    }

    public static byte[] byteArray(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) parent).get(key);
        return (v instanceof byte[]) ? (byte[]) v : null;
    }
}
