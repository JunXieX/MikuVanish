package dev.junxiex.mikuvanish.paper;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.junxiex.mikuvanish.api.VisibilityPolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
     * 死亡重生后重新应用可见性与自身状态。
     *
     * <p>原版重生流程会新建一个玩家实体实例（状态从旧实例复制），因此挂在旧实体上的
     * 非持久状态会丢失，必须重新施加：{@code vanished} metadata（TAB/Essentials 依赖）、
     * {@code setCollidable(false)}（否则隐身者可被他人顶开）与 {@code setSleepingIgnored(true)}
     * （否则会阻止他人跳过夜晚）。观众一侧的隐藏关系按 UUID 记录、不随重生丢失，但仍一并
     * 重算，避免重生瞬间的重新追踪窗口期出现短暂可见。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
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

    /**
     * 过滤命令补全建议中的隐身玩家名，避免经 tab 补全暴露其存在。
     *
     * <p>{@code hidePlayer} 只移除玩家列表条目，不会清理命令补全（这是原版既定行为，
     * 见 MC-261798）：普通玩家输入 {@code /msg <tab>} 等仍能看到隐身者的名字，从而
     * 察觉其在线。本监听按「能否看见」的策略剔除这些建议，与其它可见性判定保持一致。</p>
     *
     * <p>线程：本事件默认在主线程同步触发（仅当有插件把 {@code AsyncTabCompleteEvent}
     * 标记为异步处理时才转异步，Folia 下则在 region 线程触发）。权限查询只在该玩家所属
     * region 线程执行（Paper 主线程 / Folia 其 region），否则跳过豁免判定：宁可少豁免，
     * 也不在非所属线程读权限（{@code PermissibleBase} 内部集合非线程安全）。过滤本身只依赖
     * 镜像里的用户名字符串，任意线程安全。</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onSuggestions(AsyncPlayerSendSuggestionsEvent event) {
        Player viewer = event.getPlayer();
        List<Suggestion> original = event.getSuggestions().getList();
        if (original.isEmpty()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(viewer) && viewer.hasPermission(VisibilityPolicy.SEE_BASE)) {
            return;
        }
        Set<String> vanished = plugin.manager().vanishedNames();
        if (vanished.isEmpty()) {
            return;
        }
        // 预先归一为小写集合，避免对每条建议重复做大小写不敏感比较（tab 补全每次按键都会触发）。
        Set<String> lowered = new HashSet<>(vanished.size() * 2);
        for (String name : vanished) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        String self = viewer.getName().toLowerCase(Locale.ROOT);
        List<Suggestion> kept = new ArrayList<>(original.size());
        for (Suggestion suggestion : original) {
            String text = suggestion.getText().toLowerCase(Locale.ROOT);
            // 自身名总是保留（隐身者本人查询自己），其余按隐身名单剔除。
            if (text.equals(self) || !lowered.contains(text)) {
                kept.add(suggestion);
            }
        }
        if (kept.size() == original.size()) {
            return;
        }
        event.setSuggestions(new Suggestions(event.getSuggestions().getRange(), kept));
    }
}
