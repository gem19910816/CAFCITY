import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Offline PERFORMANCE measurement of Lost Cities' City.getCityFactor().
 *
 * Decision this answers: the addon wants to raise CITY_MAXRADIUS from 400 to
 * 1000 blocks. getCityFactor() brute-force scans (2*offset+1)^2 candidate chunks
 * with offset = (CITY_MAXRADIUS + 15) / 16, calling BuildingInfo.getProfile(),
 * City.isCityCenter() and (on a hit) City.getCityRadius() for each candidate.
 * How much wall-clock time does that cost, and would a city-center cache pay off?
 *
 * This test never asserts a timing threshold -- machine load differs, so a
 * "must be faster than X ms" check would only produce false reds. It PRINTS the
 * numbers and only checks that the measuring flow itself completed. The final
 * "passed N, failed M" therefore means "measurement ran", not "fast enough".
 *
 * ---------------------------------------------------------------------------
 * HOW A REAL MEASUREMENT IS POSSIBLE OFFLINE
 * ---------------------------------------------------------------------------
 * The game cannot be started offline, so there is no real DimensionInfo, no
 * level, no registries. Three facts make the path measurable anyway:
 *
 * 1. City.getCityFactor() only touches the provider through six methods a stub
 *    can answer: getType, getWorld, getProfile, getOutsideProfile, getSeed,
 *    dimension. getWorld() may return null: the four predefined-building
 *    lookups plus one predefined-street lookup only pass it to
 *    City.calculateMap()/getPredefinedStreet(), which return immediately once
 *    the three static predefined maps are non-null, and the two
 *    "provider.getWorld() != null" guards then skip the heightmap gate and the
 *    world-style multiplier. Both skipped steps are O(1) per call (NOT per
 *    candidate) so they cannot change the 400 -> 1000 scaling question.
 *
 * 2. The three static maps (predefinedCityMap / predefinedBuildingMap /
 *    predefinedStreetMap) are set here by reflection to an EMPTY unmodifiable
 *    HashMap. That is exactly what the shipped game has (neither the Lost
 *    Cities built-in data nor this addon's generated pack defines a predefined
 *    city), and it keeps AssetRegistries.loadPredefinedStuff(level) -- which
 *    needs a real level -- out of the measurement.
 *
 * 3. A java.lang.reflect.Proxy provider would be useless for timing: proxy
 *    dispatch costs about 10 ns and the measured per-candidate work is a few
 *    ns, so the proxy would swamp the result. Instead this test COMPILES a tiny
 *    helper at runtime with the JDK's own compiler, against the real Lost
 *    Cities jar that the runner puts on the classpath, so the helper implements
 *    IDimensionInfo DIRECTLY and calls City/BuildingInfo directly: no
 *    reflection and no proxy in any timed loop.
 *
 * Measured, per radius: the full City.getCityFactor() call, timed one call at a
 * time (avg/median/min/max/p99) after a JIT warmup.
 *
 * Attributed without any synthetic loop (synthetic loops are not trustworthy
 * here: a hand-written copy of the scan gets compiled with different inlining
 * than the real one and came out 1.5x slower in this environment). Instead the
 * SAME real call is measured three times with different data:
 *   F  = match profile, CITY_CHANCE 0.02  -> the real thing (city centers hit)
 *   C0 = match profile, CITY_CHANCE 0.00  -> identical loop, zero hits
 *   G  = a provider whose profile is a DIFFERENT instance -> the profile
 *        comparison fails, so City.isCityCenter() is never called at all
 * C0 - G is then exactly the cost of the 16129 isCityCenter calls, F - C0 the
 * cost of the getCityRadius calls plus the hit math, and G the candidate-scan
 * overhead (ChunkCoord + profile check). All three run through the real,
 * identically compiled code path, so the differences are meaningful.
 *
 * Profile values are the ones this addon forces (LcCustomCityMod
 * FORCED_PROFILE_VALUES): CITY_CHANCE = 0.02 (double), CITY_MINRADIUS = 240,
 * CITY_THRESHOLD = 0.2f, CITY_MAXRADIUS = 400 or 1000.
 *
 * This file is intentionally ASCII-only. Do not add unicode escapes or
 * non-ASCII characters: the helper source below is compiled as US-ASCII.
 *
 * Optional: -Dcaf.perf.outfile=C:\path\report.txt also saves the whole report
 * to that file (the suite does not set it, so a normal run only prints).
 */
public final class CityFactorPerfSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Lost Cities classes are reached by reflection only (runtime dependency). */
    private static final String CITY_CLASS = "mcjty.lostcities.worldgen.lost.City";
    private static final String BUILDING_INFO_CLASS = "mcjty.lostcities.worldgen.lost.BuildingInfo";
    private static final String PROFILE_CLASS = "mcjty.lostcities.config.LostCityProfile";
    private static final String DIMINFO_CLASS = "mcjty.lostcities.worldgen.IDimensionInfo";

    /** Name of the helper class whose source is HELPER_SOURCE (default package). */
    private static final String HELPER_CLASS = "CafCityFactorPerfProbe";

    // Profile values forced by the addon (see LcCustomCityMod.FORCED_PROFILE_VALUES).
    private static final double ADDON_CITY_CHANCE = 0.02d;
    private static final int ADDON_CITY_MINRADIUS = 240;
    private static final float ADDON_CITY_THRESHOLD = 0.2f;

    private static final int[] RADII = {400, 1000};

    /** Query point, away from 0 so the scan window is a plain offset. */
    private static final int BASE_X = 1400;
    private static final int BASE_Z = -2600;

    /**
     * Full-call measurement: the warmup and the timed phase are given a fixed
     * TIME budget instead of a fixed call count, so the 400-block config (a
     * 0.06 ms call) gets as much JIT warmup as the 1000-block one (0.34 ms).
     * A fixed call count would leave the cheap config measurably less compiled.
     */
    private static final int WARMUP_CALLS = 250;
    private static final long WARMUP_BUDGET_NANOS = 150_000_000L;
    private static final int MAX_WARMUP_CALLS = 3000;

    private static final int TIMED_CALLS = 300;
    private static final long TIMED_BUDGET_NANOS = 100_000_000L;
    private static final int MAX_TIMED_CALLS = 1200;

    /** Inner-op micro benchmarks: warmup reps, then timed reps, each rep = 1 window. */
    private static final int INNER_WARMUP_REPS = 15;
    private static final int INNER_TIMED_REPS = 25;

    /** Hard ceiling so a pathological machine cannot make the suite crawl. */
    private static final long CALL_BUDGET_NANOS = 4_000_000_000L;

    public static void main(String[] args) throws Exception {
        teeOutput();
        System.out.println("=== City factor performance smoke test (Lost Cities City.getCityFactor, offline) ===");

        // ---- 0. the real Lost Cities classes must be the runtime dependency ----
        Class<?> cityCls = Class.forName(CITY_CLASS);
        Class<?> infoCls = Class.forName(DIMINFO_CLASS);
        Class<?> profileCls = Class.forName(PROFILE_CLASS);
        String origin = String.valueOf(cityCls.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("Lost Cities classes from: " + origin);
        check(origin.toLowerCase(Locale.ROOT).contains("lostcities"),
                "Lost Cities classes come from the Lost Cities jar");

        // ---- 1. exact signature of getCityFactor ----
        System.out.println();
        System.out.println("City.getCityFactor overloads found by reflection:");
        Method factor = null;
        for (Method m : cityCls.getMethods()) {
            if (m.getName().equals("getCityFactor")) {
                System.out.println("  " + m);
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0].getName().endsWith("ChunkCoord")
                        && p[1] == infoCls && p[2] == profileCls) {
                    factor = m;
                }
            }
        }
        check(factor != null, "City.getCityFactor(ChunkCoord, IDimensionInfo, LostCityProfile) exists");
        if (factor == null) {
            bail("required getCityFactor overload is missing");
            return;
        }
        System.out.println("IDimensionInfo methods: " + infoCls.getMethods().length
                + " (see the call tally below for which ones are actually used)");

        Class<?> coordCls = Class.forName("mcjty.lostcities.varia.ChunkCoord");
        Class<?> biCls = Class.forName(BUILDING_INFO_CLASS);
        Method getProfile = biCls.getMethod("getProfile", coordCls, infoCls);
        Method isCityCenter = cityCls.getMethod("isCityCenter", coordCls, infoCls);
        Method getCityRadius = cityCls.getMethod("getCityRadius", coordCls, infoCls);
        System.out.println("  scan body: " + getProfile);
        System.out.println("             " + isCityCenter);
        System.out.println("             " + getCityRadius);

        // ---- 2. neutralise the predefined-city static maps ----
        Map<Object, Object> empty = Collections.unmodifiableMap(new HashMap<>());
        for (String name : new String[] {"predefinedCityMap", "predefinedBuildingMap", "predefinedStreetMap"}) {
            Field f = cityCls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, empty);
            check(f.get(null) != null, "City." + name + " set to an empty map");
        }

        // ---- 3. compile + load the direct-call helper ----
        Class<?> helper = compileHelper();
        check(helper != null, "runtime-compiled helper " + HELPER_CLASS + " loaded");
        if (helper == null) {
            bail("could not compile/load the helper; cannot measure anything");
            return;
        }
        Method makeProvider = helper.getMethod("makeProvider", Object.class);
        Method timeFactorWith = helper.getMethod("timeFactorWith", Object.class, Object.class, int.class, int.class);
        Method windowStats = helper.getMethod("windowStats", Object.class);
        Method primeCache = helper.getMethod("primeCache", Object.class);
        Method timeAlloc = helper.getMethod("timeAlloc", Object.class, int.class);
        Method timeProfile = helper.getMethod("timeProfile", Object.class, int.class);
        Method timeCenter = helper.getMethod("timeCenter", Object.class, int.class);
        Method timeRadius = helper.getMethod("timeRadius", Object.class, int.class);
        Method timeCacheProbe = helper.getMethod("timeCacheProbe", Object.class, int.class);
        Method timePrimitiveProbe = helper.getMethod("timePrimitiveCacheProbe", Object.class, int.class);
        Method primePrimitive = helper.getMethod("primePrimitiveCache", Object.class);

        // ---- 4. profiles and providers ----
        Constructor<?> pctor = profileCls.getConstructor(String.class, boolean.class);
        Object[] profiles = new Object[RADII.length];
        Object[] providers = new Object[RADII.length];
        int[][] windows = new int[RADII.length][];

        System.out.println();
        System.out.println("--- profile (the values this addon forces) ---");
        System.out.println("CITY_CHANCE      = " + ADDON_CITY_CHANCE + " (double: 2% of chunks are city centers)");
        System.out.println("CITY_MINRADIUS   = " + ADDON_CITY_MINRADIUS + " blocks");
        System.out.println("CITY_THRESHOLD   = " + ADDON_CITY_THRESHOLD);
        System.out.println("query chunk      = (" + BASE_X + ", " + BASE_Z + ")");

        for (int i = 0; i < RADII.length; i++) {
            profiles[i] = makeProfile(pctor, RADII[i], ADDON_CITY_CHANCE);
            providers[i] = makeProvider.invoke(null, profiles[i]);
            int[] w = (int[]) windowStats.invoke(null, providers[i]);
            windows[i] = w;
            int off = (radius(RADII[i]));
            int side = 2 * off + 1;
            check(w[0] == off && w[1] == side && w[2] == side * side,
                    "helper window for radius " + RADII[i] + ": offset " + off + ", side " + side
                            + ", candidates " + (side * side));
            System.out.println(String.format(Locale.ROOT,
                    "radius %-5d offset %-3d side %-4d candidates %-6d city centers in that window %d (%.2f%%)",
                    RADII[i], off, side, side * side, w[3], 100.0 * w[3] / (side * (double) side)));
        }

        int idx400 = 0;
        int idx1000 = RADII.length - 1;
        int n = windows[idx1000][2];
        int hits = windows[idx1000][3];
        int cand400 = windows[idx400][2];
        int cand1000 = windows[idx1000][2];

        System.out.println(String.format(Locale.ROOT,
                "candidate growth 400 -> 1000: %.2fx (%d -> %d)",
                cand1000 / (double) cand400, cand400, cand1000));
        System.out.println("NOTE: 625 is offset^2, not the candidate count; the real count is");
        System.out.println("(2*offset+1)^2 = 51^2 = 2601 at radius 400, so the growth is about 6.2x, not 26x.");
        check(Math.abs(cand1000 / (double) cand400 - 6.2d) < 0.1d,
                "candidate growth is about 6.2x, not 26x");

        // ---- 5. the full call, per radius ----
        double[][] full = new double[RADII.length][];
        for (int i = 0; i < RADII.length; i++) {
            full[i] = measureCalls(timeFactorWith, providers[i], profiles[i], windows[i][2]);
        }
        double avg400 = full[idx400][0];
        double avg1000 = full[idx1000][0];
        double med400 = full[idx400][1];
        double med1000 = full[idx1000][1];

        // ---- 6. attribution: the same real call, three data configurations ----
        Object profile0 = makeProfile(pctor, RADII[idx1000], 0.0d);
        Object provider0 = makeProvider.invoke(null, profile0);
        Object profileNomatch = makeProfile(pctor, RADII[idx1000], ADDON_CITY_CHANCE);
        Object providerNomatch = makeProvider.invoke(null, profileNomatch);
        check(profileNomatch != profiles[idx1000], "the no-match profile is a different instance");

        double[] fAll = measureCalls(timeFactorWith, providers[idx1000], profiles[idx1000], n);
        double[] fNone = measureCalls(timeFactorWith, provider0, profile0, n);
        double[] fNoMatch = measureCalls(timeFactorWith, providerNomatch, profiles[idx1000], n);
        // Only the two 20x-apart relations are asserted; F vs C0 differ by a few
        // percent (the getCityRadius hits) and noise must not turn this red.
        check(fNoMatch[2] < fNone[2], "isCityCenter really is the expensive part (no-match run is far cheaper)");
        check(fNoMatch[2] < fAll[2], "the no-match run is cheaper than the real run");
        // The three buckets are derived from the BEST sample of each
        // configuration (index 2). Best-of is the least interfered sample, so a
        // transient stall during one configuration cannot invent time in a
        // bucket, and the three parts then sum to exactly one real call.
        double bestAll = fAll[2];
        double bestNone = fNone[2];
        double bestNoMatch = fNoMatch[2];

        // all values below are nanoseconds
        double fMed = bestAll * 1e6;
        double fBase = fMed;
        double cCenter = Math.max(0.0, (bestNone - bestNoMatch) * 1e6);
        double cRadius = Math.max(0.0, (bestAll - bestNone) * 1e6);
        double cScan = bestNoMatch * 1e6;

        // ---- 7. isolated per-call micro benchmarks (radius 1000 window) ----
        Object prov1000 = providers[idx1000];
        primeCache.invoke(null, prov1000);
        primePrimitive.invoke(null, prov1000);
        double alloc = measureInner(timeAlloc, prov1000, n);
        double profileNs = measureInner(timeProfile, prov1000, n);
        double centerNs = measureInner(timeCenter, prov1000, n);
        double radiusNs = measureInner(timeRadius, prov1000, n);
        double cacheNs = measureInner(timeCacheProbe, prov1000, n);
        double primitiveNs = measureInner(timePrimitiveProbe, prov1000, n);

        // ---- 8. two-point fit of the full call: cost(R) = constant + slope * candidates ----
        double slopeNs = (med1000 - med400) * 1e6 / (cand1000 - cand400);
        double constantUs = med400 * 1000.0 - slopeNs * cand400 / 1000.0;

        // ---- 9. call tally via a counting proxy (LAST: it must not pollute the
        //         timed call sites with a second provider implementation) ----
        Map<String, Integer> tally = new HashMap<>();
        String tallyError = null;
        try {
            tallyCalls(helper, infoCls, profiles[idx1000], factor, tally);
        } catch (Throwable t) {
            tallyError = t.toString();
        }

        // ---- 10. tables ----
        System.out.println();
        System.out.println("--- TABLE 1: full City.getCityFactor() cost, one call at a time ---");
        System.out.println("  (warmup and sampling are time-budgeted - about "
                + (WARMUP_BUDGET_NANOS / 1_000_000) + " ms of warmup calls and "
                + (TIMED_BUDGET_NANOS / 1_000_000) + " ms of timed calls per radius -");
        System.out.println("   so both radii reach the same JIT state; times in milliseconds)");
        System.out.println();
        System.out.println("  radius   offset   candidates   avg(ms)    median(ms)  min(ms)    p99(ms)    max(ms)    ns/candidate");
        for (int i = 0; i < RADII.length; i++) {
            double[] s = full[i];
            System.out.println(String.format(Locale.ROOT,
                    "  %-8d %-8d %-12d %-10.4f %-11.4f %-10.4f %-10.4f %-10.4f %.1f",
                    RADII[i], windows[i][0], windows[i][2],
                    s[0], s[1], s[2], s[4], s[3], s[1] * 1_000_000.0 / windows[i][2]));
        }
        System.out.println();
        System.out.println(String.format(Locale.ROOT,
                "  delta per call (median): %.4f ms -> %.4f ms  = +%.4f ms (%.2fx)",
                med400, med1000, med1000 - med400, med1000 / Math.max(med400, 1e-9)));
        System.out.println(String.format(Locale.ROOT,
                "  delta per call (avg)   : %.4f ms -> %.4f ms  = +%.4f ms (%.2fx)",
                avg400, avg1000, avg1000 - avg400, avg1000 / Math.max(avg400, 1e-9)));
        System.out.println(String.format(Locale.ROOT,
                "  two-point fit over the two radii: %.1f ns per candidate + %.1f us fixed per call",
                slopeNs, constantUs));
        check(med1000 > med400 * 1.5d, "measuring flow responds to the radius change (median grows)");
        check(med1000 < med400 * 40.0d, "growth stays in the same order of magnitude as the candidate growth");

        System.out.println();
        System.out.println("--- TABLE 2: where the time goes (same real call, three data configurations) ---");
        System.out.println("  radius 1000 window: " + n + " candidates, " + hits + " city centers");
        System.out.println();
        System.out.println("  configuration                                        median(ms) best(ms)");
        System.out.println(String.format(Locale.ROOT,
                "  %-52s %-11.4f %.4f", "match profile, CITY_CHANCE 0.02 (the real case)", fAll[1], fAll[2]));
        System.out.println(String.format(Locale.ROOT,
                "  %-52s %-11.4f %.4f", "match profile, CITY_CHANCE 0.00 (no hit, loop identical)", fNone[1], fNone[2]));
        System.out.println(String.format(Locale.ROOT,
                "  %-52s %-11.4f %.4f", "different profile instance (isCityCenter never called)", fNoMatch[1], fNoMatch[2]));
        System.out.println();
        System.out.println("  the split below uses the best sample of each configuration, so the parts sum");
        System.out.println("  to exactly one call and a stray stall cannot inflate a single component.");
        System.out.println();
        System.out.println("  component                                  us        ns/candidate   share of the call");
        printRow("candidate scan (ChunkCoord + getProfile)", cScan, n, fMed);
        printRow("City.isCityCenter (" + n + " calls)", cCenter, n, fMed);
        printRow("City.getCityRadius (" + hits + " hits)", cRadius, n, fMed);
        System.out.println(String.format(Locale.ROOT, "  %-42s %-9.1f %-14s %5.1f%%",
                "sum", (cScan + cCenter + cRadius) / 1000.0, "-",
                100.0 * (cScan + cCenter + cRadius) / fMed));
        System.out.println();
        System.out.println("  (F - C0 is exactly " + hits + " getCityRadius calls plus the distance math;");
        System.out.println("   C0 - G is exactly " + n + " isCityCenter calls; G is the per-candidate scan).");

        System.out.println();
        System.out.println("--- TABLE 3: isolated per-call cost (secondary; each row pays its own");
        System.out.println("    ChunkCoord allocation. Best of " + INNER_TIMED_REPS + " reps after "
                + INNER_WARMUP_REPS + " warmup reps,");
        System.out.println("    so these do not have to match the in-loop numbers of TABLE 2) ---");
        System.out.println();
        System.out.println(String.format(Locale.ROOT, "  %-46s %s", "operation (1 ChunkCoord + 1 call)", "ns/call"));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f", "ChunkCoord allocation only (volatile sink)", alloc));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f", "BuildingInfo.getProfile", profileNs));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f", "City.isCityCenter", centerNs));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f", "City.getCityRadius", radiusNs));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f",
                "cache probe A: HashMap<ChunkCoord,Boolean>.get", cacheNs));
        System.out.println(String.format(Locale.ROOT, "  %-46s %.2f",
                "cache probe B: primitive open-addressing long table", primitiveNs));

        System.out.println();
        System.out.println("--- TABLE 4: would an isCityCenter cache pay off ---");
        System.out.println();
        double centerPerCandidate = cCenter / n;
        System.out.println(String.format(Locale.ROOT,
                "  in-loop isCityCenter (TABLE 2)     : %.1f us total, %.2f ns/candidate, %.1f%% of the call",
                cCenter / 1000.0, centerPerCandidate, 100.0 * cCenter / fBase));
        System.out.println();
        System.out.println(String.format(Locale.ROOT, "  %-46s %-10s %-12s %s",
                "cache design", "ns/call", "saving", "effect"));
        printCache("A: HashMap<ChunkCoord,Boolean> (naive)", centerNs, cacheNs, n, fMed);
        printCache("B: primitive long-table, open addressing", centerNs, primitiveNs, n, fMed);
        System.out.println();
        System.out.println("  saving = isCityCenter cost minus probe cost, per candidate, over the whole call.");
        System.out.println("  Probe A is what the obvious implementation would look like. A real cache also");
        System.out.println("  costs memory (one entry per scanned chunk per dimension, and the scan touches");
        System.out.println("  16129 chunks per call) and must be invalidated when the profile or the");
        System.out.println("  predefined city data changes.");

        System.out.println();
        System.out.println("--- TABLE 5: what this means per generated chunk ---");
        System.out.println("  Lost Cities calls City.getCityFactor per chunk in these places:");
        System.out.println("    BuildingInfo.java:506 isCityRaw  <- getChunkCharacteristics (cached per chunk)  1x");
        System.out.println("    BuildingInfo.java:933            <- BuildingInfo constructor (cached per chunk) 1x");
        System.out.println("    Scattered.java:44 isCityRaw      <- scattered-plan candidates only               0..1x");
        System.out.println("    LC 8.3.9 has no hierarchical street planner. ScatteredMargin.nearCity");
        System.out.println("        runs one extra scan per scattered-plan candidate chunk.");
        for (int calls = 2; calls <= 6; calls += 2) {
            System.out.println(String.format(Locale.ROOT,
                    "  %d call(s)/chunk: +%.3f ms/chunk at radius 1000 vs radius 400 (avg), now %.3f ms/chunk",
                    calls, (avg1000 - avg400) * calls, avg1000 * calls));
        }
        System.out.println(String.format(Locale.ROOT,
                "  p99 call (6 calls/chunk): %.3f ms at 1000 vs %.3f ms at 400",
                full[idx1000][4] * 6, full[idx400][4] * 6));
        System.out.println(String.format(Locale.ROOT,
                "  max call (6 calls/chunk): %.3f ms at 1000 vs %.3f ms at 400 (GC / scheduler noise)",
                full[idx1000][3] * 6, full[idx400][3] * 6));

        System.out.println();
        System.out.println("--- TABLE 6: IDimensionInfo methods actually invoked, per getCityFactor call ---");
        System.out.println("  (counting Proxy, radius 1000, one call; anything not listed was never called)");
        System.out.println();
        List<String> keys = new ArrayList<>(tally.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            System.out.println(String.format(Locale.ROOT, "  %-26s %d", k, tally.get(k)));
        }
        check(tallyError == null, "one getCityFactor call stayed inside the stubbed methods"
                + (tallyError == null ? "" : " (failed with: " + tallyError + ")"));
        int profCalls = tally.getOrDefault("getProfile", 0);
        check(profCalls > 3 * n, "getProfile is called several times per candidate (" + profCalls + ")");
        check(tally.getOrDefault("getWorld", 0) > 0, "getWorld is on the path");
        check(!tally.containsKey("getHeightmap(ChunkCoord)(UnsupportedOperationException)")
                        && !tally.containsKey("getHeightmap(ChunkCoord)"),
                "no heightmap lookup happens when the provider has no world (documented exclusion)");
        check(!tally.containsKey("getOutsideProfile") && !tally.containsKey("getSeed")
                        && !tally.containsKey("dimension") && !tally.containsKey("getWorldStyle"),
                "getOutsideProfile / getSeed / dimension / getWorldStyle are never called on this path");

        System.out.println();
        System.out.println("=== playback ===");
        System.out.println(String.format(Locale.ROOT,
                "radius 1000: %.4f ms per getCityFactor call (median %.4f, p99 %.4f, max %.4f)",
                avg1000, med1000, full[idx1000][4], full[idx1000][3]));
        System.out.println(String.format(Locale.ROOT,
                "radius  400: %.4f ms per getCityFactor call (median %.4f, p99 %.4f, max %.4f)",
                avg400, med400, full[idx400][4], full[idx400][3]));
        System.out.println(String.format(Locale.ROOT,
                "share of the call: isCityCenter %.1f%%, getCityRadius %.1f%%, candidate scan %.1f%%",
                100.0 * cCenter / fBase, 100.0 * cRadius / fBase, 100.0 * cScan / fBase));
        System.out.println(String.format(Locale.ROOT,
                "per chunk with 2 calls: %.3f ms -> %.3f ms (+%.3f ms); with 2..6 calls: +%.3f..%.3f ms",
                avg400 * 2, avg1000 * 2, (avg1000 - avg400) * 2,
                (avg1000 - avg400) * 2, (avg1000 - avg400) * 6));
        System.out.println(String.format(Locale.ROOT,
                "isCityCenter cache: naive HashMap %.1f%% of the extra time, primitive table %.1f%%",
                100.0 * (centerNs - cacheNs) * n / fBase, 100.0 * (centerNs - primitiveNs) * n / fBase));

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        System.out.println("(the pass count means the measurement flow completed, NOT that any timing is good)");
        if (failed == 0) {
            System.out.println("CITY-FACTOR-PERF-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int radius(int radiusBlocks) {
        return (radiusBlocks + 15) / 16;
    }

    /**
     * With -Dcaf.perf.outfile=&lt;path&gt; the whole report is also written to that
     * file, so a measured run can be archived next to the build it belongs to.
     * Without the property nothing changes.
     */
    private static void teeOutput() throws Exception {
        String path = System.getProperty("caf.perf.outfile");
        if (path == null || path.isEmpty()) {
            return;
        }
        PrintStream file = new PrintStream(new FileOutputStream(path), true, StandardCharsets.UTF_8);
        PrintStream console = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                console.write(b);
                file.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                console.write(b, off, len);
                file.write(b, off, len);
            }

            @Override
            public void flush() {
                console.flush();
                file.flush();
            }
        }, true, StandardCharsets.UTF_8));
    }

    private static Object makeProfile(Constructor<?> ctor, int radiusBlocks, double cityChance) throws Exception {
        Object profile = ctor.newInstance("caf_perf_" + radiusBlocks + "_" + cityChance, false);
        setField(profile, "CITY_CHANCE", cityChance);
        setField(profile, "CITY_MINRADIUS", ADDON_CITY_MINRADIUS);
        setField(profile, "CITY_MAXRADIUS", radiusBlocks);
        setField(profile, "CITY_THRESHOLD", ADDON_CITY_THRESHOLD);
        return profile;
    }

    /** totalNs is the cost of one whole window; candidates is what it was spent on. */
    private static void printRow(String name, double totalNs, int candidates, double baseNs) {
        System.out.println(String.format(Locale.ROOT,
                "  %-42s %-9.1f %-14.2f %5.1f%%",
                name, totalNs / 1000.0, totalNs / Math.max(candidates, 1), 100.0 * totalNs / baseNs));
    }

    /** One cache design: probe cost against the cost of recomputing isCityCenter. */
    private static void printCache(String name, double centerNs, double probeNs, int candidates, double baseNs) {
        double saving = centerNs - probeNs;
        System.out.println(String.format(Locale.ROOT,
                "  %-46s %-10.2f %-12.2f %s",
                name, probeNs, saving,
                saving > 0
                        ? String.format(Locale.ROOT, "saves %.1f%% of the call", 100.0 * saving * candidates / baseNs)
                        : String.format(Locale.ROOT, "SLOWER than recomputing (costs %.1f%% more of the call)",
                                100.0 * -saving * candidates / baseNs)));
    }

    // ------------------------------------------------------------------
    // measuring
    // ------------------------------------------------------------------

    /**
     * Time real getCityFactor calls one by one, honouring a hard budget so a
     * slow machine cannot turn this test into a hang.
     *
     * @return {avg, median, min, max, p99} in milliseconds
     */
    private static double[] measureCalls(Method timeFactorWith, Object provider, Object profile,
                                         int candidates) throws Exception {
        long[] raw = (long[]) timeFactorWith.invoke(null, provider, profile, WARMUP_CALLS, 1);
        double probeMs = raw[1] / 1e6;
        int warmup = scaled(WARMUP_CALLS, probeMs, WARMUP_BUDGET_NANOS, MAX_WARMUP_CALLS);
        int iters = scaled(TIMED_CALLS, probeMs, TIMED_BUDGET_NANOS, MAX_TIMED_CALLS);
        if (probeMs * (warmup + iters) > CALL_BUDGET_NANOS) {
            iters = Math.max(20, iters / 2);
            warmup = Math.min(warmup, MAX_WARMUP_CALLS);
        }
        System.out.println(String.format(Locale.ROOT,
                "  [measuring] %d candidates, one call about %.4f ms -> warmup %d + %d timed calls"
                        + " (about %.0f ms + %.0f ms)",
                candidates, probeMs, warmup, iters, warmup * probeMs, iters * probeMs));
        long[] all = (long[]) timeFactorWith.invoke(null, provider, profile, warmup, iters);
        long[] t = Arrays.copyOfRange(all, 1, all.length);
        Arrays.sort(t);
        double sum = 0;
        for (long v : t) {
            sum += v;
        }
        double avg = sum / t.length / 1e6;
        double median = t[t.length / 2] / 1e6;
        double min = t[0] / 1e6;
        double max = t[t.length - 1] / 1e6;
        double p99 = t[Math.min(t.length - 1, (int) (t.length * 0.99))] / 1e6;
        return new double[] {avg, median, min, max, p99};
    }

    /** Calls needed to fill a fixed time budget, clamped to [base, cap]. */
    private static int scaled(int base, double callMs, long budgetNanos, int cap) {
        if (callMs <= 0) {
            return base;
        }
        long n = (long) (budgetNanos / (callMs * 1e6));
        return (int) Math.max(base, Math.min(cap, n));
    }

    /**
     * Best (minimum) per-call cost of an isolated inner op, in nanoseconds.
     * The minimum over many reps is the standard estimator for a micro
     * benchmark: noise and GC only ever add time, so the smallest sample is the
     * least interfered one.
     */
    private static double measureInner(Method m, Object provider, int iters) throws Exception {
        for (int i = 0; i < INNER_WARMUP_REPS; i++) {
            m.invoke(null, provider, iters);
        }
        long[] t = new long[INNER_TIMED_REPS];
        for (int i = 0; i < INNER_TIMED_REPS; i++) {
            t[i] = (Long) m.invoke(null, provider, iters);
        }
        Arrays.sort(t);
        return t[0] / (double) iters;
    }

    // ------------------------------------------------------------------
    // call tally through a counting proxy (never used for timing)
    // ------------------------------------------------------------------

    private static void tallyCalls(Class<?> helper, Class<?> infoCls, Object profile,
                                   Method factor, Map<String, Integer> tally) throws Exception {
        Method typeMethod = helper.getMethod("levelType");
        Object type = typeMethod.invoke(null);
        InvocationHandler handler = (proxy, method, margs) -> {
            String name = method.getName();
            switch (name) {
                case "getProfile":
                case "getOutsideProfile":
                    bump(tally, name);
                    return profile;
                case "getWorld":
                    bump(tally, name);
                    return null;                        // no level offline: see the header comment
                case "getType":
                case "dimension":
                    bump(tally, name);
                    return type;
                case "getSeed":
                    bump(tally, name);
                    return 12345L;
                case "toString":
                    return "caf-perf-counting-provider";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == margs[0];
                default:
                    bump(tally, name + "(UnsupportedOperationException)");
                    throw new UnsupportedOperationException("provider." + name + " is not stubbed");
            }
        };
        Object counting = Proxy.newProxyInstance(CityFactorPerfSmokeTest.class.getClassLoader(),
                new Class<?>[] {infoCls}, handler);
        // exactly one real call: the tally below is per getCityFactor call
        factor.invoke(null, newCoord(helper, typeMethod, BASE_X, BASE_Z), counting, profile);
    }

    /** A ChunkCoord built through the helper (so no Lost Cities type is needed here). */
    private static Object newCoord(Class<?> helper, Method typeMethod, int cx, int cz) throws Exception {
        Method mk = helper.getMethod("makeCoord", Object.class, int.class, int.class);
        return mk.invoke(null, typeMethod.invoke(null), cx, cz);
    }

    private static void bump(Map<String, Integer> tally, String name) {
        tally.merge(name, 1, Integer::sum);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Compile HELPER_SOURCE against the running classpath (which contains the
     * real Lost Cities jar) and load it in a child loader, so the helper can
     * implement IDimensionInfo directly and make direct calls in timed loops.
     */
    private static Class<?> compileHelper() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.out.println("  no system Java compiler (JRE instead of JDK?)");
            return null;
        }
        String cp = System.getProperty("java.class.path");
        if (cp == null || !cp.toLowerCase(Locale.ROOT).contains("lostcities")) {
            System.out.println("  the Lost Cities jar is not on java.class.path, cannot compile the helper");
            return null;
        }
        Path dir = Files.createTempDirectory("caf-perf");
        Path src = dir.resolve(HELPER_CLASS + ".java");
        Files.writeString(src, HELPER_SOURCE, StandardCharsets.US_ASCII);
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        int rc = compiler.run(null, null, null,
                "-nowarn", "-proc:none", "-encoding", "US-ASCII",
                "-classpath", cp, "-d", out.toString(), src.toString());
        if (rc != 0) {
            System.out.println("  helper compilation failed with exit " + rc + " (diagnostics above)");
            return null;
        }
        java.net.URLClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[] {out.toUri().toURL()}, CityFactorPerfSmokeTest.class.getClassLoader());
        Class<?> c = Class.forName(HELPER_CLASS, true, cl);
        System.out.println("  helper compiled from " + src + " and loaded by " + cl);
        return c;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getField(name);
        f.set(target, value);
    }

    private static void bail(String why) {
        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        System.out.println("MEASUREMENT DID NOT RUN: " + why);
        System.exit(1);
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
    // helper source (compiled at runtime; kept flush-left because it lives in
    // a text block and incidental indentation must not be stripped wrongly)
    // ------------------------------------------------------------------

    private static final String HELPER_SOURCE = """
import java.util.HashMap;
import java.util.Random;

/**
 * Generated by CityFactorPerfSmokeTest: a DIRECT (non-reflective) IDimensionInfo
 * so the timed loops contain no reflection and no proxy dispatch, plus a few
 * static methods that time one Lost Cities operation each.
 *
 * getWorld() returns null. On this path the four predefined-building lookups and
 * the predefined-street lookup only pass it to City.calculateMap, which returns
 * immediately because the predefined maps are non-null (set by the test), and
 * the two "provider.getWorld() != null" guards in getCityFactor then skip the
 * heightmap gate and the world-style city-chance multiplier. Both skipped steps
 * are O(1) per call, not per candidate.
 */
public final class CafCityFactorPerfProbe {

    public static volatile Object SINK_COORD = null;
    public static volatile long SINK = 0L;

    /** Models the "isCityCenter cache" the decision is about. */
    public static final HashMap<mcjty.lostcities.varia.ChunkCoord, Boolean> CACHE = new HashMap<>();

    public static final class Prov implements mcjty.lostcities.worldgen.IDimensionInfo {

        public final mcjty.lostcities.config.LostCityProfile prof;
        public final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> type;
        public final long seed;

        public Prov(mcjty.lostcities.config.LostCityProfile prof, long seed) {
            this.prof = prof;
            this.seed = seed;
            this.type = levelType0();
        }

        // ---- the six methods this code path uses ----
        @Override
        public long getSeed() {
            return seed;
        }

        @Override
        public net.minecraft.world.level.WorldGenLevel getWorld() {
            return null;
        }

        @Override
        public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getType() {
            return type;
        }

        @Override
        public mcjty.lostcities.config.LostCityProfile getProfile() {
            return prof;
        }

        @Override
        public mcjty.lostcities.config.LostCityProfile getOutsideProfile() {
            return prof;
        }

        @Override
        public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() {
            return type;
        }

        // ---- never reached on this path; throwing makes that visible ----
        @Override
        public void setWorld(net.minecraft.world.level.WorldGenLevel world) {
            throw new UnsupportedOperationException("setWorld");
        }

        @Override
        public mcjty.lostcities.worldgen.lost.cityassets.WorldStyle getWorldStyle() {
            throw new UnsupportedOperationException("getWorldStyle");
        }

        @Override
        public Random getRandom() {
            throw new UnsupportedOperationException("getRandom");
        }

        @Override
        public mcjty.lostcities.worldgen.LostCityTerrainFeature getFeature() {
            throw new UnsupportedOperationException("getFeature");
        }

        @Override
        public mcjty.lostcities.worldgen.ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
            throw new UnsupportedOperationException("getHeightmap(int,int)");
        }

        @Override
        public mcjty.lostcities.worldgen.ChunkHeightmap getHeightmap(mcjty.lostcities.varia.ChunkCoord coord) {
            throw new UnsupportedOperationException("getHeightmap(ChunkCoord)");
        }

        @Override
        public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos pos) {
            throw new UnsupportedOperationException("getBiome");
        }
    }

    private static final int BASE_X = 1400;
    private static final int BASE_Z = -2600;

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelType0() {
        return net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"));
    }

    public static Object levelType() {
        return levelType0();
    }

    public static Object makeProvider(Object profile) {
        return new Prov((mcjty.lostcities.config.LostCityProfile) profile, 987654321L);
    }

    @SuppressWarnings("unchecked")
    public static Object makeCoord(Object type, int cx, int cz) {
        return new mcjty.lostcities.varia.ChunkCoord(
                (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>) type, cx, cz);
    }

    private static int offset(Prov p) {
        return (p.prof.CITY_MAXRADIUS + 15) / 16;
    }

    /** {offset, side, candidates, city centers in the window} */
    public static int[] windowStats(Object provider) {
        Prov p = (Prov) provider;
        int off = offset(p);
        int side = 2 * off + 1;
        int centers = 0;
        for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
            for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                if (mcjty.lostcities.worldgen.lost.City.isCityCenter(
                        new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz), p)) {
                    centers++;
                }
            }
        }
        return new int[] {off, side, side * side, centers};
    }

    /**
     * One window of a single operation. The loops are nested exactly like the
     * real code so no division ever runs inside a timed loop; iters is the
     * candidate count of one window.
     */
    public static long timeProfile(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long sink = 0L;
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    if (mcjty.lostcities.worldgen.lost.BuildingInfo.getProfile(
                            new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz), p) == p.prof) {
                        sink++;
                    }
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        SINK = sink;
        return t1 - t0;
    }

    public static long timeCenter(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long sink = 0L;
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    if (mcjty.lostcities.worldgen.lost.City.isCityCenter(
                            new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz), p)) {
                        sink++;
                    }
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        SINK = sink;
        return t1 - t0;
    }

    public static long timeRadius(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long sink = 0L;
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    sink += Float.floatToIntBits(mcjty.lostcities.worldgen.lost.City.getCityRadius(
                            new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz), p));
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        SINK = sink;
        return t1 - t0;
    }

    /** ChunkCoord allocation alone. The volatile store defeats escape analysis. */
    public static long timeAlloc(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    SINK_COORD = new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz);
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        return t1 - t0;
    }

    /** Fill the cache with one entry per scanned chunk (the cache the decision is about). */
    public static void primeCache(Object provider) {
        Prov p = (Prov) provider;
        int off = offset(p);
        for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
            for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                mcjty.lostcities.varia.ChunkCoord c = new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz);
                CACHE.put(c, mcjty.lostcities.worldgen.lost.City.isCityCenter(c, p));
            }
        }
    }

    /** What a City.isCityCenter cache would cost per candidate instead of recomputing. */
    public static long timeCacheProbe(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long sink = 0L;
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    mcjty.lostcities.varia.ChunkCoord c = new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz);
                    if (Boolean.TRUE.equals(CACHE.get(c))) {
                        sink++;
                    }
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        SINK = sink;
        return t1 - t0;
    }

    // ---- cache design B: primitive open-addressing table, packed long key ----
    // This is the fastest realistic cache: no allocation per probe, no boxing,
    // one array access instead of chasing HashMap nodes.

    private static long[] PCACHE_KEYS = null;
    private static boolean[] PCACHE_VALS = null;

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    public static void primePrimitiveCache(Object provider) {
        Prov p = (Prov) provider;
        int off = offset(p);
        int side = 2 * off + 1;
        int cap = Integer.highestOneBit(side * side * 2) << 1;
        long[] keys = new long[cap];
        java.util.Arrays.fill(keys, Long.MIN_VALUE);
        boolean[] vals = new boolean[cap];
        int mask = cap - 1;
        for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
            for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                long k = pack(cx, cz);
                boolean v = mcjty.lostcities.worldgen.lost.City.isCityCenter(
                        new mcjty.lostcities.varia.ChunkCoord(p.type, cx, cz), p);
                int idx = mix(k) & mask;
                while (keys[idx] != Long.MIN_VALUE && keys[idx] != k) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = k;
                vals[idx] = v;
            }
        }
        PCACHE_KEYS = keys;
        PCACHE_VALS = vals;
    }

    public static long timePrimitiveCacheProbe(Object provider, int iters) {
        Prov p = (Prov) provider;
        int off = offset(p);
        long[] keys = PCACHE_KEYS;
        boolean[] vals = PCACHE_VALS;
        int mask = keys.length - 1;
        long sink = 0L;
        long t0 = System.nanoTime();
        int done = 0;
        while (done < iters) {
            for (int cx = BASE_X - off; cx <= BASE_X + off; cx++) {
                for (int cz = BASE_Z - off; cz <= BASE_Z + off; cz++) {
                    long k = pack(cx, cz);
                    int idx = mix(k) & mask;
                    while (keys[idx] != k) {
                        idx = (idx + 1) & mask;         // every key primed above is present
                    }
                    if (vals[idx]) {
                        sink++;
                    }
                }
            }
            done += (2 * off + 1) * (2 * off + 1);
        }
        long t1 = System.nanoTime();
        SINK = sink;
        return t1 - t0;
    }

    /**
     * Time one real City.getCityFactor call per sample. factorProfile is the
     * profile passed as the third argument, so a provider whose getProfile()
     * returns a DIFFERENT instance can be used to make the "same profile"
     * comparison fail (that is how isCityCenter gets switched off).
     *
     * @return {sink, nanos of sample 0, nanos of sample 1, ...}
     */
    public static long[] timeFactorWith(Object provider, Object factorProfile, int warmup, int iters) {
        Prov p = (Prov) provider;
        mcjty.lostcities.config.LostCityProfile prof =
                (mcjty.lostcities.config.LostCityProfile) factorProfile;
        long sink = 0L;
        for (int i = 0; i < warmup; i++) {
            sink += Float.floatToIntBits(mcjty.lostcities.worldgen.lost.City.getCityFactor(
                    new mcjty.lostcities.varia.ChunkCoord(p.type, BASE_X + i, BASE_Z - i), p, prof));
        }
        long[] out = new long[iters + 1];
        for (int i = 0; i < iters; i++) {
            int d = warmup + i;
            mcjty.lostcities.varia.ChunkCoord c =
                    new mcjty.lostcities.varia.ChunkCoord(p.type, BASE_X + d, BASE_Z - d);
            long t0 = System.nanoTime();
            float f = mcjty.lostcities.worldgen.lost.City.getCityFactor(c, p, prof);
            long t1 = System.nanoTime();
            out[i + 1] = t1 - t0;
            sink += Float.floatToIntBits(f);
        }
        out[0] = sink;
        SINK = sink;
        return out;
    }
}
""";
}
