package com.caf.lccustomcity.schem;

import com.caf.lccustomcity.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * 建筑文件的「原始 NBT 文档」读取器 —— 比 {@link NbtReader} 多两样东西:
 * <ol>
 *   <li><b>自动 gzip 识别</b>并且把整份内容读进内存(后面的解析要在字节上做);</li>
 *   <li>返回根标签<b>之后还剩多少字节</b>({@link Parsed#tail()})。</li>
 * </ol>
 *
 * <h2>为什么非要知道「根标签之后还剩什么」</h2>
 * 玩家实例里那 38 个 .schem 的方块实体列表是<b>坏的</b>:
 * {@code Blocks.BlockEntities} 被写成了 compound 而不是 list, 于是标准 NBT 解析器
 * 读到的内容比实际数据少一大截 —— 第一个方块实体吃掉了 {@code BlockEntities} 这个
 * compound, 第二个变成 {@code Blocks} 的字段, 第三个变成根标签的字段,
 * <b>第四个往后全部堆在根标签的结束字节之后</b>, 谁都不读它们。
 * 实测: {@code 豪华酒店.schem} 根标签之后还有 17 KB, {@code 红夜总部[生化公司].schem}
 * 之后还有 137 KB —— 全是方块实体数据。
 *
 * <p>所以这里把「消费掉的字节数」算出来, 让 {@link SchematicReader} 能接着往下读。
 * 正常的(符合 Sponge 规范的)文件这里返回的 {@code tail} 是空数组, 行为完全不变。
 */
public final class NbtFile {

    /** 解压后的体积上限(防「压缩炸弹」): 256 MiB。真实建筑文件最大也就几 MiB。 */
    private static final long MAX_DECOMPRESSED = 256L * 1024 * 1024;

    private static final byte[] NO_TAIL = new byte[0];

    private NbtFile() {
    }

    /** 一个 NBT 文档: 根标签 + 根标签结束之后的剩余字节(正常文件为空)。 */
    public record Parsed(Object root, byte[] tail) {

        /** 根标签之后还有数据吗(说明文件不是标准写法)。 */
        public boolean hasTail() {
            return tail != null && tail.length > 0;
        }
    }

    /** 读文件(自动识别 gzip), 返回根标签与根标签之后的剩余字节。 */
    public static Parsed read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        byte[] data = readAll(in);

        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        Object root;
        int consumed;
        // 注意: 这里刻意不关闭 DataInputStream —— 它关的是 ByteArrayInputStream, 没影响,
        // 但 try-with-resources 会让下面拿不到 available() 的时机变得绕, 所以直接算。
        DataInputStream din = new DataInputStream(bais);
        root = NbtReader.readRoot(din);
        consumed = data.length - bais.available();

        if (consumed <= 0 || consumed >= data.length) {
            return new Parsed(root, NO_TAIL);
        }
        return new Parsed(root, Arrays.copyOfRange(data, consumed, data.length));
    }

    /** 读文件并只返回根标签(不需要 tail 时的便捷方法)。 */
    public static Object readRoot(Path file) throws IOException {
        return read(file).root();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_DECOMPRESSED) {
                throw new IOException("解压后的 NBT 超过上限 " + MAX_DECOMPRESSED + " 字节, 拒绝加载");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
