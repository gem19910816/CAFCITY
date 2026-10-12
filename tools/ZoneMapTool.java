import com.caf.lccustomcity.config.ZoneConfig;
import com.caf.lccustomcity.config.ZoneWeights;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.impl.ZoneNoise;
import com.caf.lccustomcity.nbt.NbtReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 城区分区分布图分析工具(离线, 不启动游戏)。
 *
 * <p><b>完全复用模组的真实分配逻辑</b>, 一行噪声代码都没有重写:
 * <ul>
 *   <li>噪声: {@link ZoneNoise#forSeed(long)} + {@code fbm01(chunkX/24, chunkZ/24, 3)}
 *       —— 与 {@code CityStyleZoneMixin.caf$pickZoneIndex} 用的参数一模一样</li>
 *   <li>权重切分: {@link ZoneWeights#pickIndex(double, List)} —— Mixin 调用的就是它</li>
 *   <li>子分区风格名: {@code "caf_zone_inner_" + }{@link LcAssetGenerator#shortHash(String)}
 *       —— 与 {@code CityStylePatcher.generateSubZoneStyles} 的命名规则一致,
 *       然后按风格名字典序排序(Mixin 里 {@code Collections.sort} 同款)</li>
 *   <li>占比: 用 {@link ZoneConfig#load} 直接读玩家的 城区配置.json, 取 [min,max] 中点
 *       (与 {@code LcAssetGenerator.generate} 装表时的折叠规则一致)</li>
 * </ul>
 *
 * <p>世界种子从存档 level.dat 里用模组自带的 {@link NbtReader} 读
 * ({@code Data.WorldGenSettings.seed})。
 *
 * <p>注意: 失落城市自己的"是否城市 / 内城还是外城"判定(factor 阈值)不在本工具范围内,
 * 图画的是<b>凡是被判定为内城的区块</b>会被分到哪个分区; 外城/非城市由失落城市决定。
 *
 * <p>用法: java ZoneMapTool &lt;存档目录&gt; &lt;config/CAF失落城市配置 目录&gt; &lt;输出png&gt; [中心方块X 中心方块Z]
 */
public class ZoneMapTool {

    /** 与 CityStyleZoneMixin 里的常量保持一致(只读引用不了私有常量, 值必须相同)。 */
    private static final double ZONE_WAVELENGTH = 24.0;
    private static final int ZONE_OCTAVES = 3;
    private static final String ZONE_PREFIX = "caf_zone_inner_";

    public static void main(String[] args) throws Exception {
        Path saveDir = Path.of(args[0]);
        Path configDir = Path.of(args[1]);
        Path outPng = Path.of(args[2]);
        int centerBlockX = args.length > 3 ? Integer.parseInt(args[3]) : -1362;
        int centerBlockZ = args.length > 4 ? Integer.parseInt(args[4]) : -854;

        // ---- 1. 世界种子 ----
        long seed = readWorldSeed(saveDir.resolve("level.dat"));
        System.out.println("世界种子 = " + seed);

        // ---- 2. 玩家配置(占比) ----
        List<String> warnings = new ArrayList<>();
        ZoneConfig cfg = ZoneConfig.load(configDir, warnings);
        warnings.forEach(w -> System.out.println("[warn] " + w));

        // ---- 3. 内城里"真实存在建筑文件"的子分区(与转换器的口径一致: 有建筑才生成风格) ----
        Path innerDir = configDir.resolve("内城");
        List<String> subZones = new ArrayList<>();
        if (Files.isDirectory(innerDir)) {
            try (var ds = Files.newDirectoryStream(innerDir)) {
                for (Path d : ds) {
                    if (!Files.isDirectory(d)) {
                        continue;
                    }
                    boolean hasBuilding;
                    try (var files = Files.newDirectoryStream(d)) {
                        hasBuilding = false;
                        for (Path f : files) {
                            String n = f.getFileName().toString().toLowerCase();
                            if (Files.isRegularFile(f) && (n.endsWith(".schem") || n.endsWith(".nbt"))) {
                                hasBuilding = true;
                                break;
                            }
                        }
                    }
                    if (hasBuilding) {
                        subZones.add(d.getFileName().toString());
                    }
                }
            }
        }
        if (subZones.isEmpty()) {
            System.out.println("内城下没有任何带建筑的子分区文件夹 —— 分区不会生效(与游戏内现状一致)");
        }

        // ---- 4. 子分区 -> 城市风格名(真实哈希函数), 按风格名排序(Mixin 同款) ----
        // TreeMap 按 key(风格名)字典序 = CityStyleZoneMixin.caf$findZoneStyles 里的 Collections.sort
        Map<String, String> styleToZone = new TreeMap<>();
        for (String sz : subZones) {
            styleToZone.put(ZONE_PREFIX + LcAssetGenerator.shortHash(sz), sz);
        }
        List<String> styleNames = new ArrayList<>(styleToZone.keySet());
        System.out.println("子分区风格(排序后, 下标即 pickIndex 的返回值):");
        ZoneConfig.Zone innerZone = cfg.zones.get("内城");
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String style : styleNames) {
            String zone = styleToZone.get(style);
            double w = ZoneWeights.DEFAULT_WEIGHT;
            if (innerZone != null) {
                ZoneConfig.SubZone sub = innerZone.subZones().get(zone);
                if (sub != null && sub.ratio() != null) {
                    double mid = (sub.ratio().min() + sub.ratio().max()) / 2.0;
                    if (mid > 0 && !Double.isNaN(mid)) {
                        w = mid;
                    }
                }
            }
            weights.put(zone, w);
            System.out.printf("  %s  <-  %s  (权重 %.2f)%n", style, zone, w);
        }
        // 装进真实的运行时表, 让 pickIndex(v, styleNames) 走与 Mixin 完全相同的路径
        Map<String, Double> table = new LinkedHashMap<>();
        for (String style : styleNames) {
            table.put(style, weights.get(styleToZone.get(style)));
        }
        ZoneWeights.install(table);

        // ---- 5. 理论占比 ----
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        System.out.println("理论占比(权重/总和 " + total + "):");
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            System.out.printf("  %s: %.2f%%%n", e.getKey(), e.getValue() / total * 100.0);
        }

        // ---- 6. 逐区块跑真实分配逻辑, 画图 ----
        // 3000x3000 方块 ≈ 188x188 区块
        int sizeChunks = 188;
        int centerChunkX = Math.floorDiv(centerBlockX, 16);
        int centerChunkZ = Math.floorDiv(centerBlockZ, 16);
        int x0 = centerChunkX - sizeChunks / 2;
        int z0 = centerChunkZ - sizeChunks / 2;
        System.out.printf("绘图区域: 区块 [%d,%d] ~ [%d,%d] (%dx%d, 中心区块 %d,%d)%n",
                x0, z0, x0 + sizeChunks - 1, z0 + sizeChunks - 1,
                sizeChunks, sizeChunks, centerChunkX, centerChunkZ);

        // 固定配色
        Map<String, Integer> colors = Map.of(
                "商业区", 0xE0A030,   // 橙黄
                "住宅区", 0x4CAF50,   // 绿
                "工业区", 0x808080,   // 灰
                "军事区", 0xD0342C    // 红
        );
        int fallbackColor = 0x3040C0; // 意外分区: 蓝

        ZoneNoise noise = ZoneNoise.forSeed(seed);   // 真实的噪声实例(含 0x4a43c46f6e655a4c 盐)
        BufferedImage img = new BufferedImage(sizeChunks, sizeChunks, BufferedImage.TYPE_INT_RGB);
        Map<String, int[]> counts = new LinkedHashMap<>();   // 分区 -> [区块数, 噪声值直方图用]
        for (String z : weights.keySet()) {
            counts.put(z, new int[1]);
        }
        double minV = 1, maxV = 0, sumV = 0;
        long n = 0;
        for (int dz = 0; dz < sizeChunks; dz++) {
            for (int dx = 0; dx < sizeChunks; dx++) {
                int cx = x0 + dx;
                int cz = z0 + dz;
                // ↓↓↓ 与 CityStyleZoneMixin.caf$pickZoneIndex 完全一致的三行 ↓↓↓
                double v = noise.fbm01(cx / ZONE_WAVELENGTH, cz / ZONE_WAVELENGTH, ZONE_OCTAVES);
                int idx = ZoneWeights.pickIndex(v, styleNames);
                // ↑↑↑ ---------------------------------------------------- ↑↑↑
                String zone = styleToZone.get(styleNames.get(idx));
                counts.computeIfAbsent(zone, k -> new int[1])[0]++;
                img.setRGB(dx, dz, colors.getOrDefault(zone, fallbackColor));
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
                sumV += v;
                n++;
            }
        }
        Files.createDirectories(outPng.getParent());
        ImageIO.write(img, "PNG", outPng.toFile());
        System.out.println("已写出: " + outPng);

        // ---- 7. 实测统计 ----
        System.out.printf("噪声值范围: [%.4f, %.4f], 均值 %.4f (共 %d 区块)%n", minV, maxV, sumV / n, n);
        System.out.println("实测占比(该区域内):");
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            System.out.printf("  %s: %d 区块 (%.2f%%), 理论 %.2f%%, 偏差 %+.2f 个百分点%n",
                    e.getKey(), e.getValue()[0], e.getValue()[0] * 100.0 / n,
                    weights.get(e.getKey()) / total * 100.0,
                    e.getValue()[0] * 100.0 / n - weights.get(e.getKey()) / total * 100.0);
        }
    }

    /** 从 level.dat 读世界种子(Data.WorldGenSettings.seed, 兼容老的 RandomSeed)。 */
    @SuppressWarnings("unchecked")
    private static long readWorldSeed(Path levelDat) throws Exception {
        Object root = NbtReader.readFile(levelDat);
        if (!(root instanceof Map)) {
            throw new IllegalStateException("level.dat 根不是 Compound");
        }
        Object data = ((Map<String, Object>) root).get("Data");
        if (data instanceof Map) {
            Object wgs = ((Map<String, Object>) data).get("WorldGenSettings");
            if (wgs instanceof Map) {
                Object seed = ((Map<String, Object>) wgs).get("seed");
                if (seed instanceof Number) {
                    return ((Number) seed).longValue();
                }
            }
            Object seed = ((Map<String, Object>) data).get("RandomSeed");   // 老版本字段
            if (seed instanceof Number) {
                return ((Number) seed).longValue();
            }
        }
        throw new IllegalStateException("level.dat 里找不到世界种子");
    }
}
