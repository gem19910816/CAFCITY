import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 生成一批"现成的测试建筑"(.schem), 让玩家不用先自己导出就能验证整条链路。
 *
 * <p>为什么需要它: 只有一个 10x28 的建筑时, 会被转成"多格建筑", 而失落城市每个
 * 160x160 区域只放 1~5 个多格建筑 —— 进游戏大概率只看到一片空地, 什么也验证不出来。
 * 这批建筑里既有 16x16 以内的小建筑(会频繁出现在街边), 也有刚好 16x16 的边界情况,
 * 还有 17x17 / 20x26 的多格建筑, 高度也覆盖了 6/7/9/12/13/19 这几种切片边界。
 *
 * <p>格式: Sponge v3 .schem, 即 gzip 过的 NBT:
 * <pre>
 *   TAG_Compound ""
 *     TAG_Int     Version      = 3
 *     TAG_Int     DataVersion  = 3955
 *     TAG_Short   Width / Height / Length
 *     TAG_Compound Blocks
 *       TAG_Compound  Palette { "minecraft:xxx" -> 下标 }
 *       TAG_Byte_Array Data    = 每个方块一个 varint(LEB128) 下标
 *       TAG_List       BlockEntities (空)
 * </pre>
 * 下标顺序是 {@code (y * Length + z) * Width + x} —— 和 LcAssetGenerator/SchematicData
 * 的 index(x,y,z) = (y*sizeZ + z)*sizeX + x 完全一致。
 *
 * <p>用法: java TestBuildings &lt;输出目录&gt;
 */
public final class TestBuildings {

    // ---- NBT tag 类型 ----
    private static final int TAG_END = 0;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;

    private static final String AIR = "minecraft:air";

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "build/test-buildings").toAbsolutePath();
        deleteRecursively(outDir);
        Files.createDirectories(outDir);

        List<Model> all = new ArrayList<>();
        all.add(smallHouse());
        all.add(shop6());
        all.add(twoStory());
        all.add(tower());
        all.add(courtyard());
        all.add(shopExactly16());
        all.add(warehouse17());
        all.add(apartment());

        System.out.println("=== 生成测试建筑到 " + outDir);
        for (Model m : all) {
            // 其中一栋写成【原版结构方块格式的 .nbt】: 未压缩, 而且 size/pos 是
            // TAG_List(int 列表)而不是 TAG_Int_Array —— 真实结构文件就是这样。
            // 这样 .nbt 这条路(以及 tools/nbt2schem.cjs)也会被覆盖到。
            boolean asNbt = m.name.equals(NBT_MODEL);
            Path f = outDir.resolve(m.name + (asNbt ? ".nbt" : ".schem"));
            if (asNbt) {
                writeStructureNbt(f, m);
            } else {
                writeSchem(f, m);
            }
            System.out.printf("  %-16s %2d x %2d x %2d   %6d 字节%n",
                    f.getFileName().toString(), m.w, m.h, m.d, Files.size(f));
        }
        System.out.println("  共 " + all.size() + " 个 (其中 1 个是原版结构 .nbt, 其余是 .schem)");
    }

    // ==================================================================
    // 建筑模型
    // ==================================================================

    private static final class Model {
        final String name;
        final int w;
        final int h;
        final int d;
        final String[] cells;

        Model(String name, int w, int h, int d) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.d = d;
            this.cells = new String[w * h * d];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = AIR;
            }
        }

        void set(int x, int y, int z, String id) {
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) {
                return;
            }
            cells[(y * d + z) * w + x] = id;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, String id) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        set(x, y, z, id);
                    }
                }
            }
        }

        /** 只铺一层墙(矩形环), 厚度 1。 */
        void walls(int y, int x0, int z0, int x1, int z1, String id) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, id);
                set(x, y, z1, id);
            }
            for (int z = z0; z <= z1; z++) {
                set(x0, y, z, id);
                set(x1, y, z, id);
            }
        }
    }

    /** 一层小屋, 7x7, 高 7: 地板 + 墙 + 窗户 + 门洞 + 屋顶。 */
    private static Model smallHouse() {
        Model m = new Model("小型住宅", 7, 7, 7);
        m.fill(0, 0, 0, 6, 0, 6, "minecraft:stone_bricks");
        for (int y = 1; y <= 5; y++) {
            m.walls(y, 0, 0, 6, 6, "minecraft:oak_planks");
        }
        // 窗户
        m.set(1, 3, 0, "minecraft:glass");
        m.set(5, 3, 0, "minecraft:glass");
        m.set(0, 3, 3, "minecraft:glass");
        m.set(6, 3, 3, "minecraft:glass");
        // 门洞
        m.set(3, 1, 0, AIR);
        m.set(3, 2, 0, AIR);
        // 屋顶
        m.fill(0, 6, 0, 6, 6, 6, "minecraft:oak_planks");
        return m;
    }

    /** 高度正好 6 —— 切片规则里最刁的边界(会多补一层空气)。 */
    private static Model shop6() {
        Model m = new Model("杂货铺", 11, 6, 9);
        m.fill(0, 0, 0, 10, 0, 8, "minecraft:stone");
        for (int y = 1; y <= 4; y++) {
            m.walls(y, 0, 0, 10, 8, "minecraft:bricks");
        }
        // 临街整面玻璃
        for (int x = 2; x <= 8; x++) {
            for (int y = 1; y <= 3; y++) {
                m.set(x, y, 0, "minecraft:glass");
            }
        }
        m.set(5, 1, 0, AIR);
        m.set(5, 2, 0, AIR);
        m.fill(0, 5, 0, 10, 5, 8, "minecraft:bricks");
        return m;
    }

    /** 两层, 9x11, 高 13: 2 层 + 1 层顶 = 13, 刚好放满。 */
    private static Model twoStory() {
        Model m = new Model("二层民居", 9, 13, 11);
        m.fill(0, 0, 0, 8, 0, 10, "minecraft:stone_bricks");
        for (int y = 1; y <= 11; y++) {
            m.walls(y, 0, 0, 8, 10, "minecraft:oak_planks");
        }
        // 楼层分隔
        m.fill(0, 6, 0, 8, 6, 10, "minecraft:oak_planks");
        // 每层开窗
        for (int y : new int[] {3, 9}) {
            m.set(1, y, 0, "minecraft:glass");
            m.set(7, y, 0, "minecraft:glass");
            m.set(0, y, 5, "minecraft:glass");
            m.set(8, y, 5, "minecraft:glass");
        }
        m.set(4, 1, 0, AIR);
        m.set(4, 2, 0, AIR);
        m.fill(0, 12, 0, 8, 12, 10, "minecraft:stone_bricks");
        return m;
    }

    /** 三层方塔, 9x9, 高 19: 3 层 + 1 层顶 = 19。 */
    private static Model tower() {
        Model m = new Model("方塔", 9, 19, 9);
        m.fill(0, 0, 0, 8, 0, 8, "minecraft:stone");
        for (int y = 1; y <= 17; y++) {
            m.walls(y, 0, 0, 8, 8, "minecraft:stone_bricks");
        }
        for (int y : new int[] {6, 12}) {
            m.fill(0, y, 0, 8, y, 8, "minecraft:stone_bricks");
        }
        for (int y : new int[] {3, 9, 15}) {
            m.set(2, y, 0, "minecraft:glass");
            m.set(6, y, 0, "minecraft:glass");
            m.set(4, y, 4, "minecraft:glass");
        }
        m.set(4, 1, 0, AIR);
        m.set(4, 2, 0, AIR);
        m.fill(0, 18, 0, 8, 18, 8, "minecraft:stone_bricks");
        return m;
    }

    /** 四合院, 15x15, 高 7: 四周一圈房, 中间露天。 */
    private static Model courtyard() {
        Model m = new Model("四合院", 15, 7, 15);
        m.fill(0, 0, 0, 14, 0, 14, "minecraft:stone_bricks");
        // 外墙
        for (int y = 1; y <= 5; y++) {
            m.walls(y, 0, 0, 14, 14, "minecraft:bricks");
        }
        // 内侧再围一圈, 形成环形房间
        for (int y = 1; y <= 5; y++) {
            m.walls(y, 4, 4, 10, 10, "minecraft:oak_planks");
        }
        // 中间露天: 把内圈里面的墙清掉、地板留下
        m.fill(5, 1, 5, 9, 5, 9, AIR);
        m.fill(0, 6, 0, 14, 6, 14, "minecraft:bricks");
        m.fill(5, 6, 5, 9, 6, 9, AIR);   // 中庭不封顶
        // 门洞
        m.set(7, 1, 0, AIR);
        m.set(7, 2, 0, AIR);
        m.set(7, 1, 4, AIR);
        m.set(7, 2, 4, AIR);
        return m;
    }

    /** 刚好 16x16 —— 单格与多格的边界, 高 12。 */
    private static Model shopExactly16() {
        Model m = new Model("临街商铺", 16, 12, 16);
        m.fill(0, 0, 0, 15, 0, 15, "minecraft:stone");
        for (int y = 1; y <= 10; y++) {
            m.walls(y, 0, 0, 15, 15, "minecraft:bricks");
        }
        m.fill(0, 6, 0, 15, 6, 15, "minecraft:oak_planks");
        for (int x = 3; x <= 12; x++) {
            for (int y = 1; y <= 5; y++) {
                m.set(x, y, 0, "minecraft:glass");
            }
        }
        m.set(7, 1, 0, AIR);
        m.set(7, 2, 0, AIR);
        m.set(8, 1, 0, AIR);
        m.set(8, 2, 0, AIR);
        m.fill(0, 11, 0, 15, 11, 15, "minecraft:bricks");
        return m;
    }

    /** 17x17 —— 刚好越界, 会被转成 2x2 多格建筑, 高 9。 */
    private static Model warehouse17() {
        Model m = new Model("仓库", 17, 9, 17);
        m.fill(0, 0, 0, 16, 0, 16, "minecraft:cobblestone");
        for (int y = 1; y <= 7; y++) {
            m.walls(y, 0, 0, 16, 16, "minecraft:stone_bricks");
        }
        for (int y : new int[] {3, 5}) {
            m.set(4, y, 0, "minecraft:glass");
            m.set(8, y, 0, "minecraft:glass");
            m.set(12, y, 0, "minecraft:glass");
        }
        m.set(8, 1, 0, AIR);
        m.set(8, 2, 0, AIR);
        m.fill(0, 8, 0, 16, 8, 16, "minecraft:stone_bricks");
        return m;
    }

    /** 20x26 大公寓, 高 19: 2x2 多格 + 三层。 */
    private static Model apartment() {
        Model m = new Model("公寓楼", 20, 19, 26);
        m.fill(0, 0, 0, 19, 0, 25, "minecraft:stone_bricks");
        for (int y = 1; y <= 17; y++) {
            m.walls(y, 0, 0, 19, 25, "minecraft:bricks");
        }
        for (int y : new int[] {6, 12}) {
            m.fill(0, y, 0, 19, y, 25, "minecraft:oak_planks");
        }
        for (int y : new int[] {3, 9, 15}) {
            for (int x = 3; x <= 16; x += 4) {
                m.set(x, y, 0, "minecraft:glass");
                m.set(x, y, 25, "minecraft:glass");
            }
            for (int z = 3; z <= 22; z += 4) {
                m.set(0, y, z, "minecraft:glass");
                m.set(19, y, z, "minecraft:glass");
            }
        }
        m.set(9, 1, 0, AIR);
        m.set(9, 2, 0, AIR);
        m.set(10, 1, 0, AIR);
        m.set(10, 2, 0, AIR);
        m.fill(0, 18, 0, 19, 18, 25, "minecraft:stone_bricks");
        return m;
    }

    // ==================================================================
    // 写原版结构方块的 .nbt
    //
    // 和 .schem 的四个关键差别(这也是 tools/nbt2schem.cjs 存在的理由):
    //   1. 未压缩 —— 结构方块存的是裸 NBT
    //   2. size 和 pos 是 TAG_List(int 列表), 不是 TAG_Int_Array
    //   3. 调色板条目是 {Name:"...", Properties:{...}}
    //   4. 方块表是 {pos:[x,y,z], state:<调色板下标>} 的列表
    // ==================================================================

    /** 用哪一栋来产 .nbt。 */
    private static final String NBT_MODEL = "公寓楼";

    private static void writeStructureNbt(Path file, Model m) throws IOException {
        List<String> palette = new ArrayList<>();
        palette.add(AIR);
        for (String cell : m.cells) {
            if (!palette.contains(cell)) {
                palette.add(cell);
            }
        }

        Nbt root = new Nbt();

        // size: TAG_List of TAG_Int, 3 个
        root.listTag("size", TAG_INT, 3);
        root.rawInt(m.w);
        root.rawInt(m.h);
        root.rawInt(m.d);

        // palette: TAG_List of TAG_Compound(元素无名字, 直接跟 compound 负载)
        root.listTag("palette", TAG_COMPOUND, palette.size());
        for (String state : palette) {
            Nbt entry = new Nbt();
            entry.stringTag("Name", state);
            entry.end();
            root.raw(entry.toBytes());
        }

        // blocks: 只写非空气的方块
        int count = 0;
        for (String cell : m.cells) {
            if (!AIR.equals(cell)) {
                count++;
            }
        }
        root.listTag("blocks", TAG_COMPOUND, count);
        int idx = 0;
        for (int y = 0; y < m.h; y++) {
            for (int z = 0; z < m.d; z++) {
                for (int x = 0; x < m.w; x++) {
                    String cell = m.cells[idx++];
                    if (AIR.equals(cell)) {
                        continue;
                    }
                    Nbt b = new Nbt();
                    b.listTag("pos", TAG_INT, 3);
                    b.rawInt(x);
                    b.rawInt(y);
                    b.rawInt(z);
                    b.intTag("state", palette.indexOf(cell));
                    b.end();
                    root.raw(b.toBytes());
                }
            }
        }

        root.listTag("entities", TAG_COMPOUND, 0);
        root.intTag("DataVersion", 3955);
        root.end();

        // 未压缩: 结构方块就是这么存的
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.write(TAG_COMPOUND);
        fileBytes.write(0);
        fileBytes.write(0);
        byte[] body = root.toBytes();
        fileBytes.write(body, 0, body.length);
        Files.write(file, fileBytes.toByteArray());
    }

    // ==================================================================
    // 写 .schem
    // ==================================================================

    private static void writeSchem(Path file, Model m) throws IOException {
        // 调色板: 方块名 -> 下标(按首次出现顺序)
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(AIR, 0);
        for (String cell : m.cells) {
            palette.computeIfAbsent(cell, k -> palette.size());
        }

        // varint 流, 顺序 (y * d + z) * w + x
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (String cell : m.cells) {
            writeVarint(data, palette.get(cell));
        }

        Nbt paletteTag = new Nbt();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            paletteTag.intTag(e.getKey(), e.getValue());
        }
        paletteTag.end();

        Nbt blocks = new Nbt();
        blocks.putCompound("Palette", paletteTag);
        blocks.byteArrayTag("Data", data.toByteArray());
        blocks.listTag("BlockEntities", TAG_COMPOUND, 0);
        blocks.end();

        Nbt root = new Nbt();
        root.intTag("Version", 3);
        root.intTag("DataVersion", 3955);   // 1.21.1
        root.shortTag("Width", m.w);
        root.shortTag("Height", m.h);
        root.shortTag("Length", m.d);
        root.putCompound("Blocks", blocks);
        root.end();

        // 文件 = [根标签类型][根名长度=0][根 compound 负载(含结尾 TAG_End)]
        // NbtReader.readRoot 就是这么读的: 类型字节 -> 根名 -> 负载。
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.write(TAG_COMPOUND);
        fileBytes.write(0);
        fileBytes.write(0);
        byte[] body = root.toBytes();
        fileBytes.write(body, 0, body.length);

        try (OutputStream os = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(os)) {
            gz.write(fileBytes.toByteArray());
        }
    }

    /** 无符号 LEB128, 与 SchematicReader 的解码方式一致。 */
    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    // ==================================================================
    // 极简 NBT 写入器
    //
    // 约定: 一个 Nbt 对象的字节 = 一个完整的 compound 负载(含结尾的 TAG_End)。
    // ==================================================================

    private static final class Nbt {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void intTag(String name, int v) {
            header(TAG_INT, name);
            be32(v);
        }

        void shortTag(String name, int v) {
            header(TAG_SHORT, name);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        void byteArrayTag(String name, byte[] v) {
            header(TAG_BYTE_ARRAY, name);
            be32(v.length);
            out.write(v, 0, v.length);
        }

        void listTag(String name, int elemType, int len) {
            header(TAG_LIST, name);
            out.write(elemType);
            be32(len);
        }

        /** TAG_String 的名字是 8; 这个文件里别处没用到, 所以不另外定义常量。 */
        void stringTag(String name, String v) {
            header(8, name);
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            out.write((b.length >>> 8) & 0xFF);
            out.write(b.length & 0xFF);
            out.write(b, 0, b.length);
        }

        /** 直接追加字节 —— 用于写"列表元素"(元素没有名字)。 */
        void raw(byte[] b) {
            out.write(b, 0, b.length);
        }

        /** 直接追加一个大端 int —— 用于 TAG_List of TAG_Int 的元素。 */
        void rawInt(int v) {
            be32(v);
        }

        void putCompound(String name, Nbt body) {
            byte[] b = body.toBytes();
            header(TAG_COMPOUND, name);
            out.write(b, 0, b.length);
        }

        void end() {
            out.write(TAG_END);
        }

        byte[] toBytes() {
            return out.toByteArray();
        }

        private void header(int type, String name) {
            out.write(type);
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            out.write((n.length >>> 8) & 0xFF);
            out.write(n.length & 0xFF);
            out.write(n, 0, n.length);
        }

        private void be32(int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
