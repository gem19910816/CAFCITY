import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.convert.LcAssetGenerator;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.slf4j.LoggerFactory;

/**
 * Count-limit single/multi split offline self-check.
 *
 * Regression test for the chunk-gen crash:
 *   getRandomBuilding (single-cell selector) returned a MULTI-cell building id,
 *   Lost Cities looked it up in the buildings registry, got null, and crashed in
 *   Building.<init> (BuildingRE.getRegistryName() NPE).
 * Root cause: count limits for single-cell and multi-cell buildings were stored
 * in ONE mixed table, so the single-cell lower-bound logic could force-return a
 * multi-cell id.
 *
 * This test builds a zone that has BOTH a single-cell and a multi-cell building,
 * both with explicit count limits, runs the real converter, and asserts:
 *   1. the single-cell limit table contains ONLY the single-cell building id
 *   2. the multi-cell limit table contains ONLY the multi-cell building id
 *   3. after install(), BuildingCountLimits.forStyleSingle() can NEVER hand a
 *      multi-cell id to the single-cell path (and vice versa)
 *
 * NOTE: this file is intentionally ASCII-only. Chinese config keys are written
 * as unicode escape sequences.
 *
 * Usage: java CountLimitSplitSmokeTest <sandbox-dir>
 */
public class CountLimitSplitSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]);
        var log = LoggerFactory.getLogger("countsplit");

        System.out.println("=== Count-limit single/multi split self-check ===");

        // Chinese keys used by ZoneConfig (kept as escapes to stay ASCII):
        //   \u57ce\u533a\u914d\u7f6e.json / \u57ce\u533a / \u5185\u57ce / \u5206\u533a / \u5efa\u7b51 / \u6570\u91cf
        String configFileName = "\u57ce\u533a\u914d\u7f6e.json";
        String kZones = "\u57ce\u533a";
        String kInner = "\u5185\u57ce";
        String kSub = "\u5206\u533a";
        String kBuildings = "\u5efa\u7b51";
        String kCount = "\u6570\u91cf";

        // ============================================================
        // A. One sub-zone with a single-cell AND a multi-cell building,
        //    both with explicit count limits.
        // ============================================================
        Path cfg = sandbox.resolve("a/buildings_cfg");
        Files.createDirectories(cfg.resolve("shopzone"));

        // smallshop: 4x2x4 -> 1x1 cells -> single-cell building
        writeSchem(cfg.resolve("shopzone/smallshop.schem"), 4, 2, 4);
        // bighall: 20x2x4 -> 2x1 cells -> multi-cell building
        writeSchem(cfg.resolve("shopzone/bighall.schem"), 20, 2, 4);

        String configJson = "{"
                + "\"" + kZones + "\": {"
                + "  \"" + kInner + "\": { \"" + kSub + "\": {"
                + "    \"shopzone\": { \"" + kBuildings + "\": {"
                + "      \"smallshop.schem\": { \"" + kCount + "\": [1, 5] },"
                + "      \"bighall.schem\":   { \"" + kCount + "\": [2, 6] }"
                + "    } }"
                + "  } }"
                + "} }";
        Files.writeString(cfg.resolve(configFileName), configJson, StandardCharsets.UTF_8);

        LcAssetGenerator.Output out = LcAssetGenerator.generate(cfg, log);
        check(out.errors.isEmpty(), "A: conversion had no errors: " + out.errors);
        // Guard: a malformed hand-written config JSON would be swallowed by
        // ZoneConfig.load into a warning + defaults, which then fails the value
        // checks far away from the real cause. Fail fast and close instead.
        check(out.warnings.stream().noneMatch(w -> w.contains("\u57ce\u533a\u914d\u7f6e")),
                "A: config file parsed cleanly (no \u57ce\u533a\u914d\u7f6e warning): " + out.warnings);
        check(out.buildingIds.size() == 1, "A: exactly 1 single-cell building [actual " + out.buildingIds.size() + "]");
        check(out.multiIds.size() == 1, "A: exactly 1 multi-cell building [actual " + out.multiIds.size() + "]");

        String singleId = "caf_lc_customcity:" + out.buildingIds.get(0);
        String multiId = "caf_lc_customcity:" + out.multiIds.get(0);
        System.out.println("    single id = " + singleId);
        System.out.println("    multi  id = " + multiId);

        // ---- the converter must have split the limits into the right tables ----
        check(out.countLimitsSingle.size() == 1, "A: exactly 1 style has single-cell limits");
        check(out.countLimitsMulti.size() == 1, "A: exactly 1 style has multi-cell limits");
        String style = out.countLimitsSingle.keySet().iterator().next();
        check(style.startsWith("caf_zone_inner_"), "A: style is the sub-zone style (caf_zone_inner_*): " + style);
        check(out.countLimitsMulti.containsKey(style), "A: same style key also present in the multi table");

        Map<String, BuildingCountLimits.Limit> singleMap = out.countLimitsSingle.get(style);
        Map<String, BuildingCountLimits.Limit> multiMap = out.countLimitsMulti.get(style);
        check(singleMap.containsKey(singleId), "A: single table holds the single-cell id");
        check(!singleMap.containsKey(multiId),
                "A: single table does NOT hold the multi-cell id (crash regression)");
        check(multiMap.containsKey(multiId), "A: multi table holds the multi-cell id");
        check(!multiMap.containsKey(singleId),
                "A: multi table does NOT hold the single-cell id");
        check(singleMap.get(singleId).min() == 1 && singleMap.get(singleId).max() == 5,
                "A: single-cell limit values are [1,5]");
        check(multiMap.get(multiId).min() == 2 && multiMap.get(multiId).max() == 6,
                "A: multi-cell limit values are [2,6]");

        // ---- runtime table: single path can never see the multi id ----
        BuildingCountLimits.install(out.countLimitsSingle, out.countLimitsMulti);
        check(BuildingCountLimits.forStyleSingle(style).containsKey(singleId),
                "A: runtime forStyleSingle sees the single-cell id");
        check(!BuildingCountLimits.forStyleSingle(style).containsKey(multiId),
                "A: runtime forStyleSingle NEVER returns the multi-cell id");
        check(BuildingCountLimits.forStyleMulti(style).containsKey(multiId),
                "A: runtime forStyleMulti sees the multi-cell id");
        check(!BuildingCountLimits.forStyleMulti(style).containsKey(singleId),
                "A: runtime forStyleMulti NEVER returns the single-cell id");
        check(BuildingCountLimits.forStyleSingle("no_such_style").isEmpty(),
                "A: unknown style -> empty single map (not null)");
        check(BuildingCountLimits.forStyleMulti(null).isEmpty(),
                "A: null style -> empty multi map (not null)");
        check(BuildingCountLimits.totalConfigured() == 2,
                "A: totalConfigured counts both tables [actual " + BuildingCountLimits.totalConfigured() + "]");

        // ============================================================
        // B. Buildings at the config root (no sub-zone) -> citystyle_common,
        //    default AT_LEAST_ONE count; the split must still hold.
        // ============================================================
        Path cfgB = sandbox.resolve("b/buildings_cfg");
        Files.createDirectories(cfgB);
        writeSchem(cfgB.resolve("tiny.schem"), 4, 2, 4);
        writeSchem(cfgB.resolve("wide.schem"), 4, 2, 20);
        LcAssetGenerator.Output outB = LcAssetGenerator.generate(cfgB, log);
        check(outB.errors.isEmpty(), "B: conversion had no errors: " + outB.errors);
        check(outB.buildingIds.size() == 1 && outB.multiIds.size() == 1,
                "B: 1 single + 1 multi building");
        String singleB = "caf_lc_customcity:" + outB.buildingIds.get(0);
        String multiB = "caf_lc_customcity:" + outB.multiIds.get(0);
        Map<String, BuildingCountLimits.Limit> commonSingle = outB.countLimitsSingle.get("citystyle_common");
        Map<String, BuildingCountLimits.Limit> commonMulti = outB.countLimitsMulti.get("citystyle_common");
        check(commonSingle != null && commonSingle.containsKey(singleB) && !commonSingle.containsKey(multiB),
                "B: citystyle_common single table has only the single-cell id");
        check(commonMulti != null && commonMulti.containsKey(multiB) && !commonMulti.containsKey(singleB),
                "B: citystyle_common multi table has only the multi-cell id");

        BuildingCountLimits.install(outB.countLimitsSingle, outB.countLimitsMulti);
        check(!BuildingCountLimits.forStyleSingle("citystyle_common").containsKey(multiB),
                "B: runtime single path can never see the multi id (citystyle_common)");
        check(!BuildingCountLimits.forStyleMulti("citystyle_common").containsKey(singleB),
                "B: runtime multi path can never see the single id (citystyle_common)");

        // ============================================================
        // C. Empty install -> everything empty, never null
        // ============================================================
        BuildingCountLimits.install(Map.of(), Map.of());
        check(BuildingCountLimits.forStyleSingle(style).isEmpty()
                        && BuildingCountLimits.forStyleMulti(style).isEmpty(),
                "C: empty install -> both tables empty");
        check(BuildingCountLimits.totalConfigured() == 0, "C: totalConfigured is 0");

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("COUNT-LIMIT-SPLIT-TEST-ALL-PASS");
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    // ==================================================================
    // Minimal .schem writer (Sponge v3, gzip NBT), solid stone, w x h x d.
    // ==================================================================

    private static void writeSchem(Path file, int w, int h, int d) throws Exception {
        String stone = "minecraft:stone";
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put(stone, 1);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < w * h * d; i++) {
            writeVarint(data, 1);   // every cell is stone
        }

        ByteArrayOutputStream paletteTag = new ByteArrayOutputStream();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            writeIntTag(paletteTag, e.getKey(), e.getValue());
        }
        paletteTag.write(0);

        ByteArrayOutputStream blocksTag = new ByteArrayOutputStream();
        writeCompoundHeader(blocksTag, "Palette");
        blocksTag.write(paletteTag.toByteArray());
        writeByteArrayTag(blocksTag, "Data", data.toByteArray());
        writeListHeader(blocksTag, "BlockEntities", 10, 0);
        blocksTag.write(0);

        ByteArrayOutputStream root = new ByteArrayOutputStream();
        writeIntTag(root, "Version", 3);
        writeIntTag(root, "DataVersion", 3955);
        writeShortTag(root, "Width", w);
        writeShortTag(root, "Height", h);
        writeShortTag(root, "Length", d);
        writeCompoundHeader(root, "Blocks");
        root.write(blocksTag.toByteArray());
        root.write(0);

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.write(10);        // TAG_Compound
        fileBytes.write(0);
        fileBytes.write(0);         // empty root name
        fileBytes.write(root.toByteArray());
        try (OutputStream os = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(os)) {
            gz.write(fileBytes.toByteArray());
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static void writeName(ByteArrayOutputStream out, int type, String name) {
        out.write(type);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        out.write((n.length >>> 8) & 0xFF);
        out.write(n.length & 0xFF);
        out.write(n, 0, n.length);
    }

    private static void writeIntTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 3, name);
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeShortTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 2, name);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeByteArrayTag(ByteArrayOutputStream out, String name, byte[] v) {
        writeName(out, 7, name);
        int len = v.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(v, 0, v.length);
    }

    private static void writeListHeader(ByteArrayOutputStream out, String name, int elemType, int len) {
        writeName(out, 9, name);
        out.write(elemType);
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    private static void writeCompoundHeader(ByteArrayOutputStream out, String name) {
        writeName(out, 10, name);
    }
}
