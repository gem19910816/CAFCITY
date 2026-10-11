package com.caf.lccustomcity;

import net.neoforged.bus.api.IEventBus;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 整条启动流程的离线冒烟测试。
 *
 * <p>{@link LcCustomCityMod#startup} 原本是构造器里的私有方法, 而且路径来自 {@code FMLPaths},
 * 所以<b>只有玩家进游戏才会被执行</b> —— 出了问题只能靠玩家反馈。现在它接受 {@code gameDir} 参数,
 * 于是这里能用一个沙盒目录把整条流程真跑一遍:
 *
 * <pre>
 *   config/失落城市建筑/ 扫描 → 转换 → 覆盖 citystyle → 落盘 → 注册数据包 → 打包 → 注入存档
 * </pre>
 *
 * <p>用法: java com.caf.lccustomcity.StartupSmokeTest &lt;沙盒目录&gt; &lt;失落城市jar&gt; [建筑目录]
 */
public final class StartupSmokeTest {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args.length > 0 ? args[0] : "build/smoke-startup").toAbsolutePath();
        Path lcJar = args.length > 1 ? Path.of(args[1]).toAbsolutePath() : null;
        Path buildings = args.length > 2 ? Path.of(args[2]).toAbsolutePath() : null;

        deleteRecursively(sandbox);
        Files.createDirectories(sandbox);

        System.out.println("=== 启动流程离线冒烟测试 ===");
        System.out.println("沙盒: " + sandbox);
        System.out.println();

        // ---------- 准备一个像模像样的实例目录 ----------
        Path modsDir = sandbox.resolve("mods");
        Path savesDir = sandbox.resolve("saves");
        Path cfgDir = sandbox.resolve("config").resolve(LcCustomCityMod.BUILDING_DIR_NAME);
        Path bldDir = cfgDir.resolve("示例建筑");
        Files.createDirectories(modsDir);
        Files.createDirectories(bldDir);
        if (lcJar != null && Files.isRegularFile(lcJar)) {
            Files.copy(lcJar, modsDir.resolve(lcJar.getFileName()));
        }
        Path src = buildings != null && Files.isDirectory(buildings)
                ? buildings : null;
        if (src != null) {
            try (Stream<Path> s = Files.walk(src)) {
                List<Path> schems = new ArrayList<>();
                s.filter(Files::isRegularFile)
                        .filter(p -> !src.relativize(p).startsWith(LcCustomCityMod.GENERATED_PACK_NAME))
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return name.endsWith(".schem") || name.endsWith(".nbt");
                        }).forEach(schems::add);
                for (Path p : schems) {
                    Path target = cfgDir.resolve(src.relativize(p));
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        // 两个存档: 一个正常, 一个在忽略名单里
        Path worldA = savesDir.resolve("测试存档甲");
        Path worldB = savesDir.resolve(LcCustomCityMod.DEFAULT_IGNORED_WORLD);
        Files.createDirectories(worldA);
        Files.createDirectories(worldB);
        Files.writeString(worldA.resolve("level.dat"), "dummy", StandardCharsets.UTF_8);
        Files.writeString(worldB.resolve("level.dat"), "dummy", StandardCharsets.UTF_8);

        // ---------- 跑真正的启动流程 ----------
        System.out.println("--- 调用 LcCustomCityMod.startup(fakeBus, sandbox) ---");
        AtomicReference<Consumer<Object>> captured = new AtomicReference<>();
        IEventBus bus = fakeBus(captured);
        LcCustomCityMod.startup(bus, sandbox);
        // Preserve the top-level zone folder: inserting an extra sample folder changes
        // asset IDs and incorrectly turns all outer-city files into inner-city files.
        if (src != null && Files.isDirectory(src.resolve("\u5916\u57ce"))) {
            try (var outerFiles = Files.walk(src.resolve("\u5916\u57ce"))) {
                if (outerFiles.anyMatch(p -> Files.isRegularFile(p) &&
                        (p.toString().endsWith(".nbt") || p.toString().endsWith(".schem")))) {
                    check(com.caf.lccustomcity.config.LayoutCatalog.current().buildings().stream()
                            .anyMatch(com.caf.lccustomcity.config.LayoutCatalog.Building::outer),
                            "startup preserves outer-city routing in the footprint catalog");
                }
            }
        }

        // ---------- 1. 配置文件 ----------
        System.out.println();
        System.out.println("--- 1. 首次运行写出的配置文件 ---");
        check(Files.isRegularFile(cfgDir.resolve("说明.txt")), "写了 说明.txt");
        Path ignore = cfgDir.resolve(LcCustomCityMod.IGNORE_LIST_FILE);
        check(Files.isRegularFile(ignore), "写了 " + LcCustomCityMod.IGNORE_LIST_FILE);

        // --- 平坦城市配置档(自动安装) ---
        Path profile = sandbox.resolve("config").resolve("lostcities").resolve("profiles")
                .resolve(LcCustomCityMod.FLAT_PROFILE_NAME + ".json");
        check(Files.isRegularFile(profile), "安装了平坦城市配置档 " + profile.getFileName());
        if (Files.isRegularFile(profile)) {
            String pj = Files.readString(profile, StandardCharsets.UTF_8);
            check(pj.contains("\"cityLevel0Height\": 384"),
                    "配置档把城市层级锁死在 0 (cityLevel0Height=384)");
            check(pj.contains("\"groundLevel\": 71"), "配置档保留 groundLevel=71");
            check(!pj.contains("__readonly__"), "配置档没有内置档那个只读警告字段");
            try {
                JsonParser.parseString(pj);
                check(true, "配置档是合法 JSON");
            } catch (RuntimeException e) {
                check(false, "配置档不是合法 JSON: " + e);
            }
        }
        java.util.Set<String> ig = LcCustomCityMod.loadIgnoreList(cfgDir);
        check(ig.size() == 1 && ig.contains(LcCustomCityMod.DEFAULT_IGNORED_WORLD.toLowerCase()),
                "名单里默认包含源地图, 且解析正确 -> " + ig);

        // ---------- 2. 新的数据包目录结构 ----------
        System.out.println();
        System.out.println("--- 2. 生成的建筑包目录结构 ---");
        Path packRoot = cfgDir.resolve(LcCustomCityMod.GENERATED_PACK_NAME);
        Path packDir = packRoot.resolve(LcCustomCityMod.INNER_PACK_NAME);
        check(Files.isDirectory(packRoot), "父目录 " + LcCustomCityMod.GENERATED_PACK_NAME + " 存在");
        check(Files.isDirectory(packDir), "数据包目录 " + LcCustomCityMod.INNER_PACK_NAME + " 存在");
        Path mcmeta = packDir.resolve("pack.mcmeta");
        check(Files.isRegularFile(mcmeta), "pack.mcmeta 存在");
        if (Files.isRegularFile(mcmeta)) {
            String mc = Files.readString(mcmeta, StandardCharsets.UTF_8);
            check(mc.contains("\"pack_format\": 48"), "pack.mcmeta 里 pack_format 是 48 (1.21.1 数据包格式)");
            check(mc.contains("失落城市"), "pack.mcmeta 描述里的中文是完整 UTF-8(没有乱码)");
        }
        check(Files.isDirectory(packDir.resolve("data").resolve("caf_lc_customcity").resolve("lostcities")),
                "本模组资产在 data/caf_lc_customcity 下");
        check(Files.isDirectory(packDir.resolve("data").resolve("lostcities").resolve("lostcities")),
                "citystyle 覆盖在 data/lostcities 下");
        check(!Files.exists(packDir.resolve("data").resolve("data")), "没有 data/data 这个错误路径");

        // ---------- 3. citystyle 确实被覆盖 ----------
        System.out.println();
        System.out.println("--- 3. citystyle 覆盖内容 ---");
        Path csCommon = packDir.resolve("data/lostcities/lostcities/citystyles/citystyle_common.json");
        Path csBorder = packDir.resolve("data/lostcities/lostcities/citystyles/citystyle_border.json");
        check(Files.isRegularFile(csCommon), "citystyle_common 被覆盖");
        check(Files.isRegularFile(csBorder), "citystyle_border 被覆盖");

        // 从失落城市 jar 里读出原版, 做"我们确实改了该改的 / 确实没动不该动的"对照。
        JsonObject origCommon = readCityStyleFromJar(lcJar, "citystyle_common");
        JsonObject origBorder = readCityStyleFromJar(lcJar, "citystyle_border");

        if (Files.isRegularFile(csCommon)) {
            JsonObject sel = readSelectors(csCommon);
            String t = Files.readString(csCommon, StandardCharsets.UTF_8);
            check(t.contains("caf_lc_customcity:"), "覆盖里引用了本模组的建筑");
            check(!t.contains("\"building1\""), "覆盖里不再有原版 building1");
            check(t.contains("streetblocks"), "streetblocks 等街道设置被原样保留");

            checkSelectorAllMine(sel, "buildings");
            checkSelectorAllMine(sel, "multibuildings");
            // 用户第 12 轮的决定: "去掉门脸和公园, 其他保留"
            checkCleared(sel, "fronts");
            checkCleared(sel, "parks");
            checkSelectorSameAsVanilla(sel, origCommon, "bridges");
            checkSelectorSameAsVanilla(sel, origCommon, "stairs");
            checkSelectorSameAsVanilla(sel, origCommon, "fountains");
            checkSelectorSameAsVanilla(sel, origCommon, "raildungeons");
        }
        if (Files.isRegularFile(csBorder)) {
            JsonObject sel = readSelectors(csBorder);
            checkSelectorAllMine(sel, "buildings");
            checkCleared(sel, "fronts");
            checkCleared(sel, "parks");
            checkSelectorSameAsVanilla(sel, origBorder, "bridges");
            checkSelectorSameAsVanilla(sel, origBorder, "stairs");
        }
        // 原版本来就有门脸和公园 —— 不确认这一点, "我们清空了"就没有意义。
        // 注意: sizeOf 要的是 selectors 对象, 不是 citystyle 根对象。
        if (origCommon != null && origBorder != null) {
            JsonObject os = origCommon.getAsJsonObject("selectors");
            JsonObject ob = origBorder.getAsJsonObject("selectors");
            check(sizeOf(os, "fronts") > 0,
                    "对照: 原版 citystyle_common 确实有 fronts (" + sizeOf(os, "fronts") + " 项)");
            check(sizeOf(os, "parks") > 0,
                    "对照: 原版 citystyle_common 确实有 parks (" + sizeOf(os, "parks") + " 项)");
            check(sizeOf(ob, "fronts") > 0,
                    "对照: 原版 citystyle_border 也确实有 fronts (" + sizeOf(ob, "fronts") + " 项)");
        } else {
            // 读不到原版就明确报出来, 而不是让下面几条悄悄退化成"通过"
            check(false, "读不出失落城市 jar 里的原版 citystyle, 对照无法进行 (lcJar=" + lcJar + ")");
        }

        // ---------- 3b. 建筑文件里的关键标志位 ----------
        System.out.println();
        System.out.println("--- 3b. 建筑文件里的关键标志位 ---");
        Path bldgDir = packDir.resolve("data/caf_lc_customcity/lostcities/buildings");
        List<Path> bldgs = new ArrayList<>();
        if (Files.isDirectory(bldgDir)) {
            try (Stream<Path> s = Files.list(bldgDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(bldgs::add);
            }
        }
        check(!bldgs.isEmpty(), "有生成的建筑文件 (实际 " + bldgs.size() + ")");
        int noDoors = 0;
        int overridden = 0;
        for (Path b : bldgs) {
            JsonObject o = JsonParser.parseString(Files.readString(b, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("allowDoors") && !o.get("allowDoors").getAsBoolean()) {
                noDoors++;
            }
            if (o.has("overrideFloors") && o.get("overrideFloors").getAsBoolean()
                    && o.get("minfloors").getAsInt() == o.get("maxfloors").getAsInt()) {
                overridden++;
            }
        }
        // 第 13 轮的决定: 失落城市不许在建筑墙上凿门 —— 它只往空气里写, 会改掉导出的建筑
        check(noDoors == bldgs.size(),
                "所有建筑都是 allowDoors=false (不会在墙上凿门): " + noDoors + "/" + bldgs.size());
        check(overridden == bldgs.size(),
                "所有建筑都是 overrideFloors=true 且 minfloors==maxfloors: " + overridden + "/" + bldgs.size());

        // ---------- 4. mods 里的建筑包 ----------
        System.out.println();
        System.out.println("--- 4. 兜底建筑包 ---");
        List<Path> jars1 = LcCustomCityMod.findDataJars(modsDir);
        check(jars1.size() == 1, "写出 1 个建筑包 (实际 " + jars1.size() + ")");
        if (!jars1.isEmpty()) {
            String jn = jars1.get(0).getFileName().toString();
            check(jn.startsWith(LcCustomCityMod.DATA_JAR_PREFIX + "0001-"), "编号从 0001 开始 -> " + jn);
            check(jarHas(jars1.get(0), "META-INF/neoforge.mods.toml"), "jar 里有 neoforge.mods.toml");
            check(jarHas(jars1.get(0), "pack.mcmeta"), "jar 里有 pack.mcmeta");
            check(jarHas(jars1.get(0),
                    "data/lostcities/lostcities/citystyles/citystyle_common.json"), "jar 里有 citystyle 覆盖");
            String toml = readJarEntry(jars1.get(0), "META-INF/neoforge.mods.toml");
            // 必须检查同一依赖块内 —— 光看整份 toml 里同时出现这两个串是不够的
            check(afterInSameBlock(toml, "modId=\"lostcities\"", "ordering=\"AFTER\""),
                    "toml 里 lostcities 那个依赖块声明了 ordering=\"AFTER\"");
            String neoToml = toml == null ? null : toml;
            check(!afterInSameBlock(neoToml, "modId=\"neoforge\"", "ordering=\"AFTER\""),
                    "neoforge 那个依赖块没有写成 AFTER(是 NONE, 正确)");
        }

        // ---------- 5. 存档注入 ----------
        System.out.println();
        System.out.println("--- 5. 存档注入与忽略名单 ---");
        check(Files.isRegularFile(worldA.resolve("datapacks/caf_lc_customcity/pack.mcmeta")),
                "普通存档被注入了数据包");
        check(Files.isRegularFile(worldA.resolve("datapacks/caf_lc_customcity/" + LcCustomCityMod.HASH_MARKER)),
                "普通存档里有哈希标记");
        check(!Files.exists(worldB.resolve("datapacks/caf_lc_customcity")),
                "忽略名单里的源地图没有被注入");

        // ---------- 6. 幂等 ----------
        System.out.println();
        System.out.println("--- 6. 再跑一次应当什么都不做 ---");

        // 说明文件的版本标记必须在第一行且【顶格】: readReadmeVersion 是 startsWith 判断,
        // Java 文本块的共同缩进一旦没剥干净, 它就永远认不出来, 结果每次启动都重写这个文件。
        Path readme = cfgDir.resolve("说明.txt");
        List<String> readmeLines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        check(!readmeLines.isEmpty() && readmeLines.get(0).startsWith("# CAF-LC 说明版本: "),
                "说明.txt 第一行是顶格的版本标记 -> "
                        + (readmeLines.isEmpty() ? "(空文件)" : "[" + readmeLines.get(0) + "]"));
        // 标记行里的数字必须和源码常量一致, 否则同样会一直重写
        check(!readmeLines.isEmpty() && readmeLines.get(0).endsWith(" " + LcCustomCityMod.README_VERSION),
                "说明.txt 的版本号与 README_VERSION 一致 -> "
                        + (readmeLines.isEmpty() ? "(空文件)" : "[" + readmeLines.get(0) + "]"));

        // 模拟"玩家自己在说明文件里加了笔记": 版本相同时必须一个字都不动。
        // 这是第 19 轮改这套机制的全部意义 —— 原来的写法是"文件存在就永不更新",
        // 新写法要同时做到"能更新"和"绝不覆盖玩家自己写的东西"。
        String myNote = "# 这是玩家自己的笔记, 模组不许删";
        Files.writeString(readme, Files.readString(readme, StandardCharsets.UTF_8)
                + myNote + System.lineSeparator(), StandardCharsets.UTF_8);

        Path markerBefore = worldA.resolve("datapacks/caf_lc_customcity/" + LcCustomCityMod.HASH_MARKER);
        String hashBefore = Files.readString(markerBefore, StandardCharsets.UTF_8);
        LcCustomCityMod.startup(fakeBus(new AtomicReference<>()), sandbox);
        List<Path> jars2 = LcCustomCityMod.findDataJars(modsDir);
        check(jars2.size() == 1, "第二次没有写出多余的建筑包 (实际 " + jars2.size() + ")");
        check(jars2.size() == 1 && jars2.get(0).getFileName().equals(jars1.get(0).getFileName()),
                "还是同一个建筑包");
        check(Files.readString(markerBefore, StandardCharsets.UTF_8).equals(hashBefore),
                "存档里的哈希标记没变(注入被跳过)");
        check(Files.readString(readme, StandardCharsets.UTF_8).contains(myNote),
                "第二次启动没有覆盖说明文件(玩家自己加的笔记还在)");
        check(!Files.exists(cfgDir.resolve("说明-新版.txt")),
                "版本相同, 不该另外写出 说明-新版.txt");

        // ---------- 7. 没有建筑文件时必须不动任何东西 ----------
        System.out.println();
        System.out.println("--- 7. 建筑目录为空时应当直接返回 ---");
        Path emptySandbox = sandbox.resolve("空实例");
        Files.createDirectories(emptySandbox.resolve("mods"));
        Files.createDirectories(emptySandbox.resolve("saves"));
        Path emptyCfg = emptySandbox.resolve("config").resolve(LcCustomCityMod.BUILDING_DIR_NAME);
        Files.createDirectories(emptyCfg);
        LcCustomCityMod.startup(fakeBus(new AtomicReference<>()), emptySandbox);
        check(!Files.exists(emptyCfg.resolve(LcCustomCityMod.GENERATED_PACK_NAME)),
                "没有生成任何数据包");
        check(LcCustomCityMod.findDataJars(emptySandbox.resolve("mods")).isEmpty(),
                "没有写出建筑包");
        check(Files.isRegularFile(emptyCfg.resolve("说明.txt")), "但仍然写了说明文件方便玩家上手");

        // ---------- 8. 包目录直接喂给 codec/引用校验 ----------
        System.out.println();
        System.out.println("--- 8. 沙盒里真正生成的数据包路径(给后续审计用) ---");
        System.out.println("    " + packDir);

        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + pass + " 项, 失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("[FAIL] " + f);
        }
        System.out.println(failures.isEmpty() ? "STARTUP-TEST-ALL-PASS" : "STARTUP-TEST-HAS-FAILURES");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ==================================================================

    private static IEventBus fakeBus(AtomicReference<Consumer<Object>> sink) {
        return (IEventBus) Proxy.newProxyInstance(
                StartupSmokeTest.class.getClassLoader(),
                new Class<?>[] {IEventBus.class},
                (proxy, method, args) -> {
                    if ("addListener".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Consumer<Object> c = (Consumer<Object>) args[args.length - 1];
                        sink.set(c);
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "FakeEventBus";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    /**
     * 在 {@code text} 里找到 {@code anchor} 之后, 检查 {@code needle} 是否出现在<b>同一个依赖块内</b>。
     *
     * <p>依赖块从 anchor 开始, 到下一个 {@code [[} 为止。只检查整份文件里"两个串都出现过"是不够的 ——
     * {@code ordering="AFTER"} 完全可能挂在别的依赖上。
     */
    private static boolean afterInSameBlock(String text, String anchor, String needle) {
        if (text == null) {
            return false;
        }
        int a = text.indexOf(anchor);
        if (a < 0) {
            return false;
        }
        int end = text.indexOf("[[", a + anchor.length());
        String block = end < 0 ? text.substring(a) : text.substring(a, end);
        return block.contains(needle);
    }

    private static boolean jarHas(Path jar, String entry) {
        return readJarEntry(jar, entry) != null;
    }

    private static String readJarEntry(Path jar, String entry) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry e = zf.getEntry(entry);
            if (e == null) {
                return null;
            }
            try (java.io.InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            return null;
        }
    }

    // ---------- 选择器断言辅助 ----------

    private static JsonObject readSelectors(Path cityStyleFile) {
        try {
            return JsonParser.parseString(Files.readString(cityStyleFile, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("selectors");
        } catch (Exception e) {
            return null;
        }
    }

    private static int sizeOf(JsonObject selectors, String key) {
        if (selectors == null || !selectors.has(key) || !selectors.get(key).isJsonArray()) {
            return 0;
        }
        return selectors.getAsJsonArray(key).size();
    }

    /** 选择器里的每一项都必须是本模组的资产, 而且不能为空。 */
    private static void checkSelectorAllMine(JsonObject sel, String key) {
        int n = sizeOf(sel, key);
        if (n == 0) {
            check(false, "selectors." + key + " 不该为空");
            return;
        }
        int mine = 0;
        for (JsonElement e : sel.getAsJsonArray(key)) {
            if (e.isJsonObject() && e.getAsJsonObject().has("value")
                    && e.getAsJsonObject().get("value").getAsString().startsWith("caf_lc_customcity:")) {
                mine++;
            }
        }
        check(mine == n, "selectors." + key + " 全是本模组的建筑 (" + mine + "/" + n + ")");
    }

    /** 必须被清空成空数组。 */
    private static void checkCleared(JsonObject sel, String key) {
        check(sel != null && sel.has(key) && sel.get(key).isJsonArray() && sel.getAsJsonArray(key).isEmpty(),
                "selectors." + key + " 已被清空成空数组");
    }

    /**
     * 必须与原版语义一致。比"值的集合"而不是序列化文本 —— key 顺序或数字写法
     * 的细微差别不该让断言误报, 而"哪几个资产还在"才是真正要守的东西。
     */
    private static void checkSelectorSameAsVanilla(JsonObject sel, JsonObject orig, String key) {
        if (orig == null) {
            return;
        }
        Set<String> mine = selectorValues(sel, key);
        Set<String> van = selectorValues(orig.getAsJsonObject("selectors"), key);
        check(mine.equals(van), "selectors." + key + " 与原版一致, 没被动过 (" + mine.size() + " 项)");
    }

    private static Set<String> selectorValues(JsonObject sel, String key) {
        Set<String> out = new HashSet<>();
        if (sel == null || !sel.has(key) || !sel.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement e : sel.getAsJsonArray(key)) {
            out.add(e.toString());
        }
        return out;
    }

    /** 从失落城市 jar 里读一份 citystyle; 读不到返回 null(相关对照断言会被跳过)。 */
    private static JsonObject readCityStyleFromJar(Path lcJar, String name) {
        if (lcJar == null || !Files.isRegularFile(lcJar)) {
            return null;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(lcJar.toFile())) {
            java.util.zip.ZipEntry e = zip.getEntry("data/lostcities/lostcities/citystyles/" + name + ".json");
            if (e == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(e)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + what);
        } else {
            failures.add(what);
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }

    static {
        // 让日志别因为找不到实现而刷屏
        LoggerFactory.getLogger(StartupSmokeTest.class);
    }
}
