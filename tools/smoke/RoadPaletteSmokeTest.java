import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.convert.RoadStyleGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Road palette offline self-check.
 *
 * Lost Cities (CompiledPalette.addPalettes) expands every random palette entry
 * into a BlockState[128] array: each "random" weight is the number of slots the
 * block occupies. If the weights do not add up to at least 128 it throws
 * "Invalid palette entry for 'X'! Not enough blocks in the random list
 * (factor should go up to 128)" during chunk generation.
 *
 * This test runs RoadStyleGenerator (lcJar = null, so only the palettes and the
 * wide-road parts are generated) and asserts for both road palettes
 * (road_inner / road_outer):
 *   - every entry with a "blocks" random list has weights summing to exactly 128
 *   - every weight is positive (no zero-weight entries)
 *   - the block ids are non-empty strings
 *   - generation is deterministic (two runs produce identical JSON)
 *
 * No Minecraft classes needed. Runs in group A of run-offline-tests.ps1.
 *
 * Usage: java RoadPaletteSmokeTest   (no arguments)
 */
public class RoadPaletteSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] args) {
        System.out.println("=== Road palette offline self-check (weights must reach 128) ===");

        LcAssetGenerator.Output out1 = new LcAssetGenerator.Output();
        LcAssetGenerator.Output out2 = new LcAssetGenerator.Output();
        RoadStyleGenerator.generate(null, out1, LoggerFactory.getLogger("RoadPaletteSmokeTest"));
        RoadStyleGenerator.generate(null, out2, LoggerFactory.getLogger("RoadPaletteSmokeTest"));

        for (String zone : new String[]{"inner", "outer"}) {
            String key = LcAssetGenerator.NS + "/lostcities/palettes/road_" + zone + ".json";
            System.out.println("--- " + key + " ---");

            String json1 = out1.files.get(key);
            String json2 = out2.files.get(key);
            check(json1 != null, "palette file generated");
            if (json1 == null) {
                continue;
            }
            check(json1.equals(json2), "deterministic: two runs produce identical JSON");

            JsonObject root = JsonParser.parseString(json1).getAsJsonObject();
            JsonArray palette = root.getAsJsonArray("palette");
            check(palette != null && palette.size() > 0, "palette array present");

            int randomEntries = 0;
            for (JsonElement el : palette) {
                JsonObject entry = el.getAsJsonObject();
                String ch = entry.get("char").getAsString();
                if (!entry.has("blocks")) {
                    check(entry.has("block") && !entry.get("block").getAsString().isEmpty(),
                            "'" + ch + "': plain entry has a block id");
                    continue;
                }
                randomEntries++;
                JsonArray blocks = entry.getAsJsonArray("blocks");
                int sum = 0;
                boolean allPositive = true;
                boolean allHaveBlock = true;
                for (JsonElement bEl : blocks) {
                    JsonObject b = bEl.getAsJsonObject();
                    int w = b.get("random").getAsInt();
                    sum += w;
                    if (w <= 0) { allPositive = false; }
                    if (!b.has("block") || b.get("block").getAsString().isEmpty()) { allHaveBlock = false; }
                }
                System.out.println("    '" + ch + "': " + blocks.size()
                        + " blocks, weight sum = " + sum);
                check(sum == 128, "'" + ch + "': random weights sum to exactly 128 (Lost Cities fills BlockState[128])");
                check(allPositive, "'" + ch + "': every weight is positive");
                check(allHaveBlock, "'" + ch + "': every entry has a block id");
            }
            check(randomEntries >= 2, "both 'S' and 'l' use random lists");
        }

        System.out.println();
        System.out.println("================================");
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed == 0) {
            System.out.println("ROAD-PALETTE-TEST-ALL-PASS");
            System.exit(0);
        } else {
            System.exit(1);
        }
    }
}
