import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.config.ZoneConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline smoke test: wiring the two dead ZoneConfig fields (city radius min/max
 * in CHUNKS, outer ratio range) into the forced Lost Cities profile values
 * CITY_MINRADIUS / CITY_MAXRADIUS (blocks) and CITY_STYLE_THRESHOLD (outer-city
 * threshold) via LcCustomCityMod.citySizeOverrides / outerThresholdFromShare.
 *
 * Covers:
 *   1. missing file / missing city section / missing keys -> defaults
 *      (50/50 chunks = 800/800 blocks, threshold 0.4), never crashes
 *   2. normal values (15/25 + [0.40,0.475]) -> 240/400 blocks, threshold 0.4
 *      (an explicit config value still wins over the default)
 *   3. the conversion formula th = 1 - sqrt(0.64*(1-share)) (float tolerance)
 *   4. clamping: extreme shares (0.0 / 0.99 / negative / over 1) stay in
 *      [0.21,0.95] and strictly above 0.2 (never the -1 "outer city off" value)
 *   5. min radius greater than max radius -> defaults plus warning
 *   6. broken JSON / wrong types -> defaults plus warning
 *   7. chunk to block conversion: 15 -> 240, 25 -> 400
 *
 * This file is intentionally ASCII-only: the Chinese JSON keys are written as
 * unicode escapes inside STRING literals only (never inside comments).
 */
public final class CitySizeConfigSmokeTest {

    // JSON keys (Chinese, as escapes): city section, radius-min, radius-max, outer-ratio
    private static final String K_CITY  = "\u57ce\u5e02";
    private static final String K_RMIN  = "\u6700\u5c0f\u534a\u5f84";
    private static final String K_RMAX  = "\u6700\u5927\u534a\u5f84";
    private static final String K_OUTER = "\u5916\u57ce\u5360\u6bd4";

    private static final double EPS = 1e-4;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("citysize");

        System.out.println("=== City size / outer ratio config smoke test ===");

        // ---- 1. missing file -> defaults, template written, no crash ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        ZoneConfig z1 = ZoneConfig.load(dir1, w1);
        check(z1.radiusMin == 50 && z1.radiusMax == 50,
                "missing file -> default radius 50/50 chunks");
        Map<String, Object> o1 = LcCustomCityMod.citySizeOverrides(z1);
        check(o1.get("CITY_MINRADIUS").equals(800) && o1.get("CITY_MAXRADIUS").equals(800),
                "missing file -> 800/800 blocks");
        check(close(((Number) o1.get("CITY_STYLE_THRESHOLD")).floatValue(), 0.4f),
                "missing file -> outer threshold 0.4");

        // ---- 2. file present, city section / keys missing -> same defaults ----
        Path dir2 = tmp.resolve("empty");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(ZoneConfig.FILE_NAME), "{}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        ZoneConfig z2 = ZoneConfig.load(dir2, w2);
        Map<String, Object> o2 = LcCustomCityMod.citySizeOverrides(z2);
        check(o2.get("CITY_MINRADIUS").equals(800) && o2.get("CITY_MAXRADIUS").equals(800),
                "empty JSON object -> 800/800 blocks");
        check(close(((Number) o2.get("CITY_STYLE_THRESHOLD")).floatValue(), 0.4f),
                "empty JSON object -> outer threshold 0.4");

        Path dir2b = tmp.resolve("cityonly");
        Files.createDirectories(dir2b);
        Files.writeString(dir2b.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{}}", StandardCharsets.UTF_8);
        List<String> w2b = new ArrayList<>();
        ZoneConfig z2b = ZoneConfig.load(dir2b, w2b);
        Map<String, Object> o2b = LcCustomCityMod.citySizeOverrides(z2b);
        check(o2b.get("CITY_MINRADIUS").equals(800) && o2b.get("CITY_MAXRADIUS").equals(800),
                "city section without keys -> 800/800 blocks");

        // ---- 3. normal values: 15/25 chunks + outer ratio [0.40,0.475] ----
        Path dir3 = tmp.resolve("normal");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_RMIN + "\":15,\"" + K_RMAX + "\":25,\""
                        + K_OUTER + "\":[0.40,0.475]}}",
                StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        ZoneConfig z3 = ZoneConfig.load(dir3, w3);
        check(z3.radiusMin == 15 && z3.radiusMax == 25, "config radius parsed as chunks 15/25");
        Map<String, Object> o3 = LcCustomCityMod.citySizeOverrides(z3);
        // chunk -> block conversion asserted explicitly (task requirement)
        check(o3.get("CITY_MINRADIUS").equals(240), "15 chunks -> 240 blocks");
        check(o3.get("CITY_MAXRADIUS").equals(400), "25 chunks -> 400 blocks");
        check(close(((Number) o3.get("CITY_STYLE_THRESHOLD")).floatValue(), 0.4f),
                "outer ratio [0.40,0.475] midpoint 0.4375 -> threshold 0.4");

        // ---- 4. conversion formula: th == 1 - sqrt(0.64*(1-share)) ----
        double[] shares = {0.1, 0.25, 0.4375, 0.5, 0.8};
        for (double s : shares) {
            double expect = 1.0 - Math.sqrt(0.64 * (1.0 - s));
            double actual = LcCustomCityMod.outerThresholdFromShare(s);
            check(Math.abs(actual - expect) < EPS,
                    "formula holds for share " + s + " (expect " + expect + ", got " + actual + ")");
        }

        // ---- 5. clamping: extremes stay in [0.21,0.95] and strictly > 0.2 ----
        double[] extremes = {0.0, 0.99, -3.0, -100.0};
        for (double s : extremes) {
            float th = LcCustomCityMod.outerThresholdFromShare(s);
            check(th >= 0.21f && th <= 0.95f && th > 0.2f,
                    "share " + s + " clamped into [0.21,0.95] and > 0.2: got " + th);
        }
        // share over 1 makes sqrt(NaN); must fall back to the default 0.4, never -1
        check(close(LcCustomCityMod.outerThresholdFromShare(2.0), 0.4f),
                "share 2.0 (sqrt of negative) -> fallback 0.4");
        check(LcCustomCityMod.outerThresholdFromShare(0.0) > 0.2f,
                "share 0.0 would give exactly 0.2 == city threshold; clamped strictly above");
        // a configured extreme range also clamps through citySizeOverrides
        Path dir5 = tmp.resolve("extreme");
        Files.createDirectories(dir5);
        Files.writeString(dir5.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_OUTER + "\":[0.0,0.0]}}", StandardCharsets.UTF_8);
        List<String> w5 = new ArrayList<>();
        float th5 = ((Number) LcCustomCityMod.citySizeOverrides(ZoneConfig.load(dir5, w5))
                .get("CITY_STYLE_THRESHOLD")).floatValue();
        check(th5 >= 0.21f && th5 > 0.2f, "configured outer ratio [0,0] -> clamped to 0.21");

        // ---- 6. min > max -> both fall back to defaults plus warning ----
        Path dir6 = tmp.resolve("inverted");
        Files.createDirectories(dir6);
        Files.writeString(dir6.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_RMIN + "\":30,\"" + K_RMAX + "\":10}}",
                StandardCharsets.UTF_8);
        List<String> w6 = new ArrayList<>();
        ZoneConfig z6 = ZoneConfig.load(dir6, w6);
        Map<String, Object> o6 = LcCustomCityMod.citySizeOverrides(z6);
        check(z6.radiusMin == 50 && z6.radiusMax == 50, "min > max -> radii fall back to 50/50");
        check(o6.get("CITY_MINRADIUS").equals(800) && o6.get("CITY_MAXRADIUS").equals(800),
                "min > max -> 800/800 blocks");
        check(!w6.isEmpty(), "min > max recorded a warning");

        // ---- 7. broken JSON -> defaults plus warning, no crash ----
        Path dir7 = tmp.resolve("broken");
        Files.createDirectories(dir7);
        Files.writeString(dir7.resolve(ZoneConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w7 = new ArrayList<>();
        ZoneConfig z7 = ZoneConfig.load(dir7, w7);
        Map<String, Object> o7 = LcCustomCityMod.citySizeOverrides(z7);
        check(o7.get("CITY_MINRADIUS").equals(800) && o7.get("CITY_MAXRADIUS").equals(800),
                "broken JSON -> 800/800 blocks");
        check(close(((Number) o7.get("CITY_STYLE_THRESHOLD")).floatValue(), 0.4f),
                "broken JSON -> outer threshold 0.4");
        check(!w7.isEmpty(), "broken JSON recorded a warning");

        // ---- 8. wrong types -> that field falls back plus warning, rest works ----
        Path dir8 = tmp.resolve("wrongtype");
        Files.createDirectories(dir8);
        Files.writeString(dir8.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_RMIN + "\":\"abc\",\"" + K_RMAX + "\":25,\""
                        + K_OUTER + "\":\"oops\"}}",
                StandardCharsets.UTF_8);
        List<String> w8 = new ArrayList<>();
        ZoneConfig z8 = ZoneConfig.load(dir8, w8);
        Map<String, Object> o8 = LcCustomCityMod.citySizeOverrides(z8);
        check(o8.get("CITY_MINRADIUS").equals(800) && o8.get("CITY_MAXRADIUS").equals(800),
                "wrong types -> radius falls back to 800/800 blocks");
        check(close(((Number) o8.get("CITY_STYLE_THRESHOLD")).floatValue(), 0.4f),
                "wrong outer ratio type -> threshold 0.4");
        check(!w8.isEmpty(), "wrong types recorded warnings");

        System.out.println();
        System.out.println("================================");
        // "passed N, failed M" in Chinese, via escapes inside a string literal:
        System.out.println("\u901a\u8fc7 " + passed + " \u9879, \u5931\u8d25 " + failed + " \u9879");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("CITY-SIZE-CONFIG-TEST-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < EPS;
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
