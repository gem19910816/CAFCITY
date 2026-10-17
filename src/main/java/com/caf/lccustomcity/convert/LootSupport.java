package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.config.LootPoolConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/**
 * 分区/单建筑战利品池的资产生成。
 *
 * <h2>机制(失落城市原生, 不用 Mixin)</h2>
 * 失落城市的战利品是"调色板字符级"的: 调色板条目带 {@code "loot":"条件名"},
 * 生成时在字符位置放容器并设战利品表。真实格式(jar 里 common.json):
 * <pre>{"char":"C","block":"minecraft:chest[facing=north]","loot":"chestloot"}</pre>
 * 条件文件(jar 里 conditions/chestloot.json):
 * <pre>{"values":[{"factor":8,"value":"lostcities:chests/lostcitychest"}]}</pre>
 *
 * <h2>命名空间</h2>
 * 调色板 loot 字段不带命名空间时失落城市会补 {@code lostcities:} 前缀
 * ({@code DataTools.parse}), 所以我们的条件名<b>必须写全</b>
 * {@code caf_lc_customcity:xxx}, 否则它去 lostcities 命名空间找, 找不到。
 *
 * <h2>战利品表目录(已从失落城市 jar 确认)</h2>
 * 1.21.1 用的是<b>单数</b> {@code data/<命名空间>/loot_table/}
 * (jar 里就是 {@code data/lostcities/loot_table/chests/lostcitychest.json}),
 * 不是旧版的 {@code loot_tables}。
 *
 * <h2>容器兼容</h2>
 * 战利品只设到继承 {@code RandomizableContainerBlockEntity} 的容器(原版箱子/木桶/
 * 潜影盒/发射器/熔炉等)。不兼容的 mod 容器只是空箱子 + 一条日志, 不会崩。
 */
public final class LootSupport {

    /** 会被挂战利品的容器方块 id(不含命名空间)。 */
    private static final Set<String> CONTAINERS = Set.of(
            "chest", "trapped_chest", "barrel",
            "shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
            "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box",
            "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box",
            "blue_shulker_box", "brown_shulker_box", "green_shulker_box", "red_shulker_box",
            "black_shulker_box",
            "furnace", "blast_furnace", "smoker",
            "dispenser", "dropper", "hopper");

    /** 战利品表在 loot_table 下的子目录(和失落城市自己的 chests/ 并列)。 */
    private static final String TABLE_SUBDIR = "caf_zone";

    private LootSupport() {
    }

    /** 这个方块状态(可能带 [facing=north] 之类)是容器吗? */
    public static boolean isContainer(String blockState) {
        if (blockState == null) {
            return false;
        }
        String id = blockState;
        int br = id.indexOf('[');
        if (br >= 0) {
            id = id.substring(0, br);
        }
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        return CONTAINERS.contains(id);
    }

    /**
     * 登记一套战利品池, 生成对应的<b>条件</b>和<b>战利品表</b>两份资产, 返回条件名
     * (用于写进调色板条目的 loot 字段, 形如 {@code caf_lc_customcity:pool_a1b2c3d4})。
     *
     * <p>内容完全相同的池只生成一份资产(按 {@link LootPoolConfig.LootPool#signature()} 去重),
     * 所以多个分区共用同一套物品时不会重复出文件。
     *
     * @param seen 池签名 -> 条件名 的去重表(由调用方持有, 一次转换内共享)
     */
    public static String register(LcAssetGenerator.Output out, Map<String, String> seen,
                                  LootPoolConfig.LootPool pool) {
        String sig = pool.signature();
        String existing = seen.get(sig);
        if (existing != null) {
            return existing;
        }
        String condName = "pool_" + LcAssetGenerator.shortHash(sig);
        String tableId = LcAssetGenerator.NS + ":" + TABLE_SUBDIR + "/" + condName;

        // 条件: 加权随机出战利品表。这里只有一个值(factor=1), 失落城市会原样取出。
        JsonObject value = new JsonObject();
        value.addProperty("factor", 1);
        value.addProperty("value", tableId);
        JsonArray values = new JsonArray();
        values.add(value);
        JsonObject condition = new JsonObject();
        condition.add("values", values);
        out.putAsset("lostcities/conditions/" + condName + ".json", condition);

        // 战利品表: 标准 MC 格式。目录是 loot_table(单数), 已从失落城市 jar 确认。
        out.putAsset("loot_table/" + TABLE_SUBDIR + "/" + condName + ".json", tableJson(pool));

        // 缓存里必须存【全名】。以前存的是简称 condName, 于是共用同一个池的
        // 第二栋楼起, 走缓存命中分支(见本方法开头)拿到的就是简称 —— 失落城市
        // 会给简称补 lostcities: 前缀, 找不到条件, 区块生成直接报错。
        String fullName = LcAssetGenerator.NS + ":" + condName;
        seen.put(sig, fullName);
        return fullName;
    }

    /** 标准 MC 战利品表: rolls 1~3, 每件物品带 set_count。 */
    private static JsonObject tableJson(LootPoolConfig.LootPool pool) {
        JsonArray entries = new JsonArray();
        for (LootPoolConfig.LootItem item : pool.items()) {
            JsonObject e = new JsonObject();
            e.addProperty("type", "minecraft:item");
            e.addProperty("name", item.id());
            e.addProperty("weight", item.weight());
            JsonObject setCount = new JsonObject();
            setCount.addProperty("function", "minecraft:set_count");
            JsonObject count = new JsonObject();
            count.addProperty("min", item.countMin());
            count.addProperty("max", item.countMax());
            setCount.add("count", count);
            JsonArray functions = new JsonArray();
            functions.add(setCount);
            e.add("functions", functions);
            entries.add(e);
        }
        JsonObject poolObj = new JsonObject();
        JsonObject rolls = new JsonObject();
        rolls.addProperty("min", 1);
        rolls.addProperty("max", 3);
        poolObj.add("rolls", rolls);
        poolObj.add("entries", entries);
        JsonArray pools = new JsonArray();
        pools.add(poolObj);
        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:chest");
        root.add("pools", pools);
        return root;
    }
}
