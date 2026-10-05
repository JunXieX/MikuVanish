package dev.junxiex.mikuvanish.velocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import dev.junxiex.mikuvanish.api.MikuVanishAPI;
import dev.junxiex.mikuvanish.api.SyncProtocol;
import dev.junxiex.mikuvanish.api.VanishService;
import dev.junxiex.mikuvanish.api.VanishState;
import dev.junxiex.mikuvanish.api.VanishStateChangeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MikuVanish 代理端插件：作为隐身状态的权威源。
 *
 * <p>职责：维护全网络隐身状态、持久化到本地 JSON（异步写盘）、通过 plugin messaging
 * 与后端同步、处理 {@code /vanish} 命令。</p>
 */
@Plugin(
        id = "mikuvanish",
        name = "MikuVanish",
        // 版本号单一来源：BuildInfo 由 Gradle 依 gradle.properties 生成（static final 字面量，
        // 满足注解的编译期常量要求），不再手写，避免与 Gradle 版本号两处漂移。
        version = BuildInfo.VERSION,
        description = "Cross-server vanish with tab hiding (Velocity authority).",
        authors = {"JunXieX"}
)
public final class MikuVanishVelocity implements VanishService {

    static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("mikuvanish", "sync");

    /** 脏标记：状态变更后置位，由周期任务落盘，避免在事件线程同步写文件。 */
    private final AtomicBoolean dirty = new AtomicBoolean();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final VelocityConfig config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** 权威状态表：uuid -> 状态。 */
    private final Map<UUID, VanishState> states = new ConcurrentHashMap<>();

    @Inject
    public MikuVanishVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = resolveDataDir(dataDir);
        this.config = VelocityConfig.load(this.dataDir, logger);
    }

    /**
     * 取大小写正确的数据目录 {@code plugins/MikuVanish}。
     *
     * <p>Velocity 注入的 {@code @DataDirectory} 是 {@code plugins/<插件 id>}（见
     * {@code VelocityPluginModule} 中 {@code basePluginPath.resolve(description.getId())}），
     * 而插件 id 受 Velocity 校验约束只能是 {@code [a-z][a-z0-9-_]{0,63}}——即全小写，
     * 无法写成 {@code MikuVanish}（且 id 需保持 {@code mikuvanish} 以匹配其它插件的依赖声明）。
     * 因此这里改用注入路径的<b>同级目录</b>，既得到正确大小写，又不依赖进程工作目录。</p>
     */
    private static Path resolveDataDir(Path injected) {
        Path parent = injected.getParent();
        return parent == null ? injected : parent.resolve("MikuVanish");
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        load();
        MikuVanishAPI.register(this);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("vanish")
                        .aliases("v", "vs")
                        .plugin(this)
                        .build(),
                new VanishCommand(this, proxy));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("vanishlist")
                        .aliases("vlist")
                        .plugin(this)
                        .build(),
                new VanishListCommand(this));
        // 按配置间隔检查脏标记并异步落盘。
        proxy.getScheduler().buildTask(this, this::saveIfDirty)
                .repeat(config.saveIntervalSeconds(), TimeUnit.SECONDS)
                .schedule();
        logger.info("[MikuVanish] 代理端已加载，恢复隐身玩家 {} 名。", getVanishedPlayers().size());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        MikuVanishAPI.unregister();
        saveIfDirty();
    }

    // ---- 事件：玩家跨服 / 上下线 ----

    /**
     * 玩家即将进入后端时提前推送其隐身状态。
     *
     * <p>在连接建立前推送，保证后端在 {@code PlayerJoinEvent} 触发时已持有该玩家的
     * 正确隐身状态——依赖本插件隐身状态的其它插件（如消息抑制插件）因此能在 join
     * 事件里同步查询到准确结果。</p>
     *
     * <p>目标服务器取 {@code getResult().getServer()} 而非 {@code getOriginalServer()}：
     * 选服/大厅等插件可在本事件里重定向连接，只有 result 里才是玩家真正要进入的服务器。
     * 使用原始服务器会把状态推给一个玩家不会进入的服务器，导致目标后端在 join 时拿不到
     * 隐身状态，破坏上述时序保证。</p>
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            // 连接被拒绝（denied），玩家不会进入任何后端。
            return;
        }
        Player player = event.getPlayer();
        VanishState state = states.get(player.getUniqueId());
        if (state != null && state.vanished()) {
            sendToServer(target, SyncProtocol.Message.stateUpdate(state));
        }
    }

    /**
     * 玩家完成后端连接后推送其状态。
     *
     * <p>使用 {@link ServerPostConnectEvent}（后端 join 流程已结束）而非
     * ServerConnectedEvent，保证后端镜像先有玩家记录、推送到达时立即生效。</p>
     */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = player.getCurrentServer()
                .map(sc -> sc.getServer())
                .orElse(null);
        if (server == null) {
            return;
        }
        String serverId = server.getServerInfo().getName();
        VanishState state = states.get(player.getUniqueId());
        if (state == null) {
            // 首次进入网络：建立非隐身记录。
            state = new VanishState(player.getUniqueId(), player.getUsername(), false, serverId, System.currentTimeMillis());
            states.put(player.getUniqueId(), state);
        } else if (!serverId.equals(state.serverId())) {
            // 跨服移动：更新所在服务器并广播。
            state = state.withServer(serverId);
            states.put(player.getUniqueId(), state);
            broadcastState(state);
        }
        // 向新后端推送该玩家当前状态（无论是否隐身，保证后端镜像一致）。
        sendToServer(server, SyncProtocol.Message.stateUpdate(state));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        VanishState state = states.get(id);
        // 保留隐身记录（代理重启/玩家重连后恢复隐身）；仅清除非隐身记录避免膨胀。
        if (state != null && !state.vanished()) {
            states.remove(id);
        }
    }

    // ---- 事件：服务端列表 ping ----

    /**
     * 代理网络下玩家查询的是 Velocity 的 ping（后端 PaperServerListPingEvent 不生效），
     * 因此人数与 sample 的隐身修正必须在这里做。
     */
    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (!config.pingHideVanished()) {
            return;
        }
        ServerPing ping = event.getPing();
        ServerPing.Players players = ping.getPlayers().orElse(null);
        if (players == null) {
            return;
        }
        int vanished = countVanishedOnline();
        if (vanished == 0) {
            return;
        }
        ServerPing.Builder builder = ping.asBuilder()
                .onlinePlayers(Math.max(0, players.getOnline() - vanished));
        List<ServerPing.SamplePlayer> sample = players.getSample();
        if (sample != null && !sample.isEmpty()) {
            builder.samplePlayers(sample.stream()
                    .filter(s -> !isVanished(s.getId()))
                    .toList());
        }
        event.setPing(builder.build());
    }

    private int countVanishedOnline() {
        int n = 0;
        for (Player p : proxy.getAllPlayers()) {
            if (isVanished(p.getUniqueId())) {
                n++;
            }
        }
        return n;
    }

    // ---- 事件：plugin messaging ----

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        // 本通道消息由代理全权处理，标记为已处理，避免 Velocity 再转发给后端造成回环。
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        // 只接受后端上报。
        // Velocity 4.x 对「后端 -> 代理」的消息，source 是 {@link ServerConnection}
        // （内部实现类 VelocityServerConnection），**不是** RegisteredServer；
        // 只有「客户端 -> 代理」的消息 source 才是 Player（客户端可自行发送本通道，直接丢弃）。
        if (!(event.getSource() instanceof ServerConnection sourceConnection)) {
            return;
        }
        RegisteredServer sourceServer = sourceConnection.getServer();
        String serverId = sourceServer.getServerInfo().getName();

        SyncProtocol.Message msg;
        try {
            msg = SyncProtocol.decode(event.getData());
        } catch (RuntimeException e) {
            logger.warn("[MikuVanish] 无法解码来自 {} 的消息: {}", serverId, e.getMessage());
            return;
        }
        switch (msg.op()) {
            case SyncProtocol.OP_HELLO -> {
                // 后端上线握手：推送全量隐身快照。
                for (VanishState s : states.values()) {
                    if (s.vanished()) {
                        sendToServer(sourceServer, SyncProtocol.Message.stateUpdate(s));
                    }
                }
                sendToServer(sourceServer, SyncProtocol.Message.syncComplete(serverId));
            }
            case SyncProtocol.OP_STATE_UPDATE -> {
                // 后端上报的本地切换：以代理为权威盖写 serverId 后存储、广播并发布事件。
                applyState(msg.state().withServer(serverId));
            }
            default -> {
                // 其它操作码由后端接收，代理忽略。
            }
        }
    }

    // ---- 命令调用入口 ----

    /**
     * 统一的状态落库 + 广播入口（所有权威变更路径都经此）：
     * vanished 翻转时发布 {@link VanishStateChangeEvent}。
     */
    private void applyState(VanishState state) {
        VanishState previous = states.put(state.uuid(), state);
        dirty.set(true);
        broadcastState(state);
        // 无既有记录视为「此前未隐身」：否则首个状态就为隐身时（如对离线玩家预设隐身、
        // 后端上报先于任何本地记录到达）翻转会被漏报，依赖事件的插件收不到通知。
        boolean wasVanished = previous != null && previous.vanished();
        if (wasVanished != state.vanished()) {
            // 仅状态翻转时记录，且用 debug：管理员频繁切换时不刷屏（默认不输出）。
            logger.debug("[MikuVanish] 状态翻转：{} {}（{}）",
                    state.username().isEmpty() ? state.uuid() : state.username(),
                    state.vanished() ? "进入隐身" : "解除隐身",
                    state.serverId().isEmpty() ? "未知服务器" : state.serverId());
            proxy.getEventManager().fireAndForget(new VanishStateChangeEvent(
                    state.uuid(), state.username(), state.vanished(), state.serverId()));
        }
    }

    /** 直接（代理命令）切换某在线玩家状态并广播。 */
    public void applyAndBroadcast(UUID target, String username, boolean vanish) {
        String serverId = proxy.getPlayer(target)
                .flatMap(p -> p.getCurrentServer())
                .map(sc -> sc.getServer().getServerInfo().getName())
                .orElse("");
        applyState(new VanishState(target, username, vanish, serverId, System.currentTimeMillis()));
    }

    /** 广播单个状态变更到所有后端。 */
    private void broadcastState(VanishState state) {
        SyncProtocol.Message msg = SyncProtocol.Message.stateUpdate(state);
        for (RegisteredServer server : proxy.getAllServers()) {
            sendToServer(server, msg);
        }
    }

    private void sendToServer(RegisteredServer server, SyncProtocol.Message msg) {
        if (!server.sendPluginMessage(CHANNEL, SyncProtocol.encode(msg))) {
            // Velocity 的插件消息必须借道一条玩家连接，目标服务器当前无人在线时会被静默丢弃。
            // 记录到 debug 便于排查；该服上的玩家会在接入时由 PostConnect 补推获得正确状态。
            logger.debug("[MikuVanish] {} 当前无在线玩家，本次消息未送达", server.getServerInfo().getName());
        }
    }

    // ---- VanishService 实现 ----

    @Override
    public boolean isVanished(UUID player) {
        VanishState s = states.get(player);
        return s != null && s.vanished();
    }

    @Override
    public Set<UUID> getVanishedPlayers() {
        return Set.copyOf(states.keySet().stream().filter(this::isVanished).toList());
    }

    @Override
    public VanishState getState(UUID player) {
        return states.get(player);
    }

    @Override
    public void setVanished(UUID player, boolean vanished) {
        Player p = proxy.getPlayer(player).orElse(null);
        // 玩家在线时取其当前名；离线但已有记录时沿用旧名（离线预隐身场景），
        // 两者都没有（全新 UUID）时留空。
        String username = p != null ? p.getUsername() : (states.get(player) != null ? states.get(player).username() : "");
        applyAndBroadcast(player, username, vanished);
    }

    // ---- 持久化 ----

    private Path file() {
        return dataDir.resolve("vanish-states.json");
    }

    private void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Type type = new TypeToken<List<VanishState>>() {
            }.getType();
            List<VanishState> list = gson.fromJson(json, type);
            if (list != null) {
                for (VanishState s : list) {
                    // 只接受隐身记录（与落盘契约一致），并跳过损坏/不完整条目，
                    // 避免一个损坏的 JSON 条目导致插件启用失败。
                    if (s != null && s.vanished()) {
                        states.put(s.uuid(), s);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.error("[MikuVanish] 读取持久化文件失败，本次忽略该文件", e);
        }
    }

    /** 仅在脏时落盘（由周期任务与关闭钩子调用）。原子写入，避免崩溃时留下半截文件。 */
    private void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            // 只持久化隐身记录：非隐身记录无恢复价值，避免文件随在线人数膨胀。
            List<VanishState> snapshot = new ArrayList<>();
            for (VanishState s : states.values()) {
                if (s.vanished()) {
                    snapshot.add(s);
                }
            }
            Path target = file();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 落盘失败：重新置脏，交由下一周期重试，避免这次变更永久丢失。
            dirty.set(true);
            logger.error("[MikuVanish] 写入持久化文件失败", e);
        }
    }

    public ProxyServer proxy() {
        return proxy;
    }
}
