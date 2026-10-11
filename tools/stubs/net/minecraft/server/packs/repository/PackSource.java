package net.minecraft.server.packs.repository;

/** 离线测试用的桩类。PackFinderBridge 只需要它上面有一个 WORLD 静态字段。 */
public final class PackSource {

    public static final PackSource WORLD = new PackSource("WORLD");
    public static final PackSource FEATURE = new PackSource("FEATURE");
    public static final PackSource BUILT_IN = new PackSource("BUILT_IN");

    private final String name;

    private PackSource(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
