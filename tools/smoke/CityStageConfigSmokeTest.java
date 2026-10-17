import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.StageLootConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class CityStageConfigSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("caf-city-stages");
        try {
            check(CityStageConfig.load(root, new ArrayList<>()).legacyMode(), "missing root config keeps legacy mode");
            Path buildings = root.resolve("western").resolve("\u4e61\u6751").resolve("\u5efa\u7b51\u6587\u4ef6");
            Files.createDirectories(buildings);
            Files.write(buildings.resolve("house.nbt"), new byte[]{1});
            Files.createDirectories(root.resolve("empty_style").resolve("\u4e61\u6751").resolve("\u5efa\u7b51\u6587\u4ef6"));
            String json = """
                    {
                      "\u53ef\u7528\u57ce\u5e02\u98ce\u683c\u6587\u4ef6\u5939": ["western", "empty_style", "missing"],
                      "\u9636\u6bb5": {
                        "\u4e61\u6751": {"\u542f\u7528": true, "\u8ddd\u79bb\u6700\u5c0f": 0, "\u8ddd\u79bb\u6700\u5927": 1000,
                          "\u57ce\u5e02\u98ce\u683c": "western", "\u4f7f\u7528\u5206\u533a": false, "\u533a\u5206\u5185\u57ce\u5916\u57ce": false,
                          "\u5206\u533a": {"\u4e61\u6751": {"\u542f\u7528": true, "\u5f62\u72b6": "\u5706\u5f62",
                            "\u5efa\u7b51\u5e03\u5c40": {"\u5efa\u7b51\u95f4\u9694": {"\u6700\u5c0f": 0, "\u6700\u5927": 5},
                              "\u5e76\u6392\u751f\u6210\u6700\u5927\u5bbd\u5ea6": 1, "\u5e76\u6392\u751f\u6210\u6700\u5927\u957f\u5ea6": 5,
                              "\u7a7a\u5730\u4e5f\u8981\u653e\u5efa\u7b51": false, "\u5927\u697c\u4e4b\u95f4\u5f3a\u5236\u7559\u4e00\u683c\u7a7a\u9699": false}}}},
                        "\u5c0f\u9547": {"\u542f\u7528": false, "\u8ddd\u79bb\u6700\u5c0f": 1000, "\u8ddd\u79bb\u6700\u5927": 5000, "\u57ce\u5e02\u98ce\u683c": ""},
                        "\u5c0f\u57ce": {"\u542f\u7528": false, "\u8ddd\u79bb\u6700\u5c0f": 5000, "\u8ddd\u79bb\u6700\u5927": 10000, "\u57ce\u5e02\u98ce\u683c": ""},
                        "\u5927\u57ce": {"\u542f\u7528": false, "\u8ddd\u79bb\u6700\u5c0f": 10000, "\u8ddd\u79bb\u6700\u5927": -1, "\u57ce\u5e02\u98ce\u683c": ""}
                      },
                      "\u98ce\u683c\u9636\u6bb5\u751f\u6210": {"western": {"\u4e61\u6751": {
                        "\u662f\u5426\u751f\u6210": true, "\u751f\u6210\u6570\u91cf\u4e0b\u9650": 1,
                        "\u751f\u6210\u6570\u91cf\u4e0a\u9650": 4, "\u751f\u6210\u6982\u7387": 0.5
                      }}}
                    }
                    """;
            Files.writeString(root.resolve(CityStageConfig.FILE_NAME), json);
            ArrayList<String> warnings = new ArrayList<>();
            CityStageConfig config = CityStageConfig.load(root, warnings);
            check(config.enabled(), "valid village config enabled");
            check(config.availableStyleFolders().equals(java.util.List.of("western", "empty_style", "missing")),
                    "configured styles are created and available");
            check(Files.isDirectory(root.resolve("missing").resolve("小镇").resolve("建筑文件")),
                    "missing style stage skeleton created");
            var village = config.stageAtDistance(0);
            check(village != null && village.name().equals(CityStageConfig.VILLAGE), "lower distance boundary included");
            check(config.stageAtDistance(999) == village, "stage interior");
            check(config.stageAtDistance(1000) == null, "upper distance boundary excluded");
            check(!village.useDistricts() && !village.useInnerOuter(), "independent optional city features");
            var ruralPartition = village.partitions().get("乡村");
            check(ruralPartition != null && ruralPartition.buildingSpacing().min() == 0
                            && ruralPartition.buildingSpacing().max() == 5,
                    "partition building spacing parsed");
            check(ruralPartition.maxParallelWidth() == 1 && ruralPartition.maxParallelLength() == 5,
                    "partition run dimensions parsed");
            check(!ruralPartition.fillEmptyPlots() && !ruralPartition.gapBetweenLargeBuildings(),
                    "partition layout switches parsed");
            check(config.acceptsCity("western", village, 0.49), "probability accepts lower roll");
            check(!config.acceptsCity("western", village, 0.5), "probability rejects boundary roll");
            check(config.styleStage("western", CityStageConfig.VILLAGE).maxCities() == 4, "city quota parsed");
            check(!warnings.stream().anyMatch(w -> w.contains("可用风格目录不存在")),
                    "missing style folders are created instead of rejected");
            Path lootRoot = root.resolve("western").resolve("乡村").resolve("战利品文件");
            Files.createDirectories(lootRoot.resolve("分区"));
            Files.createDirectories(lootRoot.resolve("建筑"));
            Files.writeString(lootRoot.resolve("城市通用.json"), "{\"物品\":[{\"id\":\"minecraft:stone\",\"数量\":1}]}");
            Files.writeString(lootRoot.resolve("分区").resolve("商业区.json"),
                    "{\"物品\":[{\"id\":\"minecraft:iron_ingot\",\"数量\":1}]}");
            Files.writeString(lootRoot.resolve("建筑").resolve("house.json"),
                    "{\"物品\":[{\"id\":\"minecraft:diamond\",\"数量\":1}]}");
            StageLootConfig loot = StageLootConfig.load(root.resolve("western").resolve("乡村"), warnings);
            check(loot.forBuilding("内城", "商业区", "house.nbt").items().get(0).id().equals("minecraft:diamond"),
                    "building loot overrides district and city loot");
            System.out.println("City stage config: PASS (legacy fallback, dynamic stage order, directory skeleton, bounds, feature flags, probability and quotas)");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }
}
