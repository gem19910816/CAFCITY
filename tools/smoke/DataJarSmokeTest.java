package com.caf.lccustomcity;

import com.caf.lccustomcity.convert.LcAssetGenerator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 离线测试: 数据包模组(mods/CAF-LC-建筑包-*.jar)的打包与编号逻辑。
 *
 * <p>重点验证三件事:
 * <ol>
 *   <li>文件名里的编号能被正确解析 —— 编号决定优先级, 解析错了就会用错内容</li>
 *   <li>jar 里的路径正确({@code data/} 前缀)且 {@code ordering="AFTER"} 写对了 —— 这是覆盖原版的关键</li>
 *   <li>内容变化时写出编号更大的新包, 内容没变时不重复写</li>
 * </ol>
 */
public final class DataJarSmokeTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/smoke-tmp").toAbsolutePath();
        deleteRecursively(root);
        Files.createDirectories(root);
        Path gameDir = root.resolve("game");
        Files.createDirectories(gameDir.resolve("mods"));

        // ---------- 1. 文件名解析 ----------
        check("counterOf 正常解析", LcCustomCityMod.counterOf("CAF-LC-建筑包-0007-abcdef12.jar") == 7);
        check("counterOf 解析两位数编号", LcCustomCityMod.counterOf("CAF-LC-建筑包-0042-deadbeef.jar") == 42);
        check("hashOf 正常解析",
                "abcdef12".equals(LcCustomCityMod.hashOf("CAF-LC-建筑包-0007-abcdef12.jar")));
        check("counterOf 对别人的 jar 返回 -1", LcCustomCityMod.counterOf("lostcities-1.21-8.4.4.jar") == -1);
        check("hashOf 对别人的 jar 返回 null", LcCustomCityMod.hashOf("lostcities-1.21-8.4.4.jar") == null);
        check("counterOf 对空前缀返回 -1", LcCustomCityMod.counterOf("CAF-LC-建筑包-.jar") == -1);

        // ---------- 2. 第一次写出 ----------
        LcAssetGenerator.Output out = new LcAssetGenerator.Output();
        out.files.put("lostcities/lostcities/citystyles/citystyle_common.json",
                "{\"marker\":\"citystyle\"}");
        out.files.put("caf_lc_customcity/lostcities/parts/mypart.json",
                "{\"marker\":\"part\"}");
        out.buildingIds.add("mybuilding");

        String h1 = "11111111";
        LcCustomCityMod.writeDataJar(gameDir, out, h1);

        Path modsDir = gameDir.resolve("mods");
        List<Path> jars = LcCustomCityMod.findDataJars(modsDir);
        check("第一次写出 1 个 jar", jars.size() == 1);
        check("第一个 jar 编号为 0001",
                !jars.isEmpty() && "CAF-LC-建筑包-0001-11111111.jar".equals(jars.get(0).getFileName().toString()));
        check("没有留下 .tmp 文件", !Files.exists(
                modsDir.resolve("CAF-LC-建筑包-0001-11111111.jar.tmp")));

        // ---------- 3. jar 内容 ----------
        if (!jars.isEmpty()) {
            try (ZipFile zip = new ZipFile(jars.get(0).toFile(), StandardCharsets.UTF_8)) {
                check("jar 里有 META-INF/neoforge.mods.toml", zip.getEntry("META-INF/neoforge.mods.toml") != null);
                check("jar 里有 pack.mcmeta", zip.getEntry("pack.mcmeta") != null);
                check("citystyle 覆盖在正确的路径下", zip.getEntry(
                        "data/lostcities/lostcities/citystyles/citystyle_common.json") != null);
                check("本模组命名空间的 part 在 data/ 下", zip.getEntry(
                        "data/caf_lc_customcity/lostcities/parts/mypart.json") != null);

                String toml = read(zip, "META-INF/neoforge.mods.toml");
                check("modLoader 是 lowcodefml(纯数据模组)", toml.contains("modLoader=\"lowcodefml\""));
                check("modId 与编号一致", toml.contains("modId=\"caf_lc_buildings_0001\""));
                check("依赖里声明了 lostcities", toml.contains("modId=\"lostcities\""));
                check("关键: ordering=\"AFTER\" 让本包排在失落城市后面", toml.contains("ordering=\"AFTER\""));
                check("依赖里声明了 neoforge", toml.contains("modId=\"neoforge\""));

                String meta = read(zip, "pack.mcmeta");
                check("pack.mcmeta 里的格式号正确", meta.contains("\"pack_format\": 48"));
            }
        }

        // ---------- 4. 内容没变时不重复写 ----------
        LcCustomCityMod.writeDataJar(gameDir, out, h1);
        check("哈希没变时不写新 jar",
                LcCustomCityMod.findDataJars(modsDir).size() == 1);

        // ---------- 5. 内容变了 -> 编号递增 ----------
        out.files.put("caf_lc_customcity/lostcities/parts/mypart2.json", "{\"marker\":\"part2\"}");
        String h2 = "22222222";
        LcCustomCityMod.writeDataJar(gameDir, out, h2);

        jars = LcCustomCityMod.findDataJars(modsDir);
        check("内容变化后写出编号更大的新 jar",
                !jars.isEmpty() && "CAF-LC-建筑包-0002-22222222.jar"
                        .equals(jars.get(jars.size() - 1).getFileName().toString()));
        check("旧 jar 已删掉(测试环境没被占用)", jars.size() == 1);

        // 单独验证排序契约: 编号大的必须排在后面, 因为它优先级更高
        Path sortDir = root.resolve("sort-mods");
        Files.createDirectories(sortDir);
        for (String n : new String[] {"CAF-LC-建筑包-0010-cccccccc.jar",
                "CAF-LC-建筑包-0002-aaaaaaaa.jar",
                "CAF-LC-建筑包-0005-bbbbbbbb.jar",
                "lostcities-1.21-8.4.4.jar"}) {
            Files.writeString(sortDir.resolve(n), "");
        }
        List<Path> sorted = LcCustomCityMod.findDataJars(sortDir);
        check("只挑出自己的建筑包, 不会误伤别人的 jar", sorted.size() == 3);
        check("编号大的排在最后(优先级更高)",
                sorted.size() == 3
                        && LcCustomCityMod.counterOf(sorted.get(sorted.size() - 1).getFileName().toString()) == 10);

        // ---------- 6. 新 jar 里带上了新内容 ----------
        if (!jars.isEmpty()) {
            try (ZipFile zip = new ZipFile(jars.get(0).toFile(), StandardCharsets.UTF_8)) {
                check("新 jar 包含新增的 part", zip.getEntry(
                        "data/caf_lc_customcity/lostcities/parts/mypart2.json") != null);
                String toml = read(zip, "META-INF/neoforge.mods.toml");
                check("新 jar 的 modId 也递增了", toml.contains("modId=\"caf_lc_buildings_0002\""));
            }
        }

        // ---------- 7. 哈希回滚也要写出新包(否则旧的会赢) ----------
        LcCustomCityMod.writeDataJar(gameDir, out, h1);
        jars = LcCustomCityMod.findDataJars(modsDir);
        check("哈希回滚时仍写出编号更大的包",
                !jars.isEmpty() && "CAF-LC-建筑包-0003-11111111.jar"
                        .equals(jars.get(jars.size() - 1).getFileName().toString()));

        // ---------- 结果 ----------
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过: " + passed + " 项断言");
            deleteRecursively(root);
        } else {
            System.out.println("通过 " + passed + " 项, 失败 " + failures.size() + " 项:");
            failures.forEach(f -> System.out.println("   ✗ " + f));
            System.out.println("临时目录保留用于排查: " + root);
            System.exit(1);
        }
    }

    private static String read(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return "";
        }
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + what);
        } else {
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 忽略
                }
            });
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
