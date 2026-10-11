package com.caf.lccustomcity.schem;

import java.util.List;
import java.util.Map;

/**
 * 归一化后的建筑数据。.schem(Sponge v2/v3) 与结构 .nbt 都解析成这个模型。
 *
 * <p>坐标约定:
 * <pre>
 *   x: 0 .. sizeX-1  东西
 *   y: 0 .. sizeY-1  高度, y=0 是最底层
 *   z: 0 .. sizeZ-1  南北
 *   线性下标 index(x,y,z) = (y * sizeZ + z) * sizeX + x
 * </pre>
 * Sponge 的 {@code (y * Length + z) * Width + x} 与结构文件的
 * {@code x + sizeX * (z + sizeZ * y)} 展开后都是同一个式子, 所以两种格式可以直接共用。
 *
 * <p><b>方块实体与实体(本次新增)</b>: 除了方块状态, 建筑文件里还带着
 * 「方块实体数据」(箱子里的东西、Create 伪装方块的外观、告示牌文字...)和
 * 「实体」(物品展示框、Create 动态结构)。这两样以前<b>读都不读</b>, 整类丢掉。
 * 现在分别存进 {@link #blockEntities} 和 {@link #entities}; 老文件没有这两块数据时
 * 两张表都是空的, 生成结果与改动前<b>逐字节一致</b>。
 */
public final class SchematicData {

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    /** 调色板: 下标 -> 方块状态字符串, 例如 {@code minecraft:oak_stairs[facing=north,half=bottom]} */
    public final List<String> palette;

    /** 线性下标 -> 调色板下标。 */
    public final int[] blocks;

    /**
     * 线性下标 -> 该位置方块实体的 NBT(原样保留, 含 {@code id}/坐标字段)。
     *
     * <p>为什么用「线性下标」当键: 写调色板时是按坐标遍历的, 用下标查比用坐标三元组快,
     * 也和 {@link #index(int, int, int)} 一一对应, 不会出现「同一格两种键」的歧义。
     */
    public final Map<Integer, Map<String, Object>> blockEntities;

    /** 建筑自带的实体清单(相对坐标 + 完整 NBT); 顺序与文件里一致。 */
    public final List<SchematicEntity> entities;

    /** 旧构造器(没有方块实体/实体数据) —— 保持向后兼容, 行为和改动前一模一样。 */
    public SchematicData(int sizeX, int sizeY, int sizeZ, List<String> palette, int[] blocks) {
        this(sizeX, sizeY, sizeZ, palette, blocks, Map.of(), List.of());
    }

    public SchematicData(int sizeX, int sizeY, int sizeZ, List<String> palette, int[] blocks,
                         Map<Integer, Map<String, Object>> blockEntities, List<SchematicEntity> entities) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities == null ? Map.of() : blockEntities;
        this.entities = entities == null ? List.of() : entities;
    }

    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    /** 这个建筑有没有方块实体数据。 */
    public boolean hasBlockEntities() {
        return !blockEntities.isEmpty();
    }

    /** 这个建筑有没有自带实体(动态结构等)。 */
    public boolean hasEntities() {
        return !entities.isEmpty();
    }

    /** 该位置的方块实体 NBT; 没有(或越界)返回 null。 */
    public Map<String, Object> blockEntityAt(int x, int y, int z) {
        if (blockEntities.isEmpty() || !inBounds(x, y, z)) {
            return null;
        }
        return blockEntities.get(index(x, y, z));
    }

    /** 越界一律视为空气 —— 切片时用来做边界外的填充。 */
    public String blockAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return null;
        }
        int pi = blocks[index(x, y, z)];
        if (pi < 0 || pi >= palette.size()) {
            return null;
        }
        return palette.get(pi);
    }

    /** 该位置是否为空气(含越界)。 */
    public boolean isAir(int x, int y, int z) {
        return isAirState(blockAt(x, y, z));
    }

    /** LC 把空字符当作空气, 所以这几种方块都视为"空"。 */
    public static boolean isAirState(String state) {
        if (state == null || state.isEmpty()) {
            return true;
        }
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

    /**
     * 计算非空气方块的包围盒 {@code [minX, minY, minZ, maxX, maxY, maxZ]} (闭区间)。
     * 全空时返回 null。
     */
    public int[] nonAirBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (!isAir(x, y, z)) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (z < minZ) minZ = z;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                        if (z > maxZ) maxZ = z;
                    }
                }
            }
        }
        if (maxX == Integer.MIN_VALUE) {
            return null;
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}
