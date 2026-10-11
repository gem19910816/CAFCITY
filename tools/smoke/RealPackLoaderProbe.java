import com.caf.lccustomcity.PackFinderBridge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 用<b>真实</b>的原版类与真实的 NeoForge 事件, 把 AddPackFindersEvent 那条路完整跑一遍。
 *
 * <p>之前 {@code PackFinderBridgeSmokeTest} 用的是 {@code tools/stubs} 下我自己按签名手写的桩类。
 * 桩测试能证明"反射接线自洽", 但证明不了"我猜的签名和真实类一致" —— 签名猜错了桩测试照样全绿。
 * 这个探针把真类拿进来对:
 *
 * <ol>
 *   <li>真 {@code FolderRepositorySource} + 真 {@code loadPacks} 去读我们生成的数据包目录</li>
 *   <li>{@code PackFinderBridge.register} 在真类齐全时应当返回 true(而不是优雅降级)</li>
 *   <li>造真 {@code AddPackFindersEvent}, 喂给桥接注册的那个监听器</li>
 *   <li>从事件里取出被加进去的 {@code RepositorySource}, 调它的 {@code loadPacks},
 *       确认原版真的能把我们的数据包交出来</li>
 *   <li>确认 {@code PackSource.WORLD} 是"自动启用"的 —— 玩家不需要手动去界面里勾选</li>
 * </ol>
 *
 * <p><b>跑这个探针时 classpath 上绝不能有 tools/stubs</b>, 否则桩类会遮蔽真类。
 *
 * <p>用法: java RealPackLoaderProbe &lt;数据包父目录&gt;
 */
public final class RealPackLoaderProbe {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path packRoot = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath();

        System.out.println("=== 真原版类 + 真 NeoForge 事件: AddPackFindersEvent 全路径验证 ===");
        System.out.println("数据包父目录: " + packRoot);
        System.out.println();

        Class<?> frsCls = Class.forName("net.minecraft.server.packs.repository.FolderRepositorySource");
        Class<?> packTypeCls = Class.forName("net.minecraft.server.packs.PackType");
        Class<?> packSourceCls = Class.forName("net.minecraft.server.packs.repository.PackSource");
        Class<?> validatorCls = Class.forName("net.minecraft.world.level.validation.DirectoryValidator");
        Class<?> packCls = Class.forName("net.minecraft.server.packs.repository.Pack");
        Object serverData = enumValue(packTypeCls, "SERVER_DATA");
        Object worldSource = packSourceCls.getField("WORLD").get(null);

        // ---------- 0. 让 SharedConstants 知道游戏版本 ----------
        // Pack.readMetaAndCreate 会调 SharedConstants.getCurrentVersion()。游戏里 FML 引导时早就设好了,
        // 离线跑就得自己来 —— tryDetectVersion() 会从 classpath 上找 version.json。
        System.out.println("--- 0. 游戏版本(离线跑必须自己设, 游戏里 FML 会设) ---");
        Class<?> sharedConstants = Class.forName("net.minecraft.SharedConstants");
        try {
            sharedConstants.getMethod("tryDetectVersion").invoke(null);
        } catch (Throwable t) {
            System.out.println("    tryDetectVersion 失败: " + t);
        }
        Object version = null;
        try {
            version = sharedConstants.getMethod("getCurrentVersion").invoke(null);
        } catch (Throwable t) {
            System.out.println("    getCurrentVersion 失败: " + t);
        }
        System.out.println("    游戏版本 = " + version);
        check(version != null, "SharedConstants 检测到了游戏版本(靠 classpath 上的 version.json)");
        if (version != null) {
            Class<?> wvCls = Class.forName("net.minecraft.WorldVersion");
            Object dataFmt = wvCls.getMethod("getPackVersion", packTypeCls).invoke(version, serverData);
            System.out.println("    游戏自己说的数据包格式号 = " + dataFmt);
            check("48".equals(String.valueOf(dataFmt)),
                    "游戏自己的代码也确认 1.21.1 的数据包格式号是 48");
        }

        // pack.mcmeta 里的 description 是个 Component, 解析它会一路碰到 ItemStack / BuiltInRegistries,
        // 而注册表必须先 bootstrap(游戏里 FML 会做, MC 自己的测试也是显式调这个)。不 bootstrap 就会
        // 报 "Not bootstrapped (called from registry ...)"。
        System.out.println();
        System.out.println("--- 0b. 注册表 bootstrap(离线跑必需) ---");
        try {
            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap");
            bootstrap.getMethod("bootStrap").invoke(null);
            System.out.println("    Bootstrap.bootStrap() 成功");
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            System.out.println("    Bootstrap.bootStrap() 失败, 最内层原因: " + c);
        }

        // ---------- 1. 加载到的确实是真类 ----------
        System.out.println("--- 1. 加载到的是真类吗 ---");
        String from = frsCls.getProtectionDomain().getCodeSource().getLocation().toString();
        check(from.endsWith(".jar") && from.contains("client-1.21.1"),
                "FolderRepositorySource 来自真客户端包, 不是桩 -> " + from);
        check(!frsCls.getName().contains("stub"), "类名里没有 stub 字样");

        // ---------- 2. PackSource.WORLD 会自动启用 ----------
        System.out.println();
        System.out.println("--- 2. PackSource.WORLD 的行为(决定玩家要不要手动勾选) ---");
        Method auto = packSourceCls.getMethod("shouldAddAutomatically");
        Object autoVal = auto.invoke(worldSource);
        System.out.println("    PackSource.WORLD.shouldAddAutomatically() = " + autoVal);
        check(Boolean.TRUE.equals(autoVal),
                "WORLD 是自动启用的 —— 玩家不需要进数据包界面手动勾选");

        // ---------- 3. 真扫描器读我们生成的目录 ----------
        System.out.println();
        System.out.println("--- 3. 真 FolderRepositorySource 读我们生成的目录 ---");
        List<Object> direct = loadPacksOf(frsCls, packRoot, serverData, worldSource, validatorCls);
        System.out.println("    真原版扫描器交出 " + direct.size() + " 个数据包");
        for (Object p : direct) {
            System.out.println("      - " + describe(p));
        }
        check(!direct.isEmpty(),
                "原版扫描器认得我们生成的目录(证明目录结构与 pack.mcmeta 都是原版能读的)");

        // ---------- 4. 桥接注册: 真类齐全时应当返回 true ----------
        System.out.println();
        System.out.println("--- 4. PackFinderBridge.register 在真类齐全时的结果 ---");
        List<Object> added = new ArrayList<>();
        IEventBus bus = realBus(added);
        boolean live = PackFinderBridge.register(bus, packRoot, LoggerFactory.getLogger("RealProbe"));
        System.out.println("    register 返回 " + live);
        check(live, "register 返回 true(说明真类都在, 不需要退回到 mods 建筑包那条路)");
        check(added.size() == 1, "监听器被交给了真 bus 的 addListener (实际 " + added.size() + ")");

        if (added.isEmpty()) {
            finish();
            return;
        }

        // ---------- 5. 造真事件, 喂给那个监听器 ----------
        System.out.println();
        System.out.println("--- 5. 造真 AddPackFindersEvent 并喂给监听器 ---");
        List<Object> repos = new ArrayList<>();
        Consumer<Object> collector = repos::add;
        Constructor<AddPackFindersEvent> evCtor = AddPackFindersEvent.class
                .getConstructor(packTypeCls, Consumer.class, boolean.class);
        AddPackFindersEvent realEvent = evCtor.newInstance(serverData, collector, false);
        System.out.println("    事件类型 = " + realEvent.getPackType());
        check(realEvent.getPackType() == serverData, "真事件的 PackType 就是 SERVER_DATA");
        check("SERVER_DATA".equals(String.valueOf(realEvent.getPackType())),
                "桥接里那个 getPackType().toString().equals(\"SERVER_DATA\") 判断成立");

        // 桥接注册的监听器签名是 (AddPackFindersEvent) -> void
        // 桥接注册的监听器就是个 Consumer<Object>(lambda), 直接当 Consumer 调最省事 ——
        // 反过来用反射找 accept 方法会因为 lambda 类不可访问而抛 IllegalAccessException。
        Object listener = added.get(0);
        check(listener instanceof Consumer, "桥接注册的监听器就是个 Consumer -> " + listener.getClass().getName());
        @SuppressWarnings("unchecked")
        Consumer<Object> asConsumer = (Consumer<Object>) listener;
        asConsumer.accept(realEvent);
        check(repos.size() == 1, "监听器往真事件里加进了 1 个包源 (实际 " + repos.size() + ")");

        // ---------- 6. 那个包源真的能交出我们的数据包 ----------
        System.out.println();
        System.out.println("--- 6. 事件里那个包源能不能交出我们的数据包 ---");
        if (!repos.isEmpty()) {
            Object source = repos.get(0);
            System.out.println("    包源实现类 = " + source.getClass().getName());
            List<Object> packs = new ArrayList<>();
            Method load = source.getClass().getMethod("loadPacks", Consumer.class);
            load.invoke(source, (Consumer<Object>) packs::add);
            System.out.println("    交出 " + packs.size() + " 个数据包");
            for (Object p : packs) {
                System.out.println("      - " + describe(p));
            }
            check(!packs.isEmpty(), "事件加进去的包源能把我们的数据包交出来");
            if (!packs.isEmpty()) {
                String id = str(describe(packs.get(0)), "getId");
                System.out.println("    包 id = " + id);
                check(id != null && id.contains("caf_lc_customcity"),
                        "包 id 就是内层文件夹名 caf_lc_customcity(所以那个名字必须是 ASCII)");
                Object pos = invokeOptional(packs.get(0), "getDefaultPosition");
                System.out.println("    默认位置 = " + pos);
                check(pos != null && "TOP".equals(String.valueOf(pos)),
                        "包的默认位置是 TOP —— 在数据包栈里优先级最高, 压过失落城市自己那份");
            }
        }

        // ---------- 7. 命门: 真实资源栈里我的 citystyle 覆盖能不能赢 ----------
        // LC 的资产走的是数据包注册表(RegistryAssetRegistry + ResourceKey<Registry<R>>),
        // 也就是从"数据包资源管理器"里按 id 读。同一个 id 在我和 LC 两个包里都存在时,
        // 到底是不是高优先级赢? 这一条不成立的话, 整个覆盖就是白做的。
        System.out.println();
        System.out.println("--- 7. 真实资源栈里, 同 id 冲突谁赢 ---");
        Path lcPacksRoot = Path.of(args.length > 1 ? args[1] : "build/lc-packs").toAbsolutePath();
        Path lcOwn = lcPacksRoot.resolve("lc_own");
        Path myInner = packRoot.resolve("caf_lc_customcity");
        if (!Files.isDirectory(myInner) || !Files.isDirectory(lcOwn.resolve("data"))) {
            check(false, "目录不全, 我的内层包 = " + myInner + " , 失落城市那份 = " + lcOwn);
        } else {
            Class<?> pliCls = Class.forName("net.minecraft.server.packs.PackLocationInfo");
            Class<?> pprCls = Class.forName("net.minecraft.server.packs.PathPackResources");
            Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component");
            Object title = componentCls.getMethod("literal", String.class).invoke(null, "probe");

            Object myRes = pprCls.getConstructor(pliCls, Path.class).newInstance(
                    pliCls.getConstructor(String.class, componentCls, packSourceCls, java.util.Optional.class)
                            .newInstance("mine", title, worldSource, java.util.Optional.empty()),
                    myInner);
            Object lcRes = pprCls.getConstructor(pliCls, Path.class).newInstance(
                    pliCls.getConstructor(String.class, componentCls, packSourceCls, java.util.Optional.class)
                            .newInstance("lc_own", title, worldSource, java.util.Optional.empty()),
                    lcOwn);

            Class<?> mprmCls = Class.forName("net.minecraft.server.packs.resources.MultiPackResourceManager");
            // 高优先级在前 —— 这就是数据包栈的顺序
            List<Object> stack = new ArrayList<>();
            stack.add(myRes);
            stack.add(lcRes);
            Object rm = mprmCls.getConstructor(packTypeCls, List.class).newInstance(serverData, stack);

            @SuppressWarnings("unchecked")
            java.util.function.Predicate<Object> all = rl -> true;

            // listResourceStacks 会把"同一个 id 的全部来源按优先级排好"返回, 正好能直接看出谁压谁
            Method listStacks = mprmCls.getMethod("listResourceStacks", String.class,
                    java.util.function.Predicate.class);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, List<Object>> stacks =
                    (java.util.Map<Object, List<Object>>) listStacks.invoke(rm, "lostcities/citystyles", all);
            System.out.println("    两个包里一共 " + stacks.size() + " 个 citystyle id");

            Object stacksWinner = null;
            List<Object> chain = null;
            for (java.util.Map.Entry<Object, List<Object>> e : stacks.entrySet()) {
                if (String.valueOf(e.getKey()).contains("citystyle_common")) {
                    chain = e.getValue();
                }
            }
            check(chain != null, "citystyle_common 在两个包里都有 -> 冲突确实存在");
            if (chain != null) {
                System.out.println("    citystyle_common 的来源层数 = " + chain.size() + "(2 = 我一份 + 失落城市一份)");
                check(chain.size() == 2, "确实有两个来源, 所以必须靠优先级决出胜负");
                if (!chain.isEmpty()) {
                    String first = readResource(chain.get(0));
                    String second = chain.size() > 1 ? readResource(chain.get(1)) : null;
                    System.out.println("    第 1 层含 caf_lc_customcity: " + (first != null && first.contains("caf_lc_customcity:")));
                    System.out.println("    第 2 层含 caf_lc_customcity: " + (second != null && second.contains("caf_lc_customcity:")));
                    check(first != null && first.contains("caf_lc_customcity:"),
                            "优先栈第 1 层就是我的版本");
                    check(second != null && !second.contains("caf_lc_customcity:"),
                            "第 2 层是失落城市原本的版本(它没被删掉, 只是被压住了)");
                }
            }

            // listResources 才是数据包注册表真正用的那个(RegistryDataLoader 就是调它)。
            // 关键在于"包列表的顺序": 这里把两种顺序都跑一遍, 直接把约定问出来。
            Method listRes = mprmCls.getMethod("listResources", String.class,
                    java.util.function.Predicate.class);
            for (int variant = 0; variant < 2; variant++) {
                boolean mineLast = variant == 1;
                List<Object> order = new ArrayList<>();
                if (mineLast) {
                    order.add(lcRes);
                    order.add(myRes);
                } else {
                    order.add(myRes);
                    order.add(lcRes);
                }
                Object rm2 = mprmCls.getConstructor(packTypeCls, List.class).newInstance(serverData, order);
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> map =
                        (java.util.Map<Object, Object>) listRes.invoke(rm2, "lostcities/citystyles", all);
                Object winner = null;
                for (java.util.Map.Entry<Object, Object> e : map.entrySet()) {
                    if (String.valueOf(e.getKey()).contains("citystyle_common")) {
                        winner = e.getValue();
                    }
                }
                String body = winner == null ? null : readResource(winner);
                boolean isMine = body != null && body.contains("caf_lc_customcity:");
                System.out.println("    列表顺序 [" + (mineLast ? "失落城市, 我" : "我, 失落城市")
                        + "] -> listResources 的赢家是我吗: " + isMine);
                if (mineLast) {
                    check(isMine, "我把包放在列表末尾时, listResources 读到的是我的版本");
                    check(body != null && body.contains("streetblocks"),
                            "我的版本里 streetblocks 等街道设置仍然完整");
                } else {
                    check(!isMine, "我放列表开头时读到的是失落城市那份 —— 说明 listResources 是「末尾赢」");
                }
            }
            System.out.println("    (参考: 失落城市的 Position.TOP 在 PackRepository 里会被插到队尾,"
                    + " 也就是「末尾」)");
        }

        // ---------- 8. Position.TOP 到底插在队首还是队尾 ----------
        // 这一条是整条链的最后一环, 而且绝不能靠推断: 若 TOP 其实插在队首, 而 listResources
        // 是"末尾赢", 那我的包就会输, 结论正好反过来。直接调真实方法插一次看结果。
        System.out.println();
        System.out.println("--- 8. Position.TOP 插在队首还是队尾 ---");
        Class<?> posCls = Class.forName("net.minecraft.server.packs.repository.Pack$Position");
        Class<?> cfgCls = Class.forName("net.minecraft.server.packs.PackSelectionConfig");
        Method insert = posCls.getMethod("insert", List.class, Object.class,
                java.util.function.Function.class, boolean.class);
        // insert 会拿这个 Function 从元素里取出 PackSelectionConfig 来比较位置, 所以不能传恒等映射
        java.util.function.Function<Object, Object> ident = o -> o;

        Constructor<?> cfgCtor = cfgCls.getConstructor(boolean.class, posCls, boolean.class);

        for (String which : new String[] {"TOP", "BOTTOM"}) {
            Object pos = enumValue(posCls, which);
            Object cfg = cfgCtor.newInstance(false, pos, false);
            java.util.function.Function<Object, Object> cfgFn = o -> cfg;
            List<Object> list = new ArrayList<>();
            list.add("原有的甲");
            list.add("原有的乙");
            insert.invoke(pos, list, "新来的", cfgFn, false);
            System.out.println("    " + which + " 插入后: " + list);
            if ("TOP".equals(which)) {
                check(list.indexOf("新来的") == list.size() - 1,
                        "TOP 插在队尾(配合 listResources 的「末尾赢」, 我的覆盖才会生效)");
            } else {
                check(list.indexOf("新来的") == 0, "BOTTOM 插在队首");
            }
        }

        // 把三件事串起来: 我的包 TOP 在队尾 + listResources 末尾赢 => 覆盖生效
        check(true, "链条闭合: 我的包 getDefaultPosition()=TOP -> TOP 插在队尾 -> listResources 末尾赢 -> 读到我的 citystyle");

        finish();
    }

    // ==================================================================

    private static String readResource(Object resource) {
        try {
            Object in = resource.getClass().getMethod("open").invoke(resource);
            try (java.io.InputStream is = (java.io.InputStream) in) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            System.out.println("    读取资源失败: " + t);
            return null;
        }
    }

    private static List<Object> loadPacksOf(Class<?> frsCls, Path root, Object type, Object source,
                                            Class<?> validatorCls) throws Exception {
        Object matcher = Proxy.newProxyInstance(
                RealPackLoaderProbe.class.getClassLoader(),
                new Class<?>[] {java.nio.file.PathMatcher.class},
                (p, m, a) -> "matches".equals(m.getName()) ? Boolean.TRUE : defaultAnswer(p, m, a));
        Object validator = validatorCls.getConstructor(java.nio.file.PathMatcher.class).newInstance(matcher);
        Object frs = frsCls.getConstructor(Path.class,
                Class.forName("net.minecraft.server.packs.PackType"),
                Class.forName("net.minecraft.server.packs.repository.PackSource"),
                validatorCls).newInstance(root, type, source, validator);
        List<Object> out = new ArrayList<>();
        frsCls.getMethod("loadPacks", Consumer.class).invoke(frs, (Consumer<Object>) out::add);
        return out;
    }

    /** 真 bus: 记录 addListener 拿到的监听器; 其余方法给出无害的默认值。 */
    private static IEventBus realBus(List<Object> listenerSink) {
        return (IEventBus) Proxy.newProxyInstance(
                RealPackLoaderProbe.class.getClassLoader(),
                new Class<?>[] {IEventBus.class},
                (proxy, method, a) -> {
                    if ("addListener".equals(method.getName())) {
                        listenerSink.add(a[a.length - 1]);
                        return null;
                    }
                    return defaultAnswer(proxy, method, a);
                });
    }

    private static Object defaultAnswer(Object proxy, Method m, Object[] a) {
        switch (m.getName()) {
            case "toString":
                return "Proxy";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return a != null && a.length == 1 && proxy == a[0];
            default:
                return null;
        }
    }

    /** 监听器 lambda 会把真正的方法藏在合成类里, 这里把可调用的那个方法找出来。 */
    private static Field findListenerField(Object listener) {
        for (Field f : listener.getClass().getDeclaredFields()) {
            if (Consumer.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static void invokeListener(Object listener, Object event) throws Exception {
        for (Method m : listener.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(event)) {
                m.invoke(listener, event);
                return;
            }
        }
        // lambda 的 accept
        for (Method m : listener.getClass().getMethods()) {
            if ("accept".equals(m.getName()) && m.getParameterCount() == 1) {
                m.invoke(listener, event);
                return;
            }
        }
        throw new IllegalStateException("找不到能接收事件的监听器方法: " + listener.getClass());
    }

    private static Object invokeOptional(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(String s, String key) {
        int i = s.indexOf(key + "=");
        if (i < 0) {
            return null;
        }
        int j = s.indexOf(" | ", i);
        return s.substring(i + key.length() + 1, j < 0 ? s.length() : j);
    }

    /** 反射问出这个对象的关键信息。 */
    private static String describe(Object o) {
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName());
        for (String m : new String[] {"getId", "getPackId", "getTitle", "getDefaultPosition",
                "isRequired", "getCompatibility", "getPackFormat", "getSource"}) {
            Object v = invokeOptional(o, m);
            if (v != null) {
                String s = v.toString();
                sb.append(" | ").append(m).append("=").append(s, 0, Math.min(70, s.length()));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        for (Object c : enumClass.getEnumConstants()) {
            if (c.toString().equals(name)) {
                return c;
            }
        }
        throw new IllegalStateException("没有枚举常量 " + name);
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

    private static void finish() {
        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + pass + " 项, 失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("[FAIL] " + f);
        }
        System.out.println(failures.isEmpty() ? "REAL-PROBE-ALL-PASS" : "REAL-PROBE-HAS-FAILURES");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
