import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.config.CityGenConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Verify LcCustomCityMod.forceProfileSettings() against the REAL Lost Cities classes.
 *
 * Why this test exists: the force-override writes values by REFLECTION on field names.
 * A typo (e.g. RUBBLE_LAYER instead of RUBBLELAYER) throws NoSuchFieldException which
 * is swallowed inside forceProfileSettings() -> silent failure: the game starts fine,
 * buildings get eaten by ruins, and only one log line hints at it. So every entry of
 * FORCED_PROFILE_VALUES is checked here against the real LostCityProfile class.
 *
 * This probe reads FORCED_PROFILE_VALUES itself (private field, via setAccessible),
 * so it can never drift out of sync with the mod again.
 *
 * Runs in Group B: the classpath has both the real lostcities jar and this mod.
 */
public class ProfileForceProbe {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Profile force-override: verified against real Lost Cities classes ===");

        Class<?> profileCls = Class.forName("mcjty.lostcities.config.LostCityProfile");
        Class<?> setupCls = Class.forName("mcjty.lostcities.config.ProfileSetup");
        System.out.println("LostCityProfile loaded from: "
                + profileCls.getProtectionDomain().getCodeSource().getLocation());

        // ---- 0. Read FORCED_PROFILE_VALUES straight out of the mod ----
        Field tableField = LcCustomCityMod.class.getDeclaredField("FORCED_PROFILE_VALUES");
        tableField.setAccessible(true);
        Object[][] forced = (Object[][]) tableField.get(null);
        check(forced.length > 0, "FORCED_PROFILE_VALUES is not empty (" + forced.length + " entries)");

        // ---- 1. Every field name must exist and the value type must be assignable ----
        for (Object[] entry : forced) {
            String name = (String) entry[0];
            Object value = entry[1];
            try {
                Field f = profileCls.getField(name);
                check(isAssignable(f.getType(), value),
                        "LostCityProfile." + name + " exists, type " + f.getType().getSimpleName()
                                + " <- " + value.getClass().getSimpleName() + " " + value);
            } catch (NoSuchFieldException e) {
                check(false, "LostCityProfile." + name
                        + " does NOT exist -- typo in FORCED_PROFILE_VALUES, override would silently fail!");
            }
        }

        // Reverse check: make sure near-miss names still do not exist (guards against regressions)
        for (String guess : new String[]{"RUBBLE_LAYER", "GROUND_LEVEL", "GROUNDLEVEL_OVERRIDE",
                "USE_AVERAGE_HEIGHTMAP", "CITY_LEVEL_0_HEIGHT"}) {
            try {
                profileCls.getField(guess);
                check(false, guess + " unexpectedly exists -- maybe that is the correct name?");
            } catch (NoSuchFieldException e) {
                check(true, "confirmed there is no " + guess);
            }
        }

        // ---- 2. Push a probe profile into STANDARD_PROFILES and see it get rewritten ----
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = (Map<String, Object>) setupCls.getField("STANDARD_PROFILES").get(null);
        System.out.println("STANDARD_PROFILES initially holds " + profiles.size()
                + " profiles (usually 0 offline, preInit never ran)");

        Object probe = profileCls.getConstructor(String.class, boolean.class)
                .newInstance("caf_probe", false);
        profiles.put("caf_probe", probe);

        // Set every forced field to a deliberately WRONG value first, so "changed" and
        // "never touched" are distinguishable in step 3.
        for (Object[] entry : forced) {
            String name = (String) entry[0];
            Field f = profileCls.getField(name);
            f.set(probe, wrongValue(f.getType(), entry[1]));
        }

        int touched = LcCustomCityMod.forceProfileSettings();
        check(touched >= 1, "forceProfileSettings touched at least 1 profile (actual " + touched + ")");

        // ---- 3. Verify every forced value landed ----
        for (Object[] entry : forced) {
            expect(profileCls, probe, (String) entry[0], entry[1]);
        }

        // ---- 4. Running again must be idempotent ----
        int again = LcCustomCityMod.forceProfileSettings();
        check(again == touched, "second run is idempotent (first " + touched + ", second " + again + ")");

        // ---- 5. Config-file driven behaviour (file name via CityGenConfig.FILE_NAME) ----
        Path cfgDir = Files.createTempDirectory("caf-citygen");
        try {
            // 5a. Missing file -> default template written + built-in defaults forced
            setAllWrong(profileCls, probe, forced);
            LcCustomCityMod.forceProfileSettings(cfgDir);
            Path cfgFile = cfgDir.resolve(CityGenConfig.FILE_NAME);
            check(Files.isRegularFile(cfgFile), "missing config file -> template generated: " + cfgFile.getFileName());
            for (Object[] entry : forced) {
                expect(profileCls, probe, (String) entry[0], entry[1]);
            }

            // 5b. Broken JSON -> warning path, built-in defaults still forced, no crash
            Files.writeString(cfgFile, "{ this is not valid json !!!", StandardCharsets.UTF_8);
            setAllWrong(profileCls, probe, forced);
            LcCustomCityMod.forceProfileSettings(cfgDir);
            for (Object[] entry : forced) {
                expect(profileCls, probe, (String) entry[0], entry[1]);
            }
            System.out.println("  [OK]   broken JSON -> defaults still forced (no crash)");

            // 5c. Modified value -> that value is forced, everything else stays default
            // Regenerate a clean template first (5b left broken JSON behind).
            Files.deleteIfExists(cfgFile);
            LcCustomCityMod.forceProfileSettings(cfgDir);
            JsonObject root = JsonParser.parseString(Files.readString(cfgFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String glSection = CityGenConfig.sectionFor("GROUNDLEVEL");
            String glKey = CityGenConfig.keyFor("GROUNDLEVEL");
            check(glSection != null && glKey != null, "mapping table knows GROUNDLEVEL key/section");
            JsonObject sec = root.getAsJsonObject(glSection);
            check(sec != null, "template contains section for GROUNDLEVEL");
            check(sec.has(glKey), "template contains key for GROUNDLEVEL");
            int customGround = 73;
            sec.addProperty(glKey, customGround);
            Files.writeString(cfgFile, root.toString(), StandardCharsets.UTF_8);

            setAllWrong(profileCls, probe, forced);
            LcCustomCityMod.forceProfileSettings(cfgDir);
            expect(profileCls, probe, "GROUNDLEVEL", customGround);
            for (Object[] entry : forced) {
                String name = (String) entry[0];
                if (!name.equals("GROUNDLEVEL")) {
                    expect(profileCls, probe, name, entry[1]);
                }
            }
        } finally {
            profiles.remove("caf_probe");
            try (Stream<Path> walk = Files.walk(cfgDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort cleanup of the temp dir
                    }
                });
            }
        }

        System.out.println("\n================================\npassed " + pass + ", failed " + fail);
        if (fail == 0) {
            System.out.println("PROFILE-FORCE-ALL-PASS");
            System.exit(0);
        }
        System.out.println("PROFILE-FORCE-HAS-FAILURES");
        System.exit(1);
    }

    /** Set every forced field on the probe profile to a deliberately WRONG value. */
    private static void setAllWrong(Class<?> profileCls, Object probe, Object[][] forced) throws Exception {
        for (Object[] entry : forced) {
            Field f = profileCls.getField((String) entry[0]);
            f.set(probe, wrongValue(f.getType(), entry[1]));
        }
    }

    /** A value that is guaranteed to differ from the forced one, matching the field type. */
    private static Object wrongValue(Class<?> type, Object forced) {
        if (type == float.class) {
            return 0.9f;
        }
        if (type == double.class) {
            return 0.9d;
        }
        if (type == int.class) {
            return -999;
        }
        if (type == boolean.class) {
            return !((Boolean) forced);
        }
        if (type == String.class) {
            return "WRONG_VALUE";
        }
        return null;
    }

    private static boolean isAssignable(Class<?> type, Object value) {
        if (type == float.class) {
            return value instanceof Float;
        }
        if (type == double.class) {
            return value instanceof Double;
        }
        if (type == int.class) {
            return value instanceof Integer;
        }
        if (type == boolean.class) {
            return value instanceof Boolean;
        }
        return type.isInstance(value);
    }

    private static void expect(Class<?> cls, Object target, String name, Object want) throws Exception {
        Object got = cls.getField(name).get(target);
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        check(ok, name + " = " + got + " (expected " + want + ")");
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
