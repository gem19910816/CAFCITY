package com.caf.lccustomcity.impl;

/**
 * 「出生点附近城市」的<b>纯几何</b>(不含任何 Minecraft / 失落城市的类, 可以被离线测试直接跑)。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 * 接游戏的那一半在同包的 {@link SpawnCityRule}。
 *
 * <h2>半径: 名义半径 vs 看得见的城区半径</h2>
 * 失落城市里"这个区块算不算城市"的唯一标准是<b>城市浓度</b>(city factor):
 * {@code BuildingInfo.isCityRaw} 判 {@code factor > CITY_THRESHOLD}; 而浓度是
 * <pre>factor = (城市半径 - 区块到城心的距离) / 城市半径</pre>
 * (见 LostCities {@code City.getCityFactor} 第 338~356 行的城心遍历)。
 * 所以:
 * <ul>
 *   <li>{@code factor > 0} 的范围是<b>名义半径</b>({@code getCityRadius} 的值, 本模组 800 格);</li>
 *   <li>真正<b>看得见</b>的城区(有楼有路的城市区块)只到
 *       {@code 名义半径 * (1 - CITY_THRESHOLD)} —— 本模组把阈值强制成 0.2 ⇒ 可见半径 = 0.8 * 半径
 *       ⇒ 半径 800 的城, 看得见的城区其实只有 640 格。</li>
 * </ul>
 *
 * <h2>判定与摆放用同一个口径: <b>城心距离</b>(2026-10 语义切换)</h2>
 * <ol>
 *   <li><b>已满足判定</b>({@link #satisfied})只问一件事: 出生点 N 格以内有没有<b>城心</b>
 *       (≤ N 就算满足)。<b>与半径无关</b>, 也不再乘 {@code (1 - CITY_THRESHOLD)};</li>
 *   <li><b>补出来的城</b>({@link #planGeometry})把<b>城心</b>摆在距出生点约 N 格处
 *       (沿轴按 16 方块对齐, 对齐只会让城更近)。</li>
 * </ol>
 * <p>新保证: <b>出生点 N 格以内一定有一座城的城心</b>。本模组 N = 850、半径 800 ⇒
 * 看得见的城区半径 = 640 ⇒ 看得见的城区边缘大约在 {@code 850 - 640 = 210} 格外。
 *
 * <p><b>旧口径(已废弃, 别再照它写日志)</b>: 以前量的是"<b>看得见的城区边缘</b>到出生点"的距离
 * (默认 200), 于是城心被摆到 {@code 可见半径 + 200} 处 —— 半径 1000 的城就落在 1200 格外,
 * 出生点两百格内还是荒野。那条"出生点外 N 格一定能看到城市边缘"的数学保证<b>已经不成立</b>,
 * 现在只保证"城心在 N 格处"。
 *
 * <h2>确定性</h2>
 * 全部是整数/浮点的纯函数: 不用随机数、不读时间、不读文件。
 * 只依赖 (世界种子, 出生点坐标, 半径, 阈值, 距离), 同一世界同一种子结果永远一样。
 */
public final class SpawnCityPlan {

    /** 一个区块的边长(方块)。失落城市的城心一定落在区块边界上, 所以这里到处是 16。 */
    public static final int CHUNK = 16;

    private SpawnCityPlan() {
    }

    // ==================================================================
    // 半径
    // ==================================================================

    /**
     * 合成城市的名义半径(方块) = 配置档半径区间的中点。
     *
     * <p>取中点是为了"和失落城市自然长出来的城市一样大": 原生的
     * {@code City.getCityRadius} 是 {@code CITY_MINRADIUS + rand.nextInt(MAX-MIN)},
     * 本模组配的是 800~800(城区配置.json 的 50/50 区块), 中点 800。
     *
     * @param minRadius 配置档的 CITY_MINRADIUS
     * @param maxRadius 配置档的 CITY_MAXRADIUS
     */
    public static int cityRadius(int minRadius, int maxRadius) {
        int min = minRadius;
        int max = maxRadius;
        if (max < min) {
            max = min;
        }
        int mid = min + (max - min) / 2;
        return mid < 1 ? 1 : mid;
    }

    /**
     * 看得见的城区半径(方块) = 名义半径 * (1 - 城市阈值)。
     *
     * <p>失落城市里只有 {@code factor > CITY_THRESHOLD} 的区块才是城市区块,
     * 而 {@code factor = (半径 - 距离) / 半径} ⇒ 城区边界在 {@code 半径 * (1 - 阈值)} 处。
     *
     * @param cityThreshold 配置档的 CITY_THRESHOLD(本模组强制 0.2)
     * @param radius        名义半径
     */
    public static int visibleRadius(float cityThreshold, int radius) {
        double t = cityThreshold;
        if (!(t > 0.0)) {
            t = 0.0;
        }
        if (t > 0.95) {
            t = 0.95;      // 阈值 >= 1 等于"没有城市", 夹到 0.95 免得城区半径变成 0
        }
        int v = (int) Math.round(radius * (1.0 - t));
        if (v < 1) {
            v = 1;
        }
        if (v > radius) {
            v = radius;
        }
        return v;
    }

    // ==================================================================
    // "本来就已经满足了吗"
    // ==================================================================

    /**
     * 出生点到某座城的<b>城心</b>的距离(方块), 向上取整(只给日志/玩家看)。
     *
     * <p>判定<b>不用</b>这个函数: {@link #satisfied} 直接比平方距离, 免得开方引进浮点误差。
     */
    public static int centerDistance(int spawnX, int spawnZ, int cx, int cz) {
        return (int) Math.ceil(Math.sqrt((double) centerDistanceSq(spawnX, spawnZ, cx, cz)));
    }

    /** 出生点到城心的平方距离(方块^2)。用 long 算, 大坐标下也不会溢出。 */
    public static long centerDistanceSq(int spawnX, int spawnZ, int cx, int cz) {
        long dx = (long) spawnX - (long) cx;
        long dz = (long) spawnZ - (long) cz;
        return dx * dx + dz * dz;
    }

    /**
     * 出生点到"城心在 (cx,cz)、半径 radius"那座城市的<b>看得见的城区边缘</b>的距离(方块)。
     *
     * <p>这个函数<b>不再参与判定</b>(判定只看城心距离), 只在日志里给玩家报一个参考值:
     * "补出来的城, 它看得见的城区边缘大约在多少格外"。
     *
     * <p>出生点落在城里(离城心不到半径)时返回 0 —— 玩家已经在城里了, 距离当然是 0。
     *
     * @return 0 ~ 很大的整数; 单位: 方块
     */
    public static int edgeDistance(int spawnX, int spawnZ, int cx, int cz, int radius) {
        double dx = (double) spawnX - (double) cx;
        double dz = (double) spawnZ - (double) cz;
        double d = Math.sqrt(dx * dx + dz * dz);
        double edge = d - (double) radius;
        if (!(edge > 0.0)) {
            return 0;
        }
        return (int) Math.ceil(edge);
    }

    /**
     * 附近是否<b>已经</b>有城市的<b>城心</b>在 {@code maxCenterDistance} 格以内(= 什么都不用做)。
     *
     * <p>口径 = "最近的城市的<b>城心</b>距出生点不超过 N 格"(≤ 算满足)。只比城心距离,
     * <b>完全不看半径</b>: 半径 800 的城和半径 2000 的城一视同仁, 也不再乘
     * {@code (1 - CITY_THRESHOLD)}。比较用平方距离 ⇒ 全整数、零浮点误差。
     *
     * @param spawnX            出生点 X(方块)
     * @param spawnZ            出生点 Z(方块)
     * @param maxCenterDistance 要求的最大城心距离; <b>&lt;= 0 表示功能关闭, 一律返回 true(不补)</b>
     * @param cities            既有城市, 每项 = {城心X, 城心Z, 半径}(方块; 半径在这条口径里不参与比较)
     * @return true = 已满足 / 功能关闭 ⇒ 不补城市
     */
    public static boolean satisfied(int spawnX, int spawnZ, int maxCenterDistance, int[][] cities) {
        if (maxCenterDistance <= 0) {
            return true;      // 关闭 = 一律不补
        }
        if (cities == null) {
            return false;
        }
        long limitSq = (long) maxCenterDistance * (long) maxCenterDistance;
        for (int[] c : cities) {
            if (c == null || c.length < 3) {
                continue;
            }
            if (centerDistanceSq(spawnX, spawnZ, c[0], c[1]) <= limitSq) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 补出来的城放在哪
    // ==================================================================

    /**
     * 纯函数: 算出合成城市的城心(已按区块边界对齐), 让<b>城心</b>落在距出生点约
     * {@code centerDistance} 格处。
     *
     * <p>方向由世界种子决定(4 个正方向之一, {@code floorMod(seed, 4)})。几何保证:
     * <ul>
     *   <li>垂直方向(与摆放方向垂直的那条轴)先对齐到出生点所在区块的原点 ⇒ 误差 0~15 格;</li>
     *   <li>沿轴方向的目标偏移先把垂直误差用勾股定理扣掉:
     *       {@code dxMax = floor(sqrt(N^2 - 垂直^2))}, 再向下对齐到 16 的倍数 ⇒
     *       {@code 轴向 <= dxMax} ⇒ {@code 轴向^2 + 垂直^2 <= N^2}
     *       ⇒ <b>城心距出生点永远不超过 N 格, 对齐只会让城更近</b>;</li>
     *   <li>兜底: 目标距离小到"那个方向上连一个 16 对齐点都放不下"时(约 N &lt; 22),
     *       退化为取该方向最近的对齐点(距离只有一个区块量级, 仍然远小于 850 这种正常配置)。</li>
     * </ul>
     *
     * <p><b>注意</b>: 这里<b>不再</b>保证"出生点外 N 格就能看到城市边缘"(旧的 edgeDistance 口径)。
     * 现在保证的是"城心在 N 格处"; 看得见的边缘还要再往里 可见半径(阈值 0.2 ⇒ 0.8 * 半径)那么多格。
     *
     * @param seed           世界种子(只用来选方向; 同一个世界永远一样)
     * @param spawnX         出生点 X
     * @param spawnZ         出生点 Z
     * @param radius         名义半径(原样放进返回值, 交给 {@link #factorAt})
     * @param centerDistance 要求的城心距离(> 0); 调用方保证已经夹到 [MIN_EFFECTIVE, MAX_EFFECTIVE]
     * @return {城心X, 城心Z, 名义半径}, 都是方块坐标; 城心一定是 16 的整数倍
     */
    public static int[] planGeometry(long seed, int spawnX, int spawnZ, int radius, int centerDistance) {
        int reach = centerDistance < CHUNK ? CHUNK : centerDistance;   // 兜底: 目标距离至少一个区块
        int dir = (int) Math.floorMod(seed, 4L);
        int cx;
        int cz;
        if (dir == 0) {
            // 正东: 垂直方向 = Z, 先对齐; 再把垂直误差从目标距离里扣掉
            cz = alignDown(spawnZ);
            cx = axisCoord(spawnX, axialLimit(reach, spawnZ - cz), false);
        } else if (dir == 1) {
            // 正西: 沿轴往更负的方向取整
            cz = alignDown(spawnZ);
            cx = axisCoord(spawnX, axialLimit(reach, spawnZ - cz), true);
        } else if (dir == 2) {
            // 正南: 垂直方向 = X
            cx = alignDown(spawnX);
            cz = axisCoord(spawnZ, axialLimit(reach, spawnX - cx), false);
        } else {
            // 正北
            cx = alignDown(spawnX);
            cz = axisCoord(spawnZ, axialLimit(reach, spawnX - cx), true);
        }
        return new int[]{cx, cz, radius};
    }

    /**
     * 轴向允许的最大偏移 = {@code floor(sqrt(reach^2 - 垂直偏移^2))}。
     *
     * <p>扣掉垂直偏移之后, {@code 轴向^2 + 垂直^2 <= 轴向上限^2 + 垂直^2 <= reach^2},
     * 所以"城心距出生点 <= 目标距离"这条保证与半径、与方向都无关。
     */
    private static int axialLimit(int reach, int perpendicular) {
        long v = (long) reach * (long) reach - (long) perpendicular * (long) perpendicular;
        if (v <= 0L) {
            return 0;
        }
        return (int) Math.floor(Math.sqrt((double) v));
    }

    /**
     * 沿一条轴取坐标: 尽量接近 {@code limit}, 但不超过它, 而且必须是 16 的整数倍、
     * 必须落在 {@code origin} 的指定一侧({@code negative = true} ⇒ 取更小的那一侧)。
     *
     * <p>兜底: 该侧在 limit 以内一个对齐点都没有时, 取该侧最近的那个对齐点
     * (只会在目标距离极小时发生, 见 {@link #planGeometry})。
     */
    private static int axisCoord(int origin, int limit, boolean negative) {
        if (negative) {
            int c = alignUp(origin - limit);        // >= origin - limit 的最小对齐点
            return (c < origin) ? c : alignDown(origin - 1);
        }
        int c = alignDown(origin + limit);          // <= origin + limit 的最大对齐点
        return (c > origin) ? c : alignUp(origin + 1);
    }

    // ==================================================================
    // 浓度
    // ==================================================================

    /**
     * 纯函数: 合成城市给目标区块贡献多少城市浓度(0 = 这个区块不在合成城市里)。
     *
     * <p>公式和失落城市自己的城心遍历完全一致:
     * {@code (半径 - 区块到城心的距离) / 半径}, 距离按"城心区块的原点"算
     * (失落城市就是拿 {@code 城心区块 * 16} 当城心坐标的, 见 City.java 第 347 行)。
     *
     * @param centerX      城心 X(方块, 16 的整数倍)
     * @param centerZ      城心 Z(方块, 16 的整数倍)
     * @param radius       名义半径(方块)
     * @param targetChunkX 目标区块 X
     * @param targetChunkZ 目标区块 Z
     * @return 0.0 ~ 1.0
     */
    public static float factorAt(int centerX, int centerZ, int radius,
                                 int targetChunkX, int targetChunkZ) {
        if (radius <= 0) {
            return 0.0f;
        }
        long dx = (long) centerX - ((long) targetChunkX << 4);
        long dz = (long) centerZ - ((long) targetChunkZ << 4);
        long sq = dx * dx + dz * dz;
        long rr = (long) radius * (long) radius;
        if (sq >= rr) {
            return 0.0f;        // 在城里半径之外 ⇒ 一点浓度都不给
        }
        double dist = Math.sqrt((double) sq);
        double factor = ((double) radius - dist) / (double) radius;
        if (!(factor > 0.0)) {
            return 0.0f;
        }
        return (float) Math.min(1.0, factor);
    }

    /**
     * 纯函数: 把合成浓度叠加到失落城市原生浓度上。
     *
     * <p><b>这是"绝不影响既有城市"的唯一执行点</b>:
     * <ul>
     *   <li>原生浓度 <b>非 0</b>(= 这里已经有城市在起作用) ⇒ <b>原样返回</b>, 合成浓度直接丢掉 ——
     *       既有的城怎么长还怎么长, 一个字节都不改;</li>
     *   <li>原生浓度正好是 0(= 荒野) ⇒ 才用合成浓度。</li>
     * </ul>
     *
     * <p>顺带一个好处: 失落城市的浓度记忆化缓存只存非 0 值, 所以"原生 0 ⇒ 会走到我们这里"
     * 这条路径永远不会被缓存提前返回掉(乘上"原生非 0 我们不改"这条规则, 两边永远一致)。
     *
     * @param nativeFactor     失落城市原生算出来的浓度
     * @param syntheticFactor  合成城市的浓度(0 = 不在合成城市里)
     * @return 最终浓度
     */
    public static float apply(float nativeFactor, float syntheticFactor) {
        if (nativeFactor != 0.0f) {
            return nativeFactor;        // 既有城市说了算
        }
        if (!(syntheticFactor > 0.0f)) {
            return nativeFactor;
        }
        return syntheticFactor;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 向下对齐到区块边界(对负数也用 floor, 和失落城市 {@code 区块 << 4} 的算法一致)。 */
    static int alignDown(int v) {
        return (int) (Math.floorDiv((long) v, (long) CHUNK) * (long) CHUNK);
    }

    /** 向上对齐到区块边界(对负数也一样, 用的是 floorDiv ⇒ 结果 >= v)。 */
    static int alignUp(int v) {
        return (int) (Math.floorDiv((long) v + (long) CHUNK - 1L, (long) CHUNK) * (long) CHUNK);
    }
}
