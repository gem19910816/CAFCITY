package net.neoforged.neoforge.event;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * 离线测试用的桩类。真实类在 neoforge-universal.jar 里(编译期看不到, 所以 PackFinderBridge 用反射)。
 * 这里只需要 getPackType() 和 addRepositorySource(RepositorySource) 两个入口。
 */
public class AddPackFindersEvent {

    private final PackType packType;

    public RepositorySource added;

    public AddPackFindersEvent(PackType packType) {
        this.packType = packType;
    }

    public PackType getPackType() {
        return packType;
    }

    public void addRepositorySource(RepositorySource source) {
        this.added = source;
    }
}
