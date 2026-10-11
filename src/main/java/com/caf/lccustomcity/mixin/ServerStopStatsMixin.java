package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.stats.BuildingStats;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 退出存档 / 服务器停止时自动导出"建筑生成次数统计"。
 *
 * <h2>为什么用 Mixin 而不是 ServerStoppingEvent</h2>
 * 玩家要求的是 {@code ServerStoppingEvent} 那类 NeoForge 事件, 但本模组<b>编译期看不见
 * NeoForge 的类</b>: build.ps1 给 mixin/impl 组用的 classpath 是 4 个基础 jar + Mixin +
 * 失落城市 jar + build\real-cp.txt, 而 real-cp.txt 来自版本 JSON 与 srg 客户端 jar,
 * <b>不含 neoforge-*-universal.jar</b>(事件类都在里面)。写
 * {@code import net.neoforged.neoforge.event.server.ServerStoppingEvent} 会直接编译失败。
 * 退而求其次:<b>注入 Minecraft 自己的 {@code MinecraftServer.stopServer()}</b> ——
 * 它正是"退出存档 / 关闭服务器"时一定会走的那一步, 而且 Minecraft 的类本来就在
 * real-cp 上, 编译没问题。{@code require = 0} 保证万一方法名对不上也只是不生效, 不会崩。
 * 另有 JVM 关闭钩子兜底(见 BuildingStats.installShutdownHook), 关游戏一定写得上。
 *
 * <h2>成本</h2>
 * 一局游戏只跑一次(退出存档时), 整理一次全表并写一个文本文件, 与跑图时的性能无关。
 */
@Mixin(MinecraftServer.class)
public abstract class ServerStopStatsMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-StatsMixin");

    /**
     * {@code MinecraftServer.stopServer()V}(无参数)返回时导出统计。
     *
     * <p>里面再包一层 try/catch: BuildingStats.export 自己已经吞异常了, 这里只是双保险 ——
     * 退出存档这条路绝不能因为统计而出问题。
     *
     * <p>⚠️ {@code method} 写成<b>完整签名</b>而不是只写方法名: 只写名字时, 万一以后
     * Minecraft/NeoForge 给 MinecraftServer 加了同名重载, Mixin 会挑错重载并在运行期抛
     * {@code InvalidInjectionException}(本模组历史上就这样崩过一次)。注意
     * <b>{@code require = 0} 只能防"找不到方法", 防不了"描述符写错/参数不匹配"</b> ——
     * 那类校验发生在已经找到目标之后, 与 require 无关。已用类文件反查确认 1.21.1
     * 的 {@code MinecraftServer} 里 stopServer 只有 {@code ()V} 这一个。
     */
    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 0)
    private void caf$exportBuildingStats(CallbackInfo ci) {
        try {
            BuildingStats.export("退出存档/服务器停止");
            com.caf.lccustomcity.impl.CityLayout.clear();
            com.caf.lccustomcity.impl.SpawnCityRule.clear();
        } catch (Throwable t) {
            CAF_LOGGER.warn("[CAF-LC] 导出建筑生成统计失败(已忽略): {}", t.toString());
        }
    }
}
