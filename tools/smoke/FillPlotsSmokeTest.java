import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Offline smoke test for EmptyPlotRule + EmptyPlotConfig, i.e. the
 * "fill the empty single-cell plots" feature (config key: fill empty plots).
 *
 * What it locks down:
 *   A. CORE: the original value true is NEVER touched (all 32 veto masks).
 *   B. RED LINE: a planned road cell is NEVER forced into a building -- and
 *      more generally, with actual=false and enabled=true, a cell is forced
 *      only when EVERY hard veto is absent.
 *   C. RED LINE: predefined street / multi-building section / highway below /
 *      railway below are each enough on their own to block the forcing.
 *   D. The one case we WANT: actual=false, enabled=true and no veto at all
 *      (i.e. the building chance roll is the only reason) -> forced to true.
 *   E. Switch off -> the value always comes back unchanged (all 32 masks,
 *      both actual values).
 *   F. Degenerate / null input (null veto snapshot, all-veto mask) -> the value
 *      comes back unchanged and nothing is thrown.
 *   G. Config wiring: default is true, the template is written with the right
 *      key, reading the key back works, null config dir resets to the default.
 *
 * EmptyPlotRule lives in com.caf.lccustomcity.impl, which the offline suite
 * compiles in the MIXIN group, i.e. AFTER the group-A tests. A direct
 * reference would therefore not compile here, so everything goes through
 * reflection against the production plot-filling implementation.
 *
 * No arguments, no network: the logic under test is pure (G uses a temp dir).
 *
 * This file is intentionally ASCII-only. Chinese appears only inside string
 * literals as unicode escape sequences (never in a comment: javac expands
 * unicode escapes even inside comments).
 */
public final class FillPlotsSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- reflective handles ----
    private static Class<?> ruleCls;
    private static Class<?> vetoesCls;
    private static Method mForce7;
    private static Method mForceVeto;
    private static Constructor<?> vetoesCtor;
    private static Method mVetoesAny;
    private static Method mVetoesNone;

    private static Class<?> cfgCls;
    private static Field fDefault;
    private static Field fKey;
    private static Field fFileName;
    private static Method mEnabled;
    private static Method mInstall;
    private static Method mLoad;

    public static void main(String[] args) {
        try {
            loadReflective();
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] could not load EmptyPlotRule / EmptyPlotConfig: " + t);
            t.printStackTrace(System.out);
            summary();
            return;
        }

        section("A original true is never touched", FillPlotsSmokeTest::testTrueUntouched);
        section("B road is never forced (red line)", FillPlotsSmokeTest::testRoadNeverForced);
        section("C other vetoes block the forcing", FillPlotsSmokeTest::testOtherVetoes);
        section("D only the chance roll -> force=true", FillPlotsSmokeTest::testOnlyChanceRoll);
        section("E switch off -> unchanged", FillPlotsSmokeTest::testSwitchOff);
        section("F degenerate and null input", FillPlotsSmokeTest::testDegenerateInput);
        section("G config wiring", FillPlotsSmokeTest::testConfigWiring);

        summary();
    }

    // ==================================================================
    // reflective loading
    // ==================================================================

    private static void loadReflective() throws Exception {
        ruleCls = Class.forName("com.caf.lccustomcity.impl.EmptyPlotRule");
        vetoesCls = Class.forName("com.caf.lccustomcity.impl.EmptyPlotRule$Vetoes");
        mForce7 = ruleCls.getMethod("shouldForce", boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        mForceVeto = ruleCls.getMethod("shouldForce", boolean.class, boolean.class, vetoesCls);
        vetoesCtor = vetoesCls.getDeclaredConstructor(boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class);
        mVetoesAny = vetoesCls.getMethod("any");
        mVetoesNone = vetoesCls.getMethod("none");

        cfgCls = Class.forName("com.caf.lccustomcity.config.EmptyPlotConfig");
        fDefault = cfgCls.getField("DEFAULT");
        fKey = cfgCls.getField("KEY");
        fFileName = cfgCls.getField("FILE_NAME");
        mEnabled = cfgCls.getMethod("enabled");
        mInstall = cfgCls.getMethod("install", boolean.class);
        mLoad = cfgCls.getMethod("load", Path.class, List.class);
    }

    // ==================================================================
    // helpers: the rule under test, driven only through reflection
    // ==================================================================

    /** Call the primitive-overload rule. mask bits: 0 street, 1 multi, 2 road, 3 highway, 4 railway. */
    private static boolean force(boolean actual, boolean enabled, int mask) throws Exception {
        return (Boolean) mForce7.invoke(null, actual, enabled,
                bit(mask, 0), bit(mask, 1), bit(mask, 2), bit(mask, 3), bit(mask, 4));
    }

    /** Call the Vetoes-overload rule with an explicit snapshot object (may be null). */
    private static boolean forceVeto(boolean actual, boolean enabled, Object vetoes) throws Exception {
        return (Boolean) mForceVeto.invoke(null, actual, enabled, vetoes);
    }

    private static boolean bit(int mask, int i) {
        return (mask & (1 << i)) != 0;
    }

    private static Object newVetoes(int mask) throws Exception {
        return vetoesCtor.newInstance(bit(mask, 0), bit(mask, 1), bit(mask, 2), bit(mask, 3), bit(mask, 4));
    }

    private static final int ALL_MASKS = 32;

    // ==================================================================
    // sections
    // ==================================================================

    private static void testTrueUntouched() throws Exception {
        check(force(true, true, 0), "true + switch on + no veto -> still true");
        check(force(true, true, 31), "true + switch on + every veto -> still true (never flipped)");
        check(force(true, false, 0), "true + switch off -> still true");
        check(force(true, false, 31), "true + switch off + every veto -> still true");
        int unchanged = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            if (force(true, true, m) && force(true, false, m)) {
                unchanged++;
            }
        }
        check(unchanged == ALL_MASKS, "all " + ALL_MASKS + " veto masks keep actual=true");
    }

    private static void testRoadNeverForced() throws Exception {
        // only the road veto
        check(!force(false, true, 1 << 2), "planned road only -> stays false");
        // road together with anything else
        int blocked = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            if (bit(m, 2) && !force(false, true, m)) {
                blocked++;
            }
        }
        check(blocked == 16, "all 16 road masks stay false (got " + blocked + ")");

        // RED LINE, exhaustive: with actual=false and the switch on, a value is
        // forced ONLY when the mask is empty.
        int mismatches = 0;
        int forced = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            boolean expected = (m == 0);
            boolean got = force(false, true, m);
            if (expected != got) {
                mismatches++;
            }
            if (got) {
                forced++;
            }
        }
        check(mismatches == 0, "exhaustive: forced <=> no veto at all (32 masks)");
        check(forced == 1, "exactly one out of 32 masks is forced (got " + forced + ")");
    }

    private static void testOtherVetoes() throws Exception {
        check(!force(false, true, 1 << 0), "predefined street only -> stays false");
        check(!force(false, true, 1 << 1), "multi-building section only -> stays false");
        check(!force(false, true, 1 << 3), "highway below only -> stays false");
        check(!force(false, true, 1 << 4), "railway below only -> stays false");
        for (int i = 0; i < 5; i++) {
            check(!force(false, true, 1 << i), "single veto bit " + i + " alone blocks the forcing");
        }
        // every non-empty mask blocks
        int blocked = 0;
        for (int m = 1; m < ALL_MASKS; m++) {
            if (!force(false, true, m)) {
                blocked++;
            }
        }
        check(blocked == 31, "all 31 non-empty masks stay false (got " + blocked + ")");
    }

    private static void testOnlyChanceRoll() throws Exception {
        check(force(false, true, 0), "false + switch on + no veto -> forced true (the chance roll case)");
        check(force(false, true, 0) != false, "the forced value is really true");
    }

    private static void testSwitchOff() throws Exception {
        check(!force(false, false, 0), "switch off + no veto -> unchanged false");
        check(force(true, false, 0), "switch off + true -> unchanged true");
        int mismatches = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            if (force(false, false, m) || !force(true, false, m)) {
                mismatches++;
            }
        }
        check(mismatches == 0, "switch off: all 32 masks x both values unchanged");
    }

    private static void testDegenerateInput() throws Exception {
        // null snapshot -> unchanged, no throw
        boolean threw = false;
        boolean r1 = true;
        boolean r2 = true;
        try {
            r1 = forceVeto(false, true, null);
            r2 = forceVeto(true, true, null);
        } catch (Throwable t) {
            threw = true;
        }
        check(!threw, "null veto snapshot does not throw");
        check(!r1, "null veto snapshot + actual=false -> unchanged false");
        check(r2, "null veto snapshot + actual=true -> unchanged true");

        // all-veto snapshot via the object overload
        Object all = newVetoes(31);
        check(!forceVeto(false, true, all), "all-veto snapshot + actual=false -> false");
        check(forceVeto(true, true, all), "all-veto snapshot + actual=true -> true");
        check((Boolean) mVetoesAny.invoke(all), "Vetoes.any() true for the all-veto snapshot");

        Object none = mVetoesNone.invoke(null);
        check(!(Boolean) mVetoesAny.invoke(none), "Vetoes.any() false for Vetoes.none()");
        check(forceVeto(false, true, none), "Vetoes.none() + actual=false -> forced true");

        // both overloads must agree everywhere
        int mismatches = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            Object v = newVetoes(m);
            if (force(false, true, m) != forceVeto(false, true, v)
                    || force(true, true, m) != forceVeto(true, true, v)
                    || force(false, false, m) != forceVeto(false, false, v)) {
                mismatches++;
            }
        }
        check(mismatches == 0, "the two shouldForce overloads agree on all 32 masks");

        // any() must match "the mask is not empty"
        int anyMismatch = 0;
        for (int m = 0; m < ALL_MASKS; m++) {
            if ((Boolean) mVetoesAny.invoke(newVetoes(m)) != (m != 0)) {
                anyMismatch++;
            }
        }
        check(anyMismatch == 0, "Vetoes.any() matches (mask != 0)");
    }

    @SuppressWarnings("unchecked")
    private static void testConfigWiring() throws Exception {
        check(fDefault.getBoolean(null), "EmptyPlotConfig.DEFAULT is true");

        // null dir -> built-in default, no file IO
        List<String> w0 = new ArrayList<String>();
        boolean viaNull = (Boolean) mLoad.invoke(null, null, w0);
        check(viaNull, "load(null, ...) returns the default true");
        check(w0.isEmpty(), "load(null, ...) records no warning");
        check((Boolean) mEnabled.invoke(null), "enabled() is true after load(null, ...)");

        String fileName = (String) fFileName.get(null);
        String key = (String) fKey.get(null);
        check(fileName != null && fileName.length() > 0, "FILE_NAME is set");
        check(key != null && key.length() > 0, "KEY is set");

        // fresh dir -> template is written, default true comes back
        Path dir1 = Files.createTempDirectory("fillplots-r1-");
        List<String> w1 = new ArrayList<String>();
        boolean v1 = (Boolean) mLoad.invoke(null, dir1, w1);
        check(v1, "missing file -> built-in default true");
        check(w1.isEmpty(), "writing the template records no warning (got " + w1 + ")");
        Path file1 = dir1.resolve(fileName);
        check(Files.isRegularFile(file1), "template file was created under the config dir");
        String text1 = new String(Files.readAllBytes(file1), StandardCharsets.UTF_8);
        check(text1.contains(key), "template contains the key");
        check(text1.contains("true"), "template contains the default value true");

        // read it back
        List<String> w2 = new ArrayList<String>();
        boolean v2 = (Boolean) mLoad.invoke(null, dir1, w2);
        check(v2, "reading the generated template back yields true");
        check(w2.isEmpty(), "reading the generated template records no warning (got " + w2 + ")");

        // explicit false is honoured
        Path dir2 = Files.createTempDirectory("fillplots-r2-");
        String json = "{\"" + key + "\": false}";
        Files.write(dir2.resolve(fileName), json.getBytes(StandardCharsets.UTF_8));
        List<String> w3 = new ArrayList<String>();
        boolean v3 = (Boolean) mLoad.invoke(null, dir2, w3);
        check(!v3, "key=false in the file -> load returns false");
        check(!(Boolean) mEnabled.invoke(null), "enabled() is false after loading false");
        check(w3.isEmpty(), "a valid file records no warning (got " + w3 + ")");

        // garbage json falls back to the default and only warns
        Path dir3 = Files.createTempDirectory("fillplots-r3-");
        Files.write(dir3.resolve(fileName), "not json at all".getBytes(StandardCharsets.UTF_8));
        List<String> w4 = new ArrayList<String>();
        boolean v4 = (Boolean) mLoad.invoke(null, dir3, w4);
        check(v4, "garbage json -> built-in default true");
        check(!w4.isEmpty(), "garbage json records a warning");

        // install()/enabled() round trip, then put the default back
        mInstall.invoke(null, false);
        check(!(Boolean) mEnabled.invoke(null), "install(false) -> enabled() false");
        mInstall.invoke(null, true);
        check((Boolean) mEnabled.invoke(null), "install(true) -> enabled() true");

        // clean up the temp dirs (best effort, pure ASCII paths)
        deleteQuietly(dir1);
        deleteQuietly(dir2);
        deleteQuietly(dir3);
    }

    private static void deleteQuietly(Path dir) {
        try {
            List<Path> all = new ArrayList<Path>();
            Files.walk(dir).forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
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
            System.out.println("FILLPLOTS-TEST-ALL-PASS");
        } else {
            System.out.println("FILLPLOTS-TEST-FAILED");
            System.exit(1);
        }
    }
}
