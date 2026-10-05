package dev.junxiex.mikuvanish.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /vanish [玩家] [on|off]} —— Brigadier 命令树（Paper 原生命令系统）。
 *
 * <p>权限由命令树自身的 {@code requires} 判定（{@code mikuvanish.vanish} 用于自身、
 * {@code mikuvanish.others} 用于指定他人），玩家参数直接使用原版玩家选择器，
 * 客户端因此自带语法校验与补全，无需手写 TabCompleter。</p>
 */
final class VanishCommand {

    private final MikuVanishPaper plugin;

    VanishCommand(MikuVanishPaper plugin) {
        this.plugin = plugin;
    }

    /** 构建命令树（由生命周期 COMMANDS 事件注册）。 */
    LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("vanish")
                .requires(source -> source.getSender().hasPermission("mikuvanish.vanish"))
                // /vanish —— 切换自己
                .executes(ctx -> apply(ctx, null, null))
                .then(Commands.argument("player", ArgumentTypes.players())
                        .requires(source -> source.getSender().hasPermission("mikuvanish.others"))
                        // /vanish <玩家> —— 切换该玩家
                        .executes(ctx -> apply(ctx, selector(ctx), null))
                        .then(Commands.literal("on")
                                .executes(ctx -> apply(ctx, selector(ctx), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> apply(ctx, selector(ctx), false))))
                .build();
    }

    private static PlayerSelectorArgumentResolver selector(CommandContext<CommandSourceStack> ctx) {
        return ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
    }

    /**
     * 执行切换。
     *
     * @param resolver 目标玩家选择器；{@code null} 表示操作执行者自己
     * @param mode     显式模式；{@code null} 表示按当前状态取反
     */
    private int apply(CommandContext<CommandSourceStack> ctx, PlayerSelectorArgumentResolver resolver, Boolean mode)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();

        Player target;
        if (resolver == null) {
            // 无参形式只能由玩家执行（控制台没有对应实体）。
            if (!(source.getExecutor() instanceof Player self)) {
                sender.sendMessage(Component.text("控制台请指定玩家。", NamedTextColor.RED));
                return 0;
            }
            target = self;
        } else {
            List<Player> players = resolver.resolve(source);
            // 选择器支持 @a 等多目标写法，但本命令一次只处理一人，避免手滑隐掉整服。
            if (players.size() != 1) {
                sender.sendMessage(players.isEmpty()
                        ? Component.text("找不到该玩家。", NamedTextColor.RED)
                        : Component.text("请只指定一个玩家。", NamedTextColor.RED));
                return 0;
            }
            target = players.getFirst();
        }

        boolean vanish = mode != null ? mode : !plugin.isVanished(target.getUniqueId());
        if (!plugin.manager().toggle(target.getUniqueId(), vanish)) {
            sender.sendMessage(Component.text("切换被其它插件取消。", NamedTextColor.RED));
            return 0;
        }

        String verb = vanish ? "隐身" : "现身";
        if (target.equals(sender)) {
            target.sendMessage(Component.text("你已" + verb + "。", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(target.getName() + " 已" + verb + "。", NamedTextColor.GREEN));
            target.sendMessage(Component.text("管理员将你设为" + verb + "。", NamedTextColor.YELLOW));
        }
        return Command.SINGLE_SUCCESS;
    }
}