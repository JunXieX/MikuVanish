package dev.junxiex.mikuvanish.paper;

import dev.junxiex.mikuvanish.api.VanishState;
import dev.junxiex.mikuvanish.api.VisibilityPolicy;
import dev.junxiex.mikuvanish.paper.event.PlayerUnVanishEvent;
import dev.junxiex.mikuvanish.paper.event.PlayerVanishEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端隐身状态镜像与可见性应用核心。
 *
 * <p>状态以代理为权威，本类维护本地镜像 {@code mirror}（本服相关玩家，含重连期间
 * 保留的隐身记录），并在每次状态变化时把可见性应用到当前服务器的所有观众。</p>
 *
 * <p>线程模型：镜像读写基于 {@code ConcurrentHashMap}，任意线程安全；所有触碰玩家
 * 实体状态的调用（{@code hidePlayer/showPlayer}、{@code setCollidable}、
 * {@code setSleepingIgnored}、metadata）都通过该玩家自身的 {@code EntityScheduler}
 * 调度到其 region 线程，保证 Folia 正确、Paper 兼容。</p>
 */
final class VanishManager {

    private final MikuVanishPaper plugin;

    /** 本地状态镜像：uuid -> 状态（本服在线玩家，加上需要跨重连保留的隐身记录）。 */
    private final Map<UUID, VanishState> mirror = new ConcurrentHashMap<>();

    VanishManager(MikuVanishPaper plugin) {
        this.plugin = plugin;
    }

    Map<UUID, VanishState> mirror() {
        return mirror;
    }

    boolean isVanished(UUID id) {
        VanishState s = mirror.get(id);
        return s != null && s.vanished();
    }

    Set<UUID> vanishedPlayers() {
        Set<UUID> result = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player.getUniqueId())) {
                result.add(player.getUniqueId());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 当前处于隐身的玩家名字，已归一为小写（仅读镜像，不触碰任何实体状态）。
     *
     * <p>供异步场景使用（如 tab 补全过滤事件可能在异步线程触发）：只遍历
     * {@code ConcurrentHashMap} 并读取记录里的用户名字符串，不做 {@code Bukkit.getPlayer}
     * 等跨线程不安全的实体查询。补全过滤本身是大小写不敏感比较，故在此一次归一，
     * 避免调用方每次按键再建一份小写集合。</p>
     */
    Set<String> vanishedLowerNames() {
        Set<String> names = new HashSet<>();
        for (VanishState s : mirror.values()) {
            if (s.vanished() && !s.username().isEmpty()) {
                names.add(s.username().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * 接收状态更新（来自代理推送或本地切换）：先在调用线程立即写入镜像，
     * 再把「遍历在线玩家并应用可见性」交给全局 region 线程（见 {@link #runOnGlobalRegion}）。
     *
     * <p>镜像写入必须即时且发生在调用线程（{@code ConcurrentHashMap}，netty 线程安全）：
     * 代理的 PreConnect 预推送在玩家进服前到达，若把写入也推迟，依赖隐身状态的其它插件
     * （如消息抑制插件）在 {@code PlayerJoinEvent} 里将查不到正确结果。在线玩家查询、
     * 冗余记录清理与实体状态变更都交给 {@link #applyState} 在正确线程执行。</p>
     */
    void updateState(VanishState state) {
        VanishState previous = mirror.put(state.uuid(), state);
        runOnGlobalRegion(() -> applyState(state, previous));
    }

    /**
     * 在全局 region 线程评估状态变化：遍历在线玩家安全，但所有实体状态变更都经
     * {@link #runOnEntity} 落到该玩家所属线程（viewer 的 hide/show、target 的
     * metadata/碰撞/睡觉）。Folia 下为各自的 region 线程；Paper 下即主线程并直接内联，
     * 避免在错误的 region 线程触碰实体。
     */
    private void applyState(VanishState state, VanishState previous) {
        Player target = Bukkit.getPlayer(state.uuid());
        if (target == null) {
            // 代理会把每次状态变更广播给所有后端，本服因此会收到「从未到过本服」玩家的记录。
            // 非隐身记录没有任何用途（进服时 onJoin 会重建），确认离线后就地清理，
            // 避免镜像随网络规模与运行时长无谓增长；隐身记录必须保留，供重连恢复。
            if (!state.vanished()) {
                mirror.remove(state.uuid(), state);
            }
            return;
        }
        boolean wasVanished = previous != null && previous.vanished();
        boolean nowVanished = state.vanished();

        if (wasVanished != nowVanished) {
            // 隐身翻转：重新评估 target 对所有观众的可见性。
            applyTargetVisibility(state.uuid());
            // 自身状态（metadata / 碰撞 / 睡觉判定）只在翻转时变更，且归属 target 的 region。
            // 具体值在任务执行时按镜像重新读取：主线程内联执行可能插到已排队的旧任务前面，
            // 若沿用入队时的快照，晚执行的那个任务会把实体标记写回上一个状态且无人修正。
            runOnEntity(target, () -> {
                boolean vanished = isVanished(state.uuid());
                applyMetadata(target, vanished);
                target.setCollidable(!vanished);
                // 隐身者不应计入「全服睡觉」判定（否则其存在会阻止他人跳过夜晚）。
                target.setSleepingIgnored(vanished);
            });
        }
    }

    /** 玩家加入：若镜像无既有记录则建立占位，并应用「隐身者对该玩家不可见」。 */
    void onJoin(Player player) {
        mirror.putIfAbsent(player.getUniqueId(),
                new VanishState(player.getUniqueId(), player.getName(), false, "", System.currentTimeMillis()));
        applyViewerToAll(player);
    }

    /**
     * 玩家离开：清理镜像。
     *
     * <p>隐身记录保留：重连时代理会经 PreConnect 预推送恢复，保留记录可让
     * 推送到达前的窗口期（如 {@code PlayerQuitEvent} 时刻）依然能查到隐身状态；
     * 非隐身记录直接删除避免膨胀。</p>
     */
    void onQuit(Player player) {
        VanishState s = mirror.get(player.getUniqueId());
        if (s != null && s.vanished()) {
            return;
        }
        mirror.remove(player.getUniqueId());
    }

    /**
     * 重新应用某玩家作为隐身目标的可见性与自身状态（换世界/重生后实体重新追踪时调用）。
     *
     * <p>调用方可能是玩家所在 region 线程（如 {@code PlayerChangedWorldEvent}），而这里
     * 需要遍历在线玩家并读取权限，故统一切到全局 region 线程执行。</p>
     */
    void reapplyTarget(UUID targetId) {
        if (!isVanished(targetId)) {
            return;
        }
        runOnGlobalRegion(() -> {
            Player target = Bukkit.getPlayer(targetId);
            // 排队期间可能已解除隐身或离线：以镜像为准，避免把过期状态写回实体
            // （例如重生当帧执行 /vanish off，本任务晚一拍执行就会把人重新标成隐身）。
            if (target == null || !isVanished(targetId)) {
                return;
            }
            applyTargetVisibility(targetId);
            runOnEntity(target, () -> {
                // 重连后是新的 Player 对象，metadata 需重新设置（供 TAB/Essentials 识别）。
                applyMetadata(target, true);
                target.setCollidable(false);
                target.setSleepingIgnored(true);
            });
        });
    }

    /**
     * 插件禁用时恢复可见性与实体状态。
     *
     * <p>直接同步调用而非经调度器：禁用期间该插件的待执行任务会被调度器取消，
     * 只有立即执行才能保证恢复生效。{@code onDisable} 运行在主线程（Paper）/全局 region
     * （Folia 关服流程）。</p>
     *
     * <p>只处理镜像中处于隐身的玩家：{@code hidePlayer/showPlayer} 与 metadata 按插件
     * 归属隔离，解除「隐身者对观众」的隐藏关系（O(隐身数 × 在线数)）即可；而
     * {@code setCollidable}/{@code setSleepingIgnored} 是全局实体标志，仅重置我们自己
     * 设置过的隐身玩家，避免误伤其它插件对普通玩家的状态。</p>
     */
    @SuppressWarnings("deprecation")
    void restoreAll() {
        for (VanishState state : mirror.values()) {
            if (!state.vanished()) {
                continue;
            }
            Player target = Bukkit.getPlayer(state.uuid());
            if (target == null) {
                continue;
            }
            try {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!viewer.equals(target)) {
                        viewer.showPlayer(plugin, target);
                    }
                }
                if (target.hasMetadata("vanished")) {
                    target.removeMetadata("vanished", plugin);
                }
                target.setCollidable(true);
                target.setSleepingIgnored(false);
            } catch (RuntimeException e) {
                // 关服/停用流程中实体可能已被移除，跨 region 访问在 Folia 下也会抛异常。
                // 单个玩家恢复失败不应中断其余玩家与调用方的后续清理（注销通道、API）。
                plugin.getLogger().warning("[MikuVanish] 恢复 " + target.getName()
                        + " 的可见性失败：" + e.getMessage());
            }
        }
    }

    /**
     * 切换某玩家隐身（本地发起，由命令/API 在 tick 线程调用）。应用可见性并回报代理作为权威。
     *
     * <p>幂等：目标状态与当前一致时直接返回成功，不触发事件也不上报。</p>
     *
     * @return 是否成功达成目标状态（被 {@link PlayerVanishEvent} 取消时为 {@code false}）
     */
    boolean toggle(UUID targetId, boolean vanish) {
        if (isVanished(targetId) == vanish) {
            // 已处于目标状态：幂等返回成功，不重复触发事件、不重复上报代理。
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        VanishState current = mirror.get(targetId);
        String username = target != null ? target.getName() : (current != null ? current.username() : "");

        if (vanish && target != null) {
            PlayerVanishEvent event = new PlayerVanishEvent(target);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
        }

        VanishState state = new VanishState(targetId, username, vanish, "", System.currentTimeMillis());
        // updateState 立即写镜像并把实体应用调度到全局 region，兼容 Paper 与 Folia
        // （命令线程在 Folia 下为玩家 region，不能直接遍历在线玩家）。
        updateState(state);
        if (!vanish && target != null) {
            Bukkit.getPluginManager().callEvent(new PlayerUnVanishEvent(target));
        }
        // 回报代理作为权威（代理盖写 serverId 后广播全网，本服的重复回推为幂等空操作）。
        plugin.sync().reportState(state);
        return true;
    }

    /**
     * 重新评估 target 对所有在线观众的可见性。
     *
     * <p>权限判定与 hide/show 一并落到 viewer 所属线程执行（Folia 为其 region，
     * Paper 即主线程内联）：既避免跨 region 读取其它玩家的权限状态，也保证实体操作
     * 在正确线程。</p>
     */
    private void applyTargetVisibility(UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            return;
        }
        VanishState state = mirror.get(targetId);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(targetId)) {
                continue;
            }
            runOnEntity(viewer, () -> {
                boolean visible = VisibilityPolicy.canSee(viewer::hasPermission, viewer.getUniqueId(), state);
                if (visible) {
                    viewer.showPlayer(plugin, target);
                } else {
                    viewer.hidePlayer(plugin, target);
                }
            });
        }
    }

    /**
     * 对所有在线隐身目标，一次性应用到指定观众（观众加入时调用）。
     *
     * <p>收集所有 (target, state) 后在观众所属线程一次性完成权限判定与 hide/show
     * （Folia 只排一个任务；Paper 直接内联），避免每个目标各排一个任务的调度开销。</p>
     */
    private void applyViewerToAll(Player viewer) {
        record Entry(Player target, VanishState state) {
        }
        List<Entry> entries = new ArrayList<>();
        for (VanishState state : mirror.values()) {
            if (!state.vanished() || state.uuid().equals(viewer.getUniqueId())) {
                continue;
            }
            Player target = Bukkit.getPlayer(state.uuid());
            if (target == null) {
                continue;
            }
            entries.add(new Entry(target, state));
        }
        if (entries.isEmpty()) {
            return;
        }
        runOnEntity(viewer, () -> {
            for (Entry e : entries) {
                boolean visible = VisibilityPolicy.canSee(viewer::hasPermission, viewer.getUniqueId(), e.state());
                if (visible) {
                    viewer.showPlayer(plugin, e.target());
                } else {
                    viewer.hidePlayer(plugin, e.target());
                }
            }
        });
    }

    /**
     * 维护 {@code vanished} metadata（SuperVanish 约定，供 TAB/Essentials 等识别）。
     *
     * <p>隐身时置为 {@code true}，解除时<b>移除</b>而非置 false：TAB 的
     * {@code BukkitTabPlayer.isVanished0()} 读取值的布尔语义，两种写法等效；但部分插件
     * （如 EssentialsX）仅判断该键是否存在（{@code hasMetadata}），置 false 会被误判为
     * 仍在隐身。移除只影响本插件写入的值，不会干扰其它插件的同名 metadata。</p>
     *
     * <p>Paper 已弃用整个 metadata 系统，但 TAB 硬编码读取该键，为保持兼容必须使用它。
     * 本插件对 metadata 的生命周期完全可控（隐身时设置、解除/禁用时清理），不存在弃用
     * 警告所述的泄漏问题。</p>
     */
    @SuppressWarnings("deprecation")
    private void applyMetadata(Player target, boolean vanished) {
        if (vanished) {
            target.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        } else {
            target.removeMetadata("vanished", plugin);
        }
    }

    /**
     * 在全局 region 线程执行（可安全遍历在线玩家、读取权限）。
     *
     * <p>Paper 下全局 region 就是主线程，已在主线程时直接内联：{@code GlobalRegionScheduler}
     * 即使从主线程提交也会排到下一 tick，白白推迟一次可见性应用——切换后目标会多可见一 tick，
     * 隐身者进服时已在线玩家会先看到一次 tab 条目再被移除。Folia 与异步线程照常调度。</p>
     */
    private void runOnGlobalRegion(Runnable operation) {
        if (!MikuVanishPaper.FOLIA && Bukkit.isPrimaryThread()) {
            // 内联执行绕过了调度器的「插件已停用则拒绝」检查，这里自己挡一下：
            // onDisable 已统一恢复可见性，停用后再应用会把玩家重新隐藏。
            if (plugin.isEnabled()) {
                operation.run();
            }
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, operation);
        } catch (IllegalPluginAccessException e) {
            // 停用瞬间调度器会拒绝新任务。代理的广播仍可能在这时经 netty 到达本服，
            // 此时无需再应用可见性（onDisable 已统一恢复），静默丢弃即可。
            plugin.getLogger().fine("[MikuVanish] 插件停用期间的状态更新已忽略。");
        }
    }

    /**
     * 在指定玩家所属线程执行操作（Paper/Folia 通用）。
     *
     * <p><b>Folia</b>：经实体调度器排入该玩家的 region 队列（delay 小于 1 按 1 处理，
     * 最迟下一 tick 执行；实体在任务执行前被移除则自动跳过）。{@code hidePlayer(viewer, target)}
     * 修改的是 viewer 的隐藏集合（归属 viewer 的 region），故调度到被操作玩家自身是安全的。</p>
     *
     * <p><b>Paper</b>：调用方本就运行在主线程（全局 region 调度器即主线程、join 事件即主线程），
     * 直接内联执行，省去「每个观众各排一个调度任务」的对象分配与入队开销——该开销在隐身切换时
     * 按在线人数线性增长，是多人规模下可见性应用的主要成本。</p>
     */
    private void runOnEntity(Player entity, Runnable operation) {
        if (MikuVanishPaper.FOLIA) {
            entity.getScheduler().execute(plugin, operation, null, 0L);
        } else {
            operation.run();
        }
    }
}
