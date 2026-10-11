import com.caf.lccustomcity.config.ScatteredMarginConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the "scattered (wilderness) buildings only spawn N blocks
 * away from cities" config parsing.
 *
 * Covered: missing file -> default 200 + template written; explicit 0 -> feature off;
 * broken JSON -> warning + default; negative -> treated as 0 with a warning;
 * normal value -> read back as-is.
 *
 * NOT covered: the geometry logic in com.caf.lccustomcity.impl.ScatteredMargin,
 * because it depends on game classes (IDimensionInfo, City, BuildingInfo) that
 * cannot run offline. That part must be verified in-game.
 *
 * This file is intentionally ASCII-only.
 */
public final class ScatteredMarginSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("margin-test");

        System.out.println("=== Scattered margin config smoke test ===");

        // ---- 1. missing file -> default 200, template written ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        int v1 = ScatteredMarginConfig.load(dir1, w1);
        check(v1 == 200, "missing config file -> default 200");
        check(ScatteredMarginConfig.distance() == 200, "distance() reflects default 200");
        check(Files.isRegularFile(dir1.resolve(ScatteredMarginConfig.FILE_NAME)),
                "template file was written next to the missing config");

        // ---- 2. explicit 0 -> feature off ----
        Path dir2 = tmp.resolve("off");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(ScatteredMarginConfig.FILE_NAME),
                "{\"" + ScatteredMarginConfig.KEY + "\": 0}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        int v2 = ScatteredMarginConfig.load(dir2, w2);
        check(v2 == 0, "explicit 0 in config -> feature off");
        check(ScatteredMarginConfig.distance() == 0, "distance() reflects 0");
        check(w2.isEmpty(), "no warnings for a valid 0 config");

        // ---- 3. broken JSON -> default 200, warning recorded, no crash ----
        Path dir3 = tmp.resolve("broken");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(ScatteredMarginConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        int v3 = ScatteredMarginConfig.load(dir3, w3);
        check(v3 == 200, "broken JSON -> default 200");
        check(!w3.isEmpty(), "broken JSON recorded a warning");

        // ---- 4. negative -> treated as 0, warning recorded ----
        Path dir4 = tmp.resolve("negative");
        Files.createDirectories(dir4);
        Files.writeString(dir4.resolve(ScatteredMarginConfig.FILE_NAME),
                "{\"" + ScatteredMarginConfig.KEY + "\": -50}", StandardCharsets.UTF_8);
        List<String> w4 = new ArrayList<>();
        int v4 = ScatteredMarginConfig.load(dir4, w4);
        check(v4 == 0, "negative value -> treated as 0");
        check(!w4.isEmpty(), "negative value recorded a warning");

        // ---- 5. normal value -> read back as-is ----
        Path dir5 = tmp.resolve("normal");
        Files.createDirectories(dir5);
        Files.writeString(dir5.resolve(ScatteredMarginConfig.FILE_NAME),
                "{\"" + ScatteredMarginConfig.KEY + "\": 512}", StandardCharsets.UTF_8);
        List<String> w5 = new ArrayList<>();
        int v5 = ScatteredMarginConfig.load(dir5, w5);
        check(v5 == 512, "normal value 512 read back as-is");
        check(ScatteredMarginConfig.distance() == 512, "distance() reflects 512");
        check(w5.isEmpty(), "no warnings for a valid positive config");

        // ---- 6. wrong type -> default 200, warning recorded ----
        Path dir6 = tmp.resolve("wrongtype");
        Files.createDirectories(dir6);
        Files.writeString(dir6.resolve(ScatteredMarginConfig.FILE_NAME),
                "{\"" + ScatteredMarginConfig.KEY + "\": true}", StandardCharsets.UTF_8);
        List<String> w6 = new ArrayList<>();
        int v6 = ScatteredMarginConfig.load(dir6, w6);
        check(v6 == 200, "non-number value -> default 200");
        check(!w6.isEmpty(), "non-number value recorded a warning");

        // restore the runtime default for any tests running after us
        ScatteredMarginConfig.install(ScatteredMarginConfig.DEFAULT);

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("SCATTERED-MARGIN-TEST-ALL-PASS");
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
