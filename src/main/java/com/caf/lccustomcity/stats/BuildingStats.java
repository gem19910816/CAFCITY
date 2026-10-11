package com.caf.lccustomcity.stats;

import com.caf.lccustomcity.convert.LcAssetGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 建筑生成次数统计(玩家要求: "我想看看是不是所有建筑都是正常生成的")。
 *
 * <h2>要解决什么问题</h2>
 * 玩家放了 N 栋建筑, 但城市里可能永远见不到其中某一栋(选不中 / 分区占比太小 /
 * 转换时被跳过)。只看游戏画面无法判断"这栋楼是一次都没生成, 还是我跑的图太少"。
 * 所以给每栋建筑一个计数器: <b>一次都没涨过的 = 从未生成过</b>, 一眼就能看出来。
 *
 * <h2>计数口径(重要, 别误解)</h2>
 * 挂钩点在失落城市"挑建筑"的那一刻({@code CityStyle.getRandomBuilding} /
 * {@code getRandomMultiBuilding}, 见 {@code mixin/BuildingCountMixin}), 所以
 * <b>计的是"被选中次数"</b>:
 * <ul>
 *   <li>单格建筑: 每个建筑格挑一次 ⇒ 约等于"放置的楼数";</li>
 *   <li>多格建筑: 一个多格块挑一次 ⇒ 按"整栋多格建筑"计 1 次(不是按格)。</li>
 * </ul>
 * 这个口径足够回答玩家的问题(0 次 = 一次都没被选中过), 而且统计的是"选中"而不是
 * "真正落地", 所以只会偏多、不会把存在的建筑报成 0 —— 方向上是安全的。
 *
 * <h2>性能(玩家明确问过)</h2>
 * 一次计数 = 一次 {@link ConcurrentHashMap} 查表 + 一次 {@link LongAdder#increment()}。
 * 世界生成是多线程的, 所以:
 * <ul>
 *   <li>登记表用 {@link ConcurrentHashMap}(绝不用普通 HashMap);</li>
 *   <li>计数器用 {@link LongAdder}(比 AtomicLong 在高并发下更省, 读取时才求和);</li>
 *   <li>查询路径上<b>不做任何字符串拼接/排序/日志</b>, 只有命中时自增;</li>
 *   <li>没安装(没建筑)时直接 return, 一次 map 查询都不做。</li>
 * </ul>
 *
 * <h2>绝不影响世界生成</h2>
 * {@link #record} 整个包在 try/catch 里: 出任何错只记<b>一条</b>警告, 之后静默,
 * 绝不把异常抛回区块生成。
 *
 * <h2>导出(只在两处整理, 不做实时导出)</h2>
 * <ul>
 *   <li>退出存档 / 服务器停止: {@code mixin/ServerStopStatsMixin}(MinecraftServer.stopServer)
 *       + 一个 JVM 关闭钩子(兜底, 关游戏一定写);</li>
 *   <li>游戏里敲 {@code /cafbuildstats}: {@code mixin/BuildStatsCommandMixin}。</li>
 * </ul>
 * 输出文件: {@code config/失落城市建筑/建筑生成统计.txt}(UTF-8)。
 *
 * <p>本类<b>不引用任何 Minecraft / NeoForge 类</b>, 所以能被离线冒烟测试直接跑
 * (见 {@code tools/smoke/BuildingStatsSmokeTest.java})。
 */
public final class BuildingStats {

    /** 统计文件名(写在 config/失落城市建筑/ 下)。 */
    public static final String FILE_NAME = "建筑生成统计.txt";

    /** 本模组的资产命名空间前缀 —— 失落城市挑建筑返回的字符串带这个前缀, 登记表里存的是不带前缀的 id。 */
    private static final String NS_PREFIX = LcAssetGenerator.NS + ":";

    private static final Logger LOGGER = LoggerFactory.getLogger("CAF-LC");

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 名称列的目标宽度(按显示宽度算: 一个汉字算 2 格), 用来对齐"次数"列。 */
    private static final int NAME_COLUMNS = 28;

    /** 城区在报告里的先后顺序: 内城在前, 外城在后, 其它(理论上没有)最后。 */
    private static final String ZONE_INNER = "内城";
    private static final String ZONE_OUTER = "外城";

    /** 只在第一次出错时警告一次(玩家要求: 出错静默跳过, 最多一条警告)。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** 导出时加锁, 避免"退出存档"和"命令"同时写同一个文件。 */
    private static final Object EXPORT_LOCK = new Object();

    /**
     * 一栋建筑的登记项: 属于哪个城区/子分区 + 资产 id + 给玩家看的名字 + 计数器。
     *
     * <p>计数器是 {@link LongAdder}, 自增无锁; 不在生成线程上做任何其它事。
     */
    public static final class Entry {
        /** 城区名(内城 / 外城)。 */
        public final String zone;
        /** 子分区名(仅内城有; 直接放在城区目录下的建筑为 null)。 */
        public final String subZone;
        /** 建筑资产 id(不带命名空间), 和失落城市挑建筑返回的字符串去前缀后一致。 */
        public final String id;
        /** 给玩家看的名字(建筑文件名)。同名文件会追加短哈希消歧, 所以这里不是 final。 */
        private String displayName;
        /** true = 多格建筑(整栋计 1 次)。 */
        public final boolean multi;

        private final LongAdder count = new LongAdder();

        Entry(String zone, String subZone, String id, String displayName, boolean multi) {
            this.zone = zone;
            this.subZone = subZone;
            this.id = id;
            this.displayName = displayName;
            this.multi = multi;
        }

        /** 当前计数。 */
        public long count() {
            return count.sum();
        }

        /** 给玩家看的名字(不含扩展名)。 */
        public String displayName() {
            return displayName;
        }

        /** 报告里显示的位置(如 {@code 内城·军事区} / {@code 外城})。 */
        public String location() {
            return (subZone == null || subZone.isEmpty()) ? zone : zone + "·" + subZone;
        }

        @Override
        public String toString() {
            return id + " (" + location() + ") = " + count();
        }
    }

    /** 独立资产 id(不带命名空间) -> 登记项。 */
    private static volatile Map<String, Entry> byKey = Map.of();
    /** 全部登记项(报告用, 顺序稳定: 城区 → 子分区 → 名字)。 */
    private static volatile List<Entry> registry = List.of();
    /** 不认识的 id(别的命名空间的建筑等) -> 计数, 只为报告里能看出"口径不对"。 */
    private static volatile Map<String, LongAdder> unknown = new ConcurrentHashMap<>();
    /** 统计文件路径(configDir 为 null 时是 null = 不导出)。 */
    private static volatile Path statsFile;
    /** 本次统计的起始时间(报告标题里的"自 <时间>")。 */
    private static volatile String startTime = "";
    /** 是否已经安装过。没安装时 record() 直接返回, 一次查表都不做。 */
    private static volatile boolean installed = false;
    private static volatile boolean hookInstalled = false;

    private BuildingStats() {
    }

    // ==================================================================
    // 安装(启动时, 世界生成之前)
    // ==================================================================

    /**
     * 用转换结果登记"全部建筑 = 0 次"。之后 {@link #record} 自增, 结束时还是 0 的
     * 就是"从未生成过"。
     *
     * <p>建筑清单<b>不重新扫目录</b>, 直接取 {@link LcAssetGenerator.Output} 里已有的
     * 分组结果: 内城单格/多格、外城单格/多格, 以及内城的"子分区 -> 建筑"表。
     * 没进过子分区表的建筑(直接放在 {@code 内城\} 下的)归到城区本身。
     *
     * @param configDir 统计文件的输出目录(即 config/失落城市建筑/); null = 只统计不导出
     * @param out       转换结果; null = 登记表为空
     */
    public static void install(Path configDir, LcAssetGenerator.Output out) {
        try {
            statsFile = (configDir == null) ? null : configDir.resolve(FILE_NAME);
            startTime = LocalDateTime.now().format(TIME_FMT);

            Map<String, Entry> keys = new ConcurrentHashMap<>();
            List<Entry> list = new ArrayList<>();
            Map<String, LongAdder> fresh = new ConcurrentHashMap<>();

            if (out != null) {
                // id -> 子分区名(内城才有; 单格和多格两张表合起来查)
                Map<String, String> subOf = new LinkedHashMap<>();
                collectSubZones(out.innerSubBuildingIds, subOf);
                collectSubZones(out.innerSubMultiIds, subOf);

                add(list, keys, out, out.buildingIds, false, ZONE_INNER, subOf);
                add(list, keys, out, out.multiIds, true, ZONE_INNER, subOf);
                // 外城: 失落城市原版没有"外城子分区"的概念(连续分区只切内城), 所以不细分
                add(list, keys, out, out.outerBuildingIds, false, ZONE_OUTER, Map.of());
                add(list, keys, out, out.outerMultiIds, true, ZONE_OUTER, Map.of());

                disambiguate(list);
                list.sort(Comparator.comparingInt((Entry e) -> zoneRank(e.zone))
                        .thenComparing(e -> e.subZone == null ? "" : e.subZone)
                        .thenComparing(e -> e.displayName)
                        .thenComparing(e -> e.id));
            }

            // 一次性换上(volatile 赋值): 计数线程不会看到半成品
            unknown = fresh;
            byKey = keys;
            registry = List.copyOf(list);
            installed = true;
            installShutdownHook();

            LOGGER.info("[CAF-LC] 建筑生成统计已就绪: 登记 {} 栋建筑(内城单格 {} + 内城多格 {} + 外城单格 {} + 外城多格 {}), "
                            + "退出存档 / 关闭游戏 时写出 {}",
                    list.size(),
                    out == null ? 0 : out.buildingIds.size(), out == null ? 0 : out.multiIds.size(),
                    out == null ? 0 : out.outerBuildingIds.size(), out == null ? 0 : out.outerMultiIds.size(),
                    statsFile == null ? "(未指定路径)" : statsFile);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void collectSubZones(Map<String, List<String>> bySub, Map<String, String> out) {
        if (bySub == null) {
            return;
        }
        for (Map.Entry<String, List<String>> e : bySub.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            for (String id : e.getValue()) {
                if (id != null) {
                    out.putIfAbsent(id, e.getKey());
                }
            }
        }
    }

    private static void add(List<Entry> list, Map<String, Entry> keys, LcAssetGenerator.Output out,
                            List<String> ids, boolean multi, String zone, Map<String, String> subOf) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            String sub = subOf.get(id);
            String display = shortName(out.displayNames.get(id), id);
            Entry e = new Entry(zone, sub, id, display, multi);
            list.add(e);
            // 两种键都登记: 失落城市挑建筑返回的是"命名空间:id"(selectors 里写的就是这个),
            // 但也可能拿到不带命名空间的 id —— 两种写法都指向同一个登记项, 不会重复计数
            keys.put(id, e);
            keys.put(NS_PREFIX + id, e);
        }
    }

    /** 同名建筑文件加短哈希消歧(两个子分区里都有"住宅楼.schem"时, 报告里必须能分清)。 */
    private static void disambiguate(List<Entry> list) {
        Map<String, Integer> seen = new HashMap<>();
        for (Entry e : list) {
            seen.merge(e.displayName, 1, Integer::sum);
        }
        for (Entry e : list) {
            Integer n = seen.get(e.displayName);
            if (n != null && n > 1) {
                e.displayName = e.displayName + " #" + tail(e.id);
            }
        }
    }

    private static String tail(String id) {
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }

    /** 从原始相对路径取"给玩家看的名字"(文件名去扩展名); 取不到就用资产 id。 */
    private static String shortName(String relPath, String id) {
        if (relPath == null || relPath.isEmpty()) {
            return id;
        }
        String s = relPath.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.isEmpty() ? id : s;
    }

    // ==================================================================
    // 计数(世界生成线程上调用, 必须极廉价)
    // ==================================================================

    /**
     * 记一次"这栋建筑被选中"。
     *
     * <p>整个方法包在 try/catch 里: 计数出错只记一条警告, 绝不把异常抛回区块生成。
     * 多线程安全(登记表 ConcurrentHashMap + LongAdder)。
     *
     * @param buildingId 失落城市挑建筑返回的 id(带或不带命名空间都可以)
     * @param multi      true = 走的是多格建筑那条路(只用于"没登记过的 id"的诊断信息)
     */
    public static void record(String buildingId, boolean multi) {
        if (!installed) {
            return;   // 没安装: 一次查表都不做
        }
        try {
            if (buildingId == null || buildingId.isEmpty()) {
                return;
            }
            Map<String, Entry> table = byKey;
            Entry e = table.get(buildingId);
            if (e == null) {
                e = table.get(normalize(buildingId));
            }
            if (e != null) {
                e.count.increment();   // 一次 LongAdder 自增, 无锁
                return;
            }
            // 没登记过的 id(别的命名空间的建筑, 或建筑清单变了): 单独存起来, 报告里能看出来
            unknown.computeIfAbsent(buildingId + (multi ? " [多格]" : " [单格]"), k -> new LongAdder())
                    .increment();
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /** 去掉本模组的命名空间前缀(其它命名空间保留原样, 好让它在报告里显形)。 */
    private static String normalize(String id) {
        return id.startsWith(NS_PREFIX) ? id.substring(NS_PREFIX.length()) : id;
    }

    private static void warnOnce(Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("[CAF-LC] 建筑生成统计出错(已忽略, 不影响世界生成): {}", t.toString());
        }
    }

    // ==================================================================
    // 导出
    // ==================================================================

    /** 统计文件的完整路径(configDir 为 null 时为 null)。 */
    public static Path path() {
        return statsFile;
    }

    /** 登记了多少栋建筑(0 = 还没安装)。 */
    public static int registered() {
        return registry.size();
    }

    /**
     * 整理一次并写出统计文件(退出存档 / 服务器停止 / 敲命令时调用)。
     *
     * @param reason 写日志用的原因(如 "退出存档" / "游戏内命令")
     * @return 写出的文件路径; 失败或没有路径时返回 null(调用方据此决定要不要提示玩家)
     */
    public static Path export(String reason) {
        Path file = statsFile;
        if (file == null) {
            return null;
        }
        synchronized (EXPORT_LOCK) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, formatReport(), StandardCharsets.UTF_8);
                LOGGER.info("[CAF-LC] 建筑生成统计已写出({}): {}", reason, file);
                return file;
            } catch (Throwable t) {
                warnOnce(t);
                return null;
            }
        }
    }

    /**
     * 关闭游戏时的兜底导出。
     *
     * <p>为什么不只靠 {@code ServerStoppingEvent} / {@code MinecraftServer.stopServer}:
     * 本模组编译时看不到 NeoForge 的类(构建只用 FML + 几个 jar, 见 build.ps1), 所以
     * 事件总线那条路走不通; 而"退出存档"由 {@code mixin/ServerStopStatsMixin} 负责,
     * 关游戏由这个钩子负责, 两条路都写同一个文件, 谁先到都不影响结果(写的是全量快照)。
     */
    private static void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> export("关闭游戏"), "CAF-LC-stats-export"));
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    // ==================================================================
    // 报告文本
    // ==================================================================

    /**
     * 生成报告文本(不写文件, 方便离线测试直接比对)。
     *
     * <p>格式(玩家要求的样子):
     * <pre>
     * === 建筑生成次数统计（自 2026-02-03 12:34:56）===
     * 口径: ...
     * 总放置次数: 1234
     * --- 内城 (5/12 栋生成过) ---
     * 便利店                  12 次   [商业区]
     * --- 外城 (1/2 栋生成过) ---
     * 民房A                   58 次
     * --- ⚠️ 从未生成过 (7) ---
     * 导弹楼                   0 次   [内城·军事区]
     * </pre>
     * 城区内按次数<b>降序</b>; 0 次的建筑不进城区段, 全部集中到"从未生成过"那一段
     * (那一段才是这份统计的核心价值)。
     */
    public static String formatReport() {
        try {
            List<Entry> list = registry;
            Map<String, LongAdder> others = unknown;

            long known = 0;
            for (Entry e : list) {
                known += e.count();
            }
            long outside = 0;
            for (LongAdder a : others.values()) {
                outside += a.sum();
            }

            StringBuilder sb = new StringBuilder(4096);
            sb.append("=== 建筑生成次数统计（自 ").append(startTime).append("）===\n");
            sb.append("口径: \"次\" = 失落城市挑建筑时选中它的次数(单格建筑按建筑格计, 多格建筑按整栋计); ")
                    .append("0 次 = 一次都没被选中过。\n");
            sb.append("总放置次数: ").append(known + outside);
            if (outside > 0) {
                sb.append("(清单内 ").append(known).append(" + 清单外 ").append(outside).append(')');
            }
            sb.append('\n');

            for (String zone : new String[] { ZONE_INNER, ZONE_OUTER }) {
                List<Entry> zoneAll = new ArrayList<>();
                for (Entry e : list) {
                    if (zone.equals(e.zone)) {
                        zoneAll.add(e);
                    }
                }
                if (zoneAll.isEmpty() && !ZONE_INNER.equals(zone)) {
                    continue;   // 外城一栋都没配过: 不占篇幅
                }
                int alive = 0;
                for (Entry e : zoneAll) {
                    if (e.count() > 0) {
                        alive++;
                    }
                }
                sb.append("--- ").append(zone).append(" (").append(alive).append('/').append(zoneAll.size())
                        .append(" 栋生成过) ---\n");
                List<Entry> shown = new ArrayList<>();
                for (Entry e : zoneAll) {
                    if (e.count() > 0) {
                        shown.add(e);
                    }
                }
                shown.sort(Comparator.comparingLong((Entry e) -> -e.count())
                        .thenComparing(e -> e.displayName)
                        .thenComparing(e -> e.id));
                for (Entry e : shown) {
                    appendLine(sb, e.displayName, e.count(),
                            ZONE_INNER.equals(zone) && e.subZone != null ? "[" + e.subZone + "]" : null);
                }
            }

            List<Entry> never = new ArrayList<>();
            for (Entry e : list) {
                if (e.count() == 0) {
                    never.add(e);
                }
            }
            never.sort(Comparator.comparingInt((Entry e) -> zoneRank(e.zone))
                    .thenComparing(e -> e.subZone == null ? "" : e.subZone)
                    .thenComparing(e -> e.displayName));
            sb.append("--- ⚠️ 从未生成过 (").append(never.size()).append(") ---\n");
            if (never.isEmpty()) {
                sb.append("(全部 ").append(list.size()).append(" 栋建筑都至少生成过一次)\n");
            }
            for (Entry e : never) {
                appendLine(sb, e.displayName, 0, "[" + e.location() + "]");
            }

            if (!others.isEmpty()) {
                sb.append("--- 其它(不在本模组建筑清单里) (").append(others.size()).append(") ---\n");
                List<Map.Entry<String, LongAdder>> o = new ArrayList<>(others.entrySet());
                o.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> x) -> -x.getValue().sum())
                        .thenComparing(Map.Entry::getKey));
                for (Map.Entry<String, LongAdder> x : o) {
                    appendLine(sb, x.getKey(), x.getValue().sum(), null);
                }
            }

            sb.append("（本文件在 退出存档 / 服务器停止 / 关闭游戏 时自动刷新; ")
                    .append("游戏里也可以输入 /cafbuildstats 立刻导出）\n");
            return sb.toString();
        } catch (Throwable t) {
            warnOnce(t);
            return "建筑生成次数统计生成失败: " + t + "\n";
        }
    }

    /** 一行: 名字 + 对齐空格 + "<次数> 次" + 可选的 [位置] 标注。 */
    private static void appendLine(StringBuilder sb, String name, long count, String tag) {
        sb.append(name);
        int pad = NAME_COLUMNS - displayWidth(name);
        if (pad < 1) {
            pad = 1;
        }
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        sb.append(count).append(" 次");
        if (tag != null && !tag.isEmpty()) {
            sb.append("   ").append(tag);
        }
        sb.append('\n');
    }

    private static int zoneRank(String zone) {
        if (ZONE_INNER.equals(zone)) {
            return 0;
        }
        return ZONE_OUTER.equals(zone) ? 1 : 2;
    }

    /** 显示宽度: 汉字等全角字符占 2 格(纯 ASCII 文件里这些码位写成十六进制, 不用转义字面量)。 */
    static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isWide(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }
}
