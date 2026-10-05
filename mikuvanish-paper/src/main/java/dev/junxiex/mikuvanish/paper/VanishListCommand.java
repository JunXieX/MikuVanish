package dev.junxiex.mikuvanish.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.junxiex.mikuvanish.api.VanishState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.UUID;

/**
 * {@code /vanishlist} —— 列出本服务器当前隐身玩家（Brigadier 命令树）。
 */
final class VanishListCommand {

    private final MikuVanishPaper plugin;

    VanishListCommand(MikuVanishPaper plugin) {
        this.plugin = plugin;
    }

    /** 构建命令树（由生命周期 COMMANDS 事件注册）。 */
    LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("vanishlist")
                .requires(source -> source.getSender().hasPermission("mikuvanish.list"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    int count = 0;
                    StringBuilder sb = new StringBuilder();
                    for (UUID id : plugin.manager().vanishedPlayers()) {
                        VanishState s = plugin.manager().mirror().get(id);
                        if (s == null) {
                            continue;
                        }
                        count++;
                        sb.append("\n - ").append(s.username());
                    }
                    if (count == 0) {
                        sender.sendMessage(Component.text("当前没有隐身玩家。", NamedTextColor.GRAY));
                    } else {
                        sender.sendMessage(Component.text("隐身玩家 (" + count + "):", NamedTextColor.GOLD)
                                .append(Component.text(sb.toString(), NamedTextColor.YELLOW)));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}