import com.caf.lccustomcity.convert.CityStylePatcher;
import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * 城区分区离线自检 —— 验证"三层扫描 + 内城/外城分组"把建筑放进了对的城市风格。
 *
 * <p>搭一个三层目录: 根目录放 1 栋(默认内城)、内城\住宅区 放 1 栋、外城\民房 放 1 栋,
 * 跑一遍真实转换, 然后检查分组与 citystyle 路由是否正确。
 * 注意: 建筑可能是单格也可能是多格, 所以分组断言把"单格+多格"一起算。
 *
 * 用法: java ZoningSmokeTest &lt;沙盒目录&gt; &lt;失落城市jar&gt; &lt;建筑来源目录&gt;
 */
public class ZoningSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  [OK]   " + name); }
        else { failed++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]);
        Path lcJar = Path.of(args[1]);
        Path src = Path.of(args[2]);
        var log = LoggerFactory.getLogger("zoning");

        System.out.println("=== 城区分区离线自检 ===");
        System.out.println("沙盒: " + sandbox);

        // ---- 收集来源建筑(.schem) ----
        List<Path> schems;
        try (Stream<Path> walk = Files.walk(src, 1)) {
            schems = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".schem"))
                    .sorted().toList();
        }
        if (schems.size() < 3) {
            System.out.println("来源建筑不足 3 个, 无法测试 (实际 " + schems.size() + ")");
            System.exit(1);
        }

        // ---- 搭三层目录结构 ----
        Path cfg = sandbox.resolve("失落城市建筑");
        Files.createDirectories(cfg.resolve("内城/住宅区"));
        Files.createDirectories(cfg.resolve("外城/民房"));
        Files.copy(schems.get(0), cfg.resolve("根目录建筑.schem"));
        Files.copy(schems.get(1), cfg.resolve("内城/住宅区/内城建筑.schem"));
        Files.copy(schems.get(2), cfg.resolve("外城/民房/外城建筑.schem"));
        System.out.println("--- 搭好结构: 根目录1 + 内城\\住宅区1 + 外城\\民房1 ---");

        // ---- 跑真实转换 ----
        LcAssetGenerator.Output out = LcAssetGenerator.generate(cfg, log);

        // 内城集合 = 内城单格 + 内城多格; 外城集合 = 外城单格 + 外城多格
        Set<String> inner = new HashSet<>(out.buildingIds);
        inner.addAll(out.multiIds);
        Set<String> outer = new HashSet<>(out.outerBuildingIds);
        outer.addAll(out.outerMultiIds);
        System.out.println("--- 分组结果 ---");
        System.out.println("    内城 " + inner.size() + " 栋 : " + inner);
        System.out.println("    外城 " + outer.size() + " 栋 : " + outer);

        check(inner.size() == 2, "内城建筑=2 栋(根目录+内城) [实际 " + inner.size() + "]");
        check(outer.size() == 1, "外城建筑=1 栋 [实际 " + outer.size() + "]");
        check(!out.isEmpty(), "三层深处的建筑被扫到了(没漏)");
        Set<String> both = new HashSet<>(inner);
        both.retainAll(outer);
        check(both.isEmpty(), "内城/外城建筑不重叠(没有一栋被算进两边)");

        // ---- 覆盖城市风格, 检查内外城各自拿到了对的建筑 ----
        CityStylePatcher.patch(lcJar, out, log);
        Set<String> commonAll = idsOf(out, "citystyle_common.json", "buildings");
        commonAll.addAll(idsOf(out, "citystyle_common.json", "multibuildings"));
        Set<String> borderAll = idsOf(out, "citystyle_border.json", "buildings");
        borderAll.addAll(idsOf(out, "citystyle_border.json", "multibuildings"));
        System.out.println("--- citystyle 建筑列表 ---");
        System.out.println("    common(内城) 共 " + commonAll.size() + " 个引用");
        System.out.println("    border(外城) 共 " + borderAll.size() + " 个引用");

        String outerId = outer.iterator().next();
        check(borderAll.contains(outerId), "外城建筑进了 citystyle_border(外城)");
        check(!commonAll.contains(outerId), "外城建筑没有泄进 citystyle_common(内城)");
        for (String id : inner) {
            check(commonAll.contains(id), "内城建筑进了 citystyle_common: " + id);
        }

        // ---- 回归: 全是多格建筑的子分区也必须生成分区城市风格 ----
        // 历史 bug: generateSubZoneStyles 的门槛只看 innerSubBuildingIds(单格表),
        // 玩家全是多格建筑时该表为空 → 一个 caf_zone_inner_* 都不生成 → 分区整体失效。
        // 这里直接手工构造一个"只有多格子分区"的 Output(不依赖来源建筑是单格还是多格)。
        System.out.println("--- 回归: 全多格子分区 ---");
        LcAssetGenerator.Output out2 = new LcAssetGenerator.Output();
        out2.buildingIds.add("dummy_single");   // 让 citystyle_common 的 buildings 非空
        out2.multiIds.add("dummy_multi");
        // 注意: 只登记多格表, 单格表保持为空 —— 正是触发历史 bug 的场景
        out2.innerSubMultiIds.computeIfAbsent("工业区", k -> new ArrayList<>()).add("dummy_multi");
        CityStylePatcher.patch(lcJar, out2, log);
        List<String> zoneStyles = new ArrayList<>();
        for (String key : out2.files.keySet()) {
            if (key.startsWith("lostcities/lostcities/citystyles/" + CityStylePatcher.ZONE_STYLE_PREFIX)) {
                zoneStyles.add(key);
            }
        }
        System.out.println("    生成的分区城市风格: " + zoneStyles);
        check(zoneStyles.size() == 1, "全是多格建筑的子分区照样生成 caf_zone_inner_* 城市风格 [实际 "
                + zoneStyles.size() + " 个]");
        if (!zoneStyles.isEmpty()) {
            Set<String> zoneMultis = new HashSet<>();
            JsonObject zs = JsonParser.parseString(out2.files.get(zoneStyles.get(0))).getAsJsonObject()
                    .getAsJsonObject("selectors");
            for (JsonElement e : zs.getAsJsonArray("multibuildings")) {
                String v = e.getAsJsonObject().get("value").getAsString();
                int colon = v.indexOf(':');
                zoneMultis.add(colon >= 0 ? v.substring(colon + 1) : v);
            }
            check(zoneMultis.contains("dummy_multi"), "分区风格的 multibuildings 含该多格建筑");
        }

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + passed + " 项, 失败 " + failed + " 项");
        if (failed == 0) {
            System.out.println("ZONING-TEST-ALL-PASS");
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    /** 读某个 citystyle 里 selectors.&lt;字段&gt; 的值列表(去掉命名空间前缀)。 */
    private static Set<String> idsOf(LcAssetGenerator.Output out, String citystyleFile, String field) {
        Set<String> ids = new HashSet<>();
        String json = out.files.get("lostcities/lostcities/citystyles/" + citystyleFile);
        if (json == null) {
            return ids;
        }
        JsonObject selectors = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("selectors");
        if (selectors == null) {
            return ids;
        }
        JsonArray arr = selectors.getAsJsonArray(field);
        if (arr == null) {
            return ids;
        }
        for (JsonElement e : arr) {
            String v = e.getAsJsonObject().get("value").getAsString();
            int colon = v.indexOf(':');
            ids.add(colon >= 0 ? v.substring(colon + 1) : v);
        }
        return ids;
    }
}
