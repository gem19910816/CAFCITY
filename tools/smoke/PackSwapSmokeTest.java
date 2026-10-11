import com.caf.lccustomcity.convert.LcAssetGenerator;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * Offline check for the data pack write path (LcAssetGenerator.writeTo).
 *
 * It guards the properties the player-visible bug was about:
 *   1. a pack is NEVER left half rebuilt (staging dir + whole-tree swap);
 *   2. files that are no longer generated DISAPPEAR (no stale leftovers, which
 *      is what makes a pack reference parts that do not exist);
 *   3. if the leftover/old tree cannot be cleaned up, the rebuild is skipped as a
 *      whole and the OLD pack is kept intact (old but complete, never broken);
 *   4. no data.new / data.old residue inside a finished pack.
 *
 * With an optional second argument it also verifies a REAL generated pack:
 * every part / palette / cell building referenced by buildings/*.json and
 * multibuildings/*.json must exist on disk - that is exactly the
 * "referenced part is missing" symptom, checked over every generated building.
 * This check also proves that the multi-cell "_top" part of each cell is written
 * (building_xxx_cN_M_top.json), i.e. the generator did not silently skip it.
 *
 * Usage: java PackSwapSmokeTest [workDir] [rootContainingAGeneratedPack]
 *
 * NOTE: this file is ASCII-only except the mandated summary line at the end.
 */
public class PackSwapSmokeTest {

    private static int pass = 0;
    private static int fail = 0;

    private static final Pattern NS_REF =
            Pattern.compile("caf_lc_customcity:([A-Za-z0-9_./-]+)");

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + name + (detail.isEmpty() ? "" : "  -> " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  -> " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base;
        if (args.length > 0 && !args[0].isEmpty()) {
            base = Path.of(args[0]);
        } else {
            base = Files.createTempDirectory("caf-packswap-");
        }
        Path realPackRoot = args.length > 1 && !args[1].isEmpty() ? Path.of(args[1]) : null;

        // Never delete the directory we are handed: only this sub directory is ours.
        Path work = base.resolve("packswap-work");
        deleteTree(work);
        Files.createDirectories(work);
        Path packDir = work.resolve("saves").resolve("world").resolve("datapacks").resolve("caf_lc_customcity");
        System.out.println("work dir  = " + work);
        System.out.println("pack dir  = " + packDir);
        if (realPackRoot != null) {
            System.out.println("real pack = " + realPackRoot + "  (read only)");
        }

        var log = LoggerFactory.getLogger("smoke");

        // ---------------------------------------------------------------
        System.out.println();
        System.out.println("--- 1. first write: pack does not exist yet -------------------");
        LcAssetGenerator.Output v1 = new LcAssetGenerator.Output();
        v1.files.put("caf_lc_customcity/lostcities/parts/p_a.json", "{\"v\":1}");
        v1.files.put("caf_lc_customcity/lostcities/parts/p_b.json", "{\"v\":1}");
        v1.files.put("caf_lc_customcity/lostcities/buildings/b_a.json", "{\"v\":1}");
        v1.files.put("caf_lc_customcity/lostcities/palettes/pal_a.json", "{\"v\":1}");
        v1.files.put("lostcities/lostcities/citystyles/citystyle_common.json", "{\"v\":1}");
        LcAssetGenerator.writeTo(v1, packDir, log);

        check("pack.mcmeta written", Files.exists(packDir.resolve("pack.mcmeta")), "");
        check("mod namespace asset written",
                Files.exists(packDir.resolve("data").resolve("caf_lc_customcity")
                        .resolve("lostcities").resolve("parts").resolve("p_a.json")), "");
        check("lostcities namespace override written",
                Files.exists(packDir.resolve("data").resolve("lostcities")
                        .resolve("lostcities").resolve("citystyles").resolve("citystyle_common.json")), "");
        check("no bogus data/data path", !Files.exists(packDir.resolve("data").resolve("data")), "");
        check("no staging residue", !Files.exists(packDir.resolve("data.new")), "");
        check("no backup residue", !Files.exists(packDir.resolve("data.old")), "");

        // ---------------------------------------------------------------
        System.out.println();
        System.out.println("--- 2. content shrinks: removed + hand-made stale files must vanish ---");
        // A file that only exists on disk (like the leftovers of an older, half
        // deleted pack). It MUST be gone after the rebuild.
        Path stale = packDir.resolve("data").resolve("caf_lc_customcity")
                .resolve("lostcities").resolve("parts").resolve("HAND_MADE_STALE.json");
        Files.writeString(stale, "{\"stale\":true}", StandardCharsets.UTF_8);

        LcAssetGenerator.Output v2 = new LcAssetGenerator.Output();
        v2.files.put("caf_lc_customcity/lostcities/parts/p_a.json", "{\"v\":2}");
        v2.files.put("caf_lc_customcity/lostcities/buildings/b_a.json", "{\"v\":2}");
        v2.files.put("caf_lc_customcity/lostcities/palettes/pal_a.json", "{\"v\":2}");
        v2.files.put("lostcities/lostcities/citystyles/citystyle_common.json", "{\"v\":2}");
        LcAssetGenerator.writeTo(v2, packDir, log);

        Path partA = packDir.resolve("data").resolve("caf_lc_customcity")
                .resolve("lostcities").resolve("parts").resolve("p_a.json");
        check("rewritten file has new content",
                Files.readString(partA, StandardCharsets.UTF_8).contains("\"v\":2"), "");
        check("file that is no longer generated is gone (p_b.json)",
                !Files.exists(packDir.resolve("data").resolve("caf_lc_customcity")
                        .resolve("lostcities").resolve("parts").resolve("p_b.json")), "");
        check("hand-made stale file is gone", !Files.exists(stale), "");
        check("untouched external asset survived",
                Files.readString(packDir.resolve("data").resolve("lostcities")
                        .resolve("lostcities").resolve("citystyles")
                        .resolve("citystyle_common.json"), StandardCharsets.UTF_8).contains("\"v\":2"), "");
        check("still no staging residue", !Files.exists(packDir.resolve("data.new")), "");
        check("still no backup residue", !Files.exists(packDir.resolve("data.old")), "");

        // ---------------------------------------------------------------
        System.out.println();
        System.out.println("--- 3. undeletable residue: skip the rebuild, keep the OLD pack ---");
        if (!canBlockDeletion(work)) {
            check("undeletable-residue case", true,
                    "(skipped: this filesystem allows deleting a file that is open)");
        } else {
            Path staging = packDir.resolve("data.new");
            Files.createDirectories(staging);
            Path locked = staging.resolve("residue.txt");
            Files.writeString(locked, "x", StandardCharsets.UTF_8);
            boolean thrown;
            try (FileChannel fc = FileChannel.open(locked, StandardOpenOption.WRITE)) {
                LcAssetGenerator.Output v3 = new LcAssetGenerator.Output();
                v3.files.put("caf_lc_customcity/lostcities/parts/p_a.json", "{\"v\":3}");
                v3.files.put("caf_lc_customcity/lostcities/parts/SHOULD_NOT_APPEAR.json", "{\"v\":3}");
                IOException caught = null;
                try {
                    LcAssetGenerator.writeTo(v3, packDir, log);
                } catch (IOException e) {
                    caught = e;
                }
                thrown = caught != null;
                System.out.println("  (writeTo said: " + caught + ")");
                check("rebuild refused while a leftover cannot be deleted", thrown, "");
                check("old pack kept (file of v2 still there)",
                        Files.readString(partA, StandardCharsets.UTF_8).contains("\"v\":2"), "");
                check("old pack NOT overwritten by the refused rebuild",
                        !Files.exists(packDir.resolve("data").resolve("caf_lc_customcity")
                                .resolve("lostcities").resolve("parts")
                                .resolve("SHOULD_NOT_APPEAR.json")), "");
                check("old pack still complete (pack.mcmeta + data dir)",
                        Files.exists(packDir.resolve("pack.mcmeta"))
                                && Files.isDirectory(packDir.resolve("data")), "");
            }
            // release the lock and prove the next attempt succeeds (self healing)
            deleteTree(staging);
            boolean reused;
            try {
                LcAssetGenerator.writeTo(v2, packDir, log);
                reused = !Files.exists(packDir.resolve("data.new")) && Files.exists(partA);
            } catch (IOException e) {
                reused = false;
                System.out.println("  (writeTo said: " + e + ")");
            }
            check("next attempt after the lock is released succeeds", reused, "");
        }

        // ---------------------------------------------------------------
        System.out.println();
        System.out.println("--- 4. real generated pack: every referenced asset must exist ---");
        if (realPackRoot == null) {
            check("real pack integrity", true, "(skipped: no pack root given as 2nd argument)");
        } else {
            Path pack = findPack(realPackRoot);
            if (pack == null) {
                check("real pack integrity", false, "no generated pack found under " + realPackRoot);
            } else {
                System.out.println("  pack = " + pack);
                check("no staging residue in the real pack",
                        !Files.exists(pack.resolve("data.new")), "");
                check("no backup residue in the real pack",
                        !Files.exists(pack.resolve("data.old")), "");
                checkRealPack(pack);
            }
        }

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + pass + " 项, 失败 " + fail + " 项");
        System.out.println(fail == 0 ? "PACK-SWAP-TEST-ALL-PASS" : "PACK-SWAP-TEST-HAS-FAILURES");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * Every asset id our namespace is referenced with, inside buildings/*.json and
     * multibuildings/*.json, must exist on disk. Also counts the multi-cell
     * "_top" parts, since a missing _top part is exactly the reported symptom.
     */
    private static void checkRealPack(Path pack) throws Exception {
        Path data = pack.resolve("data").resolve("caf_lc_customcity").resolve("lostcities");
        Path parts = data.resolve("parts");
        Path buildings = data.resolve("buildings");
        Path palettes = data.resolve("palettes");
        Path multis = data.resolve("multibuildings");

        int buildingFiles = 0;
        int refsChecked = 0;
        int missing = 0;
        String firstMissing = "";
        int topRefs = 0;
        int topMissing = 0;
        TreeSet<String> topParts = new TreeSet<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(buildings, "*.json")) {
            for (Path f : ds) {
                buildingFiles++;
                for (String id : refsOf(f)) {
                    refsChecked++;
                    boolean ok = Files.exists(parts.resolve(id + ".json"))
                            || Files.exists(palettes.resolve(id + ".json"));
                    if (!ok) {
                        missing++;
                        if (firstMissing.isEmpty()) {
                            firstMissing = f.getFileName() + " -> " + id;
                        }
                    }
                    if (id.endsWith("_top")) {
                        topRefs++;
                        if (Files.exists(parts.resolve(id + ".json"))) {
                            topParts.add(id);
                        } else {
                            topMissing++;
                        }
                    }
                }
            }
        }
        check("building files scanned", buildingFiles > 0, String.valueOf(buildingFiles));
        check("every part/palette referenced by buildings/*.json exists",
                missing == 0, missing == 0 ? (refsChecked + " references checked")
                        : (missing + " missing, first: " + firstMissing));
        check("every referenced _top (upper part) exists",
                topMissing == 0, topRefs + " _top references checked");
        check("pack really contains _top parts on disk", !topParts.isEmpty(),
                topParts.size() + " distinct _top parts");

        int multiFiles = 0;
        int cellsMissing = 0;
        String firstCellMissing = "";
        int cells = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(multis, "*.json")) {
            for (Path f : ds) {
                multiFiles++;
                for (String id : refsOf(f)) {
                    cells++;
                    if (!Files.exists(buildings.resolve(id + ".json"))) {
                        cellsMissing++;
                        if (firstCellMissing.isEmpty()) {
                            firstCellMissing = f.getFileName() + " -> " + id;
                        }
                    }
                }
            }
        }
        check("multibuilding files scanned", multiFiles > 0, String.valueOf(multiFiles));
        check("every multi-cell building of every multibuilding exists",
                cellsMissing == 0, cellsMissing == 0 ? (cells + " cells checked")
                        : (cellsMissing + " missing, first: " + firstCellMissing));
    }

    /** All "caf_lc_customcity:<id>" references inside one json file. */
    private static List<String> refsOf(Path json) throws IOException {
        List<String> ids = new ArrayList<>();
        Matcher m = NS_REF.matcher(Files.readString(json, StandardCharsets.UTF_8));
        while (m.find()) {
            if (!ids.contains(m.group(1))) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /** Find a directory that looks like our generated pack (pack.mcmeta + data/) below root. */
    private static Path findPack(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        if (Files.exists(root.resolve("pack.mcmeta")) && Files.isDirectory(root.resolve("data"))) {
            return root;
        }
        try (Stream<Path> walk = Files.walk(root, 6)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("pack.mcmeta")))
                    .filter(p -> Files.isDirectory(p.resolve("data")))
                    .filter(p -> Files.isDirectory(p.resolve("data").resolve("caf_lc_customcity")))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Does deleting an open file fail on this filesystem? On Windows it does. */
    private static boolean canBlockDeletion(Path work) {
        Path probe = work.resolve("delete-probe.txt");
        boolean blocked = false;
        try {
            Files.writeString(probe, "x", StandardCharsets.UTF_8);
            try (FileChannel fc = FileChannel.open(probe, StandardOpenOption.WRITE)) {
                try {
                    Files.delete(probe);
                } catch (IOException e) {
                    blocked = true;
                }
            }
        } catch (IOException e) {
            blocked = false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // probe file only
            }
        }
        return blocked;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: sandbox only
                }
            });
        } catch (IOException ignored) {
            // best effort: sandbox only
        }
    }
}
