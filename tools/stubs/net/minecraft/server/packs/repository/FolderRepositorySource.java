package net.minecraft.server.packs.repository;

import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 离线测试用的桩类, 构造签名与真实类一致(从 ServerPacksSource 字节码读出):
 * {@code FolderRepositorySource(Path, PackType, PackSource, DirectoryValidator)}。
 */
public class FolderRepositorySource implements RepositorySource {

    public static int loadPacksCalls;
    public static Path lastRoot;
    public static PackType lastType;
    public static PackSource lastSource;
    public static Object lastValidator;

    private final Path root;

    public FolderRepositorySource(Path root, PackType type, PackSource source, DirectoryValidator validator) {
        this.root = root;
        lastRoot = root;
        lastType = type;
        lastSource = source;
        lastValidator = validator;
    }

    @Override
    public void loadPacks(Consumer<Object> packs) {
        loadPacksCalls++;
        // 真实实现会扫 root 下的子目录, 这里发一个假包表示"扫到了"
        packs.accept("fake-pack@" + root);
    }
}
