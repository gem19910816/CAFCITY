package com.caf.lccustomcity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Settings shared by all distance stages in the root city configuration. */
public final class RootCityOptions {
    public static final String SECTION = "附属功能设置";

    public record Settings(int spawnCityDistance, boolean dynamicStructures, boolean highwayStyle,
                           boolean blockEntityContentWins, boolean forceFlatten, int scatteredMargin, boolean fillEmptyPlots,
                           boolean gapBetweenLargeBuildings) {}

    private RootCityOptions() {}

    public static Settings load(Path rootDir, Path legacyDir, List<String> warnings) {
        JsonObject section = readSection(rootDir, warnings);
        int spawnDistance = integer(section, "出生点城市中心距离", SpawnCityConfig.DEFAULT,
                legacyDir, SpawnCityConfig.FILE_NAME, warnings, () -> SpawnCityConfig.load(legacyDir, warnings));
        boolean dynamic = bool(section, "生成建筑自带的动态结构", DynamicStructureConfig.DEFAULT,
                legacyDir, DynamicStructureConfig.FILE_NAME, warnings,
                () -> DynamicStructureConfig.load(legacyDir, warnings));
        boolean highway = bool(section, "高速路换成我们的路面", HighwayStyleConfig.DEFAULT,
                legacyDir, HighwayStyleConfig.FILE_NAME, warnings,
                () -> HighwayStyleConfig.load(legacyDir, warnings));
        boolean blockEntities = bool(section, "建筑自带的箱子内容优先", BlockEntityContentConfig.DEFAULT,
                legacyDir, BlockEntityContentConfig.FILE_NAME, warnings,
                () -> BlockEntityContentConfig.load(legacyDir, warnings));
        boolean flatten = bool(section, "城市范围内一律削平", ForceFlattenConfig.DEFAULT,
                legacyDir, ForceFlattenConfig.FILE_NAME, warnings,
                () -> ForceFlattenConfig.load(legacyDir, warnings));
        int margin = integer(section, "野外建筑距城市中心的额外距离", ScatteredMarginConfig.DEFAULT,
                legacyDir, ScatteredMarginConfig.FILE_NAME, warnings,
                () -> ScatteredMarginConfig.load(legacyDir, warnings));
        boolean fillPlots = bool(section, "空地也要放建筑", EmptyPlotConfig.DEFAULT,
                legacyDir, EmptyPlotConfig.FILE_NAME, warnings,
                () -> EmptyPlotConfig.load(legacyDir, warnings));
        boolean buildingGap = bool(section, "大楼之间强制留一格空隙", GapConfig.DEFAULT,
                legacyDir, GapConfig.FILE_NAME, warnings,
                () -> GapConfig.load(legacyDir, warnings));

        if (spawnDistance < 0) spawnDistance = 0;
        else if (spawnDistance > 0 && spawnDistance < SpawnCityConfig.MIN_EFFECTIVE) {
            spawnDistance = SpawnCityConfig.MIN_EFFECTIVE;
        } else if (spawnDistance > SpawnCityConfig.MAX_EFFECTIVE) {
            spawnDistance = SpawnCityConfig.MAX_EFFECTIVE;
        }
        margin = Math.max(0, margin);

        SpawnCityConfig.install(spawnDistance);
        DynamicStructureConfig.install(dynamic);
        HighwayStyleConfig.install(highway);
        BlockEntityContentConfig.install(blockEntities);
        ForceFlattenConfig.install(flatten);
        ScatteredMarginConfig.install(margin);
        EmptyPlotConfig.install(fillPlots);
        GapConfig.install(buildingGap);
        return new Settings(spawnDistance, dynamic, highway, blockEntities, flatten, margin,
                fillPlots, buildingGap);
    }

    private static JsonObject readSection(Path rootDir, List<String> warnings) {
        if (rootDir == null) return null;
        Path file = rootDir.resolve(CityStageConfig.FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement value = root.get(SECTION);
            if (value == null || value.isJsonNull()) return null;
            if (value.isJsonObject()) return value.getAsJsonObject();
            warnings.add(CityStageConfig.FILE_NAME + "." + SECTION + " 应该是对象，沿用已有阶段配置或内置默认值");
        } catch (IOException | RuntimeException e) {
            warnings.add(CityStageConfig.FILE_NAME + " 读取失败，附属功能设置沿用已有阶段配置或内置默认值: " + e.getMessage());
        }
        return null;
    }

    private static boolean bool(JsonObject section, String key, boolean fallback, Path legacyDir,
                                String legacyFile, List<String> warnings, BooleanSupplier legacy) {
        JsonElement value = section == null ? null : section.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value != null) warnings.add(CityStageConfig.FILE_NAME + "." + SECTION + "." + key
                + " 应该是 true/false，沿用已有阶段配置或内置默认值");
        return hasLegacy(legacyDir, legacyFile) ? legacy.getAsBoolean() : fallback;
    }

    private static int integer(JsonObject section, String key, int fallback, Path legacyDir,
                               String legacyFile, List<String> warnings, IntSupplier legacy) {
        JsonElement value = section == null ? null : section.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsInt();
        }
        if (value != null) warnings.add(CityStageConfig.FILE_NAME + "." + SECTION + "." + key
                + " 应该是整数，沿用已有阶段配置或内置默认值");
        return hasLegacy(legacyDir, legacyFile) ? legacy.getAsInt() : fallback;
    }

    private static boolean hasLegacy(Path dir, String file) {
        return dir != null && Files.isRegularFile(dir.resolve(file));
    }

    @FunctionalInterface private interface BooleanSupplier { boolean getAsBoolean(); }
    @FunctionalInterface private interface IntSupplier { int getAsInt(); }
}
