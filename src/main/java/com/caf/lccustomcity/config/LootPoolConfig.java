package com.caf.lccustomcity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 独立的战利品池模型；不再依赖旧城区配置。 */
public final class LootPoolConfig {
    public record LootItem(String id, int countMin, int countMax, int weight) {}

    public record LootPool(List<LootItem> items) {
        public boolean isEmpty() { return items.isEmpty(); }
        public String signature() {
            StringBuilder result = new StringBuilder();
            for (LootItem item : items) {
                result.append(item.id()).append('|').append(item.countMin()).append('-')
                        .append(item.countMax()).append('x').append(item.weight()).append(';');
            }
            return result.toString();
        }
    }

    private LootPoolConfig() {}

    public static LootPool load(Path file, List<String> warnings) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                warnings.add(file.getFileName() + " 应该是 JSON 对象，已忽略");
                return null;
            }
            JsonObject source = parsed.getAsJsonObject();
            JsonElement value = source.has("战利品池") ? source.get("战利品池") : source;
            if (!value.isJsonObject()) return null;
            JsonElement entries = value.getAsJsonObject().get("物品");
            if (entries == null || !entries.isJsonArray()) return new LootPool(List.of());
            List<LootItem> result = new ArrayList<>();
            for (JsonElement entry : entries.getAsJsonArray()) {
                if (!entry.isJsonObject()) continue;
                JsonObject item = entry.getAsJsonObject();
                String id = text(item, "物品", text(item, "id", ""));
                if (id.isBlank()) continue;
                int min = integer(item, "最少数量", integer(item, "数量", 1));
                int max = integer(item, "最多数量", min);
                int weight = Math.max(1, integer(item, "权重", 1));
                result.add(new LootItem(id, Math.max(0, min), Math.max(min, max), weight));
            }
            return new LootPool(List.copyOf(result));
        } catch (IOException | RuntimeException e) {
            warnings.add(file.getFileName() + " 读取或解析失败，已忽略: " + e.getMessage());
            return null;
        }
    }

    private static String text(JsonObject root, String key, String fallback) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject root, String key, int fallback) {
        return root.has(key) && root.get(key).isJsonPrimitive() && root.getAsJsonPrimitive(key).isNumber()
                ? root.get(key).getAsInt() : fallback;
    }
}
