package com.caf.lccustomcity.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 地皮填充的候选挑选 —— 纯函数实现。
 *
 * 本类不引用任何 Minecraft / 失落城市的类，也不做任何 I/O，因此可以离线直测。
 *
 * 背景：失落城市原本是"随机抽一栋建筑，塞不进这块地皮就重抽，抽满次数就放弃"，
 * 于是抽不中时地皮会留空。这里换一个思路：
 *
 *   1) 先按尺寸过滤，只留下真正放得下的候选；
 *   2) 在放得下的候选里优先挑"占地更大"的那一档（让大建筑先去占大块地皮）；
 *   3) 同一档里按权重随机（权重非法时退化成均匀随机）。
 *
 * 挑选规则（写清楚，便于接线时对照）：
 *   - 尺寸单位是区块格。"放得下"= width &lt;= plotWidth 且 depth &lt;= plotDepth，不旋转。
 *     宽或深 &lt;= 0 的候选视为非法候选，直接丢弃；地皮宽或深 &lt;= 0 直接返回空。
 *   - "更大"= 占地面积更大（面积 = 宽 * 深，用 long 计算，避免 int 溢出）。
 *     面积不等于"最大那一档"的候选不会被选中，这就是"优先填满"。
 *   - 同一档内做加权随机：候选的 weight 作为相对权重；weight 为 NaN / 无穷 / &lt;= 0
 *     一律按 0 处理；如果这一档的总权重 &lt;= 0（例如全是 0 或全是非法值），
 *     则退化为均匀随机 —— 候选不够时也不抛异常，尽力均匀。
 *
 * 为什么"只要放得下就一定选中"：
 *   pickCandidate 先过滤出所有放得下的候选；只要这个集合非空，
 *   就从"最大面积那一档"里必然返回一个元素。它绝不会返回 null，
 *   也没有任何"抽满次数就放弃"的分支 —— 只有"一个都放不下"时才返回 null。
 *
 * 确定性：所有随机都来自 (long 种子 + 输入列表) 派生出来的独立 java.util.Random
 * 实例（每次 new，绝不用全局状态），种子先经 splitmix64 混合以打散相关性。
 * 同一个 (种子, 输入) 永远得到同样的结果。
 */
public final class PlotFill {

    /** 工具类，不允许实例化。 */
    private PlotFill() {
    }

    // ------------------------------------------------------------------
    // 候选建筑
    // ------------------------------------------------------------------

    /** 一个候选建筑：id + 占地宽 x 深（区块格）+ 权重/优先级。 */
    public static final class Candidate {

        /** 建筑 id（null 视为空串）。 */
        public final String id;
        /** 占地宽，单位区块格。 */
        public final int width;
        /** 占地深，单位区块格。 */
        public final int depth;
        /** 权重 / 优先级，仅在"同尺寸档"内影响随机分布。 */
        public final double weight;

        public Candidate(String id, int width, int depth, double weight) {
            this.id = (id == null ? "" : id);
            this.width = width;
            this.depth = depth;
            this.weight = weight;
        }

        /** 占地面积，用 long 避免 int 溢出。 */
        public long area() {
            return (long) width * (long) depth;
        }

        /** 尺寸是否合法且能放进这块地皮。 */
        public boolean fits(int plotWidth, int plotDepth) {
            return width > 0 && depth > 0 && width <= plotWidth && depth <= plotDepth;
        }

        @Override
        public String toString() {
            return id + "(" + width + "x" + depth + ", w=" + weight + ")";
        }
    }

    // ------------------------------------------------------------------
    // 种子派生（splitmix64，纯算法，无状态）
    // ------------------------------------------------------------------

    private static final long SALT_PICK = 0x9E3779B97F4A7C15L;
    private static final long SALT_BAG = 0xC2B2AE3D27D4EB4FL;

    /** splitmix64 混合函数：把任意 long 打散成雪崩效应良好的 long。 */
    public static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 由世界种子 + 用途盐值派生一个子种子，保证不同用途的随机流互不相关。 */
    private static long derive(long seed, long salt) {
        return mix(seed ^ mix(salt));
    }

    // ------------------------------------------------------------------
    // 1 + 2：候选过滤 + 优先填满
    // ------------------------------------------------------------------

    /**
     * 挑一个能放进这块地皮的候选。
     *
     * 规则：先过滤出所有放得下的候选；再只保留占地面积最大的那一档；
     * 最后在这一档里按权重随机（总权重 &lt;= 0 时均匀随机）。
     *
     * 只要存在能放下的候选，返回值一定非空；一个都放不下（或参数非法）时返回 null。
     * 任何情况下都不抛异常。
     *
     * @param plotWidth  地皮可用宽（区块格）
     * @param plotDepth  地皮可用深（区块格）
     * @param candidates 候选列表，可以为 null；列表里的 null 元素会被跳过
     * @param seed       随机种子
     * @return 选中的候选，或 null
     */
    public static Candidate pickCandidate(int plotWidth, int plotDepth, List<Candidate> candidates, long seed) {
        if (plotWidth <= 0 || plotDepth <= 0 || candidates == null || candidates.isEmpty()) {
            return null;
        }

        List<Candidate> fits = new ArrayList<>();
        long bestArea = 0L;
        for (Candidate c : candidates) {
            if (c == null || !c.fits(plotWidth, plotDepth)) {
                continue;
            }
            fits.add(c);
            long a = c.area();
            if (a > bestArea) {
                bestArea = a;
            }
        }
        if (fits.isEmpty()) {
            return null;
        }

        // 只留最大面积那一档。
        List<Candidate> widest = new ArrayList<>();
        for (Candidate c : fits) {
            if (c.area() == bestArea) {
                widest.add(c);
            }
        }

        return pickWeighted(widest, new Random(derive(seed, SALT_PICK)));
    }

    /**
     * 在给定的一批候选里按权重随机取一个。
     * 非法权重（NaN / 无穷 / &lt;= 0）按 0 处理；总权重不为正时退化成均匀随机。
     * 调用方保证 list 非空。
     */
    private static Candidate pickWeighted(List<Candidate> list, Random rnd) {
        double total = 0.0;
        for (Candidate c : list) {
            total += weightOf(c);
        }
        if (!(total > 0.0) || Double.isInfinite(total)) {
            // 总权重不可用：尽力均匀，绝不抛异常。
            return list.get(rnd.nextInt(list.size()));
        }
        double target = rnd.nextDouble() * total;
        double acc = 0.0;
        for (Candidate c : list) {
            acc += weightOf(c);
            if (target < acc) {
                return c;
            }
        }
        return list.get(list.size() - 1);
    }

    /** 有效权重：NaN / 无穷 / &lt;= 0 一律当 0。 */
    private static double weightOf(Candidate c) {
        double w = c.weight;
        if (Double.isNaN(w) || Double.isInfinite(w) || w <= 0.0) {
            return 0.0;
        }
        return w;
    }

    // ------------------------------------------------------------------
    // 3：不放回抽样（洗牌袋）
    // ------------------------------------------------------------------

    /**
     * 把候选洗成一个袋（一轮不放回抽样顺序）。
     *
     * 纯函数：同一个 (种子, 输入) 永远得到同样的顺序。输入不被修改。
     * 输入为 null 时返回空列表；列表里的 null 元素会被丢掉。
     */
    public static List<Candidate> shuffledBag(List<Candidate> candidates, long seed) {
        List<Candidate> out = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c != null) {
                    out.add(c);
                }
            }
        }
        shuffleInPlace(out, new Random(derive(seed, SALT_BAG)));
        return out;
    }

    /** Fisher-Yates 洗牌，随机源由调用方提供。 */
    private static void shuffleInPlace(List<Candidate> list, Random rnd) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }

    /**
     * 有状态的洗牌袋：把候选洗乱后逐个取出，一袋取完自动用派生种子重新洗牌。
     *
     * 用途：同一片城区里尽量不重复地取建筑。
     *
     * 确定性：同一个 (种子, 输入) 会得到完全相同的取出序列（包括第二袋、第三袋……）。
     * 候选为空 / null 时 next() 返回 null，不抛异常。
     */
    public static final class Bag {

        private final List<Candidate> source;
        private final long seed;
        private List<Candidate> order;
        private int cursor;
        private int refills;

        public Bag(List<Candidate> candidates, long seed) {
            List<Candidate> copy = new ArrayList<>();
            if (candidates != null) {
                for (Candidate c : candidates) {
                    if (c != null) {
                        copy.add(c);
                    }
                }
            }
            this.source = copy;
            this.seed = seed;
            this.order = new ArrayList<>();
            this.cursor = 0;
            this.refills = 0;
        }

        /** 袋里一共有几个候选（不随取用变化）。 */
        public int size() {
            return source.size();
        }

        public boolean isEmpty() {
            return source.isEmpty();
        }

        /** 已经装填过几袋（第一次取之前为 0）。 */
        public int refillCount() {
            return refills;
        }

        /** 当前这一袋里还剩几个。 */
        public int remaining() {
            return order.size() - cursor;
        }

        /** 取下一个；袋空了就自动重洗一袋。候选为空时返回 null。 */
        public Candidate next() {
            if (source.isEmpty()) {
                return null;
            }
            if (cursor >= order.size()) {
                refill();
            }
            return order.get(cursor++);
        }

        /**
         * 连续取 count 个；count 超过袋容量时会自动进入下一袋。
         * 候选为空时返回空列表。
         */
        public List<Candidate> next(int count) {
            List<Candidate> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Candidate c = next();
                if (c == null) {
                    break;
                }
                out.add(c);
            }
            return out;
        }

        /** 用派生种子装填新的一袋（第 refills 袋，从 0 开始）。 */
        private void refill() {
            order = shuffledBag(source, derive(seed, SALT_BAG + refills));
            cursor = 0;
            refills++;
        }
    }
}
