package com.caf.lccustomcity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage-specific geometry and placement rules stored in the root city configuration. */
public final class StageLayoutConfig {
    public static final String FILE_NAME = CityGenConfig.FILE_NAME;

    public record Range(Integer min, Integer max) {
        public Range {
            if (min != null && min < 0) min = 0;
            if (max != null && max < 0) max = 0;
            if (min != null && max != null && max < min) {
                int value = min;
                min = max;
                max = value;
            }
        }
        public boolean configured() { return min != null || max != null; }
        public int choose(long seed, int fallback) {
            int lo = min == null ? fallback : min;
            int hi = max == null ? lo : max;
            if (hi < lo) hi = lo;
            return lo + (hi == lo ? 0 : (int) Math.floorMod(seed, hi - lo + 1));
        }
    }

    public record BuildingRule(Range spacing, double refreshRate, Integer minCount, Integer maxCount) {
        public boolean hasCount() { return minCount != null || maxCount != null; }
        public int minimumCount() { return minCount == null ? 0 : Math.max(0, minCount); }
        public int maximumCount() { return maxCount == null ? Integer.MAX_VALUE : Math.max(minimumCount(), maxCount); }
        /** Whether this building overrides the stage-wide same-building spacing. */
        public boolean hasSpacing() { return spacing != null && spacing.configured(); }
        public int minimumSpacing() { return !hasSpacing() || spacing.min() == null ? 0 : spacing.min(); }
        public Integer maximumSpacing() { return !hasSpacing() ? null : spacing.max(); }
    }

    public record Rules(int radiusMinBlocks, int radiusMaxBlocks, Double centerChance,
                        int candidateCenterCount, int candidateGridSize,
                        boolean terrainCheckEnabled, int terrainCheckRadiusBlocks,
                        Range centerSpacing, Range buildingSpacing, int maxContinuousWidth,
                        int maxContinuousLength, int sameBuildingSpacing,
                        Map<String, BuildingRule> buildings) {
        public Rules {
            radiusMinBlocks = Math.max(16, radiusMinBlocks);
            radiusMaxBlocks = Math.max(radiusMinBlocks, radiusMaxBlocks);
            centerChance = centerChance == null ? null : clamp(centerChance);
            candidateCenterCount = Math.max(1, Math.min(64, candidateCenterCount));
            candidateGridSize = Math.max(256, Math.min(8192, candidateGridSize));
            terrainCheckRadiusBlocks = Math.max(0, Math.min(256, terrainCheckRadiusBlocks));
            maxContinuousWidth = Math.max(1, maxContinuousWidth);
            maxContinuousLength = Math.max(1, maxContinuousLength);
            sameBuildingSpacing = Math.max(0, sameBuildingSpacing);
            buildings = buildings == null ? Map.of() : Map.copyOf(buildings);
        }
        public BuildingRule building(String fileName) {
            if (fileName == null) return null;
            BuildingRule rule = buildings.get(fileName);
            if (rule != null) return rule;
            String simple = Path.of(fileName).getFileName().toString();
            return buildings.get(simple);
        }
        public int chosenBuildingSpacing(long seed) {
            return buildingSpacing == null || !buildingSpacing.configured()
                    ? 1 : buildingSpacing.choose(seed, 1);
        }
        private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    }

    private StageLayoutConfig() {}

    public static Rules defaults(int radiusMin, int radiusMax) {
        return new Rules(radiusMin, radiusMax, null, 10, 1024, true, 50, new Range(null, null),
                new Range(null, null), 2, 6, 10, Map.of());
    }

    /** Reads the extra layout section; missing or invalid files use safe defaults. */
    public static Rules load(Path configDir, List<String> warnings, int fallbackMin, int fallbackMax) {
        Rules defaults = defaults(fallbackMin, fallbackMax);
        if (configDir == null) return defaults;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return defaults;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return defaults;
            return parse(parsed.getAsJsonObject(), warnings, defaults);
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 布局部分读取失败，使用默认值: " + e.getMessage());
            return defaults;
        }
    }

    public static Rules loadForStage(Path configDir, String stageName, List<String> warnings,
                                     int fallbackMin, int fallbackMax) {
        Rules defaults = defaults(fallbackMin, fallbackMax);
        JsonObject root = RootConfigFile.read(configDir, warnings);
        JsonObject stage = RootConfigFile.stage(root, stageName);
        JsonElement value = stage.get("生成规则");
        return value != null && value.isJsonObject()
                ? parse(value.getAsJsonObject(), warnings, defaults) : defaults;
    }

    private static Rules parse(JsonObject root, List<String> warnings, Rules defaults) {
        try {
            JsonObject city = object(root, "城市");
            Double centerChance = null;
            int candidateCount = defaults.candidateCenterCount();
            int candidateGridSize = defaults.candidateGridSize();
            boolean terrainCheckEnabled = defaults.terrainCheckEnabled();
            int terrainCheckRadius = defaults.terrainCheckRadiusBlocks();
            Range centerSpacing = defaults.centerSpacing();
            if (city != null) {
                centerChance = decimal(city.get("城心生成概率"));
                candidateCount = integer(city, "候选城心数量", candidateCount);
                candidateGridSize = integer(city, "候选网格大小", candidateGridSize);
                terrainCheckEnabled = bool(city, "城心附近地形检查", terrainCheckEnabled);
                terrainCheckRadius = integer(city, "城心附近地形检查半径", terrainCheckRadius);
                centerSpacing = rangeObject(city.get("城心间隔"));
            }
            JsonObject layout = object(root, "建筑布局");
            Range buildingSpacing = defaults.buildingSpacing();
            int maxWidth = defaults.maxContinuousWidth();
            int maxLength = defaults.maxContinuousLength();
            int sameSpacing = defaults.sameBuildingSpacing();
            if (layout != null) {
                Range configuredSpacing = rangeObject(layout.get("建筑间隔"));
                if (configuredSpacing != null) buildingSpacing = configuredSpacing;
                maxWidth = Math.max(1, integer(layout, "连续生成最大宽度", maxWidth));
                maxLength = Math.max(1, integer(layout, "连续生成最大长度", maxLength));
                sameSpacing = Math.max(0, integer(layout, "相同建筑间隔", sameSpacing));
            }
            Map<String, BuildingRule> buildings = parseBuildings(object(root, "建筑"), warnings);
            return new Rules(defaults.radiusMinBlocks(), defaults.radiusMaxBlocks(), centerChance,
                    candidateCount, candidateGridSize, terrainCheckEnabled, terrainCheckRadius,
                    centerSpacing, buildingSpacing,
                    maxWidth, maxLength, sameSpacing, buildings);
        } catch (Exception e) {
            warnings.add(FILE_NAME + " 布局部分读取失败，使用默认值: " + e.getMessage());
            return defaults;
        }
    }

    private static Map<String, BuildingRule> parseBuildings(JsonObject root, List<String> warnings) {
        Map<String, BuildingRule> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry.getKey().startsWith("_") || !entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            Range spacing = rangeObject(value.get("生成间隔"));
            Double rate = decimal(value.get("刷新率"));
            if (rate == null) rate = 1.0;
            rate = Math.max(0, Math.min(1, rate));
            Integer min = integerOrNull(value.get("最少数量"));
            Integer max = integerOrNull(value.get("最大数量"));
            JsonElement count = value.get("数量");
            int[] counts = rangeInts(count);
            if (counts != null) { min = counts[0]; max = counts[1]; }
            result.put(entry.getKey(), new BuildingRule(spacing, rate, min, max));
        }
        return result;
    }

    public static void addBuildingTemplates(Path configDir, List<String> fileNames) {
        if (configDir == null || fileNames == null || fileNames.isEmpty()) return;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            boolean changed = false;
            if (!root.has("城市")) { root.add("城市", cityTemplate()); changed = true; }
            else changed |= ensureCityFields(root.getAsJsonObject("城市"));
            if (!root.has("建筑布局")) { root.add("建筑布局", layoutTemplate()); changed = true; }
            JsonObject buildings = root.has("建筑") && root.get("建筑").isJsonObject()
                    ? root.getAsJsonObject("建筑") : new JsonObject();
            for (String name : fileNames) {
                if (name == null || name.isBlank() || buildings.has(name)) continue;
                buildings.add(name, buildingTemplate());
                changed = true;
            }
            if (!root.has("建筑") || root.get("建筑") != buildings) { root.add("建筑", buildings); changed = true; }
            if (changed) Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Configuration recovery must never stop asset conversion.
        }
    }

    public static void addBuildingTemplates(Path configDir, String stageName, List<String> fileNames) {
        if (configDir == null || stageName == null || fileNames == null || fileNames.isEmpty()) return;
        Path file = configDir.resolve(CityStageConfig.FILE_NAME);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            JsonObject stage = RootConfigFile.stage(root, stageName);
            if (stage.size() == 0) return;
            JsonObject rules = stage.has("生成规则") && stage.get("生成规则").isJsonObject()
                    ? stage.getAsJsonObject("生成规则") : new JsonObject();
            JsonObject buildings = rules.has("建筑") && rules.get("建筑").isJsonObject()
                    ? rules.getAsJsonObject("建筑") : new JsonObject();
            boolean changed = false;
            for (String name : fileNames) {
                if (name != null && !name.isBlank() && !buildings.has(name)) {
                    buildings.add(name, buildingTemplate());
                    changed = true;
                }
            }
            if (!rules.has("城市")) { rules.add("城市", cityTemplate()); changed = true; }
            else changed |= ensureCityFields(rules.getAsJsonObject("城市"));
            if (!rules.has("建筑布局")) { rules.add("建筑布局", layoutTemplate()); changed = true; }
            if (!rules.has("建筑") || rules.get("建筑") != buildings) { rules.add("建筑", buildings); changed = true; }
            if (!stage.has("生成规则") || stage.get("生成规则") != rules) { stage.add("生成规则", rules); changed = true; }
            if (changed) Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Configuration recovery must never stop asset conversion.
        }
    }

    public static JsonObject template() {
        JsonObject root = new JsonObject();
        root.add("城市", cityTemplate());
        root.add("建筑布局", layoutTemplate());
        root.add("建筑", new JsonObject());
        return root;
    }

    private static JsonObject cityTemplate() {
        JsonObject o = new JsonObject();
        o.addProperty("_说明", "城市半径单位是方块；城心间隔单位是方块，填 null 表示不限制。");
        o.addProperty("城心生成概率", 1.0);
        o.addProperty("候选城心数量", 10);
        o.addProperty("候选网格大小", 1024);
        o.addProperty("城心附近地形检查", true);
        o.addProperty("城心附近地形检查半径", 50);
        JsonObject gap = new JsonObject(); gap.add("最小", com.google.gson.JsonNull.INSTANCE); gap.add("最大", com.google.gson.JsonNull.INSTANCE); o.add("城心间隔", gap);
        return o;
    }
    private static boolean ensureCityFields(JsonObject city) {
        if (city == null) return false;
        boolean changed = false;
        if (!city.has("候选城心数量")) { city.addProperty("候选城心数量", 10); changed = true; }
        if (!city.has("候选网格大小")) { city.addProperty("候选网格大小", 1024); changed = true; }
        if (!city.has("城心附近地形检查")) { city.addProperty("城心附近地形检查", true); changed = true; }
        if (!city.has("城心附近地形检查半径")) { city.addProperty("城心附近地形检查半径", 50); changed = true; }
        return changed;
    }
    private static JsonObject layoutTemplate() {
        JsonObject o = new JsonObject();
        // 未填写时由阶段道路宽度提供默认建筑间隔。
        o.addProperty("连续生成最大宽度", 2); o.addProperty("连续生成最大长度", 6); o.addProperty("相同建筑间隔", 10);
        return o;
    }
    private static JsonObject buildingTemplate() {
        JsonObject o = new JsonObject();
        JsonObject gap = new JsonObject(); gap.add("最小", com.google.gson.JsonNull.INSTANCE); gap.add("最大", com.google.gson.JsonNull.INSTANCE); o.add("生成间隔", gap);
        o.addProperty("刷新率", 1.0); o.addProperty("最少数量", 0); o.add("最大数量", com.google.gson.JsonNull.INSTANCE);
        return o;
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
    }
    private static int integer(JsonObject root, String key, int fallback) {
        Integer value = integerOrNull(root == null ? null : root.get(key));
        return value == null ? fallback : value;
    }
    private static Integer integerOrNull(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : null;
    }
    private static boolean bool(JsonObject root, String key, boolean fallback) {
        JsonElement value = root == null ? null : root.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : fallback;
    }
    private static Double decimal(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsDouble() : null;
    }
    private static int[] rangeInts(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 2) return null;
        Integer a = integerOrNull(element.getAsJsonArray().get(0));
        Integer b = integerOrNull(element.getAsJsonArray().get(1));
        if (a == null || b == null) return null;
        return a <= b ? new int[]{a, b} : new int[]{b, a};
    }
    private static Range rangeObject(JsonElement element) {
        if (element == null || !element.isJsonObject()) return new Range(null, null);
        JsonObject o = element.getAsJsonObject();
        return new Range(integerOrNull(o.get("最小")), integerOrNull(o.get("最大")));
    }
}
