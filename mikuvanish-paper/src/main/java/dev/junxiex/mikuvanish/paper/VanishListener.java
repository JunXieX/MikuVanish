package dev.junxiex.mikuvanish.paper;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;

/**
 * 玩家进出、世界切换与服务端列表事件处理。
 *
 * <p>隐身状态的权威在代理：玩家进服前代理会经 PreConnect 预推送隐身状态，
 * 因此后端在 {@code PlayerJoinEvent} 触发时已持有正确状态，依赖隐身状态的其它插件
 * （如消息抑制插件）可在此同步查询到准确结果。进出服消息的抑制本身不在本插件职责内。</p>
 */
final class VanishListener implements Listener {

    private final MikuVanishPaper plugin;

    VanishListener(MikuVanishPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 对加入者应用所有已知隐身者的可见性。
        plugin.manager().onJoin(player);
        // 若该玩家自身处于隐身（含重连恢复），补放其作为隐身目标的可见性与自身状态。
        plugin.manager().reapplyTarget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.manager().onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // 换世界后实体重新追踪，重新应用该玩家作为 target 的可见性。
        plugin.manager().reapplyTarget(event.getPlayer().getUniqueId());
    }

    /**
     * 服务端列表 ping（单机直连场景）：减去本服隐身玩家数，并从 sample 中移除隐身玩家。
     *
     * <p>代理网络下玩家查询的是 Velocity 的 ping（由代理端 {@code ProxyPingEvent} 处理），
     * 本监听仅覆盖后端被直接 ping 的情况。</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPing(ServerListPingEvent event) {
        int vanished = countVanishedOnline();
        if (vanished == 0) {
            return;
        }
        if (event instanceof PaperServerListPingEvent paper) {
            paper.setNumPlayers(Math.max(0, paper.getNumPlayers() - vanished));
            try {
                paper.getListedPlayers().removeIf(info -> plugin.manager().isVanished(info.id()));
            } catch (UnsupportedOperationException ignored) {
                // 某些实现返回不可变列表，忽略 sample 清理（人数已修正）。
            }
        }
    }

    private int countVanishedOnline() {
        int n = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.manager().isVanished(p.getUniqueId())) {
                n++;
            }
        }
        return n;
    }
}
