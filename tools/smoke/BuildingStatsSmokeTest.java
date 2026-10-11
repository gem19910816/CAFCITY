import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.stats.BuildingStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline smoke test for the building-placement statistics (BuildingStats).
 *
 * Why this test exists: the player asked "I want to see whether all my buildings
 * are actually generated". The only thing that answers it is a per-building
 * counter that starts at 0 for EVERY building and is bumped when Lost Cities
 * picks that building. Two failure modes are invisible in the game:
 *   1. a building that is missing from the registry -> it never shows up in the
 *      "never generated" list, so a broken building looks fine;
 *   2. the runtime id shape (namespace prefix or not) differing from the
 *      registry key -> EVERYTHING would be reported as "never generated".
 * So this test pins both: the registry contents (from LcAssetGenerator.Output,
 * both single-cell and multi-cell, inner and outer, plus the sub-zone grouping)
 * and the id lookup (both "ns:id" and bare "id" must hit the same counter).
 *
 * Covered:
 *   1. install(): registry size, file name (Chinese), file path
 *   2. record(): "ns:id" and bare "id" hit the same counter; single and multi
 *      both count; unknown ids are collected separately, never dropped
 *   3. record() never throws (null / empty id)
 *   4. record() is thread safe: 8 threads x 2000 hits = exactly 16000
 *   5. report: title, total, per-zone sections with (alive/total), counts sorted
 *      descending, zero-count buildings ONLY in the "never generated" section
 *   6. report: sub-zone tag, unknown-id section
 *   7. export(): writes the file, content equals formatReport()
 *   8. re-install replaces the registry; duplicate display names get a hash suffix
 *
 * This file is intentionally ASCII-only: the Chinese strings the report has to
 * contain are written as unicode escapes inside STRING literals only (never
 * inside comments).
 */
public final class BuildingStatsSmokeTest {

    /** Report title: "building generation count statistics". */
    private static final String TITLE = "\u5efa\u7b51\u751f\u6210\u6b21\u6570\u7edf\u8ba1";
    /** "inner city". */
    private static final String ZONE_INNER = "\u5185\u57ce";
    /** "outer city". */
    private static final String ZONE_OUTER = "\u5916\u57ce";
    /** "never generated". */
    private static final String NEVER = "\u4ece\u672a\u751f\u6210\u8fc7";
    /**
     * The full section header of the never-generated list: "--- " + warning sign
     * (U+26A0 U+FE0F) + {@link #NEVER}. The sign is part of the format documented in
     * BuildingStats.formatReport(); escapes keep this file pure ASCII.
     */
    private static final String NEVER_HEADER = "--- \u26a0\ufe0f " + NEVER;
    /** "total placements". */
    private static final String TOTAL = "\u603b\u653e\u7f6e\u6b21\u6570";
    /** "times" (the counter word). */
    private static final String CI = "\u6b21";
    /** The stats file name the mod must use: "building generation stats.txt". */
    private static final String FILE_NAME = "\u5efa\u7b51\u751f\u6210\u7edf\u8ba1.txt";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Building placement statistics smoke test ===");

        // ---- 1. install: a hand-built conversion output, no building files needed ----
        Path dir = Files.createTempDirectory("caf-stats");
        LcAssetGenerator.Output out = new LcAssetGenerator.Output();
        out.buildingIds.add("gym_11111111");                              // inner, single, sub-zone shop
        out.buildingIds.add("industry_22222222");                         // inner, single, no sub-zone
        out.multiIds.add("mall_33333333");                                // inner, multi, sub-zone industry
        out.outerBuildingIds.add("house_a_44444444");                     // outer, single
        out.outerMultiIds.add("warehouse_55555555");                      // outer, multi
        out.innerSubBuildingIds.computeIfAbsent("shop", k -> new ArrayList<>()).add("gym_11111111");
        out.innerSubMultiIds.computeIfAbsent("industry", k -> new ArrayList<>()).add("mall_33333333");
        out.displayNames.put("gym_11111111", "inner\\shop\\gym.schem");
        out.displayNames.put("industry_22222222", "inner\\industry.schem");
        out.displayNames.put("mall_33333333", "inner\\industry\\mall.schem");
        out.displayNames.put("house_a_44444444", "outer\\house_a.schem");
        out.displayNames.put("warehouse_55555555", "outer\\warehouse.schem");

        BuildingStats.install(dir, out);

        check(BuildingStats.registered() == 5, "install registered all 5 buildings (actual "
                + BuildingStats.registered() + ")");
        check(BuildingStats.FILE_NAME.equals(FILE_NAME),
                "stats file name is the Chinese one the player was told about");
        check(BuildingStats.path() != null && BuildingStats.path().equals(dir.resolve(FILE_NAME)),
                "stats file path is <config dir>/" + FILE_NAME);
        check(BuildingStats.registered() == 5 && out.displayNames.size() == 5,
                "display names came from the conversion output (5 entries)");

        // ---- 2. counting: both id shapes, single and multi ----
        BuildingStats.record("caf_lc_customcity:gym_11111111", false);
        BuildingStats.record("caf_lc_customcity:gym_11111111", false);
        BuildingStats.record("caf_lc_customcity:gym_11111111", false);
        BuildingStats.record("gym_11111111", false);            // bare id must hit the same counter
        BuildingStats.record("caf_lc_customcity:mall_33333333", true);
        BuildingStats.record("caf_lc_customcity:mall_33333333", true);
        BuildingStats.record("lostcities:vanilla_thing", false);   // unknown -> separate bucket

        // ---- 3. never throws ----
        boolean threw = false;
        try {
            BuildingStats.record(null, false);
            BuildingStats.record("", true);
            BuildingStats.record(null, true);
        } catch (Throwable t) {
            threw = true;
        }
        check(!threw, "record(null/empty) does not throw");

        // ---- 4. thread safety: exact count, no lost updates ----
        int threads = 8;
        int perThread = 2000;
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                for (int n = 0; n < perThread; n++) {
                    BuildingStats.record("caf_lc_customcity:industry_22222222", false);
                }
            });
            pool.add(t);
        }
        for (Thread t : pool) {
            t.start();
        }
        for (Thread t : pool) {
            t.join();
        }

        String report = BuildingStats.formatReport();

        // total = gym 4 + mall 2 + industry 16000 + 1 unknown
        long expectedTotal = 4 + 2 + (long) threads * perThread + 1;
        check(report.startsWith("=== " + TITLE), "report starts with the title line");
        check(report.contains(TOTAL + ": " + expectedTotal),
                "total placements is exact: " + expectedTotal + " (no lost updates)");

        // ---- 5. zone sections and ordering ----
        check(report.contains("--- " + ZONE_INNER), "report has the inner-city section");
        check(report.contains("--- " + ZONE_OUTER), "report has the outer-city section");
        check(report.contains("(3/3 "), "inner section header shows 3 of 3 generated");
        check(report.contains("(0/2 "), "outer section header shows 0 of 2 generated");

        // The report lists the player-facing names (the .schem file stem), NOT the asset
        // ids: inner\industry.schem -> "industry", inner\shop\gym.schem -> "gym",
        // inner\industry\mall.schem -> "mall". Each name is looked up at its first hit,
        // which is its line in the inner-city section (counted buildings are listed only
        // there; the zero-count ones live in the never-generated section at the end).
        int idxIndustry = report.indexOf("industry");
        int idxGym = report.indexOf("gym");
        int idxMall = report.indexOf("mall");
        check(idxIndustry >= 0 && idxGym > idxIndustry,
                "inner section is sorted by count descending (16000 before 4)");
        check(idxGym > idxIndustry && idxMall > idxGym,
                "inner section is sorted by count descending (4 before 2)");

        // ---- 6. never-generated list: the core value of the report ----
        check(report.contains(NEVER_HEADER + " (2) ---"),
                "never-generated section lists exactly the 2 buildings with count 0");
        int idxNever = report.indexOf(NEVER_HEADER);
        int idxHouse = report.indexOf("house_a");
        int idxWarehouse = report.indexOf("warehouse");
        check(idxHouse > idxNever && idxWarehouse > idxNever,
                "both zero-count buildings appear inside the never-generated section");
        check(report.contains("house_a") && report.contains("0 " + CI),
                "a never-generated building is printed with 0");
        int idxOuterHeader = report.indexOf("--- " + ZONE_OUTER);
        check(idxHouse > idxNever && idxOuterHeader < idxNever,
                "zero-count buildings are NOT listed in their zone section (no duplication)");

        // ---- 7. sub-zone tag, unknown ids, alignment ----
        check(report.contains("[shop]"), "inner building with a sub-zone carries a tag");
        check(report.contains("lostcities:vanilla_thing"),
                "a building from another namespace is listed instead of being dropped");
        int gymLineStart = report.lastIndexOf('\n', idxGym) + 1;
        int gymLineEnd = report.indexOf('\n', idxGym);
        String gymLine = report.substring(gymLineStart, gymLineEnd < 0 ? report.length() : gymLineEnd);
        check(gymLine.contains("4 " + CI) && gymLine.indexOf("4 " + CI) > gymLine.indexOf("gym") + 3,
                "count column is padded away from the name: " + gymLine.trim());

        // ---- 8. export writes the very same text ----
        Path written = BuildingStats.export("smoke test");
        check(written != null && Files.isRegularFile(written), "export wrote the stats file");
        String onDisk = Files.readString(written, StandardCharsets.UTF_8);
        check(onDisk.equals(BuildingStats.formatReport()), "file content equals formatReport()");
        check(onDisk.startsWith("==="), "file starts with the header");

        // ---- 9. re-install replaces the registry; duplicate names get a hash suffix ----
        Path dir2 = Files.createTempDirectory("caf-stats-dup");
        LcAssetGenerator.Output dup = new LcAssetGenerator.Output();
        dup.buildingIds.add("a_aaaaaaaa");
        dup.buildingIds.add("b_bbbbbbbb");
        dup.displayNames.put("a_aaaaaaaa", "x\\same.schem");
        dup.displayNames.put("b_bbbbbbbb", "y\\same.schem");
        BuildingStats.install(dir2, dup);
        String r2 = BuildingStats.formatReport();
        check(BuildingStats.registered() == 2, "re-install replaces the registry (2 entries)");
        check(r2.contains("same #aaaaaaaa") && r2.contains("same #bbbbbbbb"),
                "two buildings with the same file name are told apart by a hash suffix");
        check(r2.contains(NEVER_HEADER + " (2) ---"),
                "after re-install nothing has been counted yet, so both are 'never generated'");

        System.out.println();
        System.out.println("================================");
        System.out.println("\u901a\u8fc7 " + passed + " \u9879, \u5931\u8d25 " + failed + " \u9879");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("BUILDING-STATS-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
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

    /** Keeps the compiler honest about the unused variable warnings this test avoids. */
    private static void unused(Map<String, String> ignored) {
        // no-op
    }
}
