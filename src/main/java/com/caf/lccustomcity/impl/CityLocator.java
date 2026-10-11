package com.caf.lccustomcity.impl;

/**
 * 「查最近的城市」的<b>纯函数</b>部分(不含任何 Minecraft / 失落城市的类, 可以被离线测试直接跑)。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包。
 * 接游戏的那一半在同包的 {@link CityCommandService}, 命令注册在 {@code mixin/CityCommandMixin}。
 *
 * <h2>这个类负责什么</h2>
 * <ul>
 *   <li>距离上限参数的解析与回落({@link #parseLimit(String)} / {@link #sanitizeLimit(int)});</li>
 *   <li>搜索窗口 / 记忆化格子的换算({@link #scanWindowChunks(int)} / {@link #cellIndex(int)});</li>
 *   <li>从一堆城心里挑"离玩家最近的那座"({@link #nearest(int, int, int[][])});</li>
 *   <li>"看得见的城区边缘"还差多少格({@link #visibleEdgeDistance(int, int, float)});</li>
 *   <li>8 向方位换算({@link #directionText(int, int)});</li>
 *   <li>内外城分区的三态映射({@link #classifyZone(Boolean)});</li>
 *   <li>最后那几行中文输出({@link #foundLines} / {@link #notFoundLines})。</li>
 * </ul>
 *
 * <h2>为什么"看得见的边缘"要这么算</h2>
 * 失落城市里"这个区块算不算城里"的唯一判据是城市浓度
 * {@code factor = (半径 - 到城心距离) / 半径 > CITY_THRESHOLD}
 * (见 LostCities {@code City.getCityFactor} 第 338~356 行的城心遍历)⇒
 * 真正有楼有路的城区只到 {@code 半径 * (1 - 阈值)} 处。本模组把阈值配成 0.2,
 * 所以半径 1000 的城, 看得见的城区其实只有 800 格。这里和
 * {@link SpawnCityPlan#visibleRadius(float, int)} 用同一个换算, 不另搞一套。
 *
 * <h2>确定性</h2>
 * 全是整数/浮点纯函数: 不用随机数、不读时间、不读文件, 同样的输入永远给同样的输出。
 */
public final class CityLocator {

    /** 所有中文输出的统一前缀。 */
    public static final String PREFIX = "[CAF-LC] ";

    /** 不填参数时用的搜索距离上限(格, 量到城心)。 */
    public static final int DEFAULT_LIMIT = 5000;

    /**
     * 允许的最大搜索距离上限(格)。
     *
     * <p>扫描量按面积涨: 20k 时窗口半径 1275 区块, 约 651 万个候选区块 ——
     * 按失落城市自己 {@code getCityFactor} 的实测速度(约 22ns/候选区块)算约 0.15 秒,
     * 一条不热的 OP 指令可以接受。再大就没意义了(城心平均间距才 2920 格)。
     */
    public static final int MAX_LIMIT = 20000;

    /** 记忆化格子的边长(格): 同一格子里查询的结论完全一样, 所以一个格子只扫一次。 */
    public static final int CELL = 512;

    /** 格子中心到格子里任一点的最大距离(格) = ceil(512 * sqrt(2) / 2) = 363。 */
    public static final int CELL_HALF_DIAGONAL = 363;

    /** 分区: 拿不到(不在城里 / 取不到城市风格)。 */
    public static final int ZONE_UNKNOWN = 0;
    /** 分区: 内城。 */
    public static final int ZONE_INNER = 1;
    /** 分区: 外城。 */
    public static final int ZONE_OUTER = 2;

    /**
     * 8 个方位, 下标 = {@code (方位角 + 22.5) / 45} 向下取整。
     * Minecraft 里 -Z 是北、+Z 是南、+X 是东、-X 是西。
     */
    private static final String[] DIRECTIONS = {
            "正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"
    };

    private CityLocator() {
    }

    // ==================================================================
    // 距离上限参数
    // ==================================================================

    /**
     * 把命令行给的文本解析成可用的搜索距离上限(格)。
     *
     * <p><b>绝不抛异常</b>: 空 / 非数字 / 0 / 负数一律回落到 {@link #DEFAULT_LIMIT},
     * 超过 {@link #MAX_LIMIT} 就夹到上限。这样 {@code /cafcity abc}、
     * {@code /cafcity 0}、{@code /cafcity -5} 都只是"按默认值查", 不会给玩家一个报错。
     *
     * @param raw 命令行原文; null = 玩家没填这个参数
     * @return 1 ~ {@link #MAX_LIMIT} 之间的可用值
     */
    public static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            return sanitizeLimit(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    /** 数字形式的回落: &lt;= 0 当"没填"(用默认值), 超过上限就夹到上限。 */
    public static int sanitizeLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        if (requested > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return requested;
    }

    /**
     * 参数被回落或夹取时给玩家的说明。
     *
     * @param raw   命令行原文(null = 没填参数 ⇒ 不算异常, 不给提示)
     * @param limit 实际用的上限(必须是 {@link #parseLimit(String)} 的结果)
     * @return 空串 = 参数正常, 不用提示
     */
    public static String limitNotice(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        try {
            if (Integer.parseInt(text) == limit) {
                return "";
            }
        } catch (NumberFormatException e) {
            // 不是数字 ⇒ 落到下面的统一提示
        }
        return "输入的距离上限 " + text + " 无效或超出范围, 已按 " + limit + " 格处理";
    }

    // ==================================================================
    // 搜索窗口 / 记忆化格子
    // ==================================================================

    /** 方块坐标属于哪个记忆化格子(对负数也用 floor, 和坐标正负无关)。 */
    public static int cellIndex(int blockCoord) {
        return Math.floorDiv(blockCoord, CELL);
    }

    /** 记忆化格子的中心方块坐标(一定是 {@code CELL} 的整数倍再加半个格子)。 */
    public static int cellCenter(int cellIndex) {
        return cellIndex * CELL + CELL / 2;
    }

    /**
     * 以格子中心为圆心, 要扫多少区块(正方形窗口的半径, 单位区块)。
     *
     * <p>推导: 玩家在格子里 ⇒ 到格子中心最多 {@link #CELL_HALF_DIAGONAL} 格;
     * "城心离玩家不超过 limit 格" ⇒ "城心离格子中心不超过 limit + 363 格"
     * ⇒ 区块偏移不超过 {@code ceil((limit + 363) / 16)}。再多给 2 个区块的余量,
     * 保证边界上那座城不会被漏掉(漏掉就会误报"附近没有城市")。
     *
     * @param limit 距离上限(格, 量到城心); 非法值按 {@link #DEFAULT_LIMIT} 处理
     */
    public static int scanWindowChunks(int limit) {
        int reach = sanitizeLimit(limit) + CELL_HALF_DIAGONAL;
        int needed = (reach + SpawnCityPlan.CHUNK - 1) / SpawnCityPlan.CHUNK;
        return needed + 2;
    }

    // ==================================================================
    // 找最近的城心
    // ==================================================================

    /**
     * 两点之间的方块距离(四舍五入到整数格)。
     *
     * <p>用 long 算平方, 30M 坐标也不会溢出。
     */
    public static int distance(int x1, int z1, int x2, int z2) {
        long dx = (long) x2 - (long) x1;
        long dz = (long) z2 - (long) z1;
        return (int) Math.round(Math.sqrt((double) (dx * dx + dz * dz)));
    }

    /**
     * 一堆城心里离玩家最近的那一座。
     *
     * <p>比较用平方距离(不开方, 免得浮点误差让"更远的那座"胜出);
     * 并列时取数组里靠前的那座, 而数组是按 {@code cx 递增、cz 递增} 的固定顺序扫出来的
     * ⇒ 同一世界同一位置永远是同一个答案。
     *
     * @param centers 城心, 每项 = {城心X, 城心Z, 名义半径}, 都是方块坐标; 可以是 null / 空
     * @return {城心X, 城心Z, 名义半径, 距离} 或 null(列表为空)
     */
    public static int[] nearest(int playerX, int playerZ, int[][] centers) {
        int[] best = null;
        long bestSq = Long.MAX_VALUE;
        if (centers != null) {
            for (int[] c : centers) {
                if (c == null || c.length < 3) {
                    continue;
                }
                long dx = (long) c[0] - (long) playerX;
                long dz = (long) c[1] - (long) playerZ;
                long sq = dx * dx + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
        }
        if (best == null) {
            return null;
        }
        return new int[]{best[0], best[1], best[2], distance(playerX, playerZ, best[0], best[1])};
    }

    /**
     * 同 {@link #nearest}, 但只认"距离 &lt;= limit"的城心 —— 这就是命令里那个"距离上限"。
     *
     * <p>边界: 正好等于 limit 的那座城算"找到了", 再远 1 格就算"没找到";
     * limit 非法(0 / 负数)时按 {@link #DEFAULT_LIMIT} 处理。
     *
     * @return {城心X, 城心Z, 名义半径, 距离} 或 null(上限之内没有城)
     */
    public static int[] nearestWithin(int playerX, int playerZ, int[][] centers, int limit) {
        int[] best = nearest(playerX, playerZ, centers);
        if (best == null) {
            return null;
        }
        return best[3] <= sanitizeLimit(limit) ? best : null;
    }

    // ==================================================================
    // 看得见的城区边缘
    // ==================================================================

    /**
     * "看得见的城区边缘"离玩家还有多少格。
     *
     * <pre>可见边缘距离 = 到城心距离 - 半径 * (1 - CITY_THRESHOLD)</pre>
     *
     * <p>口径和 {@link SpawnCityPlan#visibleRadius(float, int)} 完全一致(同一个换算):
     * <ul>
     *   <li>{@code > 0} ⇒ 你还在城外, 这就是还要走多少格才能看到城市边缘;</li>
     *   <li>{@code == 0} ⇒ 你正好站在城区边缘(这一格按失落城市的判据还不算城里);</li>
     *   <li>{@code < 0} ⇒ 你已经在城里了, 绝对值是"进城多深"。</li>
     * </ul>
     *
     * @param centerDistance 到城心的距离(格, >= 0)
     * @param radius         名义半径(格)
     * @param cityThreshold  配置档的 CITY_THRESHOLD(本模组 0.2)
     */
    public static int visibleEdgeDistance(int centerDistance, int radius, float cityThreshold) {
        int visible = SpawnCityPlan.visibleRadius(cityThreshold, radius);
        return centerDistance - visible;
    }

    // ==================================================================
    // 方位
    // ==================================================================

    /**
     * 从玩家指向城心的 8 向方位(<b>不含"方向"两个字</b>, 由调用方拼)。
     *
     * @return "正北" / "东北" / "正东" / "东南" / "正南" / "西南" / "正西" / "西北";
     *         差分全 0(正好站在城心上)时返回 "原地"
     */
    public static String directionText(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "原地";
        }
        int index = (int) Math.floor((bearingDegrees(dx, dz) + 22.5) / 45.0);
        return DIRECTIONS[Math.floorMod(index, DIRECTIONS.length)];
    }

    /**
     * 方位角(度): 0 = 正北, 90 = 正东, 180 / -180 = 正南, -90 = 正西。
     * Minecraft 里 -Z 是北, 所以 {@code atan2(dx, -dz)}。
     */
    private static double bearingDegrees(int dx, int dz) {
        return Math.toDegrees(Math.atan2((double) dx, (double) -dz));
    }

    // ==================================================================
    // 内外城分区
    // ==================================================================

    /**
     * 把"这个区块是不是内城"映射成三态分区号。
     *
     * <p>是不是内城由 {@code WideRoadLogic.isInnerCity} 判(和宽路系统同一套判据:
     * 区块的城市风格名 == {@code CITY_STYLE_ALTERNATIVE} ⇒ 外城), 这里只做 null 归并 ——
     * 拿不到就说"未知", 绝不猜。
     *
     * @param innerCity true = 内城, false = 外城, null = 拿不到
     */
    public static int classifyZone(Boolean innerCity) {
        if (innerCity == null) {
            return ZONE_UNKNOWN;
        }
        return innerCity ? ZONE_INNER : ZONE_OUTER;
    }

    /**
     * 分区的中文说法(给输出用)。
     *
     * @param visibleEdge 已经算好的可见边缘距离(负数 = 在城里)
     * @param zone        {@link #classifyZone(Boolean)} 的结果
     */
    public static String zoneText(int visibleEdge, int zone) {
        if (visibleEdge >= 0) {
            return "未知(你在城外, 城内分区不适用)";
        }
        if (zone == ZONE_INNER) {
            return "内城";
        }
        if (zone == ZONE_OUTER) {
            return "外城";
        }
        return "未知(拿不到这个区块的城市风格)";
    }

    // ==================================================================
    // 输出(中文, 三行, 每行都带前缀, 方便逐行发给玩家 / 写日志)
    // ==================================================================

    /**
     * 找到城市时的三行输出。
     *
     * <p>第一行给"往哪走、多远、城心在哪", 第二行给"城多大、离看得见的城区边缘还有多远",
     * 第三行给"我现在算什么分区 + 这次搜索的范围"。
     *
     * @param playerX    玩家所在方块 X
     * @param playerZ    玩家所在方块 Z
     * @param centerX    最近那座城的城心 X(方块, 16 的整数倍)
     * @param centerZ    最近那座城的城心 Z
     * @param radius     那座城的<b>实际</b>名义半径(失落城市自己的 {@code getCityRadius})
     * @param minRadius  配置档的 CITY_MINRADIUS
     * @param maxRadius  配置档的 CITY_MAXRADIUS
     * @param threshold  配置档的 CITY_THRESHOLD
     * @param limit      这次用的距离上限(格)
     * @param cityCount  搜索窗口里一共找到几座城心
     * @param zone       {@link #classifyZone(Boolean)} 的结果
     */
    public static String[] foundLines(int playerX, int playerZ, int centerX, int centerZ, int radius,
                                      int minRadius, int maxRadius, float threshold,
                                      int limit, int cityCount, int zone) {
        int centerDistance = distance(playerX, playerZ, centerX, centerZ);
        int visible = SpawnCityPlan.visibleRadius(threshold, radius);
        int edge = visibleEdgeDistance(centerDistance, radius, threshold);

        String[] lines = new String[3];
        if (centerDistance == 0) {
            lines[0] = PREFIX + "最近的城: 城心 (" + centerX + ", " + centerZ
                    + "), 你正站在城心上(距离 0 格)";
        } else {
            lines[0] = PREFIX + "最近的城: 城心 (" + centerX + ", " + centerZ + "), 距你 "
                    + centerDistance + " 格, " + directionText(centerX - playerX, centerZ - playerZ) + "方向";
        }

        String head = PREFIX + "城半径 " + radius + " 格(配置档 " + minRadius + "~" + maxRadius + "), ";
        if (edge > 0) {
            lines[1] = head + "看得见的城区边缘还在 " + edge + " 格外 —— 你还没进城";
        } else if (edge == 0) {
            lines[1] = head + "看得见的城区边缘正好在你脚下(边缘距城心 " + visible + " 格), 再往前走就进城";
        } else {
            lines[1] = head + "你已经在城里(离看得见的城区边缘 " + (-edge)
                    + " 格, 边缘距城心 " + visible + " 格)";
        }

        lines[2] = PREFIX + "你所在分区: " + zoneText(edge, zone)
                + " | 搜索范围: 城心 " + limit + " 格以内, 窗口内共 " + cityCount + " 座城心";
        return lines;
    }

    /**
     * 上限之内没找到城市时的一行输出。
     *
     * <p>还会告诉玩家"窗口里最近的一座其实在多少格外"或者"整个窗口一座都没有",
     * 并给一个更大的距离让他直接复制 —— 玩家不用猜。
     *
     * @param limit                  这次用的距离上限(格)
     * @param nearestCenterDistance  窗口内最近的城心有多远(格); 负数 = 窗口内一座城心都没有
     */
    public static String[] notFoundLines(int limit, int nearestCenterDistance) {
        int used = sanitizeLimit(limit);
        StringBuilder sb = new StringBuilder(PREFIX);
        sb.append("附近 ").append(used).append(" 格内没有找到城市城心");
        if (nearestCenterDistance >= 0) {
            sb.append("(窗口里最近的一座在 ").append(nearestCenterDistance).append(" 格外)");
        } else {
            sb.append("(整个搜索窗口里一座城心都没有)");
        }
        if (used >= MAX_LIMIT) {
            sb.append("。搜索范围已经到上限 ").append(MAX_LIMIT).append(" 格 —— 想找城就朝一个方向多走几万格再查");
        } else {
            int next = Math.min(MAX_LIMIT, Math.max(used * 2, nearestCenterDistance + 16));
            sb.append("。可以试 /cafcity ").append(next).append(" 扩大范围");
        }
        sb.append("。");
        return new String[]{sb.toString()};
    }
}
