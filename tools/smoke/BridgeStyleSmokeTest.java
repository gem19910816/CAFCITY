import com.caf.lccustomcity.config.BridgeStyleConfig;
import com.caf.lccustomcity.convert.BridgeStyleGenerator;
import com.caf.lccustomcity.convert.CityStylePatcher;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.convert.RoadStyleGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Offline smoke test for the "water-crossing street bridge restyle" feature
 * (qiao mian huan cheng wo men de lu mian).
 *
 * Background: the player saw a road crossing water whose deck was light grey
 * stone with a white centre line, sitting right next to an inner-city road that
 * already used our own mottled stone mix -- and asked for the bridge to use the
 * same materials as the inner city.
 *
 * What it checks:
 *   (0) WHICH asset the bridge is: the Lost Cities jar really does ship
 *       parts/bridge_large_open.json with "refpalette": "street_large", its deck
 *       really is 192 cells of 'l' with the two centre rows (z=7, z=8) being 'q'
 *       and the parapet 'w', and the citystyles really do select it through
 *       selectors.largebridges. That is the evidence for the whole change;
 *       if the jar ever stops matching, this test fails loudly instead of
 *       silently restyling the wrong thing.
 *   (1) with the switch ON the generated pack contains an override of
 *       lostcities/lostcities/palettes/street_large.json, both in memory and on
 *       disk after the real landing code (LcAssetGenerator.writeTo) ran;
 *   (2) the bridge deck 'l' is no longer minecraft:smooth_stone_slab[type=double]
 *       and the centre line 'q' is no longer minecraft:smooth_quartz; the deck
 *       entry is byte-for-byte the SAME palette entry that RoadStyleGenerator
 *       writes for our own inner-city road surface (road_inner 'l'), and 'q' is
 *       the same entry as road_inner 'q' -- i.e. "the same materials as the
 *       inner-city road", not merely "some nice-looking stone";
 *       'w' became a stone brick wall instead of a cobblestone wall;
 *   (3) every character OTHER than l/q/w is identical to the original jar palette
 *       both structurally and after JSON re-serialization, and the entry order is
 *       unchanged -- proven twice: on the real 2-entry palette, and on a synthetic
 *       6-entry palette with extra characters that must survive untouched;
 *   (4) the entry count is the original number plus exactly one (the appended
 *       parapet entry 'w'), and it stays "original number" when the source
 *       already defines 'w';
 *   (5) with the switch OFF nothing at all is generated (vanilla bridge look);
 *   (6) failure modes (no 'l', no 'q', a duplicated target char, palette missing,
 *       broken JSON, non-object top level, jar that is not even a zip, null jar
 *       path) write nothing and never let an exception escape -- a failed restyle
 *       must never break data pack generation;
 *   (7) THE ANTI-COLLATERAL GUARD: a census over every single part shipped in the
 *       Lost Cities jar proves that the characters we touch are used ONLY by the
 *       bridge: 'q' resolves to street_large for exactly one part in the whole
 *       jar (bridge_large_open) plus the nine street_large_* street templates,
 *       'w' resolves to street_large for exactly bridge_large_open, the region
 *       palette defines 'l' as a LADDER (so the 21 building parts that use 'l' are
 *       untouched) and does not define 'q' at all, and the citystyle patcher
 *       rewrites streetblocks.largeparts away from street_large_* so those nine
 *       templates never appear in our cities.
 *
 * Also covers the BridgeStyleConfig parsing itself (missing file -> default ON +
 * template written, explicit false honoured, broken JSON -> default ON + warning).
 *
 * This file is intentionally ASCII-only: every comment is English, and the single
 * Chinese output line is written as unicode escapes inside a STRING LITERAL only.
 * Unicode escapes must never appear in a comment, because Java expands them before
 * parsing and everything after the escape is then compiled as source text.
 *
 * Usage: java BridgeStyleSmokeTest <lostcities.jar>
 */
public class BridgeStyleSmokeTest {

    /** Where the override must land inside the generated data pack. */
    private static final String PACK_KEY = "lostcities/lostcities/palettes/street_large.json";
    /** The same path inside the Lost Cities jar (data/ prefix included). */
    private static final String JAR_KEY = "data/" + PACK_KEY;
    /** The bridge part we are restyling. */
    private static final String BRIDGE_PART = "data/lostcities/lostcities/parts/bridge_large_open.json";
    /** Prefix of every part entry inside the jar. */
    private static final String PART_PREFIX = "data/lostcities/lostcities/parts/";
    /** Prefix of every citystyle entry inside the jar. */
    private static final String CITYSTYLE_PREFIX = "data/lostcities/lostcities/citystyles/";

    /**
     * The three characters the restyle touches.
     *
     * Verified on parts/bridge_large_open.json (16x16, 4 slices, refpalette
     * street_large): deck slices are v(36) / l(192) / q(32) / w(36); z=7 and z=8
     * are the two centre-line rows, z=1..6 and z=9..14 are the deck body, z=0 and
     * z=15 are the deck edges, and 'w' makes the parapet plus the four pillars.
     */
    private static final String SURFACE_CHAR = "l";
    private static final String DIVIDER_CHAR = "q";
    private static final String PARAPET_CHAR = "w";

    /** The vanilla blocks that must be gone from the override. */
    private static final String VANILLA_SURFACE = "minecraft:smooth_stone_slab[type=double]";
    private static final String VANILLA_DIVIDER = "minecraft:smooth_quartz";
    private static final String VANILLA_PARAPET = "minecraft:cobblestone_wall";

    /** Our replacement for the parapet (the inner-city kerb is plain stone bricks). */
    private static final String OUR_PARAPET = "minecraft:stone_brick_wall";

    /** The nine street templates that share the same refpalette (must be listed). */
    private static final String[] STREET_LARGE_PARTS = {
            "street_large_all", "street_large_bend", "street_large_connector", "street_large_end",
            "street_large_full", "street_large_none", "street_large_stair", "street_large_straight",
            "street_large_t"
    };

    private static final Logger LOG = LoggerFactory.getLogger("BridgeStyleSmokeTest");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: BridgeStyleSmokeTest <lostcities.jar>");
            System.exit(2);
        }
        Path lcJar = Path.of(args[0]);
        Path tmp = Files.createTempDirectory("bridgestyle");

        System.out.println("=== Water-crossing street bridge restyle smoke test ===");

        // ================================================================
        // A. the config switch itself (config/.../qiao liang feng ge.json)
        // ================================================================
        Path dirMissing = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        boolean v1 = BridgeStyleConfig.load(dirMissing, w1);
        check(v1, "config: missing file -> default ON");
        check(BridgeStyleConfig.enabled(), "config: enabled() reflects the default ON");
        check(Files.isRegularFile(dirMissing.resolve(BridgeStyleConfig.FILE_NAME)),
                "config: a template file was written next to the missing config");
        check(w1.isEmpty(), "config: missing file is not a warning (it is the normal first run)");

        Path dirOff = tmp.resolve("off");
        Files.createDirectories(dirOff);
        Files.writeString(dirOff.resolve(BridgeStyleConfig.FILE_NAME),
                "{\"" + BridgeStyleConfig.KEY + "\": false}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        boolean v2 = BridgeStyleConfig.load(dirOff, w2);
        check(!v2, "config: explicit false in the file -> OFF");
        check(!BridgeStyleConfig.enabled(), "config: enabled() reflects OFF");
        check(w2.isEmpty(), "config: no warnings for a valid false config");

        Path dirBroken = tmp.resolve("broken");
        Files.createDirectories(dirBroken);
        Files.writeString(dirBroken.resolve(BridgeStyleConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        boolean v3 = BridgeStyleConfig.load(dirBroken, w3);
        check(v3, "config: broken JSON -> default ON");
        check(!w3.isEmpty(), "config: broken JSON recorded a warning instead of throwing");

        // ================================================================
        // 0. WHICH part is the bridge, straight out of the Lost Cities jar
        // ================================================================
        JsonObject bridgePart;
        JsonObject original;
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            bridgePart = readJson(zip, BRIDGE_PART);
            original = readJson(zip, JAR_KEY);
        }
        check(bridgePart != null, "(0) " + BRIDGE_PART + " is readable from the Lost Cities jar");
        check(original != null, "(0) " + JAR_KEY + " is readable from the Lost Cities jar");
        if (bridgePart == null || original == null) {
            finish();
            return;
        }

        // the bridge part really points at the palette we are about to override
        check("street_large".equals(bridgePart.has("refpalette")
                        ? bridgePart.get("refpalette").getAsString() : null),
                "(0) the bridge part carries refpalette=street_large (the palette we override)");

        Map<String, Integer> bc = charCounts(bridgePart);
        check(Integer.valueOf(192).equals(bc.get("l")),
                "(0) bridge deck body: l = 192 cells (got " + bc.get("l") + ")");
        check(Integer.valueOf(32).equals(bc.get("q")),
                "(0) bridge centre line: q = 32 cells = the two middle rows z=7/z=8 (got " + bc.get("q") + ")");
        check(Integer.valueOf(36).equals(bc.get("w")),
                "(0) bridge parapet/pillars: w = 36 cells (got " + bc.get("w") + ")");
        check(Integer.valueOf(36).equals(bc.get("v")),
                "(0) bridge deck edges/edge posts: v = 36 cells (got " + bc.get("v") + ")");

        JsonArray deck = bridgePart.getAsJsonArray("slices").get(0).getAsJsonArray();
        check(rowIs(deck, 7, "q") && rowIs(deck, 8, "q"),
                "(0) the two centre rows z=7/z=8 of the deck are all q (the player's white lane line)");
        boolean bodyOk = true;
        for (int z = 1; z <= 6; z++) {
            bodyOk &= rowIs(deck, z, "l");
        }
        for (int z = 9; z <= 14; z++) {
            bodyOk &= rowIs(deck, z, "l");
        }
        check(bodyOk, "(0) the deck body z=1..6 and z=9..14 is all l (the light grey surface)");
        check(rowIs(deck, 0, "v") && rowIs(deck, 15, "v"),
                "(0) z=0/z=15 are the v deck edges");

        // the vanilla materials we are about to replace
        Map<String, JsonObject> origByChar = byChar(original.getAsJsonArray("palette"));
        check(origByChar.size() == 2, "(0) the vanilla street_large palette has 2 entries (got "
                + origByChar.size() + ")");
        check(blockIs(origByChar.get(SURFACE_CHAR), VANILLA_SURFACE),
                "(0) vanilla " + SURFACE_CHAR + " = " + VANILLA_SURFACE + " (light grey stone)");
        check(blockIs(origByChar.get(DIVIDER_CHAR), VANILLA_DIVIDER),
                "(0) vanilla " + DIVIDER_CHAR + " = " + VANILLA_DIVIDER + " (white centre line)");

        // the citystyle really selects bridge_large_open through largebridges
        String largeBridge = null;
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            JsonObject common = readJson(zip, CITYSTYLE_PREFIX + "citystyle_common.json");
            if (common != null && common.has("selectors")) {
                JsonObject sel = common.getAsJsonObject("selectors");
                if (sel.has("largebridges")) {
                    largeBridge = firstSelectorValue(sel.getAsJsonArray("largebridges"));
                }
            }
        }
        check("bridge_large_open".equals(largeBridge),
                "(0) citystyle_common selectors.largebridges -> bridge_large_open (got " + largeBridge + ")");

        // ================================================================
        // C. switch OFF -> nothing at all is generated
        // ================================================================
        BridgeStyleConfig.install(false);
        LcAssetGenerator.Output outOff = new LcAssetGenerator.Output();
        int nOff = BridgeStyleGenerator.generate(lcJar, outOff, LOG);
        check(nOff == 0, "(5) switch OFF -> generator reports 0 overrides (got " + nOff + ")");
        check(!outOff.files.containsKey(PACK_KEY),
                "(5) switch OFF -> no " + PACK_KEY + " in the generated pack");
        check(outOff.files.isEmpty(),
                "(5) switch OFF -> nothing at all is generated (vanilla bridge look kept)");

        // ================================================================
        // D. switch ON -> the override, and what is inside it
        // ================================================================
        BridgeStyleConfig.install(true);
        LcAssetGenerator.Output out = new LcAssetGenerator.Output();
        int n = BridgeStyleGenerator.generate(lcJar, out, LOG);
        check(n == 1, "switch ON -> generator reports exactly 1 override written (got " + n + ")");

        String text = out.files.get(PACK_KEY);
        check(text != null, "(1) the generated pack contains the override " + PACK_KEY);
        check(out.files.size() == 1,
                "switch ON -> this generator writes nothing else (got " + out.files.size() + " file(s))");
        if (text == null) {
            finish();
            return;
        }

        // it must also survive the real landing code (staging dir + rename swap)
        Path packDir = tmp.resolve("pack");
        LcAssetGenerator.writeTo(out, packDir);
        check(Files.isRegularFile(packDir.resolve("data").resolve(PACK_KEY)),
                "(1) the override really lands on disk as data/" + PACK_KEY);

        JsonObject patched = JsonParser.parseString(text).getAsJsonObject();
        JsonArray patchedEntries = patched.getAsJsonArray("palette");
        check(patchedEntries != null, "the override has a palette array");
        if (patchedEntries == null) {
            finish();
            return;
        }

        // ---- (4) entry count: original + exactly one appended parapet entry ----
        check(patchedEntries.size() == origByChar.size() + 1,
                "(4) entry count = original + 1 (the appended w parapet entry): "
                        + patchedEntries.size() + " == " + origByChar.size() + " + 1");

        Map<String, JsonObject> newMap = byChar(patchedEntries);
        Set<String> expectedChars = new LinkedHashSet<>(origByChar.keySet());
        expectedChars.add(PARAPET_CHAR);
        check(newMap.keySet().equals(expectedChars),
                "(3) character set = original set plus w only (got " + newMap.keySet() + ")");
        List<String> wantOrder = charsInOrder(original.getAsJsonArray("palette"));
        wantOrder.add(PARAPET_CHAR);
        check(charsInOrder(patchedEntries).equals(wantOrder),
                "(3) entry order unchanged, the new entry appended at the end");

        // ---- (3) THE IMPORTANT ONE: every char other than l/q/w is verbatim identical ----
        List<String> valueDiff = new ArrayList<>();
        List<String> textDiff = new ArrayList<>();
        for (Map.Entry<String, JsonObject> en : origByChar.entrySet()) {
            String ch = en.getKey();
            if (SURFACE_CHAR.equals(ch) || DIVIDER_CHAR.equals(ch) || PARAPET_CHAR.equals(ch)) {
                continue;
            }
            JsonObject got = newMap.get(ch);
            if (got == null || !got.equals(en.getValue())) {
                valueDiff.add(ch);
                continue;
            }
            if (!got.toString().equals(en.getValue().toString())) {
                textDiff.add(ch);
            }
        }
        check(valueDiff.isEmpty(),
                "(3) every char other than l/q/w is identical to the original (differing: " + valueDiff + ")");
        check(textDiff.isEmpty(),
                "(3) ...and identical field by field after re-serialization (differing: " + textDiff + ")");

        // ---- (2) l / q / w now point at OUR materials ----
        JsonObject l = newMap.get(SURFACE_CHAR);
        JsonObject q = newMap.get(DIVIDER_CHAR);
        JsonObject w = newMap.get(PARAPET_CHAR);
        check(l != null, "(2) the override still has an entry for " + SURFACE_CHAR);
        check(q != null, "(2) the override still has an entry for " + DIVIDER_CHAR);
        check(w != null, "(2) the override has an entry for " + PARAPET_CHAR);

        if (l != null) {
            check(!mentions(l, VANILLA_SURFACE), "(2) bridge deck l is no longer " + VANILLA_SURFACE);
            check(l.has("blocks") && l.getAsJsonArray("blocks").size() > 1,
                    "(2) l became a random block mix (the same shape RoadStyleGenerator uses for streets)");
            int sum = l.has("blocks") ? weightSum(l.getAsJsonArray("blocks")) : -1;
            check(sum == 128,
                    "(2) l random weights sum to exactly 128 (Lost Cities fills BlockState[128], got "
                            + sum + ")");
            check(!l.has("block") && !l.has("variant") && !l.has("frompalette"),
                    "(2) l no longer keeps the single-block fields (Lost Cities reads block BEFORE blocks,"
                            + " so a leftover block would silently win)");
        }
        if (q != null) {
            check(!mentions(q, VANILLA_DIVIDER), "(2) bridge centre line q is no longer " + VANILLA_DIVIDER);
            check(q.has("block") && !q.has("blocks"),
                    "(2) q is a single block again (not a random mix)");
        }
        if (w != null) {
            check(!mentions(w, VANILLA_PARAPET), "(2) parapet w is no longer " + VANILLA_PARAPET);
            check(blockIs(w, OUR_PARAPET), "(2) parapet w became " + OUR_PARAPET);
            check(w.has("block") && !w.has("blocks"), "(2) parapet w is a single block");
        }

        // ---- (2) STRONGEST: identical to the inner-city road palette, entry by entry ----
        LcAssetGenerator.Output roadOut = new LcAssetGenerator.Output();
        RoadStyleGenerator.generate(lcJar, roadOut, LOG);
        String roadKey = LcAssetGenerator.NS + "/lostcities/palettes/road_inner.json";
        String roadText = roadOut.files.get(roadKey);
        check(roadText != null, "(2) RoadStyleGenerator produced the inner-city palette " + roadKey);
        if (roadText != null) {
            Map<String, JsonObject> road = byChar(
                    JsonParser.parseString(roadText).getAsJsonObject().getAsJsonArray("palette"));
            check(l != null && l.equals(road.get(SURFACE_CHAR)),
                    "(2) SURFACE: the bridge deck entry is literally the same palette entry as the"
                            + " inner-city road surface (road_inner l)");
            check(q != null && q.equals(road.get(DIVIDER_CHAR)),
                    "(2) DIVIDER: the bridge centre line entry is literally the same palette entry as"
                            + " the inner-city road centre line (road_inner q)");
        }

        // ================================================================
        // D2. a synthetic palette with extra chars: (3) gets real teeth there,
        //     because the vanilla street_large palette only has two entries.
        // ================================================================
        Path richJar = tmp.resolve("rich-lc.jar");
        String richBody = "{\"palette\":["
                + "{\"char\":\"q\",\"block\":\"minecraft:smooth_quartz\"},"
                + "{\"char\":\"b\",\"block\":\"minecraft:structure_void\"},"
                + "{\"char\":\"l\",\"block\":\"minecraft:smooth_stone_slab[type=double]\"},"
                + "{\"char\":\"_\",\"block\":\"minecraft:smooth_stone_slab[type=bottom]\"},"
                + "{\"char\":\"v\",\"variant\":\"stonebrick\",\"damaged\":\"minecraft:iron_bars\"},"
                + "{\"char\":\";\",\"block\":\"minecraft:furnace\",\"torch\":true}]}";
        writeJar(richJar, JAR_KEY, richBody);
        JsonObject richOriginal = JsonParser.parseString(richBody).getAsJsonObject();
        LcAssetGenerator.Output richOut = new LcAssetGenerator.Output();
        int richN = BridgeStyleGenerator.generate(richJar, richOut, LOG);
        check(richN == 1, "(3) synthetic 6-entry palette -> the override is generated");
        String richText = richOut.files.get(PACK_KEY);
        if (richText != null) {
            JsonArray richPatched = JsonParser.parseString(richText).getAsJsonObject()
                    .getAsJsonArray("palette");
            Map<String, JsonObject> richOrig = byChar(richOriginal.getAsJsonArray("palette"));
            Map<String, JsonObject> richNew = byChar(richPatched);
            check(richPatched.size() == richOrig.size() + 1,
                    "(4) synthetic: entry count = 6 + 1 = " + richPatched.size());
            List<String> richWant = charsInOrder(richOriginal.getAsJsonArray("palette"));
            richWant.add(PARAPET_CHAR);
            check(charsInOrder(richPatched).equals(richWant),
                    "(3) synthetic: order unchanged, w appended (got " + charsInOrder(richPatched) + ")");
            List<String> richBad = new ArrayList<>();
            for (Map.Entry<String, JsonObject> en : richOrig.entrySet()) {
                String ch = en.getKey();
                if (SURFACE_CHAR.equals(ch) || DIVIDER_CHAR.equals(ch) || PARAPET_CHAR.equals(ch)) {
                    continue;
                }
                JsonObject got = richNew.get(ch);
                if (got == null || !got.toString().equals(en.getValue().toString())) {
                    richBad.add(ch);
                }
            }
            check(richBad.isEmpty(),
                    "(3) synthetic: the 4 other chars (b/_/v/;) are verbatim identical (bad: " + richBad + ")");
            JsonObject richV = richNew.get("v");
            check(richV != null && "stonebrick".equals(richV.has("variant")
                            ? richV.get("variant").getAsString() : null)
                            && "minecraft:iron_bars".equals(richV.has("damaged")
                            ? richV.get("damaged").getAsString() : null),
                    "(3) synthetic: a variant entry with damaged keeps both fields untouched");
            JsonObject richFurnace = richNew.get(";");
            check(richFurnace != null && richFurnace.has("torch"),
                    "(3) synthetic: an entry with torch keeps its extra field");
        }

        // a palette that already defines w: replace in place, do not append a second one
        Path wJar = tmp.resolve("w-lc.jar");
        writeJar(wJar, JAR_KEY, "{\"palette\":["
                + "{\"char\":\"l\",\"block\":\"minecraft:smooth_stone_slab[type=double]\"},"
                + "{\"char\":\"w\",\"block\":\"minecraft:cobblestone_wall\"},"
                + "{\"char\":\"q\",\"block\":\"minecraft:smooth_quartz\"}]}");
        LcAssetGenerator.Output wOut = new LcAssetGenerator.Output();
        check(BridgeStyleGenerator.generate(wJar, wOut, LOG) == 1,
                "(4) a palette that already defines w is still patchable");
        String wText = wOut.files.get(PACK_KEY);
        if (wText != null) {
            JsonArray wPatched = JsonParser.parseString(wText).getAsJsonObject().getAsJsonArray("palette");
            check(wPatched.size() == 3, "(4) w already present -> entry count stays 3 (got "
                    + wPatched.size() + ")");
            check(charsInOrder(wPatched).equals(List.of("l", PARAPET_CHAR, "q")),
                    "(4) w replaced in place, order kept (got " + charsInOrder(wPatched) + ")");
            JsonObject wEntry = byChar(wPatched).get(PARAPET_CHAR);
            check(blockIs(wEntry, OUR_PARAPET), "(4) the existing w entry was restyled to " + OUR_PARAPET);
        }

        // ================================================================
        // E. failure modes: write nothing, never throw
        // (the generated pack must never be broken by a failed restyle)
        // ================================================================
        Path noLJar = tmp.resolve("no-l-lc.jar");
        writeJar(noLJar, JAR_KEY, "{\"palette\":[{\"char\":\"q\",\"block\":\"minecraft:smooth_quartz\"}]}");
        check(noOverride(noLJar), "(6) palette without l -> nothing written (never a fragmentary palette)");

        Path noQJar = tmp.resolve("no-q-lc.jar");
        writeJar(noQJar, JAR_KEY,
                "{\"palette\":[{\"char\":\"l\",\"block\":\"minecraft:smooth_stone_slab[type=double]\"}]}");
        check(noOverride(noQJar), "(6) palette without q -> nothing written");

        Path dupJar = tmp.resolve("dup-lc.jar");
        writeJar(dupJar, JAR_KEY, "{\"palette\":["
                + "{\"char\":\"l\",\"block\":\"minecraft:smooth_stone_slab[type=double]\"},"
                + "{\"char\":\"l\",\"block\":\"minecraft:stone\"},"
                + "{\"char\":\"q\",\"block\":\"minecraft:smooth_quartz\"}]}");
        check(noOverride(dupJar), "(6) duplicated l -> nothing written (structure not as expected)");

        Path noCharJar = tmp.resolve("no-char-lc.jar");
        writeJar(noCharJar, JAR_KEY, "{\"palette\":[{\"block\":\"minecraft:stone\"}]}");
        check(noOverride(noCharJar), "(6) entry without a char field -> nothing written");

        Path noPaletteJar = tmp.resolve("no-palette-lc.jar");
        writeJar(noPaletteJar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        check(noOverride(noPaletteJar),
                "(6) jar without " + JAR_KEY + " -> nothing written, no crash");

        Path badJsonJar = tmp.resolve("bad-json-lc.jar");
        writeJar(badJsonJar, JAR_KEY, "{ not json at all");
        check(noOverride(badJsonJar), "(6) broken palette JSON -> nothing written, no crash");

        Path arrayJar = tmp.resolve("array-lc.jar");
        writeJar(arrayJar, JAR_KEY, "[1,2,3]");
        check(noOverride(arrayJar), "(6) palette whose top level is not an object -> nothing written");

        Path noPaletteFieldJar = tmp.resolve("no-palette-field-lc.jar");
        writeJar(noPaletteFieldJar, JAR_KEY, "{\"chars\":[]}");
        check(noOverride(noPaletteFieldJar), "(6) object without a palette array -> nothing written");

        Path junkJar = tmp.resolve("junk.jar");
        Files.writeString(junkJar, "this is not a zip file at all", StandardCharsets.UTF_8);
        check(noOverride(junkJar), "(6) jar that is not even a zip -> nothing written, no crash");

        LcAssetGenerator.Output outNull = new LcAssetGenerator.Output();
        int nNull = BridgeStyleGenerator.generate(null, outNull, LOG);
        check(nNull == 0 && outNull.files.isEmpty(),
                "(6) null jar path -> nothing written, no crash");

        // ================================================================
        // (7) THE ANTI-COLLATERAL GUARD: census over EVERY part in the jar
        // ================================================================
        int partCount;
        Map<String, Integer> usesL = new TreeMap<>();
        Map<String, Integer> usesQ = new TreeMap<>();
        Map<String, Integer> usesW = new TreeMap<>();
        Map<String, Integer> withRef = new TreeMap<>();
        List<String> badRef = new ArrayList<>();
        try (ZipFile zip = new ZipFile(lcJar.toFile())) {
            List<? extends ZipEntry> partEntries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().startsWith(PART_PREFIX) && e.getName().endsWith(".json"))
                    .toList();
            partCount = partEntries.size();

            JsonObject paletteDefault = readJson(zip, "data/lostcities/lostcities/palettes/default.json");
            JsonObject paletteCommon = readJson(zip, "data/lostcities/lostcities/palettes/common.json");

            for (ZipEntry e : partEntries) {
                String name = e.getName().substring(PART_PREFIX.length(),
                        e.getName().length() - ".json".length());
                JsonObject part = readJson(zip, e.getName());
                if (part == null) {
                    badRef.add(name + "(unreadable)");
                    continue;
                }
                String ref = part.has("refpalette") ? part.get("refpalette").getAsString() : null;
                if (ref != null && !ref.contains(":")) {
                    // a bare name like "street_large" resolves in the lostcities namespace
                    ref = "lostcities:" + ref;
                }
                if (ref != null && ref.endsWith(":street_large")) {
                    withRef.put(name, 0);
                }
                Map<String, Integer> c = charCounts(part);
                if (c.containsKey(SURFACE_CHAR)) {
                    usesL.put(name, c.get(SURFACE_CHAR));
                }
                if (c.containsKey(DIVIDER_CHAR)) {
                    usesQ.put(name, c.get(DIVIDER_CHAR));
                }
                if (c.containsKey(PARAPET_CHAR)) {
                    usesW.put(name, c.get(PARAPET_CHAR));
                }
            }

            check(partCount == 207, "(7) the jar ships 207 parts (got " + partCount + ")");

            // every part that resolves the three chars to street_large
            Set<String> lFromStreetLarge = new LinkedHashSet<>();
            Set<String> qFromStreetLarge = new LinkedHashSet<>();
            Set<String> wFromStreetLarge = new LinkedHashSet<>();
            for (String name : withRef.keySet()) {
                if (usesL.containsKey(name)) {
                    lFromStreetLarge.add(name);
                }
                if (usesQ.containsKey(name)) {
                    qFromStreetLarge.add(name);
                }
                if (usesW.containsKey(name)) {
                    wFromStreetLarge.add(name);
                }
            }

            Set<String> expectedRef = new LinkedHashSet<>();
            expectedRef.add("bridge_large_open");
            for (String s : STREET_LARGE_PARTS) {
                expectedRef.add(s);
            }
            check(withRef.keySet().equals(expectedRef),
                    "(7) exactly the bridge + the 9 street_large_* templates use refpalette street_large"
                            + " (got " + withRef.keySet() + ")");
            check(lFromStreetLarge.equals(expectedRef),
                    "(7) 'l' resolves to street_large for the bridge + all 9 street templates"
                            + " (got " + lFromStreetLarge + ")");
            // street_large_connector is an 8-cell stub with no centre line, so it uses l but not q
            Set<String> expectedQ = new LinkedHashSet<>(expectedRef);
            expectedQ.remove("street_large_connector");
            check(qFromStreetLarge.equals(expectedQ),
                    "(7) 'q' resolves to street_large for the bridge + 8 street templates (all but the"
                            + " connector stub) (got " + qFromStreetLarge + ")");
            check(wFromStreetLarge.equals(Set.of("bridge_large_open")),
                    "(7) 'w' resolves to street_large for the BRIDGE ALONE -- the new entry cannot"
                            + " touch anything else (got " + wFromStreetLarge + ")");

            // other parts DO use l / q, but through other palettes entirely
            Set<String> othersL = new LinkedHashSet<>(usesL.keySet());
            othersL.removeAll(expectedRef);
            boolean othersLAreBuildingsOrOilrig = true;
            for (String name : othersL) {
                othersLAreBuildingsOrOilrig &= name.startsWith("building") || name.startsWith("oilrig");
            }
            check(othersL.size() == 23 && othersLAreBuildingsOrOilrig,
                    "(7) the 23 other 'l' users are the 21 building*_N parts (l = LADDER from the region"
                            + " palette) plus oilrig01/oilrig11 (refpalette oilrig) (got " + othersL + ")");
            Set<String> othersQ = new LinkedHashSet<>(usesQ.keySet());
            othersQ.removeAll(expectedRef);
            check(othersQ.equals(Set.of("oilrig01", "rails_3split")),
                    "(7) the only other 'q' users are oilrig01 (refpalette oilrig) and rails_3split"
                            + " (refpalette rails) (got " + othersQ + ")");
            boolean noW = true;
            for (String s : STREET_LARGE_PARTS) {
                noW &= !usesW.containsKey(s);
            }
            check(noW, "(7) none of the 9 street_large_* templates use 'w' at all");

            // the region palette proves the 22 building parts are untouched
            String commonL = null;
            if (paletteCommon != null) {
                JsonObject e = byChar(paletteCommon.getAsJsonArray("palette")).get(SURFACE_CHAR);
                commonL = e != null && e.has("block") ? e.get("block").getAsString() : null;
            }
            check("minecraft:ladder[facing=north]".equals(commonL),
                    "(7) the region palette (common.json) defines 'l' as a LADDER, so the 21 building"
                            + " parts are untouched by this change (got " + commonL + ")");
            if (paletteCommon != null) {
                check(!byChar(paletteCommon.getAsJsonArray("palette")).containsKey(DIVIDER_CHAR),
                        "(7) common.json does not define 'q' (nobody can get q from the region palette)");
            }
            if (paletteDefault != null) {
                Map<String, JsonObject> def = byChar(paletteDefault.getAsJsonArray("palette"));
                check(blockIs(def.get(PARAPET_CHAR), VANILLA_PARAPET),
                        "(7) the SHARED default.json still defines w = " + VANILLA_PARAPET
                                + " (we did not touch it; the bridge now gets w from street_large)");
                JsonObject defV = def.get("v");
                check(defV != null && defV.has("variant") && !defV.has("block"),
                        "(7) default.json still defines v as the stonebrick VARIANT (bridge edges and"
                                + " support pillars keep our stone-brick look)");
                check(!def.containsKey(SURFACE_CHAR),
                        "(7) default.json does not define 'l' at all");
                check(!def.containsKey(DIVIDER_CHAR),
                        "(7) default.json does not define 'q' at all");
            }
            check(badRef.isEmpty(), "(7) every part JSON was readable (bad: " + badRef + ")");

            // the only citystyle mentioning the street_large_* part names is citystyle_common
            List<String> csMentions = new ArrayList<>();
            List<? extends ZipEntry> csEntries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().startsWith(CITYSTYLE_PREFIX) && e.getName().endsWith(".json"))
                    .toList();
            for (ZipEntry e : csEntries) {
                JsonObject csObj = readJson(zip, e.getName());
                if (csObj != null && csObj.toString().contains("street_large_")) {
                    csMentions.add(e.getName().substring(CITYSTYLE_PREFIX.length()));
                }
            }
            check(csMentions.equals(List.of("citystyle_common.json")),
                    "(7) only citystyle_common.json names street_large_* parts (got " + csMentions + ")");
        }

        // and the citystyle patcher really does rewrite those names away
        LcAssetGenerator.Output csOut = new LcAssetGenerator.Output();
        List<String> patchedStyles = CityStylePatcher.patch(lcJar, csOut, LOG);
        check(patchedStyles.contains("citystyle_common.json") && patchedStyles.contains("citystyle_border.json"),
                "(7) the citystyle patcher rewrote both common and border (got " + patchedStyles.size()
                        + " styles)");
        List<String> leftover = new ArrayList<>();
        for (String style : new String[]{"citystyle_common.json", "citystyle_border.json"}) {
            String csText = csOut.files.get("lostcities/lostcities/citystyles/" + style);
            if (csText == null) {
                leftover.add(style + "(missing)");
                continue;
            }
            JsonObject streetblocks = JsonParser.parseString(csText).getAsJsonObject()
                    .getAsJsonObject("streetblocks");
            if (streetblocks == null) {
                leftover.add(style + "(no streetblocks)");
                continue;
            }
            for (String field : new String[]{"parts", "largeparts", "tertiaryparts"}) {
                JsonObject group = streetblocks.has(field) && streetblocks.get(field).isJsonObject()
                        ? streetblocks.getAsJsonObject(field) : null;
                if (group == null) {
                    leftover.add(style + "/" + field + "(missing)");
                    continue;
                }
                String dumped = group.toString();
                if (dumped.contains("street_large_")) {
                    leftover.add(style + "/" + field);
                }
                if (!dumped.contains(LcAssetGenerator.NS + ":road_")) {
                    leftover.add(style + "/" + field + "(not ours)");
                }
            }
        }
        check(leftover.isEmpty(),
                "(7) so in OUR cities the 9 street_large_* templates are no longer referenced at all"
                        + " (leftover: " + leftover + ")");

        // restore the runtime default for any test running after us
        BridgeStyleConfig.install(BridgeStyleConfig.DEFAULT);

        finish();
    }

    /**
     * Run the generator against a jar that cannot be patched and assert that it
     * wrote nothing and let no exception escape.
     */
    private static boolean noOverride(Path jar) {
        try {
            LcAssetGenerator.Output o = new LcAssetGenerator.Output();
            int n = BridgeStyleGenerator.generate(jar, o, LOG);
            return n == 0 && o.files.isEmpty();
        } catch (Throwable t) {
            System.out.println("       (an exception escaped: " + t + ")");
            return false;
        }
    }

    /** Read one UTF-8 JSON entry out of a jar; null when missing or unparsable. */
    private static JsonObject readJson(ZipFile zip, String entryName) {
        try {
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) {
                return null;
            }
            String text;
            try (InputStream in = zip.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            System.out.println("       (could not read " + entryName + ": " + e + ")");
            return null;
        }
    }

    /** Write a minimal zip holding a single entry (used to build fake Lost Cities jars). */
    private static void writeJar(Path jar, String entryName, String content) throws Exception {
        try (OutputStream os = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** char -> its raw palette entry object. */
    private static Map<String, JsonObject> byChar(JsonArray entries) {
        Map<String, JsonObject> map = new LinkedHashMap<>();
        for (JsonElement el : entries) {
            if (el.isJsonObject() && el.getAsJsonObject().has("char")) {
                JsonObject o = el.getAsJsonObject();
                map.put(o.get("char").getAsString(), o);
            }
        }
        return map;
    }

    /** The char of every entry, in file order. */
    private static List<String> charsInOrder(JsonArray entries) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : entries) {
            out.add(el.isJsonObject() && el.getAsJsonObject().has("char")
                    ? el.getAsJsonObject().get("char").getAsString() : "?");
        }
        return out;
    }

    /** How many cells of each non-space char a part uses across all its slices. */
    private static Map<String, Integer> charCounts(JsonObject part) {
        Map<String, Integer> map = new TreeMap<>();
        JsonElement slicesEl = part.get("slices");
        if (slicesEl == null || !slicesEl.isJsonArray()) {
            return map;
        }
        for (JsonElement sliceEl : slicesEl.getAsJsonArray()) {
            if (!sliceEl.isJsonArray()) {
                continue;
            }
            for (JsonElement rowEl : sliceEl.getAsJsonArray()) {
                String row = rowEl.getAsString();
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (c == ' ') {
                        continue;
                    }
                    String key = String.valueOf(c);
                    map.merge(key, 1, Integer::sum);
                }
            }
        }
        return map;
    }

    /** True when every cell of one 16-wide row of a slice is the given char. */
    private static boolean rowIs(JsonArray slice, int z, String ch) {
        if (z < 0 || z >= slice.size()) {
            return false;
        }
        String row = slice.get(z).getAsString();
        if (row.length() != 16) {
            return false;
        }
        for (int x = 0; x < 16; x++) {
            if (!ch.equals(String.valueOf(row.charAt(x)))) {
                return false;
            }
        }
        return true;
    }

    /** The first selector entry's "value". */
    private static String firstSelectorValue(JsonArray selectors) {
        for (JsonElement el : selectors) {
            if (el.isJsonObject() && el.getAsJsonObject().has("value")) {
                return el.getAsJsonObject().get("value").getAsString();
            }
        }
        return null;
    }

    /** True when the entry exists and its single "block" equals the expected id. */
    private static boolean blockIs(JsonObject entry, String expected) {
        if (entry == null || expected == null || !entry.has("block")) {
            return false;
        }
        return expected.equals(entry.get("block").getAsString());
    }

    /** True when the serialized entry mentions the given text (a block id or a damaged value). */
    private static boolean mentions(JsonObject entry, String needle) {
        return entry.toString().contains(needle);
    }

    /** Sum of the "random" weights of a blocks list. */
    private static int weightSum(JsonArray blocks) {
        int sum = 0;
        for (JsonElement el : blocks) {
            sum += el.getAsJsonObject().get("random").getAsInt();
        }
        return sum;
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }

    /**
     * Print the summary and exit (0 = all passed).
     *
     * The Chinese words ("passed N, failed M") are unicode escapes in a string
     * literal, which keeps this file pure ASCII.
     */
    private static void finish() {
        System.out.println();
        System.out.println("================================");
        System.out.println("\u901A\u8FC7 " + passed + " \u9879, \u5931\u8D25 " + failed + " \u9879");
        if (failed == 0) {
            System.out.println("BRIDGE-STYLE-TEST-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
