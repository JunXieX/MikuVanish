package dev.junxiex.mikuvanish.paper;

import dev.junxiex.mikuvanish.api.SyncProtocol;
import dev.junxiex.mikuvanish.api.VanishState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;

/**
 * 后端与代理之间的 plugin messaging 桥接。
 *
 * <p>插件启用时发送一次 HELLO 握手拉取全量隐身快照（覆盖 reload 时已有玩家在线的场景）；
 * 玩家进服前代理经 PreConnect 预推送其隐身状态，进服后经 PostConnect 补全；
 * 增量变更通过 {@code OP_STATE_UPDATE} 双向流动。</p>
 */
final class SyncBridge implements PluginMessageListener {

    private final MikuVanishPaper plugin;
    private final VanishManager manager;

    SyncBridge(MikuVanishPaper plugin, VanishManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, this);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SyncProtocol.CHANNEL);
    }

    /** 向代理握手，请求全量隐身快照。Bukkit 的出口必须借用一个在线玩家；无人在线时跳过。 */
    void sendHello() {
        Player any = routePlayer(null);
        if (any != null) {
            any.sendPluginMessage(plugin, SyncProtocol.CHANNEL,
                    SyncProtocol.encode(SyncProtocol.Message.hello("")));
        }
    }

    /** 后端本地切换后回报代理。优先用目标玩家作为出口（保证消息一定可达）。 */
    void reportState(VanishState state) {
        // 调用方可能是玩家 region 线程（Folia 命令），遍历在线玩家需切到全局 region。
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Player any = routePlayer(state.uuid());
            if (any == null) {
                // Bukkit 没有"服务端 -> 代理"的出口，必须借用一个在线玩家；
                // 一个都没有时消息发不出去，记一条警告便于排查（代理不会收到本次变更）。
                plugin.getLogger().warning("[MikuVanish] 无在线玩家可用于上报隐身状态，本次变更未同步到代理。");
                return;
            }
            any.sendPluginMessage(plugin, SyncProtocol.CHANNEL,
                    SyncProtocol.encode(SyncProtocol.Message.stateUpdate(state)));
            // 仅走 fine：管理员频繁切换隐身时不刷屏（默认不输出，服务端可自行开启调试）。
            plugin.getLogger().fine("[MikuVanish] 已上报状态到代理："
                    + (state.username().isEmpty() ? state.uuid() : state.username())
                    + (state.vanished() ? " 进入隐身" : " 解除隐身"));
        });
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SyncProtocol.CHANNEL.equals(channel)) {
            return;
        }
        final SyncProtocol.Message msg;
        try {
            msg = SyncProtocol.decode(message);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[MikuVanish] 无法解码代理消息: " + e.getMessage());
            return;
        }
        switch (msg.op()) {
            case SyncProtocol.OP_STATE_UPDATE -> {
                // 本回调运行在 netty 线程。updateState 自身即线程安全：镜像写入在调用线程
                // 立即完成（ConcurrentHashMap），实体状态变更由其调度到正确 region 线程。
                // 切勿在外层再包一层调度——那会把镜像写入推迟，破坏 PreConnect 预推送
                // 「进服前同步可见」的时序保证。
                manager.updateState(msg.state());
            }
            case SyncProtocol.OP_SYNC_COMPLETE ->
                    plugin.getLogger().info("[MikuVanish] 已与代理完成隐身状态同步。");
            default -> {
                // 其它操作码由代理接收，后端忽略。
            }
        }
    }

    /** 选择 plugin messaging 出口玩家：优先目标玩家，其次任意在线玩家。 */
    private Player routePlayer(UUID preferred) {
        if (preferred != null) {
            Player p = Bukkit.getPlayer(preferred);
            if (p != null) {
                return p;
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            return p;
        }
        return null;
    }
}
