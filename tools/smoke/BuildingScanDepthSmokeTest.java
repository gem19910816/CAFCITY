import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.convert.LcAssetGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.slf4j.LoggerFactory;

/**
 * Offline smoke test for the building-scan fixes.
 *
 * Two real defects are pinned here:
 *
 *   1. The scanner used to be {@code Files.walk(configDir, 3)} (even shallower
 *      before that) with a SILENT filter: anything deeper than the limit was
 *      dropped without a single log line. A user with 38 buildings in a
 *      three-level layout saw "no building files found" and nothing else - the
 *      whole generation step was skipped, so the game kept the OLD building pack.
 *      Now the walk is unlimited and the only pruned subtree is the generated
 *      pack directory, so a file five levels deep MUST be found and converted.
 *
 *   2. Every skipped file must be accounted for: classified (wrong suffix /
 *      inside the generated pack / the mod's own readme+config files / unreadable),
 *      counted, and listed as a relative path in the scan report. That report must
 *      be written even when NO building was found (that is exactly when it
 *      matters), and writing it must never break the generation flow.
 *
 * It also covers the pure helpers used by the startup self-check that reports
 * duplicate / outdated mod jars (version parsing + numeric comparison).
 *
 * This file is intentionally ASCII-only: every Chinese string the report has to
 * contain is written as a unicode escape inside a STRING literal (never inside a
 * comment).
 *
 * Usage: java BuildingScanDepthSmokeTest   (no arguments, it builds its own dirs)
 */
public final class BuildingScanDepthSmokeTest {

    /** Report file name: "building scan report.txt". */
    private static final String REPORT = "\u5efa\u7b51\u626b\u63cf\u62a5\u544a.txt";
    /** "inner city". */
    private static final String INNER = "\u5185\u57ce";
    /** "outer city". */
    private static final String OUTER = "\u5916\u57ce";
    /** Sub-zone folder name "residential". */
    private static final String SUB_RES = "\u4f4f\u5b85\u533a";
    /** Sub-zone folder name "houses". */
    private static final String SUB_HOUSE = "\u6c11\u623f";
    /** Folder name "deep". */
    private static final String DEEP1 = "\u6df1";
    /** Folder name "deeper". */
    private static final String DEEP2 = "\u66f4\u6df1";
    /** The generated pack directory: "generated building pack". */
    private static final String PACK_DIR = "\u751f\u6210\u7684\u5efa\u7b51\u5305";
    /** Report label: "scan depth". */
    private static final String DEPTH_LABEL = "\u626b\u63cf\u6df1\u5ea6";
    /** Report label: "ignored files". */
    private static final String IGNORED_LABEL = "\u88ab\u5ffd\u7565\u7684\u6587\u4ef6";
    /** Report label: "candidate files scanned". */
    private static final String CANDIDATES_LABEL = "\u626b\u63cf\u8fc7\u7684\u5019\u9009\u6587\u4ef6";
    /** Ignore reason: "suffix is not .schem / .nbt". */
    private static final String REASON_SUFFIX = "\u540e\u7f00\u4e0d\u662f .schem / .nbt";
    /** Ignore reason prefix: "the mod's / the player's config files and backups (normal)". */
    private static final String REASON_OWN = "\u672c\u6a21\u7ec4/\u73a9\u5bb6\u7684\u914d\u7f6e\u6587\u4ef6\u4e0e\u5907\u4efd";
    /** Report wording "possibly misplaced" (used to prove backups are NOT counted as a problem). */
    private static final String POSSIBLY_MISPLACED = "\u53ef\u80fd\u662f\u653e\u9519\u4e86";
    /** Report label: "building files found". */
    private static final String FOUND_LABEL = "\u627e\u5230\u5efa\u7b51\u6587\u4ef6";
    /** Counter word "files" (measure word). */
    private static final String GE = "\u4e2a";
    /** Warning text: "no building files found". */
    private static final String NOT_FOUND = "\u6ca1\u6709\u627e\u5230\u4efb\u4f55\u5efa\u7b51\u6587\u4ef6";
    /** The mod's own readme name: "readme.txt". */
    private static final String OWN_README = "\u8bf4\u660e.txt";
    /** A plain backup of a config file: "chengqu_config.json.bak". */
    private static final String BAK_PLAIN = "chengqu_config.json.bak";
    /** A tagged backup: "city_gen_config.json.bak-caf". */
    private static final String BAK_CAF = "city_gen_config.json.bak-caf";
    /** A long tagged backup: "highway_style.json.bak-before-refresh". */
    private static final String BAK_LONG = "highway_style.json.bak-before-refresh";
    /** The ".old" spelling: "legacy_rules.old". */
    private static final String BAK_OLD = "legacy_rules.old";
    /** The trailing-tilde spelling: "notes.txt~" (NOT the same file as notes.txt). */
    private static final String BAK_TILDE = "notes.txt~";
    /** A building backup inside a sub-zone folder: "house.schem.bak". */
    private static final String BAK_SCHEM = "house.schem.bak";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        var log = LoggerFactory.getLogger("building-scan");

        // ==============================================================
        // A. depth is unlimited, the generated pack is pruned, skips are counted
        // ==============================================================
        System.out.println("=== Building scan smoke test ===");
        System.out.println("--- A. five levels deep + pruned pack + classified skips ---");
        Path a = Files.createTempDirectory("caf-scan-deep");
        // deepest file: <root>\inner\residential\deep\deeper\deep5.schem  (5 names deep)
        Path deep = a.resolve(INNER).resolve(SUB_RES).resolve(DEEP1).resolve(DEEP2);
        Files.createDirectories(deep);
        writeSchem(deep.resolve("deep5.schem"));
        // a normal outer-city building at the documented three-level layout
        Files.createDirectories(a.resolve(OUTER).resolve(SUB_HOUSE));
        writeSchem(a.resolve(OUTER).resolve(SUB_HOUSE).resolve("outer.schem"));
        // a .schem INSIDE the generated pack must never be treated as a building
        Path packData = a.resolve(PACK_DIR).resolve("caf_lc_customcity").resolve("data");
        Files.createDirectories(packData);
        writeSchem(packData.resolve("generated.schem"));
        // wrong-suffix files: ignored, and they must be listed by relative path
        Files.writeString(a.resolve("notes.txt"), "not a building", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(INNER).resolve(SUB_RES).resolve("backup.litematic"),
                "not a building", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(INNER).resolve(SUB_RES).resolve("config_backup.json"),
                "not a building", StandardCharsets.UTF_8);
        // the mod's own readme in the scan root: counted, but NOT reported as a problem
        Files.writeString(a.resolve(OWN_README), "own file", StandardCharsets.UTF_8);
        // Backups the mod or the player left behind: also NORMAL, never "possibly misplaced".
        // This is the real-instance noise being fixed - four such files in the live
        // config folder were reported on EVERY startup. All the name shapes the fix
        // accepts are covered here on purpose: .bak / .bak-xxx / .old / ~ /
        // a building backup inside a sub-zone folder.
        Files.writeString(a.resolve(BAK_PLAIN), "backup", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(BAK_CAF), "backup", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(BAK_LONG), "backup", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(BAK_OLD), "backup", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(BAK_TILDE), "backup", StandardCharsets.UTF_8);
        Files.writeString(a.resolve(INNER).resolve(SUB_RES).resolve(BAK_SCHEM),
                "backup", StandardCharsets.UTF_8);

        LcAssetGenerator.Output out = LcAssetGenerator.generate(a, log);

        check(out.errors.isEmpty(), "conversion had no errors: " + out.errors);
        check(out.buildingIds.size() + out.multiIds.size() == 1,
                "1 inner-city building converted (the five-levels-deep one), actual "
                        + (out.buildingIds.size() + out.multiIds.size()));
        check(out.outerBuildingIds.size() + out.outerMultiIds.size() == 1,
                "1 outer-city building converted, actual "
                        + (out.outerBuildingIds.size() + out.outerMultiIds.size()));
        check(hasDisplayName(out, "deep5.schem") && hasDisplayName(out, DEEP2),
                "the file five levels deep WAS scanned and converted (the bug being fixed)");
        check(!hasDisplayName(out, "generated.schem"),
                "a .schem inside " + PACK_DIR + " is NOT treated as a building");
        check(!hasDisplayName(out, PACK_DIR), "no building path points into " + PACK_DIR);

        Path reportFile = a.resolve(REPORT);
        check(Files.isRegularFile(reportFile), "the scan report was written next to the buildings");
        String report = Files.isRegularFile(reportFile)
                ? Files.readString(reportFile, StandardCharsets.UTF_8) : "";

        check(report.contains(FOUND_LABEL + ": 2 " + GE), "report says 2 building files were found");
        check(report.contains("--- " + INNER + " (1 " + GE + ") ---"), "report splits inner city = 1");
        check(report.contains("--- " + OUTER + " (1 " + GE + ") ---"), "report splits outer city = 1");
        check(report.contains(SUB_RES + ": 1 " + GE), "report lists the inner sub-zone with its count");
        check(report.contains(SUB_HOUSE + ": 1 " + GE), "report lists the outer sub-zone with its count");
        check(report.contains(DEPTH_LABEL), "report states the scan depth");
        check(report.contains(IGNORED_LABEL), "report has the ignored-files section");
        check(report.contains(REASON_SUFFIX), "report names the wrong-suffix reason");
        check(report.contains("notes.txt") && report.contains("backup.litematic")
                        && report.contains("config_backup.json"),
                "report lists the ignored files (.txt / .litematic / .json) by relative path");
        check(report.contains(REASON_SUFFIX + ": 3 " + GE),
                "exactly 3 files were ignored because of their suffix (the 6 backups are NOT among them)");
        check(report.contains(REASON_OWN) && !report.contains(OWN_README),
                "the mod's own file is counted but not listed (no log spam)");
        check(report.contains("3 " + GE + POSSIBLY_MISPLACED),
                "only the 3 genuinely misplaced files are called 'possibly misplaced' (backups are not)");
        check(!report.contains(BAK_PLAIN) && !report.contains(BAK_CAF) && !report.contains(BAK_LONG)
                        && !report.contains(BAK_OLD) && !report.contains(BAK_TILDE)
                        && !report.contains(BAK_SCHEM),
                "backups (.bak / .bak-caf / .bak-before-... / .old / ~ / a .schem.bak in a sub-folder) "
                        + "are counted but never listed as possibly misplaced");
        check(noWarningContains(out, BAK_PLAIN) && noWarningContains(out, BAK_TILDE)
                        && noWarningContains(out, BAK_SCHEM),
                "no warning names a backup file");
        check(report.contains(PACK_DIR), "report names the pruned subtree");
        // Candidate count must match the real tree. Two files are left out of the
        // expectation on purpose:
        //   - everything under the pruned pack dir (the scanner never visits it,
        //     so a wrong number here means pruning is broken);
        //   - the report file itself (it is written AFTER the scan, so it cannot be
        //     part of the scan's own count).
        // The zone-config template IS expected: ZoneConfig.load writes it from the
        // built-in template just before the scan runs.
        int expectedCandidates = 0;
        try (var walk = Files.walk(a)) {
            expectedCandidates = (int) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(a.resolve(PACK_DIR)))
                    .filter(p -> !p.equals(reportFile))
                    .count();
        }
        check(report.contains(CANDIDATES_LABEL + ": " + expectedCandidates + " " + GE),
                "candidate count matches the real tree with the pruned pack excluded ("
                        + expectedCandidates + ")");
        check(noWarningContains(out, NOT_FOUND), "no 'nothing found' warning when buildings exist");
        System.out.println("    report: " + reportFile);

        // ==============================================================
        // B. no buildings at all -> the report must STILL be written
        // ==============================================================
        System.out.println();
        System.out.println("--- B. no buildings: the report is the only clue the player gets ---");
        Path b = Files.createTempDirectory("caf-scan-empty");
        Files.writeString(b.resolve(OWN_README), "own file", StandardCharsets.UTF_8);
        Files.writeString(b.resolve("screenshot.png"), "not a building", StandardCharsets.UTF_8);

        LcAssetGenerator.Output outB = LcAssetGenerator.generate(b, log);

        check(outB.isEmpty(), "no buildings were registered");
        Path reportFileB = b.resolve(REPORT);
        check(Files.isRegularFile(reportFileB),
                "the scan report IS written even when no building was found");
        String reportB = Files.isRegularFile(reportFileB)
                ? Files.readString(reportFileB, StandardCharsets.UTF_8) : "";
        check(reportB.contains(DEPTH_LABEL), "empty-case report states the scan depth");
        check(reportB.contains(IGNORED_LABEL), "empty-case report has the ignored-files section");
        check(reportB.contains("--- " + INNER + " (0 " + GE + ") ---"),
                "empty-case report says inner city = 0");
        check(reportB.contains(REASON_SUFFIX + ": 1 " + GE) && reportB.contains("screenshot.png"),
                "empty-case report names the ignored file and why");
        check(hasWarningContains(outB, NOT_FOUND) && hasWarningContains(outB, REPORT),
                "the 'nothing found' warning now carries the self-check result and the report name");
        System.out.println("    report: " + reportFileB);

        // ==============================================================
        // C. startup self-check helpers (mod version / duplicate jars)
        // ==============================================================
        System.out.println();
        System.out.println("--- C. version helpers used by the startup self-check ---");
        check("1.0.42".equals(LcCustomCityMod.versionOfJarName("caf_lc_customcity-1.21.1-1.0.42.jar")),
                "the running jar name maps to a version: caf_lc_customcity-1.21.1-1.0.42.jar -> 1.0.42");
        check(LcCustomCityMod.versionOfJarName("some_other_mod-1.0.jar") == null,
                "another mod's jar name is not mistaken for ours");
        check(LcCustomCityMod.versionOfJarName(null) == null, "a null file name does not throw");
        check(LcCustomCityMod.versionOfJarName("CAF-LC-0001-1a2b3c4d.jar") == null,
                "a generated pack jar name is not mistaken for the mod itself");
        Integer older = LcCustomCityMod.compareVersions("1.0.40", "1.0.42");
        Integer same = LcCustomCityMod.compareVersions("1.0.42", "1.0.42");
        Integer newer = LcCustomCityMod.compareVersions("1.0.42", "1.0.9");
        check(older != null && older < 0, "1.0.40 is detected as older than 1.0.42");
        check(same != null && same == 0, "the same version compares equal");
        check(newer != null && newer > 0, "1.0.42 is newer than 1.0.9 (numeric, not string, compare)");
        check(LcCustomCityMod.compareVersions("dev", "1.0") == null, "an unparsable version is 'unknown'");
        check(LcAssetGenerator.SCAN_REPORT_FILE_NAME.equals(REPORT),
                "the report file name constant is the one the player is told about");
        check(LcAssetGenerator.MAX_CELLS * 16 == 256,
                "the footprint cap is 16 cells = 256x256 blocks (what the readme now says)");

        System.out.println();
        System.out.println("================================");
        System.out.println("\u901a\u8fc7 " + passed + " \u9879, \u5931\u8d25 " + failed + " \u9879");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("BUILDING-SCAN-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // assertion helpers
    // ------------------------------------------------------------------

    /** Does any converted building's stored source path (relative) contain the text? */
    private static boolean hasDisplayName(LcAssetGenerator.Output out, String text) {
        for (String p : out.displayNames.values()) {
            if (p != null && p.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean noWarningContains(LcAssetGenerator.Output out, String text) {
        return !hasWarningContains(out, text);
    }

    private static boolean hasWarningContains(LcAssetGenerator.Output out, String text) {
        for (String w : out.warnings) {
            if (w != null && w.contains(text)) {
                return true;
            }
        }
        return false;
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

    // ------------------------------------------------------------------
    // Minimal Sponge-v3 .schem writer (gzip NBT), same shape as the writer in
    // LootSmokeTest: a 4x2x4 region with a single stone block at (0,0,0) and air
    // everywhere else, i.e. a 1x1x1 single-cell building. Only the fields the
    // converter reads are written.
    // ------------------------------------------------------------------

    private static final int W = 4;
    private static final int H = 2;
    private static final int D = 4;

    private static void writeSchem(Path file) throws IOException {
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        intTag(root, "Version", 3);
        intTag(root, "DataVersion", 3955);
        shortTag(root, "Width", W);
        shortTag(root, "Height", H);
        shortTag(root, "Length", D);
        compoundHeader(root, "Blocks");
        compoundHeader(root, "Palette");
        intTag(root, "minecraft:air", 0);
        intTag(root, "minecraft:stone", 1);
        root.write(0);                       // end of Palette
        byte[] data = new byte[W * H * D];   // one varint byte per cell (palette index < 128)
        data[0] = 1;                         // (x=0,y=0,z=0) = stone, everything else = air
        byteArrayTag(root, "Data", data);
        listHeader(root, "BlockEntities", 10, 0);
        root.write(0);                       // end of Blocks
        root.write(0);                       // end of the root compound

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        fileBytes.write(10);                 // TAG_Compound
        fileBytes.write(0);                  // root name length, high byte
        fileBytes.write(0);                  // root name length, low byte (empty name)
        fileBytes.write(root.toByteArray());
        try (OutputStream os = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(os)) {
            gz.write(fileBytes.toByteArray());
        }
    }

    private static void writeName(ByteArrayOutputStream out, int type, String name) {
        out.write(type);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        out.write((n.length >>> 8) & 0xFF);
        out.write(n.length & 0xFF);
        out.write(n, 0, n.length);
    }

    private static void intTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 3, name);
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void shortTag(ByteArrayOutputStream out, String name, int v) {
        writeName(out, 2, name);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void byteArrayTag(ByteArrayOutputStream out, String name, byte[] v) {
        writeName(out, 7, name);
        int len = v.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(v, 0, v.length);
    }

    private static void listHeader(ByteArrayOutputStream out, String name, int elemType, int len) {
        writeName(out, 9, name);
        out.write(elemType);
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    private static void compoundHeader(ByteArrayOutputStream out, String name) {
        writeName(out, 10, name);
    }
}
