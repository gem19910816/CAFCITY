import com.caf.lccustomcity.LcCustomCityMod;
import com.caf.lccustomcity.convert.LcAssetGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * 离线自检第二段: 验证"把数据包注入存档"的逻辑(哈希 / 幂等 / 新存档自动补上)。
 *
 * 用法: java InjectSmokeTest <建筑目录> <失落城市jar> <沙盒目录>
 */
public class InjectSmokeTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + name + (detail.isEmpty() ? "" : "  -> " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  -> " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        Path buildingDir = Path.of(args[0]);
        Path lcJar = Path.of(args[1]);
        Path sandbox = Path.of(args[2]);

        // 清空沙盒
        if (Files.exists(sandbox)) {
            try (Stream<Path> w = Files.walk(sandbox)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(sandbox);

        var log = LoggerFactory.getLogger("smoke");
        LcAssetGenerator.Output out = LcAssetGenerator.generate(buildingDir, log);
        com.caf.lccustomcity.convert.CityStylePatcher.patch(lcJar, out, log);

        String hash = LcCustomCityMod.contentHash(out);
        System.out.println("内容哈希 = " + hash);
        System.out.println("建筑: 单格 " + out.buildingIds.size() + ", 多格 " + out.multiIds.size());
        System.out.println();

        Path packName = Path.of(LcCustomCityMod.PACK_DIR_NAME);
        String marker = LcCustomCityMod.HASH_MARKER;

        // ---- 准备两个目录: 一个真存档, 一个假目录 ----
        Path save1 = sandbox.resolve("存档一");
        Files.createDirectories(save1);
        Files.writeString(save1.resolve("level.dat"), "dummy");
        Path notASave = sandbox.resolve("这不是存档");
        Files.createDirectories(notASave);

        System.out.println("--- 场景 1: 首次注入(只有 1 个真存档) ---");
        int n1 = LcCustomCityMod.injectIntoWorlds(sandbox, out, hash);
        check("注入数量 = 1", n1 == 1, "实际 " + n1);
        Path packDir = save1.resolve("datapacks").resolve(packName);
        check("pack.mcmeta 已写入", Files.exists(packDir.resolve("pack.mcmeta")), packDir.toString());
        check("data 目录已写入", Files.isDirectory(packDir.resolve("data")), "");
        check("哈希标记已写入", Files.exists(packDir.resolve(marker)), "");
        check("城市风格覆盖在 lostcities 命名空间",
                Files.exists(packDir.resolve("data/lostcities/lostcities/citystyles/citystyle_common.json")), "");
        check("没有 data/data 这个错误路径", !Files.exists(packDir.resolve("data/data")), "");
        check("假目录没有被注入", !Files.exists(notASave.resolve("datapacks")), "");

        // 统计实际文件数
        long fileCount;
        try (Stream<Path> w = Files.walk(packDir)) {
            fileCount = w.filter(Files::isRegularFile).count();
        }
        System.out.println("  (存档里共写入 " + fileCount + " 个文件)");

        System.out.println();
        System.out.println("--- 场景 2: 再注入一次, 内容没变应当跳过(幂等) ---");
        int n2 = LcCustomCityMod.injectIntoWorlds(sandbox, out, hash);
        check("注入数量 = 0(跳过)", n2 == 0, "实际 " + n2);

        System.out.println();
        System.out.println("--- 场景 3: 新建一个存档, 应当自动补上 ---");
        Path save2 = sandbox.resolve("新存档");
        Files.createDirectories(save2);
        Files.writeString(save2.resolve("level.dat"), "dummy");
        int n3 = LcCustomCityMod.injectIntoWorlds(sandbox, out, hash);
        check("注入数量 = 1(只补新存档)", n3 == 1, "实际 " + n3);
        check("新存档里有数据包",
                Files.exists(save2.resolve("datapacks").resolve(packName).resolve("pack.mcmeta")), "");

        System.out.println();
        System.out.println("--- 场景 4: 建筑内容变了(哈希不同), 应当重写 ---");
        int n4 = LcCustomCityMod.injectIntoWorlds(sandbox, out, "deadbeef");
        check("注入数量 = 2(两个存档都重写)", n4 == 2, "实际 " + n4);
        check("标记已更新",
                Files.readString(save1.resolve("datapacks").resolve(packName).resolve(marker)).trim()
                        .equals("deadbeef"), "");

        System.out.println();
        System.out.println("--- 场景 5: 空目录 / 不存在的目录 ---");
        Path empty = sandbox.resolve("空的saves");
        Files.createDirectories(empty);
        check("空目录返回 0", LcCustomCityMod.injectIntoWorlds(empty, out, hash) == 0, "");
        check("不存在目录返回 0",
                LcCustomCityMod.injectIntoWorlds(sandbox.resolve("根本不存在"), out, hash) == 0, "");

        System.out.println();
        System.out.println("--- 场景 6: 存档忽略名单(保护建筑提取源地图) ---");
        Path cfg = sandbox.resolve("config");
        Files.createDirectories(cfg);

        // 文件不存在 -> 名单为空, 所有存档都注入
        check("名单文件不存在时集合为空",
                LcCustomCityMod.loadIgnoreList(cfg).isEmpty(), "");

        // 名字里带 # 的存档名不能被当成注释行(真实源地图名字里就有 #)
        Files.writeString(cfg.resolve(LcCustomCityMod.IGNORE_LIST_FILE),
                "# 这是注释\r\n  " + LcCustomCityMod.DEFAULT_IGNORED_WORLD + "  \r\n\r\n", StandardCharsets.UTF_8);
        java.util.Set<String> ignore = LcCustomCityMod.loadIgnoreList(cfg);
        check("忽略名单读到 1 项(注释被跳过)", ignore.size() == 1, "实际 " + ignore);
        check("名字里的 # 不影响解析(只有行首的 # 才是注释)",
                ignore.contains(LcCustomCityMod.DEFAULT_IGNORED_WORLD.toLowerCase(java.util.Locale.ROOT)), "");
        check("前后空格被去掉", !ignore.contains(" " + LcCustomCityMod.DEFAULT_IGNORED_WORLD), "");

        // 造三个存档: 一个在名单里, 两个不在(用独立目录, 免得和前面场景的存档混在一起)
        Path saves6 = sandbox.resolve("场景6saves");
        Files.createDirectories(saves6);
        Path ignoredWorld = saves6.resolve(LcCustomCityMod.DEFAULT_IGNORED_WORLD);
        Path keptWorldA = saves6.resolve("应当被注入A");
        Path keptWorldB = saves6.resolve("应当被注入B");
        for (Path w : new Path[] {ignoredWorld, keptWorldA, keptWorldB}) {
            Files.createDirectories(w);
            Files.writeString(w.resolve("level.dat"), "dummy");
        }
        int n6 = LcCustomCityMod.injectIntoWorlds(saves6, out, "hash-c6", ignore);
        check("只注入不在名单里的 2 个存档", n6 == 2, "实际 " + n6);
        check("名单里的源地图没有被写入",
                !Files.exists(ignoredWorld.resolve("datapacks")), "");
        check("不在名单里的存档照常写入",
                Files.exists(keptWorldA.resolve("datapacks").resolve(packName).resolve("pack.mcmeta"))
                        && Files.exists(keptWorldB.resolve("datapacks").resolve(packName).resolve("pack.mcmeta")), "");

        // 空名单 -> 全都注入(含刚才被跳过的那个)
        int n7 = LcCustomCityMod.injectIntoWorlds(saves6, out, "hash-c7", java.util.Set.of());
        check("名单为空时 3 个存档全都注入", n7 == 3, "实际 " + n7);
        check("名单为空时源地图也会被写入",
                Files.exists(ignoredWorld.resolve("datapacks").resolve(packName).resolve("pack.mcmeta")), "");

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + pass + " 项, 失败 " + fail + " 项");
        System.out.println(fail == 0 ? "INJECT-TEST-ALL-PASS" : "INJECT-TEST-HAS-FAILURES");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
