import com.caf.lccustomcity.config.ZoneWeights;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Offline smoke test for the zone-noise normaliser (stage 2b patch, v2).
 *
 * Background: the fBm zone noise is NOT uniformly distributed on [0,1) --
 * measured range is only about [0.193, 0.784], samples clustered near 0.5.
 * Feeding it straight into ZoneWeights.pickIndex starves the tail segments
 * (measured: commercial 38.5% / residential 60.8% / industry 0.09% /
 * military 0.6% instead of the configured 47.6/23.8/23.8/4.8).
 *
 * The old version of this test fed UNIFORM inputs and demanded uniform
 * outputs, which locked the map to near-identity and made the fix too weak.
 * This rewrite tests against the REAL noise instead:
 *
 *   1. Boundary behaviour: uniform(seed,0)=0, uniform(seed,1)=1, clamps
 *      outside [0,1], midpoint stays inside [0,1].
 *   2. Monotonicity: 1000 ascending inputs must give non-decreasing outputs.
 *   3. Real-noise distribution: sample the actual ZoneNoise.fbm01 for a
 *      fixed seed on a 256x256 grid (65536 points, coords x/24.0, z/24.0,
 *      3 octaves -- the exact scale the mixin uses), print the measured
 *      range, then:
 *      a. SANITY: raw samples counted into the weighted segments must show
 *         industry OR military below 5% -- proving the bug is real and this
 *         test measures the real thing.
 *      b. CORE: after ZoneNormalize.uniform(seed, v), every segment share
 *         must be within 5 percentage points of the weighted target, and
 *         the military segment must reach at least 2%.
 *      c. Prints a table: raw share vs mapped share vs target per segment.
 *
 * ZoneNormalize and ZoneNoise are reached by REFLECTION because the impl
 * package is compiled in the mixin group (after the group-A tests are
 * compiled), so a direct reference would not compile here. ZoneWeights
 * lives in the config package (group A) and exposes a pure double[]
 * overload, so it is referenced directly.
 *
 * The two-arg uniform(long, double) is used because the map calibrates its
 * empirical CDF per world seed; calling it once with the seed performs the
 * calibration, so the test does not need any separate seed-priming step.
 *
 * This file is intentionally ASCII-only.
 */
public final class ZoneNormalizeSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Fixed world seed for the whole test (deterministic). */
    private static final long SEED = 20240521L;

    /** Configured zone weights: commercial 2 / residential 1 / industry 1 / military 0.2. */
    private static final double[] WEIGHTS = {2.0, 1.0, 1.0, 0.2};
    private static final String[] NAMES = {"commercial", "residential", "industry", "military"};

    /** Allowed deviation from the weighted share, in percentage points. */
    private static final double TOL_PP = 5.0;

    /** Minimum acceptable mapped share for the military segment. */
    private static final double MILITARY_MIN = 0.02;

    /** Real-noise sample grid: 256x256 = 65536 points. */
    private static final int SIDE = 256;
    private static final int COUNT = SIDE * SIDE;

    /** Same wavelength / octaves as CityStyleZoneMixin. */
    private static final double WAVELENGTH = 24.0;
    private static final int OCTAVES = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Zone normalize smoke test (real noise, self-calibrated CDF) ===");

        Class<?> zn = Class.forName("com.caf.lccustomcity.impl.ZoneNormalize");
        Method uniform = zn.getMethod("uniform", long.class, double.class);
        Class<?> znoise = Class.forName("com.caf.lccustomcity.impl.ZoneNoise");
        Method forSeed = znoise.getMethod("forSeed", long.class);
        Object noise = forSeed.invoke(null, SEED);
        check(noise != null, "ZoneNoise.forSeed(seed) returns an instance");
        Method fbm = znoise.getMethod("fbm01", double.class, double.class, int.class);

        // ---- 1. boundaries ----
        check(((Double) uniform.invoke(null, SEED, 0.0)) == 0.0, "uniform(seed, 0) == 0");
        check(((Double) uniform.invoke(null, SEED, 1.0)) == 1.0, "uniform(seed, 1) == 1");
        check(((Double) uniform.invoke(null, SEED, -5.0)) == 0.0, "uniform(seed, -5) == 0 (clamped)");
        check(((Double) uniform.invoke(null, SEED, 5.0)) == 1.0, "uniform(seed, 5) == 1 (clamped)");
        double mid = (Double) uniform.invoke(null, SEED, 0.5);
        check(mid >= 0.0 && mid <= 1.0, "uniform(seed, 0.5) stays inside [0,1]");

        // ---- 2. monotonicity on 1000 ascending inputs ----
        boolean mono = true;
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 1000; i++) {
            double u = (Double) uniform.invoke(null, SEED, i / 999.0);
            if (u < prev) {
                mono = false;
                break;
            }
            prev = u;
        }
        check(mono, "monotone non-decreasing over 1000 ascending inputs");

        // ---- 3. real-noise distribution ----
        double[] raw = new double[COUNT];
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        int k = 0;
        for (int i = 0; i < SIDE; i++) {
            double x = i / WAVELENGTH;
            for (int j = 0; j < SIDE; j++) {
                double v = (Double) fbm.invoke(noise, x, j / WAVELENGTH, OCTAVES);
                raw[k++] = v;
                if (v < lo) {
                    lo = v;
                }
                if (v > hi) {
                    hi = v;
                }
            }
        }
        System.out.println();
        System.out.println(String.format(Locale.ROOT,
                "  measured raw range over %d samples: [%.4f, %.4f]", COUNT, lo, hi));

        double total = 0.0;
        for (double w : WEIGHTS) {
            total += w;
        }

        // 3a. sanity: the bug must be visible in the raw samples
        int[] rawCounts = new int[WEIGHTS.length];
        for (int i = 0; i < COUNT; i++) {
            rawCounts[ZoneWeights.pickIndex(raw[i], WEIGHTS)]++;
        }
        double rawIndustry = rawCounts[2] / (double) COUNT;
        double rawMilitary = rawCounts[3] / (double) COUNT;
        check(rawIndustry < 0.05 || rawMilitary < 0.05,
                "sanity: raw noise starves a tail segment (industry or military < 5%)");

        // 3b. core: mapped shares must match the weighted targets
        int[] mappedCounts = new int[WEIGHTS.length];
        for (int i = 0; i < COUNT; i++) {
            double u = (Double) uniform.invoke(null, SEED, raw[i]);
            mappedCounts[ZoneWeights.pickIndex(u, WEIGHTS)]++;
        }

        System.out.println();
        System.out.println("  segment          raw%    mapped%   target%   delta(pp)");
        double cumulative = 0.0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            double expected = WEIGHTS[i] / total;
            double rawShare = rawCounts[i] / (double) COUNT;
            double mappedShare = mappedCounts[i] / (double) COUNT;
            double deltaPp = (mappedShare - expected) * 100.0;
            cumulative += mappedShare;
            System.out.println(String.format(Locale.ROOT,
                    "  %-12s  %7.2f%%  %8.2f%%  %8.2f%%  %+8.2f",
                    NAMES[i], rawShare * 100.0, mappedShare * 100.0,
                    expected * 100.0, deltaPp));
            check(Math.abs(deltaPp) < TOL_PP,
                    NAMES[i] + " mapped share within " + (int) TOL_PP + "pp of the weighted target");
        }
        double mappedMilitary = mappedCounts[3] / (double) COUNT;
        check(mappedMilitary >= MILITARY_MIN,
                "military mapped share >= 2% (tail segment actually reachable)");
        check(Math.abs(cumulative - 1.0) < 1e-9, "mapped segment shares sum to 100%");

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("ZONE-NORMALIZE-TEST-ALL-PASS");
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
