package com.caf.lccustomcity.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Root-config partition definitions for one distance stage. */
public final class StagePartitionConfig {
    public static final String OVERVIEW_FILE = "城市配置总览.json";

    public record Range(int min, int max) {
        public Range {
            min = Math.max(0, min);
            max = Math.max(min, max);
        }
        public int choose(long seed, int fallback) {
            int lower = min;
            int upper = max < lower ? lower : max;
            if (upper == lower) return lower;
            long span = (long) upper - lower + 1;
            return lower + (int) Math.floorMod(seed, span);
        }
    }

    public record Partition(String name, boolean enabled, String shape, String placement, int priority,
                            int centerX, int centerZ, int innerRadius, int outerRadius,
                            int width, int length, int angleStart, int angleRange,
                            Range buildingSpacing, int maxParallelWidth, int maxParallelLength,
                            boolean fillEmptyPlots, boolean gapBetweenLargeBuildings,
                            double rowGenerationChance) {
        public Partition(String name, boolean enabled, String shape, String placement, int priority,
                        int centerX, int centerZ, int innerRadius, int outerRadius,
                        int width, int length, int angleStart, int angleRange,
                        Range buildingSpacing, int maxParallelWidth, int maxParallelLength,
                        boolean fillEmptyPlots, boolean gapBetweenLargeBuildings) {
            this(name, enabled, shape, placement, priority, centerX, centerZ, innerRadius, outerRadius,
                    width, length, angleStart, angleRange, buildingSpacing, maxParallelWidth, maxParallelLength,
                    fillEmptyPlots, gapBetweenLargeBuildings, 1.0);
        }
        public Partition {
            name = name == null ? "未命名分区" : name;
            shape = shape == null || shape.isBlank() ? "圆形" : shape;
            placement = placement == null || placement.isBlank() ? "固定" : placement;
            innerRadius = Math.max(0, innerRadius);
            outerRadius = Math.max(innerRadius, outerRadius);
            width = Math.max(1, width);
            length = Math.max(1, length);
            angleRange = Math.max(1, Math.min(360, angleRange));
            // 未填写时由阶段道路宽度提供默认建筑间隔。
            maxParallelWidth = Math.max(1, maxParallelWidth);
            maxParallelLength = Math.max(1, maxParallelLength);
            rowGenerationChance = Math.max(0, Math.min(1, rowGenerationChance));
        }
    }

    private StagePartitionConfig() {}

    public static Map<String, Partition> parse(JsonObject stage, String at, List<String> warnings) {
        Map<String, Partition> result = new LinkedHashMap<>();
        if (stage == null || !stage.has("分区") || !stage.get("分区").isJsonObject()) return result;
        JsonObject root = stage.getAsJsonObject("分区");
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String name = entry.getKey().trim();
            if (name.isBlank() || name.startsWith("_") || !entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            boolean enabled = bool(value, "启用", true);
            String shape = string(value, "形状", "圆形");
            String placement = string(value, "摆放方式", "固定");
            int priority = integer(value, "优先级", 0);
            JsonObject size = object(value, "大小");
            int centerX = integer(size, "中心X", 0);
            int centerZ = integer(size, "中心Z", 0);
            int innerRadius = integer(size, "内半径", 0);
            int outerRadius = integer(size, "外半径", 50);
            int width = integer(size, "宽度", 20);
            int length = integer(size, "长度", 20);
            int angleStart = integer(size, "角度起点", 0);
            int angleRange = integer(size, "角度范围", 90);
            JsonObject layout = object(value, "建筑布局");
            JsonObject spacing = object(layout, "建筑间隔");
            // 缺省值跟随阶段道路宽度，由 CityPlotPlan 的阶段默认间距处理。
            Range gap = null;
            if (spacing != null && !isDefault(spacing)) {
                gap = new Range(integer(spacing, "最小", 1), integer(spacing, "最大", 1));
            }
            int maxWidth = integer(layout, "并排生成最大宽度", 2);
            int maxLength = integer(layout, "并排生成最大长度", 6);
            boolean fillEmptyPlots = bool(layout, "空地也要放建筑", true);
            boolean gapBetweenLargeBuildings = bool(layout, "大楼之间强制留一格空隙", true);
            double rowGenerationChance = decimal(layout, "连排建筑生成概率", 1.0);
            result.put(name, new Partition(name, enabled, shape, placement, priority, centerX, centerZ,
                    innerRadius, outerRadius, width, length, angleStart, angleRange, gap, maxWidth, maxLength,
                    fillEmptyPlots, gapBetweenLargeBuildings, rowGenerationChance));
        }
        return Map.copyOf(result);
    }

    public static void ensureBuildingDirectories(Path configDir, List<String> styles,
                                                  List<String> stages, Map<String, Map<String, Partition>> partitions,
                                                  List<String> warnings) {
        for (String style : styles) for (String stage : stages) {
            Map<String, Partition> entries = partitions.getOrDefault(stage, Map.of());
            for (String name : entries.keySet()) {
                if (!safeName(name)) continue;
                try {
                    Path root = configDir.resolve(style).resolve(stage).resolve(name);
                    Files.createDirectories(root.resolve("建筑文件"));
                    // 战利品已经属于当前分区，直接放在该目录下，不再按“分区/建筑”二次分类。
                    Files.createDirectories(root.resolve("战利品文件"));
                } catch (IOException | RuntimeException e) {
                    warnings.add("分区建筑目录创建失败 " + style + "/" + stage + "/" + name + ": " + e.getMessage());
                }
            }
        }
    }

    /** Creates shape-specific folders for complete, player-authored road structures. */
    public static void ensureRoadDirectories(Path configDir, List<String> styles,
                                             List<String> stages, Map<String, Map<String, Partition>> partitions,
                                             List<String> warnings) {
        for (String style : styles) for (String stage : stages) {
            try {
                Path root = configDir.resolve(style).resolve(stage).resolve("道路文件");
                for (String shape : List.of("直道", "弯道", "尽头", "三岔路口", "十字路口")) {
                    Files.createDirectories(root.resolve(shape));
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("阶段道路目录创建失败 " + style + "/" + stage + ": " + e.getMessage());
            }
        }
    }

    public static void writeOverview(Path configDir, List<String> styles, List<String> stageOrder,
                                     Map<String, Map<String, Partition>> partitions, List<String> warnings) {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "自动生成的城市配置总览，仅用于查询。请修改根目录的 城市风格配置.json，不要直接修改本文件。");
        root.add("可用城市风格", array(styles));
        JsonObject stages = new JsonObject();
        for (String stage : stageOrder) {
            JsonObject value = new JsonObject();
            value.addProperty("分区数量", partitions.getOrDefault(stage, Map.of()).size());
            value.add("分区", array(new ArrayList<>(partitions.getOrDefault(stage, Map.of()).keySet())));
            stages.add(stage, value);
        }
        root.add("阶段", stages);
        try {
            Files.writeString(configDir.resolve(OVERVIEW_FILE),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            warnings.add(OVERVIEW_FILE + " 写入失败: " + e.getMessage());
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
    }
    private static String string(JsonObject root, String key, String fallback) {
        return root != null && root.has(key) && root.get(key).isJsonPrimitive()
                ? root.get(key).getAsString() : fallback;
    }
    private static int integer(JsonObject root, String key, int fallback) {
        return root != null && root.has(key) && root.get(key).isJsonPrimitive()
                && root.getAsJsonPrimitive(key).isNumber() ? root.get(key).getAsInt() : fallback;
    }
    private static double decimal(JsonObject root, String key, double fallback) {
        try { return root != null && root.has(key) && root.get(key).isJsonPrimitive()
                ? root.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean bool(JsonObject root, String key, boolean fallback) {
        return root != null && root.has(key) && root.get(key).isJsonPrimitive()
                && root.getAsJsonPrimitive(key).isBoolean() ? root.get(key).getAsBoolean() : fallback;
    }
    private static boolean isDefault(JsonObject root) {
        if (root == null || root.entrySet().isEmpty()) return true;
        for (JsonElement value : root.entrySet().stream().map(Map.Entry::getValue).toList()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || !"默认".equals(value.getAsString())) return false;
        }
        return true;
    }
    private static JsonElement array(List<String> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.forEach(array::add);
        return array;
    }
    private static boolean safeName(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\") && name.indexOf(':') < 0;
    }
}
