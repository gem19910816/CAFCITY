package com.caf.lccustomcity.impl;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性的二维 Perlin 噪声(改进版柏林噪声), 给"连续城区分区"用。
 *
 * <h2>为什么不直接用失落城市/Minecraft 的噪声</h2>
 * 失落城市自己的噪声(ImprovedNoise 等)包在 Minecraft 的类里, 而且入口不保证
 * 我们能拿到同样的种子结构。这里自带一个标准实现, 只依赖 {@link Random}(用世界种子),
 * 保证: 同一个世界种子 + 同一个区块坐标 → 永远得到同一个噪声值。
 *
 * <h2>用法</h2>
 * {@code ZoneNoise.forSeed(worldSeed).fbm01(chunkX / 24.0, chunkZ / 24.0, 3)}
 * 返回 [0,1) 的平滑值; 相邻区块的值接近 ⇒ 按值分段就得到"连成一片"的分区。
 *
 * <p><b>注意: 本类是辅助类, 不能放在 mixin 包里</b>(Sponge Mixin 规定 mixin 包里的
 * 类只能作为 Mixin 加载, 不能被普通引用)。所以它放在 impl 包, 供各 Mixin 跨包调用。
 */
public final class ZoneNoise {

    /** 按世界种子缓存噪声实例(一个存档一个, 几乎不占地)。 */
    private static final Map<Long, ZoneNoise> CACHE = new ConcurrentHashMap<>();

    /** 取某个世界种子对应的噪声实例。 */
    public static ZoneNoise forSeed(long seed) {
        // 异或一个固定盐, 避免和失落城市/原版任何使用同一种子的噪声撞 pattern
        return CACHE.computeIfAbsent(seed ^ 0x4a43c46f6e655a4cL, ZoneNoise::new);
    }

    // ---- 经典 Improved Perlin(参考 Ken Perlin 2002) ----

    private final int[] perm = new int[512];

    private ZoneNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        // Fisher-Yates 洗牌, 种子固定 ⇒ 排列固定 ⇒ 全图确定
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    /** 单倍频噪声, 值域约 [-1, 1]。 */
    public double noise(double x, double y) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        double u = fade(x);
        double v = fade(y);

        int aa = perm[perm[xi] + yi];
        int ab = perm[perm[xi] + yi + 1];
        int ba = perm[perm[xi + 1] + yi];
        int bb = perm[perm[xi + 1] + yi + 1];

        double x1 = lerp(grad(aa, x, y), grad(ba, x - 1, y), u);
        double x2 = lerp(grad(ab, x, y - 1), grad(bb, x - 1, y - 1), u);
        return lerp(x1, x2, v);   // 理论范围 [-1,1], 实际大部分在 [-0.7,0.7]
    }

    /**
     * 分形叠加(fBm), 输出归一到 [0,1)。
     *
     * @param octaves 叠加层数; 层数多边界更曲折, 层数少分区更圆整。3 层是个不错的折中。
     */
    public double fbm01(double x, double y, int octaves) {
        double sum = 0;
        double amp = 1;
        double freq = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x * freq, y * freq) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        double v = sum / norm;            // [-1,1]
        v = v * 0.5 + 0.5;                // [0,1]
        // 防止浮点误差顶到 1(后面要按 floor(v * n) 取下标)
        return Math.min(v, 0.999999999);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    /** 2D 梯度: 用哈希低 2 位选 (±x ±y) 组合。 */
    private static double grad(int hash, double x, double y) {
        return ((hash & 1) == 0 ? x : -x) + ((hash & 2) == 0 ? y : -y);
    }
}
