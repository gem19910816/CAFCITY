import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 跨文件引用完整性校验 —— 补上 DecodeAudit 没覆盖的那一半。
 *
 * <p>{@code DecodeAudit} 只证明"每个文件单独能被失落城市的 codec 解析",
 * 证明不了"文件之间的引用能对上"。而悬空引用恰恰是最典型的运行期故障:
 * 建筑引用了不存在的 part、多格矩阵里的格子在 buildings 里没有、城市风格里
 * 引用了不存在的建筑 —— 任何一种都会让城市生成失败或直接抛异常。
 *
 * <p>本工具按失落城市<b>真实的解析规则</b>来判定引用是否有效:
 * <pre>
 *   DataTools.fromName(name):                      // RegistryAssetRegistry 就是这么查的
 *     name.contains(":") ? ResourceLocation.parse(name)
 *                        : ResourceLocation.fromNamespaceAndPath("lostcities", name)
 * </pre>
 * 资源 id 从文件路径推出: {@code data/<命名空间>/lostcities/<类型>/<路径>.json} ⇒ {@code <命名空间>:<路径>}。
 * 校验时同时索引<b>本数据包</b>和<b>失落城市 jar 自带的资产</b>, 所以城市风格引用的原版
 * bridge/parks/fronts 不会被误判。
 *
 * <p>用法: java RefAudit &lt;数据包目录&gt; &lt;失落城市jar&gt;
 */
public final class RefAudit {

    private static final String LC = "lostcities";
    private static final String[] TYPES = {
            "parts", "buildings", "multibuildings", "palettes", "citystyles",
            "conditions", "styles", "scattered", "stuff", "worldstyles", "variants",
    };

    /** 类型 -> (资源id -> JSON 对象) */
    private static final Map<String, Map<String, JsonObject>> assets = new LinkedHashMap<>();

    private static int pass;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> warnings = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path packDir = Path.of(args.length > 0 ? args[0] : "build/smoke-r2-inj").toAbsolutePath();
        Path lcJar = args.length > 1 ? Path.of(args[1]).toAbsolutePath() : null;

        System.out.println("=== 跨文件引用完整性校验 ===");
        System.out.println("数据包: " + packDir);
        System.out.println("失落城市: " + lcJar);
        System.out.println();

        for (String t : TYPES) {
            assets.put(t, new TreeMap<>());
        }

        Path dataDir = packDir.resolve("data");
        if (!Files.isDirectory(dataDir)) {
            System.out.println("找不到 data 目录: " + dataDir);
            System.exit(2);
        }
        indexFromDirectory(dataDir);
        if (lcJar != null && Files.isRegularFile(lcJar)) {
            indexFromZip(lcJar);
        }

        System.out.println("--- 索引到的资源 ---");
        for (String t : TYPES) {
            Map<String, JsonObject> m = assets.get(t);
            if (!m.isEmpty()) {
                System.out.println("  " + pad(t, 16) + m.size());
            }
        }
        System.out.println();

        // 只校验本模组自己产出的东西(城市风格因为被整体复制, 里面大部分引用是原版的)
        List<String> myParts = byNamespace("parts", "caf_lc_customcity");
        List<String> myBuildings = byNamespace("buildings", "caf_lc_customcity");
        List<String> myMultis = byNamespace("multibuildings", "caf_lc_customcity");
        List<String> myPalettes = byNamespace("palettes", "caf_lc_customcity");
        List<String> myStyles = byNamespace("citystyles", "lostcities");

        System.out.println("--- 1. part 引用与切片数 ---");
        checkParts(myParts, myPalettes);
        System.out.println();
        System.out.println("--- 2. building 引用与楼层数 ---");
        checkBuildings(myBuildings, myPalettes);
        System.out.println();
        System.out.println("--- 3. multibuilding 矩阵 ---");
        checkMultis(myMultis);
        System.out.println();
        System.out.println("--- 4. citystyle 选择器 (只查我替换过的两个字段) ---");
        checkStyles(myStyles);
        System.out.println();
        System.out.println("--- 5. 城市风格继承链 ---");
        checkInherit(myStyles);

        System.out.println();
        System.out.println("================================");
        for (String w : warnings) {
            System.out.println("[提示] " + w);
        }
        System.out.println("通过 " + pass + " 项, 失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("[FAIL] " + f);
        }
        System.out.println(failures.isEmpty() ? "REF-AUDIT-ALL-PASS" : "REF-AUDIT-HAS-FAILURES");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ==================================================================

    private static void checkParts(List<String> ids, List<String> palettes) {
        for (String id : ids) {
            JsonObject o = assets.get("parts").get(id);
            String ref = str(o, "refpalette");
            if (ref != null) {
                check(palettes.contains(fromName(ref)), id + " 的 refpalette 指向存在的调色板: " + ref);
            } else if (!o.has("palette")) {
                fail(id + " 既没有 refpalette 也没有内联 palette");
            } else {
                // 内联 palette 必须是 PaletteRE 结构 {"palette":[...]}
                JsonElement p = o.get("palette");
                if (!p.isJsonObject() || !p.getAsJsonObject().has("palette")) {
                    fail(id + " 的内联 palette 形状不对, 应该是 {\"palette\":[...]} 两层嵌套");
                } else {
                    check(true, id + " 有合法的内联 palette");
                }
            }
            int xs = num(o, "xsize", -1);
            int zs = num(o, "zsize", -1);
            check(xs > 0 && zs > 0 && xs <= 16 && zs <= 16,
                    id + " 的 xsize/zsize 合法且不超过 16 (" + xs + "x" + zs + ")");
        }
    }

    private static void checkBuildings(List<String> ids, List<String> palettes) {
        for (String id : ids) {
            JsonObject o = assets.get("buildings").get(id);
            String ref = str(o, "refpalette");
            if (ref != null) {
                check(palettes.contains(fromName(ref)), id + " 的 refpalette 指向存在的调色板");
            }

            int minF = num(o, "minfloors", -1);
            int maxF = num(o, "maxfloors", -1);
            check(minF >= 1 && minF == maxF,
                    id + " 的 minfloors==maxfloors 且 >=1 (overrideFloors 下 LC 只用这两个值)");

            JsonArray parts = o.has("parts") ? o.getAsJsonArray("parts") : null;
            if (parts == null || parts.isEmpty()) {
                fail(id + " 的 parts 为空");
                continue;
            }

            int floorParts = 0;
            int tops = 0;
            for (JsonElement pe : parts) {
                JsonObject p = pe.getAsJsonObject();
                String pname = str(p, "part");
                boolean isTop = bool(p, "top");
                boolean isCellar = bool(p, "cellar");
                if (pname == null) {
                    fail(id + " 有 parts 条目缺少 part 字段");
                    continue;
                }
                String pid = fromName(pname);
                if (!assets.get("parts").containsKey(pid)) {
                    fail(id + " 引用了不存在的 part: " + pname);
                    continue;
                }
                JsonObject part = assets.get("parts").get(pid);
                int slices = part.has("slices") ? part.getAsJsonArray("slices").size() : -1;

                if (isTop) {
                    tops++;
                    check(slices >= 1, id + " 的顶层 part " + pname + " 有至少 1 层 (实际 " + slices + ")");
                } else if (isCellar) {
                    check(slices >= 1, id + " 的地下室 part " + pname + " 有至少 1 层");
                } else {
                    floorParts++;
                    check(slices == 6, id + " 的楼层 part " + pname + " 正好 6 层 (实际 " + slices + ")");
                }
            }
            check(floorParts == minF,
                    id + " 的楼层 part 数(" + floorParts + ") 等于 minfloors(" + minF + ")");
            if (tops == 0) {
                warnings.add(id + " 没有任何 top:true 的 part");
            }
        }
    }

    private static void checkMultis(List<String> ids) {
        for (String id : ids) {
            JsonObject o = assets.get("multibuildings").get(id);
            int dimx = num(o, "dimx", -1);
            int dimz = num(o, "dimz", -1);
            JsonArray rows = o.has("buildings") ? o.getAsJsonArray("buildings") : null;
            if (rows == null) {
                fail(id + " 缺少 buildings");
                continue;
            }
            check(rows.size() == dimx,
                    id + " 的 buildings 外层长度(" + rows.size() + ") 等于 dimx(" + dimx + ")");

            Set<String> cells = new LinkedHashSet<>();
            boolean allOk = true;
            for (JsonElement re : rows) {
                JsonArray row = re.getAsJsonArray();
                if (row.size() != dimz) {
                    fail(id + " 有一行长度 " + row.size() + " != dimz(" + dimz + ")");
                    allOk = false;
                }
                for (JsonElement ce : row) {
                    String cid = fromName(ce.getAsString());
                    cells.add(cid);
                    if (!assets.get("buildings").containsKey(cid)) {
                        fail(id + " 的矩阵引用了不存在的建筑: " + ce.getAsString());
                        allOk = false;
                    }
                }
            }
            if (allOk) {
                check(true, id + " 的 " + dimx + "x" + dimz + " 矩阵全部指向存在的建筑");
            }

            // BuildingInfo 用左上格的 palette / 楼层数 / 地下室数, 所以各格必须一致
            String pal0 = null;
            int minF0 = -1;
            int maxF0 = -1;
            int minC0 = -1;
            int maxC0 = -1;
            boolean consistent = true;
            for (String cid : cells) {
                JsonObject b = assets.get("buildings").get(cid);
                if (b == null) {
                    continue;
                }
                String pal = str(b, "refpalette");
                int mnF = num(b, "minfloors", -1);
                int mxF = num(b, "maxfloors", -1);
                int mnC = num(b, "mincellars", -1);
                int mxC = num(b, "maxcellars", -1);
                if (pal0 == null) {
                    pal0 = pal;
                    minF0 = mnF;
                    maxF0 = mxF;
                    minC0 = mnC;
                    maxC0 = mxC;
                } else if (!eq(pal0, pal) || minF0 != mnF || maxF0 != mxF || minC0 != mnC || maxC0 != mxC) {
                    consistent = false;
                    fail(id + " 的格子 " + cid + " 与左上格的调色板/楼层/地下室不一致");
                }
            }
            if (consistent) {
                check(true, id + " 各格的调色板与楼层数完全一致");
            }
        }
    }

    /**
     * 城市风格的选择器必须按"有效集合"来查, 因为失落城市在
     * {@code CityStyle.init()} 里是 {@code buildingSelector.addAll(父级)} ——
     * 也就是沿继承链<b>累积</b>, 不是替换。子级写了什么, 父级的还会被加进来。
     */
    private static void checkStyles(List<String> ids) {
        for (String id : ids) {
            List<String> effBuildings = new ArrayList<>();
            List<String> effMultis = new ArrayList<>();
            List<String> chain = new ArrayList<>();

            String cur = id;
            Set<String> seen = new LinkedHashSet<>();
            while (cur != null && seen.add(cur) && chain.size() < 20) {
                JsonObject o = assets.get("citystyles").get(cur);
                if (o == null) {
                    break;
                }
                chain.add(cur);
                JsonObject sel = o.has("selectors") ? o.getAsJsonObject("selectors") : null;
                if (sel != null) {
                    collect(sel, "buildings", effBuildings);
                    collect(sel, "multibuildings", effMultis);
                }
                String inh = str(o, "inherit");
                cur = inh == null ? null : fromName(inh);
            }

            System.out.println("  " + id + "   继承链: " + String.join(" <- ", chain));
            System.out.println("      有效建筑 " + effBuildings.size() + " 项, 有效多格 " + effMultis.size() + " 项");

            // 引用必须都能解析
            int badRefs = 0;
            for (String v : effBuildings) {
                if (!assets.get("buildings").containsKey(fromName(v))) {
                    failures.add(id + " 有效集里的建筑不存在: " + v);
                    badRefs++;
                }
            }
            for (String v : effMultis) {
                if (!assets.get("multibuildings").containsKey(fromName(v))) {
                    failures.add(id + " 有效集里的多格建筑不存在: " + v);
                    badRefs++;
                }
            }
            check(badRefs == 0, id + " 有效集合里的引用全部存在");

            // 核心目标: 有效集合里不能残留原版(非本模组)建筑
            if (effBuildings.isEmpty()) {
                warnings.add(id + " 的有效建筑集合为空"
                        + (chain.size() > 1 ? "" : " (它是继承链的根, 只提供配置, 通常不会直接当城市风格用)"));
            } else {
                List<String> foreign = new ArrayList<>();
                for (String v : effBuildings) {
                    if (!v.startsWith("caf_lc_customcity:")) {
                        foreign.add(v);
                    }
                }
                check(foreign.isEmpty(),
                        id + " 的有效集合里只剩本模组的建筑"
                                + (foreign.isEmpty() ? "" : ", 残留原版: " + foreign));
            }
            for (String v : effMultis) {
                if (!v.startsWith("caf_lc_customcity:")) {
                    failures.add(id + " 的有效多格集合里残留原版建筑: " + v);
                }
            }
        }
    }

    private static void collect(JsonObject sel, String key, List<String> into) {
        if (!sel.has(key)) {
            return;
        }
        for (JsonElement e : sel.getAsJsonArray(key)) {
            if (e.isJsonObject()) {
                String v = str(e.getAsJsonObject(), "value");
                if (v != null) {
                    into.add(v);
                }
            }
        }
    }

    private static void checkInherit(List<String> ids) {
        for (String id : ids) {
            String cur = id;
            Set<String> seen = new LinkedHashSet<>();
            int hops = 0;
            while (true) {
                JsonObject o = assets.get("citystyles").get(cur);
                if (o == null) {
                    break;
                }
                String inh = str(o, "inherit");
                if (inh == null) {
                    break;
                }
                String inhId = fromName(inh);
                if (!assets.get("citystyles").containsKey(inhId)) {
                    fail(cur + " 的 inherit 指向不存在的城市风格: " + inh);
                    break;
                }
                if (!seen.add(inhId)) {
                    fail("城市风格继承出现环: " + seen);
                    break;
                }
                cur = inhId;
                hops++;
                if (hops > 10) {
                    fail("城市风格继承链过长(疑似成环)");
                    break;
                }
            }
            check(true, id + " 的继承链可解析, 向上 " + hops + " 层");
        }
    }

    // ==================================================================
    // 索引

    private static void indexFromDirectory(Path dataDir) throws Exception {
        try (Stream<Path> s = Files.walk(dataDir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String rel = dataDir.relativize(p).toString().replace('\\', '/');
                put(rel, () -> JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)));
            });
        }
    }

    private static void indexFromZip(Path jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zf.entries())) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (!name.startsWith("data/") || !name.endsWith(".json")) {
                    continue;
                }
                try (Reader r = new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8)) {
                    JsonElement json = JsonParser.parseReader(r);
                    if (json.isJsonObject()) {
                        put(name.substring("data/".length()), () -> json);
                    }
                } catch (Exception ignored) {
                    // 不是 JSON 或读不动就跳过
                }
            }
        }
    }

    private interface Supplier {
        JsonElement get() throws Exception;
    }

    private static void put(String rel, Supplier json) {
        // data/<ns>/lostcities/<type>/<path>.json
        String[] seg = rel.split("/");
        if (seg.length < 4 || !LC.equals(seg[1])) {
            return;
        }
        Map<String, JsonObject> bucket = assets.get(seg[2]);
        if (bucket == null) {
            return;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 3; i < seg.length; i++) {
            if (i > 3) {
                path.append('/');
            }
            path.append(seg[i]);
        }
        String name = path.toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        try {
            JsonElement j = json.get();
            if (j != null && j.isJsonObject()) {
                bucket.putIfAbsent(seg[0] + ":" + name, j.getAsJsonObject());
            }
        } catch (Exception ignored) {
            // 忽略坏 JSON, DecodeAudit 会专门报
        }
    }

    /** 与失落城市 DataTools.fromName 完全一致。 */
    static String fromName(String name) {
        return name.contains(":") ? name : LC + ":" + name;
    }

    private static List<String> byNamespace(String type, String ns) {
        List<String> out = new ArrayList<>();
        for (String k : assets.get(type).keySet()) {
            if (k.startsWith(ns + ":")) {
                out.add(k);
            }
        }
        return out;
    }

    // ==================================================================

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static int num(JsonObject o, String k, int dflt) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? dflt : e.getAsInt();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) {
            b.append(' ');
        }
        return b.toString();
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
        } else {
            failures.add(what);
        }
    }

    private static void fail(String what) {
        failures.add(what);
    }
}
