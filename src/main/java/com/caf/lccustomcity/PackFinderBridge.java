package com.caf.lccustomcity;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.function.Consumer;

/**
 * 把自动生成的数据包在<b>本次启动</b>就塞进数据包栈。
 *
 * <p>机制: 监听 NeoForge 的 {@code AddPackFindersEvent}, 收到事件后交出一个
 * {@code RepositorySource}; 真正的扫描/建包交给原版的 {@code FolderRepositorySource}。
 *
 * <h2>为什么整段都是反射</h2>
 * 在开发环境之外, 实例里的 {@code neoforge-*-client.jar} 只是<b>补丁包</b>
 * (1603 项, 只有 1562 个 {@code net/minecraft/*} 类), 完整的 Mojmap Minecraft 类
 * 是启动时才生成的。{@code PackLocationInfo} / {@code PackSource} / {@code RepositorySource} /
 * {@code FolderRepositorySource} / {@code Component} <b>都不在补丁包里</b>, 编译期根本看不到,
 * 所以只能运行时按类名反射。
 *
 * <h2>优先级: 这条路真能压过原版建筑</h2>
 * 反汇编完整客户端 {@code client-1.21.1-*-srg.jar} 里的 {@code FolderRepositorySource} 得到:
 * <pre>
 *   static { DISCOVERED_PACK_SELECTION_CONFIG = new PackSelectionConfig(false, Pack.Position.TOP, false); }
 *   loadPacks(...) { ... Pack.readMetaAndCreate(info, supplier, type, DISCOVERED_PACK_SELECTION_CONFIG); }
 * </pre>
 * 而 {@code Pack$Position.insert} 的字节码表明 {@code TOP} 是"从尾部扫, 插到列表最末",
 * 且原版包用的是 {@code BOTTOM}(列表最前, 优先级最低)、模组数据包用的是 {@code TOP}。
 * 所以<b>本桥接交出去的包 = 列表最末 = 优先级最高</b>, 和 {@code mods} 里那个建筑包同级。
 *
 * <h2>签名是从字节码里读出来的, 不是猜的</h2>
 * 反汇编 {@code net.minecraft.server.packs.repository.ServerPacksSource} 得到:
 * <pre>
 *   new FolderRepositorySource(Path, PackType, PackSource.WORLD, DirectoryValidator)
 *   new DirectoryValidator(PathMatcher)          // 参数是纯 JDK 的 java.nio.file.PathMatcher
 *   PackLocationInfo(String, Component, PackSource, Optional)
 *   PackSelectionConfig(boolean, Pack.Position, boolean)
 *   Pack.readMetaAndCreate(PackLocationInfo, Pack$ResourcesSupplier, PackType, PackSelectionConfig)
 *   PackSource.WORLD / FEATURE / BUILT_IN 都是 PackSource 上的静态字段
 * </pre>
 *
 * <p>用 {@code PackSource.WORLD} 还有个好处: 存档目录里的数据包就是用它,
 * 而你的地图 {@code level.dat} 里 {@code file/bukkit}、{@code file/owza_datapack}
 * 确实被自动加进了 {@code Enabled} —— 说明 {@code shouldAddAutomatically()} 为真,
 * 所以本包也会被自动启用, 不需要玩家手动开。
 *
 * <h2>安全约定</h2>
 * 任何一步失败都只记日志并返回 {@code false}, 绝不抛出 —— 这样最坏情况就是退回
 * "mods 里的建筑包"那条路(下次启动生效), 而不会让游戏崩在启动阶段。
 */
public final class PackFinderBridge {

    private PackFinderBridge() {
    }

    private static final String CLS_ADD_PACK_FINDERS = "net.neoforged.neoforge.event.AddPackFindersEvent";
    private static final String CLS_PACK_TYPE = "net.minecraft.server.packs.PackType";
    private static final String CLS_PACK_SOURCE = "net.minecraft.server.packs.repository.PackSource";
    private static final String CLS_REPOSITORY_SOURCE = "net.minecraft.server.packs.repository.RepositorySource";
    private static final String CLS_FOLDER_REPOSITORY_SOURCE =
            "net.minecraft.server.packs.repository.FolderRepositorySource";
    private static final String CLS_DIRECTORY_VALIDATOR =
            "net.minecraft.world.level.validation.DirectoryValidator";

    /** 数据包类型, 只要这个, 不要资源包。 */
    private static final String PACK_TYPE_SERVER_DATA = "SERVER_DATA";

    /**
     * 注册监听器。必须在模组构造阶段调用(事件在模组构造之后才触发),
     * 而且此时 {@code packRoot} 下已经写好了数据包。
     *
     * @param packRoot 数据包<b>父</b>目录; 原版会扫描它下面每个子目录当成一个数据包
     * @return 是否注册成功
     */
    public static boolean register(IEventBus bus, Path packRoot, Logger log) {
        try {
            Class<?> eventCls = Class.forName(CLS_ADD_PACK_FINDERS);
            Consumer<Object> handler = event -> handle(event, packRoot, log);

            Method add = IEventBus.class.getMethod(
                    "addListener", EventPriority.class, boolean.class, Class.class, Consumer.class);
            add.invoke(bus, EventPriority.NORMAL, false, eventCls, handler);

            log.info("[CAF-LC] 已注册 AddPackFindersEvent, 数据包会在本次启动就进入数据包栈");
            return true;
        } catch (Throwable t) {
            log.warn("[CAF-LC] 注册 AddPackFindersEvent 失败({}), 本次启动不注入; "
                    + "mods 里的建筑包仍然有效: {}", t.toString(), t.getMessage());
            return false;
        }
    }

    /** 事件回调。只要 {@code SERVER_DATA} 那一次。 */
    private static void handle(Object event, Path packRoot, Logger log) {
        try {
            Object packType = event.getClass().getMethod("getPackType").invoke(event);
            String typeName = String.valueOf(packType);
            if (!PACK_TYPE_SERVER_DATA.equals(typeName)) {
                return;
            }

            Object source = buildFolderSource(packRoot, log);
            if (source == null) {
                return;
            }

            Class<?> repoSourceCls = Class.forName(CLS_REPOSITORY_SOURCE);
            event.getClass().getMethod("addRepositorySource", repoSourceCls).invoke(event, source);
            log.info("[CAF-LC] 已把 {} 下的数据包注册进数据包栈", packRoot);
        } catch (Throwable t) {
            log.warn("[CAF-LC] 向数据包栈注册失败, 改用 mods 里的建筑包: {}", t.toString());
        }
    }

    /**
     * 用原版的 {@code FolderRepositorySource} 建一个包源, 再套一层
     * {@code RepositorySource} 动态代理把调用转过去。
     *
     * <p>刻意不自己实现 {@code PackResources} —— 那个接口有二十来个方法,
     * 一旦有哪个返回不对, 失落城市会<b>静默地</b>读不到资产。交给原版代码最稳。
     */
    private static Object buildFolderSource(Path packRoot, Logger log) throws Exception {
        Class<?> packTypeCls = Class.forName(CLS_PACK_TYPE);
        Object serverData = enumConstant(packTypeCls, PACK_TYPE_SERVER_DATA);

        Class<?> packSourceCls = Class.forName(CLS_PACK_SOURCE);
        Object worldSource = staticField(packSourceCls, "WORLD", "SERVER", "DEFAULT");

        Class<?> validatorCls = Class.forName(CLS_DIRECTORY_VALIDATOR);
        Object validator = newDirectoryValidator(validatorCls);

        Class<?> folderCls = Class.forName(CLS_FOLDER_REPOSITORY_SOURCE);
        Object folderSource = folderCls
                .getConstructor(Path.class, packTypeCls, packSourceCls, validatorCls)
                .newInstance(packRoot, serverData, worldSource, validator);

        Class<?> repoSourceCls = Class.forName(CLS_REPOSITORY_SOURCE);
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("loadPacks".equals(name)) {
                folderCls.getMethod("loadPacks", Consumer.class).invoke(folderSource, args[0]);
                return null;
            }
            if ("toString".equals(name)) {
                return "CAF-LC RepositorySource(" + packRoot + ")";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            log.debug("[CAF-LC] RepositorySource 收到未预期的方法调用: {}", name);
            return null;
        };
        return Proxy.newProxyInstance(repoSourceCls.getClassLoader(),
                new Class<?>[] {repoSourceCls}, handler);
    }

    /** {@code DirectoryValidator(PathMatcher)}; 全放行即可, 我们只是读自己写的目录。 */
    private static Object newDirectoryValidator(Class<?> validatorCls) throws Exception {
        PathMatcher allowAll = path -> true;
        try {
            return validatorCls.getConstructor(PathMatcher.class).newInstance(allowAll);
        } catch (NoSuchMethodException first) {
            // 万一不是 public, 退一步用 declared + setAccessible
            var ctor = validatorCls.getDeclaredConstructor(PathMatcher.class);
            ctor.setAccessible(true);
            return ctor.newInstance(allowAll);
        }
    }

    /** 按名字取枚举常量(不用 Enum.valueOf, 免得和泛型较劲)。 */
    private static Object enumConstant(Class<?> enumCls, String name) {
        Object[] consts = enumCls.getEnumConstants();
        if (consts != null) {
            for (Object c : consts) {
                if (name.equals(((Enum<?>) c).name())) {
                    return c;
                }
            }
        }
        throw new IllegalStateException("找不到枚举常量 " + enumCls.getName() + "." + name);
    }

    /** 依次尝试几个静态字段名, 取到第一个非空的。 */
    private static Object staticField(Class<?> cls, String... names) throws Exception {
        for (String n : names) {
            try {
                Object v = cls.getField(n).get(null);
                if (v != null) {
                    return v;
                }
            } catch (NoSuchFieldException ignored) {
                // 试下一个
            }
        }
        throw new IllegalStateException("在 " + cls.getName() + " 上找不到字段 " + String.join("/", names));
    }
}
