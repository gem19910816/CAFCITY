import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Offline smoke test for PlotFill (pure candidate selection for plot filling).
 *
 * What it locks down:
 *   A. CORE: if ANY candidate fits the plot, pickCandidate MUST return non-null.
 *      A candidate that does not fit must never be returned. 800 randomised
 *      plots x candidate lists, plus the "nothing fits -> null" direction.
 *   B. A candidate that is too big is never selected (named ids, 300 seeds).
 *   C. "Prefer the larger footprint": big/mid/small against one plot that can
 *      hold all three -> always the big one; when the big one does not fit ->
 *      the next largest. Within one size band the pick is still random, and a
 *      bigger weight wins more often.
 *   D. Shuffle bag: 10 candidates, 8 draws -> 8 distinct; 12 draws -> the first
 *      10 are a full permutation of all 10, then the bag is refilled and the
 *      next 2 come from the second bag. Plus determinism and null/empty safety.
 *   E. Determinism: same (seed, input) -> same result; a different seed changes
 *      the result at least once.
 *   F. Degenerate input: plot 0 / negative, null or empty candidate list,
 *      illegal candidate sizes, NaN / infinite / zero / negative weights, and
 *      Integer.MAX_VALUE sizes -> safe null or a safe non-null, never a throw.
 *
 * PlotFill lives in com.caf.lccustomcity.impl, and the offline suite compiles
 * that package in the MIXIN group, i.e. AFTER the group-A tests. A direct
 * reference would therefore not compile here, so everything goes through
 * reflection -- the same approach ZoneNormalizeSmokeTest uses.
 *
 * No arguments, no files: the logic under test is pure.
 *
 * This file is intentionally ASCII-only. Chinese appears only inside string
 * literals as unicode escape sequences (never in a comment: javac expands
 * unicode escapes even inside comments).
 */
public final class PlotFillSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- reflective handles ----
    private static Class<?> candCls;
    private static Constructor<?> candCtor;
    private static Method mPick;
    private static Method mShuffle;
    private static Method mBagNext;
    private static Method mBagNextN;
    private static Method mBagRefillCount;
    private static Method mBagSize;
    private static Constructor<?> bagCtor;
    private static Field fId;
    private static Field fWidth;
    private static Field fDepth;

    public static void main(String[] args) {
        try {
            loadReflective();
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] could not load com.caf.lccustomcity.impl.PlotFill: " + t);
            t.printStackTrace(System.out);
            summary();
            return;
        }

        section("A core guarantee", PlotFillSmokeTest::testCoreGuarantee);
        section("B no unfit candidate", PlotFillSmokeTest::testUnfitNeverSelected);
        section("C prefer larger footprint", PlotFillSmokeTest::testPreferLarger);
        section("D shuffle bag", PlotFillSmokeTest::testShuffleBag);
        section("E determinism", PlotFillSmokeTest::testDeterminism);
        section("F degenerate input", PlotFillSmokeTest::testDegenerateInput);

        summary();
    }

    // ==================================================================
    // sections
    // ==================================================================

    private static void testCoreGuarantee() throws Exception {
        Random rnd = new Random(20240521L);
        int withFits = 0, withoutFits = 0;
        int missedNonNull = 0, badNull = 0, outOfRange = 0;
        String firstMiss = "";

        for (int t = 0; t < 800; t++) {
            int pw = 1 + rnd.nextInt(12);
            int pd = 1 + rnd.nextInt(12);
            int n = 1 + rnd.nextInt(10);
            List<Object> cs = new ArrayList<Object>();
            boolean anyFits = false;
            for (int i = 0; i < n; i++) {
                // 0 width/depth is deliberately illegal and must be dropped.
                int w = rnd.nextInt(15);
                int d = rnd.nextInt(15);
                double wt = (i % 3 == 0) ? 0.0 : (i % 3 == 1 ? 1.0 : rnd.nextDouble() * 3.0);
                cs.add(cand("t" + t + "_" + i, w, d, wt));
                if (w > 0 && d > 0 && w <= pw && d <= pd) {
                    anyFits = true;
                }
            }
            Object got = pick(pw, pd, cs, (long) t * 7919L + 13L);
            if (anyFits) {
                withFits++;
                if (got == null) {
                    missedNonNull++;
                    if (firstMiss.isEmpty()) {
                        firstMiss = "plot " + pw + "x" + pd + " with " + n + " candidates";
                    }
                } else if (wOf(got) > pw || dOf(got) > pd) {
                    outOfRange++;
                }
            } else {
                withoutFits++;
                if (got != null) {
                    badNull++;
                }
            }
        }

        check(withFits > 0 && missedNonNull == 0,
                "A: " + withFits + " plots WITH a fitting candidate -> always non-null (missed "
                        + missedNonNull + (firstMiss.isEmpty() ? "" : ", first " + firstMiss) + ")");
        check(withoutFits > 0 && badNull == 0,
                "A: " + withoutFits + " plots with NO fitting candidate -> null (bad " + badNull + ")");
        check(outOfRange == 0,
                "A: a returned candidate always fits the plot (" + outOfRange + " out-of-range picks)");
    }

    private static void testUnfitNeverSelected() throws Exception {
        List<Object> cs = new ArrayList<Object>();
        cs.add(cand("big", 5, 5, 1.0));
        cs.add(cand("huge", 9, 9, 100.0));
        cs.add(cand("tall", 1, 4, 50.0));
        cs.add(cand("ok1", 2, 3, 1.0));
        cs.add(cand("ok2", 3, 3, 1.0));

        int nulls = 0, unfit = 0, badId = 0;
        for (long seed = 0; seed < 300; seed++) {
            Object got = pick(3, 3, cs, seed);
            if (got == null) {
                nulls++;
                continue;
            }
            if (wOf(got) > 3 || dOf(got) > 3) {
                unfit++;
            }
            String id = idOf(got);
            if (id.equals("big") || id.equals("huge") || id.equals("tall")) {
                badId++;
            }
        }
        check(nulls == 0, "B: 3x3 plot with two fitting candidates -> never null (" + nulls + " nulls)");
        check(unfit == 0, "B: 5x5 / 9x9 / 1x4 candidates never returned for a 3x3 plot (" + unfit + ")");
        check(badId == 0, "B: only ids ok1 / ok2 were ever returned (" + badId + " bad ids)");
    }

    private static void testPreferLarger() throws Exception {
        // C1: one plot that can hold all three -> always the largest area.
        List<Object> all = new ArrayList<Object>();
        all.add(cand("small", 1, 1, 5.0));
        all.add(cand("mid", 2, 3, 5.0));
        all.add(cand("big", 4, 5, 1.0));
        int notBig = 0;
        String sample = "";
        for (long seed = 0; seed < 100; seed++) {
            Object got = pick(4, 5, all, seed);
            if (got == null || !idOf(got).equals("big")) {
                notBig++;
                if (sample.isEmpty() && got != null) {
                    sample = idOf(got);
                }
            }
        }
        check(notBig == 0,
                "C: plot 4x5 holding 1x1 / 2x3 / 4x5 -> always the 4x5 (area) candidate"
                        + (notBig == 0 ? "" : ", " + notBig + " misses e.g. " + sample));

        // C2: the big one does not fit -> the next largest area wins.
        // 2x3 excludes 4x5 (width) and 2x3 fits, 1x1 fits -> the 2x3 must win.
        int notMid = 0;
        for (long seed = 0; seed < 100; seed++) {
            Object got = pick(2, 3, all, seed);
            if (got == null || !idOf(got).equals("mid")) {
                notMid++;
            }
        }
        check(notMid == 0, "C: plot 2x3 excludes 4x5 -> always the 2x3 candidate (" + notMid + " misses)");

        // C3: same size band -> still random, but only within that band.
        List<Object> twins = new ArrayList<Object>();
        twins.add(cand("a", 2, 2, 1.0));
        twins.add(cand("b", 2, 2, 1.0));
        int ca = 0, cb = 0, other = 0;
        for (long seed = 0; seed < 400; seed++) {
            Object got = pick(3, 3, twins, seed);
            if (got == null) {
                other++;
                continue;
            }
            String id = idOf(got);
            if (id.equals("a")) {
                ca++;
            } else if (id.equals("b")) {
                cb++;
            } else {
                other++;
            }
        }
        check(ca > 0 && cb > 0 && other == 0,
                "C: two same-size candidates -> both appear (a=" + ca + ", b=" + cb + ", bad=" + other + ")");

        // C4: inside one size band the weight is respected.
        List<Object> weighted = new ArrayList<Object>();
        weighted.add(cand("heavy", 2, 2, 3.0));
        weighted.add(cand("light", 2, 2, 1.0));
        int heavy = 0, light = 0;
        for (long seed = 0; seed < 400; seed++) {
            Object got = pick(3, 3, weighted, seed);
            if (got != null && idOf(got).equals("heavy")) {
                heavy++;
            } else if (got != null) {
                light++;
            }
        }
        check(heavy > light,
                "C: weight 3.0 vs 1.0 in the same band -> heavier wins more (heavy=" + heavy + ", light=" + light + ")");
    }

    private static void testShuffleBag() throws Exception {
        int n = 10;
        List<Object> ten = new ArrayList<Object>();
        Set<String> allIds = new HashSet<String>();
        for (int i = 0; i < n; i++) {
            ten.add(cand("c" + i, 2, 2, 1.0));
            allIds.add("c" + i);
        }
        long seed = 424242L;

        // D1: one bag is a full permutation.
        List<Object> bag = shuffle(ten, seed);
        check(bag.size() == n, "D: shuffledBag keeps every candidate (size " + bag.size() + ")");
        Set<String> bagIds = new HashSet<String>();
        for (Object c : bag) {
            bagIds.add(idOf(c));
        }
        check(bagIds.size() == n && bagIds.equals(allIds),
                "D: shuffledBag is a permutation of the input (" + bagIds.size() + " distinct)");

        // D2: taking 8 out of the 10-item bag gives 8 distinct picks.
        Set<String> first8 = new HashSet<String>();
        for (int i = 0; i < 8; i++) {
            first8.add(idOf(bag.get(i)));
        }
        check(first8.size() == 8, "D: drawing 8 of 10 -> 8 distinct ids (" + first8.size() + ")");

        // D3: pure and deterministic.
        List<Object> again = shuffle(ten, seed);
        check(sameIds(bag, again), "D: shuffledBag(seed) twice -> identical order");

        // D4: a different seed changes the order at least once.
        boolean orderDiff = false;
        for (long s = 1; s <= 32 && !orderDiff; s++) {
            if (!sameIds(bag, shuffle(ten, s))) {
                orderDiff = true;
            }
        }
        check(orderDiff, "D: a different seed changes the shuffled order");

        // D5: the stateful bag -- 10 draws are a full permutation.
        Object b1 = bagCtor.newInstance(ten, seed);
        List<String> draws = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            Object c = mBagNext.invoke(b1);
            draws.add(c == null ? "<null>" : idOf(c));
        }
        Set<String> first10 = new HashSet<String>(draws.subList(0, 10));
        check(first10.size() == 10 && first10.equals(allIds),
                "D: bag 12 draws -> the first 10 are all 10 candidates exactly once (" + first10.size() + ")");
        check(!draws.get(10).equals("<null>") && !draws.get(11).equals("<null>")
                        && !draws.get(10).equals(draws.get(11)),
                "D: draws 11 and 12 come from the refilled bag and are distinct ("
                        + draws.get(10) + ", " + draws.get(11) + ")");
        check(((Integer) mBagRefillCount.invoke(b1)).intValue() == 2,
                "D: after 12 draws from a 10-item bag -> 2 bags filled ("
                        + mBagRefillCount.invoke(b1) + ")");
        check(((Integer) mBagSize.invoke(b1)).intValue() == 10, "D: bag size stays 10 after refills");

        // D6: refill boundary.
        Object b2 = bagCtor.newInstance(ten, seed);
        for (int i = 0; i < 10; i++) {
            mBagNext.invoke(b2);
        }
        int rcAfter10 = ((Integer) mBagRefillCount.invoke(b2)).intValue();
        mBagNext.invoke(b2);
        int rcAfter11 = ((Integer) mBagRefillCount.invoke(b2)).intValue();
        check(rcAfter10 == 1 && rcAfter11 == 2,
                "D: refillCount is 1 after 10 draws and 2 after the 11th (" + rcAfter10 + " -> " + rcAfter11 + ")");

        // D7: the whole 12-draw sequence is reproducible from the seed.
        Object b3 = bagCtor.newInstance(ten, seed);
        boolean sameSeq = true;
        for (int i = 0; i < 12 && sameSeq; i++) {
            Object c = mBagNext.invoke(b3);
            String id = (c == null ? "<null>" : idOf(c));
            if (!id.equals(draws.get(i))) {
                sameSeq = false;
            }
        }
        check(sameSeq, "D: same (seed, input) -> the same 12-draw sequence");

        // D8: a different seed changes the sequence at least once.
        boolean seqDiff = false;
        for (long s = 1; s <= 32 && !seqDiff; s++) {
            Object bs = bagCtor.newInstance(ten, s);
            for (int i = 0; i < 12; i++) {
                Object c = mBagNext.invoke(bs);
                String id = (c == null ? "<null>" : idOf(c));
                if (!id.equals(draws.get(i))) {
                    seqDiff = true;
                    break;
                }
            }
        }
        check(seqDiff, "D: a different seed changes the draw sequence");

        // D9: batch draw, single candidate, null/empty safety.
        Object b4 = bagCtor.newInstance(ten, seed);
        List<?> batch = (List<?>) mBagNextN.invoke(b4, Integer.valueOf(12));
        check(batch.size() == 12, "D: next(12) returns 12 candidates (" + batch.size() + ")");

        List<Object> one = new ArrayList<Object>();
        one.add(cand("solo", 3, 3, 1.0));
        Object b5 = bagCtor.newInstance(one, 7L);
        boolean soloOk = true;
        for (int i = 0; i < 5; i++) {
            Object c = mBagNext.invoke(b5);
            if (c == null || !idOf(c).equals("solo")) {
                soloOk = false;
            }
        }
        check(soloOk, "D: a 1-candidate bag keeps returning that candidate");

        List<Object> withNull = new ArrayList<Object>();
        withNull.add(null);
        withNull.add(cand("x", 1, 1, 1.0));
        withNull.add(null);
        check(shuffle(withNull, 1L).size() == 1, "D: shuffledBag drops null elements");
        check(shuffle(null, 1L).isEmpty(), "D: shuffledBag(null) -> empty list");
        check(shuffle(new ArrayList<Object>(), 1L).isEmpty(), "D: shuffledBag(empty) -> empty list");

        Object b6 = bagCtor.newInstance(null, 1L);
        check(((Integer) mBagSize.invoke(b6)).intValue() == 0 && mBagNext.invoke(b6) == null
                        && ((List<?>) mBagNextN.invoke(b6, Integer.valueOf(3))).isEmpty()
                        && ((Integer) mBagRefillCount.invoke(b6)).intValue() == 0,
                "D: Bag(null) -> empty bag, next() null, next(3) empty, no refill");

        Object b7 = bagCtor.newInstance(new ArrayList<Object>(), 1L);
        check(mBagNext.invoke(b7) == null, "D: Bag(empty) -> next() null");
    }

    private static void testDeterminism() throws Exception {
        List<Object> cs = new ArrayList<Object>();
        cs.add(cand("k0", 2, 2, 1.0));
        cs.add(cand("k1", 2, 2, 1.0));
        cs.add(cand("k2", 3, 2, 1.0));
        cs.add(cand("k3", 1, 1, 1.0));
        cs.add(cand("k4", 4, 4, 1.0));

        int mismatch = 0;
        for (long seed = 0; seed < 200; seed++) {
            Object a = pick(4, 4, cs, seed);
            Object b = pick(4, 4, cs, 0L + seed);
            if (a == null || b == null || !idOf(a).equals(idOf(b))) {
                mismatch++;
            }
        }
        check(mismatch == 0, "E: same (seed, input) over 200 seeds -> identical picks (" + mismatch + " mismatches)");

        // The seed only changes the pick inside one size band (a bigger footprint
        // always wins), so use four same-size candidates here.
        List<Object> sameBand = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) {
            sameBand.add(cand("s" + i, 2, 2, 1.0));
        }
        Set<String> distinct = new HashSet<String>();
        for (long seed = 0; seed < 64; seed++) {
            Object got = pick(3, 3, sameBand, seed);
            distinct.add(got == null ? "<null>" : idOf(got));
        }
        check(distinct.size() > 1,
                "E: a different seed changes the pick inside one size band (distinct ids " + distinct.size() + ")");
    }

    private static void testDegenerateInput() throws Exception {
        List<Object> ok = new ArrayList<Object>();
        ok.add(cand("ok", 2, 2, 1.0));

        check(pick(0, 5, ok, 1L) == null, "F: plot width 0 -> null");
        check(pick(5, 0, ok, 1L) == null, "F: plot depth 0 -> null");
        check(pick(-1, 5, ok, 1L) == null, "F: plot width -1 -> null");
        check(pick(5, -3, ok, 1L) == null, "F: plot depth -3 -> null");
        check(pick(0, 0, ok, 1L) == null, "F: plot 0x0 -> null");
        check(pick(Integer.MIN_VALUE, 5, ok, 1L) == null, "F: plot width Integer.MIN_VALUE -> null");
        check(pick(5, 5, null, 1L) == null, "F: candidate list null -> null");
        check(pick(5, 5, new ArrayList<Object>(), 1L) == null, "F: candidate list empty -> null");
        check(pick(0, 0, null, 0L) == null, "F: null list and 0x0 plot -> null");

        List<Object> illegal = new ArrayList<Object>();
        illegal.add(cand("zeroW", 0, 3, 1.0));
        illegal.add(cand("zeroD", 3, 0, 1.0));
        illegal.add(cand("negW", -2, 3, 1.0));
        illegal.add(cand("negD", 3, -2, 1.0));
        illegal.add(cand("zerozero", 0, 0, 1.0));
        check(pick(9, 9, illegal, 1L) == null, "F: only illegal sizes -> null (never a throw)");

        List<Object> mixed = new ArrayList<Object>();
        mixed.add(null);
        mixed.add(cand("bad", 0, 0, 1.0));
        mixed.add(cand("good", 3, 3, 1.0));
        mixed.add(null);
        Object got = pick(4, 4, mixed, 5L);
        check(got != null && idOf(got).equals("good"),
                "F: null elements and illegal sizes are skipped -> the one good candidate wins");

        double[] weird = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -5.0, 0.0, Double.MAX_VALUE, Double.MIN_VALUE};
        int weirdNull = 0;
        for (double w : weird) {
            List<Object> one = new ArrayList<Object>();
            one.add(cand("w", 2, 2, w));
            if (pick(3, 3, one, 9L) == null) {
                weirdNull++;
            }
        }
        check(weirdNull == 0, "F: NaN / infinite / 0 / negative weights still return the only fitting candidate");

        List<Object> twoMax = new ArrayList<Object>();
        twoMax.add(cand("m1", 2, 2, Double.MAX_VALUE));
        twoMax.add(cand("m2", 2, 2, Double.MAX_VALUE));
        check(pick(3, 3, twoMax, 11L) != null, "F: weights summing to infinity -> still a non-null pick");

        List<Object> huge = new ArrayList<Object>();
        huge.add(cand("hugeBoth", Integer.MAX_VALUE, Integer.MAX_VALUE, 1.0));
        huge.add(cand("small", 1, 1, 1.0));
        Object hugeGot = pick(Integer.MAX_VALUE, Integer.MAX_VALUE, huge, 3L);
        check(hugeGot != null && idOf(hugeGot).equals("hugeBoth"),
                "F: Integer.MAX_VALUE plot and candidate sizes -> no area overflow, biggest wins");

        List<Object> dup = new ArrayList<Object>();
        for (int i = 0; i < 32; i++) {
            dup.add(cand("d", 2, 2, 1.0));
        }
        check(pick(2, 2, dup, 1L) != null, "F: duplicate ids are fine -> non-null");
    }

    // ==================================================================
    // reflective plumbing
    // ==================================================================

    private static void loadReflective() throws Exception {
        Class<?> plotCls = Class.forName("com.caf.lccustomcity.impl.PlotFill");
        candCls = Class.forName("com.caf.lccustomcity.impl.PlotFill$Candidate");
        candCtor = candCls.getConstructor(String.class, int.class, int.class, double.class);
        mPick = plotCls.getMethod("pickCandidate", int.class, int.class, List.class, long.class);
        mShuffle = plotCls.getMethod("shuffledBag", List.class, long.class);
        Class<?> bagCls = Class.forName("com.caf.lccustomcity.impl.PlotFill$Bag");
        bagCtor = bagCls.getConstructor(List.class, long.class);
        mBagNext = bagCls.getMethod("next");
        mBagNextN = bagCls.getMethod("next", int.class);
        mBagRefillCount = bagCls.getMethod("refillCount");
        mBagSize = bagCls.getMethod("size");
        fId = candCls.getField("id");
        fWidth = candCls.getField("width");
        fDepth = candCls.getField("depth");
    }

    private static Object cand(String id, int w, int d, double weight) throws Exception {
        return candCtor.newInstance(id, Integer.valueOf(w), Integer.valueOf(d), Double.valueOf(weight));
    }

    private static Object pick(int pw, int pd, List<Object> cs, long seed) throws Exception {
        return mPick.invoke(null, Integer.valueOf(pw), Integer.valueOf(pd), cs, Long.valueOf(seed));
    }

    private static List<Object> shuffle(List<Object> cs, long seed) throws Exception {
        Object r = mShuffle.invoke(null, cs, Long.valueOf(seed));
        List<Object> out = new ArrayList<Object>();
        if (r != null) {
            for (Object o : (List<?>) r) {
                out.add(o);
            }
        }
        return out;
    }

    private static String idOf(Object c) throws Exception {
        return (String) fId.get(c);
    }

    private static int wOf(Object c) throws Exception {
        return ((Integer) fWidth.get(c)).intValue();
    }

    private static int dOf(Object c) throws Exception {
        return ((Integer) fDepth.get(c)).intValue();
    }

    private static boolean sameIds(List<Object> a, List<Object> b) throws Exception {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            String ix = (x == null ? "<null>" : idOf(x));
            String iy = (y == null ? "<null>" : idOf(y));
            if (!ix.equals(iy)) {
                return false;
            }
        }
        return true;
    }

    // ==================================================================
    // harness
    // ==================================================================

    private interface Section {
        void run() throws Exception;
    }

    private static void section(String name, Section s) {
        System.out.println("--- " + name + " ---");
        try {
            s.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + ": unexpected exception " + t);
            t.printStackTrace(System.out);
        }
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

    private static void summary() {
        System.out.println();
        System.out.println("================================");
        System.out.println("\u901A\u8FC7 " + passed + " \u9879, \u5931\u8D25 " + failed + " \u9879");
        if (failed == 0) {
            System.out.println("PLOTFILL-TEST-ALL-PASS");
        } else {
            System.out.println("PLOTFILL-TEST-FAILED");
            System.exit(1);
        }
    }
}
