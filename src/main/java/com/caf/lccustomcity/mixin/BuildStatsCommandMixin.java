package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.stats.BuildingStats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * 游戏内命令 {@code /cafbuildstats}: 立刻整理一次建筑生成次数统计并写文件, 在聊天栏回一句路径。
 *
 * <h2>为什么是 Mixin 而不是 RegisterCommandsEvent</h2>
 * 同 {@link ServerStopStatsMixin}: 本模组编译期拿不到 NeoForge 的事件类
 * ({@code net.neoforged.neoforge.event.RegisterCommandsEvent} 在 universal jar 里,
 * 而 build.ps1 给 mixin 组的 classpath 不含它), 所以改成<b>注入 Minecraft 自己的
 * {@code Commands} 构造函数</b>: 服务器每次构造命令表(也就是每次进存档 / reload)时,
 * 往 dispatcher 上挂一个自己的字面量节点。用到的全是 Minecraft + brigadier 的类, 都在
 * real-cp 上, 编译无碍。
 *
 * <h2>为什么命令名是全 ASCII 的 cafbuildstats</h2>
 * brigadier 解析"不带引号的字符串"时只认 {@code [0-9A-Za-z_\-+.]}, 中文参数根本读不进来,
 * 所以 {@code /caf建筑统计} 敲不出来, 只能用 {@code /cafbuildstats}(玩家给的备选名字)。
 *
 * <h2>安全性</h2>
 * 整个注册过程包在 try/catch 里, 出错只记一行警告; {@code require = 0} 保证注入点对不上时
 * 只是"没有这条命令", 不会让游戏起不来。命令体本身也只是"整理一次 + 写文件 + 回一句话",
 * 不碰世界生成。
 */
@Mixin(Commands.class)
public abstract class BuildStatsCommandMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-StatsCmd");

    /**
     * 命令名(纯 ASCII, 见类注释)。
     *
     * <p>⚠️ 必须是 {@code private}: Sponge Mixin 有硬性规定 —— mixin 类里的静态字段
     * 一律不能是 public/protected/包私有, 否则应用注入时直接抛
     * {@code InvalidMixinException: ... contains non-private static field ...},
     * 整个模组加载失败、游戏崩溃(1.0.40 就是这么崩的)。
     */
    private static final String COMMAND_NAME = "cafbuildstats";

    /**
     * {@code Commands.<init>(Commands$CommandSelection, CommandBuildContext)V} 返回时注册命令。
     *
     * <p>签名与真实构造函数逐字对齐(参数个数/类型必须完全一致, 否则 Mixin 会在应用注入时抛错),
     * 已用类文件反查确认过 1.21.1 就是这个签名。
     */
    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
            at = @At("RETURN"), require = 0)
    private void caf$registerBuildStatsCommand(Commands.CommandSelection selection,
                                               CommandBuildContext context,
                                               CallbackInfo ci) {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = ((Commands) (Object) this).getDispatcher();
            LiteralArgumentBuilder<CommandSourceStack> node = LiteralArgumentBuilder.literal(COMMAND_NAME);
            // 权限门槛: 只有 OP(权限等级 2) 能执行, 免得联机时任意玩家都能让服务器写文件
            node.requires(source -> source.hasPermission(2));
            node.executes(ctx -> {
                Path file = BuildingStats.export("游戏内命令");
                ctx.getSource().sendSystemMessage(Component.literal(file == null
                        ? "[CAF-LC] 建筑生成统计导出失败(详见日志)"
                        : "[CAF-LC] 建筑生成统计已导出到 " + file));
                return 1;
            });
            dispatcher.register(node);
            CAF_LOGGER.info("[CAF-LC] 已注册命令 /{} (整理并导出建筑生成统计)", COMMAND_NAME);
        } catch (Throwable t) {
            // 注册失败不影响游戏: 退出存档时还有 ServerStopStatsMixin 与关闭钩子两条路
            CAF_LOGGER.warn("[CAF-LC] 注册命令 /{} 失败(已忽略): {}", COMMAND_NAME, t.toString());
        }
    }
}
