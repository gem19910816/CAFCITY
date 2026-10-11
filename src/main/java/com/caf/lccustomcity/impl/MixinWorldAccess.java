package com.caf.lccustomcity.impl;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.world.level.WorldGenLevel;

/**
 * 世界上下文传递站(供 Mixin 包使用)。
 *
 * <h2>为什么需要它</h2>
 * {@code CityStyle.getRandomBuilding(Random, ChunkCoord)} 的签名里<b>没有</b> world / provider,
 * 而"建筑数量上下限" mixin 需要世界种子(做确定性哈希)和城市风格注册表(判断区块属于哪个子分区)。
 * 好在失落城市的流程里, 选建筑之前一定会先算这个区块的城市风格
 * ({@code BuildingInfo.checkBuildingPossibility} → {@code City.getCityStyle}),
 * 所以 {@code CityStyleZoneMixin} 拦截 {@code getCityStyleInt} 时顺手把 provider 存到这里,
 * 后面的 {@code getRandomBuilding} 就能拿到同一个世界上下文。
 *
 * <p>上下文按生成线程保存，避免并行生成和多个维度之间串用 provider。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包, 供各 Mixin 跨包调用。
 */
public final class MixinWorldAccess {

    private static final ThreadLocal<IDimensionInfo> CONTEXT = new ThreadLocal<>();

    private MixinWorldAccess() {
    }

    /** 在能拿到 provider 的 Mixin 里每次调用都记录一次(代价只是两次字段读)。 */
    public static void record(IDimensionInfo p) {
        if (p == null) {
            return;
        }
        CONTEXT.set(p);
    }

    public static long seed() {
        IDimensionInfo p = CONTEXT.get();
        return p == null ? 0 : p.getSeed();
    }

    /** 可能为 null(比如还没进世界时的 GUI 预览), 调用方必须判空。 */
    public static WorldGenLevel world() {
        IDimensionInfo p = CONTEXT.get();
        return p == null ? null : p.getWorld();
    }

    public static IDimensionInfo provider() {
        return CONTEXT.get();
    }
}
