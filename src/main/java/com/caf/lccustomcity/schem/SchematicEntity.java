package com.caf.lccustomcity.schem;

import com.caf.lccustomcity.nbt.NbtReader;

import java.util.Map;

/**
 * 建筑文件里自带的一个实体(原版结构文件 / Sponge 的 {@code entities} 列表)。
 *
 * <p>典型内容: 物品展示框、盔甲架, 以及<b>机械动力(Create)的动态结构</b>
 * ({@code create:contraption} / {@code create:stationary_contraption}) ——
 * 后者就是「图纸里能转的机器」, 少了它建筑里的机器只剩一堆静态方块。
 *
 * <p>坐标是<b>建筑内的相对坐标</b>: 和 {@link SchematicData} 的方块索引同一套原点
 * (结构文件里 {@code entities[].pos}, Sponge 里 {@code Entities[].Pos})。
 * 允许小数(原版结构文件里物品展示框就是 {@code 30.5})。
 *
 * <p>NBT 原样保存, 不做任何删改 —— 运行时直接交给 Minecraft 的
 * {@code EntityType.create(tag, level)}, 类型一个都不能走样。
 */
public final class SchematicEntity {

    /** 建筑内相对坐标(与方块索引同一原点)。 */
    public final double x;
    public final double y;
    public final double z;

    /** 完整实体 NBT(含 {@code id})。 */
    public final Map<String, Object> nbt;

    /** 实体 id(从 NBT 的 {@code id} 取; 取不到为 null)。 */
    public final String id;

    public SchematicEntity(double x, double y, double z, Map<String, Object> nbt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.nbt = nbt;
        this.id = nbt == null ? null : NbtReader.string(nbt, "id");
    }

    /** 实体所在的方块坐标(向下取整)。 */
    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    @Override
    public String toString() {
        return "entity[" + id + " @ " + x + "," + y + "," + z + "]";
    }
}
