package com.caf.lccustomcity.impl;

/**
 * 「建筑数量上下限」的 <b>O(1) 判定核心</b>(性能修复版: 取代原来的 16384 格位区域扫描)。
 *
 * <h2>为什么重写(2026-10-04 卡顿事故)</h2>
 * 旧实现在 {@code BuildingCountMixin} 里为每个 (配额区域 128x128 区块, 城市风格) 建一张表:
 * 先扫 16384 个格位跑噪声判分区({@link ZoneNoise#fbm01} 3 层), 再对"每栋配了数量的建筑"
 * 各扫一遍 16384 格位、装箱排序候选槽位、建 16384 级别的 HashMap。建筑多的时候
 * (玩家有 83 栋配了数量) 一次就是几百毫秒, 而且缓存满了会整体清空重算。
 * 现在已经没有那张表了: 判定退化成 <b>两次哈希 + 两次 double 比较</b>, 每次调用常数时间。
 *
 * <h2>现在的语义(相对旧版是"近似", 请知悉)</h2>
 * <ul>
 *   <li>每个配额区域 = 128x128 区块(2048x2048 方块, 约等于一座城);</li>
 *   <li>每栋建筑在区域内每个格位有一个确定的"抽签值" {@code u∈[0,1)}(种子+建筑id+坐标的纯函数);</li>
 *   <li><b>名额</b> = 该风格在区域里的候选格位数({@link #effectiveSlots}), <b>落选概率</b>由名额决定:
 *       {@code u * 候选格位数 < 名额} ⇒ 通过。于是"通过"的格位数的<b>期望</b>正好等于名额
 *       (所以上限是"统计意义上的上限", 不是旧版那种"区域内恰好不超过 max 次"的硬保证);</li>
 *   <li>下限同理: {@code u * 候选格位数 < min} 的格位是"被指定给这栋楼的槽位", 期望每区域 min 个;</li>
 *   <li>同一栋楼的"指定槽位"一定是"名额内槽位"(min &lt;= max ⇒ 同一 u 的两个判定是嵌套的),
 *       不会出现"被强制放了却又超上限"的自相矛盾。</li>
 * </ul>
 * 与生成顺序无关(纯函数), 只依赖世界种子 / 建筑 id / 区块坐标 ⇒ 同一世界同一区块结果恒定。
 *
 * <p>本类不引用 Minecraft 类, 可以被离线冒烟测试直接跑。
 */
public final class SlotQuota {

    /** 配额区域边长位移: 2^7 = 128 区块(和旧实现一致, 换算见类注释)。 */
    public static final int REGION_SHIFT = 7;

    /** 配额区域边长(区块)。 */
    public static final int REGION_SIZE = 1 << REGION_SHIFT;

    /** 一个配额区域里的格位总数: 16384。 */
    public static final int REGION_SLOTS = REGION_SIZE * REGION_SIZE;

    /** 抽签哈希盐, 只为和本模组其它用途(宽路稀疏 / 分区噪声)错开。 */
    private static final long QUOTA_SALT = 0x6b43c0dec0de7a11L;

    /** 大于等于这个名额就视为"不限制"(上限写"完全随机"时用的就是 Integer.MAX_VALUE)。 */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    private SlotQuota() {
    }

    /**
     * 一个风格在一个配额区域里的<b>候选格位数</b> = 区域格位数 × 该风格的面积占比。
     *
     * <p>占比来自子分区权重(见 {@code ZoneWeights}): 商业区 2 / 住宅区 1 / 工业区 1 / 军事区 0.2
     * ⇒ 军事区占 0.2/4.2 ≈ 4.8% ⇒ 候选格位约 780 个。非子分区风格(内外城兜底风格)占比按 1.0。
     *
     * @param styleShare 该风格在区域里的面积占比(0~1]; 非正数视为拿不到 ⇒ 只有 1 个候选格位
     * @return 候选格位数, 恒在 [1, {@link #REGION_SLOTS}]
     */
    public static int effectiveSlots(double styleShare) {
        if (!(styleShare > 0)) {
            return 1;
        }
        double share = styleShare > 1 ? 1 : styleShare;
        long n = Math.round(REGION_SLOTS * share);
        if (n < 1) {
            return 1;
        }
        return n > REGION_SLOTS ? REGION_SLOTS : (int) n;
    }

    /**
     * 这栋建筑在这个区块的抽签值 ∈ [0,1)。
     *
     * <p>纯函数: mix64(种子, 建筑 id 的 FNV-1a, 区块坐标) —— 不看生成顺序、不用无种子随机。
     *
     * @param buildingId 建筑资产 id(可为 null, 按空串处理; 绝不抛异常)
     */
    public static double ticket(long seed, String buildingId, int chunkX, int chunkZ) {
        long v = seed ^ QUOTA_SALT ^ fnv1a(buildingId == null ? "" : buildingId);
        v ^= mix64(Integer.toUnsignedLong(chunkX) + 0x9e3779b97f4a7c15L);
        v ^= mix64(Integer.toUnsignedLong(chunkZ) + 0xc2b2ae3d27d4eb4fL);
        v = mix64(v);
        return (v >>> 11) * 0x1.0p-53;
    }

    /**
     * 抽签值是否落在名额内。
     *
     * <p>{@code u * slots < quota} ⇒ 通过; 期望通过格位数 = 名额。
     * 名额 &gt;= 候选格位数(含"完全随机"的 Integer.MAX_VALUE)时恒通过 ⇒ 等于不限制。
     *
     * @param ticket 抽签值({@link #ticket}); 越界也安全
     * @param slots  候选格位数({@link #effectiveSlots}), &lt;= 0 时按 1 处理
     * @param quota  名额(上限或下限); &lt;= 0 时恒不通过
     */
    public static boolean within(double ticket, int slots, int quota) {
        if (quota <= 0) {
            return false;
        }
        if (quota >= UNLIMITED) {
            return true;
        }
        int s = slots < 1 ? 1 : slots;
        if (quota >= s) {
            return true;
        }
        return ticket * s < quota;
    }

    /** splitmix64 终混(和失落城市自己的 unitDouble 思路一致)。 */
    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** FNV-1a 64 位字符串哈希(把建筑 id 混进抽签种子)。 */
    private static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
