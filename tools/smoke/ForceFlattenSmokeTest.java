import com.caf.lccustomcity.config.ForceFlattenConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the "flatten everything inside city range" config parsing.
 *
 * Covered: missing file -> default ON + template written; explicit false honored;
 * explicit true honored; broken JSON -> default ON with a warning, never throws.
 *
 * Runtime foundation, vanilla structure scope and hook descriptors are checked
 * separately by CityTerrainSmokeTest against the actual 8.3.9/game jars.
 *
 * This file is intentionally ASCII-only.
 */
public final class ForceFlattenSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("flatten-test");

        System.out.println("=== Force flatten config smoke test ===");

        // ---- 1. missing file -> default ON, template written ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        boolean v1 = ForceFlattenConfig.load(dir1, w1);
        check(v1, "missing config file -> default ON");
        check(ForceFlattenConfig.enabled(), "enabled() reflects default ON");
        check(Files.isRegularFile(dir1.resolve(ForceFlattenConfig.FILE_NAME)),
                "template file was written next to the missing config");

        // ---- 2. explicit false is honored ----
        Path dir2 = tmp.resolve("off");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(ForceFlattenConfig.FILE_NAME),
                "{\"" + ForceFlattenConfig.KEY + "\": false}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        boolean v2 = ForceFlattenConfig.load(dir2, w2);
        check(!v2, "explicit false in config -> OFF");
        check(!ForceFlattenConfig.enabled(), "enabled() reflects OFF");
        check(w2.isEmpty(), "no warnings for a valid false config");

        // ---- 3. explicit true is honored ----
        Path dir3 = tmp.resolve("on");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(ForceFlattenConfig.FILE_NAME),
                "{\"" + ForceFlattenConfig.KEY + "\": true}", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        boolean v3 = ForceFlattenConfig.load(dir3, w3);
        check(v3, "explicit true in config -> ON");
        check(ForceFlattenConfig.enabled(), "enabled() reflects ON");
        check(w3.isEmpty(), "no warnings for a valid true config");

        // ---- 4. broken JSON -> default ON, warning recorded, no crash ----
        Path dir4 = tmp.resolve("broken");
        Files.createDirectories(dir4);
        Files.writeString(dir4.resolve(ForceFlattenConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w4 = new ArrayList<>();
        boolean v4 = ForceFlattenConfig.load(dir4, w4);
        check(v4, "broken JSON -> default ON");
        check(!w4.isEmpty(), "broken JSON recorded a warning");

        // ---- 5. wrong type -> default ON, warning recorded ----
        Path dir5 = tmp.resolve("wrongtype");
        Files.createDirectories(dir5);
        Files.writeString(dir5.resolve(ForceFlattenConfig.FILE_NAME),
                "{\"" + ForceFlattenConfig.KEY + "\": 123}", StandardCharsets.UTF_8);
        List<String> w5 = new ArrayList<>();
        boolean v5 = ForceFlattenConfig.load(dir5, w5);
        check(v5, "non-boolean value -> default ON");
        check(!w5.isEmpty(), "non-boolean value recorded a warning");

        // restore the runtime default for any tests running after us
        ForceFlattenConfig.install(ForceFlattenConfig.DEFAULT);

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("FORCE-FLATTEN-TEST-ALL-PASS");
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
