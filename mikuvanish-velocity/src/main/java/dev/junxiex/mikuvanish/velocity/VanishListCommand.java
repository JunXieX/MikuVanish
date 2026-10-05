package dev.junxiex.mikuvanish.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import dev.junxiex.mikuvanish.api.VanishState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/**
 * {@code /vanishlist} —— 列出当前隐身玩家。
 */
final class VanishListCommand implements SimpleCommand {

    private final MikuVanishVelocity plugin;

    VanishListCommand(MikuVanishVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        List<VanishState> vanished = plugin.proxy().getAllPlayers().stream()
                .map(p -> plugin.getState(p.getUniqueId()))
                .filter(s -> s != null && s.vanished())
                .toList();

        if (vanished.isEmpty()) {
            invocation.source().sendMessage(Component.text("当前没有隐身玩家。", NamedTextColor.GRAY));
            return;
        }

        invocation.source().sendMessage(Component.text("隐身玩家 (" + vanished.size() + "):", NamedTextColor.GOLD));
        for (VanishState s : vanished) {
            invocation.source().sendMessage(
                    Component.text(" - " + s.username(), NamedTextColor.YELLOW)
                            .append(Component.text(" @ " + s.serverId(), NamedTextColor.DARK_GRAY))
            );
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("mikuvanish.list");
    }
}
