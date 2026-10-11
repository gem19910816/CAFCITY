package com.caf.lccustomcity.schem;

import com.caf.lccustomcity.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建筑文件解析器。支持三种格式, 统一解析成 {@link SchematicData}:
 * <ol>
 *   <li><b>Sponge v3</b> (WorldEdit 7.2+ 的 {@code .schem}):
 *       {@code Blocks.Palette} + {@code Blocks.Data}</li>
 *   <li><b>Sponge v2</b> (WorldEdit 7.0/7.1 的 {@code .schem}):
 *       {@code Palette} + {@code BlockData}</li>
 *   <li><b>原版结构文件</b> (结构方块导出 / 数据包 {@code .nbt}):
 *       {@code size} + {@code palette} + {@code blocks}</li>
 * </ol>
 *
 * <p>Sponge 方块数据是 varint(LEB128) 序列; 结构 NBT 使用带坐标的稀疏方块列表。
 *
 * <h2>本次新增: 方块实体 + 实体</h2>
 * 以前这两块<b>读都不读</b>, 于是建筑里的箱子全是空的、Create 的伪装方块全是默认外观、
 * 图纸里的动态结构一个都放不出来。现在:
 * <ul>
 *   <li>方块实体: Sponge 读 {@code Blocks.BlockEntities}, 原版结构文件读 {@code blocks[].nbt}
 *       ⇒ {@link SchematicData#blockEntities}</li>
 *   <li>实体: Sponge 读 {@code Entities}, 原版结构文件读 {@code entities}
 *       ⇒ {@link SchematicData#entities}</li>
 * </ul>
 * 读不到/写坏了都<b>只记警告不抛异常</b> —— 建筑照样转换, 只是少一块数据。
 * 没有这两块数据的文件, 解析结果与改动前完全一致。
 *
 * <h2>容错: 原版结构文件的 size / pos 是「int 列表」而不是 int 数组</h2>
 * 结构方块写出来的 {@code size} / {@code blocks[].pos} 是 {@code TAG_List<TAG_Int>},
 * 而我们以前只认 {@code TAG_Int_Array} ⇒ <b>所有原版 .nbt 建筑都直接报
 * 「结构文件缺少 size 字段」被跳过</b>(实例日志里从 10 月初到现在每次都是这句)。
 * 现在两种写法都认。
 *
 * <h2>容错: 写坏了的 BlockEntities</h2>
 * 玩家实例里 38 个 .schem 的 {@code Blocks.BlockEntities} 被写成了 compound(应该是 list),
 * 于是第一个方块实体成了这个 compound 的内容, 第二个成了 {@code Blocks} 的字段,
 * 第三个成了根标签的字段, <b>第四个往后全部堆在根标签结束字节之后</b>。
 * 这里按「规范写法优先, 不规范写法兜底」把它们全部捞回来(见
 * {@link #collectSpongeBlockEntities})。
 */
public final class SchematicReader {

    /** 根标签之后最多再读多少个方块实体(坏数据兜底时的上限, 防止无底洞)。 */
    private static final int MAX_TRAILING_BLOCK_ENTITIES = 20000;

    /** Sponge 里属于「文件格式」而不是「方块实体」的字段: 出现在 {@code Blocks} 这一层。 */
    private static final java.util.Set<String> SPONGE_CONTAINER_KEYS = java.util.Set.of(
            "Palette", "PaletteMax", "Data", "BlockEntities", "Entities", "Scheduler");

    /** 同上, 出现在根标签这一层的格式字段。 */
    private static final java.util.Set<String> SPONGE_ROOT_KEYS = java.util.Set.of(
            "Version", "DataVersion", "Width", "Height", "Length",
            "Blocks", "Palette", "PaletteMax", "BlockData", "BlockEntities", "Entities",
            "Metadata", "Offset", "Scheduler",
            "WEOriginX", "WEOriginY", "WEOriginZ", "WEOffsetX", "WEOffsetY", "WEOffsetZ");

    private SchematicReader() {
    }

    /** 读一个建筑文件(不收集警告)。 */
    public static SchematicData read(Path file) throws IOException {
        return read(file, null);
    }

    /**
     * 读一个建筑文件, 并把「不影响转换、但玩家值得知道」的问题写进 {@code warnings}。
     *
     * @param warnings 允许为 null(那就什么都不记)
     */
    public static SchematicData read(Path file, List<String> warnings) throws IOException {
        NbtFile.Parsed parsed = NbtFile.read(file);
        Map<String, Object> root = NbtReader.asCompound(parsed.root());
        if (root == null) {
            throw new IOException("不是有效的 NBT 复合标签: " + file.getFileName());
        }

        // 旧版 WorldEdit / Sponge v1 会把整个内容包在 "Schematic" 复合标签里,
        // 所以顶层先解一层壳。
        if (!looksLikeSchematic(root)) {
            Map<String, Object> wrapped = NbtReader.compound(root, "Schematic");
            if (wrapped != null && looksLikeSchematic(wrapped)) {
                root = wrapped;
            }
        }

        // ---- Sponge v3 ----
        Map<String, Object> blocksTag = NbtReader.compound(root, "Blocks");
        if (blocksTag != null) {
            Map<String, Object> pal = NbtReader.compound(blocksTag, "Palette");
            byte[] data = NbtReader.byteArray(blocksTag, "Data");
            if (pal != null && data != null) {
                return fromSponge(file, root, blocksTag, pal, data, parsed.tail(), warnings);
            }
        }

        // ---- Sponge v2 ----
        Map<String, Object> pal2 = NbtReader.compound(root, "Palette");
        byte[] data2 = NbtReader.byteArray(root, "BlockData");
        if (pal2 != null && data2 != null) {
            return fromSponge(file, root, root, pal2, data2, parsed.tail(), warnings);
        }

        // ---- 原版结构文件 ----
        if (root.containsKey("blocks") && root.containsKey("palette") && root.containsKey("size")) {
            return fromStructure(file, root, warnings);
        }

        throw new IOException("无法识别的建筑文件格式 (既不是 .schem, 也不是结构方块的 .nbt): "
                + file.getFileName() + " 顶层字段=" + root.keySet());
    }

    /** 判断一个复合标签看起来是不是建筑数据。 */
    private static boolean looksLikeSchematic(Map<String, Object> m) {
        if (m.containsKey("Blocks")) {
            return true;
        }
        if (m.containsKey("Palette") && m.containsKey("BlockData")) {
            return true;
        }
        return m.containsKey("size") && m.containsKey("palette") && m.containsKey("blocks");
    }

    // ------------------------------------------------------------------

    /**
     * Sponge v2/v3。
     *
     * @param root      （可能解过壳的)根标签
     * @param container 真正装着 Palette/Data 的那个复合标签(v3 是 {@code Blocks}, v2 是根)
     * @param tail      根标签之后的剩余字节(写坏了的文件里还藏着方块实体)
     */
    private static SchematicData fromSponge(Path file, Map<String, Object> root, Map<String, Object> container,
                                            Map<String, Object> palTag, byte[] data, byte[] tail,
                                            List<String> warnings) throws IOException {
        int sizeX = NbtReader.intValue(root, "Width", 0);
        int sizeY = NbtReader.intValue(root, "Height", 0);
        int sizeZ = NbtReader.intValue(root, "Length", 0);
        checkSize(file, sizeX, sizeY, sizeZ);
        int volume = sizeX * sizeY * sizeZ;

        // 调色板: 条目是 "方块名" -> 下标
        int maxIdx = -1;
        for (Object v : palTag.values()) {
            maxIdx = Math.max(maxIdx, NbtReader.intValue(v, -1));
        }
        if (maxIdx < 0) {
            throw new IOException("调色板是空的: " + file.getFileName());
        }
        List<String> palette = new ArrayList<>(Collections.nCopies(maxIdx + 1, "minecraft:air"));
        for (Map.Entry<String, Object> e : palTag.entrySet()) {
            int idx = NbtReader.intValue(e.getValue(), -1);
            if (idx >= 0 && idx <= maxIdx) {
                palette.set(idx, normalizeId(e.getKey()));
            }
        }

        int[] blocks = decodeVarInts(data, volume, file);
        Map<Integer, Map<String, Object>> bes =
                collectSpongeBlockEntities(file, root, container, sizeX, sizeY, sizeZ, tail, warnings);
        List<SchematicEntity> entities = collectSpongeEntities(file, root, warnings);
        return new SchematicData(sizeX, sizeY, sizeZ, palette, blocks, bes, entities);
    }

    /** 原版结构文件(结构方块导出 / 数据包)。 */
    private static SchematicData fromStructure(Path file, Map<String, Object> root,
                                               List<String> warnings) throws IOException {
        int[] size = intTriple(root, "size");
        if (size == null) {
            throw new IOException("结构文件缺少 size 字段: " + file.getFileName());
        }
        int sizeX = size[0];
        int sizeY = size[1];
        int sizeZ = size[2];
        checkSize(file, sizeX, sizeY, sizeZ);
        int volume = sizeX * sizeY * sizeZ;

        List<Object> palList = NbtReader.list(root, "palette");
        if (palList == null || palList.isEmpty()) {
            throw new IOException("结构文件的 palette 是空的: " + file.getFileName());
        }
        List<String> palette = new ArrayList<>(palList.size());
        for (Object o : palList) {
            Map<String, Object> entry = NbtReader.asCompound(o);
            palette.add(toStateString(NbtReader.string(entry, "Name"),
                    NbtReader.compound(entry, "Properties")));
        }

        Map<Integer, Map<String, Object>> bes = new LinkedHashMap<>();
        int[] counters = new int[3];       // 0=越界, 1=重复, 2=缺字段
        // Create 结构文件省略空气, palette[0] 可以是任意方块。
        // 追加空气而不是插到表头, 保持文件中 state 编号与调色板一一对应。
        int sourcePaletteSize = palette.size();
        int airIndex = palette.indexOf("minecraft:air");
        if (airIndex < 0) {
            airIndex = palette.size();
            palette.add("minecraft:air");
        }
        int[] blocks = new int[volume];
        java.util.Arrays.fill(blocks, airIndex);
        List<Object> blockList = NbtReader.list(root, "blocks");
        if (blockList != null) {
            for (Object o : blockList) {
                Map<String, Object> b = NbtReader.asCompound(o);
                if (b == null) {
                    continue;
                }
                int state = NbtReader.intValue(b, "state", -1);
                int[] pos = intTriple(b, "pos");
                if (pos == null) {
                    continue;
                }
                int x = pos[0], y = pos[1], z = pos[2];
                // 方块实体: 这一格附带的 nbt 就是它的全部数据
                Map<String, Object> be = NbtReader.compound(b, "nbt");
                if (be != null && !be.isEmpty()) {
                    putBlockEntity(bes, counters, new int[]{x, y, z}, be, sizeX, sizeY, sizeZ);
                }
                if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                    continue;
                }
                if (state < 0 || state >= sourcePaletteSize) {
                    continue;
                }
                blocks[(y * sizeZ + z) * sizeX + x] = state;
            }
        }
        List<SchematicEntity> entities = collectStructureEntities(file, root, warnings);
        reportCounters(file, warnings, counters, "方块实体");
        return new SchematicData(sizeX, sizeY, sizeZ, palette, blocks, bes, entities);
    }

    // ------------------------------------------------------------------
    // 方块实体: Sponge
    // ------------------------------------------------------------------

    /**
     * 收集 Sponge 文件里的方块实体。
     *
     * <p>三种写法都认:
     * <ol>
     *   <li><b>规范写法</b>: {@code Blocks.BlockEntities} 是 list, 每项含 {@code Pos}(int 数组)
     *       或 {@code x/y/z} 三个 int, 外加 {@code id}/{@code Id}</li>
     *   <li><b>被写成 compound</b>: {@code Blocks.BlockEntities} 本身就是第一个方块实体(实测
     *       玩家实例里 38 个 .schem 全是这样), 第二/第三个被压进了 {@code Blocks} /
     *       根标签的字段里 —— 这两种「裸字段」也认</li>
     *   <li><b>堆在根标签之后</b>: 写坏的文件里, 第四个往后的方块实体整段排在根标签结束字节
     *       之后(豪华酒店 17 KB / 红夜总部 137 KB), 按「一个 compound 负载」逐个往下读</li>
     * </ol>
     * 第 2、3 种只在「BlockEntities 存在但不是 list」时才尝试 —— 也就是只有在文件确实
     * 写坏了的时候才走容错分支, 正常文件的行为一个字节都不变。
     */
    private static Map<Integer, Map<String, Object>> collectSpongeBlockEntities(
            Path file, Map<String, Object> root, Map<String, Object> container,
            int sizeX, int sizeY, int sizeZ, byte[] tail, List<String> warnings) {

        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        int[] counters = new int[3];       // 0=越界, 1=重复, 2=认不出来
        Object beRaw = container.get("BlockEntities");
        if (beRaw == null && container != root) {
            beRaw = root.get("BlockEntities");
        }
        if (beRaw == null) {
            return out;
        }

        if (beRaw instanceof List<?> list) {
            // ---- 规范写法 ----
            for (Object o : list) {
                Map<String, Object> be = NbtReader.asCompound(o);
                if (!looksLikeBlockEntity(be)) {
                    counters[2]++;
                    continue;
                }
                putBlockEntity(out, counters, bePos(be), stripPos(be), sizeX, sizeY, sizeZ);
            }
            reportCounters(file, warnings, counters, "方块实体");
            return out;
        }

        // ---- 写坏了的写法(实测玩家实例里 38 个 .schem 全是这样)----
        Map<String, Object> first = NbtReader.asCompound(beRaw);
        if (looksLikeBlockEntity(first)) {
            putBlockEntity(out, counters, bePos(first), stripPos(first), sizeX, sizeY, sizeZ);
        }
        // 第二个: 被压进 Blocks 的字段里
        if (container != root && looksLikeBlockEntity(container)) {
            putBlockEntity(out, counters, bePos(container),
                    flattened(container, SPONGE_CONTAINER_KEYS), sizeX, sizeY, sizeZ);
        }
        // 第三个: 被压进根标签的字段里
        if (looksLikeBlockEntity(root)) {
            putBlockEntity(out, counters, bePos(root),
                    flattened(root, SPONGE_ROOT_KEYS), sizeX, sizeY, sizeZ);
        }
        // 第四个往后: 堆在根标签结束字节之后
        int trailing = collectTrailingBlockEntities(tail, out, counters, sizeX, sizeY, sizeZ);
        if (warnings != null) {
            warnings.add(file.getFileName() + ": 方块实体列表写法不规范(BlockEntities 是 compound 而不是 list), "
                    + "已按容错方式读出 " + out.size() + " 个方块实体(其中 " + trailing
                    + " 个排在根标签之后)。建议用 WorldEdit 重新导出这个文件。");
        }
        reportCounters(file, warnings, counters, "方块实体");
        return out;
    }

    /**
     * 根标签之后那一串「裸 compound 负载」。
     *
     * <p>每个负载读到一个 {@code TAG_End} 为止, 读完必须是「有坐标 + 有 id」的方块实体,
     * 否则立刻停止(数据坏了就保留已经读到的, 绝不硬猜)。
     */
    private static int collectTrailingBlockEntities(byte[] tail, Map<Integer, Map<String, Object>> out,
                                                    int[] counters, int sizeX, int sizeY, int sizeZ) {
        if (tail == null || tail.length == 0) {
            return 0;
        }
        int added = 0;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(tail));
        while (added < MAX_TRAILING_BLOCK_ENTITIES) {
            Map<String, Object> be;
            try {
                if (in.available() <= 0) {
                    break;
                }
                be = NbtReader.readCompoundBody(in);
            } catch (Throwable t) {
                break;      // 截断/坏数据: 到此为止
            }
            if (!looksLikeBlockEntity(be)) {
                break;      // 不是方块实体: 说明已经到尾巴了
            }
            int idx = indexOf(bePos(be), sizeX, sizeY, sizeZ);
            if (idx >= 0 && !out.containsKey(idx)) {
                out.put(idx, stripPos(be));
                added++;
            } else if (idx >= 0) {
                counters[1]++;
            } else {
                counters[0]++;      // 越界或没坐标
            }
        }
        return added;
    }

    /** 一个复合标签看起来是不是方块实体(既要有 id, 也要有坐标)。 */
    private static boolean looksLikeBlockEntity(Map<String, Object> m) {
        if (m == null) {
            return false;
        }
        boolean hasId = (m.get("id") instanceof String) || (m.get("Id") instanceof String);
        return hasId && bePos(m) != null;
    }

    /** 方块实体的坐标: 认 {@code Pos}(int 数组或 int 列表) 与 {@code x/y/z} 三个 int。 */
    private static int[] bePos(Map<String, Object> be) {
        if (be == null) {
            return null;
        }
        int[] p = intTriple(be, "Pos");
        if (p != null) {
            return p;
        }
        Integer x = asInt(be.get("x"));
        Integer y = asInt(be.get("y"));
        Integer z = asInt(be.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return new int[]{x, y, z};
    }

    /** 去掉坐标字段(id 留着 —— 生成调色板时会一起剥掉, 这里不动原始数据)。 */
    private static Map<String, Object> stripPos(Map<String, Object> be) {
        Map<String, Object> copy = new LinkedHashMap<>(be);
        copy.remove("Pos");
        copy.remove("x");
        copy.remove("y");
        copy.remove("z");
        return copy;
    }

    /**
     * 「被压进容器字段里」的方块实体: 它的字段和容器自己的字段混在同一个复合标签里
     * (例如 {@code Blocks} 里既有 Palette/Data 又有 x/y/z/Items/id), 所以必须把
     * <b>容器的格式字段</b>全部剔掉, 只留方块实体自己的字段。
     *
     * <p>不这么做的话那个方块实体的 NBT 里会带着整份 {@code Data}(实测 61 KB 的
     * byte[] 方块数据)和 {@code Palette} —— 会被写进调色板的 tag 字段, 白白撑爆数据包。
     */
    private static Map<String, Object> flattened(Map<String, Object> source, java.util.Set<String> containerKeys) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String k = e.getKey();
            if (containerKeys.contains(k) || k.equals("x") || k.equals("y") || k.equals("z") || k.equals("Pos")) {
                continue;
            }
            copy.put(k, e.getValue());
        }
        return copy;
    }

    // ------------------------------------------------------------------
    // 方块实体: 原版结构文件 / 写入
    // ------------------------------------------------------------------

    /**
     * 把一个方块实体按坐标放进结果表。
     *
     * @param counters 0=越界 1=重复(同一格两个方块实体) 2=认不出来
     */
    private static void putBlockEntity(Map<Integer, Map<String, Object>> out, int[] counters, int[] pos,
                                       Map<String, Object> nbt, int sizeX, int sizeY, int sizeZ) {
        if (nbt == null) {
            counters[2]++;
            return;
        }
        int idx = indexOf(pos, sizeX, sizeY, sizeZ);
        if (idx < 0) {
            counters[0]++;
            return;
        }
        if (out.putIfAbsent(idx, nbt) != null) {
            // 同一格出现两个方块实体: 只保留第一个(保持结果确定, 不随读写顺序漂移)
            counters[1]++;
        }
    }

    /** 坐标 -> 线性下标; 越界或没坐标返回 -1。 */
    private static int indexOf(int[] pos, int sizeX, int sizeY, int sizeZ) {
        if (pos == null || pos.length < 3) {
            return -1;
        }
        int x = pos[0], y = pos[1], z = pos[2];
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return -1;
        }
        return (y * sizeZ + z) * sizeX + x;
    }

    private static void reportCounters(Path file, List<String> warnings, int[] counters, String what) {
        if (warnings == null) {
            return;
        }
        if (counters[0] > 0) {
            warnings.add(file.getFileName() + ": 有 " + counters[0] + " 个" + what
                    + "坐标越界(不在建筑范围内), 已跳过");
        }
        if (counters[1] > 0) {
            warnings.add(file.getFileName() + ": 有 " + counters[1] + " 个" + what
                    + "和别的" + what + "落在同一格, 只保留第一个");
        }
        if (counters[2] > 0) {
            warnings.add(file.getFileName() + ": 有 " + counters[2] + " 个" + what
                    + "缺少 id 或坐标, 认不出来已跳过");
        }
    }

    // ------------------------------------------------------------------
    // 实体
    // ------------------------------------------------------------------

    /**
     * 原版结构文件的 {@code entities}。
     *
     * <p>每项 = {@code {pos:[x,y,z](double 列表), blockPos:[...], nbt:{...}}}。
     * 坐标用 {@code pos}(相对建筑原点, 可以带小数 —— 物品展示框就是 30.5),
     * 数据用 {@code nbt}(原样保留)。
     */
    private static List<SchematicEntity> collectStructureEntities(Path file, Map<String, Object> root,
                                                                  List<String> warnings) {
        List<SchematicEntity> out = new ArrayList<>();
        List<Object> list = NbtReader.list(root, "entities");
        if (list == null) {
            return out;
        }
        int skippedPos = 0;
        int skippedNbt = 0;
        for (Object o : list) {
            Map<String, Object> e = NbtReader.asCompound(o);
            if (e == null) {
                skippedNbt++;
                continue;
            }
            Map<String, Object> nbt = NbtReader.compound(e, "nbt");
            if (nbt == null || nbt.isEmpty()) {
                skippedNbt++;
                continue;
            }
            double[] pos = doubleTriple(e, "pos");
            if (pos == null) {
                // 极少数写法把坐标只放在 nbt 的 Pos 里(那是存档世界坐标, 不能直接用), 只能跳过
                skippedPos++;
                continue;
            }
            out.add(new SchematicEntity(pos[0], pos[1], pos[2], nbt));
        }
        if (warnings != null) {
            if (skippedPos > 0) {
                warnings.add(file.getFileName() + ": 有 " + skippedPos + " 个实体缺少 pos 坐标, 已跳过");
            }
            if (skippedNbt > 0) {
                warnings.add(file.getFileName() + ": 有 " + skippedNbt + " 个实体缺少 nbt 数据, 已跳过");
            }
        }
        return out;
    }

    /**
     * Sponge 的 {@code Entities}。
     *
     * <p>规范写法每项 = {@code {Id:"minecraft:x", Pos:[x,y,z](double), Data:{...}}},
     * 也认把数据直接摊在条目里的写法(那就要把 {@code Pos}/{@code Data} 剥掉)。
     */
    private static List<SchematicEntity> collectSpongeEntities(Path file, Map<String, Object> root,
                                                               List<String> warnings) {
        List<SchematicEntity> out = new ArrayList<>();
        List<Object> list = NbtReader.list(root, "Entities");
        if (list == null) {
            list = NbtReader.list(root, "entities");
        }
        if (list == null) {
            return out;
        }
        int skipped = 0;
        for (Object o : list) {
            Map<String, Object> e = NbtReader.asCompound(o);
            if (e == null) {
                skipped++;
                continue;
            }
            String id = NbtReader.string(e, "Id");
            if (id == null) {
                id = NbtReader.string(e, "id");
            }
            double[] pos = doubleTriple(e, "Pos");
            if (pos == null) {
                pos = doubleTriple(e, "pos");
            }
            Map<String, Object> nbt = NbtReader.compound(e, "Data");
            if (nbt == null) {
                Map<String, Object> copy = new LinkedHashMap<>(e);
                copy.remove("Pos");
                copy.remove("pos");
                copy.remove("Id");
                copy.remove("Data");
                nbt = copy;
            } else {
                nbt = new LinkedHashMap<>(nbt);
            }
            if (id != null && !nbt.containsKey("id")) {
                nbt.put("id", id);
            }
            if (pos == null || !(nbt.get("id") instanceof String)) {
                skipped++;
                continue;
            }
            out.add(new SchematicEntity(pos[0], pos[1], pos[2], nbt));
        }
        if (skipped > 0 && warnings != null) {
            warnings.add(file.getFileName() + ": 有 " + skipped + " 个实体缺少坐标或 id, 已跳过");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 通用取值
    // ------------------------------------------------------------------

    /**
     * 取一个「三个整数的坐标/尺寸」值。
     *
     * <p><b>两种写法都要认</b>: Sponge 用 {@code TAG_Int_Array}, 原版结构文件用
     * {@code TAG_List<TAG_Int>}(结构方块就是这么写的)。以前只认前者, 于是所有原版
     * .nbt 建筑都会报「缺少 size 字段」被跳过。
     */
    private static int[] intTriple(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) parent).get(key);
        if (v instanceof int[] a) {
            return a.length >= 3 ? new int[]{a[0], a[1], a[2]} : null;
        }
        if (v instanceof List<?> l) {
            if (l.size() < 3) {
                return null;
            }
            Integer a = asInt(l.get(0));
            Integer b = asInt(l.get(1));
            Integer c = asInt(l.get(2));
            if (a == null || b == null || c == null) {
                return null;
            }
            return new int[]{a, b, c};
        }
        return null;
    }

    /** 取一个「三个小数」值(实体坐标): 认 double 列表与 double 数组。 */
    private static double[] doubleTriple(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) parent).get(key);
        if (v instanceof List<?> l) {
            if (l.size() < 3) {
                return null;
            }
            Double a = asDouble(l.get(0));
            Double b = asDouble(l.get(1));
            Double c = asDouble(l.get(2));
            if (a == null || b == null || c == null) {
                return null;
            }
            return new double[]{a, b, c};
        }
        if (v instanceof double[] d) {
            return d.length >= 3 ? new double[]{d[0], d[1], d[2]} : null;
        }
        return null;
    }

    private static Integer asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : null;
    }

    private static Double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    // ------------------------------------------------------------------

    private static void checkSize(Path file, int sizeX, int sizeY, int sizeZ) throws IOException {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IOException("建筑尺寸非法 (" + sizeX + " x " + sizeY + " x " + sizeZ + "): " + file.getFileName());
        }
        long vol = (long) sizeX * sizeY * sizeZ;
        if (vol > 64L * 1024 * 1024) {
            throw new IOException("建筑体积过大 (" + vol + " 方块), 拒绝加载: " + file.getFileName());
        }
    }

    /** varint(LEB128) 解码成调色板下标数组; 数据不足的位置补 0(空气)。 */
    private static int[] decodeVarInts(byte[] data, int expected, Path file) throws IOException {
        int[] out = new int[expected];
        int i = 0;
        int o = 0;
        while (i < data.length && o < expected) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                if (i >= data.length) {
                    throw new IOException("方块数据 varint 被截断: " + file.getFileName());
                }
                b = data[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new IOException("方块数据 varint 过长: " + file.getFileName());
                }
            } while ((b & 0x80) != 0);
            out[o++] = value;
        }
        return out;
    }

    /** 补全命名空间, 因为 LC 内部用 {@code ResourceLocation.parse} 解析, 要求 {@code 命名空间:路径}。 */
    private static String normalizeId(String id) {
        if (id == null || id.isEmpty()) {
            return "minecraft:air";
        }
        int br = id.indexOf('[');
        String head = br >= 0 ? id.substring(0, br) : id;
        String tail = br >= 0 ? id.substring(br) : "";
        return head.indexOf(':') >= 0 ? id : "minecraft:" + head + tail;
    }

    /** 把结构文件里 {@code Name + Properties} 拼成标准方块状态字符串。 */
    private static String toStateString(String name, Map<String, Object> props) {
        String id = normalizeId(name == null ? "minecraft:air" : name);
        if (props == null || props.isEmpty()) {
            return id;
        }
        List<String> keys = new ArrayList<>(props.keySet());
        Collections.sort(keys); // 固定顺序, 保证输出可复现
        StringBuilder sb = new StringBuilder(id).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(keys.get(i)).append('=').append(props.get(keys.get(i)));
        }
        return sb.append(']').toString();
    }
}
