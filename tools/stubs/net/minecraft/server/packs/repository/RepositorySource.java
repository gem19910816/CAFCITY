package net.minecraft.server.packs.repository;

import java.util.function.Consumer;

/** 离线测试用的桩类。真实签名是 loadPacks(Consumer&lt;Pack&gt;), 反射只看到 Consumer。 */
public interface RepositorySource {

    void loadPacks(Consumer<Object> packs);
}
