package com.caf.lccustomcity.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分区目录的战利品覆盖。
 *
 * <p>目录约定：</p>
 * <pre>
 * 战利品文件/分区通用.json
 * 战利品文件/&lt;建筑文件名&gt;.json
 * </pre>
 *
 * <p>每个分区有自己的战利品目录，因此不再需要用“分区/建筑”子目录区分。
 * {@code 分区通用.json}（也兼容旧的 {@code 城市通用.json}、{@code 通用.json}）
 * 是该分区的通用池；其他 JSON 文件按建筑文件名匹配，例如
 * {@code 商店.nbt.json} 匹配 {@code 商店.nbt}。单建筑文件优先级最高，之后是
 * 分区通用。没有任何覆盖时返回 {@code null}，让根配置继续提供兼容行为。</p>
 */
public final class StageLootConfig {
    private static final List<String> CITY_FILES = List.of(
            "分区通用.json", "城市通用.json", "城市通用战利品.json", "通用.json");
    private final LootPoolConfig.LootPool city;
    private final Map<String, LootPoolConfig.LootPool> zones;
    private final Map<String, LootPoolConfig.LootPool> buildings;

    private StageLootConfig(LootPoolConfig.LootPool city,
                            Map<String, LootPoolConfig.LootPool> zones,
                            Map<String, LootPoolConfig.LootPool> buildings) {
        this.city = city;
        this.zones = Map.copyOf(zones);
        this.buildings = Map.copyOf(buildings);
    }

    public static StageLootConfig empty() {
        return new StageLootConfig(null, Map.of(), Map.of());
    }

    public static StageLootConfig load(Path stageRoot, List<String> warnings) {
        if (stageRoot == null) return empty();
        Path root = stageRoot.resolve("战利品文件");
        if (!Files.isDirectory(root)) return empty();

        LootPoolConfig.LootPool city = null;
        for (String fileName : CITY_FILES) {
            Path cityFile = root.resolve(fileName);
            if (Files.isRegularFile(cityFile)) {
                city = LootPoolConfig.load(cityFile, warnings);
                if (city != null) break;
            }
        }
        Map<String, LootPoolConfig.LootPool> zones = new LinkedHashMap<>();
        Map<String, LootPoolConfig.LootPool> buildings = new LinkedHashMap<>();

        // 新目录格式：所有配置文件直接放在当前分区的战利品文件目录中。
        // 文件名为分区通用/城市通用/通用时作为分区池，其余文件按建筑名索引。
        readFlatFiles(root, zones, buildings, warnings);

        // 兼容早期版本的目录格式。新格式优先，只有同名键不存在时才采用旧文件。
        readDirectory(root.resolve("分区"), warnings).forEach(zones::putIfAbsent);
        readDirectory(root.resolve("建筑"), warnings).forEach(buildings::putIfAbsent);
        return new StageLootConfig(city, zones, buildings);
    }

    private static void readFlatFiles(Path root, Map<String, LootPoolConfig.LootPool> zones,
                                      Map<String, LootPoolConfig.LootPool> buildings,
                                      List<String> warnings) {
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String key = normalize(fileName.substring(0, fileName.length() - 5));
                        LootPoolConfig.LootPool pool = LootPoolConfig.load(path, warnings);
                        if (pool == null) return;
                        if (CITY_FILES.stream().anyMatch(name -> name.equalsIgnoreCase(fileName))) {
                            // load() 已按优先级读取通用文件；这里按键保留为分区通用池。
                            zones.putIfAbsent(key, pool);
                        } else {
                            buildings.putIfAbsent(key, pool);
                        }
                    });
        } catch (IOException e) {
            warnings.add(root + " 扫描失败，分区战利品覆盖停用: " + e.getMessage());
        }
    }

    /**
     * 查找覆盖池。建筑名按带扩展名和不带扩展名两种键匹配，方便配置文件名保持
     * 与 NBT 文件一致或只写建筑显示名。
     */
    public LootPoolConfig.LootPool forBuilding(String zone, String subZone, String fileName) {
        LootPoolConfig.LootPool pool = lookupBuilding(fileName);
        if (pool != null) return pool;
        for (String key : zoneKeys(zone, subZone)) {
            pool = zones.get(key);
            if (pool != null) return pool;
        }
        return city;
    }

    public boolean isEmpty() {
        return city == null && zones.isEmpty() && buildings.isEmpty();
    }

    private LootPoolConfig.LootPool lookupBuilding(String fileName) {
        if (fileName == null) return null;
        String normalized = normalize(fileName);
        LootPoolConfig.LootPool pool = buildings.get(normalized);
        if (pool != null) return pool;
        int dot = normalized.lastIndexOf('.');
        return dot > 0 ? buildings.get(normalized.substring(0, dot)) : null;
    }

    private static List<String> zoneKeys(String zone, String subZone) {
        List<String> keys = new ArrayList<>();
        if (zone != null && subZone != null) {
            keys.add(normalize(zone + "/" + subZone));
            keys.add(normalize(zone + "_" + subZone));
        }
        if (subZone != null) keys.add(normalize(subZone));
        if (zone != null) keys.add(normalize(zone));
        return keys;
    }

    private static Map<String, LootPoolConfig.LootPool> readDirectory(Path dir, List<String> warnings) {
        if (!Files.isDirectory(dir)) return Map.of();
        Map<String, LootPoolConfig.LootPool> result = new LinkedHashMap<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        String key = normalize(dir.relativize(path).toString());
                        if (key.endsWith(".json")) key = key.substring(0, key.length() - 5);
                        LootPoolConfig.LootPool pool = LootPoolConfig.load(path, warnings);
                        if (pool != null) {
                            result.put(key, pool);
                            // 直接放在目录下的文件同时按文件名索引，兼容“商业区.json”写法。
                            int slash = key.lastIndexOf('/');
                            if (slash >= 0) result.putIfAbsent(key.substring(slash + 1), pool);
                        }
                    });
        } catch (IOException e) {
            warnings.add(dir + " 扫描失败，阶段战利品覆盖停用: " + e.getMessage());
        }
        return result;
    }

    private static String normalize(String value) {
        return value.replace('\\', '/').trim();
    }
}
