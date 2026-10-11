package net.minecraft.world.level.validation;

import java.nio.file.PathMatcher;

/** 离线测试用的桩类。真实构造签名是 DirectoryValidator(PathMatcher) —— 参数是纯 JDK 接口。 */
public class DirectoryValidator {

    private final PathMatcher matcher;

    public DirectoryValidator(PathMatcher matcher) {
        this.matcher = matcher;
    }

    public PathMatcher matcher() {
        return matcher;
    }
}
