import com.caf.lccustomcity.config.ZoneWeights;

import java.util.List;
import java.util.Map;

/**
 * Zone weight offline self-check - verifies that configured "ratio" weights
 * actually widen the segment a zone gets inside the noise range [0,1).
 *
 * Pure Java level test of ZoneWeights.pickIndex (the weight-splitting logic
 * used by CityStyleZoneMixin.caf$pickZoneIndex). No Minecraft classes needed.
 *
 * Usage: java ZoneWeightSmokeTest   (no arguments)
 */
public class ZoneWeightSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] args) {
        System.out.println("=== Zone weight offline self-check ===");

        // ---- 1. equal weights -> equal split (old behaviour) ----
        System.out.println("--- equal split when no weights configured ---");
        double[] eq = {1.0, 1.0, 1.0};
        check(ZoneWeights.pickIndex(0.0, eq) == 0, "v=0.00 -> zone 0");
        check(ZoneWeights.pickIndex(0.33, eq) == 0, "v=0.33 -> zone 0");
        check(ZoneWeights.pickIndex(0.34, eq) == 1, "v=0.34 -> zone 1");
        check(ZoneWeights.pickIndex(0.66, eq) == 1, "v=0.66 -> zone 1");
        check(ZoneWeights.pickIndex(0.67, eq) == 2, "v=0.67 -> zone 2");
        check(ZoneWeights.pickIndex(0.999, eq) == 2, "v=0.999 -> zone 2");

        // ---- 2. 3:1 weights -> [0,1) split at exactly 0.75 ----
        System.out.println("--- 3:1 weights split the noise range 3:1 ---");
        double[] w31 = {3.0, 1.0};
        check(ZoneWeights.pickIndex(0.0, w31) == 0, "v=0.00 -> zone 0");
        check(ZoneWeights.pickIndex(0.7499, w31) == 0, "v=0.7499 -> zone 0");
        check(ZoneWeights.pickIndex(0.75, w31) == 1, "v=0.75 -> zone 1 (boundary)");
        check(ZoneWeights.pickIndex(0.9999, w31) == 1, "v=0.9999 -> zone 1");

        // statistical: sample the whole range, zone 0 must get ~75%
        int hits0 = 0;
        int n = 100000;
        for (int i = 0; i < n; i++) {
            if (ZoneWeights.pickIndex((i + 0.5) / n, w31) == 0) {
                hits0++;
            }
        }
        double share = hits0 / (double) n;
        System.out.println("    zone 0 share = " + share);
        check(Math.abs(share - 0.75) < 0.001, "zone 0 gets ~75% of the noise range (actual "
                + String.format("%.4f", share) + ")");

        // ---- 3. 0.75:0.25 is equivalent to 3:1 (relative weights) ----
        System.out.println("--- 0.75:0.25 equals 3:1 (relative weight semantics) ---");
        double[] wFrac = {0.75, 0.25};
        boolean same = true;
        for (int i = 0; i < 1000; i++) {
            double v = i / 1000.0;
            if (ZoneWeights.pickIndex(v, wFrac) != ZoneWeights.pickIndex(v, w31)) {
                same = false;
                break;
            }
        }
        check(same, "0.75:0.25 and 3:1 produce identical splits");

        // ---- 4. runtime table: configured style wider, unconfigured = 1.0 ----
        System.out.println("--- runtime table lookup (style name -> weight) ---");
        ZoneWeights.install(Map.of("caf_zone_inner_aaaa1111", 3.0));
        check(ZoneWeights.weightOf("caf_zone_inner_aaaa1111") == 3.0, "configured style -> 3.0");
        check(ZoneWeights.weightOf("caf_zone_inner_bbbb2222") == 1.0, "unconfigured style -> 1.0 (equal share)");
        check(ZoneWeights.weightOf(null) == 1.0, "null style name -> 1.0");
        check(ZoneWeights.configured() == 1, "configured() reports 1");

        List<String> styles = List.of("caf_zone_inner_aaaa1111", "caf_zone_inner_bbbb2222");
        check(ZoneWeights.pickIndex(0.7499, styles) == 0, "table lookup: v=0.7499 -> zone 0");
        check(ZoneWeights.pickIndex(0.75, styles) == 1, "table lookup: v=0.75 -> zone 1");

        // ---- 5. fault tolerance ----
        System.out.println("--- fault tolerance ---");
        double[] bad = {0.0, 1.0};
        check(ZoneWeights.pickIndex(0.49, bad) == 0, "weight 0 treated as 1.0 (v=0.49 -> zone 0)");
        check(ZoneWeights.pickIndex(0.51, bad) == 1, "weight 0 treated as 1.0 (v=0.51 -> zone 1)");
        double[] nan = {Double.NaN, 1.0};
        check(ZoneWeights.pickIndex(0.49, nan) == 0, "NaN weight treated as 1.0");
        double[] neg = {-5.0, 1.0};
        check(ZoneWeights.pickIndex(0.49, neg) == 0, "negative weight treated as 1.0");
        check(ZoneWeights.pickIndex(-0.5, w31) == 0, "v below 0 clamped");
        check(ZoneWeights.pickIndex(1.5, w31) == 1, "v above 1 clamped into last zone");
        check(ZoneWeights.pickIndex(0.5, new double[]{7.0}) == 0, "single zone always returns 0");
        check(ZoneWeights.pickIndex(0.5, new double[0]) == 0, "empty weights return 0");

        // install drops non-positive entries so they fall back to default 1.0
        ZoneWeights.install(Map.of("caf_zone_inner_dead", -2.0));
        check(ZoneWeights.weightOf("caf_zone_inner_dead") == 1.0,
                "install drops non-positive weight (falls back to 1.0)");
        check(ZoneWeights.configured() == 0, "configured() is 0 after dropping invalid entry");

        // empty table -> everything defaults to 1.0 -> equal split
        ZoneWeights.install(Map.of());
        check(ZoneWeights.pickIndex(0.6, List.of("a", "b")) == 1,
                "empty table -> equal split (v=0.6 of 2 zones -> zone 1)");

        // ---- 6. determinism ----
        System.out.println("--- determinism ---");
        ZoneWeights.install(Map.of("caf_zone_inner_aaaa1111", 3.0));
        boolean det = true;
        for (int i = 0; i < 1000; i++) {
            double v = (i * 0.000999) % 1.0;
            if (ZoneWeights.pickIndex(v, styles) != ZoneWeights.pickIndex(v, styles)) {
                det = false;
                break;
            }
        }
        check(det, "same (v, table) always yields the same zone index");

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("ZONE-WEIGHT-TEST-ALL-PASS");
            System.exit(0);
        } else {
            System.exit(1);
        }
    }
}
