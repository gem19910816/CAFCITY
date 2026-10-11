package com.caf.lccustomcity;

import com.caf.lccustomcity.config.BlockEntityContentConfig;
import com.caf.lccustomcity.config.BridgeStyleConfig;
import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.CityGenConfig;
import com.caf.lccustomcity.config.DynamicStructureConfig;
import com.caf.lccustomcity.config.ForceFlattenConfig;
import com.caf.lccustomcity.config.EmptyPlotConfig;
import com.caf.lccustomcity.config.GapConfig;
import com.caf.lccustomcity.config.HighwayStyleConfig;
import com.caf.lccustomcity.config.ScatteredMarginConfig;
import com.caf.lccustomcity.config.SpawnCityConfig;
import com.caf.lccustomcity.config.WideRoadBlockConfig;
import com.caf.lccustomcity.config.ZoneConfig;
import com.caf.lccustomcity.config.ZoneWeights;
import com.caf.lccustomcity.convert.BridgeStyleGenerator;
import com.caf.lccustomcity.convert.CityStylePatcher;
import com.caf.lccustomcity.convert.HighwayStyleGenerator;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.convert.RoadStyleGenerator;
import com.caf.lccustomcity.stats.BuildingStats;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CAF失落城市自定义城市附属 主入口。
 *
 * <p>本类刻意<b>只依赖 FML 与本模组自身的类</b>, 不引用任何 Minecraft 类。
 * 原因是 Minecraft 的类在开发环境之外没有可供编译的完整 jar(实例里的
 * {@code neoforge-*-client.jar} 只是补丁包), 保持零依赖可以让构建过程只需要
 * JDK + 实例里已有的几个 jar, 秒级完成, 不需要 Gradle 和几百 MB 的工具链。
 *
 * <p>流程(在模组构造阶段执行, 此时数据包尚未加载):
 * <ol>
 *   <li>扫描 {@code <实例>/config/失落城市建筑/} 下的整栋建筑文件</li>
 *   <li>转换成失落城市的 part / palette / building / multibuilding 资产</li>
 *   <li>读出失落城市 jar 里自带的 citystyle, 只替换建筑列表, 其余原样保留</li>
 *   <li>写出 {@code config/失落城市建筑/生成的建筑包/caf_lc_customcity/} (可直接查看)</li>
 *   <li><b>注册 {@code AddPackFindersEvent}, 把上面这个数据包在本次启动就塞进数据包栈</b>
 *       (见 {@link PackFinderBridge}; 它是运行时按类名反射的, 因为编译期看不到 Minecraft 的类)</li>
 *   <li>兜底: 同时打包成 {@code mods/CAF-LC-建筑包-<编号>-<哈希>.jar} 并声明
 *       {@code ordering="AFTER" lostcities"} —— 万一上一步失败, 下次启动也一定能生效</li>
 *   <li>再往每个存档的 {@code saves/<存档>/datapacks/} 里放一份(第三条路, 名字可在
 *       {@code 不注入的存档.txt} 里排除)</li>
 *   <li>最后起一个后台线程, 给之后新建的存档自动补上数据包</li>
 * </ol>
 *
 * <p>三条路都通到同一份内容, 所以无论数据包优先级朝哪个方向排, 赢的都是我们的数据。
 */
@Mod(LcCustomCityMod.MODID)
public class LcCustomCityMod {

    public static final String MODID = "caf_lc_customcity";
    public static final Logger LOGGER = LoggerFactory.getLogger("CAF-LC");

    public static final String BUILDING_DIR_NAME = "失落城市建筑";
    /**
     * 生成的数据包所在的<b>父</b>目录(相对 config/失落城市建筑/)。
     *
     * <p>原版的 {@code FolderRepositorySource} 会把传进去的目录下面每个子目录当成一个数据包,
     * 所以这里要留一层, 真正的包在 {@link #INNER_PACK_NAME} 里。
     * 名字刻意<b>不以点开头</b> —— 隐藏目录有被目录校验跳过的风险。
     */
    public static final String GENERATED_PACK_NAME = "生成的建筑包";

    /**
     * 真正的数据包目录名(位于 {@link #GENERATED_PACK_NAME} 下面)。
     *
     * <p>刻意用纯 ASCII: 原版 {@code FolderRepositorySource} 会拿子目录名当数据包 id,
     * 中文名虽然理论上能用, 但没必要冒这个风险。
     */
    public static final String INNER_PACK_NAME = "caf_lc_customcity";
    /** 数据包目录名, 同时用作存档里 datapacks 下的文件夹名。 */
    public static final String PACK_DIR_NAME = MODID;
    /** 记录生成内容的哈希, 用来判断是否需要重新写入。 */
    public static final String HASH_MARKER = ".caf-content-hash";

    /**
     * 自动生成的数据包模组(放在 mods 目录里)的文件名前缀。
     *
     * <p>为什么要走这条路: 反汇编确认 Minecraft 的数据包优先级是
     * {@code Pack.Position} 决定的 —— {@code BOTTOM} 插到列表最前, {@code TOP} 插到最后;
     * 而原版必须最低优先级却用 {@code BOTTOM}, 所以"列表越靠后 = 优先级越高"。
     * NeoForge 给模组数据包用的是 {@code MOD_PACK_SELECTION_CONFIG = (false, TOP, false)},
     * 即 {@code mod_data}(失落城市自己的资产就在里面)排在最后 = 最高优先级。
     * 因此<b>存档目录里的数据包盖不住失落城市</b>, 必须作为模组的一部分、
     * 并用 {@code ordering="AFTER"} 排在失落城市后面才赢。
     *
     * <p>文件名里的编号是递增的: 目录扫描按文件名排序, 编号大的排在后面 = 优先级更高,
     * 所以新生成的内容总能盖住旧内容。旧 jar 删不掉也不影响(编号更小, 优先级更低)。
     */
    public static final String DATA_JAR_PREFIX = "CAF-LC-建筑包-";

    /**
     * 本模组<b>自己</b>的 jar 文件名前缀(和 build.ps1 里打出来的
     * {@code caf_lc_customcity-1.21.1-<版本>.jar} 一致)。
     *
     * <p>启动自检靠它数"mods 里堆了几个本模组" —— 玩家换上新版却没删旧 jar 时,
     * 游戏只会加载其中一个, 表现就是"明明更新了, 行为还是旧的"。
     * 注意它和 {@link #DATA_JAR_PREFIX}(自动生成的建筑包)是两个不同的名字, 不会互相误报。
     */
    public static final String MOD_JAR_PREFIX = "caf_lc_customcity-";

    /** 失落城市的一个格子 = 一个区块 = 16 格方块(和 LcAssetGenerator.CELL 一致, 只用于日志文字)。 */
    private static final int BLOCKS_PER_CELL = 16;

    /** 不往里面注入数据包的存档名单(每行一个存档文件夹名)。 */
    public static final String IGNORE_LIST_FILE = "不注入的存档.txt";

    /** 默认写进名单里的存档: 用来提取建筑的源地图, 保持它干净。 */
    public static final String DEFAULT_IGNORED_WORLD = "MC Zombie Apocalypse - Update #17.1";

    /** 后台补数据包的间隔(毫秒)。只做目录列举, 开销可忽略。 */
    private static final long WATCH_INTERVAL_MS = 1000L;

    public LcCustomCityMod(IEventBus modEventBus, ModContainer modContainer) {
        try {
            startup(modEventBus, FMLPaths.GAMEDIR.get());

            // 强制覆盖所有配置档的 废墟/爆炸/高度 设置。
            // 必须挂在 FMLLoadCompleteEvent 上, 时机才对:
            //   - 挂早了不行: 失落城市是在 preInit 的 ProfileSetup.setupProfiles() 里
            //     才把配置档填进 STANDARD_PROFILES, 挂早了这个表还是空的。
            //   - 挂晚了不行: 创建世界的界面和读存档都会去查这个表。
            // 这个事件正好卡在两者之间。
            modEventBus.addListener(FMLLoadCompleteEvent.class, e -> forceProfileSettings());
        } catch (Throwable t) {
            LOGGER.error("[CAF-LC] 启动流程中途出错, 请把下面这段报错发给作者。"
                    + "注意: 出错之前已经完成的步骤(生成建筑包、覆盖城市风格)仍然是生效的。", t);
        }
    }

    /**
     * 整个启动流程: 扫描建筑 → 转换 → 覆盖城市风格 → 落盘 → 注册数据包 → 打包 → 注入存档 → 起监视线程。
     *
     * <p>刻意做成 <b>public static 且把游戏目录当参数传入</b>: 这样它能在<b>不启动游戏</b>的情况下
     * 被离线冒烟测试完整跑一遍(见 {@code tools/smoke/StartupSmokeTest.java})。否则这一段代码
     * 只有在玩家进游戏时才会被执行, 出了任何问题都只能等玩家反馈。
     */
    public static void startup(IEventBus modEventBus, Path gameDir) throws IOException {
        Path configDir = gameDir.resolve("config").resolve(BUILDING_DIR_NAME);

        // ---- 0. 启动自检 ----
        // 放在最前面: 玩家排查"我的建筑为什么没生效"时, 日志头几行就该看到关键前提
        // (支持的扫描深度/格式/尺寸上限)和 mods 里的重复/旧版 jar。
        // 两个方法内部都自带 try/catch, 而且拿不到 mods 目录时直接跳过 ——
        // 离线测试(FMLPaths 不可用)绝不会因为这段自检失败。
        logScanSelfCheck();
        checkDuplicateModJars(gameDir);

        Files.createDirectories(configDir);
        writeReadme(configDir);
        writeIgnoreListIfMissing(configDir);
        installFlatCityProfile(gameDir);

        // ---- 1. 转换建筑 ----
        LcAssetGenerator.Output out = LcAssetGenerator.generate(configDir, LOGGER);
        out.warnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        out.errors.forEach(e -> LOGGER.error("[CAF-LC] {}", e));

        // 建筑数量上下限(阶段 3): 装进运行时查询表, Mixin 选建筑时按城市风格名查。
        // 单格/多格分两张表装(混装会让单格路径强制返回多格 id → 区块生成崩)。
        // 即使下面 isEmpty() 提前返回也要先装 —— 没建筑时装进空表, Mixin 自然不生效。
        BuildingCountLimits.install(out.countLimitsSingle, out.countLimitsMulti);
        if (BuildingCountLimits.totalConfigured() > 0) {
            LOGGER.info("[CAF-LC] 建筑数量上下限: 共 {} 栋建筑配了数量区间(每个配额区域约一座城大小内生效)",
                    BuildingCountLimits.totalConfigured());
        }

        // 内城子分区占比(阶段 2b): 装进运行时查询表, CityStyleZoneMixin 分区时按城市风格名查。
        // 即使下面 isEmpty() 提前返回也要先装 —— 没建筑时装进空表, 分区自然退化为均分。
        ZoneWeights.install(out.zoneWeights);
        com.caf.lccustomcity.config.LayoutCatalog.install(out.footprints, out.zoneWeights, out.outerShare);
        if (ZoneWeights.configured() > 0) {
            LOGGER.info("[CAF-LC] 内城子分区占比: {} 个子分区配了占比(相对权重, 没配的按均分)",
                    ZoneWeights.configured());
        }

        // 建筑生成次数统计(玩家: "我想看看是不是所有建筑都是正常生成的"):
        // 用这份转换结果登记"全部建筑 = 0 次", 之后 Mixin 每选中一栋就 +1,
        // 统计时还是 0 的就是"从未生成过"。也必须挂在 isEmpty() 提前返回之前 ——
        // 没建筑时报告就是空的, 但文件路径/关闭钩子照样要装好。
        // 用已有的 Output 分组, 不重新扫目录(见 BuildingStats.install)。
        BuildingStats.install(configDir, out);

        // 大楼间距开关(多格建筑之间强制留一格空隙): 装进运行时查询表,
        // MultiChunkGapMixin 放置多格建筑时读。即使下面 isEmpty() 提前返回也要先读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> gapWarnings = new ArrayList<>();
        boolean gapEnabled = GapConfig.load(configDir, gapWarnings);
        gapWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 大楼之间强制留一格空隙: {}", gapEnabled ? "开(多格建筑互不相邻)" : "关(允许相邻)");

        // 出生点附近城市(保证出生点 N 格以内有一个"城心", 默认 850 格):
        // 装进运行时取值, SpawnCityMixin 叠加城市浓度时读。
        // 玩家要求: "将之前的 200 格看到城市边缘改为在出生点半径 850 格刷新一个城心"
        // —— 口径是**城心距离**(不是边缘距离): 出生点 850 格以内已经有城心就什么都不做
        // (绝不搬走已有的城), 否则补一座城, 让它的城心落在距出生点约 850 格处。
        // 半径 800 的城看得见的城区边缘大约在 850 - 800*0.8 = 210 格外(给玩家参考, 不是保证)。
        // 必须在下面 isEmpty() 提前返回之前读 —— 读配置不依赖有没有建筑文件。
        List<String> spawnCityWarnings = new ArrayList<>();
        int spawnCityDistance = SpawnCityConfig.load(configDir, spawnCityWarnings);
        spawnCityWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        if (spawnCityDistance > 0) {
            LOGGER.info("[CAF-LC] 出生点附近城市已生效: 出生点 {} 格以内保证有一座城的城心"
                    + "(口径是城心距离, 不是看得见的边缘距离)", spawnCityDistance);
        } else {
            LOGGER.info("[CAF-LC] 出生点附近城市: 关(完全恢复失落城市原生行为)");
        }

        // 高速路风格开关(把失落城市高速路的路面换成我们的路面方块):
        // 转换数据包时 HighwayStyleGenerator 读。必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> highwayWarnings = new ArrayList<>();
        boolean highwayStyle = HighwayStyleConfig.load(configDir, highwayWarnings);
        highwayWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 高速路使用城市空地铺面: {}", highwayStyle);

        // 桥梁风格开关(把跨水街道桥的桥面/分隔线/矮墙换成我们的路面方块)。
        // 和高速路那个开关分开: 两者改的是不同的调色板、副作用也不同(见 BridgeStyleConfig 的类注释)。
        // 转换数据包时 BridgeStyleGenerator 读。必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> bridgeWarnings = new ArrayList<>();
        boolean bridgeStyle = BridgeStyleConfig.load(configDir, bridgeWarnings);
        bridgeWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] LC 8.3.9: bridge reskinning disabled.");

        // 野外建筑离城市距离(野外原版建筑只在城市 N 格以外生成): 装进运行时取值,
        // ScatteredMarginMixin 判定候选区块时读。必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> marginWarnings = new ArrayList<>();
        int scatteredMargin = ScatteredMarginConfig.load(configDir, marginWarnings);
        marginWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 野外建筑距城市中心的额外距离: {}", scatteredMargin > 0
                ? scatteredMargin + " 格以内不放野外建筑" : "关(恢复失落城市原生行为)");

        // 城市范围内一律削平(关掉失落城市的结构回避): 装进运行时开关,
        // 结构回避、城市地基与原版地表结构拦截读取。必须在下面 isEmpty() 提前返回之前读。
        List<String> flattenWarnings = new ArrayList<>();
        boolean forceFlatten = ForceFlattenConfig.load(configDir, flattenWarnings);
        flattenWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 城市范围内一律削平: {}", forceFlatten
                ? "开(城市补实地基，停止放置相交的原版地表结构)" : "关(恢复失落城市原生结构回避)");

        // 建筑不压宽路开关(多格建筑不许放进宽路带): 装进运行时开关,
        // MultiChunkGapMixin 放置多格建筑时读。必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> roadBlockWarnings = new ArrayList<>();
        boolean roadBlock = WideRoadBlockConfig.load(configDir, roadBlockWarnings);
        roadBlockWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] LC 8.3.9: no wide-road bands; building spacing is two vacant chunks.");

        // 建筑自带的箱子内容 vs 失落城市战利品池(阶段 1 新增): 转换数据包时 LcAssetGenerator 读。
        // 失落城市的生成顺序是 loot > mob > tag ⇒ 同一个字符不能既挂战利品又带建筑自带的
        // 方块实体数据, 必须让玩家选谁优先。必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> beContentWarnings = new ArrayList<>();
        boolean beContentWins = BlockEntityContentConfig.load(configDir, beContentWarnings);
        beContentWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 建筑自带的箱子内容: {}", beContentWins
                ? "优先(自带内容的容器不挂战利品池)"
                : "让位(挂战利品池的容器丢掉自带内容, 即改动前的行为)");

        // 动态结构开关(阶段 2 新增): 运行时由 impl 里的放置器读(见 DynamicStructurePlacer)。
        // 它决定"建筑自带的实体(尤其是机械动力的动态结构)要不要放进世界"。
        // 同样必须在下面 isEmpty() 提前返回之前读 —— 读配置不依赖有没有建筑文件。
        List<String> dynWarnings = new ArrayList<>();
        boolean dynEnabled = DynamicStructureConfig.load(configDir, dynWarnings);
        dynWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 建筑自带的动态结构(实体): {}", dynEnabled
                ? "开(生成建筑时把自带实体放回世界)" : "关(只放方块, 不放实体)");

        // 地皮填充开关(空地也要放建筑): 单格地皮只要不在道路/预定义街道/公路/铁路净空上,
        // 就不再被"建筑几率掷骰"挡掉, 强制放建筑(消除城里的小空地)。
        // 运行时由 FillPlotsMixin 读; 同样必须在下面 isEmpty() 提前返回之前读 ——
        // 读配置不依赖有没有建筑文件。
        List<String> fillPlotsWarnings = new ArrayList<>();
        boolean fillEmptyPlots = EmptyPlotConfig.load(configDir, fillPlotsWarnings);
        fillPlotsWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
        LOGGER.info("[CAF-LC] 空地也要放建筑: {}", fillEmptyPlots
                ? "开(单格地皮不被建筑几率掷骰挡掉, 强制放建筑)" : "关(保留失落城市原生的随机空地)");

        if (out.isEmpty()) {
            LOGGER.info("[CAF-LC] {} 里没有建筑文件, 不做任何改动(城市仍生成原版建筑)。"
                    + "把 .schem / .nbt 建筑文件放进去后重启游戏即可。", configDir);
            return;
        }

        // ---- 2. 覆盖 citystyle: 让城市只选得到我们的建筑 ----
        Path lcJar = findLostCitiesJar(gameDir);
        // Preserve the flat fallback; authored scenes only occupy existing vacant land.
        RoadStyleGenerator.generateVacantSurface(out);
        var presetWarnings=new ArrayList<String>();
        com.caf.lccustomcity.config.RoadPresetConfig.load(configDir,presetWarnings);
        presetWarnings.forEach(w->LOGGER.warn("[CAF-LC] {}",w));
        int presetParts=com.caf.lccustomcity.convert.ChaosRoadAssets.generate(out,LOGGER);
        LOGGER.info("[CAF-LC] 已加载 {} 张路面场景，现有空地预设生成: {}",presetParts,
                com.caf.lccustomcity.config.RoadPresetConfig.enabled());
        HighwayStyleGenerator.generate(lcJar,out,LOGGER);
        LOGGER.info("[CAF-LC] 建筑地皮不划分道路；空地按连接方向生成路面场景，高速使用六材料铺面。");
        List<String> patched = CityStylePatcher.patch(lcJar, out, LOGGER);
        if (lcJar == null) {
            LOGGER.error("[CAF-LC] 在 mods 目录里找不到失落城市的 jar, 无法覆盖城市风格 —— "
                    + "建筑会被转换, 但城市里仍会生成原版建筑。");
        }

        // ---- 3. 落盘到 config 目录: 父目录 + 里面的数据包 ----
        Path packRoot = configDir.resolve(GENERATED_PACK_NAME);
        Path packDir = packRoot.resolve(INNER_PACK_NAME);
        // 带上 LOGGER: 清理/换位失败时要留下带路径的警告, 方便玩家排查
        try {
            LcAssetGenerator.writeTo(out, packDir, LOGGER);
        } catch (IOException e) {
            // 写不进去(config 目录被占用 / 有删不掉的残留)不当作致命错误:
            // 旧数据包原封不动(宁可旧也不要残), 而且还有另外两条生效路径
            // (mods 里的建筑包 + 每个存档里的数据包), 所以继续往下走。
            LOGGER.warn("[CAF-LC] 写 config 里的建筑包失败, 已保留旧数据包, 继续走另外两条生效路径。"
                            + "数据包目录: {}。原因: {}", packDir, e.toString());
        }

        LOGGER.info("[CAF-LC] 转换完成: 单格建筑 {} 个, 多格建筑 {} 个, part {} 个, 调色板 {} 个, "
                        + "覆盖城市风格 {} 个",
                out.buildingIds.size(), out.multiIds.size(), out.partCount, out.paletteCount, patched.size());

        String hash = contentHash(out);

        // ---- 4. 让数据包在【本次启动】就生效 ----
        // 这一步才是目标里说的 AddPackFindersEvent 注入; 它和下面的 mods 建筑包互为兜底。
        boolean liveNow = PackFinderBridge.register(modEventBus, packRoot, LOGGER);

        // ---- 5. 兜底: 同时写一个数据包模组到 mods(即使第 4 步失败, 下次启动也一定生效) ----
        writeDataJar(gameDir, out, hash, !liveNow);

        LOGGER.info("[CAF-LC] 生效方式: {}", liveNow
                ? "AddPackFindersEvent 已注册, 本次进游戏就生效"
                : "仅靠 mods 里的建筑包, 需要再启动一次游戏才生效");

        // ---- 6. 再往存档里放一份。优先级方向有两种可能, 三条路并存就没有死角 ----
        Path savesDir = gameDir.resolve("saves");
        Set<String> ignore = loadIgnoreList(configDir);
        if (!ignore.isEmpty()) {
            LOGGER.info("[CAF-LC] 不在这些存档里注入数据包: {}", ignore);
        }
        int injected = injectIntoWorlds(savesDir, out, hash, ignore);
        if (injected > 0) {
            LOGGER.info("[CAF-LC] 已注入 {} 个现有存档: {}", injected, packsDisplayPath(savesDir));
        } else if (!Files.isDirectory(savesDir)) {
            LOGGER.info("[CAF-LC] 还没有 saves 目录。新建存档后会自动补上(后台每秒检查一次)。");
        }

        // ---- 7. 后台补数据包: 处理之后才创建的存档 ----
        startWorldWatcher(savesDir, out, hash, ignore);

        LOGGER.info("[CAF-LC] 想在游戏里找城市, 用指令 /lostcities locate <建筑id> (按 Tab 可以列出所有 id)");
    }

    /** 对生成内容做哈希, 用来判断存档里的数据包是否需要更新。 */
    public static String contentHash(LcAssetGenerator.Output out) {
        StringBuilder sb = new StringBuilder();
        out.files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('\u0000').append(e.getValue()).append('\u0001'));
        return LcAssetGenerator.shortHash(sb.toString());
    }

    // ==================================================================
    // 数据包模组: 写进 mods 目录, 靠 ordering="AFTER lostcities" 赢过原版资产
    // ==================================================================

    /** 找出 mods 目录里本模组生成的所有建筑包 jar, 按文件名排序。 */
    public static List<Path> findDataJars(Path modsDir) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return found;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, DATA_JAR_PREFIX + "*.jar")) {
            for (Path p : ds) {
                found.add(p);
            }
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 扫描 mods 目录失败: {}", e.getMessage());
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return found;
    }

    /** 从 {@code CAF-LC-建筑包-0001-1a2b3c4d.jar} 里取出编号 1; 解析不出来返回 -1。 */
    static int counterOf(String fileName) {
        String body = stripJarPrefix(fileName);
        if (body == null) {
            return -1;
        }
        int dash = body.indexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(body.substring(0, dash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从文件名里取出内容哈希; 解析不出来返回 null。 */
    static String hashOf(String fileName) {
        String body = stripJarPrefix(fileName);
        if (body == null) {
            return null;
        }
        int dash = body.indexOf('-');
        return dash < 0 ? null : body.substring(dash + 1);
    }

    private static String stripJarPrefix(String fileName) {
        if (!fileName.startsWith(DATA_JAR_PREFIX) || !fileName.endsWith(".jar")) {
            return null;
        }
        return fileName.substring(DATA_JAR_PREFIX.length(), fileName.length() - ".jar".length());
    }

    /**
     * 把生成结果打包成 {@code mods/CAF-LC-建筑包-<编号>-<哈希>.jar}。
     *
     * <p>只有当"编号最大的那个 jar"内容不是当前内容时才写新包 —— 因为编号大的
     * 排在后面、优先级更高, 所以只要最大编号的包是最新的, 就不需要再写。
     */
    public static void writeDataJar(Path gameDir, LcAssetGenerator.Output out, String hash) {
        writeDataJar(gameDir, out, hash, false);
    }

    /**
     * 同上。
     *
     * @param warnAboutRestart 当 {@code AddPackFindersEvent} 那条路没走通时为 true,
     *                         这时才提示玩家需要再启动一次
     */
    public static void writeDataJar(Path gameDir, LcAssetGenerator.Output out, String hash,
                                    boolean warnAboutRestart) {
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            LOGGER.warn("[CAF-LC] 找不到 mods 目录, 无法写建筑包");
            return;
        }

        List<Path> existing = findDataJars(modsDir);
        Path newest = existing.isEmpty() ? null : existing.get(existing.size() - 1);
        String newestHash = newest == null ? null : hashOf(newest.getFileName().toString());

        if (hash.equals(newestHash)) {
            cleanupOldDataJars(existing, newest);
            LOGGER.info("[CAF-LC] 建筑包已是最新({}), 不用重写", newest.getFileName());
            return;
        }

        int counter = (newest == null ? 0 : Math.max(counterOf(newest.getFileName().toString()), 0)) + 1;
        String tag = String.format("%04d", counter);
        String modId = "caf_lc_buildings_" + tag;
        Path target = modsDir.resolve(DATA_JAR_PREFIX + tag + "-" + hash + ".jar");
        try {
            packJar(target, out, modId, "CAF失落城市自定义城市附属 - 建筑包 " + tag);
            LOGGER.info("[CAF-LC] 已写出建筑包模组: {}", target.getFileName());
            cleanupOldDataJars(existing, target);
            if (warnAboutRestart && newest == null) {
                LOGGER.warn("[CAF-LC] 这是第一次生成建筑包, 而且 AddPackFindersEvent 没注册上, "
                        + "所以它要从【下一次启动游戏】才生效。如果这次进游戏还是原版建筑, 退出再进一次即可。");
            }
        } catch (IOException e) {
            LOGGER.error("[CAF-LC] 写建筑包模组失败: {}", e.getMessage());
        }
    }

    /** 删掉旧的建筑包(尽力而为)。删不掉是正常现象: jar 正在被游戏占用, 且它优先级更低, 无害。 */
    private static void cleanupOldDataJars(List<Path> existing, Path keep) {
        int left = 0;
        for (Path p : existing) {
            if (p.equals(keep)) {
                continue;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                left++;
            }
        }
        if (left > 0) {
            LOGGER.info("[CAF-LC] 有 {} 个旧建筑包正在被游戏占用, 删不掉。它们编号更小、优先级更低, 不影响结果;"
                    + "关掉游戏后手动删掉 mods 里多余的 {}*.jar 即可。", left, DATA_JAR_PREFIX);
        }
    }

    /** 打包: 先写临时文件再改名, 避免写出半个 jar 被下次启动读到。 */
    static void packJar(Path target, LcAssetGenerator.Output out, String modId, String displayName)
            throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {

            putText(zip, "META-INF/neoforge.mods.toml", dataModToml(modId, displayName));
            putText(zip, "pack.mcmeta", LcAssetGenerator.PACK_MCMETA);

            for (Map.Entry<String, String> e : out.files.entrySet()) {
                putText(zip, "data/" + e.getKey(), e.getValue());
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void putText(ZipOutputStream zip, String path, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * 数据包模组的元数据。
     *
     * <p>{@code ordering="AFTER"} 是关键: 它让本包在 {@code mod_data} 里排在失落城市后面,
     * 从而覆盖失落城市自带的 citystyle。{@code modLoader} 用 {@code lowcodefml},
     * 因为这里面只有数据、没有代码。
     */
    static String dataModToml(String modId, String displayName) {
        return """
                modLoader="lowcodefml"
                loaderVersion="[2,)"
                license="All Rights Reserved"

                [[mods]]
                modId="%s"
                version="1.0.0"
                displayName="%s"
                description='''
                由 CAF失落城市自定义城市附属 自动生成的数据包, 请勿手动修改。
                内容来自 config/失落城市建筑/ 下的建筑文件, 每次启动游戏时重新生成。
                '''

                [[dependencies.%s]]
                modId="neoforge"
                type="required"
                versionRange="[21.1,)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.%s]]
                modId="lostcities"
                type="required"
                versionRange="[0,)"
                ordering="AFTER"
                side="BOTH"
                """.formatted(modId, displayName, modId, modId);
    }

    // 注意: lostcities 的 versionRange 必须是 "[0,)"。
    // 失落城市的版本号字符串是 "1.21-8.4.4", 会被拆成 1,21,8,4,4 来比较,
    // 第一位 1 就小于 8, 所以任何形如 "[8.4,)" 的约束都会判定不满足,
    // 导致模组加载失败、游戏进不去。加载顺序交给 ordering="AFTER" 就够。

    /**
     * 在 mods 目录里找失落城市的 jar。
     *
     * <p>不通过 ModList API 而是直接按文件名扫, 这样可以完全不依赖加载器的类。
     */
    public static Path findLostCitiesJar(Path gameDir) {
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.contains("lostcities")) {
                    found.add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 读取 mods 目录失败: {}", e.getMessage());
            return null;
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            LOGGER.warn("[CAF-LC] mods 里找到多个失落城市 jar, 使用第一个: {}", found.get(0).getFileName());
        }
        return found.get(0);
    }

    // ==================================================================
    // 启动自检(版本 / 扫描能力 / mods 里的重复 jar)
    // ==================================================================

    /**
     * 把本模组"这次是怎么工作的"明确打出来。
     *
     * <p>为什么要打: 玩家装了<b>旧版</b>(扫描只到两层)却把 38 栋建筑放在第 3 层时,
     * 结果是"扫描不到任何建筑 ⇒ 整个生成流程被跳过 ⇒ 游戏里看到的还是旧建筑包",
     * 而日志里<b>一句话都没有</b>, 排查花了很久。所以"支持的扫描深度 / 格式 / 尺寸上限"
     * 这类关键前提必须在启动时就摆在日志里。
     *
     * <p>版本号不在这里重复打: FML 启动时会自己列出 {@code CAF Lost Cities Custom City Addon 1.0.xx}。
     */
    private static void logScanSelfCheck() {
        LOGGER.info("[CAF-LC] 建筑扫描: 不限深度(只剪掉 {} 整棵子树); 支持 .schem/.nbt; "
                        + "占地上限 {}x{} 格({}x{} 方块); 扫描详情每次都写 {}",
                GENERATED_PACK_NAME,
                LcAssetGenerator.MAX_CELLS, LcAssetGenerator.MAX_CELLS,
                BLOCKS_PER_CELL * LcAssetGenerator.MAX_CELLS, BLOCKS_PER_CELL * LcAssetGenerator.MAX_CELLS,
                LcAssetGenerator.SCAN_REPORT_FILE_NAME);
    }

    /**
     * 检查 mods 目录里有没有堆着<b>多个本模组的 jar</b>(以及多个建筑包), 发现旧版本时点名。
     *
     * <p>为什么要查: 玩家"明明换了新版本, 行为还是旧的"十有八九是旧 jar 没删干净 ——
     * 游戏只会加载其中一个(通常是按文件名排序找到的第一个)。
     *
     * <p>怎么拿目录: 和 {@link #findLostCitiesJar} 一样<b>直接从 gameDir 拼 mods</b>、
     * 按文件名扫, 完全不碰 {@code FMLPaths} / ModList API。拿不到 mods 目录(离线测试、
     * 非常规部署)时<b>直接返回</b>, 整个自检包在 try/catch 里 —— 绝不能因为它让启动或
     * 离线测试失败。
     *
     * <p>本模组自动生成的 {@link #DATA_JAR_PREFIX}{@code *.jar} 名字不同, 不会被误报成
     * "本模组的重复版本"。
     */
    private static void checkDuplicateModJars(Path gameDir) {
        Path modsDir = gameDir == null ? null : gameDir.resolve("mods");
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return;   // 拿不到 mods 目录: 静默跳过(FMLPaths 在离线场合本来也不可用)
        }
        try {
            List<Path> modJars = listJars(modsDir, MOD_JAR_PREFIX);
            List<Path> packJars = listJars(modsDir, DATA_JAR_PREFIX);
            String mine = ownVersion();
            String runningJar = runningJarName();
            if (mine != null && runningJar != null) {
                LOGGER.info("[CAF-LC] 本次运行的模组文件: {} (版本 {})", runningJar, mine);
            }
            if (modJars.size() > 1) {
                LOGGER.warn("[CAF-LC] mods 里有 {} 个本模组的 jar: {} —— 游戏只会加载其中一个"
                                + "(通常是找到的第一个), 请删掉多余的。",
                        modJars.size(), fileNames(modJars));
            }
            List<String> older = new ArrayList<>();
            if (mine != null) {
                for (Path p : modJars) {
                    String v = versionOfJarName(p.getFileName().toString());
                    Integer cmp = compareVersions(v, mine);
                    if (cmp != null && cmp < 0) {
                        older.add(p.getFileName() + "(版本 " + v + ")");
                    }
                }
            }
            if (!older.isEmpty()) {
                LOGGER.warn("[CAF-LC] 检测到旧版本 {}, 当前运行的是版本 {} —— 建议删除(旧 jar 不会生效, "
                        + "留着只会让排查变难)。", older, mine);
            }
            if (packJars.size() > 1) {
                LOGGER.warn("[CAF-LC] mods 里有 {} 个自动生成的建筑包: {} —— 游戏只会加载其中一个"
                                + "(编号最大的那个生效), 请删掉多余的(本模组也会尽力自动清理)。",
                        packJars.size(), fileNames(packJars));
            }
        } catch (Throwable t) {
            // 自检本身绝不能影响启动
            LOGGER.warn("[CAF-LC] 检查 mods 目录里的重复 jar 失败(已忽略, 不影响游戏): {}", t.toString());
        }
    }

    /**
     * 本模组自己的版本号(形如 {@code 1.0.42}); 认不出来返回 {@code null}。
     *
     * <p>取法: 正在运行的类是从哪个 jar 载入的 → 从那个 jar 的<b>文件名</b>里抠版本号
     * (build.ps1 打出来的是 {@code caf_lc_customcity-1.21.1-<版本>.jar})。
     * 完全不依赖 FML / ModList 的 API; 开发环境和离线测试里载入位置是<b>目录</b>而不是 jar,
     * 于是返回 null, 调用方自动跳过版本比较(只保留"重复 jar"那部分检查)。
     */
    public static String ownVersion() {
        String jar = runningJarName();
        return jar == null ? null : versionOfJarName(jar);
    }

    /** 正在运行的本模组 jar 文件名; 开发环境/离线测试(载入位置是目录)里返回 null。 */
    private static String runningJarName() {
        try {
            URL location = LcCustomCityMod.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path p = Path.of(location.toURI());
            return Files.isRegularFile(p) ? p.getFileName().toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 {@code caf_lc_customcity-1.21.1-1.0.42.jar} 里取出 {@code 1.0.42}; 认不出返回 null。
     *
     * <p>public + 纯函数, 离线测试可以直接盯着它。
     */
    public static String versionOfJarName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(MOD_JAR_PREFIX) || !lower.endsWith(".jar")) {
            return null;
        }
        String body = fileName.substring(MOD_JAR_PREFIX.length(), fileName.length() - ".jar".length());
        int dash = body.lastIndexOf('-');
        if (dash < 0 || dash == body.length() - 1) {
            return null;
        }
        return body.substring(dash + 1);
    }

    /**
     * 比较两个版本号(按点分段、逐段比数字)。
     *
     * @return a &lt; b 返回负数, 相等返回 0, 任何一段不是数字则返回 {@code null}(=比不出来)
     */
    public static Integer compareVersions(String a, String b) {
        int[] x = parseVersion(a);
        int[] y = parseVersion(b);
        if (x == null || y == null) {
            return null;
        }
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    /** {@code "1.0.42"} → {@code [1, 0, 42]}; 空串/非数字段返回 null。 */
    private static int[] parseVersion(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        String[] parts = v.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] < 0) {
                return null;
            }
        }
        return out;
    }

    /** mods 里文件名以 prefix 开头的 jar(按文件名排序, 顺序稳定)。 */
    private static List<Path> listJars(Path modsDir, String prefix) throws IOException {
        List<Path> found = new ArrayList<>();
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    found.add(p);
                }
            }
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return found;
    }

    /** 文件名列表(日志里一行打完)。 */
    private static String fileNames(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path p : jars) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.getFileName());
        }
        return sb.toString();
    }

    /**
     * 给日志用的"数据包会被放到哪"提示串。
     *
     * <p>中间那层是字面量 {@code <存档>}, 表示"每个存档各一份"。它含 Windows 路径非法字符
     * {@code < >}, 所以<b>这里刻意返回 String 而不是 Path</b> —— 用 {@code Path.resolve}
     * 拼会直接抛 {@code InvalidPathException}, 把后面的启动步骤全带崩。
     */
    private static String packsDisplayPath(Path savesDir) {
        String sep = java.io.File.separator;
        return savesDir + sep + "<存档>" + sep + "datapacks" + sep + PACK_DIR_NAME;
    }

    /**
     * 把数据包写进每个存档的 {@code datapacks/} 目录。
     *
     * @return 本次真正写入(内容有变化)的存档数量
     */
    public static int injectIntoWorlds(Path savesDir, LcAssetGenerator.Output out, String hash) {
        return injectIntoWorlds(savesDir, out, hash, Set.of());
    }

    /**
     * 同上, 但会跳过 {@code ignore} 名单里的存档(名字按小写比较)。
     *
     * <p>名单的用途: 拿来提取建筑的源地图不想被改动, 就把它写进
     * {@code config/失落城市建筑/不注入的存档.txt}。
     */
    public static int injectIntoWorlds(Path savesDir, LcAssetGenerator.Output out, String hash,
                                       Set<String> ignore) {
        if (!Files.isDirectory(savesDir)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(savesDir)) {
            for (Path world : ds) {
                if (injectOne(world, out, hash, ignore)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 读取 saves 目录失败: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 已经被我们确认"内容哈希对得上、而且包是完整的"的那些存档数据包(绝对路径)。
     *
     * <p>用途: 区分两种情况 —— "标记文件就是我们自己写的内容哈希" vs "标记文件被别的进程
     * 删掉了"。后者最典型的场景是<b>原版正在删除这个存档</b>(它就是一个文件一个文件删的,
     * 我们这边看到的是"标记先没了")。这时候再往里重建数据包, 就是和原版对着干:
     * 实测原版会连报 5 次删除失败、存档删不掉, 我们还会在死存档里留下残包。
     */
    private static final Set<String> KNOWN_GOOD_PACKS = ConcurrentHashMap.newKeySet();

    /** 数据包被判定为"可疑"(标记没了但数据目录还在)连续出现这么多轮之后, 强行重建一次。 */
    private static final int SUSPECT_POLLS_BEFORE_FORCE = 15;

    /** 每个存档连续被判定为"可疑"的轮数。 */
    private static final Map<String, Integer> SUSPECT_POLLS = new ConcurrentHashMap<>();

    /** 已经警告过的数据包(后台线程每秒跑一次, 同一个问题只提醒一次, 免得刷屏)。 */
    private static final Set<String> WARNED_PACKS = ConcurrentHashMap.newKeySet();

    /** @return 是否真的写入了(内容有变化或首次写入) */
    private static boolean injectOne(Path world, LcAssetGenerator.Output out, String hash, Set<String> ignore) {
        // 只处理真正的存档目录
        if (!Files.isDirectory(world) || !Files.exists(world.resolve("level.dat"))) {
            return false;
        }
        if (!ignore.isEmpty()
                && ignore.contains(world.getFileName().toString().toLowerCase(Locale.ROOT))) {
            return false;
        }
        Path packDir = world.resolve("datapacks").resolve(PACK_DIR_NAME);
        Path marker = packDir.resolve(HASH_MARKER);
        Path dataDir = packDir.resolve("data");
        String key = packDir.toAbsolutePath().toString();
        try {
            boolean markerThere = Files.exists(marker);
            // 哈希对得上<b>而且包看着完整</b>才跳过。必须连 data 目录和 pack.mcmeta 一起看:
            // 老版本可能在数据包被删了一半的时候就把哈希标记写上了, 只看标记会把残包当成"已写好"。
            if (markerThere
                    && hash.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())
                    && Files.isDirectory(dataDir)
                    && Files.exists(packDir.resolve("pack.mcmeta"))) {
                KNOWN_GOOD_PACKS.add(key);
                SUSPECT_POLLS.remove(key);
                WARNED_PACKS.remove(key);
                return false;   // 内容没变, 不用重写
            }
            // 包原来是好的, 现在哈希标记却不见了 —— 十有八九是原版正在删这个存档
            // (它就是一个文件一个文件删的, 我们这边看到的就是"标记先没了")。
            // 这一轮绝不重建: 我们一边写、它一边删, 结果就是它删不掉、我们留下残包。
            if (KNOWN_GOOD_PACKS.contains(key) && !markerThere) {
                int polls = SUSPECT_POLLS.merge(key, 1, Integer::sum);
                if (polls <= SUSPECT_POLLS_BEFORE_FORCE) {
                    if (WARNED_PACKS.add(key)) {
                        LOGGER.warn("[CAF-LC] 存档 {} 的数据包好像正在被删除(哈希标记 {} 不见了), "
                                        + "本轮跳过重建, 免得和删除流程互相打架;"
                                        + "连续 {} 轮都是这样就会强行重建一次。",
                                world.getFileName(), marker, SUSPECT_POLLS_BEFORE_FORCE);
                    }
                    return false;
                }
                LOGGER.warn("[CAF-LC] 存档 {} 连续 {} 轮都是\"标记没了但数据目录还在\", 这次强行重建一遍数据包: {}",
                        world.getFileName(), polls, packDir);
                SUSPECT_POLLS.remove(key);      // 万一这次还失败, 再等一轮计数
            }
            LcAssetGenerator.writeTo(out, packDir, LOGGER);
            Files.writeString(marker, hash, StandardCharsets.UTF_8);
            KNOWN_GOOD_PACKS.add(key);
            SUSPECT_POLLS.remove(key);
            WARNED_PACKS.remove(key);
            LOGGER.info("[CAF-LC] 已注入存档: {}", world.getFileName());
            return true;
        } catch (IOException e) {
            // 写失败(残留删不掉 / 换位失败 / 存档正好在被删)不是致命错误: 不写哈希标记,
            // 下一轮会重来; 旧数据包保持原样 —— 宁可旧也不要残。
            if (WARNED_PACKS.add(key)) {
                LOGGER.warn("[CAF-LC] 注入存档 {} 失败, 本轮保留旧数据包(后台每秒会自动重试)。"
                                + "数据包目录: {}。原因: {}",
                        world.getFileName(), packDir, e.toString());
            } else {
                LOGGER.debug("[CAF-LC] 注入存档 {} 仍然失败(已警告过一次): {}", world.getFileName(), e.toString());
            }
            return false;
        }
    }

    /**
     * 后台线程: 每秒检查一次 saves 目录, 给之后新建的存档补上数据包。
     *
     * <p>因为本模组不引用 Minecraft 类, 没法监听"世界创建"事件, 所以用轮询代替。
     * 线程是守护线程, 游戏退出时自动结束。
     */
    private static void startWorldWatcher(Path savesDir, LcAssetGenerator.Output out, String hash,
                                          Set<String> ignore) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(WATCH_INTERVAL_MS);
                    injectIntoWorlds(savesDir, out, hash, ignore);
                } catch (InterruptedException ie) {
                    return;                     // 游戏退出
                } catch (Throwable e) {
                    // 绝不让后台线程把游戏搞崩
                    LOGGER.debug("[CAF-LC] 后台检查存档时出错(已忽略): {}", e.toString());
                }
            }
        }, "CAF-LC-world-watcher");
        t.setDaemon(true);
        t.start();
    }

    /** 首次运行时放一个"不注入的存档"名单文件。 */
    private static void writeIgnoreListIfMissing(Path configDir) {
        Path file = configDir.resolve(IGNORE_LIST_FILE);
        if (Files.exists(file)) {
            return;
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(IGNORE_LIST_TEXT);
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 无法写入不注入名单: {}", e.getMessage());
        }
    }

    /**
     * 读"不注入的存档"名单。
     *
     * <p>每行一个存档文件夹名; {@code #} 开头的行是注释。名字不区分大小写。
     * 文件不存在时返回空集合(那就所有存档都注入)。
     */
    public static Set<String> loadIgnoreList(Path configDir) {
        Set<String> names = new HashSet<>();
        Path file = configDir.resolve(IGNORE_LIST_FILE);
        if (!Files.isRegularFile(file)) {
            return names;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    names.add(s.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 读取不注入名单失败: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 首次运行时放一个"不注入的存档"名单。
     *
     * <p><b>这里每一行说明都必须以 {@code #} 开头</b>: 解析规则是"只有行首的 # 才是注释",
     * 没加 # 的说明行会被当成存档名。名字里出现的 # 则不影响解析 —— 见下面那条默认条目。
     */
    private static final String IGNORE_LIST_TEXT = """
            # CAF失落城市自定义城市附属 —— 不注入数据包的存档名单
            # ====================================================
            #
            # 每行写一个存档文件夹名(就是 saves 里那个文件夹的名字), 不区分大小写。
            # 只有行首的 # 才是注释 —— 所以下面那行名字中间带 # 也照样生效。
            # 写在这里的存档不会被放进 datapacks\\caf_lc_customcity\\, 其它存档不受影响。
            # 改完重启游戏生效。
            #
            # 下面这个是用来提取建筑的源地图, 默认不注入, 保持它干净:
            %s
            """.formatted(DEFAULT_IGNORED_WORLD);

    /** 城市地面统一到的高度(和内置 nodamage 档一致)。 */
    public static final int GROUNDLEVEL_FORCED = 71;

    /**
     * 要强制写进<b>每一个</b>失落城市配置档的字段与取值。
     *
     * <p>字段名必须和 {@code LostCityProfile} 里一模一样。特别注意有两个没有下划线:
     * {@code RUBBLELAYER} 和 {@code GROUNDLEVEL}。
     * 类型也要对上: float 用 Float, int 用 Integer, boolean 用 Boolean,
     * double 用 Double(CITY_CHANCE / CITY_PERLIN_* 是 double, 写成 0.01f 会抛异常)。
     * 枚举字段(LANDSCAPE_TYPE / STREET_GENERATION_MODE / HIGHWAY_GENERATION_MODE 等)
     * <b>不能</b>放进这张表 —— Field.set 传字符串会抛 IllegalArgumentException。
     *
     * <p>取值全部来自 {@code caf_flat_sea_profile.json}(基于内置 nodamage, 已验证可用)。
     * "平坦"的核心是: {@code CITY_LEVEL0_HEIGHT=384} 让任何地形高度都算 0 级
     * (见 BuildingInfo.getLevelBasedOnHeight), 地面高度 = GROUNDLEVEL + 层级*6,
     * 于是全城恒为 Y=71; 其余 LEVEL1~7 高度只是兜底, 实际永远走不到。
     * TERRAIN_FIX_* 控制城市边缘地形向城市地面过渡的随机幅度, 取 nodamage 同款值。
     */
    private static final Object[][] FORCED_PROFILE_VALUES = {
            // ---- 废墟/爆炸/装饰破坏: 全关 ----
            { "RUIN_CHANCE",           0.0f  },
            { "EXPLOSION_CHANCE",      0.0f  },
            { "MINI_EXPLOSION_CHANCE", 0.0f  },
            { "RUBBLELAYER",           false },
            { "VINE_CHANCE",           0.0f  },
            // ---- 平坦城市的核心: 锁死 0 级 + 地基 Y=71 ----
            { "CITY_LEVEL0_HEIGHT",    384   },
            { "CITY_LEVEL1_HEIGHT",    83    },
            { "CITY_LEVEL2_HEIGHT",    91    },
            { "CITY_LEVEL3_HEIGHT",    99    },
            { "CITY_LEVEL4_HEIGHT",    107   },
            { "CITY_LEVEL5_HEIGHT",    115   },
            { "CITY_LEVEL6_HEIGHT",    123   },
            { "CITY_LEVEL7_HEIGHT",    131   },
            { "GROUNDLEVEL",           GROUNDLEVEL_FORCED },
            { "USE_AVG_HEIGHTMAP",     false },  // 不用周边平均高度图, 城市层级只看本区块(反正恒 0 级, 这是双保险)
            // 地面高度超出 [CITY_MINHEIGHT, CITY_MAXHEIGHT] 的区块会被失落城市判成"非城市"
            // (City.getCityFactor 直接返回 0), 于是不铲平、不放建筑 —— 这就是"城市正中间
            // 杵着一座原版高山/河流"的原因(nodamage 默认 50~150, 一座山就把城市挖出洞)。
            // 这里放开到整个建筑限高范围: 任何地形落在城市半径内都算城市区块,
            // 一律被 doCityChunk 铲平/填平到 Y=71。
            { "CITY_MINHEIGHT",        -64   },
            { "CITY_MAXHEIGHT",        384   },
            // ---- 城市边缘地形向城市地面过渡的修正幅度(nodamage 同款) ----
            { "TERRAIN_FIX_LOWER_MIN_OFFSET", -4 },
            { "TERRAIN_FIX_LOWER_MAX_OFFSET", -3 },
            { "TERRAIN_FIX_UPPER_MIN_OFFSET", -1 },
            { "TERRAIN_FIX_UPPER_MAX_OFFSET", 1  },
            { "OCEAN_CORRECTION_BORDER",      4  },
            // ---- 城市大小/密度(nodamage 同款, 保证任何档选出来观感一致) ----
            { "CITY_CHANCE",           0.00003 },  // double! 0.003% 区块是城心: 平均城心间距 = 16/sqrt(几率) ≈ 16/0.00548 ≈ 2920 格
                                                   // (玩家要求 1500~5000 格; 几率 = 256/3000^2 ≈ 0.00003)
                                                   // ★ 必须是 double(不能写 0.00003f): 字段类型是 double, 写成 Float 会被类型检查跳过
            { "CITY_THRESHOLD",        0.2f  },
            { "CITY_MINRADIUS",        800   },  // 单位: 方块(= 50 区块); 城区配置.json 的 城市.最小半径 会盖掉它
            { "CITY_MAXRADIUS",        800   },  // 单位: 方块(= 50 区块); 城区配置.json 的 城市.最大半径 会盖掉它
                                                 // ★ 内置值必须和 ZoneConfig 默认值(50/50 区块)一致 ⇒ 半径恒 800 方块(玩家要的"半径 800 方块")。
                                                 //   两边不一致会互相矛盾: 城区配置.json 永远优先, 所以落地的是 800/800, 表里写别的就是死值+测试报红
            { "CITY_PERLIN_SCALE",     3.0   },  // double
            { "CITY_PERLIN_OFFSET",    0.1   },  // double
            { "CITY_PERLIN_INNERSCALE", 0.1  },  // double
            // ---- 建筑密度与楼层(nodamage 同款) ----
            { "BUILDING_CHANCE",           0.3f },
            { "BUILDING_MINFLOORS",        0    },
            { "BUILDING_MAXFLOORS",        8    },
            { "BUILDING_MINFLOORS_CHANCE", 4    },
            { "BUILDING_MAXFLOORS_CHANCE", 6    },
            { "BUILDING_MINCELLARS",       0    },
            { "BUILDING_MAXCELLARS",       3    },
            { "PARK_CHANCE",               0f   },  // 0 = 不再把非建筑区块变成"公园"(玩家要求删掉草地空地)
            { "CORRIDOR_CHANCE",           0.7f },
            { "BRIDGE_CHANCE",             0.7f },
            { "FOUNTAIN_CHANCE",           0.05f },
            // ---- 内外城分区: 城市因子(离市中心越近越大)低于阈值的外圈算"外城", 用 citystyle_border ----
            // 阈值 -1 = 关(全城内城)。这里强制 0.2(外城约占城市面积 36%), 让内外城之分对所有配置档生效。
            { "CITY_STYLE_THRESHOLD",  0.4f  },  // 外城阈值必须大于城市阈值(0.2), 否则外城环带宽度为 0; 城区配置.json 的 外城占比 会盖掉它
            { "CITY_STYLE_ALTERNATIVE", "citystyle_border" },
    };

    /** 内置默认值(字段名 -> 值), 是 {@link CityGenConfig} 缺失/出错时的回退来源。 */
    public static Map<String, Object> forcedProfileDefaults() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Object[] row : FORCED_PROFILE_VALUES) {
            out.put((String) row[0], row[1]);
        }
        return out;
    }

    /**
     * 由"外城占比(可见城市面积里属于外城的比例)"反解失落城市的外城阈值 CITY_STYLE_THRESHOLD。
     *
     * <p>数学关系: 城市阈值 CITY_THRESHOLD=0.2, 所以可见城市范围是 dist &lt;= 0.8r;
     * 外城是 dist &gt; (1-th)r。面积成正比于半径平方, 所以
     * 可见面积里外城占比 share = (0.64 - (1-th)^2) / 0.64, 反解得
     * th = 1 - sqrt(0.64 * (1 - share))。
     *
     * <p><b>clamp 到 [0.21, 0.95]</b>: 阈值必须<b>严格大于</b>城市阈值 0.2 ——
     * 两者相等时外城环带宽度为 0, 外城根本不会出现(这是之前"看不到外城"的根因);
     * 也不能 &gt;= 1。另外失落城市里 -1 是"关闭外城"的语义, 玩家配出极端值时宁可 clamp
     * 也绝不能算出负数传下去(share &gt; 1 时 sqrt 出 NaN, 回落到默认 0.4)。
     */
    public static float outerThresholdFromShare(double share) {
        double th = 1.0 - Math.sqrt(0.64 * (1.0 - share));
        if (Double.isNaN(th)) {
            return 0.4f;    // share > 1 等配错的情况: 用默认阈值 0.4
        }
        return (float) Math.min(0.95, Math.max(0.21, th));
    }

    /**
     * 把 城区配置.json 的 城市.最小半径/最大半径(单位: 区块)和 外城占比 折算成
     * 要强制写进失落城市配置档的三项值: CITY_MINRADIUS / CITY_MAXRADIUS(换算成方块, *16)
     * 和 CITY_STYLE_THRESHOLD(取占比区间中点, 按 {@link #outerThresholdFromShare} 反解)。
     *
     * <p>公开成 static 供离线冒烟测试直接验证换算逻辑。
     */
    public static Map<String, Object> citySizeOverrides(ZoneConfig zc) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("CITY_MINRADIUS", zc.radiusMin * 16);
        out.put("CITY_MAXRADIUS", zc.radiusMax * 16);
        double share = (zc.outerRatio.min() + zc.outerRatio.max()) / 2.0;
        out.put("CITY_STYLE_THRESHOLD", outerThresholdFromShare(share));
        return out;
    }

    /**
     * 强制覆盖所有失落城市配置档的 废墟/爆炸/高度 设置。
     *
     * <p><b>为什么需要它</b>: 这些设置原本由"创建世界时选哪个配置档"决定。玩家如果忘了选,
     * 或者选了 default / safe / largecities 之类, 建筑就会被废墟腐蚀、被爆炸炸出洞,
     * 看起来就像"方块大量丢失"。既然本模组的目标是"只生成我自己的建筑",
     * 就不该让这个选择影响结果 —— 不管选哪个档, 都由本模组统一覆盖。
     *
     * <p><b>取值</b>: 从 {@code config/失落城市建筑/城市生成配置.json} 读(见 {@link CityGenConfig}),
     * 读不到/出错就回退到 {@link #FORCED_PROFILE_VALUES} 的内置默认值。
     *
     * <p><b>做法</b>: 直接改内存里 {@code ProfileSetup.STANDARD_PROFILES} 里的那些对象。
     * 失落城市的三处读取都是按名字从这个 Map 取的:
     * <ul>
     *   <li>{@code Config} —— 读存档时</li>
     *   <li>{@code LostCityFeature} —— 地形生成时</li>
     *   <li>{@code LostCitySetup} —— 创建世界的界面</li>
     * </ul>
     * 所以改内存就够, <b>不需要</b>去动 {@code config/lostcities/profiles/} 下的文件
     * (那些文件每次启动都会被失落城市重写一遍)。
     *
     * @return 被覆盖的配置档个数; 0 表示没找到失落城市(不算错误)
     */
    public static int forceProfileSettings() {
        Path configDir;
        try {
            configDir = FMLPaths.GAMEDIR.get().resolve("config").resolve(BUILDING_DIR_NAME);
        } catch (Throwable t) {
            // 离线测试等场合 FMLPaths 不可用: 跳过文件读写, 直接用内置默认值
            configDir = null;
        }
        return forceProfileSettings(configDir);
    }

    /**
     * 同上, 但显式指定 {@code config/失落城市建筑} 目录(离线测试用)。
     *
     * @param configDir 配置目录; 传 null = 跳过文件读写, 全部用内置默认值
     */
    public static int forceProfileSettings(Path configDir) {
        int count = 0;
        try {
            List<String> cfgWarnings = new ArrayList<>();
            Map<String, Object> values = CityGenConfig.load(configDir, forcedProfileDefaults(), cfgWarnings);
            cfgWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));

            // 城市大小与外城占比: 城区配置.json 的优先级最高 —— 玩家要的就是
            // "改城区配置就行"。ZoneConfig 自带容错: 缺键/坏 JSON 时回落默认
            // (50/50 区块 = 800/800 方块, 外城占比中点 0.4375 = 阈值 0.4),
            // 所以这三行覆盖永远不会把值变成 0 或崩掉。configDir 为 null
            // (FMLPaths 不可用的离线场合)时跳过, 用上面的内置默认即可。
            if (configDir != null) {
                List<String> zoneWarnings = new ArrayList<>();
                ZoneConfig zc = ZoneConfig.load(configDir, zoneWarnings);
                zoneWarnings.forEach(w -> LOGGER.warn("[CAF-LC] {}", w));
                Map<String, Object> sizeOverrides = citySizeOverrides(zc);
                values.putAll(sizeOverrides);
                LOGGER.info("[CAF-LC] 城市大小(来自 城区配置.json): 半径 {}~{} 区块({}~{} 方块), 外城阈值 {}",
                        zc.radiusMin, zc.radiusMax,
                        sizeOverrides.get("CITY_MINRADIUS"), sizeOverrides.get("CITY_MAXRADIUS"),
                        sizeOverrides.get("CITY_STYLE_THRESHOLD"));
            }

            Class<?> setupCls = Class.forName("mcjty.lostcities.config.ProfileSetup");
            Object mapObj = setupCls.getField("STANDARD_PROFILES").get(null);
            if (!(mapObj instanceof Map<?, ?> profiles)) {
                LOGGER.warn("[CAF-LC] STANDARD_PROFILES 不是 Map, 跳过强制设置");
                return 0;
            }
            Class<?> profileCls = Class.forName("mcjty.lostcities.config.LostCityProfile");
            int entries = CityGenConfig.ENTRIES.size();
            Field[] fields = new Field[entries];
            Object[] vals = new Object[entries];
            for (int i = 0; i < entries; i++) {
                CityGenConfig.Entry e = CityGenConfig.ENTRIES.get(i);
                Object v = values.get(e.field());
                Field f = profileCls.getField(e.field());
                // 类型严格匹配: float<-Float, double<-Double, int<-Integer,
                // boolean<-Boolean, String<-String; 对不上就跳过+警告(绝不抛异常中断)
                if (!wrapperType(f.getType()).isInstance(v)) {
                    LOGGER.warn("[CAF-LC] 城市生成配置: {} 需要 {} 类型的值, 实际配置值是 {}, 该项跳过(失落城市原值保留)",
                            e.field(), f.getType().getSimpleName(),
                            v == null ? "null" : v.getClass().getSimpleName());
                    continue;
                }
                fields[i] = f;
                vals[i] = v;
            }
            for (Object profile : profiles.values()) {
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null) {
                        fields[i].set(profile, vals[i]);
                    }
                }
                count++;
            }
            // 失落城市可能已经把配置档缓存过一份了, 清掉让它重新取。
            // 这个方法是可选的: 拿不到也无所谓, 上面的 Map 已经是改过的了。
            try {
                Class.forName("mcjty.lostcities.setup.Config")
                        .getMethod("resetProfileCache").invoke(null);
            } catch (Throwable ignored) {
                // 故意忽略
            }
            LOGGER.info("[CAF-LC] 已强制覆盖 {} 个配置档: 废墟/爆炸/碎砖/藤蔓全关, "
                    + "城市层级锁 0 级、地基统一在 Y={}、不随地形起伏(平坦城市), "
                    + "城市大小/密度/楼层统一为 nodamage 同款", count, GROUNDLEVEL_FORCED);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[CAF-LC] 没找到失落城市的配置档类, 跳过强制设置(不影响建筑生成)");
        } catch (Throwable t) {
            LOGGER.warn("[CAF-LC] 强制覆盖配置档设置失败: {}", t.toString());
        }
        return count;
    }

    /** 字段类型(可能是原始类型)对应的包装类, 用来做反射写入前的严格类型匹配。 */
    private static Class<?> wrapperType(Class<?> t) {
        if (t == float.class) {
            return Float.class;
        }
        if (t == double.class) {
            return Double.class;
        }
        if (t == int.class) {
            return Integer.class;
        }
        if (t == boolean.class) {
            return Boolean.class;
        }
        return t;
    }

    /** "平坦城市"配置档的名字 —— 同时也是文件名(不带 .json), 所以不能含点。 */
    public static final String FLAT_PROFILE_NAME = "caf_flat_sea";

    /** jar 里那份配置档内容的资源路径。 */
    private static final String FLAT_PROFILE_RESOURCE = "/caf_flat_sea_profile.json";

    /**
     * 把"平坦城市"配置档装进 {@code config/lostcities/profiles/}。
     *
     * <p><b>为什么必须用新名字</b>: 失落城市每次启动都会把内置配置档重新写一遍
     * ({@code ProfileSetup.setupProfiles()}), 直接改 {@code nodamage.json} 下次启动就被抹掉。
     * 而 {@code ProfileSetup.readProfiles()} 会读取 profiles 目录下<b>所有</b> .json
     * 并塞进 {@code STANDARD_PROFILES}, 所以新名字的档照样能用, 而且会出现在
     * 新建世界时那个 Cities 按钮的轮换列表里。
     *
     * <p><b>顺序也是安全的</b>: 失落城市先 "Creating standard profiles" 再 "Reading existing
     * profiles", 写内置档的时候我的文件还不在它的列表里, 所以不会被覆盖。
     *
     * <p><b>玩家改过就不动</b>: 只要目标文件已存在就原样保留。
     */
    static void installFlatCityProfile(Path gameDir) {
        Path dir = gameDir.resolve("config").resolve("lostcities").resolve("profiles");
        Path target = dir.resolve(FLAT_PROFILE_NAME + ".json");
        try {
            if (Files.exists(target)) {
                LOGGER.info("[CAF-LC] 平坦城市配置档已存在, 保留你自己那份: {}", target);
                return;
            }
            String json;
            try (InputStream in = LcCustomCityMod.class.getResourceAsStream(FLAT_PROFILE_RESOURCE)) {
                if (in == null) {
                    LOGGER.warn("[CAF-LC] jar 里找不到 {}, 跳过安装平坦城市配置档", FLAT_PROFILE_RESOURCE);
                    return;
                }
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Files.createDirectories(dir);
            Files.writeString(target, json, StandardCharsets.UTF_8);
            LOGGER.info("[CAF-LC] 已安装平坦城市配置档: {}", target);
            LOGGER.info("[CAF-LC] 用法: 新建世界时进「更多」标签页, 点右上角 Cities 按钮, 选到 '{}'",
                    FLAT_PROFILE_NAME);
            LOGGER.info("[CAF-LC] 效果: 城市地面高度不再随地形起伏, 全城统一在 Y=71");
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 安装平坦城市配置档失败: {}", e.toString());
        }
    }

    /** 说明文件的版本号。{@link #README_TEXT} 的内容有改动时把这个数字 +1。 */
    public static final int README_VERSION = 9;
    /** 写在说明文件开头、用来识别版本的标记行。只有本模组会写这一行。 */
    private static final String README_VERSION_MARK = "# CAF-LC 说明版本: ";

    /**
     * 写说明文件。
     *
     * <p>为什么要判版本: 原来的实现是"文件存在就什么都不做", 结果这个文件一旦生成过,
     * 以后模组改进说明文字也永远传不到玩家那里。这里的规则保证<b>绝不覆盖玩家自己写的东西</b>:
     * <ul>
     *   <li>文件不存在 → 直接写</li>
     *   <li>文件存在, 带我们的版本标记但版本更旧 → 先把旧的改名成 {@code 说明-旧版本.txt}, 再写新的</li>
     *   <li>文件存在, 但<b>没有</b>我们的版本标记(玩家自己建的, 或把标记删了) → 不碰它,
     *       改写一份 {@code 说明-新版.txt} 放在旁边</li>
     *   <li>版本相同 → 一个字都不动</li>
     * </ul>
     */
    private static void writeReadme(Path configDir) {
        Path readme = configDir.resolve("说明.txt");
        Path target = readme;
        if (Files.exists(readme)) {
            int old = readReadmeVersion(readme);
            if (old == README_VERSION) {
                return;
            }
            if (old > 0) {
                try {
                    Files.move(readme, configDir.resolve("说明-旧版本.txt"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOGGER.warn("[CAF-LC] 无法备份旧说明文件({}), 改写为 说明-新版.txt", e.getMessage());
                    target = configDir.resolve("说明-新版.txt");
                }
            } else {
                target = configDir.resolve("说明-新版.txt");
            }
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            // 版本行由常量生成, 不再写死在 README_TEXT 里。
            // 写死过: 改了常量忘了改文本, 两者对不上, 于是每次启动都误判"说明过期",
            // 把玩家的笔记覆盖掉。
            w.write(README_VERSION_MARK + README_VERSION + "\n");
            w.write(README_TEXT);
        } catch (IOException e) {
            LOGGER.warn("[CAF-LC] 无法写入说明文件: {}", e.getMessage());
        }
    }

    /** 读说明文件里的版本号。不是本模组写的(没有标记行)就返回 0。 */
    private static int readReadmeVersion(Path readme) {
        try {
            for (String line : Files.readAllLines(readme, StandardCharsets.UTF_8)) {
                if (line.startsWith(README_VERSION_MARK)) {
                    return Integer.parseInt(line.substring(README_VERSION_MARK.length()).trim());
                }
            }
        } catch (Exception e) {
            // 读不了 / 解析不了: 当成"不是我们的文件", 绝不会去覆盖它
        }
        return 0;
    }

    private static final String README_TEXT = """
            CAF失落城市自定义城市附属 —— 建筑存放目录
            ============================================

            把【一整栋建筑】的文件放进这个目录, 城市里就会只生成你放的建筑。

            1) 支持的格式
               - WorldEdit 导出的 .schem   (//wand 选区 -> //copy -> //schem save 名字)
               - 结构方块导出的 .nbt
               - 不支持 Litematica 的 .litematic (那是另一种格式)

            1.1) 怎么做出一个建筑文件(用 WorldEdit)
               - 手里拿【木斧】(输入 //wand 会给你一把):
                   左键点建筑的一个角, 右键点它的对角 -> 选区就定好了
               - //copy                把选区抄进剪贴板
               - //schem save 名字      存成图纸。这个名字就是游戏里的建筑 id
               - 图纸会存到 config\\worldedit\\schematics\\ 里。
                 把它【手动复制】到 失落城市建筑\\ 下的对应文件夹里, 重启游戏就生效。
               - 框选要点: 【选区最底下那格, 就是建筑的一楼地板】。
                 不要把地面、草皮、野树框进去; 建筑自己的后花园和观赏树可以框,
                 但花园的地面要先用 //fill 压平到同一个高度。
               - 想改建筑: 重新框选、同名 //schem save 一次, 用新文件替换掉旧的, 重启游戏。
               - 附属会给每张楼层图纸自动打上"我是第几层"的标记, 所以楼层顺序不会乱。

            2) 建筑怎么放
               - 【扫描不限深度】: 放在本目录下、还是套几层文件夹, 都能扫到
                 (旧版本只认两层, 更深的会被【静默忽略】且不报警 —— 现在已经不会了)
               - 推荐结构(三层):
                    config\\失落城市建筑\\内城\\住宅区\\小楼.schem
                    config\\失落城市建筑\\外城\\民房\\平房.schem
               - 城区只有两个: 内城 / 外城。放在 外城\\ 下面的算外城, 其余(含直接放根目录)都算内城
               - “子分区”文件夹要放在 内城\\ 或 外城\\ 下面(第一层), 用来分区/配占比;
                 再往下套文件夹也可以, 子分区仍按那一层算
               - 一个文件 = 一栋建筑。导出时请只框住建筑本身, 不要带周围的地面和树
               - 同目录里放 说明.txt / 图片 / 压缩包都没关系, 会被自动忽略
                 (只认 .schem / .nbt, 其它后缀一律忽略)
               - 每次启动都会写一份 config\\失落城市建筑\\建筑扫描报告.txt:
                 扫到多少栋、按 内城/外城 和子分区分别是多少、忽略了哪些文件及其路径。
                 建筑没生效时先看这个文件

            3) 尺寸限制
               - 占地最大 256 x 256 方块(16 x 16 格, 一格 = 一个区块 16 方块), 高度不限
               - 超过 16x16 的建筑会被自动转成"多格建筑"(占 2x2 格或更多)
               - 强烈建议至少放几栋 16x16 以内的小建筑: 失落城市要求单格建筑列表非空,
                 如果你的建筑全都大于 16x16, 模组会自动注册一个全空气的占位建筑,
                 街道边就会出现空地

            4) 朝向
               - 失落城市不会旋转建筑。你导出时朝哪个方向, 游戏里就是哪个方向。
                 建议所有建筑用同一个朝向导出。

            5) 建筑必须自带出入口(重要)
               - 失落城市默认会往临街的墙里"凿门": 把两列方块换成 filler(你建筑里最多的
                 那种方块)、中间挖成空气。这会把导出的建筑改掉。
               - 本模组已把这个功能关掉(allowDoors=false), 所以【你的建筑一个方块都
                 不会被改动】。
               - 代价是: 失落城市不再替你开门, 所以建筑【必须自己做出入口】,
                 否则从街上进不去。

            6) 建筑内部可能出现蜘蛛网和锁链
               - 失落城市自带一套装饰(stuff): 往建筑内部挂 5~10 条锁链、撒 5~20 个蜘蛛网。
               - 它们【只往空气里放】, 不会覆盖你放好的任何方块, 所以建筑本身是安全的。
               - 这是僵尸末日的氛围设定, 默认保留。不想要的话可以请作者关掉。

            7) 城市里不会出现失落城市的门脸和公园
               - 本模组把 citystyle 的 fronts(临街门脸部件)和 parks(公园, 其中包含两栋
                 完整的原版建筑)清空了, 否则它们会出现在你的城市里。
               - 桥梁/楼梯/喷泉/铁路地牢等街道设施保持原样。

            8) 生成的东西在哪
               - config\\失落城市建筑\\生成的建筑包\\caf_lc_customcity\\ = 自动生成的数据包, 可以打开检查
               - 它通过 AddPackFindersEvent 直接进数据包栈, 所以【本次进游戏就生效】
               - mods\\CAF-LC-建筑包-XXXX-XXXXXXXX.jar = 兜底(万一上面那条路没走通)
               - 每个存档的 saves\\<存档>\\datapacks\\caf_lc_customcity\\ = 双保险
               - 每次启动游戏都会重新生成, 改了建筑文件重启即可

            8.1) 第一次用的时候
               - 正常情况直接生效。如果进游戏发现还是原版建筑, 退出游戏再进一次就好了
                 (这时候是兜底那条路在起作用), 并把 logs\\latest.log 里的 [CAF-LC] 行发出来。
               - mods 里如果堆了多个 CAF-LC-建筑包-*.jar, 不用管: 编号大的生效,
                 模组会自己删掉多余的; 删不掉的(被游戏占用)关掉游戏后手动删即可。

            8.2) 不想被改动的存档
               - 打开 不注入的存档.txt, 每行写一个存档文件夹名, 就不会往那个存档里放任何东西。
                 文件里默认已经写了你用来提取建筑的源地图。

            9) 已知限制
               - 失落城市的建筑是"字符网格", 会丢失: 告示牌文字、旗帜图案、
                 玩家头颅的主人、箱子内的具体物品、以及所有方块实体的内容。
                 方块本身和它的【全部状态】(楼梯朝向、台阶类型、门开合…)都会保留。
               - 已经生成过的区块不会重新生成, 请用新世界或未探索区域测试。
               - 建筑的<地面/地形>不要框进选区: 最下面 6 格会变成一楼地板。

            10) 在游戏里找城市
               - /lostcities locate <建筑id>      (需要作弊或 OP)
                  以你为中心【螺旋搜索半径 30 区块(480 方块)】, 最多报 7 个,
                  找到就打印坐标 Found at X,Y,Z, 再用 /tp 过去看。
                  命令输入到一半按 Tab 会列出所有建筑 id。
                - /lostcities debug                 (不需要 OP)
                  把你【脚下这一格】的建筑信息打出来, 包括 buildingType(哪栋建筑)、
                  multiBuilding、getCityStyle()、cityLevel 等。
                  注意: 它输出到 logs\\latest.log(或启动器控制台), 不在聊天框里显示。

            11) 废墟/爆炸/城市高度: 创建世界时选哪个配置档都一样
                - 游戏加载完成后, 模组会强制把【所有】失落城市配置档改成下面这套值:
                    废墟(ruinChance)            0     关
                    爆炸(explosionChance)       0     关
                    小爆炸(miniExplosionChance) 0     关
                    碎砖层(rubbleLayer)         false 关
                    藤蔓(vineChance)            0     关
                    城市层级(cityLevel0Height)  384   锁死在 0 级
                    地基高度(groundLevel)       71    全城统一 Y=71
                - 也就是说: 新建世界时右上角那个 Cities 按钮选什么, 结果都一样。
                  不会再出现"建筑被啃得只剩框架、楼板悬空"的情况。
                - 为什么必须这么做: 失落城市的默认档(default)是开着废墟的
                  (ruinChance 0.05) 还带爆炸, 一旦你忘了选档, 建筑就会被腐蚀掉一大半,
                  看起来像"方块大量丢失"。所以干脆由模组统一盖掉这个选择。
                - 注意: 这条会一并盖掉 space / cavern / void_outside 这类特殊配置档的
                  地形效果。
                - 想调整这套强制值: 打开本目录下的 城市生成配置.json 改里面的数字即可
                  (首次启动会自动生成, 带中文说明), 重启游戏生效。
                  整个文件删掉 = 全部用内置默认值。
                - config\\lostcities\\profiles\\caf_flat_sea.json 仍然会自动安装,
                  内容就是上面这套值, 可以打开对照检查; 你自己改过它就不会被覆盖。
                - 范围说明: 以上只管【城市地面高度】。城市外围的自然地形仍是原样,
                  所以高山群系里城市边缘可能仍有原版地形露出。
            """;
}
