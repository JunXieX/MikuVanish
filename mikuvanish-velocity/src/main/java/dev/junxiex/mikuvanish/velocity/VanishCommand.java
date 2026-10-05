package dev.junxiex.mikuvanish.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /vanish [玩家] [on|off]} —— 代理端隐身切换命令。
 */
final class VanishCommand implements SimpleCommand {

    private final MikuVanishVelocity plugin;
    private final ProxyServer proxy;

    VanishCommand(MikuVanishVelocity plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        Player target;

        if (args.length == 0) {
            if (!(invocation.source() instanceof Player self)) {
                invocation.source().sendMessage(Component.text("控制台请指定玩家。", NamedTextColor.RED));
                return;
            }
            target = self;
        } else {
            Optional<Player> found = proxy.getPlayer(args[0]);
            if (found.isEmpty()) {
                invocation.source().sendMessage(Component.text("玩家不存在: " + args[0], NamedTextColor.RED));
                return;
            }
            target = found.get();
            if (target != invocation.source() && !invocation.source().hasPermission("mikuvanish.others")) {
                invocation.source().sendMessage(Component.text("你没有操作他人的权限。", NamedTextColor.RED));
                return;
            }
        }

        boolean vanish = resolveVanish(args, target);

        plugin.applyAndBroadcast(target.getUniqueId(), target.getUsername(), vanish);

        String verb = vanish ? "隐身" : "现身";
        if (target == invocation.source()) {
            target.sendMessage(Component.text("你已" + verb + "。", NamedTextColor.GREEN));
        } else {
            invocation.source().sendMessage(Component.text(target.getUsername() + " 已" + verb + "。", NamedTextColor.GREEN));
            target.sendMessage(Component.text("管理员将你设为" + verb + "。", NamedTextColor.YELLOW));
        }
    }

    /**
     * 是否请求进入隐身：显式 {@code on/true} / {@code off/false}，
     * 其余输入（含非法值）按当前状态取反。
     */
    private boolean resolveVanish(String[] args, Player target) {
        if (args.length >= 2) {
            String mode = args[1].toLowerCase(Locale.ROOT);
            if (mode.equals("on") || mode.equals("true")) {
                return true;
            }
            if (mode.equals("off") || mode.equals("false")) {
                return false;
            }
        }
        return !plugin.isVanished(target.getUniqueId());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            // 无 mikuvanish.others 的玩家只能操作自己，因此只补全自己的名字，
            // 避免把全服玩家名单泄露给仅有 mikuvanish.vanish 的玩家。
            if (!invocation.source().hasPermission("mikuvanish.others")) {
                return invocation.source() instanceof Player self
                        ? List.of(self.getUsername())
                        : List.of();
            }
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> names = new ArrayList<>();
            for (Player p : proxy.getAllPlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(p.getUsername());
                    if (names.size() >= 40) {
                        break;
                    }
                }
            }
            return names;
        }
        if (args.length == 2) {
            return List.of("on", "off");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("mikuvanish.vanish");
    }
}
