package com.caf.lccustomcity.mixin;

import com.caf.lccustomcity.impl.CityCommandService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 游戏内命令 {@code /cafcity [距离上限]}: 报告"离我最近的城市在哪、有多远、我算不算在城里"。
 *
 * <h2>为什么是 Mixin 而不是 RegisterCommandsEvent</h2>
 * 同 {@code BuildStatsCommandMixin}: 本模组编译期拿不到 NeoForge 的事件类
 * ({@code net.neoforged.neoforge.event.RegisterCommandsEvent} 在 universal jar 里,
 * 而 build.ps1 给 mixin 组的 classpath 不含它), 所以改成<b>注入 Minecraft 自己的
 * {@code Commands} 构造函数</b>: 服务器每次构造命令表(每次进存档 / reload)时, 往 dispatcher 上
 * 挂一个自己的字面量节点。用到的全是 Minecraft + brigadier 的类, 都在 real-cp 上。
 *
 * <p>失落城市自己的命令也是服务端命令({@code Commands.CommandSelection} 建表时注册, 见
 * LostCities {@code commands/ModCommands.java}), 我们和它一样: 命令只在服务端的命令表里生效。
 *
 * <h2>命令名与权限</h2>
 * 命令名 {@code cafcity} 是纯 ASCII(brigadier 解析不带引号的字符串时只认
 * {@code [0-9A-Za-z_\-+.]}, 中文命令名敲不出来); 权限等级 2(OP), 和 {@code /cafbuildstats} 一致 ——
 * 这条命令会扫几十万个候选区块, 不能让普通玩家随手刷。
 *
 * <h2>参数</h2>
 * {@code /cafcity} = 默认 5000 格; {@code /cafcity 8000} = 搜索城心在 8000 格以内的城。
 * 参数用 {@code IntegerArgumentType} 而不是字符串: 它是 brigadier 内建类型, 命令表同步一定没问题
 * (字符串参数类型一旦没注册, 会让整棵命令树同步失败 —— 那才是真的会崩)。0 / 负数 / 超大数字
 * 都由 {@link CityCommandService} 里的回落逻辑变成"按默认值查"或"夹到上限", 不会报错。
 *
 * <h2>安全性</h2>
 * 注册过程整个包在 try/catch 里, 出错只记一行警告(最坏情况 = 没有这条命令, 游戏照常启动);
 * {@code require = 0} 保证注入点对不上时也只是"没有这条命令"。命令体只是"查一次 + 回话 + 写日志",
 * 不碰世界生成、不改任何存档数据。
 */
@Mixin(Commands.class)
public abstract class CityCommandMixin {

    private static final Logger CAF_LOGGER = LoggerFactory.getLogger("CAF-LC-CityCmd");

    /**
     * 命令名(纯 ASCII, 见类注释)。
     *
     * <p>⚠️ 必须是 {@code private}: Sponge Mixin 有硬性规定 —— mixin 类里的静态字段
     * 一律不能是 public/protected/包私有, 否则应用注入时直接抛
     * {@code InvalidMixinException: ... contains non-private static field ...},
     * 整个模组加载失败、游戏崩溃(1.0.40 就是这么崩的)。
     */
    private static final String COMMAND_NAME = "cafcity";

    /** 可选参数名(纯 ASCII, 和 brigadier 解析规则无关, 只是命令表里的键)。 */
    private static final String ARG_LIMIT = "limit";

    /**
     * {@code Commands.<init>(Commands$CommandSelection, CommandBuildContext)V} 返回时注册命令。
     *
     * <p>签名与真实构造函数逐字对齐(参数个数/类型必须完全一致, 否则 Mixin 会在应用注入时抛错),
     * 已用类文件反查确认过 1.21.1 就是这个签名。
     */
    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
            at = @At("RETURN"), require = 0)
    private void caf$registerCityCommand(Commands.CommandSelection selection,
                                         CommandBuildContext context,
                                         CallbackInfo ci) {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = ((Commands) (Object) this).getDispatcher();
            LiteralArgumentBuilder<CommandSourceStack> node = LiteralArgumentBuilder.literal(COMMAND_NAME);
            // 权限门槛: 只有 OP(权限等级 2) 能执行, 免得联机时任意玩家都让服务器扫几十万格
            node.requires(source -> source.hasPermission(2));
            // /cafcity —— 用默认距离上限
            node.executes(ctx -> {
                CityCommandService.report(ctx.getSource(), null);
                return 1;
            });
            // /cafcity <距离上限>
            node.then(Commands.argument(ARG_LIMIT, IntegerArgumentType.integer()).executes(ctx -> {
                // 交回"文本"入口, 让 0 / 负数 / 超大数字都走 CityLocator 里那套回落逻辑
                CityCommandService.report(ctx.getSource(),
                        Integer.toString(IntegerArgumentType.getInteger(ctx, ARG_LIMIT)));
                return 1;
            }));
            dispatcher.register(node);
            CAF_LOGGER.info("[CAF-LC] 已注册命令 /{} [距离上限] (报告最近的城市)", COMMAND_NAME);
        } catch (Throwable t) {
            // 注册失败不影响游戏: 最坏就是没有这条命令
            CAF_LOGGER.warn("[CAF-LC] 注册命令 /{} 失败(已忽略): {}", COMMAND_NAME, t.toString());
        }
    }
}
