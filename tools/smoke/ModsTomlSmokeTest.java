import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * neoforge.mods.toml 的依赖声明自检。
 *
 * <p>为什么需要它 —— 1.0.18 崩过游戏, 而这个错误所有别的测试都抓不到:
 *
 * <pre>
 *   [ERROR] ModSorter: Missing or unsupported mandatory dependencies:
 *       Mod ID: 'lostcities', Requested by: 'caf_lc_customcity',
 *       Expected range: '[8.4,)', Actual version: '1.21-8.4.4'
 *   [FATAL] Error during pre-loading phase: Mod caf_lc_customcity requires lostcities 8.4 or above
 * </pre>
 *
 * <p>失落城市的版本号字符串是 {@code 1.21-8.4.4}, 按版本比较规则会被拆成
 * {@code 1,21,8,4,4}, 第一位 1 就小于 8, 于是任何形如 {@code [8.4,)} 的约束都会
 * 判定"不满足" —— 游戏连主菜单都进不去。加载顺序交给 {@code ordering="AFTER"}。
 *
 * <p>这个测试不依赖任何版本比较库, 直接断言"策略": lostcities 的 versionRange
 * 必须是 {@code [0,)}。这样谁把它改回紧约束, 测试立刻红。
 *
 * <p>用法: java ModsTomlSmokeTest &lt;工程根目录&gt; [失落城市jar]
 */
public final class ModsTomlSmokeTest {

    /** 必须是这个值。理由见类注释。 */
    private static final String REQUIRED_RANGE = "[0,)";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path lcJar = args.length > 1 && !args[1].equals("-") ? Path.of(args[1]) : null;

        System.out.println("=== neoforge.mods.toml 依赖声明自检 ===");
        System.out.println("工程根目录: " + root);
        System.out.println();

        Path resourceToml = root.resolve("src/main/resources/META-INF/neoforge.mods.toml");
        Path modSource = root.resolve("src/main/java/com/caf/lccustomcity/LcCustomCityMod.java");

        // ---- 1. 资源里的 toml ----
        System.out.println("--- 1. 资源文件 " + resourceToml.getFileName() + " ---");
        List<String> fromResource = lostcitiesRanges(readAll(resourceToml), "资源 toml");
        check(!fromResource.isEmpty(),
                "从资源 toml 里找到了 lostcities 依赖声明 (找到 " + fromResource.size() + " 处)");
        for (String r : fromResource) {
            check(REQUIRED_RANGE.equals(r),
                    "资源 toml 里 lostcities 的 versionRange 是 " + REQUIRED_RANGE + " (实际: " + r + ")");
        }

        // ---- 2. Java 里动态生成的那份 toml ----
        // 兜底建筑包 mods/CAF-LC-建筑包-*.jar 的 toml 是这个方法拼出来的。
        // 只改资源、忘了这里, 一样会崩 —— 所以两份都要查, 还要查它们一致。
        System.out.println();
        System.out.println("--- 2. Java 源码里生成的 toml (" + modSource.getFileName() + ") ---");
        List<String> fromJava = lostcitiesRanges(readAll(modSource), "Java 源码");
        check(!fromJava.isEmpty(),
                "从 Java 源码里找到了 lostcities 依赖声明 (找到 " + fromJava.size() + " 处)");
        for (String r : fromJava) {
            check(REQUIRED_RANGE.equals(r),
                    "Java 源码里 lostcities 的 versionRange 是 " + REQUIRED_RANGE + " (实际: " + r + ")");
        }

        // ---- 3. 两份必须一致 ----
        System.out.println();
        System.out.println("--- 3. 两处声明应当一致 ---");
        check(fromResource.equals(fromJava),
                "资源 toml 与 Java 源码里的 lostcities 声明一致 (资源 " + fromResource
                        + ", Java " + fromJava + ")");

        // ---- 4. 失落城市的真实版本号确实是"非标准"的 ----
        // 这一条是给未来的人看的: 如果哪天失落城市把版本号改成干净的 8.4.4,
        // 上面那条 [0,) 依然安全, 但紧约束也可以用了。这里只做提示, 不算失败。
        System.out.println();
        System.out.println("--- 4. 失落城市的真实版本号 (背景信息) ---");
        if (lcJar == null) {
            System.out.println("  (没给失落城市 jar, 跳过)");
        } else {
            String v = readModVersionFromJar(lcJar);
            if (v == null) {
                System.out.println("  [提示] 没能从 jar 里读出版本号, 跳过");
            } else {
                boolean looksStandard = v.matches("\\d+(\\.\\d+)*");
                System.out.println("  [信息] 失落城市版本号 = " + v
                        + (looksStandard ? " (标准版本号)" : " (非标准: 带前缀, 紧约束会误判)"));
                if (!looksStandard) {
                    check(v.startsWith("1.21-"),
                            "版本号确实是 1.21- 前缀 —— 这正是必须用 " + REQUIRED_RANGE + " 的原因");
                }
            }
        }

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + passed + " 项, 失败 " + failed + " 项");
        if (failed == 0) {
            System.out.println("MODSTOML-TEST-ALL-PASS");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    /** 找出所有 lostcities 依赖块里声明的 versionRange 值。 */
    private static List<String> lostcitiesRanges(List<String> lines, String where) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().equals("modId=\"lostcities\"")) {
                continue;
            }
            // 往后找同一条目里的 versionRange(条目之间用空行分隔)
            for (int j = i + 1; j < lines.size() && j < i + 12; j++) {
                String t = lines.get(j).trim();
                if (t.isEmpty() || t.startsWith("[")) {
                    break;
                }
                int k = t.indexOf("versionRange=");
                if (k == 0) {
                    out.add(t.substring("versionRange=".length()).replace("\"", "").trim());
                    break;
                }
            }
        }
        return out;
    }

    /** 从失落城市 jar 的 toml/manifest 里读出它声明的版本号。 */
    private static String readModVersionFromJar(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (String name : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                ZipEntry e = zf.getEntry(name);
                if (e == null) {
                    continue;
                }
                String text = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String t = line.trim();
                    if (t.startsWith("version=")) {
                        String v = t.substring("version=".length()).replace("\"", "").replace("'", "").trim();
                        if (!v.startsWith("${")) {
                            return v;
                        }
                    }
                }
            }
            // 退回到 manifest
            ZipEntry mf = zf.getEntry("META-INF/MANIFEST.MF");
            if (mf != null) {
                String text = new String(zf.getInputStream(mf).readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (line.startsWith("Implementation-Version:")) {
                        return line.substring("Implementation-Version:".length()).trim();
                    }
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<String> readAll(Path p) throws Exception {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + what);
        } else {
            failed++;
            System.out.println("  [失败] " + what);
        }
    }
}
