package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.config.BlockEntityContentConfig;
import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.ZoneConfig;
import com.caf.lccustomcity.schem.SchematicData;
import com.caf.lccustomcity.schem.SchematicReader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 config 目录里的【整栋建筑文件】转换成失落城市的数据包资产。
 *
 * <h2>为什么必须切成 6 层一片</h2>
 * 失落城市放置建筑时, 每一层的 Y 位置是硬编码按 6 递增的
 * ({@code LostCityTerrainFeature} 的 {@code height += FLOORHEIGHT}), 也就是说
 * <b>非顶层的 part 必须正好 6 层</b>, 少于 6 层会留空洞, 多于 6 层会被上一层覆盖。
 * 顶层 part 的高度则不限。所以这里按 6 层切, 最后剩下的一片做顶层。
 *
 * <h2>为什么按 16x16 切格</h2>
 * 失落城市的一个格子就是一个区块, part 的尺寸必须正好 16x16 (少数桥梁零件例外)。
 * 超过 16x16 的建筑要用"多格建筑"(multibuilding), 它的 {@code dimx}/{@code dimz}
 * 上限是 16 (本模组把 worldstyle 的 areasize 覆盖为 16, 见 CityStylePatcher),
 * 所以最大支持 <b>256x256</b>。
 *
 * <h2>多格建筑的硬性约束</h2>
 * {@code BuildingInfo} 里写着: 多格建筑中<b>非左上角</b>的格子会直接复制左上角格子的
 * {@code floors}/{@code cellars}/{@code palette}。因此:
 * <ul>
 *   <li>所有格子必须共用<b>同一张调色板</b> (这里为每个建筑生成一张共享调色板)</li>
 *   <li>所有格子必须声明<b>相同的楼层数</b></li>
 * </ul>
 */
public final class LcAssetGenerator {

    public static final String NS = "caf_lc_customcity";

    private static final int CELL = 16;
    // 多格建筑 dimx/dimz 上限(格)。必须和 CityStylePatcher 写进 worldstyle 的
    // areasize 保持一致 —— 失落城市 MultiChunk 里 rand.nextInt(areasize - dimX + 1),
    // 建筑超过 areasize 会直接崩。16 格 = 256x256 方块。
    // 包级可见: CityStylePatcher 覆盖 worldstyle 时直接引用, 保证两边永远一致。
    // public: 启动自检日志和说明文字都要引用它(见 LcCustomCityMod.README_TEXT),
    // 免得文档里又写一个和代码不一致的数字(曾经 README 写 160x160, 代码其实是 256x256)。
    public static final int MAX_CELLS = 16;
    private static final int FLOOR_HEIGHT = 6;

    /** 所有建筑共用的一张"全空气"part, 用于万一某个 profile 强制生成地下室时填空气。 */
    public static final String EMPTY_PART = "_empty_cellar";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LcAssetGenerator() {
    }

    // ==================================================================

    public record Summary(int buildings, int multiBuildings, int parts, int palettes,
                          List<String> warnings, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** 转换结果: 相对路径(数据包内) -> JSON 文本。 */
    public static final class Output {
        public final List<com.caf.lccustomcity.config.LayoutCatalog.Building> footprints = new ArrayList<>();
        public double outerShare = .4375;
        public final Map<String, String> files = new LinkedHashMap<>();
        // 内城建筑(放 失落城市建筑\内城\... 或直接放根目录的)。默认大多数建筑是内城。
        public final List<String> buildingIds = new ArrayList<>();
        public final List<String> multiIds = new ArrayList<>();
        // 外城建筑(放 失落城市建筑\外城\... 的)。外城没建筑时, 城市边缘会回退用内城的。
        public final List<String> outerBuildingIds = new ArrayList<>();
        public final List<String> outerMultiIds = new ArrayList<>();
        // 内城按"子分区"再分组的建筑清单(子分区名 -> 建筑 id 列表)。
        // 给 CityStylePatcher 生成"每个子分区一套城市风格"用(连续分区 Mixin 按前缀找它们)。
        public final Map<String, List<String>> innerSubBuildingIds = new LinkedHashMap<>();
        public final Map<String, List<String>> innerSubMultiIds = new LinkedHashMap<>();
        // 建筑资产 id(不带命名空间) -> 玩家原始相对路径(如 内城\军事区\导弹楼.schem)。
        // 只给"建筑生成次数统计"(BuildingStats)用: 资产 id 会把中文清成下划线
        // (idFor 的 sanitize), 报告里直接印 id 玩家根本认不出是哪栋楼, 所以要留一份原始名字。
        public final Map<String, String> displayNames = new LinkedHashMap<>();
        // 建筑数量上下限(阶段 3): 城市风格名 -> (建筑资产 id(含命名空间) -> 上下限)。
        // 只收配置了 "数量": [最少,最多] 的建筑; "完全随机" / 缺省 的不出现(=不限制)。
        // ★ 单格和多格分两张表 ★: 失落城市选建筑分 getRandomBuilding(单格, 查 buildings
        // 注册表) 和 getRandomMultiBuilding(多格, 查 multibuildings 注册表) 两条路;
        // 混在一起存会让单格路径的下限逻辑强制返回一个多格 id → 查 buildings 注册表
        // 拿到 null → 区块生成崩(BuildingRE.getRegistryName() NPE)。分表从结构上杜绝。
        // 由 LcCustomCityMod.startup 装进 BuildingCountLimits 供 Mixin 查询。
        public final Map<String, Map<String, BuildingCountLimits.Limit>> countLimitsSingle = new LinkedHashMap<>();
        public final Map<String, Map<String, BuildingCountLimits.Limit>> countLimitsMulti = new LinkedHashMap<>();
        // 内城子分区占比(阶段 2b): 城市风格名(caf_zone_inner_<哈希>) -> 权重(double, > 0)。
        // 只收配置了 "占比" 的子分区; 没配的不出现(= 运行时按 1.0 均分)。
        // 占比是相对权重: 各城区占比 ÷ 总和 = 实际面积比例, 写 3:1 和 0.75:0.25 等价。
        // 由 LcCustomCityMod.startup 装进 ZoneWeights 供 CityStyleZoneMixin 查询。
        public final Map<String, Double> zoneWeights = new LinkedHashMap<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public int partCount;
        public int paletteCount;

        /** 是否一栋建筑都没有(内城+外城、单格+多格全空)。 */
        public boolean isEmpty() {
            return buildingIds.isEmpty() && multiIds.isEmpty()
                    && outerBuildingIds.isEmpty() && outerMultiIds.isEmpty();
        }

        /**
         * 内城所有子分区名: 单格表({@link #innerSubBuildingIds}) ∪ 多格表({@link #innerSubMultiIds})
         * 的键集, 按字典序排序后返回。
         *
         * <p><b>所有"遍历子分区"的消费点都必须用这个方法</b>, 不能只查单格表 ——
         * 整个子分区全是多格建筑时它只出现在多格表里(历史上因此漏掉过分区城市风格的生成,
         * 导致运行时分区逻辑整体失效)。
         *
         * <p>顺序必须固定(排序): CityStyleZoneMixin 运行时是按城市风格名排序枚举分区的,
         * 两边枚举顺序一致, "分区 ↔ 权重"的对应关系才对得上。
         */
        public List<String> innerSubZoneNames() {
            java.util.SortedSet<String> all = new java.util.TreeSet<>(innerSubBuildingIds.keySet());
            all.addAll(innerSubMultiIds.keySet());
            return new ArrayList<>(all);
        }

        /**
         * 写入本模组命名空间下的资产。
         *
         * @param path 形如 {@code lostcities/parts/xxx.json}, 会自动加上 data/&lt;本模组命名空间&gt;/
         */
        void putAsset(String path, JsonObject json) {
            files.put(NS + "/" + path, GSON.toJson(json));
        }

        /**
         * 写入任意命名空间的资产, 用于覆盖失落城市自己的文件(如 citystyle)。
         *
         * @param pathFromData 从 {@code data/} 之后算起的完整路径,
         *                     例如 {@code lostcities/lostcities/citystyles/citystyle_common.json}
         */
        void putExternal(String pathFromData, JsonObject json) {
            files.put(pathFromData, GSON.toJson(json));
        }
    }

    // ==================================================================

    public static Output generate(Path configDir, Logger log) {
        Output out = new Output();

        // 城区配置(城区配置.json): 不存在就写一份带说明的模板。
        // 这个阶段真正用到的只有"战利品池"; 占比/数量只是解析存起来, 留给后面的 Mixin 阶段。
        ZoneConfig zoneCfg = ZoneConfig.load(configDir, out.warnings);
        Map<String, String> lootPools = new HashMap<>();   // 池签名 -> 条件名(去重)

        ScanResult scanResult = scan(configDir, out, log);
        // 报告无条件写一份 —— 包括"一个建筑都没扫到"的时候, 那正是最需要它的时候。
        // 写失败只警告(见 writeScanReport), 绝不影响生成流程。
        writeScanReport(configDir, scanResult, log);
        warnAboutIgnored(configDir, scanResult, log);
        List<Path> files = scanResult.files;
        if (files.isEmpty()) {
            // 这条警告必须带上自检结果: 老实现只说"没有找到任何建筑文件", 玩家完全看不出
            // 是深度超限、后缀不对还是目录选错了 —— 排查花了很久, 就是因为一句话都不说。
            out.warnings.add("在 " + configDir + " 里没有找到任何建筑文件 (*.schem / *.nbt); "
                    + "本次扫描了 " + scanResult.candidates + " 个候选文件, 忽略了 "
                    + scanResult.ignoredTotal() + " 个(原因: " + reasonsSummary(scanResult, false)
                    + "); 详情见 " + SCAN_REPORT_FILE_NAME);
            return out;
        }

        // 所有建筑共用这一张空 part
        addEmptyPart(out);
        out.outerShare = (zoneCfg.outerRatio.min() + zoneCfg.outerRatio.max()) / 2;

        for (Path f : files) {
            try {
                convertOne(configDir, f, out, log, zoneCfg, lootPools);
            } catch (Exception e) {
                String msg = f.getFileName() + " 转换失败: " + e.getMessage();
                out.errors.add(msg);
                log.error("[CAF-LC] {}", msg, e);
            }
        }

        // ---- 内城子分区占比(阶段 2b) ----
        // 只对真正存在建筑文件夹的子分区登记; 风格名规则与 CityStylePatcher.generateSubZoneStyles
        // 严格对齐(caf_zone_inner_<子分区名短哈希>)。区间 [min,max] 取中点 —— 转换期就折叠成
        // 单个 double, 运行时不再碰任何随机。占比 <= 0 → 警告并按 1.0(不登记)。
        ZoneConfig.Zone innerZone = zoneCfg.zones.get("内城");
        if (innerZone != null) {
            // 用并集: 全是多格建筑的子分区只在 innerSubMultiIds 里, 只查单格表会漏掉它的占比
            List<String> subZones = out.innerSubZoneNames();
            for (String subZone : subZones) {
                ZoneConfig.SubZone sub = innerZone.subZones().get(subZone);
                if (sub == null || sub.ratio() == null) {
                    continue;   // 没配占比 → 运行时按 1.0 均分
                }
                double w = (sub.ratio().min() + sub.ratio().max()) / 2.0;
                if (!(w > 0) || Double.isNaN(w)) {
                    out.warnings.add("城区.内城.分区." + subZone + " 的占比(" + sub.ratio().min()
                            + "~" + sub.ratio().max() + ")不大于 0, 已按均分处理");
                    continue;
                }
                out.zoneWeights.put(CityStylePatcher.ZONE_STYLE_PREFIX + shortHash(subZone), w);
            }
        }

        // ---- 兜底 ----
        // BuildingInfo 里写着: 如果 cityStyle.getRandomBuilding() 返回 null 就抛
        // "Invalid building for multibuilding!", 后果是区块生成失败。
        // 所以 citystyle_common(内城)的 selectors.buildings 绝不能为空 —— 内城没有任何
        // 16x16 以内的单格建筑时(可能都放在外城了, 或全都是多格建筑), 补一个全空气占位建筑防崩。
        if (out.buildingIds.isEmpty()) {
            out.putAsset("lostcities/buildings/_fallback_empty.json", fallbackBuildingJson());
            out.buildingIds.add("_fallback_empty");
            out.warnings.add("内城没有任何 16x16 以内的单格建筑(可能都放在 外城 文件夹了, 或全是多格建筑)。"
                    + "失落城市要求内城 buildings 列表非空, 已自动注册一个占位(全空气)建筑以免区块生成失败"
                    + " —— 建议在 内城 文件夹里放几栋 16x16 以内的小建筑。");
        }
        return out;
    }

    /** 占位建筑: 全空气, 只为让 buildings 列表非空。 */
    private static JsonObject fallbackBuildingJson() {
        JsonObject root = new JsonObject();
        // 注意这一层嵌套! BuildingRE 的 "palette" 字段类型是 PaletteRE, 而 PaletteRE
        // 自己的结构是 {"palette":[...]}, 所以必须是 {"palette":{"palette":[...]}}。
        // 直接塞数组会报 "Not a JSON object" —— 建筑解析失败会让创建世界卡在准备界面。
        JsonObject paletteWrapper = new JsonObject();
        paletteWrapper.add("palette", airPalette());
        root.add("palette", paletteWrapper);
        root.addProperty("filler", CharPalette.AIR_CHAR);
        root.addProperty("mincellars", 0);
        root.addProperty("maxcellars", 0);
        root.addProperty("minfloors", 1);
        root.addProperty("maxfloors", 1);
        root.addProperty("overrideFloors", true);
        // 和其他建筑保持一致, 免得"全空气也要凿门"这种说法自相矛盾
        root.addProperty("allowDoors", false);
        root.addProperty("allowFillers", false);

        JsonArray parts = new JsonArray();
        JsonObject floor = new JsonObject();
        floor.addProperty("part", NS + ":" + EMPTY_PART);
        floor.addProperty("top", false);
        floor.addProperty("cellar", false);
        parts.add(floor);
        JsonObject top = new JsonObject();
        top.addProperty("part", NS + ":" + EMPTY_PART);
        top.addProperty("top", true);
        parts.add(top);
        root.add("parts", parts);
        return root;
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 扫描报告文件名(写在 config/失落城市建筑/ 下; 每次都写, 包括"一个建筑都没有"的时候)。 */
    public static final String SCAN_REPORT_FILE_NAME = "建筑扫描报告.txt";

    /** 生成产物目录名(位于 config/失落城市建筑/ 下; 必须和 LcCustomCityMod.GENERATED_PACK_NAME 一致)。 */
    private static final String GENERATED_PACK_DIR_NAME = "生成的建筑包";

    /** 忽略原因: 后缀不符。 */
    private static final String IGNORE_SUFFIX = "后缀不是 .schem / .nbt";
    /** 忽略原因: 文件读不了。 */
    private static final String IGNORE_UNREADABLE = "读不了(权限/被别的程序占用)";
    /**
     * 忽略原因: <b>正常、不用管</b>的一类 —— 本模组自己写在扫描根目录里的说明/配置/统计文件,
     * 以及本模组/玩家留下的备份文件。
     *
     * <p>文案特意写成"本模组/玩家的配置文件与备份(正常)": 玩家在报告里一眼就能看出这类不是问题。
     * 旧文案是"本模组自己的文件(说明/配置/统计, 正常)", 而备份文件当时落进了"后缀不是 .schem /
     * .nbt"那一类 ⇒ 真机实例里 {@code 城区配置.json.bak} 等 4 个文件每次启动都报"可能是放错了"。
     */
    private static final String IGNORE_OWN_FILE = "本模组/玩家的配置文件与备份(正常)";
    /** 常见备份后缀的"基名": {@code .bak} / {@code .bak1} / {@code .bak-caf} / {@code .old} / {@code .orig} 都算(大小写不敏感)。 */
    private static final String[] BACKUP_EXT_BASES = { "bak", "old", "orig" };
    /** 日志/报告里每类最多列几个被忽略的文件名(免得刷屏)。 */
    private static final int IGNORE_SAMPLE_LIMIT = 10;
    /** 报告标题里的时间格式。 */
    private static final DateTimeFormatter REPORT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 一次扫描的自检结果: 找到的建筑文件 + 各类被忽略文件的计数与样例。
     *
     * <p>存在的理由: 老实现把"深度超限"的文件直接过滤掉且一声不响, 玩家把建筑放在
     * 第 3 层时扫描结果为空, 却只看到一句"没有找到任何建筑文件"。现在凡是没有被当成
     * 建筑的文件都必须能说清"为什么不算"(分类计数 + 相对路径)。
     */
    private static final class ScanResult {
        /** 扫描到的建筑文件(按路径排序, 顺序稳定 ⇒ 生成的资产也稳定)。 */
        final List<Path> files = new ArrayList<>();
        /** 见过的普通文件数(不含被整棵剪掉的子树)。 */
        int candidates;
        /** 忽略原因 -> 个数(含"本模组自己的文件"这一类正常忽略)。 */
        final Map<String, Integer> ignoredCounts = new LinkedHashMap<>();
        /** 忽略原因 -> 被忽略文件的相对路径(每类最多 {@link #IGNORE_SAMPLE_LIMIT} 个)。 */
        final Map<String, List<String>> ignoredSamples = new LinkedHashMap<>();
        /** 被整棵剪掉的子树(相对路径) —— 只记目录名, 里面的文件不逐个统计。 */
        final List<String> prunedDirs = new ArrayList<>();

        void ignore(String reason, String relativePath) {
            ignoredCounts.merge(reason, 1, Integer::sum);
            List<String> samples = ignoredSamples.computeIfAbsent(reason, k -> new ArrayList<>());
            if (samples.size() < IGNORE_SAMPLE_LIMIT) {
                samples.add(relativePath);
            }
        }

        /** 全部被忽略的文件数(含"本模组自己的文件"这种正常情况)。 */
        int ignoredTotal() {
            int n = 0;
            for (int v : ignoredCounts.values()) {
                n += v;
            }
            return n;
        }

        /** 真正值得提醒玩家的忽略数(不含本模组自己的说明/配置/统计文件)。 */
        int ignoredProblem() {
            Integer own = ignoredCounts.get(IGNORE_OWN_FILE);
            return ignoredTotal() - (own == null ? 0 : own);
        }
    }

    /**
     * 扫描 config 目录, 找出所有建筑文件。
     *
     * <h2>不限深度(本次修复)</h2>
     * 老实现是 {@code Files.walk(configDir, 3)} + 静默过滤: 深度超过 3 的文件被<b>直接丢掉</b>。
     * 玩家把 38 栋楼放在 {@code 内城\住宅区\x.schem}(第 3 层)时扫描结果为空 ——
     * 只看到一句"没有找到任何建筑文件", 整个生成流程被跳过, 游戏里看到的还是旧建筑包,
     * 而且<b>一句话都不说</b>。现在改成不限深度: 放多深都算。
     *
     * <h2>只剪掉输出目录</h2>
     * 唯一被整棵剪掉的是 {@code 生成的建筑包}(本模组自己的产物, 每次启动都会重新生成几千个
     * 文件): 在 {@code preVisitDirectory} 里返回 {@code SKIP_SUBTREE}, 一个文件都不进去。
     *
     * <h2>跳过必须报原因</h2>
     * 每个没被当成建筑的文件都会被分类计数, 并在有"玩家可能放错"的类别时打一条 WARN
     * (见 {@link #warnAboutIgnored})。另外无条件写一份 {@link #SCAN_REPORT_FILE_NAME}。
     *
     * <p>分区规则(不变): 第一层文件夹是"城区"(内城/外城), 第二层是"子分区"(住宅区/商业区...);
     * 再深的文件夹只影响文件位置, 分区仍按第二层算。直接放根目录算内城。
     */
    private static ScanResult scan(Path configDir, Output out, Logger log) {
        ScanResult res = new ScanResult();
        if (!Files.isDirectory(configDir)) {
            return res;
        }
        try {
            Files.walkFileTree(configDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 只剪掉"生成的建筑包"整棵子树: 那是本模组自己的产物(自动生成的数据包),
                    // 不是玩家放的建筑; 走进去等于每次启动白遍历几千个文件。
                    if (!dir.equals(configDir) && dir.getFileName() != null
                            && dir.getFileName().toString().equals(GENERATED_PACK_DIR_NAME)) {
                        res.prunedDirs.add(relPath(configDir, dir));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    res.candidates++;
                    if (!isBuildingFile(file)) {
                        // 分类计数: 后缀不符 / 本模组自己的说明配置文件(正常, 只计数不报警)
                        res.ignore(isOwnConfigFile(configDir, file) ? IGNORE_OWN_FILE : IGNORE_SUFFIX,
                                relPath(configDir, file));
                        return FileVisitResult.CONTINUE;
                    }
                    res.files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 读不了的文件(权限/被别的进程占用)以前同样是静默丢掉的: 记进报告里
                    res.ignore(IGNORE_UNREADABLE, relPath(configDir, file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            out.errors.add("扫描目录失败: " + e.getMessage());
            if (log != null) {
                log.error("[CAF-LC] 扫描 {} 失败", configDir, e);
            }
        }
        res.files.sort(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)));
        return res;
    }

    /** 报告/日志里用的相对路径(原样用平台分隔符, 玩家在资源管理器里看到的就是这个)。 */
    private static String relPath(Path configDir, Path file) {
        try {
            return configDir.relativize(file).toString();
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    /**
     * 这是不是"正常、不用报警"的文件(本模组自己的说明/配置/统计/报告, 或者备份文件)?
     *
     * <p>这些既不是建筑、也不是玩家放错的东西, 所以单独归一类: <b>只计数、不报警、不列文件名</b>。
     * 不这么做的话每次启动都会因为 {@code 说明.txt} / {@code 城区配置.json} 这些文件
     * 打一条毫无意义的警告(玩家明确要求: 正常情况不要多打日志)。
     *
     * <p><b>备份文件(本次修复)</b>: {@code 城区配置.json.bak} / {@code 城市生成配置.json.bak-caf} /
     * {@code 高速路风格.json.bak-before-highway-doc-refresh} 这类是本模组或玩家自己留的备份,
     * 以前它们落进"后缀不是 .schem / .nbt"那一类 ⇒ 每次启动都报"4 个可能是放错了"(纯噪音)。
     * 现在按文件名判定(见 {@link #isBackupName})且<b>不看它在哪一层</b> —— 备份就是备份,
     * 放在扫描根目录还是放在 {@code 内城\住宅区\} 下面, 都不是"放错的建筑"。
     *
     * <p><b>本模组自己的说明/配置/统计仍然只认直接放在扫描根目录</b>的文件(这条规则没动):
     * 子文件夹里的 .json/.txt 照样算"后缀不符" —— 玩家把 {@code config_backup.json} 这种
     * 丢进分区文件夹, 那真有可能就是放错了, 必须继续报。
     */
    private static boolean isOwnConfigFile(Path configDir, Path file) {
        Path namePath = file.getFileName();
        if (namePath == null) {
            return false;
        }
        String name = namePath.toString();
        // 1) 备份文件: 只看名字, 不看目录(见方法注释)
        if (isBackupName(name)) {
            return true;
        }
        // 2) 本模组自己写在扫描根目录里的说明/配置/统计/报告
        Path parent = file.getParent();
        if (parent == null || !parent.equals(configDir)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json")                  // 城区配置.json / 城市生成配置.json / ...
                || name.equals("建筑生成统计.txt")        // = BuildingStats.FILE_NAME
                || name.equals(SCAN_REPORT_FILE_NAME)
                || name.equals("不注入的存档.txt")        // = LcCustomCityMod.IGNORE_LIST_FILE
                || (name.startsWith("说明") && lower.endsWith(".txt"));   // 说明.txt / 说明-旧版本.txt
    }

    /**
     * 这是不是一个<b>备份文件名</b>(大小写不敏感)?
     *
     * <p>认这些常见写法:
     * <ul>
     *   <li>后缀为 {@code .bak} / {@code .bak1} / {@code .bak2} / {@code .bak-*} / {@code .bak_*}
     *       (例如 {@code 城区配置.json.bak} / {@code 城市生成配置.json.bak-caf}
     *       / {@code 高速路风格.json.bak-before-highway-doc-refresh});</li>
     *   <li>后缀为 {@code .old} / {@code .orig}(同样允许 {@code .old1} / {@code .old-2026-01-01} 这类尾巴);</li>
     *   <li>以 {@code ~} 结尾(编辑器/命令行留下的备份, 例如 {@code 城区配置.json~})。</li>
     * </ul>
     *
     * <p>刻意<b>不</b>把"名字里带 backup 的 .json"算进来: {@code config_backup.json} 这类
     * 仍然按"后缀不符"报警, 该报的一个都不能少。
     */
    private static boolean isBackupName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // 波浪号备份: "城区配置.json~"
        if (lower.endsWith("~")) {
            return true;
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;   // 没有扩展名(或名字以点结尾) ⇒ 不是备份命名
        }
        String ext = lower.substring(dot + 1);
        for (String base : BACKUP_EXT_BASES) {
            if (!ext.startsWith(base)) {
                continue;
            }
            String tail = ext.substring(base.length());
            // .bak / .old / .orig 后面只允许: 结束、- 或 _ 开头、或者纯数字(.bak1 / .bak2)
            if (tail.isEmpty() || tail.charAt(0) == '-' || tail.charAt(0) == '_' || isAllDigits(tail)) {
                return true;
            }
        }
        return false;
    }

    /** 整串都是数字? (给 {@code .bak1} / {@code .bak2} 这种用) */
    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 有"玩家可能放错"的忽略时打<b>一条</b> WARN(说清扫了多少、忽略了什么、例如哪些文件)。
     *
     * <p>刻意不含"本模组自己的文件"那一类 —— 否则每次启动都会刷一条没意义的警告。
     * "生成的建筑包"整棵子树是被剪掉的, 根本不参与计数, 所以也不会刷屏。
     */
    private static void warnAboutIgnored(Path configDir, ScanResult res, Logger log) {
        int n = res.ignoredProblem();
        if (n <= 0 || log == null) {
            return;
        }
        log.warn("[CAF-LC] 建筑扫描: 扫描了 {} 个候选文件, 找到 {} 个建筑, 忽略了 {} 个(原因: {}); "
                        + "被忽略的例如: {}; 详情见 {}",
                res.candidates, res.files.size(), n, reasonsSummary(res, true), samplesSummary(res),
                configDir.resolve(SCAN_REPORT_FILE_NAME));
    }

    /** "后缀不是 .schem / .nbt 3 个; 读不了 1 个" 这样的一行摘要。 */
    private static String reasonsSummary(ScanResult res, boolean problemsOnly) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : res.ignoredCounts.entrySet()) {
            if (problemsOnly && IGNORE_OWN_FILE.equals(e.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue()).append(" 个");
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 被忽略文件的样例(最多 {@link #IGNORE_SAMPLE_LIMIT} 个, 之后写"等 N 个")。 */
    private static String samplesSummary(ScanResult res) {
        List<String> samples = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : res.ignoredSamples.entrySet()) {
            if (IGNORE_OWN_FILE.equals(e.getKey())) {
                continue;
            }
            for (String s : e.getValue()) {
                if (samples.size() < IGNORE_SAMPLE_LIMIT) {
                    samples.add(s);
                }
            }
        }
        if (samples.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder(String.join(", ", samples));
        int total = res.ignoredProblem();
        if (total > samples.size()) {
            sb.append(" 等 ").append(total).append(" 个");
        }
        return sb.toString();
    }

    /** 写扫描报告。写失败<b>只警告</b>: 报告只是给玩家排查用的, 绝不能影响生成流程。 */
    private static void writeScanReport(Path configDir, ScanResult res, Logger log) {
        Path file = configDir.resolve(SCAN_REPORT_FILE_NAME);
        try {
            Files.createDirectories(configDir);
            Files.writeString(file, scanReportText(configDir, res), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 连"目录被占用"这种 RuntimeException 也算在内: 报告写不出来不是致命错误
            if (log != null) {
                log.warn("[CAF-LC] 写 {} 失败(不影响建筑生成): {}", file, t.toString());
            }
        }
    }

    /**
     * 扫描报告的正文。
     *
     * <p>格式照 {@code stats/BuildingStats} 那份"建筑生成统计.txt"的风格: 中文、带时间戳、
     * 分节、最后写清"怎么办"。这份报告的核心价值是回答"我的建筑为什么没生效" ——
     * 所以"没扫到任何建筑"时也必须写, 并且要把被忽略的文件路径原样列出来。
     */
    private static String scanReportText(Path configDir, ScanResult res) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("=== 建筑扫描报告（自 ")
                .append(LocalDateTime.now().format(REPORT_TIME_FMT)).append("）===\n");
        sb.append("扫描根目录: ").append(configDir).append('\n');
        sb.append("扫描深度: 不限（整棵目录树都扫; 唯一被跳过的子树是 ")
                .append(GENERATED_PACK_DIR_NAME).append("\\ 目录, 那是本模组自动生成的数据包）\n");
        sb.append("支持的格式: .schem / .nbt —— 一个文件 = 一栋建筑\n");
        sb.append('\n');

        // ---- 找到了什么(按 内城/外城 分开, 再按子分区列数量) ----
        sb.append("找到建筑文件: ").append(res.files.size()).append(" 个\n");
        for (String zone : new String[] { "内城", "外城" }) {
            Map<String, Integer> bySubZone = new java.util.TreeMap<>();
            int total = 0;
            for (Path f : res.files) {
                if (!zone.equals(zoneOf(configDir, f))) {
                    continue;
                }
                total++;
                String sub = subZoneOf(configDir, f);
                bySubZone.merge(sub == null || sub.isEmpty() ? "（没有子分区, 直接放在 " + zone + " 下）" : sub,
                        1, Integer::sum);
            }
            sb.append("--- ").append(zone).append(" (").append(total).append(" 个) ---\n");
            if (total == 0) {
                sb.append("    （没有）\n");
                continue;
            }
            for (Map.Entry<String, Integer> e : bySubZone.entrySet()) {
                sb.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append(" 个\n");
            }
        }

        // ---- 忽略了什么(每一类都列出来, 这才是玩家排查的关键) ----
        sb.append('\n');
        sb.append("扫描过的候选文件: ").append(res.candidates).append(" 个（不含被跳过的子树）\n");
        sb.append("被忽略的文件: ").append(res.ignoredTotal()).append(" 个");
        if (res.ignoredProblem() > 0) {
            sb.append("（其中 ").append(res.ignoredProblem()).append(" 个可能是放错了, 见下面列出的路径）");
        }
        sb.append('\n');
        if (res.ignoredCounts.isEmpty()) {
            sb.append("    （一个都没有）\n");
        }
        for (Map.Entry<String, Integer> e : res.ignoredCounts.entrySet()) {
            sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append(" 个\n");
            if (IGNORE_OWN_FILE.equals(e.getKey())) {
                continue;   // 本模组自己的说明/配置: 只计数, 不列文件名(否则每次启动刷一屏)
            }
            List<String> samples = res.ignoredSamples.get(e.getKey());
            if (samples == null) {
                continue;
            }
            for (String s : samples) {
                sb.append("          ").append(s).append('\n');
            }
            if (e.getValue() > samples.size()) {
                sb.append("          ... 等 ").append(e.getValue()).append(" 个\n");
            }
        }
        sb.append("被跳过的子树: ").append(res.prunedDirs.isEmpty() ? "（无）"
                : String.join(", ", res.prunedDirs) + "（自动生成的数据包, 里面的文件不扫描、不统计）");
        sb.append('\n');

        // ---- 怎么改 ----
        sb.append('\n');
        sb.append("怎么放（放哪一层都可以, 但“子分区”文件夹要放在 内城\\ 或 外城\\ 下面）:\n");
        sb.append("    失落城市建筑\\内城\\住宅区\\小楼.schem   = 内城, 子分区 住宅区\n");
        sb.append("    失落城市建筑\\外城\\民房\\平房.schem     = 外城, 子分区 民房\n");
        sb.append("    失落城市建筑\\小楼.schem                = 内城（没有子分区）\n");
        sb.append("    还可以再往下套文件夹（不限深度）, 子分区仍按 内城\\ 或 外城\\ 下面的第一层算\n");
        if (res.files.isEmpty()) {
            sb.append('\n');
            sb.append("⚠️ 一个建筑文件都没找到 —— 现在城市里生成的是失落城市自带的原版建筑。\n");
            sb.append("    请照着上面的路径核对一遍(注意是 config\\失落城市建筑\\, 不是存档目录),\n");
            sb.append("    然后重启游戏; 上面“被忽略的文件”列出的路径就是没被当成建筑的那些。\n");
        }
        sb.append('\n');
        sb.append("注意:\n");
        sb.append("    - 只认 .schem / .nbt; .litematic / .zip / 图片 / .json / .txt 一律忽略\n");
        sb.append("    - 备份文件（.bak / .bak-xxx / .old / .orig / 以 ~ 结尾）和本模组自己的说明/配置/统计\n");
        sb.append("      一律只计数、不算“放错了”, 不会被列在上面（这类不用管）\n");
        sb.append("    - 一栋建筑占地最大 16x16 格 = 256x256 方块, 超了会在日志里报 ERROR 并跳过\n");
        sb.append("    - 建筑必须自带出入口(失落城市不会替你凿门)\n");
        sb.append("（本报告每次启动游戏时重新生成, 专门用来排查“我的建筑为什么没生效”）\n");
        return sb.toString();
    }

    /** 这栋建筑属于哪个城区: 第一层文件夹叫"外城"才是外城, 其余(含根目录)都算内城。 */
    private static String zoneOf(Path configDir, Path file) {
        return isOuter(configDir, file) ? "外城" : "内城";
    }

    /**
     * 这栋建筑属于哪个子分区(配置里 "分区" 的键)。
     *
     * <p>规则: {@code <城区>\<子分区>\<建筑>} 取第二层; 直接放根目录的没有子分区(null);
     * 根目录下一层子文件夹({@code <子分区>\<建筑>})视为内城的子分区 —— 和"第一层不是外城
     * 就是内城"的分组规则保持一致。
     */
    private static String subZoneOf(Path configDir, Path file) {
        Path rel = configDir.relativize(file);
        int depth = rel.getNameCount();
        if (depth >= 3) {
            return rel.getName(1).toString();               // 内城\商业区\x.schem / 外城\民房\x.schem
        }
        if (depth == 2 && !isOuter(configDir, file) && !rel.getName(0).toString().equals("内城")) {
            return rel.getName(0).toString();               // 商业区\x.schem → 内城的商业区
        }
        return null;
    }

    /** 这栋建筑属于外城吗? 规则: 第一层文件夹叫"外城"才是外城, 其余(含根目录)都算内城。 */
    private static boolean isOuter(Path configDir, Path file) {
        Path rel = configDir.relativize(file);
        return rel.getNameCount() >= 2 && rel.getName(0).toString().equals("外城");
    }

    private static boolean isBuildingFile(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".schem") || n.endsWith(".nbt");
    }

    /**
     * 由文件路径生成资产 id: 子文件夹名 + 文件名, 转成合法资源路径。
     *
     * <p>注意: Minecraft 的资源路径只允许 {@code [a-z0-9_-.}。中文文件名会被清成下划线,
     * 所以这里额外附加原路径的短哈希 —— 否则「便利店.schem」和「杂货铺.schem」会得到
     * 同一个 id, 建筑互相覆盖。
     */
    private static String idFor(Path configDir, Path file) {
        Path rel = configDir.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            String part = rel.getName(i).toString();
            if (i == rel.getNameCount() - 1) {
                int dot = part.lastIndexOf('.');
                if (dot > 0) {
                    part = part.substring(0, dot);
                }
            }
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(part);
        }
        String original = sb.toString();
        String id = sanitize(original);
        if (id.isEmpty()) {
            id = "building";
        }
        if (id.length() > 40) {
            id = id.substring(0, 40);
        }
        // 附加短哈希: 保证唯一(中文名被清成下划线后仍不冲突), 且同名文件在不同子文件夹也不会冲突
        return id + "_" + shortHash(rel.toString().replace('\\', '/'));
    }

    /** 稳定的小哈希(FNV-1a 32 位), 输出 8 位十六进制。 */
    public static String shortHash(String s) {
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return String.format("%08x", h);
    }

    /** 转成 Minecraft 资源路径允许的字符集: [a-z0-9_-.]。 */
    static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        // 去掉首尾的点/下划线, 避免出现 ".hidden" 之类
        String r = sb.toString();
        while (r.startsWith(".") || r.startsWith("_")) {
            r = r.substring(1);
        }
        while (r.endsWith(".") || r.endsWith("_")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 单个建筑转换
    // ------------------------------------------------------------------

    private static void convertOne(Path configDir, Path file, Output out, Logger log,
                                   ZoneConfig zoneCfg, Map<String, String> lootPools) throws IOException {
        SchematicData data = SchematicReader.read(file, out.warnings);
        String incomplete=SourceCompleteness.warning(file.getFileName().toString(),data);
        if(incomplete!=null)out.warnings.add(incomplete);
        int[] b = data.nonAirBounds();
        if (b == null) {
            out.warnings.add(file.getFileName() + " 是空建筑(全空气), 已跳过");
            return;
        }
        // b = [minX, minY, minZ, maxX, maxY, maxZ]
        int fx = b[3] - b[0] + 1;
        int fy = b[4] - b[1] + 1;
        int fz = b[5] - b[2] + 1;

        int cellsX = ceilDiv(fx, CELL);
        int cellsZ = ceilDiv(fz, CELL);
        if (cellsX > MAX_CELLS || cellsZ > MAX_CELLS) {
            out.errors.add(String.format("%s 占地 %dx%d 方块, 超过上限 %dx%d (失落城市 areasize=%d), 已跳过",
                    file.getFileName(), fx, fz, CELL * MAX_CELLS, CELL * MAX_CELLS, MAX_CELLS));
            return;
        }

        String id = idFor(configDir, file);
        // 记一份"资产 id -> 玩家原始路径", 给建筑生成次数统计报告显示用(sanitize 会把中文抹掉)
        out.displayNames.put(id, configDir.relativize(file).toString());
        int floors = floorsFor(fy);
        int topHeight = fy - FLOOR_HEIGHT * floors;   // <=0 表示顶层需要补空气

        // ---- 0. 战利品池: 单建筑覆盖 > 分区池; 都没有 = null(容器保持失落城市默认) ----
        ZoneConfig.LootPool lootPool = zoneCfg.lootFor(zoneOf(configDir, file), subZoneOf(configDir, file),
                file.getFileName().toString());
        String lootCondition = lootPool == null ? null : LootSupport.register(out, lootPools, lootPool);

        // ---- 1. 共享调色板: 整栋建筑(含所有格子)用同一张 ----
        // ★ 关键: 分配字符时必须带上该位置的方块实体数据 ★
        // 只按方块状态分配的话, 两个状态相同、内容不同的箱子会共用同一个字符,
        // 于是被迫共用同一份 tag —— 全城的箱子内容会变成一模一样。
        CharPalette palette = new CharPalette();
        for (int y = b[1]; y <= b[4]; y++) {
            for (int z = b[2]; z <= b[5]; z++) {
                for (int x = b[0]; x <= b[3]; x++) {
                    palette.charFor(data.blockAt(x, y, z), DynamicNbt.paletteTag(data, x, y, z));
                }
            }
        }
        String paletteId = id + "_palette";
        out.putAsset("lostcities/palettes/" + paletteId + ".json", paletteJson(palette, lootCondition, out, file));
        out.paletteCount++;
        // 字符空间不够时的退化: 绝不静默 —— 玩家看到这条警告就知道该给建筑减内容了
        if (palette.overflowCount() > 0) {
            out.warnings.add(file.getFileName() + ": 调色板字符用完了(上限约 2 万个), 有 "
                    + palette.overflowCount() + " 处方块实体被迫退化成「同一个方块状态共用第一个字符」(例如 "
                    + palette.overflowSamples() + ")。这些位置会显示同状态第一个方块实体的内容。");
        }

        // ---- 2. 填缝/瓦砾用字符: 取出现次数最多的非空气方块 ----
        String fillerChar = dominantChar(data, b, palette);

        // ---- 3. 逐格、逐层生成 part ----
        int padX = (CELL * cellsX - fx) / 2;
        int padZ = (CELL * cellsZ - fz) / 2;

        // buildings[ix][iz] = 建筑 id
        String[][] cellBuildingIds = new String[cellsX][cellsZ];

        for (int ix = 0; ix < cellsX; ix++) {
            for (int iz = 0; iz < cellsZ; iz++) {
                String cellId = cellsX == 1 && cellsZ == 1 ? id : id + "_c" + ix + "_" + iz;
                List<String> floorParts = new ArrayList<>();

                // 非顶层: 每片正好 6 层
                for (int f = 0; f < floors; f++) {
                    String partId = cellId + "_f" + f;
                    int startY = b[1] + f * FLOOR_HEIGHT;
                    out.putAsset("lostcities/parts/" + partId + ".json",
                            partJson(palette, paletteId, data, b, ix, iz, padX, padZ, startY, FLOOR_HEIGHT));
                    out.partCount++;
                    floorParts.add(partId);
                }
                // 顶层: 高度不限, 不足则补空气
                String topId = cellId + "_top";
                int topStart = b[1] + floors * FLOOR_HEIGHT;
                int topSlices = Math.max(1, topHeight);
                out.putAsset("lostcities/parts/" + topId + ".json",
                        partJson(palette, paletteId, data, b, ix, iz, padX, padZ, topStart, topSlices));
                out.partCount++;

                out.putAsset("lostcities/buildings/" + cellId + ".json",
                        buildingJson(paletteId, fillerChar, floors, floorParts, topId));
                cellBuildingIds[ix][iz] = cellId;
            }
        }

        // ---- 4. 多格建筑 ----
        boolean outer = isOuter(configDir, file);
        // 子分区只有内城参与(连续分区目前只切内城); 外城暂不再细分
        String subZone = outer ? null : subZoneOf(configDir, file);
        String layoutStyle = outer ? "citystyle_border" : subZone == null ? "citystyle_common"
                : CityStylePatcher.ZONE_STYLE_PREFIX + shortHash(subZone);
        out.footprints.add(new com.caf.lccustomcity.config.LayoutCatalog.Building(
                NS + ":" + id, layoutStyle, subZone, outer, cellsX, cellsZ));

        // ---- 4.5 数量上下限登记(阶段 3 Mixin 用) ----
        // 键必须是 Mixin 运行时能拿到的两样东西: 城市风格名 + 建筑资产 id。
        // 风格名规则与 CityStylePatcher / CityStyleZoneMixin 严格对齐:
        //   外城 -> citystyle_border; 内城有子分区 -> caf_zone_inner_<子分区名短哈希>; 其余 -> citystyle_common
        // 注意: 这里只查配置、先不登记 —— 登记在下面的 单格/多格 分流里进行,
        // 多格建筑进 countLimitsMulti, 单格进 countLimitsSingle, 两张表绝不混装
        // (混装会让单格选择路径强制返回多格 id → 失落城市查 buildings 注册表拿到 null → 崩)。
        ZoneConfig.CountRange count = zoneCfg.countFor(zoneOf(configDir, file), subZoneOf(configDir, file),
                file.getFileName().toString());
        String limitStyleName = count == null ? null : (outer ? "citystyle_border"
                : (subZone != null ? CityStylePatcher.ZONE_STYLE_PREFIX + shortHash(subZone) : "citystyle_common"));
        if (cellsX > 1 || cellsZ > 1) {
            JsonArray rows = new JsonArray();
            for (int ix = 0; ix < cellsX; ix++) {
                JsonArray row = new JsonArray();
                for (int iz = 0; iz < cellsZ; iz++) {
                    row.add(NS + ":" + cellBuildingIds[ix][iz]);
                }
                rows.add(row);
            }
            JsonObject mb = new JsonObject();
            mb.addProperty("dimx", cellsX);
            mb.addProperty("dimz", cellsZ);
            mb.add("buildings", rows);
            out.putAsset("lostcities/multibuildings/" + id + ".json", mb);
            // Rotated multi assets only reorder references; floor slices and palettes remain shared.
            for(int turn=1;turn<4;turn++){
                int rw=(turn&1)==0?cellsX:cellsZ,rd=(turn&1)==0?cellsZ:cellsX;
                String[][] rotated=new String[rw][rd];
                for(int ix=0;ix<cellsX;ix++)for(int iz=0;iz<cellsZ;iz++)
                    rotated[com.caf.lccustomcity.config.BuildingRotation.x(ix,iz,cellsX,cellsZ,turn)]
                           [com.caf.lccustomcity.config.BuildingRotation.z(ix,iz,cellsX,cellsZ,turn)]=cellBuildingIds[ix][iz];
                JsonArray rotatedRows=new JsonArray();
                for(var column:rotated){JsonArray row=new JsonArray();for(String cell:column)row.add(NS+":"+cell);rotatedRows.add(row);}
                JsonObject variant=new JsonObject();variant.addProperty("dimx",rw);variant.addProperty("dimz",rd);
                variant.add("buildings",rotatedRows);out.putAsset("lostcities/multibuildings/"+id+"_r"+turn+".json",variant);
            }
            (outer ? out.outerMultiIds : out.multiIds).add(id);
            if (subZone != null) {
                out.innerSubMultiIds.computeIfAbsent(subZone, k -> new ArrayList<>()).add(id);
            }
            if (limitStyleName != null) {
                out.countLimitsMulti.computeIfAbsent(limitStyleName, k -> new LinkedHashMap<>())
                        .put(NS + ":" + id, new BuildingCountLimits.Limit(count.min(), count.max()));
            }
        } else {
            (outer ? out.outerBuildingIds : out.buildingIds).add(id);
            if (subZone != null) {
                out.innerSubBuildingIds.computeIfAbsent(subZone, k -> new ArrayList<>()).add(id);
            }
            if (limitStyleName != null) {
                out.countLimitsSingle.computeIfAbsent(limitStyleName, k -> new LinkedHashMap<>())
                        .put(NS + ":" + id, new BuildingCountLimits.Limit(count.min(), count.max()));
            }
        }

        log.info("[CAF-LC] {} -> {}x{} 格, 高度 {} ({} 层 + 顶 {}), 调色板 {} 项",
                file.getFileName(), cellsX, cellsZ, fy, floors, Math.max(1, topHeight), palette.entries().size());
    }

    // ------------------------------------------------------------------
    // 高度切片规则
    // ------------------------------------------------------------------

    /**
     * 决定"层数"(非顶层 part 的个数)。
     *
     * <p>失落城市要求至少 1 层, 所以 6 格及以下的矮建筑无法用 floors=0 表示
     * (floors=0 会让 {@code getTopLevel()} 等于地面高度, 影响废墟计算和上方清理范围),
     * 这种情况就在顶层 part 里补空气。
     */
    static int floorsFor(int height) {
        int floors = Math.max(1, (int) Math.round(height / (double) FLOOR_HEIGHT));
        while (floors > 1 && height - FLOOR_HEIGHT * floors < 1) {
            floors--;
        }
        return floors;
    }

    // ------------------------------------------------------------------
    // JSON 构造
    // ------------------------------------------------------------------

    /**
     * 生成调色板 JSON。返回「战利品与自带内容冲突」的字符数(给日志用)。
     *
     * <h2>战利品 vs 建筑自带内容(tag)</h2>
     * 失落城市的生成顺序是 {@code loot > mob > tag}({@code LostCityTerrainFeature.java:1816-1822})。
     * 所以一个字符不能既挂 {@code loot} 又带 {@code tag} —— 谁在前面谁生效, 另一个等于白写。
     * 由 {@link BlockEntityContentConfig} 决定谁赢:
     * <ul>
     *   <li>开(默认): 有自带内容的字符<b>不写 loot</b>, 箱子里的东西就是作者放的;</li>
     *   <li>关: 挂 loot 的字符<b>不写 tag</b>(保持改动前的行为: 容器内容由战利品池决定)。</li>
     * </ul>
     * 只有"本来会挂 loot 的容器"才算冲突; 没有战利品池的建筑里, 箱子照样带自带内容。
     */
    private static JsonObject paletteJson(CharPalette palette, String lootCondition, Output out, Path file) {
        boolean beWins = BlockEntityContentConfig.enabled();
        int conflicts = 0;
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (CharPalette.Entry e : palette.entries()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("char", e.chr());
            entry.addProperty("block", e.state());
            boolean hasTag = e.tag() != null && !e.tag().isEmpty();
            // 容器挂战利品条件: 保留原 blockstate(朝向等), 只多加一个 loot 字段。
            // loot 必须写全命名空间 —— 不带命名空间失落城市会补 lostcities: 前缀(DataTools.parse),
            // 我们的条件在 caf_lc_customcity 命名空间下, 不带前缀就找不到了。
            boolean wantsLoot = lootCondition != null && LootSupport.isContainer(e.state());
            if (hasTag && wantsLoot) {
                conflicts++;
                if (beWins) {
                    wantsLoot = false;      // 自带内容优先: 这个字符不挂 loot
                } else {
                    hasTag = false;         // 战利品优先: 丢掉建筑自带的内容
                }
            }
            if (wantsLoot) {
                entry.addProperty("loot", lootCondition);
            }
            if (hasTag) {
                // tag = 方块实体数据(箱子里的东西、Create 伪装方块的外观、告示牌文字...)。
                // 失落城市拿到之后自己会补 x/y/z 和 id (LostCityTerrainFeature.java:1877-1881),
                // 所以这里已经把坐标和 id 剥掉了(见 BlockEntityNbt.forPalette)。
                entry.add("tag", BlockEntityNbt.toJson(e.tag()));
            }
            arr.add(entry);
        }
        root.add("palette", arr);
        if (conflicts > 0) {
            out.warnings.add(file.getFileName() + ": 有 " + conflicts
                    + " 个调色板字符同时被「战利品条件」和「建筑自带的容器内容」占用, 已按 "
                    + (beWins ? "建筑自带内容优先(这些字符不挂战利品)" : "战利品优先(建筑自带的容器内容被丢弃)")
                    + " 处理。想换一种: 改 config\\失落城市建筑\\" + BlockEntityContentConfig.FILE_NAME
                    + " 里的 " + BlockEntityContentConfig.KEY + "。");
        }
        return root;
    }

    private static JsonObject partJson(CharPalette palette, String paletteId, SchematicData data, int[] b,
                                       int cellX, int cellZ, int padX, int padZ,
                                       int startY, int slices) {
        JsonObject root = new JsonObject();
        root.addProperty("xsize", CELL);
        root.addProperty("zsize", CELL);
        root.addProperty("refpalette", NS + ":" + paletteId);

        JsonArray sliceArr = new JsonArray();
        for (int sy = 0; sy < slices; sy++) {
            JsonArray rowArr = new JsonArray();
            for (int z = 0; z < CELL; z++) {
                StringBuilder row = new StringBuilder(CELL);
                for (int x = 0; x < CELL; x++) {
                    // 网格坐标 -> 原建筑坐标(居中)
                    int gx = cellX * CELL + x - padX;
                    int gz = cellZ * CELL + z - padZ;
                    int wx = b[0] + gx;
                    int wz = b[2] + gz;
                    String state = data.blockAt(wx, startY + sy, wz);
                    // 越界(null)与空气统一用空格。
                    // ★ 必须带上该位置的方块实体数据 ★ —— 调色板分配时也是按 (状态 + 方块实体内容)
                    // 分配的, 这里如果只按状态取字符, 就会拿到"第一份内容"的那个字符
                    // (即箱子内容全变成一样的)。
                    row.append(state == null ? CharPalette.AIR_CHAR
                            : palette.charFor(state, DynamicNbt.paletteTag(data, wx, startY + sy, wz)));
                }
                rowArr.add(row.toString());
            }
            sliceArr.add(rowArr);
        }
        root.add("slices", sliceArr);
        DynamicNbt.addEntities(root, data, b[0] + cellX * CELL - padX,
                startY, b[2] + cellZ * CELL - padZ, slices);
        if(data.hasEntities())for(int turn=1;turn<4;turn++)
            DynamicNbt.addEntities(root,data,b[0]+cellX*CELL-padX,startY,b[2]+cellZ*CELL-padZ,slices,
                    b[0]-padX,b[2]-padZ,ceilDiv(b[3]-b[0]+1,CELL),ceilDiv(b[5]-b[2]+1,CELL),cellX,cellZ,turn);
        JsonArray metadata=root.has("meta")?root.getAsJsonArray("meta"):new JsonArray();
        JsonObject sourceCell=new JsonObject();sourceCell.addProperty("key",com.caf.lccustomcity.config.BuildingRotation.SOURCE_CELL);
        sourceCell.addProperty("string",cellX+","+cellZ+","+ceilDiv(b[3]-b[0]+1,CELL)+","+ceilDiv(b[5]-b[2]+1,CELL));
        metadata.add(sourceCell);root.add("meta",metadata);
        return root;
    }

    private static JsonObject buildingJson(String paletteId, String fillerChar, int floors,
                                           List<String> floorParts, String topId) {
        JsonObject root = new JsonObject();
        root.addProperty("refpalette", NS + ":" + paletteId);
        // filler/rubble 是【调色板字符】而不是方块 id —— 详见 BuildingRE: filler.charAt(0)
        root.addProperty("filler", fillerChar);
        root.addProperty("rubble", fillerChar);
        root.addProperty("mincellars", 0);
        root.addProperty("maxcellars", 0);
        root.addProperty("minfloors", floors);
        root.addProperty("maxfloors", floors);
        root.addProperty("overrideFloors", true);
        // 不要让失落城市给建筑凿门。用户导出的建筑自带门, 而 LC 的凿门是直接覆写:
        // 在格子边界(x=0 或 15)上把 z=6/z=9 两列换成 filler(建筑里最多的那种方块),
        // 把 z=7/z=8 挖成空气 —— 也就是在墙上开一个 2 宽 4 高的洞。
        // 这个标志位在 LC 里只有"是否凿门"一个作用(唯一用处是 LostCityTerrainFeature:2189),
        // 不影响连通性判定, 所以关掉它是安全的。
        root.addProperty("allowDoors", false);
        root.addProperty("allowFillers", false);

        JsonArray parts = new JsonArray();
        // ★★ 关键: 必须给每张楼层图纸写上"我是第几层" ★★
        //
        // 失落城市选图纸的机制是【条件筛选 + 随机抽】, 不是"按顺序取第 i 张":
        //   Building.getRandomPart() 会把所有"条件成立"的图纸收进一个列表, 然后随机抽一张
        //   (Building.java:151-162)
        // 而条件来自 ConditionTest, 不写条件的图纸会被解析成"永远成立"
        //   (ConditionContext.parseTest: 结尾 if (test == null) test = c -> true)
        //
        // 如果所有楼层图纸都不写条件 -> 19 张全部成立 -> 每一层都从 19 张里随机抽一张
        // -> 整栋楼错乱, 而且上层会抽到一楼那张(带草地/树)的图纸, 看起来像"楼里长草长树"。
        //
        // ConditionContext.isFloor(l) 判断的是 floor == l, 而 BuildingInfo 构造条件时传的
        // floor 就是"地上第几层"(i - cellars), 所以这里逐层写 floor=0,1,2... 正好一一对应。
        int floorNo = 0;
        for (String p : floorParts) {
            JsonObject ref = new JsonObject();
            ref.addProperty("part", NS + ":" + p);
            ref.addProperty("top", false);
            ref.addProperty("cellar", false);
            ref.addProperty("floor", floorNo);
            floorNo++;
            parts.add(ref);
        }
        // 万一某个 profile 强制生成地下室, 用全空气 part 顶掉, 避免把楼层复制到地下
        JsonObject cellar = new JsonObject();
        cellar.addProperty("part", NS + ":" + EMPTY_PART);
        cellar.addProperty("cellar", true);
        parts.add(cellar);

        JsonObject top = new JsonObject();
        top.addProperty("part", NS + ":" + topId);
        top.addProperty("top", true);
        // range 作为兜底: 万一 floors 被算成别的值, 顶层图纸仍然能覆盖最高的那些槽位,
        // 避免出现"某一层没有任何图纸匹配"-> getRandomPart 返回 null -> 载入图纸时出错。
        top.addProperty("range", floors + ",999");
        parts.add(top);

        root.add("parts", parts);
        return root;
    }

    private static void addEmptyPart(Output out) {
        JsonObject root = new JsonObject();
        root.addProperty("xsize", CELL);
        root.addProperty("zsize", CELL);
        // 自带一张只含空气的局部调色板, 这样这个 part 完全不依赖外部文件。
        // 注意这一层嵌套: BuildingPartRE 的 "palette" 字段类型是 PaletteRE,
        // 而 PaletteRE 自己的结构是 {"palette":[...]}, 所以必须是 {"palette":{"palette":[...]}}。
        // 直接写裸数组会让 codec 报 "Not a JSON object" —— 而每个建筑的 parts 都引用这个 part,
        // 所以那一处写错会导致整个模组在游戏里加载失败。
        JsonObject paletteWrapper = new JsonObject();
        paletteWrapper.add("palette", airPalette());
        root.add("palette", paletteWrapper);
        JsonArray slices = new JsonArray();
        for (int y = 0; y < FLOOR_HEIGHT; y++) {
            JsonArray slice = new JsonArray();
            for (int z = 0; z < CELL; z++) {
                slice.add(" ".repeat(CELL));
            }
            slices.add(slice);
        }
        root.add("slices", slices);
        out.putAsset("lostcities/parts/" + EMPTY_PART + ".json", root);
        out.partCount++;
    }

    /** 只含"空气"一项的调色板。 */
    private static JsonArray airPalette() {
        JsonArray arr = new JsonArray();
        JsonObject air = new JsonObject();
        air.addProperty("char", CharPalette.AIR_CHAR);
        air.addProperty("block", "minecraft:air");
        arr.add(air);
        return arr;
    }

    /**
     * 选一个"体面"的方块当 filler/rubble。
     *
     * <p>失落城市用 filler 填建筑边缘(尤其是地下那一段)和"下面什么都没有"的地方,
     * 所以它必须是建筑本身的建材。原来的实现只排除空气, 于是建筑里最常见的
     * <b>装饰方块</b>(梯子、地毯、火把)会被选中, 生成时整条边缘全是梯子地毯 —— 实测踩过。
     *
     * <p>这里按优先级挑: 无状态的实心建材 &gt; 有状态的 &gt; 任何非空气 &gt; 石头。
     */
    private static String dominantChar(SchematicData data, int[] b, CharPalette palette) {
        Map<String, Integer> plain = new HashMap<>();   // 无方括号状态, 且非装饰 —— 最像实心建材
        Map<String, Integer> stated = new HashMap<>();  // 有状态, 但非装饰
        Map<String, Integer> any = new HashMap<>();     // 兜底(整栋楼全是装饰的情况)
        for (int y = b[1]; y <= b[4]; y++) {
            for (int z = b[2]; z <= b[5]; z++) {
                for (int x = b[0]; x <= b[3]; x++) {
                    String s = data.blockAt(x, y, z);
                    if (s == null || SchematicData.isAirState(s)) {
                        continue;
                    }
                    any.merge(s, 1, Integer::sum);
                    if (isDecorative(s)) {
                        continue;
                    }
                    if (s.indexOf('[') >= 0) {
                        stated.merge(s, 1, Integer::sum);
                    } else {
                        plain.merge(s, 1, Integer::sum);
                    }
                }
            }
        }
        String best = mostCommon(plain);
        if (best == null) {
            best = mostCommon(stated);
        }
        if (best == null) {
            best = mostCommon(any);
        }
        if (best == null) {
            best = "minecraft:stone";   // 整栋全是空气: 给个中性建材, 至少不会是梯子
        }
        // 用 charForPlain: filler 会被撒在建筑边缘和地下, 那里绝不能带方块实体数据
        return palette.charForPlain(best);
    }

    /** 取出现次数最多的键; 次数相同时按字典序, 保证每次转换结果一致。 */
    private static String mostCommon(Map<String, Integer> counts) {
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue();
            if (n > bestN || (n == bestN && best != null && e.getKey().compareTo(best) < 0)) {
                bestN = n;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * 装饰性 / 非实心方块的粗筛。
     *
     * <p>刻意用<b>方块 id 名字</b>判断而不是查询游戏注册表 —— 本模组在编译期看不到
     * Minecraft 的类, 运行期也不该为了一个 filler 去碰注册表。名字里带这些片段的
     * 基本都不是"当墙用的方块"。
     */
    private static boolean isDecorative(String state) {
        String id = state;
        int br = id.indexOf('[');
        if (br >= 0) {
            id = id.substring(0, br);
        }
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        for (String bad : DECORATIVE) {
            if (id.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    /** 名字里含这些片段的一律不当 filler。 */
    private static final String[] DECORATIVE = {
            "ladder", "carpet", "torch", "chain", "pane", "fence", "gate", "door",
            "rail", "sign", "banner", "button", "lever", "plate",
            "pot", "sapling", "flower", "short_grass", "tall_grass", "fern", "vine",
            "lily", "mushroom", "crop", "wheat", "carrot", "potato", "beetroot",
            "stem", "kelp", "candle", "lantern", "cobweb", "tripwire", "scaffolding",
            "cake", "slab", "stairs", "wall", "bars", "head", "skull", "coral",
            "sea_pickle", "sugar_cane", "bamboo", "azalea", "roots", "sprouts", "bud"
    };

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 数据包的 pack.mcmeta 内容。48 = Minecraft 1.21.1 的数据包格式。 */
    public static final String PACK_MCMETA = """
            {
              "pack": {
                "description": "CAF失落城市自定义城市附属 - 由 config/失落城市建筑 自动生成",
                "pack_format": 48
              }
            }
            """;

    /**
     * 落盘用的临时目录名(数据包根目录下)。
     *
     * <p>名字故意<b>不叫 data</b>: 原版的数据包只认根目录下的 {@code data/} 和
     * {@code pack.mcmeta}, 所以这个中间目录不会被当成数据包内容。
     */
    private static final String STAGING_DIR = "data.new";

    /** 旧数据目录的备份名(同样故意不叫 data)。换位失败时用它把旧内容换回来。 */
    private static final String BACKUP_DIR = "data.old";

    /**
     * 落盘的串行锁。
     *
     * <p>启动线程(写 config 里的建筑包)与后台监视线程(写存档里的建筑包)本来是不同目录,
     * 但"删旧 + 换新"必须整体执行, 一旦交错就会出现半新半旧的数据包, 所以统一加锁。
     */
    private static final Object WRITE_LOCK = new Object();

    /** 清理目录失败后, 重试前等多久(毫秒)。给占用文件的进程(杀软/资源管理器/游戏)一点时间放手。 */
    private static final long CLEANUP_RETRY_DELAY_MS = 200L;

    /** 把生成结果写成真正的数据包文件夹(方便用户直接查看/排错)。 */
    public static void writeTo(Output out, Path packDir) throws IOException {
        writeTo(out, packDir, null);
    }

    /**
     * 同上, 但会把清理/换位过程中的异常情况写进日志。
     *
     * <h2>为什么不再"先删旧目录、再往里写"</h2>
     * 老写法是 {@code deleteRecursively(data)} 之后逐个写文件, 有两个坑:
     * <ol>
     *   <li>删不掉的文件被<b>静默忽略</b>, 新内容于是写在"删了一半"的旧目录上, 留下半新半旧
     *       的数据包(旧文件还在, 它引用的 part 可能已经换成别的名字/被删掉了);</li>
     *   <li>原版删除存档时是<b>一个文件一个文件删</b>的(见 {@code LevelStorageSource.deleteLevel}),
     *       我们一边删一边写就会跟它打架 —— 实测因此出现过
     *       {@code NoSuchFileException} 与 {@code DirectoryNotEmptyException}, 存档连删 5 次删不掉。</li>
     * </ol>
     * 现在改成两步: <b>先把新内容完整写进临时目录, 全部写成功了再整体换位</b>。
     * 任何一步失败都只会留下"完整的旧包"或"完整的新包", 绝不会留下半成品。
     *
     * @param log 允许为 null(离线测试里没有日志器)
     */
    public static void writeTo(Output out, Path packDir, Logger log) throws IOException {
        synchronized (WRITE_LOCK) {
            // 注意: 这里不拼命名空间 —— 键已经是"从 data/ 之后算起"的完整路径,
            // 因为 citystyle 覆盖要落在 lostcities 命名空间而不是本模组命名空间。
            Files.createDirectories(packDir);
            Path dataDir = packDir.resolve("data");
            Path staging = packDir.resolve(STAGING_DIR);
            Path backup = packDir.resolve(BACKUP_DIR);

            // ---- 1. 先清掉上一次留下的中间目录 ----
            // 删不掉就整轮跳过: 旧数据包一个字节都没动 —— 宁可旧也不要残。
            if (!deleteRecursively(staging, log) || !deleteRecursively(backup, log)) {
                throw new IOException("数据包目录里的中间残留删不掉, 本次跳过重建并保留旧数据包: "
                        + (Files.exists(staging, LinkOption.NOFOLLOW_LINKS) ? staging : backup));
            }

            // ---- 2. 新内容完整写进临时目录(这一步失败时旧数据包完全没被碰过) ----
            try {
                Files.createDirectories(staging);
                for (Map.Entry<String, String> e : out.files.entrySet()) {
                    Path target = staging.resolve(e.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                // 半成品只可能在临时目录里, 删掉它, 旧包保持原样
                if (!deleteRecursively(staging, log) && log != null) {
                    log.warn("[CAF-LC] 写入失败后临时目录也没能删掉(下次写入会再试): {}", staging);
                }
                throw e;
            }

            // ---- 3. 换位: data -> data.old, data.new -> data ----
            // 用"改名"而不是"逐个删文件": 改名是瞬间完成的, 中途不会出现删了一半的状态。
            boolean hadOld = Files.exists(dataDir, LinkOption.NOFOLLOW_LINKS);
            if (hadOld) {
                try {
                    moveWithRetry(dataDir, backup, log);
                } catch (IOException e) {
                    deleteRecursively(staging, log);    // 新内容还没就位, 临时目录留着没用
                    throw e;                            // 旧数据包原封不动
                }
            }
            try {
                moveWithRetry(staging, dataDir, log);
            } catch (IOException e) {
                if (hadOld) {
                    try {
                        moveWithRetry(backup, dataDir, log);    // 把旧包换回来
                    } catch (IOException back) {
                        if (log != null) {
                            log.warn("[CAF-LC] 换位失败后旧目录也没能换回原位, 旧内容仍完整地放在 {}"
                                    + " —— 手动把它改名成 data 即可: {}", backup, back.toString());
                        }
                    }
                }
                deleteRecursively(staging, log);
                throw e;
            }

            // ---- 4. pack.mcmeta(和数据目录同级, 内容固定, 直接覆盖) ----
            Files.writeString(packDir.resolve("pack.mcmeta"), PACK_MCMETA, StandardCharsets.UTF_8);

            // ---- 5. 备份只是"以防万一": 删不掉也不影响数据包(它不叫 data, 不会被加载) ----
            if (hadOld && !deleteRecursively(backup, log) && log != null) {
                log.warn("[CAF-LC] 旧数据目录没能删掉, 已废弃不再使用(不影响数据包): {}", backup);
            }
        }
    }

    /**
     * 给目录改名("换位")。
     *
     * <p>{@link NoSuchFileException} / {@link DirectoryNotEmptyException} 这类异常在 Windows 上
     * 基本都来自<b>文件系统竞争</b>(别的进程正在删/写同一个目录, 最典型的就是原版正在删存档),
     * 不是逻辑错误, 所以这里统一重试一次再放弃。
     */
    private static void moveWithRetry(Path from, Path to, Logger log) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Files.move(from, to);
                return;
            } catch (IOException e) {
                last = e;
                if (log != null) {
                    log.warn("[CAF-LC] 目录换位第 {} 次失败({}), 稍后重试: {} -> {}",
                            attempt, e.getClass().getSimpleName(), from, to);
                }
                sleepBeforeCleanupRetry();
            }
        }
        throw new IOException("目录换位失败(可能被别的进程占用, 或正在被删除): " + from + " -> " + to, last);
    }

    /**
     * 递归删掉一个目录: 先删里面的文件与子目录, 最后删目录本身。
     *
     * <p><b>绝不抛异常, 也绝不静默失败</b> —— 返回"这个目录是不是真的没了"。
     * {@link NoSuchFileException}(别人已经删掉了)和 {@link DirectoryNotEmptyException}
     * (我们遍历之后又有别的进程往里写了东西)这类文件系统竞争都不算致命错误:
     * 重试一次, 还不行就留一条<b>带路径</b>的警告, 由调用方决定是放弃还是照旧。
     */
    private static boolean deleteRecursively(Path dir, Logger log) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            List<Path> failed = new ArrayList<>();
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        deleteOne(file, failed);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 遍历到一半文件已经不存在(竞争)也会走这里, 记下来重试即可
                        failed.add(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                        if (exc != null) {
                            failed.add(d);
                        }
                        deleteOne(d, failed);       // 子文件都删完了才轮到目录自己
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                failed.add(dir);
                if (log != null) {
                    log.warn("[CAF-LC] 遍历待删目录失败({}): {}", e.getClass().getSimpleName(), dir);
                }
            }
            if (failed.isEmpty() && !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (log != null) {
                log.warn("[CAF-LC] 第 {} 次清理没删干净: 剩 {} 项, 例如 {} (目录: {})",
                        attempt, failed.size(), describeFirst(failed), dir);
            }
            sleepBeforeCleanupRetry();
        }
        boolean gone = !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        if (!gone && log != null) {
            log.warn("[CAF-LC] 这个目录仍然删不掉, 已放弃清理(不崩游戏, 但旧文件会留着): {}", dir);
        }
        return gone;
    }

    /** 删一个文件/空目录; 删不掉就记进 {@code failed}, 由外层统一汇报。 */
    private static void deleteOne(Path p, List<Path> failed) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            failed.add(p);
        }
    }

    /** 日志里最多列 5 项, 免得一个上万文件的目录把日志刷爆。 */
    private static String describeFirst(List<Path> failed) {
        if (failed.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(5, failed.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(failed.get(i).getFileName());
        }
        if (failed.size() > n) {
            sb.append(" | ...");
        }
        return sb.toString();
    }

    /** 重试前短暂等待。被打断(游戏退出)时把中断标记还回去, 由上层决定停不停。 */
    private static void sleepBeforeCleanupRetry() {
        try {
            Thread.sleep(CLEANUP_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
