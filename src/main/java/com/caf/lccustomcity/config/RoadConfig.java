package com.caf.lccustomcity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stage-level road width; the road scenes themselves are complete NBT files. */
public record RoadConfig(int minWidth, int maxWidth, Map<String, Double> weights) {
    public static final int DEFAULT_WIDTH = 1;

    public RoadConfig {
        minWidth = Math.max(1, Math.min(32, minWidth));
        maxWidth = minWidth;
        weights = weights == null ? Map.of() : Map.copyOf(weights);
    }

    public int width() {
        return minWidth;
    }

    public static RoadConfig defaults() {
        return new RoadConfig(DEFAULT_WIDTH, DEFAULT_WIDTH, Map.of());
    }

    public static RoadConfig parse(JsonObject root) {
        RoadConfig defaults = defaults();
        if (root == null) return defaults;
        int legacyWidth = number(root.get("道路宽度"), defaults.width());
        int minWidth = number(root.get("道路最小宽度"), legacyWidth);
        int maxWidth = number(root.get("道路最大宽度"), minWidth);
        Map<String, Double> weights = new LinkedHashMap<>();
        JsonElement configured = root.get("道路生成权重");
        if (configured != null && configured.isJsonObject()) {
            for (var entry : configured.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    double value = entry.getValue().getAsDouble();
                    if (Double.isFinite(value) && value >= 0) weights.put(entry.getKey(), value);
                }
            }
        }
        return new RoadConfig(minWidth, maxWidth, weights);
    }

    public static JsonObject template() {
        JsonObject root = new JsonObject();
        root.addProperty("_说明", "每个阶段只使用一种道路宽度。完整道路场景（含路面、街景和装饰）放在对应阶段的道路文件/形状目录中，以 .nbt 导出。");
        root.addProperty("道路宽度", DEFAULT_WIDTH);
        root.add("道路生成权重", new JsonObject());
        root.addProperty("_文件夹说明", "道路文件/直道、弯道、尽头、三岔路口、十字路口；将对应形状的完整道路 NBT 放进对应文件夹。没有文件的形状不会使用其他形状代替。");
        return root;
    }

    private static int number(JsonElement element, int fallback) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }
}
