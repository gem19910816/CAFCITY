import com.caf.lccustomcity.PackFinderBridge;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 离线自检: AddPackFindersEvent 桥接(PackFinderBridge)的反射接线是否正确。
 *
 * <p>真实游戏里这些 Minecraft / NeoForge 类在编译期看不到, 所以桥接全靠运行时按类名反射。
 * 为了能在不启动游戏的情况下验证它, 这里用 tools/stubs 下的<b>同名同签名的桩类</b>顶替,
 * 于是能真正跑一遍: 注册监听 → 收到事件 → 交出 RepositorySource → 转发给原版
 * FolderRepositorySource。签名都是从字节码里读出来的, 桩类照着写。
 *
 * <p>用法: java PackFinderBridgeSmokeTest [沙盒目录]
 */
public final class PackFinderBridgeSmokeTest {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args.length > 0 ? args[0] : "build/smoke-pfb").toAbsolutePath();
        Path packRoot = sandbox.resolve("生成的建筑包");
        java.nio.file.Files.createDirectories(packRoot.resolve("CAF-LC建筑包"));
        java.nio.file.Files.writeString(
                packRoot.resolve("CAF-LC建筑包").resolve("pack.mcmeta"), "{}");

        var log = LoggerFactory.getLogger("smoke");

        // ---------- 1. 注册监听器 ----------
        System.out.println("--- 场景 1: 注册监听器 ---");
        AtomicReference<Consumer<Object>> captured = new AtomicReference<>();
        IEventBus bus = fakeBus(captured, false);
        boolean ok = PackFinderBridge.register(bus, packRoot, log);
        check("register 返回 true", ok);
        check("监听器被交给了 addListener", captured.get() != null, "");

        Consumer<Object> listener = captured.get();
        if (listener == null) {
            finish(sandbox);
            return;
        }

        // ---------- 2. SERVER_DATA 事件应当交出包源 ----------
        System.out.println();
        System.out.println("--- 场景 2: 收到 SERVER_DATA 事件 ---");
        FolderRepositorySource.loadPacksCalls = 0;
        AddPackFindersEvent dataEvent = new AddPackFindersEvent(PackType.SERVER_DATA);
        listener.accept(dataEvent);
        check("事件收到了 RepositorySource", dataEvent.added != null, "");

        RepositorySource source = dataEvent.added;
        if (source == null) {
            finish(sandbox);
            return;
        }
        check("交出的是 RepositorySource 的实现(动态代理)",
                source instanceof RepositorySource
                        && Proxy.isProxyClass(source.getClass()), "实际 " + source.getClass());
        check("原版 FolderRepositorySource 已被构造出来, 且根目录正确",
                packRoot.equals(FolderRepositorySource.lastRoot), "实际 " + FolderRepositorySource.lastRoot);
        check("类型是 SERVER_DATA",
                FolderRepositorySource.lastType == PackType.SERVER_DATA, "");
        check("包源用的是 PackSource.WORLD(会被自动启用)",
                FolderRepositorySource.lastSource != null
                        && "WORLD".equals(FolderRepositorySource.lastSource.toString()),
                "实际 " + FolderRepositorySource.lastSource);
        check("DirectoryValidator 用 PathMatcher 构造成功",
                FolderRepositorySource.lastValidator instanceof DirectoryValidator, "");
        check("全放行的 PathMatcher 能正常求值",
                ((DirectoryValidator) FolderRepositorySource.lastValidator)
                        .matcher().matches(Path.of("任意路径")), "");

        // ---------- 3. loadPacks 必须转发给原版 ----------
        System.out.println();
        System.out.println("--- 场景 3: 把 loadPacks 转发给原版扫描器 ---");
        List<Object> emitted = new ArrayList<>();
        source.loadPacks(emitted::add);
        check("原版 FolderRepositorySource.loadPacks 被调用了一次",
                FolderRepositorySource.loadPacksCalls == 1, "实际 " + FolderRepositorySource.loadPacksCalls);
        check("原版发出的包透传给了调用方",
                emitted.size() == 1 && String.valueOf(emitted.get(0)).startsWith("fake-pack@"),
                "实际 " + emitted);

        // ---------- 4. 其它类型(资源包)必须被忽略 ----------
        System.out.println();
        System.out.println("--- 场景 4: CLIENT_RESOURCES 事件应当被忽略 ---");
        AddPackFindersEvent resEvent = new AddPackFindersEvent(PackType.CLIENT_RESOURCES);
        listener.accept(resEvent);
        check("资源包事件不交出包源", resEvent.added == null, "");

        // ---------- 5. 注册失败时必须吞掉异常 ----------
        System.out.println();
        System.out.println("--- 场景 5: bus 抛异常时不能把游戏搞崩 ---");
        IEventBus brokenBus = fakeBus(new AtomicReference<>(), true);
        boolean broken = true;
        try {
            broken = !PackFinderBridge.register(brokenBus, packRoot, log);
        } catch (Throwable t) {
            check("register 不应该向外抛异常, 但它抛了: " + t, false);
        }
        if (broken) {
            check("注册失败时返回 false(退回 mods 建筑包那条路)", true, "");
        }

        finish(sandbox);
    }

    /** 造一个假的 IEventBus: 抓住 addListener 传进来的 Consumer。 */
    private static IEventBus fakeBus(AtomicReference<Consumer<Object>> sink, boolean explode) {
        return (IEventBus) Proxy.newProxyInstance(
                PackFinderBridgeSmokeTest.class.getClassLoader(),
                new Class<?>[] {IEventBus.class},
                (proxy, method, args) -> {
                    if ("addListener".equals(method.getName())) {
                        if (explode) {
                            throw new IllegalStateException("故意炸一下");
                        }
                        sink.set(castConsumer(args[args.length - 1]));
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

    @SuppressWarnings("unchecked")
    private static Consumer<Object> castConsumer(Object o) {
        return (Consumer<Object>) o;
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [OK]   " + what);
        } else {
            failures.add(what);
            System.out.println("  [FAIL] " + what + (detail.isEmpty() ? "" : "  -> " + detail));
        }
    }

    private static void finish(Path sandbox) {
        System.out.println();
        System.out.println("================================");
        System.out.println("通过 " + pass + " 项, 失败 " + failures.size() + " 项");
        System.out.println(failures.isEmpty() ? "PACKFINDER-TEST-ALL-PASS" : "PACKFINDER-TEST-HAS-FAILURES");
        if (!failures.isEmpty()) {
            System.out.println("沙盒目录: " + sandbox);
            System.exit(1);
        }
    }
}
