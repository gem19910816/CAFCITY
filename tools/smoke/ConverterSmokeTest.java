import com.caf.lccustomcity.convert.CityStylePatcher;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.LoggerFactory;

/**
 * 转换流程离线自检 —— 不启动 Minecraft, 直接用真实的建筑文件跑一遍完整转换。
 *
 * 用法: java ConverterSmokeTest <建筑目录> <失落城市jar> <输出目录>
 */
public class ConverterSmokeTest {

    public static void main(String[] args) throws Exception {
        Path buildingDir = Path.of(args[0]);
        Path lcJar = args.length > 1 && !args[1].equals("-") ? Path.of(args[1]) : null;
        Path outDir = Path.of(args[2]);

        System.out.println("=== 建筑目录 : " + buildingDir);
        System.out.println("=== 失落城市 : " + lcJar);
        System.out.println("=== 输出目录 : " + outDir);
        System.out.println();

        var log = LoggerFactory.getLogger("smoke");

        long t0 = System.currentTimeMillis();
        LcAssetGenerator.Output out = LcAssetGenerator.generate(buildingDir, log);
        long t1 = System.currentTimeMillis();

        System.out.println("--- 转换耗时 " + (t1 - t0) + " ms");
        System.out.println("--- 单格建筑 " + out.buildingIds.size() + " : " + out.buildingIds);
        System.out.println("--- 多格建筑 " + out.multiIds.size() + " : " + out.multiIds);
        System.out.println("--- part 数 " + out.partCount + ", 调色板数 " + out.paletteCount);
        for (String w : out.warnings) {
            System.out.println("--- [警告] " + w);
        }
        for (String e : out.errors) {
            System.out.println("--- [错误] " + e);
        }

        if (lcJar != null) {
            List<String> patched = CityStylePatcher.patch(lcJar, out, log);
            System.out.println("--- 覆盖的城市风格 " + patched.size() + " : " + patched);
            assertWorldStyleOverride(out, lcJar);
            assertCityStylesHaveStyle(out, lcJar);
        }

        LcAssetGenerator.writeTo(out, outDir);
        System.out.println("--- 已写出 " + out.files.size() + " 个文件到 " + outDir);

        System.out.println();
        System.out.println("=== 生成的资源清单 ===");
        out.files.keySet().stream().sorted().limit(40).forEach(k -> System.out.println("    data/" + k));

        // 抽查一个 part 的结构是否正确(不打印全部切片, 太长)
        String sampleKey = out.files.keySet().stream()
                .filter(k -> k.contains("/parts/") && !k.contains("_empty_cellar"))
                .sorted().findFirst().orElse(null);
        if (sampleKey != null) {
            String json = out.files.get(sampleKey);
            System.out.println();
            System.out.println("=== 抽查 " + sampleKey + " (长度 " + json.length() + ") ===");
            // 只打印前 60 行, 够看清 xsize/zsize/refpalette/slices 结构
            String[] lines = json.split("\n");
            for (int i = 0; i < Math.min(60, lines.length); i++) {
                System.out.println(lines[i]);
            }
            if (lines.length > 60) {
                System.out.println("    ... (共 " + lines.length + " 行)");
            }
        }

        // 抽查一个 building, 确认 filler 等字段
        String bKey = out.files.keySet().stream()
                .filter(k -> k.contains("/buildings/"))
                .sorted().findFirst().orElse(null);
        if (bKey != null) {
            System.out.println();
            System.out.println("=== 抽查 " + bKey + " ===");
            System.out.println(out.files.get(bKey));
        }

        // 抽查被覆盖的 citystyle, 确认街道配置还在、建筑列表已换
        String cKey = out.files.keySet().stream()
                .filter(k -> k.contains("citystyles/"))
                .sorted().findFirst().orElse(null);
        if (cKey != null) {
            String json = out.files.get(cKey);
            System.out.println();
            System.out.println("=== 抽查 " + cKey + " (长度 " + json.length() + ") ===");
            System.out.println("    streetblocks 还在? " + json.contains("streetblocks"));
            System.out.println("    parkblocks   还在? " + json.contains("parkblocks"));
            System.out.println("    含原版 building1 ? " + json.contains("\"building1\""));
            System.out.println("    含本模组建筑 ? " + json.contains("caf_lc_customcity:"));
            String[] lines = json.split("\n");
            for (int i = 0; i < Math.min(45, lines.length); i++) {
                System.out.println(lines[i]);
            }
        }

        System.out.println();
        System.out.println("已落盘的文件:");
        try (var walk = Files.walk(outDir)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .limit(30)
                    .forEach(p -> System.out.println("    " + outDir.relativize(p)));
        }
        System.out.println();
        System.out.println("SMOKE-TEST-DONE");
    }

    /**
     * Every citystyle JSON the pack writes (overrides AND generated sub-zone styles)
     * must carry a non-empty "style" field naming a style that actually exists in the
     * Lost Cities jar. BuildingInfo.createPalette does
     * AssetRegistries.STYLES.getOrThrow(world, cityStyle.getStyle()) which throws on
     * null, and the base citystyle_common has no "style" of its own - so any style
     * derived from it (our caf_zone_inner_* copies) used to crash chunk generation.
     */
    private static void assertCityStylesHaveStyle(LcAssetGenerator.Output out, Path lcJar) throws Exception {
        // Collect the style names that exist in the Lost Cities jar (styles/*.json).
        java.util.Set<String> knownStyles = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().startsWith("data/lostcities/lostcities/styles/")
                            && e.getName().endsWith(".json"))
                    .forEach(e -> {
                        String n = e.getName();
                        knownStyles.add(n.substring(n.lastIndexOf('/') + 1, n.length() - ".json".length()));
                    });
        }
        if (knownStyles.isEmpty()) {
            throw new IllegalStateException("no styles found in Lost Cities jar: " + lcJar);
        }

        int checked = 0;
        for (Map.Entry<String, String> en : out.files.entrySet()) {
            if (!en.getKey().contains("lostcities/citystyles/") || !en.getKey().endsWith(".json")) {
                continue;
            }
            JsonObject root = JsonParser.parseString(en.getValue()).getAsJsonObject();
            if (!root.has("style") || root.get("style").isJsonNull()
                    || root.get("style").getAsString().isBlank()) {
                throw new IllegalStateException("citystyle has no non-empty 'style' field: " + en.getKey());
            }
            String style = root.get("style").getAsString();
            if (!knownStyles.contains(style)) {
                throw new IllegalStateException("citystyle " + en.getKey()
                        + " references unknown style '" + style + "' (known: " + knownStyles + ")");
            }
            // citystyle_border must keep its own standard_border, not be forced to standard.
            if (en.getKey().endsWith("citystyle_border.json") && !style.equals("standard_border")) {
                throw new IllegalStateException("citystyle_border lost its standard_border style, got: " + style);
            }
            checked++;
        }
        if (checked == 0) {
            throw new IllegalStateException("no citystyle files found in generated pack");
        }
        System.out.println("--- citystyle style OK: " + checked + " file(s) all have a valid non-empty style");
    }

    /**
     * Verify the worldstyle override: the generated pack must contain a full copy of the
     * original lostcities worldstyles/standard.json with ONLY the five multisettings
     * fields changed: areasize=16 (= 256x256 blocks), minimum=40, maximum=80,
     * attempts=200, correctstylefactor=0.6 (multi-building density, fixes the
     * "single-building everywhere" problem). Every other field must be identical to
     * the original file from the Lost Cities jar (a datapack override replaces the
     * whole file, so dropping a field would silently reset it to defaults).
     */
    private static void assertWorldStyleOverride(LcAssetGenerator.Output out, Path lcJar) throws Exception {
        String packKey = "lostcities/lostcities/worldstyles/standard.json";
        String jarKey = "data/" + packKey;
        String text = out.files.get(packKey);
        if (text == null) {
            throw new IllegalStateException("worldstyle override missing from generated pack: " + packKey);
        }
        JsonObject patched = JsonParser.parseString(text).getAsJsonObject();

        String origText;
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            ZipEntry e = zip.getEntry(jarKey);
            if (e == null) {
                throw new IllegalStateException(jarKey + " not found in " + lcJar);
            }
            try (InputStream in = zip.getInputStream(e)) {
                origText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        JsonObject original = JsonParser.parseString(origText).getAsJsonObject();

        // areasize must be 16, plus the multi-building density overrides
        JsonObject ms = patched.getAsJsonObject("multisettings");
        if (ms == null) {
            throw new IllegalStateException("patched worldstyle has no multisettings");
        }
        int area = ms.get("areasize").getAsInt();
        if (area != 16) {
            throw new IllegalStateException("patched worldstyle areasize = " + area + ", expected 16");
        }
        assertMultiSetting(ms, "minimum", 40.0);
        assertMultiSetting(ms, "maximum", 80.0);
        assertMultiSetting(ms, "attempts", 200.0);
        assertMultiSetting(ms, "correctstylefactor", 0.6);

        // top-level key sets must be identical (nothing dropped, nothing invented)
        if (!patched.keySet().equals(original.keySet())) {
            throw new IllegalStateException("worldstyle top-level keys differ: patched="
                    + patched.keySet() + " original=" + original.keySet());
        }
        // every top-level field except multisettings must be byte-identical (as JSON)
        for (Map.Entry<String, JsonElement> en : original.entrySet()) {
            if (en.getKey().equals("multisettings")) {
                continue;
            }
            if (!patched.get(en.getKey()).equals(en.getValue())) {
                throw new IllegalStateException("worldstyle field '" + en.getKey() + "' differs from the original");
            }
        }
        // inside multisettings, everything except the five overridden fields must be identical
        java.util.Set<String> allowedChanged = java.util.Set.of(
                "areasize", "minimum", "maximum", "attempts", "correctstylefactor");
        JsonObject origMs = original.getAsJsonObject("multisettings");
        if (!ms.keySet().equals(origMs.keySet())) {
            throw new IllegalStateException("multisettings keys differ: patched="
                    + ms.keySet() + " original=" + origMs.keySet());
        }
        for (Map.Entry<String, JsonElement> en : origMs.entrySet()) {
            if (allowedChanged.contains(en.getKey())) {
                continue;
            }
            if (!ms.get(en.getKey()).equals(en.getValue())) {
                throw new IllegalStateException("multisettings field '" + en.getKey() + "' differs from the original");
            }
        }
        System.out.println("--- worldstyle override OK: areasize=16, minimum=40, maximum=80, attempts=200,"
                + " correctstylefactor=0.6, all other fields identical to the original");
    }

    private static void assertMultiSetting(JsonObject ms, String key, double expected) {
        if (!ms.has(key)) {
            throw new IllegalStateException("patched worldstyle multisettings." + key + " is missing");
        }
        double actual = ms.get(key).getAsDouble();
        if (actual != expected) {
            throw new IllegalStateException("patched worldstyle multisettings." + key
                    + " = " + actual + ", expected " + expected);
        }
    }
}
