package com.caf.lccustomcity.config;

import com.caf.lccustomcity.LcCustomCityMod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Root configuration for distance-based city stages and their enabled style folders. */
public final class CityStageConfig {
    public static final String FILE_NAME = "城市风格配置.json";
    public static final String VILLAGE = "乡村";
    public static final String TOWN = "小镇";
    public static final String CITY = "小城";
    public static final String METROPOLIS = "大城";
    /** 默认模板使用的阶段顺序；实际运行时顺序来自配置文件。 */
    public static final List<String> DEFAULT_STAGE_NAMES = List.of(VILLAGE, TOWN, CITY, METROPOLIS);
    /** 兼容旧调用方；新代码应使用 {@link #stageNames()}。 */
    @Deprecated
    public static final List<String> STAGE_NAMES = DEFAULT_STAGE_NAMES;
    public static final List<String> DEFAULT_ALLOWED_BIOMES = List.of(
            "minecraft:plains", "minecraft:sunflower_plains", "minecraft:savanna",
            "minecraft:savanna_plateau", "minecraft:meadow");

    public enum StyleMode {
        FIXED("按阶段配置"),
        ALL_CITIES_RANDOM("所有城市随机"),
        PER_STAGE_RANDOM("按阶段随机");

        private final String label;
        StyleMode(String label) { this.label = label; }
        public String label() { return label; }
        static StyleMode parse(String value) {
            for (StyleMode mode : values()) {
                if (mode.label.equals(value) || mode.name().equalsIgnoreCase(value)) return mode;
            }
            return FIXED;
        }
    }

    public record Stage(String name, boolean enabled, int minDistance, int maxDistance,
                        String styleFolder, boolean useDistricts, boolean useInnerOuter,
                        RoadConfig road,
                        Map<String, StagePartitionConfig.Partition> partitions) {
        public boolean contains(int distance) {
            return distance >= minDistance && (maxDistance < 0 || distance < maxDistance);
        }
        public Stage(String name, boolean enabled, int minDistance, int maxDistance,
                     String styleFolder, boolean useDistricts, boolean useInnerOuter) {
            this(name, enabled, minDistance, maxDistance, styleFolder, useDistricts, useInnerOuter,
                    RoadConfig.defaults(), Map.of());
        }
    }

    public record StyleStage(boolean enabled, int minCities, int maxCities, double probability) {
        public boolean eligible(double roll) {
            return enabled && probability > 0 && roll < probability;
        }
    }

    private static final CityStageConfig LEGACY = new CityStageConfig(false, List.of(), List.of(), Map.of(), Map.of(),
            StyleMode.FIXED, true, Map.of(), DEFAULT_ALLOWED_BIOMES, 60, 80, 0);
    private static final AtomicLong REVISIONS = new AtomicLong();
    private static volatile CityStageConfig current = LEGACY;
    private final boolean enabled;
    private final List<String> availableStyleFolders;
    private final List<String> stageOrder;
    private final Map<String, Stage> stages;
    private final Map<String, Map<String, StyleStage>> styleStages;
    private final StyleMode styleMode;
    private final boolean adjacentStyleReuse;
    private final Map<String, Double> styleWeights;
    private final List<String> allowedBiomes;
    private final int minTerrainHeight;
    private final int maxTerrainHeight;
    private final long revision;

    private CityStageConfig(boolean enabled, List<String> availableStyleFolders, List<String> stageOrder,
                            Map<String, Stage> stages, Map<String, Map<String, StyleStage>> styleStages,
                            StyleMode styleMode, boolean adjacentStyleReuse, Map<String, Double> styleWeights,
                            List<String> allowedBiomes, int minTerrainHeight, int maxTerrainHeight,
                            long revision) {
        this.enabled = enabled;
        this.availableStyleFolders = List.copyOf(availableStyleFolders);
        this.stageOrder = List.copyOf(stageOrder);
        this.stages = Map.copyOf(stages);
        Map<String, Map<String, StyleStage>> copy = new LinkedHashMap<>();
        styleStages.forEach((style, entries) -> copy.put(style, Map.copyOf(entries)));
        this.styleStages = Map.copyOf(copy);
        this.styleMode = styleMode == null ? StyleMode.FIXED : styleMode;
        this.adjacentStyleReuse = adjacentStyleReuse;
        this.styleWeights = Map.copyOf(styleWeights);
        this.allowedBiomes = List.copyOf(allowedBiomes == null ? List.of() : allowedBiomes);
        this.minTerrainHeight = Math.min(minTerrainHeight, maxTerrainHeight);
        this.maxTerrainHeight = Math.max(minTerrainHeight, maxTerrainHeight);
        this.revision = revision;
    }

    public static CityStageConfig legacy() { return LEGACY; }
    public static CityStageConfig current() { return current; }
    public static void install(CityStageConfig config) { current = config == null ? LEGACY : config; }
    public boolean legacyMode() { return !enabled && stages.isEmpty(); }
    public boolean enabled() { return enabled; }
    public List<String> availableStyleFolders() { return availableStyleFolders; }
    public List<String> stageNames() { return stageOrder; }
    public StyleMode styleMode() { return styleMode; }
    public boolean adjacentStyleReuse() { return adjacentStyleReuse; }
    public Map<String, Double> styleWeights() { return styleWeights; }
    public List<String> allowedBiomes() { return allowedBiomes; }
    public int minTerrainHeight() { return minTerrainHeight; }
    public int maxTerrainHeight() { return maxTerrainHeight; }
    public List<String> stylesForStage(String stageName) {
        Stage stage = stages.get(stageName);
        if (stage == null) return List.of();
        if (styleMode == StyleMode.FIXED) {
            return availableStyleFolders.contains(stage.styleFolder())
                    ? List.of(stage.styleFolder()) : List.of();
        }
        List<String> result = new ArrayList<>();
        for (String style : availableStyleFolders) {
            StyleStage rule = styleStage(style, stageName);
            if (rule != null && rule.enabled()) result.add(style);
        }
        return List.copyOf(result);
    }
    public Map<String, Stage> stages() { return stages; }
    public long revision() { return revision; }
    public Stage stage(String name) { return stages.get(name); }
    public Stage activeStage() {
        for (String name : stageOrder) {
            Stage stage = stages.get(name);
            if (stage != null && stage.enabled()) return stage;
        }
        return null;
    }
    public Path buildingRoot(Path configDir) {
        Stage stage = activeStage();
        if (!enabled || stage == null) return configDir;
        return configDir.resolve(stage.styleFolder()).resolve(stage.name()).resolve("建筑文件");
    }
    public Path stageRoot(Path configDir) {
        Stage stage = activeStage();
        if (!enabled || stage == null) return configDir;
        return configDir.resolve(stage.styleFolder()).resolve(stage.name());
    }
    public StyleStage styleStage(String style, String stage) {
        return styleStages.getOrDefault(style, Map.of()).get(stage);
    }

    /** 稳定的失落城市 citystyle 名称，按阶段和风格隔离建筑列表。 */
    public static String cityStyleName(String stage, String style, String role) {
        int hash = (stage + "|" + style).hashCode();
        return "caf_stage_" + Integer.toUnsignedString(hash, 16) + "_" + role;
    }

    /** 按配置模式、世界种子和城市坐标稳定地选择该城的风格。 */
    public String styleForCity(String stageName, long worldSeed, int centerX, int centerZ) {
        Stage stage = stages.get(stageName);
        if (stage == null) return null;
        if (styleMode == StyleMode.FIXED && availableStyleFolders.contains(stage.styleFolder())) {
            return stage.styleFolder();
        }
        List<String> candidates = stylesForStage(stageName);
        if (candidates.isEmpty()) return null;
        if (styleMode == StyleMode.ALL_CITIES_RANDOM) {
            long citySeed = mix(worldSeed ^ mix(centerX * 0x9e3779b97f4a7c15L)
                    ^ mix(centerZ * 0xc2b2ae3d27d4eb4fL) ^ 0x6a09e667f3bcc909L);
            return weightedPick(candidates, citySeed);
        }
        long seed = worldSeed ^ mix(stageName.hashCode() * 0x9e3779b97f4a7c15L);
        String chosen = weightedPick(candidates, seed);
        if (!adjacentStyleReuse) {
            int index = stageOrder.indexOf(stageName);
            if (index > 0) {
                String previous = styleForStageRandom(stageOrder.get(index - 1), worldSeed);
                if (candidates.size() > 1 && chosen.equals(previous)) {
                    List<String> alternatives = candidates.stream().filter(s -> !s.equals(previous)).toList();
                    chosen = weightedPick(alternatives, seed ^ 0x3c6ef372fe94f82aL);
                }
            }
        }
        return chosen;
    }

    private String styleForStageRandom(String stageName, long worldSeed) {
        List<String> candidates = stylesForStage(stageName);
        if (candidates.isEmpty()) return null;
        long seed = worldSeed ^ mix(stageName.hashCode() * 0x9e3779b97f4a7c15L);
        return weightedPick(candidates, seed);
    }

    private String weightedPick(List<String> candidates, long seed) {
        double total = 0;
        for (String style : candidates) total += Math.max(0.0, styleWeights.getOrDefault(style, 1.0));
        if (!(total > 0)) return candidates.get((int) Math.floorMod(seed, candidates.size()));
        double point = ((mix(seed) >>> 11) * 0x1.0p-53) * total;
        for (String style : candidates) {
            point -= Math.max(0.0, styleWeights.getOrDefault(style, 1.0));
            if (point < 0) return style;
        }
        return candidates.getLast();
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /**
     * Creates an inert root template on first startup.  The template deliberately
     * has no enabled stage or style, so installing it cannot change an existing
     * world until the player fills it in.
     */
    public static void writeTemplateIfMissing(Path configDir, List<String> warnings) {
        if (configDir == null) return;
        Path file = configDir.resolve(FILE_NAME);
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(configDir);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(template()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another startup thread won the race; keep the player's file intact.
        } catch (IOException | RuntimeException e) {
            warnings.add(FILE_NAME + " 模板写入失败: " + e.getMessage());
        }
    }

    private static JsonObject template() {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "新城市阶段配置。填写后重启游戏生效；本模板默认全部停用，不会改变旧版城市生成。"
                + "可用城市风格文件夹填写 CAF失落城市配置 下的风格目录名。分区建筑必须放在 风格/阶段/分区/建筑文件/ 下。"
                + "配置统一写在本文件的附属功能设置、城市生成配置及各阶段项中；分区目录下放建筑文件、道路文件和战利品文件。"
                + "每个分区的战利品配置直接放在该分区的战利品文件目录中，分区通用文件建议命名为 分区通用.json；"
                + "战利品优先级为单建筑、分区通用。"
                + "距离单位为方块，最大距离填 -1 表示没有上限，生成概率填 0 到 1。"
                + "生成数量上下限表示一次候选城心扫描区域内的阶段城市座数配额。"
                + "阶段可以自定义名称，名称同时作为风格目录下的阶段文件夹名，最多 10 个。"
        );
        root.add("可用城市风格文件夹", new JsonArray());
        JsonArray biomes = new JsonArray();
        DEFAULT_ALLOWED_BIOMES.forEach(biomes::add);
        root.add("允许生成的群系", biomes);
        root.addProperty("城市地面高度最小", 60);
        root.addProperty("城市地面高度最大", 80);
        JsonObject addonOptions = new JsonObject();
        addonOptions.addProperty("_说明", "适用于所有阶段城市的附属功能设置，集中在根目录配置文件中。");
        addonOptions.addProperty("出生点城市中心距离", SpawnCityConfig.DEFAULT);
        addonOptions.addProperty("_说明_出生点城市中心距离", "单位为格，保证出生点此距离内有城市城心；0关闭。");
        addonOptions.addProperty("生成建筑自带的动态结构", DynamicStructureConfig.DEFAULT);
        addonOptions.addProperty("_说明_生成建筑自带的动态结构", "生成建筑文件自带的实体，包括机械动力动态结构。");
        addonOptions.addProperty("高速路换成我们的路面", HighwayStyleConfig.DEFAULT);
        addonOptions.addProperty("_说明_高速路换成我们的路面", "将失落城市高速路的铺面替换为本模组配置的路面材料。");
        addonOptions.addProperty("建筑自带的箱子内容优先", BlockEntityContentConfig.DEFAULT);
        addonOptions.addProperty("_说明_建筑自带的箱子内容优先", "true保留建筑文件中箱子的自带内容；false时战利品池优先。");
        addonOptions.addProperty("城市范围内一律削平", ForceFlattenConfig.DEFAULT);
        addonOptions.addProperty("_说明_城市范围内一律削平", "关闭结构回避并补实城市地基，阻止与城市相交的原版地表结构生成。");
        addonOptions.addProperty("野外建筑距城市中心的额外距离", ScatteredMarginConfig.DEFAULT);
        addonOptions.addProperty("_说明_野外建筑距城市中心的额外距离", "单位为格；城市半径外再增加此距离才允许生成野外建筑，0关闭额外避让。");
        root.add(RootCityOptions.SECTION, addonOptions);
        root.add("城市生成配置", CityGenConfig.profileTemplate(LcCustomCityMod.forcedProfileDefaults()));
        root.addProperty("城市风格分配方式", StyleMode.FIXED.label());
        root.addProperty("相邻阶段允许相同风格", true);
        root.add("城市风格生成概率", new JsonObject());
        root.addProperty("阶段数量", DEFAULT_STAGE_NAMES.size());

        JsonObject stages = new JsonObject();
        int[][] ranges = {{0, 3000}, {3000, 8000}, {8000, 15000}, {15000, -1}};
        for (int i = 0; i < STAGE_NAMES.size(); i++) {
            JsonObject stage = new JsonObject();
            stage.addProperty("启用", false);
            stage.addProperty("距离最小", ranges[i][0]);
            stage.addProperty("距离最大", ranges[i][1]);
            stage.addProperty("城市风格", "");
            stage.addProperty("使用分区", true);
            stage.add("道路", RoadConfig.template());
            stage.add("分区", partitionTemplates());
            stage.add("生成规则", StageLayoutConfig.template());
            stages.add(STAGE_NAMES.get(i), stage);
        }
        root.add("阶段", stages);
        root.add("风格阶段生成", new JsonObject());
        return root;
    }

    private static JsonObject partitionTemplates() {
        JsonObject result = new JsonObject();
        result.add("住宅区", partitionTemplate("扇形", "固定", 10, 45, 90));
        result.add("工业区", partitionTemplate("扇形", "固定", 10, 225, 90));
        result.add("商业区", partitionTemplate("环形", "固定", 0, 0, 360));
        return result;
    }

    private static JsonObject partitionTemplate(String shape, String placement, int priority,
                                                int angleStart, int angleRange) {
        JsonObject value = new JsonObject();
        value.addProperty("_说明", "分区文件夹为 风格/阶段/本分区名/，其中建筑文件、战利品文件和道路文件分别存放对应资源。道路文件下按直道、弯道、尽头、三岔路口、十字路口分类，每个 .nbt 都是包含路面和街景装饰的完整道路场景。"
                + "随机分区会稳定地优先寻找固定分区覆盖较少的位置；空缺按相邻分区优先级归属。"
                + "大小和建筑布局均按区块计算；角度0度朝南，90度朝东，180度朝北，270度朝西。优先级数字越大越优先。");
        value.addProperty("启用", true);
        value.addProperty("形状", shape);
        value.addProperty("摆放方式", placement);
        value.addProperty("优先级", priority);
        JsonObject size = new JsonObject();
        size.addProperty("中心X", 0); size.addProperty("中心Z", 0);
        size.addProperty("内半径", 0); size.addProperty("外半径", 50);
        size.addProperty("宽度", 20); size.addProperty("长度", 20);
        size.addProperty("角度起点", angleStart); size.addProperty("角度范围", angleRange);
        value.add("大小", size);
        JsonObject layout = new JsonObject();
        // 未填写建筑间隔时，由阶段道路宽度提供默认值。
        layout.addProperty("并排生成最大宽度", 2);
        layout.addProperty("并排生成最大长度", 6);
        layout.addProperty("连排建筑生成概率", 1.0);
        layout.addProperty("空地也要放建筑", EmptyPlotConfig.DEFAULT);
        layout.addProperty("大楼之间强制留一格空隙", GapConfig.DEFAULT);
        value.add("建筑布局", layout);
        return value;
    }

    public Stage stageAtDistance(int distance) {
        if (!enabled || distance < 0) return null;
        for (String name : stageOrder) {
            Stage stage = stages.get(name);
            if (stage != null && stage.enabled() && stage.contains(distance)) return stage;
        }
        return null;
    }

    public boolean acceptsCity(String style, Stage stage, double roll) {
        if (stage == null || style == null) return false;
        StyleStage rule = styleStage(style, stage.name());
        return rule != null && rule.eligible(roll);
    }

    /** Loads and validates the root file. A missing file means the old single-city system stays active. */
    public static CityStageConfig load(Path configDir, List<String> warnings) {
        if (configDir == null) return legacy();
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return legacy();
        final JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            warnings.add(FILE_NAME + " 读取或解析失败，新城市方案本次停用: " + e.getMessage());
            return legacy();
        }
        ensureRootSections(configDir, root, warnings);

        List<String> configuredFolders = stringList(root.get("可用城市风格文件夹"), FILE_NAME + ".可用城市风格文件夹", warnings);
        StyleMode styleMode = StyleMode.parse(string(root, "城市风格分配方式", StyleMode.FIXED.label(),
                FILE_NAME, warnings));
        boolean adjacentStyleReuse = bool(root, "相邻阶段允许相同风格", true, FILE_NAME, warnings);
        Map<String, Double> styleWeights = parseStyleWeights(root.get("城市风格生成概率"),
                configuredFolders, warnings);
        List<String> allowedBiomes = parseBiomeList(root.get("允许生成的群系"), warnings);
        int minTerrainHeight = integer(root, "城市地面高度最小", 60, FILE_NAME, warnings);
        int maxTerrainHeight = integer(root, "城市地面高度最大", 80, FILE_NAME, warnings);
        if (minTerrainHeight > maxTerrainHeight) {
            warnings.add(FILE_NAME + ": 城市地面高度最小值大于最大值，已交换两者");
            int swap = minTerrainHeight; minTerrainHeight = maxTerrainHeight; maxTerrainHeight = swap;
        }
        Set<String> available = new LinkedHashSet<>();
        for (String folder : configuredFolders) {
            if (!isSafeDirectoryName(folder)) {
                warnings.add(FILE_NAME + ": 风格目录必须是本目录下的单层文件夹名，已忽略 " + folder);
            }
        }

        LinkedHashMap<String, JsonObject> stageEntries = parseStageEntries(root.get("阶段"), warnings);
        if (stageEntries.isEmpty()) return disabledWithWarnings("缺少 阶段 配置");
        List<String> stageOrder = List.copyOf(stageEntries.keySet());
        int declaredStageCount = integer(root, "阶段数量", stageOrder.size(), FILE_NAME, warnings);
        if (declaredStageCount < 1 || declaredStageCount > 10) {
            warnings.add(FILE_NAME + ": 阶段数量必须在 1 到 10 之间，实际阶段仍以 阶段 配置为准");
        } else if (declaredStageCount != stageOrder.size()) {
            warnings.add(FILE_NAME + ": 阶段数量为 " + declaredStageCount
                    + "，但阶段配置实际有 " + stageOrder.size() + " 个，实际阶段仍以 阶段 配置为准");
        }
        ensureStyleDirectories(configDir, configuredFolders, stageOrder, warnings);

        // 创建目录后再确认风格是否有效。这样配置里新增风格名时，首次启动会自动建立骨架目录。
        available.clear();
        for (String folder : configuredFolders) {
            if (!isSafeDirectoryName(folder)) continue;
            Path stylePath = configDir.resolve(folder).normalize();
            if (Files.isDirectory(stylePath) && stylePath.getParent().equals(configDir.normalize())) {
                available.add(folder);
            }
        }
        JsonObject styleObject = object(root.get("风格阶段生成"), FILE_NAME + ".风格阶段生成", warnings);
        Map<String, Map<String, StyleStage>> styleStages = parseStyleStages(styleObject, available, stageOrder, warnings);

        Map<String, Stage> stages = new LinkedHashMap<>();
        Map<String, Map<String, StagePartitionConfig.Partition>> partitionDefinitions = new LinkedHashMap<>();
        int previousMax = -1;
        for (String name : stageOrder) {
            JsonObject value = stageEntries.get(name);
            boolean stageEnabled = bool(value, "启用", false, FILE_NAME + ".阶段." + name, warnings);
            int min = integer(value, "距离最小", -1, FILE_NAME + ".阶段." + name, warnings);
            int max = integer(value, "距离最大", -1, FILE_NAME + ".阶段." + name, warnings);
            String style = string(value, "城市风格", "", FILE_NAME + ".阶段." + name, warnings);
            boolean districts = bool(value, "使用分区", false, FILE_NAME + ".阶段." + name, warnings);
            boolean innerOuter = false;
            RoadConfig road = value.has("道路") && value.get("道路").isJsonObject()
                    ? RoadConfig.parse(value.getAsJsonObject("道路")) : RoadConfig.defaults();
            Map<String, StagePartitionConfig.Partition> partitions = StagePartitionConfig.parse(
                    value, FILE_NAME + ".阶段." + name + ".分区", warnings);
            partitionDefinitions.put(name, partitions);
            if (min < 0 || (max >= 0 && max <= min)) {
                warnings.add(FILE_NAME + ": 阶段 " + name + " 的距离范围无效，该阶段停用");
                stageEnabled = false;
            }
            if (previousMax >= 0 && min >= 0 && min < previousMax) {
                warnings.add(FILE_NAME + ": 阶段 " + name + " 与前一阶段距离重叠，该阶段停用");
                stageEnabled = false;
            }
            if (stageEnabled && styleMode == StyleMode.FIXED
                    && (!available.contains(style) || !hasStageBuildings(configDir, style, name))) {
                warnings.add(FILE_NAME + ": 阶段 " + name + " 指向不可用或没有建筑的风格 " + style + "，该阶段停用");
                stageEnabled = false;
            }
            if (stageEnabled) {
                boolean hasRule = styleMode == StyleMode.FIXED
                        ? styleStages.getOrDefault(style, Map.of()).get(name) != null
                        && styleStages.getOrDefault(style, Map.of()).get(name).enabled()
                        : available.stream().anyMatch(candidate -> {
                            StyleStage rule = styleStages.getOrDefault(candidate, Map.of()).get(name);
                            return rule != null && rule.enabled();
                        });
                if (!hasRule) {
                    warnings.add(FILE_NAME + ": 阶段 " + name + " 没有可用的风格阶段生成规则，该阶段停用");
                    stageEnabled = false;
                }
            }
            Stage stage = new Stage(name, stageEnabled, min, max, style, districts, innerOuter, road, partitions);
            stages.put(name, stage);
            previousMax = max;
        }
        StagePartitionConfig.ensureBuildingDirectories(configDir, configuredFolders, stageOrder,
                partitionDefinitions, warnings);
        StagePartitionConfig.ensureRoadDirectories(configDir, configuredFolders, stageOrder,
                partitionDefinitions, warnings);
        ensureRoadWeightTemplates(configDir, root, configuredFolders, stageOrder, partitionDefinitions, warnings);
        // 读取刚刚补全的道路文件权重，供本次启动立即生效。
        for (String name : stageOrder) {
            Stage old = stages.get(name);
            if (old == null) continue;
            JsonObject value = stageEntries.get(name);
            RoadConfig road = value != null && value.has("道路") && value.get("道路").isJsonObject()
                    ? RoadConfig.parse(value.getAsJsonObject("道路")) : RoadConfig.defaults();
            stages.put(name, new Stage(old.name(), old.enabled(), old.minDistance(), old.maxDistance(),
                    old.styleFolder(), old.useDistricts(), old.useInnerOuter(), road, old.partitions()));
        }
        boolean anyEnabled = stages.values().stream().anyMatch(Stage::enabled);
        if (available.isEmpty()) warnings.add(FILE_NAME + ": 可用城市风格文件夹没有有效目录，新城市方案停用");
        StagePartitionConfig.writeOverview(configDir, List.copyOf(available), stageOrder,
                partitionDefinitions, warnings);
        return new CityStageConfig(anyEnabled && !available.isEmpty(), List.copyOf(available), stageOrder, stages,
                styleStages, styleMode, adjacentStyleReuse, styleWeights, allowedBiomes,
                minTerrainHeight, maxTerrainHeight, REVISIONS.incrementAndGet());
    }

    private static CityStageConfig disabledWithWarnings(String reason) {
        return new CityStageConfig(false, List.of(), List.of(), Map.of(), Map.of(),
                StyleMode.FIXED, true, Map.of(), DEFAULT_ALLOWED_BIOMES, 60, 80, reason.hashCode());
    }

    private static Map<String, Map<String, StyleStage>> parseStyleStages(JsonObject root, Set<String> available,
                                                                         List<String> stageOrder, List<String> warnings) {
        Map<String, Map<String, StyleStage>> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (Map.Entry<String, JsonElement> styleEntry : root.entrySet()) {
            if (styleEntry.getKey().startsWith("_") || !available.contains(styleEntry.getKey())) continue;
            JsonObject stageEntries = object(styleEntry.getValue(), FILE_NAME + ".风格阶段生成." + styleEntry.getKey(), warnings);
            if (stageEntries == null) continue;
            Map<String, StyleStage> parsed = new LinkedHashMap<>();
            for (String name : stageOrder) {
                JsonObject entry = object(stageEntries.get(name), FILE_NAME + ".风格阶段生成." + styleEntry.getKey() + "." + name, warnings);
                if (entry == null) continue;
                boolean enabled = bool(entry, "是否生成", false, name, warnings);
                int min = integer(entry, "生成数量下限", 0, name, warnings);
                int max = integer(entry, "生成数量上限", Integer.MAX_VALUE, name, warnings);
                double probability = decimal(entry, "生成概率", 0, name, warnings);
                if (min < 0 || max < min || probability < 0 || probability > 1 || Double.isNaN(probability)) {
                    warnings.add(FILE_NAME + ": " + styleEntry.getKey() + "." + name + " 城市数量范围或概率无效，该风格阶段停用");
                    enabled = false;
                }
                parsed.put(name, new StyleStage(enabled, min, max, Math.max(0, Math.min(1, probability))));
            }
            result.put(styleEntry.getKey(), parsed);
        }
        return result;
    }

    private static Map<String, Double> parseStyleWeights(JsonElement value, List<String> folders,
                                                         List<String> warnings) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String folder : folders) result.put(folder, 1.0);
        if (value == null) return result;
        JsonObject object = object(value, FILE_NAME + ".城市风格生成概率", warnings);
        if (object == null) return result;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!folders.contains(entry.getKey())) continue;
            JsonElement number = entry.getValue();
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
                warnings.add(FILE_NAME + ": 城市风格生成概率." + entry.getKey() + " 应该是数字，使用 1");
                continue;
            }
            double weight = number.getAsDouble();
            if (Double.isNaN(weight) || weight < 0) {
                warnings.add(FILE_NAME + ": 城市风格生成概率." + entry.getKey() + " 不能小于 0，使用 1");
                continue;
            }
            result.put(entry.getKey(), weight);
        }
        return result;
    }

    /**
     * 读取阶段配置。对象格式保持对旧配置兼容；数组格式允许明确写出阶段名称，
     * 例如 [{"名称":"乡村", ...}, {"名称":"前哨", ...}]。
     */
    private static LinkedHashMap<String, JsonObject> parseStageEntries(JsonElement value,
                                                                         List<String> warnings) {
        LinkedHashMap<String, JsonObject> result = new LinkedHashMap<>();
        if (value == null || value.isJsonNull()) {
            warnings.add(FILE_NAME + ".阶段 应该是对象或数组，已停用");
            return result;
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                String name = entry.getKey().trim();
                if (name.startsWith("_")) {
                    continue;
                }
                if (!isSafeDirectoryName(name)) {
                    warnings.add(FILE_NAME + ": 阶段名必须是单层安全目录名，已忽略 " + name);
                    continue;
                }
                JsonObject definition = object(entry.getValue(), FILE_NAME + ".阶段." + name, warnings);
                if (definition != null) {
                    putStage(result, name, definition, warnings);
                }
            }
            return result;
        }
        if (!value.isJsonArray()) {
            warnings.add(FILE_NAME + ".阶段 应该是对象或数组，已停用");
            return result;
        }
        int index = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            String at = FILE_NAME + ".阶段[" + index + "]";
            JsonObject definition = object(element, at, warnings);
            if (definition != null) {
                String name = string(definition, "名称", "", at, warnings);
                if (!isSafeDirectoryName(name)) {
                    warnings.add(at + ".名称 必须是单层安全目录名，已忽略该阶段");
                } else {
                    putStage(result, name, definition, warnings);
                }
            }
            index++;
        }
        return result;
    }

    private static boolean putStage(LinkedHashMap<String, JsonObject> result, String name,
                                    JsonObject definition, List<String> warnings) {
        if (result.size() >= 10 && !result.containsKey(name)) {
            warnings.add(FILE_NAME + ": 阶段最多支持 10 个，已忽略 " + name);
            return false;
        }
        if (result.putIfAbsent(name, definition) != null) {
            warnings.add(FILE_NAME + ": 阶段名重复，已忽略后面的 " + name);
            return false;
        }
        return true;
    }

    private static boolean isSafeDirectoryName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) return false;
        if (name.contains("/") || name.contains("\\") || name.indexOf(':') >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    /** 创建风格/阶段目录骨架；只补缺失目录，绝不覆盖用户文件。 */
    private static void ensureStyleDirectories(Path configDir, List<String> styleFolders,
                                               List<String> stageOrder, List<String> warnings) {
        for (String style : styleFolders) {
            if (!isSafeDirectoryName(style)) continue;
            try {
                Path styleRoot = configDir.resolve(style).normalize();
                if (!styleRoot.getParent().equals(configDir.normalize())) continue;
                Files.createDirectories(styleRoot);
                for (String stage : stageOrder) {
                    if (!isSafeDirectoryName(stage)) continue;
                    Path stageRoot = styleRoot.resolve(stage);
                    // 阶段根目录只作为分区目录的容器；建筑和战利品必须位于具体分区下。
                    Files.createDirectories(stageRoot);
                }
            } catch (IOException | RuntimeException e) {
                warnings.add(FILE_NAME + ": 创建风格目录 " + style + " 失败: " + e.getMessage());
            }
        }
    }

    private static void ensureRootSections(Path configDir, JsonObject root, List<String> warnings) {
        boolean changed = false;
        if (!root.has("城市生成配置") || !root.get("城市生成配置").isJsonObject()) {
            root.add("城市生成配置", CityGenConfig.profileTemplate(LcCustomCityMod.forcedProfileDefaults()));
            changed = true;
        }
        JsonObject addon = root.has(RootCityOptions.SECTION) && root.get(RootCityOptions.SECTION).isJsonObject()
                ? root.getAsJsonObject(RootCityOptions.SECTION) : new JsonObject();
        if (!root.has(RootCityOptions.SECTION) || !root.get(RootCityOptions.SECTION).isJsonObject()) changed = true;
        if (!root.has(RootCityOptions.SECTION) || !root.get(RootCityOptions.SECTION).isJsonObject()) {
            root.add(RootCityOptions.SECTION, addon);
        }
        JsonElement stageElement = root.get("阶段");
        if (stageElement != null && stageElement.isJsonObject()) {
            JsonObject stages = stageElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : stages.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject stage = entry.getValue().getAsJsonObject();
                changed |= addDefaultObject(stage, "道路", RoadConfig.template());
                changed |= addDefaultObject(stage, "生成规则", StageLayoutConfig.template());
            }
        }
        if (changed) {
            try {
                Files.writeString(configDir.resolve(FILE_NAME), new GsonBuilder().setPrettyPrinting()
                        .disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                warnings.add(FILE_NAME + " 新配置模板补全失败: " + e.getMessage());
            }
        }
    }

    private static boolean addDefaultObject(JsonObject object, String key, JsonObject value) {
        if (object.has(key) && object.get(key).isJsonObject()) return false;
        object.add(key, value);
        return true;
    }

    private static void ensureRoadWeightTemplates(Path configDir, JsonObject root, List<String> styles,
                                                   List<String> stages,
                                                   Map<String, Map<String, StagePartitionConfig.Partition>> partitions,
                                                   List<String> warnings) {
        boolean changed = false;
        JsonObject stageRoot = root.get("阶段").isJsonObject() ? root.getAsJsonObject("阶段") : new JsonObject();
        for (String stageName : stages) {
            JsonObject stage = stageRoot.has(stageName) && stageRoot.get(stageName).isJsonObject()
                    ? stageRoot.getAsJsonObject(stageName) : null;
            if (stage == null) continue;
            JsonObject road = stage.has("道路") && stage.get("道路").isJsonObject()
                    ? stage.getAsJsonObject("道路") : new JsonObject();
            JsonObject oldWeights = road.has("道路生成权重") && road.get("道路生成权重").isJsonObject()
                    ? road.getAsJsonObject("道路生成权重") : new JsonObject();
            Map<String, Double> existing = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : oldWeights.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    existing.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            JsonObject weights = new JsonObject();
            for (String style : styles) {
                Path dir = configDir.resolve(style).resolve(stageName).resolve("道路文件");
                if (!Files.isDirectory(dir)) continue;
                try (var shapes = Files.list(dir)) {
                        for (Path shape : shapes.filter(Files::isDirectory).sorted().toList()) {
                            try (var files = Files.list(shape)) {
                                for (Path file : files.filter(Files::isRegularFile).sorted()
                                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbt")).toList()) {
                                    String key = shape.getFileName() + "/" + file.getFileName();
                                    // Migrate the previous stage/partition-prefixed key when present;
                                    // otherwise preserve the user's current weight, defaulting to 1.
                                    Double value = existing.get(key);
                                    if (value == null) value = existing.get(stageName + "/" + key);
                                    if (value == null) value = 1.0;
                                    weights.addProperty(key, value);
                                }
                            }
                        }
                } catch (IOException e) {
                    warnings.add(FILE_NAME + ": 扫描道路文件失败 " + dir + ": " + e.getMessage());
                }
            }
            JsonElement previous = road.get("道路生成权重");
            if (previous == null || !weights.equals(previous)) changed = true;
            road.add("道路生成权重", weights);
            stage.add("道路", road);
        }
        if (changed) {
            try {
                Files.writeString(configDir.resolve(FILE_NAME), new GsonBuilder().setPrettyPrinting()
                        .disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                warnings.add(FILE_NAME + " 道路权重模板补全失败: " + e.getMessage());
            }
        }
    }

    private static boolean hasStageBuildings(Path configDir, String style, String stage) {
        Path stageRoot = configDir.resolve(style).resolve(stage);
        if (!Files.isDirectory(stageRoot)) return false;
        try (var files = Files.walk(stageRoot)) {
            return files.anyMatch(p -> Files.isRegularFile(p)
                    && isUnderNamedDirectory(p, "建筑文件")
                    && (p.getFileName().toString().toLowerCase().endsWith(".nbt")
                    || p.getFileName().toString().toLowerCase().endsWith(".schem")));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isUnderNamedDirectory(Path file, String directoryName) {
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) {
            if (directoryName.equals(parent.getFileName() == null ? null : parent.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> stringList(JsonElement value, String at, List<String> warnings) {
        if (value == null || !value.isJsonArray()) {
            warnings.add(at + " 应该是字符串数组");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) values.add(entry.getAsString().trim());
            else warnings.add(at + " 含有非字符串项，已忽略");
        }
        return values;
    }

    private static List<String> parseBiomeList(JsonElement value, List<String> warnings) {
        if (value == null || value.isJsonNull()) return DEFAULT_ALLOWED_BIOMES;
        List<String> values = stringList(value, FILE_NAME + ".允许生成的群系", warnings);
        List<String> result = new ArrayList<>();
        for (String biome : values) {
            String id = biome.trim().toLowerCase(java.util.Locale.ROOT);
            if (id.equals("*")) return List.of();
            if (!id.contains(":")) {
                warnings.add(FILE_NAME + ": 群系 ID 必须包含命名空间，已忽略 " + biome);
                continue;
            }
            if (!result.contains(id)) result.add(id);
        }
        return List.copyOf(result);
    }

    private static JsonObject object(JsonElement value, String at, List<String> warnings) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return value.getAsJsonObject();
        warnings.add(at + " 应该是对象，已忽略");
        return null;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback, String at, List<String> warnings) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        warnings.add(at + "." + key + " 应该是布尔值，使用默认值 " + fallback);
        return fallback;
    }

    private static int integer(JsonObject object, String key, int fallback, String at, List<String> warnings) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsInt();
        warnings.add(at + "." + key + " 应该是整数，使用默认值 " + fallback);
        return fallback;
    }

    private static double decimal(JsonObject object, String key, double fallback, String at, List<String> warnings) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
        warnings.add(at + "." + key + " 应该是数字，使用默认值 " + fallback);
        return fallback;
    }

    private static String string(JsonObject object, String key, String fallback, String at, List<String> warnings) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString().trim();
        warnings.add(at + "." + key + " 应该是字符串，使用空值");
        return fallback;
    }
}
