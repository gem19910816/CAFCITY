import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline smoke test for the pure half of the in-game "/cafcity" command
 * (nearest city search), i.e. com.caf.lccustomcity.impl.CityLocator.
 *
 * The impl package is compiled in the MIXIN group, which runs after the group-A
 * tests, so CityLocator is reached by REFLECTION here (same trick as
 * SpawnCitySmokeTest). CityLocator itself references no game classes, so it loads
 * and runs fine offline.
 *
 * Covered:
 *   1. distance-limit parameter parsing: missing / blank / non-numeric / non-integer
 *      / 0 / negative / huge / overflowing text all fall back to the default 5000,
 *      values above the hard cap are clamped, valid values pass through unchanged.
 *   2. limitNotice(): empty for a clean parameter, one friendly Chinese sentence
 *      otherwise (it never throws).
 *   3. memoisation cell maths: cellIndex / cellCenter are floor based and every
 *      block coordinate lies within half a cell of its cell centre.
 *   4. scanWindowChunks(): the square search window really covers every city centre
 *      that is within N blocks of ANY block inside the cell (that is the whole
 *      point of the window), for N = 1 / 100 / 800 / 5000 / 20000.
 *   5. distance(): integer, overflow safe, rounded.
 *   6. nearest(): picks the closest centre, skips malformed entries, ties resolve
 *      to the first entry (scan order is fixed, so the answer is deterministic),
 *      distance 0 when standing on the centre.
 *   7. nearestWithin(): the distance limit itself -- exactly at the limit counts,
 *      one block further does not, and it still returns the NEAREST one inside.
 *   8. visibleEdgeDistance(): distance - radius * (1 - CITY_THRESHOLD), negative
 *      inside the city, exactly 0 on the visible edge, and it uses the same
 *      rounding as SpawnCityPlan.visibleRadius (one criterion, not two).
 *   9. directionText(): all 8 compass directions of the Minecraft axis
 *      convention (-Z = north), scale invariant, "same spot" for 0/0.
 *  10. classifyZone()/zoneText(): inner / outer / unknown, and never claims a zone
 *      while the player is outside the city.
 *  11. foundLines(): the assembled Chinese output really contains the city centre
 *      coordinates, the distance, the radius, the visible edge, the zone and the
 *      search range (guards against dropped parameters when concatenating).
 *  12. notFoundLines(): says "not found within N" plus the nearest centre of the
 *      window, and stops suggesting a bigger range once the cap is reached.
 *  13. every line starts with the "[CAF-LC] " prefix.
 *
 * NOT covered here (needs the real game, verify in game):
 *   com.caf.lccustomcity.impl.CityCommandService (reflective dimension lookup,
 *   the City.isCityCenter scan, the inner/outer city style call) and the mixin
 *   that registers the command.
 *
 * This file is intentionally ASCII-only; every Chinese fragment it prints or
 * compares against is written with unicode escapes inside a string literal
 * (never in a comment).
 */
public final class CityCommandSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- Chinese fragments asserted below (escapes only, see the class comment) ----
    private static final String INNER = "\u5185\u57ce";
    private static final String OUTER = "\u5916\u57ce";
    private static final String UNKNOWN = "\u672a\u77e5";
    private static final String OUTSIDE_TOWN = "\u4f60\u5728\u57ce\u5916";
    private static final String NOT_FOUND = "\u6ca1\u6709\u627e\u5230\u57ce\u5e02\u57ce\u5fc3";
    private static final String CITY_CENTER = "\u57ce\u5fc3";
    private static final String SAME_SPOT = "\u539f\u5730";
    private static final String NORTH = "\u6b63\u5317";
    private static final String NORTH_EAST = "\u4e1c\u5317";
    private static final String EAST = "\u6b63\u4e1c";
    private static final String SOUTH_EAST = "\u4e1c\u5357";
    private static final String SOUTH = "\u6b63\u5357";
    private static final String SOUTH_WEST = "\u897f\u5357";
    private static final String WEST = "\u6b63\u897f";
    private static final String NORTH_WEST = "\u897f\u5317";
    private static final String LIMIT_REACHED = "\u641c\u7d22\u8303\u56f4\u5df2\u7ecf\u5230\u4e0a\u9650";
    private static final String TRY_AGAIN = "\u53ef\u4ee5\u8bd5 /cafcity";
    private static final String IN_TOWN = "\u4f60\u5df2\u7ecf\u5728\u57ce\u91cc";
    private static final String VISIBLE_EDGE = "\u770b\u5f97\u89c1\u7684\u57ce\u533a\u8fb9\u7f18";
    private static final String RIGHT_UNDER = "\u6b63\u597d\u5728\u4f60\u811a\u4e0b";
    private static final String NOT_IN_YET = "\u4f60\u8fd8\u6ca1\u8fdb\u57ce";
    private static final String ZONE_LABEL = "\u5206\u533a";
    private static final String ON_THE_CENTER = "\u4f60\u6b63\u7ad9\u5728\u57ce\u5fc3\u4e0a";
    private static final String CITY_COUNTER = "\u5ea7\u57ce\u5fc3";

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("cafcity-test");

        System.out.println("=== /cafcity (nearest city) smoke test ===");

        Class<?> loc = Class.forName("com.caf.lccustomcity.impl.CityLocator");
        Method parseLimit = loc.getMethod("parseLimit", String.class);
        Method sanitizeLimit = loc.getMethod("sanitizeLimit", int.class);
        Method limitNotice = loc.getMethod("limitNotice", String.class, int.class);
        Method cellIndex = loc.getMethod("cellIndex", int.class);
        Method cellCenter = loc.getMethod("cellCenter", int.class);
        Method scanWindowChunks = loc.getMethod("scanWindowChunks", int.class);
        Method distance = loc.getMethod("distance", int.class, int.class, int.class, int.class);
        Method nearest = loc.getMethod("nearest", int.class, int.class, int[][].class);
        Method nearestWithin = loc.getMethod("nearestWithin", int.class, int.class, int[][].class, int.class);
        Method visibleEdgeDistance = loc.getMethod("visibleEdgeDistance", int.class, int.class, float.class);
        Method directionText = loc.getMethod("directionText", int.class, int.class);
        Method classifyZone = loc.getMethod("classifyZone", Boolean.class);
        Method zoneText = loc.getMethod("zoneText", int.class, int.class);
        Method foundLines = loc.getMethod("foundLines", int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, float.class, int.class, int.class, int.class);
        Method notFoundLines = loc.getMethod("notFoundLines", int.class, int.class);

        int defaultLimit = loc.getField("DEFAULT_LIMIT").getInt(null);
        int maxLimit = loc.getField("MAX_LIMIT").getInt(null);
        int cell = loc.getField("CELL").getInt(null);
        int halfDiagonal = loc.getField("CELL_HALF_DIAGONAL").getInt(null);
        int zoneUnknown = loc.getField("ZONE_UNKNOWN").getInt(null);
        int zoneInner = loc.getField("ZONE_INNER").getInt(null);
        int zoneOuter = loc.getField("ZONE_OUTER").getInt(null);
        String prefix = (String) loc.getField("PREFIX").get(null);

        // ---- 1. constants ----
        checkEq(defaultLimit, 5000, "DEFAULT_LIMIT is 5000 blocks");
        checkEq(maxLimit, 20000, "MAX_LIMIT is 20000 blocks");
        checkEq(cell, 512, "memoisation cell is 512 blocks wide");
        checkEq(halfDiagonal, 363, "CELL_HALF_DIAGONAL covers 512*sqrt(2)/2 = 362.04");
        check(zoneUnknown == 0 && zoneInner == 1 && zoneOuter == 2, "zone ids are 0/1/2 (unknown/inner/outer)");
        checkEqStr(prefix, "[CAF-LC] ", "every output line starts with the CAF-LC prefix");

        // ---- 2. distance limit parsing + fallback ----
        checkEq(limit(parseLimit, "5000"), 5000, "limit \"5000\" -> 5000");
        checkEq(limit(parseLimit, "800"), 800, "limit \"800\" -> 800");
        checkEq(limit(parseLimit, "  800  "), 800, "limit with spaces is trimmed");
        checkEq(limit(parseLimit, "1"), 1, "limit \"1\" -> 1");
        checkEq(limit(parseLimit, "20000"), 20000, "limit exactly at the cap passes through");
        checkEq(limit(parseLimit, "20001"), 20000, "limit above the cap is clamped to the cap");
        checkEq(limit(parseLimit, "999999"), 20000, "huge limit is clamped to the cap");
        checkEq(limit(parseLimit, "99999999999999999999"), 5000, "overflowing number -> default");
        checkEq(limit(parseLimit, "0"), 5000, "limit 0 -> default");
        checkEq(limit(parseLimit, "-1"), 5000, "negative limit -> default");
        checkEq(limit(parseLimit, "-99999"), 5000, "very negative limit -> default");
        checkEq(limit(parseLimit, "abc"), 5000, "non-numeric limit -> default (no exception)");
        checkEq(limit(parseLimit, "80.5"), 5000, "non-integer limit -> default (no exception)");
        checkEq(limit(parseLimit, "1e3"), 5000, "scientific notation -> default (no exception)");
        checkEq(limit(parseLimit, ""), 5000, "empty limit -> default");
        checkEq(limit(parseLimit, "   "), 5000, "blank limit -> default");
        checkEq(limit(parseLimit, null), 5000, "missing limit -> default");

        checkEq(sanitizeLimit(sanitizeLimit, 0), 5000, "sanitize(0) -> default");
        checkEq(sanitizeLimit(sanitizeLimit, -7), 5000, "sanitize(-7) -> default");
        checkEq(sanitizeLimit(sanitizeLimit, 1), 1, "sanitize(1) -> 1");
        checkEq(sanitizeLimit(sanitizeLimit, 20000), 20000, "sanitize(20000) -> 20000");
        checkEq(sanitizeLimit(sanitizeLimit, 20001), 20000, "sanitize(20001) -> cap");
        checkEq(sanitizeLimit(sanitizeLimit, Integer.MAX_VALUE), 20000, "sanitize(MAX_VALUE) -> cap");
        checkEq(sanitizeLimit(sanitizeLimit, Integer.MIN_VALUE), 5000, "sanitize(MIN_VALUE) -> default");

        // ---- 3. the "your parameter was ignored" notice ----
        checkEqStr(notice(limitNotice, null, 5000), "", "no parameter -> no notice");
        checkEqStr(notice(limitNotice, "5000", 5000), "", "clean parameter -> no notice");
        checkEqStr(notice(limitNotice, "0800", 800), "", "leading zeros are still a clean parameter");
        checkEqStr(notice(limitNotice, " 800 ", 800), "", "padded clean parameter -> no notice");
        check(notice(limitNotice, "abc", 5000).contains("5000"), "non-numeric parameter notice names the used limit");
        check(!notice(limitNotice, "abc", 5000).isEmpty(), "non-numeric parameter produces a notice");
        check(notice(limitNotice, "0", 5000).contains("5000"), "limit 0 produces a notice with the used limit");
        check(notice(limitNotice, "-5", 5000).contains("5000"), "negative limit produces a notice");
        check(notice(limitNotice, "999999", 20000).contains("20000"), "clamped limit notice names the cap");

        // ---- 4. memoisation cells ----
        // cells are floor based: cell 0 covers blocks 0..511, cell -1 covers -512..-1
        checkEq(cellIndexOf(cellIndex, 0), 0, "cell of block 0 is 0");
        checkEq(cellIndexOf(cellIndex, 255), 0, "cell of block 255 is 0");
        checkEq(cellIndexOf(cellIndex, 256), 0, "cell of block 256 is still 0 (cells are 512 wide)");
        checkEq(cellIndexOf(cellIndex, 511), 0, "cell of block 511 is still 0");
        checkEq(cellIndexOf(cellIndex, 512), 1, "cell of block 512 is 1");
        checkEq(cellIndexOf(cellIndex, 1023), 1, "cell of block 1023 is still 1");
        checkEq(cellIndexOf(cellIndex, -1), -1, "cell of block -1 is -1 (floor, not truncation)");
        checkEq(cellIndexOf(cellIndex, -512), -1, "cell of block -512 is -1");
        checkEq(cellIndexOf(cellIndex, -513), -2, "cell of block -513 is -2");
        checkEq(centerOf(cellCenter, 0), 256, "cell 0 is centred on block 256");
        checkEq(centerOf(cellCenter, 1), 768, "cell 1 is centred on block 768");
        checkEq(centerOf(cellCenter, -1), -256, "cell -1 is centred on block -256");
        checkEq(centerOf(cellCenter, -2), -768, "cell -2 is centred on block -768");
        long[] probes = {-1000000L, -513L, -512L, -1L, 0L, 255L, 256L, 511L, 512L, 999999L};
        boolean insideCell = true;
        for (long v : probes) {
            int ci = cellIndexOf(cellIndex, (int) v);
            int cc = centerOf(cellCenter, ci);
            if (Math.abs((long) cc - v) > cell / 2L) {
                insideCell = false;
            }
        }
        check(insideCell, "every probe coordinate is within half a cell of its cell centre");
        boolean centresAligned = true;
        for (int ci = -4; ci <= 4; ci++) {
            if (Math.floorMod(centerOf(cellCenter, ci), cell) != cell / 2) {
                centresAligned = false;
            }
        }
        check(centresAligned, "cell centres are aligned to half a cell for negative and positive cells");

        // ---- 5. the search window covers everything the limit promises ----
        checkEq(windowOf(scanWindowChunks, 5000), 338, "window for the default 5000 is 338 chunks");
        checkEq(windowOf(scanWindowChunks, 1), 25, "window for limit 1 is 25 chunks");
        checkEq(windowOf(scanWindowChunks, 20000), 1275, "window for the cap 20000 is 1275 chunks");
        checkEq(windowOf(scanWindowChunks, 0), 338, "illegal limit 0 falls back to the default window");
        checkEq(windowOf(scanWindowChunks, -100), 338, "negative limit falls back to the default window");
        int[] windowLimits = {1, 100, 800, 5000, 20000};
        for (int n : windowLimits) {
            int w = windowOf(scanWindowChunks, n);
            // worst case: player at a cell corner (256 blocks from the centre) and a city
            // centre exactly n blocks away along one axis -> per-axis chunk offset
            double worst = (256.0 + n) / 16.0;
            check((double) w >= worst, "window for limit " + n + " covers the worst case (" + worst + " chunks)");
        }

        // ---- 6. distance ----
        checkEq(dist(distance, 0, 0, 0, 0), 0, "distance to itself is 0");
        checkEq(dist(distance, 0, 0, 3, 4), 5, "3/4/5 triangle is 5");
        checkEq(dist(distance, 0, 0, 1000, 0), 1000, "1000 blocks along X is 1000");
        checkEq(dist(distance, 0, 0, 0, -1000), 1000, "1000 blocks along -Z is 1000");
        checkEq(dist(distance, -100, -100, 100, 100), 283, "diagonal is rounded (282.84 -> 283)");
        checkEq(dist(distance, 30000000, 0, -30000000, 0), 60000000, "60M block span does not overflow");

        // ---- 7. nearest centre ----
        check(nearest.invoke(null, 0, 0, null) == null, "nearest of null list is null");
        check(nearest.invoke(null, 0, 0, new int[0][]) == null, "nearest of empty list is null");
        check(nearest.invoke(null, 0, 0, new int[][]{null, {0, 0}}) == null,
                "malformed centre entries (null / too short) are skipped");
        int[] extra = (int[]) nearest.invoke(null, 0, 0, new int[][]{{1, 2, 3, 4}});
        check(extra != null && extra[0] == 1 && extra[1] == 2 && extra[2] == 3 && extra[3] == 2,
                "an entry with extra values still works (first three are the centre)");
        int[] one = (int[]) nearest.invoke(null, 0, 0, new int[][]{{1000, 0, 800}});
        check(one != null && one[0] == 1000 && one[1] == 0 && one[2] == 800 && one[3] == 1000,
                "single centre is returned with its radius and distance");
        int[] three = (int[]) nearest.invoke(null, 0, 0,
                new int[][]{{5000, 0, 1000}, {0, 3000, 1000}, {2000, 0, 1000}});
        check(three != null && three[0] == 2000 && three[3] == 2000, "closest centre wins (2000, not 3000/5000)");
        int[] far = (int[]) nearest.invoke(null, 0, 0, new int[][]{{-4000, -3000, 1000}});
        check(far != null && far[3] == 5000, "negative coordinates give the right distance (3/4/5)");
        int[] tie = (int[]) nearest.invoke(null, 0, 0, new int[][]{{1000, 0, 800}, {0, 1000, 800}});
        check(tie != null && tie[0] == 1000 && tie[1] == 0,
                "equal distances resolve to the first entry (fixed scan order -> deterministic)");
        int[] atCenter = (int[]) nearest.invoke(null, 1234, -5678, new int[][]{{1234, -5678, 1000}});
        check(atCenter != null && atCenter[3] == 0, "standing on the city centre gives distance 0");

        // ---- 8. distance limit (nearestWithin) ----
        int[][] near3000 = {{3000, 0, 1000}, {1000, 0, 800}};
        int[] withinExact = (int[]) nearestWithin.invoke(null, 0, 0, near3000, 1000);
        check(withinExact != null && withinExact[0] == 1000, "a centre exactly at the limit counts as found");
        check(nearestWithin.invoke(null, 0, 0, near3000, 999) == null, "a centre one block further is not found");
        int[] withinBig = (int[]) nearestWithin.invoke(null, 0, 0, near3000, 3000);
        check(withinBig != null && withinBig[0] == 1000,
                "inside the limit it still returns the NEAREST centre, not the first found");
        check(nearestWithin.invoke(null, 0, 0, new int[][]{{3000, 0, 1000}}, 2000) == null,
                "no centre inside the limit -> null");
        check(nearestWithin.invoke(null, 0, 0, new int[0][], 5000) == null, "empty list -> null");
        int[] fallback = (int[]) nearestWithin.invoke(null, 0, 0, new int[][]{{1000, 0, 800}}, 0);
        check(fallback != null, "illegal limit 0 falls back to the default instead of finding nothing");

        // ---- 9. visible city edge ----
        checkEq(edge(visibleEdgeDistance, 1234, 1000, 0.2f), 434, "1234 away, radius 1000, threshold 0.2 -> 434");
        checkEq(edge(visibleEdgeDistance, 1000, 1000, 0.2f), 200, "1000 away -> 200 to the visible edge");
        checkEq(edge(visibleEdgeDistance, 800, 1000, 0.2f), 0, "800 away -> exactly on the visible edge");
        checkEq(edge(visibleEdgeDistance, 799, 1000, 0.2f), -1, "799 away -> already inside by one block");
        checkEq(edge(visibleEdgeDistance, 300, 1000, 0.2f), -500, "300 away -> inside, 500 blocks deep");
        checkEq(edge(visibleEdgeDistance, 0, 1000, 0.2f), -800, "on the centre -> inside by the full 800");
        checkEq(edge(visibleEdgeDistance, 1234, 1000, 0.0f), 234, "threshold 0 -> the whole radius is visible");
        checkEq(edge(visibleEdgeDistance, 1234, 1000, 0.5f), 734, "threshold 0.5 -> only half the radius is visible");

        Class<?> plan = Class.forName("com.caf.lccustomcity.impl.SpawnCityPlan");
        Method visibleRadius = plan.getMethod("visibleRadius", float.class, int.class);
        int visible = (Integer) visibleRadius.invoke(null, 0.2f, 1000);
        checkEq(visible, 800, "SpawnCityPlan visible radius at threshold 0.2 is 800 (single criterion)");
        boolean sameRounding = true;
        for (int d : new int[]{0, 799, 800, 1234, 5000}) {
            if (edge(visibleEdgeDistance, d, 1000, 0.2f) != d - visible) {
                sameRounding = false;
            }
        }
        check(sameRounding, "the visible edge uses the very same radius as the spawn-city feature");

        // ---- 10. compass directions ----
        checkEqStr(dir(directionText, 0, 0), SAME_SPOT, "dx=dz=0 -> same spot");
        checkEqStr(dir(directionText, 0, -1), NORTH, "pure -Z is north");
        checkEqStr(dir(directionText, 0, 1), SOUTH, "pure +Z is south");
        checkEqStr(dir(directionText, 1, 0), EAST, "pure +X is east");
        checkEqStr(dir(directionText, -1, 0), WEST, "pure -X is west");
        checkEqStr(dir(directionText, 1, -1), NORTH_EAST, "+X/-Z is north-east");
        checkEqStr(dir(directionText, 1, 1), SOUTH_EAST, "+X/+Z is south-east");
        checkEqStr(dir(directionText, -1, 1), SOUTH_WEST, "-X/+Z is south-west");
        checkEqStr(dir(directionText, -1, -1), NORTH_WEST, "-X/-Z is north-west");
        checkEqStr(dir(directionText, 1000, -1), EAST, "a nearly axis aligned vector is a cardinal direction");
        checkEqStr(dir(directionText, 1, -1000), NORTH, "same for the Z axis");
        checkEqStr(dir(directionText, 1000, 1000), SOUTH_EAST, "diagonal vectors pick the diagonal sector");
        checkEqStr(dir(directionText, 1000, -1000), NORTH_EAST, "diagonal vectors pick the diagonal sector (2)");
        checkEqStr(dir(directionText, -1000, 1000), SOUTH_WEST, "diagonal vectors pick the diagonal sector (3)");
        checkEqStr(dir(directionText, -1000, -1000), NORTH_WEST, "diagonal vectors pick the diagonal sector (4)");
        checkEqStr(dir(directionText, 1234, -5678), dir(directionText, 2468, -11356),
                "the direction is scale invariant (same bearing, different distance)");

        // ---- 11. zone classification ----
        checkEq(zone(classifyZone, Boolean.TRUE), zoneInner, "inner city flag -> inner zone");
        checkEq(zone(classifyZone, Boolean.FALSE), zoneOuter, "outer city flag -> outer zone");
        checkEq(zone(classifyZone, null), zoneUnknown, "no flag -> unknown zone");
        checkEqStr(ztext(zoneText, -100, zoneInner), INNER, "inside the city -> inner zone text");
        checkEqStr(ztext(zoneText, -100, zoneOuter), OUTER, "inside the city -> outer zone text");
        check(ztext(zoneText, -100, zoneUnknown).contains(UNKNOWN), "inside the city but no style -> unknown");
        check(ztext(zoneText, 0, zoneInner).contains(UNKNOWN)
                && ztext(zoneText, 0, zoneInner).contains(OUTSIDE_TOWN), "on the edge -> the zone does not apply");
        check(ztext(zoneText, 500, zoneInner).contains(OUTSIDE_TOWN), "outside the city -> the zone does not apply");
        check(!ztext(zoneText, 500, zoneInner).contains(INNER)
                && !ztext(zoneText, 500, zoneOuter).contains(OUTER),
                "outside the city it never claims inner or outer (no misleading answer)");

        // ---- 12. the found-output string ----
        List<String> samples = new ArrayList<>();
        String[] outside = (String[]) foundLines.invoke(null, 0, 0, 600, 600, 1000,
                992, 1008, 0.2f, 5000, 3, zoneInner);
        checkEq(outside.length, 3, "found output is three lines");
        String joinedOutside = String.join("\n", outside);
        samples.add("-- outside the city --");
        for (String s : outside) {
            samples.add(s);
        }
        checkContains(joinedOutside, "(600, 600)", "found output contains the city centre coordinates");
        checkContains(joinedOutside, CITY_CENTER, "found output labels the city centre");
        checkContains(joinedOutside, " 849 ", "found output contains the distance to the centre (849)");
        // spaces around the number so "849" cannot satisfy the edge check by accident
        checkContains(outside[1], " 49 ", "found output contains the visible edge distance (49)");
        checkContains(joinedOutside, VISIBLE_EDGE, "found output explains the visible city edge");
        checkContains(joinedOutside, NOT_IN_YET, "outside the city it says you are not in yet");
        checkContains(joinedOutside, "992", "found output contains the configured minimum radius");
        checkContains(joinedOutside, "1008", "found output contains the configured maximum radius");
        checkContains(joinedOutside, "5000", "found output contains the search range");
        checkContains(joinedOutside, ZONE_LABEL, "found output contains the zone line");
        checkContains(joinedOutside, OUTSIDE_TOWN, "outside the city the zone is reported as not applicable");
        checkContains(joinedOutside, SOUTH_EAST, "the compass direction of (600,600) is south-east");
        checkContains(joinedOutside, CITY_COUNTER, "found output counts the city centres of the window");

        String[] inside = (String[]) foundLines.invoke(null, 0, 0, 300, 0, 1000,
                992, 1008, 0.2f, 5000, 1, zoneOuter);
        String joinedInside = String.join("\n", inside);
        samples.add("-- inside the city --");
        for (String s : inside) {
            samples.add(s);
        }
        checkContains(joinedInside, "(300, 0)", "inside output contains the city centre coordinates");
        checkContains(joinedInside, " 300 ", "inside output contains the distance to the centre");
        checkContains(joinedInside, IN_TOWN, "inside the city it says so");
        checkContains(joinedInside, " 500 ", "inside output contains how deep you are (500)");
        checkContains(joinedInside, OUTER, "inside the city the zone is reported (outer)");
        checkContains(joinedInside, EAST, "the compass direction of (300,0) is east");
        check(!joinedInside.contains(UNKNOWN), "inside the city the zone is never unknown");

        String[] onEdge = (String[]) foundLines.invoke(null, 0, 0, 800, 0, 1000,
                992, 1008, 0.2f, 5000, 2, zoneInner);
        String joinedOnEdge = String.join("\n", onEdge);
        samples.add("-- exactly on the visible edge --");
        for (String s : onEdge) {
            samples.add(s);
        }
        checkContains(joinedOnEdge, " 800 ", "edge output contains the distance to the centre");
        checkContains(joinedOnEdge, RIGHT_UNDER, "exactly on the edge it says the edge is right there");
        checkContains(joinedOnEdge, UNKNOWN, "exactly on the edge still counts as outside (unknown zone)");
        check(!joinedOnEdge.contains(IN_TOWN), "exactly on the edge does not claim you are inside");

        String[] onCenter = (String[]) foundLines.invoke(null, 0, 0, 0, 0, 1000,
                992, 1008, 0.2f, 5000, 7, zoneInner);
        String joinedOnCenter = String.join("\n", onCenter);
        samples.add("-- standing on the city centre --");
        for (String s : onCenter) {
            samples.add(s);
        }
        checkContains(joinedOnCenter, "(0, 0)", "on-centre output still contains the coordinates");
        checkContains(joinedOnCenter, ON_THE_CENTER, "on-centre output says you are standing on the centre");
        checkContains(joinedOnCenter, IN_TOWN, "on the centre you are inside the city");

        String[] weird = (String[]) foundLines.invoke(null, 0, 0, -12345, 6789, 1000,
                992, 1008, 0.2f, 5000, 1, zoneUnknown);
        String joinedWeird = String.join("\n", weird);
        checkContains(joinedWeird, "(-12345, 6789)", "negative centre coordinates are printed verbatim");
        checkContains(joinedWeird, " 14089 ", "the distance for (-12345,6789) is 14089 (no dropped parameter)");
        // cross check: the printed edge equals the edge function for the same numbers
        int weirdEdge = edge(visibleEdgeDistance, 14089, 1000, 0.2f);
        checkContains(joinedWeird, " " + Math.abs(weirdEdge) + " ",
                "the printed edge matches visibleEdgeDistance for the same numbers");

        // ---- 13. the not-found output string ----
        String[] noneAtAll = (String[]) notFoundLines.invoke(null, 5000, -1);
        checkEq(noneAtAll.length, 1, "not-found output is one line");
        samples.add("-- nothing found --");
        samples.add(noneAtAll[0]);
        checkContains(noneAtAll[0], NOT_FOUND, "not-found output says no city was found");
        checkContains(noneAtAll[0], " 5000 ", "not-found output names the search range");
        checkContains(noneAtAll[0], TRY_AGAIN, "not-found output tells the player how to widen the search");
        checkContains(noneAtAll[0], " 10000 ", "not-found output suggests twice the range");

        String[] noneWithin = (String[]) notFoundLines.invoke(null, 5000, 5811);
        samples.add(noneWithin[0]);
        checkContains(noneWithin[0], " 5811 ", "not-found output reports the nearest centre of the window");
        check(!noneWithin[0].contains(LIMIT_REACHED), "under the cap it still suggests a wider search");

        String[] atCap = (String[]) notFoundLines.invoke(null, 20000, 25000);
        samples.add(atCap[0]);
        checkContains(atCap[0], LIMIT_REACHED, "at the cap it says the range is already maxed out");
        check(!atCap[0].contains(TRY_AGAIN), "at the cap it does not suggest the same range again");

        String[] badLimit = (String[]) notFoundLines.invoke(null, 0, -1);
        checkContains(badLimit[0], " 5000 ", "an illegal limit falls back to the default in the message too");

        // ---- 14. every line carries the prefix ----
        List<String> all = new ArrayList<>();
        for (String[] block : new String[][]{outside, inside, onEdge, onCenter, noneAtAll}) {
            for (String s : block) {
                all.add(s);
            }
        }
        boolean prefixed = true;
        for (String s : all) {
            if (!s.startsWith(prefix)) {
                prefixed = false;
            }
        }
        check(prefixed, "all " + all.size() + " produced lines start with the CAF-LC prefix");

        Path out = tmp.resolve("cafcity-samples.txt");
        Files.write(out, samples, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Sample output written to: " + out);
        System.out.println();

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        System.out.println("\u901a\u8fc7 " + passed + " \u9879, \u5931\u8d25 " + failed + " \u9879");
        if (failed == 0) {
            System.out.println("CAFCITY-TEST-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- reflection helpers (the impl package is not on the group-A classpath) ----

    private static int limit(Method m, String raw) throws Exception {
        return (Integer) m.invoke(null, raw);
    }

    private static int sanitizeLimit(Method m, int v) throws Exception {
        return (Integer) m.invoke(null, v);
    }

    private static String notice(Method m, String raw, int used) throws Exception {
        return (String) m.invoke(null, raw, used);
    }

    private static int cellIndexOf(Method m, int block) throws Exception {
        return (Integer) m.invoke(null, block);
    }

    private static int centerOf(Method m, int index) throws Exception {
        return (Integer) m.invoke(null, index);
    }

    private static int windowOf(Method m, int l) throws Exception {
        return (Integer) m.invoke(null, l);
    }

    private static int dist(Method m, int x1, int z1, int x2, int z2) throws Exception {
        return (Integer) m.invoke(null, x1, z1, x2, z2);
    }

    private static int edge(Method m, int centerDistance, int radius, float threshold) throws Exception {
        return (Integer) m.invoke(null, centerDistance, radius, threshold);
    }

    private static String dir(Method m, int dx, int dz) throws Exception {
        return (String) m.invoke(null, dx, dz);
    }

    private static int zone(Method m, Boolean innerCity) throws Exception {
        return (Integer) m.invoke(null, innerCity);
    }

    private static String ztext(Method m, int visibleEdge, int zone) throws Exception {
        return (String) m.invoke(null, visibleEdge, zone);
    }

    // ---- assertions ----

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void checkEq(int actual, int expected, String what) {
        check(actual == expected, what + " (got " + actual + ", want " + expected + ")");
    }

    private static void checkEqStr(String actual, String expected, String what) {
        check(expected.equals(actual), what + " (got \"" + actual + "\", want \"" + expected + "\")");
    }

    private static void checkContains(String haystack, String needle, String what) {
        check(haystack != null && haystack.contains(needle), what + " (looked for \"" + needle + "\")");
    }
}
