import com.caf.lccustomcity.config.GapConfig;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the "force one empty chunk ring between multi buildings"
 * rule (da lou zhi jian qiang zhi liu yi ge kong xi).
 *
 * Two halves:
 *   1. GapConfig parsing: missing file -> default ON + template written;
 *      explicit false honored; broken JSON -> default ON, never throws.
 *   2. The pure ring logic in com.caf.lccustomcity.impl.MultiGapRule, reached by
 *      REFLECTION because the impl package is compiled in the mixin group (after
 *      the group-A tests are compiled), so a direct reference would not compile.
 *
 * This file is intentionally ASCII-only (Chinese written as unicode escapes).
 */
public final class GapRuleSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("gaptest");

        System.out.println("=== Gap rule smoke test ===");

        // ---- 1. missing file -> default ON, template written ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        boolean v1 = GapConfig.load(dir1, w1);
        check(v1, "missing config file -> default ON");
        check(GapConfig.enabled(), "enabled() reflects default ON");
        check(Files.isRegularFile(dir1.resolve(GapConfig.FILE_NAME)),
                "template file was written next to the missing config");

        // ---- 2. explicit false is honored ----
        Path dir2 = tmp.resolve("off");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(GapConfig.FILE_NAME),
                "{\"" + GapConfig.KEY + "\": false}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        boolean v2 = GapConfig.load(dir2, w2);
        check(!v2, "explicit false in config -> OFF");
        check(!GapConfig.enabled(), "enabled() reflects OFF");
        check(w2.isEmpty(), "no warnings for a valid false config");

        // ---- 3. broken JSON -> default ON, warning recorded, no crash ----
        Path dir3 = tmp.resolve("broken");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(GapConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        boolean v3 = GapConfig.load(dir3, w3);
        check(v3, "broken JSON -> default ON");
        check(!w3.isEmpty(), "broken JSON recorded a warning");

        // ---- 4. wrong type -> default ON, warning recorded ----
        Path dir4 = tmp.resolve("wrongtype");
        Files.createDirectories(dir4);
        Files.writeString(dir4.resolve(GapConfig.FILE_NAME),
                "{\"" + GapConfig.KEY + "\": 123}", StandardCharsets.UTF_8);
        List<String> w4 = new ArrayList<>();
        boolean v4 = GapConfig.load(dir4, w4);
        check(v4, "non-boolean value -> default ON");
        check(!w4.isEmpty(), "non-boolean value recorded a warning");

        // restore the runtime default for any tests running after us
        GapConfig.install(GapConfig.DEFAULT);

        // ---- 5. ring logic via reflection (impl package, mixin-group classes) ----
        Class<?> rule = Class.forName("com.caf.lccustomcity.impl.MultiGapRule");
        Method ring = rule.getMethod("ringOccupiedGrid",
                Object[][].class, int.class, int.class, int.class, int.class);

        // 8x8 grid with one 2x2 "building" occupying (2,2),(3,2),(2,3),(3,3)
        Object[][] grid = new Object[8][8];
        Object marker = new Object();
        grid[2][2] = marker;
        grid[3][2] = marker;
        grid[2][3] = marker;
        grid[3][3] = marker;

        // a 2x2 footprint starting at (4,2): its ring touches (3,2) and (3,3)
        check((Boolean) ring.invoke(null, grid, 4, 2, 2, 2),
                "footprint touching an existing building via the ring is vetoed");
        // diagonal touch: footprint at (4,4) has (4,3),(3,4) in its ring... (3,4) empty,
        // but ring corner (3,3) is occupied -> vetoed
        check((Boolean) ring.invoke(null, grid, 4, 4, 2, 2),
                "diagonal corner contact through the ring is vetoed");
        // one full empty chunk between: footprint at (5,2) ring spans x 4..7, z 1..4 -> all empty
        check(!(Boolean) ring.invoke(null, grid, 5, 2, 2, 2),
                "one empty chunk between buildings is allowed");
        // grid edge: footprint at (0,0) with neighbor (1,0) occupied -> vetoed
        Object[][] edge = new Object[8][8];
        edge[1][0] = marker;
        check((Boolean) ring.invoke(null, edge, 0, 0, 1, 1),
                "ring check at the grid edge still sees the occupied neighbor");
        // grid edge with nothing around -> allowed (out-of-bounds ring cells are skipped)
        Object[][] empty = new Object[8][8];
        check(!(Boolean) ring.invoke(null, empty, 0, 0, 1, 1),
                "out-of-bounds ring cells at the grid edge are skipped");
        // the footprint itself is NOT checked by the ring (vanilla handles that)
        Object[][] self = new Object[8][8];
        self[3][3] = marker;
        check(!(Boolean) ring.invoke(null, self, 3, 3, 1, 1),
                "the footprint itself is not part of the ring check");

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("GAPRULE-TEST-ALL-PASS");
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
}
