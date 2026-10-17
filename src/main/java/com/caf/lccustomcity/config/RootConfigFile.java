package com.caf.lccustomcity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Small reader for the single root city configuration document. */
public final class RootConfigFile {
    private RootConfigFile() {}

    public static JsonObject read(Path configDir, List<String> warnings) {
        if (configDir == null) return new JsonObject();
        Path file = configDir.resolve(CityStageConfig.FILE_NAME);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            warnings.add(CityStageConfig.FILE_NAME + " 根节点应该是对象");
        } catch (IOException | RuntimeException e) {
            warnings.add(CityStageConfig.FILE_NAME + " 读取失败: " + e.getMessage());
        }
        return new JsonObject();
    }

    public static JsonObject stage(JsonObject root, String stageName) {
        if (root == null || stageName == null || !root.has("阶段") || !root.get("阶段").isJsonObject()) {
            return new JsonObject();
        }
        JsonObject stages = root.getAsJsonObject("阶段");
        return stages.has(stageName) && stages.get(stageName).isJsonObject()
                ? stages.getAsJsonObject(stageName) : new JsonObject();
    }

    public static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }
}
