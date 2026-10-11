import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.config.SpawnCityConfig;
import com.caf.lccustomcity.config.ZoneConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Offline smoke test for the "guarantee a city CENTRE within N blocks of the spawn"
 * feature (chu sheng dian fu jin cheng shi).
 *
 * 2026-10 SEMANTICS SWITCH (player request: "keep a city centre 850 blocks from the
 * spawn instead of a visible city edge 200 blocks away"):
 *   OLD: criterion = distance from the spawn to the VISIBLE city edge
 *        (centre distance - nominal radius * (1 - CITY_THRESHOLD)); the synthetic
 *        city was placed with its visible edge at N, i.e. centre at
 *        visibleRadius + N.  Key: spawn city EDGE distance, default 200.
 *   NEW: criterion = distance from the spawn to the city CENTRE; the radius is not
 *        part of the criterion at all; the synthetic city is placed with its
 *        CENTRE about N blocks from the spawn.  Key: spawn city CENTRE distance,
 *        default 850.
 *
 * Covered here:
 *   1. SpawnCityConfig parsing: missing file -> default 850 + template written;
 *      explicit 0 -> off; broken JSON / wrong type -> default 850 + warning;
 *      negative -> 0 + warning; 1..15 -> clamped to 16 (chunk alignment).
 *   1b. COMPAT: the obsolete edge-distance key (value 200) is tolerated - the new
 *      key is absent, so the default 850 applies, no warning is recorded, and the
 *      old value is never used; next to a new key the new key wins.
 *   2. The pure geometry in com.caf.lccustomcity.impl.SpawnCityPlan, reached by
 *      REFLECTION because the impl package is compiled in the mixin group (after
 *      the group-A tests), so a direct reference would not compile. That class
 *      deliberately references no game classes, so it loads fine offline.
 *   2b. the radius chain: the zone config file (city section, in chunks) 50/50 ->
 *      profile 800/800 blocks -> synthetic city radius 800 -> visible city radius
 *      640 (0.8 * 800); an explicit config value still wins over the default.
 *      - determinism: same (seed, spawn, target chunk) -> same result, always
 *      - "already satisfied" vs "needs a city" are distinguishable
 *      - THE CRITERION IS THE CENTRE DISTANCE: a centre 846 away with a tiny
 *        radius counts as satisfied (its visible edge is 766 away), while a centre
 *        1000 away with radius 1000 does NOT (its visible edge is only 200 away).
 *        Both are the mirror image of the old edge rule, so either decision
 *        flipping back would be caught here.
 *        Boundary: centre distance == N counts as satisfied, N + 1 does not.
 *      - the placed centre really lands within N blocks (computed geometrically,
 *        not just trusted from a boolean), 16-aligned, as close to N as the
 *        alignment allows (alignment only pulls the city CLOSER), carries the
 *        configured radius (800), and the spawn chunk stays wilderness
 *      - the native > 0 rule: an existing city factor is never modified
 *
 * NOT covered here (needs the real game classes, verify in game):
 *   com.caf.lccustomcity.impl.SpawnCityRule (reads the server spawn point,
 *   scans City.isCityCenter / City.getCityRadius) and the mixin injection.
 *
 * This file is intentionally ASCII-only; the Chinese config keys and the one
 * Chinese line it prints are written with unicode escapes inside string literals
 * (never inside comments).
 */
public final class SpawnCitySmokeTest {

    /** The OBSOLETE key (spawn city edge distance): must be tolerated and ignored. */
    private static final String OLD_KEY = "\u51fa\u751f\u70b9\u57ce\u5e02\u8fb9\u7f18\u8ddd\u79bb";

    /** Zone config keys (Chinese, escapes): city section, radius min, radius max. */
    private static final String K_CITY = "\u57ce\u5e02";
    private static final String K_RMIN = "\u6700\u5c0f\u534a\u5f84";
    private static final String K_RMAX = "\u6700\u5927\u534a\u5f84";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("spawncity-test");

        System.out.println("=== Spawn city smoke test ===");

        // ---- 1. config parsing (the key is now the CENTRE distance) ----
        Path dir1 = tmp.resolve("missing");
        List<String> w1 = new ArrayList<>();
        int v1 = SpawnCityConfig.load(dir1, w1);
        check(v1 == 850, "missing config file -> default 850 (centre distance)");
        check(SpawnCityConfig.DEFAULT == 850, "built-in default is 850");
        check(SpawnCityConfig.distance() == 850, "distance() reflects default 850");
        check(Files.isRegularFile(dir1.resolve(SpawnCityConfig.FILE_NAME)),
                "template file was written next to the missing config");

        // the generated template must describe the NEW semantics (centre, and the
        // visible edge landing about 850 - 640 = 210 blocks out)
        String template = Files.readString(dir1.resolve(SpawnCityConfig.FILE_NAME), StandardCharsets.UTF_8);
        JsonObject tpl = JsonParser.parseString(template).getAsJsonObject();
        check(tpl.has(SpawnCityConfig.KEY), "template writes the NEW key");
        check(tpl.get(SpawnCityConfig.KEY).getAsInt() == 850, "template writes the new default 850");
        check(!tpl.has(OLD_KEY), "template no longer emits the obsolete edge-distance key");
        check(template.contains("850 - 640 = 210"),
                "template explains that the visible edge lands about 210 blocks out");
        check(template.contains(SpawnCityConfig.KEY),
                "template documentation is keyed on the new key name");
        check(w1.isEmpty(), "writing the template records no warning");

        Path dir2 = tmp.resolve("off");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + SpawnCityConfig.KEY + "\": 0}", StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        int v2 = SpawnCityConfig.load(dir2, w2);
        check(v2 == 0, "explicit 0 in config -> feature off");
        check(SpawnCityConfig.distance() == 0, "distance() reflects 0");
        check(w2.isEmpty(), "no warnings for a valid 0 config");

        Path dir3 = tmp.resolve("broken");
        Files.createDirectories(dir3);
        Files.writeString(dir3.resolve(SpawnCityConfig.FILE_NAME),
                "{ this is not json", StandardCharsets.UTF_8);
        List<String> w3 = new ArrayList<>();
        int v3 = SpawnCityConfig.load(dir3, w3);
        check(v3 == 850, "broken JSON -> default 850");
        check(!w3.isEmpty(), "broken JSON recorded a warning");

        Path dir4 = tmp.resolve("wrongtype");
        Files.createDirectories(dir4);
        Files.writeString(dir4.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + SpawnCityConfig.KEY + "\": true}", StandardCharsets.UTF_8);
        List<String> w4 = new ArrayList<>();
        int v4 = SpawnCityConfig.load(dir4, w4);
        check(v4 == 850, "non-number value -> default 850");
        check(!w4.isEmpty(), "non-number value recorded a warning");

        Path dir5 = tmp.resolve("negative");
        Files.createDirectories(dir5);
        Files.writeString(dir5.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + SpawnCityConfig.KEY + "\": -50}", StandardCharsets.UTF_8);
        List<String> w5 = new ArrayList<>();
        int v5 = SpawnCityConfig.load(dir5, w5);
        check(v5 == 0, "negative value -> 0 (feature off)");
        check(!w5.isEmpty(), "negative value recorded a warning");

        Path dir6 = tmp.resolve("tiny");
        Files.createDirectories(dir6);
        Files.writeString(dir6.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + SpawnCityConfig.KEY + "\": 5}", StandardCharsets.UTF_8);
        List<String> w6 = new ArrayList<>();
        int v6 = SpawnCityConfig.load(dir6, w6);
        check(v6 == SpawnCityConfig.MIN_EFFECTIVE, "5 -> clamped to 16 (chunk aligned centre)");
        check(!w6.isEmpty(), "clamped value recorded a warning");

        Path dir7 = tmp.resolve("normal");
        Files.createDirectories(dir7);
        Files.writeString(dir7.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + SpawnCityConfig.KEY + "\": 512}", StandardCharsets.UTF_8);
        List<String> w7 = new ArrayList<>();
        int v7 = SpawnCityConfig.load(dir7, w7);
        check(v7 == 512, "normal value 512 read back as-is");
        check(w7.isEmpty(), "no warnings for a valid positive config");

        // ---- 1b. COMPAT: the obsolete edge-distance key is ignored, not an error ----
        Path dir8 = tmp.resolve("oldkey");
        Files.createDirectories(dir8);
        Files.writeString(dir8.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + OLD_KEY + "\": 200}", StandardCharsets.UTF_8);
        List<String> w8 = new ArrayList<>();
        int v8 = SpawnCityConfig.load(dir8, w8);
        check(v8 == 850, "old key (edge distance 200) alone -> the NEW default 850 applies");
        check(SpawnCityConfig.distance() == 850, "distance() reflects 850 when only the old key is present");
        check(w8.isEmpty(), "an obsolete key alone records NO warning (old player files keep working)");

        Path dir9 = tmp.resolve("bothkeys");
        Files.createDirectories(dir9);
        Files.writeString(dir9.resolve(SpawnCityConfig.FILE_NAME),
                "{\"" + OLD_KEY + "\": 200, \"" + SpawnCityConfig.KEY + "\": 900}", StandardCharsets.UTF_8);
        List<String> w9 = new ArrayList<>();
        int v9 = SpawnCityConfig.load(dir9, w9);
        check(v9 == 900, "old key next to the new one -> the NEW key wins (old one is dead)");
        check(w9.isEmpty(), "old key next to the new one records no warning");

        // restore the runtime default for any tests running after us
        SpawnCityConfig.install(SpawnCityConfig.DEFAULT);

        // ---- 2. pure geometry, by reflection (impl package) ----
        Class<?> planCls = Class.forName("com.caf.lccustomcity.impl.SpawnCityPlan");
        Method cityRadius = planCls.getMethod("cityRadius", int.class, int.class);
        Method visibleRadius = planCls.getMethod("visibleRadius", float.class, int.class);
        Method centerDistance = planCls.getMethod("centerDistance", int.class, int.class, int.class, int.class);
        Method edgeDistance = planCls.getMethod("edgeDistance", int.class, int.class, int.class, int.class, int.class);
        Method satisfied = planCls.getMethod("satisfied", int.class, int.class, int.class, int[][].class);
        // 2026-10: planGeometry lost the visibleRadius parameter - the placement target is
        // now the CENTRE distance itself (seed, spawnX, spawnZ, radius, centreDistance)
        Method planGeometry = planCls.getMethod("planGeometry",
                long.class, int.class, int.class, int.class, int.class);
        Method factorAt = planCls.getMethod("factorAt", int.class, int.class, int.class, int.class, int.class);
        Method apply = planCls.getMethod("apply", float.class, float.class);

        // radius helpers: profile 800..800 -> 800 ; threshold 0.2 -> visible radius 640
        int radius = (Integer) cityRadius.invoke(null, 800, 800);
        check(radius == 800, "radius midpoint of 800..800 is 800");
        check((Integer) cityRadius.invoke(null, 1000, 1000) == 1000, "empty radius range still gives a radius");
        int visible = (Integer) visibleRadius.invoke(null, 0.2f, radius);
        check(visible == 640, "visible city radius at threshold 0.2 is 640 (0.8 * 800)");
        check((Integer) visibleRadius.invoke(null, 0.0f, 1000) == 1000, "threshold 0 -> whole nominal radius is visible");

        // centre-distance helper (this is what the criterion and the log use now)
        check((Integer) centerDistance.invoke(null, 0, 0, 850, 0) == 850, "centre distance of (850,0) is 850");
        check((Integer) centerDistance.invoke(null, 0, 0, 600, 600) == 849,
                "centre distance of (600,600) is 849 (848.5 rounded up)");

        // visible-edge helper: NO LONGER a criterion, only the reference number in the log
        check((Integer) edgeDistance.invoke(null, 0, 0, 1100, 0, 1000) == 100,
                "reference: centre 1100 with radius 1000 -> visible edge 100 blocks out");
        check((Integer) edgeDistance.invoke(null, 0, 0, 500, 0, 1000) == 0,
                "spawn inside the city -> visible edge distance 0");

        // ---- 2b. the radius chain: zone config file -> profile -> synthetic city ----
        Path zdir = tmp.resolve("zonechain");
        Files.createDirectories(zdir);
        // the player's instance file now says 50/50 chunks
        Files.writeString(zdir.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_RMIN + "\":50,\"" + K_RMAX + "\":50}}", StandardCharsets.UTF_8);
        List<String> zw = new ArrayList<>();
        ZoneConfig zc = ZoneConfig.load(zdir, zw);
        check(zc.radiusMin == 50 && zc.radiusMax == 50, "zone config radius parsed as 50/50 chunks");
        Map<String, Object> chain = LcCustomCityMod.citySizeOverrides(zc);
        check(chain.get("CITY_MINRADIUS").equals(800) && chain.get("CITY_MAXRADIUS").equals(800),
                "50/50 chunks -> 800/800 blocks for the Lost Cities profile");
        int chainRadius = (Integer) cityRadius.invoke(null,
                (Integer) chain.get("CITY_MINRADIUS"), (Integer) chain.get("CITY_MAXRADIUS"));
        check(chainRadius == 800, "profile radius 800 -> 800 for the synthetic city");
        check(chainRadius == radius, "the synthetic city uses exactly the configured radius");
        check((Integer) visibleRadius.invoke(null, 0.2f, chainRadius) == 640,
                "radius 800 at city threshold 0.2 -> visible city radius 640");

        // an explicit config value still wins over the built-in default (config priority)
        Files.writeString(zdir.resolve(ZoneConfig.FILE_NAME),
                "{\"" + K_CITY + "\":{\"" + K_RMIN + "\":60,\"" + K_RMAX + "\":60}}", StandardCharsets.UTF_8);
        Map<String, Object> chain2 = LcCustomCityMod.citySizeOverrides(ZoneConfig.load(zdir, new ArrayList<>()));
        check(chain2.get("CITY_MINRADIUS").equals(960) && chain2.get("CITY_MAXRADIUS").equals(960),
                "an explicit 60/60 chunks config still wins over the default -> 960/960 blocks");

        // ---- 3. satisfied: the criterion is the CENTRE distance (<= N) ----
        // The third value of each entry is still the radius, but the radius is NOT part
        // of the criterion any more: only the centre distance is compared.
        final int N = 850;                          // configured spawn-city centre distance
        int[][] none = new int[0][];
        int[][] centre850 = {{850, 0, 800}};        // centre exactly N away
        int[][] centre851 = {{851, 0, 800}};        // one block too far
        int[][] centre800 = {{800, 0, 800}};        // an existing city 800 blocks away
        int[][] farAway = {{5000, -5000, 800}};
        int[][] inside = {{300, -400, 800}};        // spawn (0,0) is inside this city

        check(!(Boolean) satisfied.invoke(null, 0, 0, N, none), "no city nearby -> needs a synthetic city");
        check((Boolean) satisfied.invoke(null, 0, 0, N, centre850),
                "centre exactly 850 away -> already satisfied (the <= is inclusive)");
        check(!(Boolean) satisfied.invoke(null, 0, 0, N, centre851), "centre 851 away -> needs a synthetic city");
        check((Boolean) satisfied.invoke(null, 0, 0, N, centre800),
                "an existing city with its centre 800 blocks away -> already satisfied");
        check((Boolean) satisfied.invoke(null, 0, 0, N, inside), "spawn inside a city -> already satisfied");
        check(!(Boolean) satisfied.invoke(null, 0, 0, N, farAway), "centre ~7071 away -> needs a synthetic city");
        // the radius must play NO part in the decision any more
        check((Boolean) satisfied.invoke(null, 0, 0, N, new int[][]{{850, 0, 1}}),
                "radius 1 with the centre 850 away -> satisfied (radius is not part of the criterion)");
        check((Boolean) satisfied.invoke(null, 0, 0, N, new int[][]{{850, 0, 9000}}),
                "radius 9000 with the centre 850 away -> satisfied too (radius really is ignored)");
        // the two cases must be distinguishable, not both-true / both-false
        check(((Boolean) satisfied.invoke(null, 0, 0, N, centre850))
                != ((Boolean) satisfied.invoke(null, 0, 0, N, centre851)),
                "satisfied and not-satisfied cases really differ");

        // ---- 3b. THE CRITERION SWITCH (2026-10): centre distance, not visible edge ----
        // Case A: centre 846 away, nominal radius 100 (visible radius 80)
        //   OLD edge rule  : 846 - 80 = 766 > 200 -> "needs a city"
        //   NEW centre rule: 846 <= 850            -> "already satisfied"
        // Case B: centre 1000 away, nominal radius 1000 (visible radius 800)
        //   OLD edge rule  : 1000 - 800 = 200 <= 200 -> "already satisfied"
        //   NEW centre rule: 1000 > 850              -> "needs a city"
        // Both flip, in opposite directions: either decision going back to the old
        // answer would fail here.
        int[][] tinyRadiusNearCentre = {{846, 0, 100}};
        int[][] bigRadiusFarCentre = {{1000, 0, 1000}};

        check((Integer) edgeDistance.invoke(null, 0, 0, 846, 0, 80) == 766,
                "reference: centre 846 with visible radius 80 -> visible edge is 766 blocks away");
        check((Integer) edgeDistance.invoke(null, 0, 0, 1000, 0, 800) == 200,
                "reference: centre 1000 with visible radius 800 -> visible edge is 200 blocks away");

        boolean oldRuleA = ((Integer) edgeDistance.invoke(null, 0, 0, 846, 0, 80)) <= 200;
        boolean oldRuleB = ((Integer) edgeDistance.invoke(null, 0, 0, 1000, 0, 800)) <= 200;
        boolean newRuleA = (Boolean) satisfied.invoke(null, 0, 0, N, tinyRadiusNearCentre);
        boolean newRuleB = (Boolean) satisfied.invoke(null, 0, 0, N, bigRadiusFarCentre);

        check(!oldRuleA && newRuleA,
                "case A flips: old edge rule said 'needs a city', new centre rule says 'satisfied'");
        check(oldRuleB && !newRuleB,
                "case B flips: old edge rule said 'satisfied', new centre rule says 'needs a city'");

        // ---- 3c. boundary of the new criterion: centre distance == N is satisfied ----
        int[][] diagonalInside = {{600, 600, 800}};     // 848.5 blocks away
        int[][] diagonalOutside = {{602, 602, 800}};    // 851.4 blocks away

        check((Boolean) satisfied.invoke(null, 0, 0, N, diagonalInside),
                "boundary: a diagonal centre 848.5 away -> already satisfied");
        check(!(Boolean) satisfied.invoke(null, 0, 0, N, diagonalOutside),
                "boundary: a diagonal centre 851.4 away -> needs a synthetic city");
        check((Integer) centerDistance.invoke(null, 0, 0, 602, 602) == 852,
                "boundary: 851.4 is reported as 852 by the centre-distance helper");

        // ---- 4. off (0) -> never place anything ----
        check((Boolean) satisfied.invoke(null, 0, 0, 0, none), "distance 0 -> satisfied() says: do nothing");
        check((Boolean) satisfied.invoke(null, 0, 0, 0, centre851),
                "distance 0 -> do nothing even with the nearest centre 851 away");

        // ---- 5. merge rule: never touch an existing (non zero) city factor ----
        check((Float) apply.invoke(null, 0.42f, 0.9f) == 0.42f, "native factor 0.42 is returned unchanged");
        check((Float) apply.invoke(null, 0.01f, 0.9f) == 0.01f, "small native factor is returned unchanged");
        check((Float) apply.invoke(null, 1.0f, 0.9f) == 1.0f, "native factor 1.0 is returned unchanged");
        check((Float) apply.invoke(null, 0.0f, 0.7f) == 0.7f, "native 0 -> synthetic factor is used");
        check((Float) apply.invoke(null, 0.0f, 0.0f) == 0.0f, "native 0 and no synthetic city -> still 0 (vanilla)");

        // ---- 6. placement geometry: the city CENTRE really lands within N ----
        int spawnX = 137;
        int spawnZ = -421;
        int target = N;                 // 850 blocks to the city CENTRE
        for (int seed = 0; seed < 4; seed++) {
            int[] p1 = (int[]) planGeometry.invoke(null, (long) seed, spawnX, spawnZ, radius, target);
            int[] p2 = (int[]) planGeometry.invoke(null, (long) seed, spawnX, spawnZ, radius, target);
            check(Arrays.equals(p1, p2), "seed " + seed + ": plan is deterministic across calls");
            check(p1[0] % 16 == 0 && p1[1] % 16 == 0, "seed " + seed + ": city centre is chunk aligned");
            check(p1[2] == radius, "seed " + seed + ": plan keeps the nominal radius");
            check(p1[2] == 800, "seed " + seed + ": the placed city has the configured radius 800");

            double dx = (double) p1[0] - spawnX;
            double dz = (double) p1[1] - spawnZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double perpendicular = Math.min(Math.abs(dx), Math.abs(dz));

            check(perpendicular <= 15.0,
                    "seed " + seed + ": perpendicular offset is at most one chunk (is " + perpendicular + ")");
            check(dist <= (double) target,
                    "seed " + seed + ": centre within " + target + " blocks (is " + dist + ")");
            // alignment may only pull the city CLOSER: at most 15 axial + 15 perpendicular,
            // so the centre can never end up further than N nor much nearer than N
            check(dist >= (double) target - 16.0,
                    "seed " + seed + ": centre is as close to " + target + " as alignment allows (is " + dist + ")");
            check(dist > (double) visible,
                    "seed " + seed + ": the spawn is outside the visible city area (is " + dist + ")");

            // the visible edge is only a REFERENCE number now: distance - visible radius
            double visibleEdge = dist - (double) visible;
            check(visibleEdge > 190.0 && visibleEdge <= (double) target - (double) visible + 1e-9,
                    "seed " + seed + ": visible edge lands about 210 blocks out (is " + visibleEdge + ")");

            // the two criteria must agree: the city we just placed is "already satisfied"
            // under the same centre-distance rule SpawnCityRule uses to decide (if this
            // failed, the rule would want to place a city that is already there)
            int[][] placedCity = {{p1[0], p1[1], p1[2]}};
            check((Boolean) satisfied.invoke(null, spawnX, spawnZ, target, placedCity),
                    "seed " + seed + ": the placed city passes the centre-distance rule (centre "
                            + dist + " <= " + target + ")");

            // the spawn chunk must stay a wilderness chunk: factor <= CITY_THRESHOLD
            float atSpawn = (Float) factorAt.invoke(null, p1[0], p1[1], p1[2], spawnX >> 4, spawnZ >> 4);
            check(atSpawn <= 0.2f, "seed " + seed + ": spawn chunk factor " + atSpawn + " is not a city chunk");

            // walk from the spawn towards the city along the dominant axis: the first city
            // chunk must be about (centre distance - visible radius) blocks away, i.e. the
            // visible edge, not 850 and not 0
            boolean xAxis = Math.abs(p1[0] - spawnX) >= Math.abs(p1[1] - spawnZ);
            int stepX = xAxis ? Integer.signum(p1[0] - spawnX) : 0;
            int stepZ = xAxis ? 0 : Integer.signum(p1[1] - spawnZ);
            int reached = -1;
            for (int d = 0; d <= target + 32; d += 16) {
                int tx = (spawnX + stepX * d) >> 4;
                int tz = (spawnZ + stepZ * d) >> 4;
                float f = (Float) factorAt.invoke(null, p1[0], p1[1], p1[2], tx, tz);
                if (f > 0.2f) {
                    reached = d;
                    break;
                }
            }
            check(reached >= 0, "seed " + seed + ": walking towards the city reaches a city chunk");
            check(Math.abs((double) reached - visibleEdge) <= 48.0,
                    "seed " + seed + ": the first city chunk is about " + Math.round(visibleEdge)
                            + " blocks out (16-block stepping reached " + reached + ")");

            // far outside the city: no factor at all
            float outside = (Float) factorAt.invoke(null, p1[0], p1[1], p1[2], (p1[0] >> 4) + 200, p1[1] >> 4);
            check(outside == 0.0f, "seed " + seed + ": chunks outside the radius get factor 0");

            // determinism of the factor itself for one fixed target chunk
            float f1 = (Float) factorAt.invoke(null, p1[0], p1[1], p1[2], 10, 10);
            float f2 = (Float) factorAt.invoke(null, p1[0], p1[1], p1[2], 10, 10);
            check(f1 == f2, "seed " + seed + ": factor for a fixed target chunk is deterministic");
        }

        // same seed + same spawn, different distance -> different plan (sanity: the knob works)
        int[] small = (int[]) planGeometry.invoke(null, 7L, spawnX, spawnZ, radius, 16);
        int[] big = (int[]) planGeometry.invoke(null, 7L, spawnX, spawnZ, radius, target);
        check(!Arrays.equals(small, big), "a smaller centre distance moves the city closer");
        double smallDist = Math.sqrt(Math.pow((double) small[0] - spawnX, 2.0)
                + Math.pow((double) small[1] - spawnZ, 2.0));
        double bigDist = Math.sqrt(Math.pow((double) big[0] - spawnX, 2.0)
                + Math.pow((double) big[1] - spawnZ, 2.0));
        check(smallDist < bigDist, "the smaller target really puts the centre nearer ("
                + smallDist + " < " + bigDist + ")");
        // smallest allowed target (16): the "wrong side" fallback may take over, but the
        // centre still stays within about two chunks
        check(smallDist <= 22.0, "the smallest target (16) still keeps the centre close (is " + smallDist + ")");
        check(bigDist <= (double) target, "the 850-block target is still honoured (is " + bigDist + ")");

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        System.out.println("\u901a\u8fc7 " + passed + " \u9879, \u5931\u8d25 " + failed + " \u9879");
        if (failed == 0) {
            System.out.println("SPAWN-CITY-TEST-ALL-PASS");
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
