package com.caf.lccustomcity.impl;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「野外建筑只在城市 N 格以外生成」的判定逻辑(供 {@code ScatteredMarginMixin} 调用)。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 *
 * <h2>规则</h2>
 * 失落城市原版在<b>非城市区块</b>随机放 5 种野外建筑(无线电塔/小木屋/钻井平台/
 * 加油站/公路餐厅)。本规则额外要求: 候选区块离任何一座城的城心超过
 * {@code 城心半径 + margin} 才允许放 —— 也就是"离城市(城心半径 + 200 格)以内
 * 不放野外建筑"。
 *
 * <h2>判定方法</h2>
 * 完全照抄失落城市 {@code City.getCityFactor} 里的城心遍历循环
 * (LostCities 源码 City.java 第 338-356 行), 只把判定条件从
 * {@code sqdist < radius*radius} 换成 {@code sqdist < (radius+margin)*(radius+margin)}:
 * 对候选区块周围 {@code (CITY_MAXRADIUS + margin + 15) / 16} 格内的每个区块,
 * 找出"同一个配置档的城心", 算它到候选区块的平方距离, 小于 {@code (半径+margin)^2}
 * 就算"离城市太近"。
 *
 * <h2>确定性 / 兜底</h2>
 * 只用失落城市自己的确定性查询(isCityCenter / getCityRadius 都由种子决定),
 * 不碰随机数, 同一世界同一种子结果不变。整段包在 try/catch 里:
 * <b>任何异常都返回 false = 回退失落城市原生行为</b>, 绝不让世界生成崩掉。
 */
public final class ScatteredMargin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-ScatteredMargin");
    private static boolean cafLoggedFail = false;

    private ScatteredMargin() {
    }

    /**
     * 这个区块是否"离城市太近"(太近 = 不许放野外建筑)。
     *
     * @param provider 维度信息
     * @param profile  候选区块生效的配置档
     * @param coord    候选区块坐标
     * @param margin   城心半径之外再额外留出的距离(方块); &lt;= 0 时功能关闭, 直接返回 false
     * @return true = 离某座城市的城心不到 (半径 + margin), 应禁止放野外建筑
     */
    public static boolean nearCity(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord, int margin) {
        if (margin <= 0) {
            return false;
        }
        try {
            int chunkX = coord.chunkX();
            int chunkZ = coord.chunkZ();
            // 和 City.getCityFactor 一样的遍历窗口, 只是把 CITY_MAXRADIUS 加上 margin
            int offset = (profile.CITY_MAXRADIUS + margin + 15) / 16;
            for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
                for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                    ChunkCoord c = new ChunkCoord(provider.getType(), cx, cz);
                    LostCityProfile pro = BuildingInfo.getProfile(c, provider);
                    // 只算同一配置档的城心(照抄原版写法)
                    if (pro != profile) {
                        continue;
                    }
                    if (!City.isCityCenter(c, provider)) {
                        continue;
                    }
                    float radius = City.getCityRadius(c, provider);
                    int dx = cx * 16 - (chunkX << 4);
                    int dz = cz * 16 - (chunkZ << 4);
                    float sqdist = dx * dx + dz * dz;
                    float limit = radius + margin;
                    if (sqdist < limit * limit) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            if (!cafLoggedFail) {
                cafLoggedFail = true;
                CAF_LOGGER.warn("[CAF-LC] 野外建筑离城市距离判定异常, 本次按失落城市原生处理: {}", t.toString());
            }
        }
        return false;
    }
}
