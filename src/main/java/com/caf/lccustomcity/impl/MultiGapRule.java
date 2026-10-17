package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.GapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 「大楼之间强制留一格空隙」的判定逻辑(供 {@code MultiChunkGapMixin} 调用)。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 *
 * <h2>规则</h2>
 * 多格建筑放进 {@code MultiChunk} 之前, 除了失落城市原生的"占地格必须全空"之外,
 * 本规则还要求<b>占地外圈一格(环形带)也全空</b>: 对 dimX x dimZ 的占地 (x,z),
 * 检查 [x-1, x+dimX] x [z-1, z+dimZ] 这个边框上的每一格, 任何一格已有别的多格建筑
 * (buildingGrid 非 null)就否决这次放置。效果: 两栋多格建筑之间至少隔一个区块。
 *
 * <h2>确定性</h2>
 * 只读已经放下的格子(calculateBuildings 是单线程顺序放的), 不碰随机数,
 * 同一世界同一种子结果不变。开关来自 {@link GapConfig}(启动时一次性读入)。
 *
 * <h2>为什么用反射拿 buildingGrid</h2>
 * {@code MultiChunk.buildingGrid} 是 {@code private final MB[][]}, 而 {@code MB}
 * 是 MultiChunk 的<b>私有</b> record —— Mixin 里没法用 @Shadow 写出它的类型。
 * 反射只读不写, 且任何失败(字段改名等)都按"环为空"放行 = 退回失落城市原生行为,
 * 绝不会因为本规则崩掉区块生成。
 */
public final class MultiGapRule {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-GapRule");

    /** MultiChunk.buildingGrid 的反射句柄(惰性解析, 失败为 null = 本规则整体停用)。 */
    private static volatile Field cafGridField;
    private static volatile boolean cafGridResolved = false;
    private static boolean cafLoggedFail = false;

    private MultiGapRule() {
    }

    /**
     * Mixin 入口: 这栋多格建筑的"外圈一环"是否已被占用(被占用 = 不许放)。
     *
     * @param multiChunk MultiChunk 实例(Mixin 里传 (Object) this)
     * @param x          占地左上角在 buildingGrid 里的 x
     * @param z          占地左上角在 buildingGrid 里的 z
     * @param dimX       占地宽(格)
     * @param dimZ       占地长(格)
     * @return true = 环里有别的建筑, 应否决放置; false = 环空 / 开关关 / 判定失败(放行)
     */
    public static boolean ringOccupied(Object multiChunk, int x, int z, int dimX, int dimZ) {
        return ringOccupied(multiChunk, x, z, dimX, dimZ, GapConfig.enabled());
    }

    public static boolean ringOccupied(Object multiChunk, int x, int z, int dimX, int dimZ,
                                       boolean enabled) {
        if (!enabled) {
            return false;
        }
        try {
            Field f = cafGridField(multiChunk.getClass());
            if (f == null) {
                return false;
            }
            Object[][] grid = (Object[][]) f.get(multiChunk);
            return ringOccupiedGrid(grid, x, z, dimX, dimZ);
        } catch (Throwable t) {
            if (!cafLoggedFail) {
                cafLoggedFail = true;
                CAF_LOGGER.warn("[CAF-LC] 大楼间距判定异常, 本次按失落城市原生处理: {}", t.toString());
            }
            return false;
        }
    }

    /**
     * 纯逻辑: 在 grid 上检查 (x,z) 起 dimX x dimZ 占地的外圈一环是否有非 null 格。
     * 只检查环, 不检查占地本身(占地是否为空由失落城市原生的 canPlaceBuilding 保证)。
     * 越出 grid 边界的环格直接跳过(区域边缘不需要留空)。
     */
    public static boolean ringOccupiedGrid(Object[][] grid, int x, int z, int dimX, int dimZ) {
        if (grid == null) {
            return false;
        }
        int size = grid.length;
        for (int gx = x - 1; gx <= x + dimX; gx++) {
            if (gx < 0 || gx >= size) {
                continue;
            }
            for (int gz = z - 1; gz <= z + dimZ; gz++) {
                if (gz < 0 || gz >= size) {
                    continue;
                }
                // 跳过占地本身(它在原生检查里已经是空的)
                if (gx >= x && gx < x + dimX && gz >= z && gz < z + dimZ) {
                    continue;
                }
                if (grid[gx][gz] != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 解析 MultiChunk.buildingGrid 字段(只解析一次; 找不到返回 null = 规则停用)。 */
    private static Field cafGridField(Class<?> cls) {
        if (cafGridResolved) {
            return cafGridField;
        }
        synchronized (MultiGapRule.class) {
            if (cafGridResolved) {
                return cafGridField;
            }
            Field f = null;
            try {
                f = cls.getDeclaredField("buildingGrid");
                f.setAccessible(true);
            } catch (Throwable t) {
                CAF_LOGGER.warn("[CAF-LC] 找不到 MultiChunk.buildingGrid 字段, 大楼间距规则不生效: {}",
                        t.toString());
            }
            cafGridField = f;
            cafGridResolved = true;
            return cafGridField;
        }
    }
}
