import com.caf.lccustomcity.config.WideRoadBlockConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the "buildings must not sit on wide roads" switch
 * (jian zhu bu neng ya dao kuan lu shang).
 *
 * Covers only the config parsing half:
 *   1. missing file -> default ON + template written;
 *   2. explicit false honored;
 *   3. broken JSON -> default ON + warning, never throws;
 *   4. wrong type -> default ON + warning.
 *
 * The band check itself (com.caf.lccustomcity.impl.WideRoadBlockRule.isWideRoadBand)
 * needs live game classes (IDimensionInfo / HierarchicalStreetPlanner) and cannot
 * be exercised offline; it is wrapped in try/catch and falls back to vanilla
 * behavior on any failure.
 *
 * This file is intentionally ASCII-only (Chinese lives in WideRoadBlockConfig
 * and is reached here through its constants).
 */
public final class WideRoadBlockSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("wideroadblock");

        System.out.println("=== Wide road block config smoke test ===");

        // ---- 1. missing file -> default ON, template written ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        boolean v1 = WideRoadBlockConfig.load(dir1, w1);
        check(v1, "missing config file -> default ON");
        check(WideRoadBlockConfig.enabled(), "enabled() reflects default ON");
        check(Files.isRegularFile(dir1.resolve(WideRoadBlockConfig.FILE_NAME)),
                "template file was written next to the missing config");

        // ---- 2. explicit false is honored ----
        Path dir2 = tmp.resolve("off");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(WideRoadBlockConfig.FILE_NAME),
                "{\"" + WideRoadBlockConfig.KEY + "\": false}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        boolean v2 = WideRoadBlockConfig.load(dir2, w2);
        check(!v2, "explicit false in config -> OFF");
        check(!WideRoadBlockConfig.enabled(), "enabled() reflects OFF");
        check(w2.isEmpty(), "no warnings for a valid false config");

        // ---- 3. broken JSON -> default ON, warning recorded, no crash ----
        Path dir3 = tmp.resolve("broken");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(WideRoadBlockConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        boolean v3 = WideRoadBlockConfig.load(dir3, w3);
        check(v3, "broken JSON -> default ON");
        check(!w3.isEmpty(), "broken JSON recorded a warning");

        // ---- 4. wrong type -> default ON, warning recorded ----
        Path dir4 = tmp.resolve("wrongtype");
        Files.createDirectories(dir4);
        Files.writeString(dir4.resolve(WideRoadBlockConfig.FILE_NAME),
                "{\"" + WideRoadBlockConfig.KEY + "\": 123}", StandardCharsets.UTF_8);
        List<String> w4 = new ArrayList<>();
        boolean v4 = WideRoadBlockConfig.load(dir4, w4);
        check(v4, "non-boolean value -> default ON");
        check(!w4.isEmpty(), "non-boolean value recorded a warning");

        // restore the runtime default for any tests running after us
        WideRoadBlockConfig.install(WideRoadBlockConfig.DEFAULT);

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("WIDE-ROAD-BLOCK-TEST-ALL-PASS");
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
