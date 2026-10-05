package dev.junxiex.mikuvanish.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.junxiex.mikuvanish.api.MikuVanishAPI;
import dev.junxiex.mikuvanish.api.VanishService;
import dev.junxiex.mikuvanish.api.VanishState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MikuVanish 后端插件（Paper + Folia，Paper 插件格式）。
 *
 * <p>作为代理权威状态的镜像，负责在本服务器应用世界隐藏与 tab 隐藏，
 * 并兼容 TAB 插件。隐身切换命令在本地即时生效并回报代理。</p>
 */
public final class MikuVanishPaper extends JavaPlugin implements VanishService {

    private VanishManager manager;
    private SyncBridge sync;
    private BehaviorConfig behaviorConfig;

    @Override
    public void onEnable() {
        this.behaviorConfig = new BehaviorConfig(this);
        this.manager = new VanishManager(this);
        this.sync = new SyncBridge(this, manager);
        this.sync.register();
        MikuVanishAPI.register(this);

        Bukkit.getPluginManager().registerEvents(new VanishListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BehaviorListener(manager, behaviorConfig), this);

        // 命令走 Paper 原生的 Brigadier + 生命周期注册（重新加载时自动重注册）。
        // 权限由命令树自身的 requires 判定，描述符不声明 commands。
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            registerQuietly(commands, new VanishCommand(this).create(), "切换隐身状态", List.of("v", "vs"));
            registerQuietly(commands, new VanishListCommand(this).create(), "列出当前隐身玩家", List.of("vlist"));
        });

        // TAB 兼容：注册 VanishIntegration（需在 paper-plugin.yml 中声明 join-classpath 才能访问 TAB-API）。
        if (Bukkit.getPluginManager().getPlugin("TAB") != null) {
            TabIntegration.register(manager);
            getLogger().info("[MikuVanish] 已接入 TAB 隐身集成。");
        }

        // 冷启动：为已在线玩家拉取全量快照。
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            sync.sendHello();
        }

        getLogger().info("[MikuVanish] 后端已启用（folia=" + isFolia() + "）。");
    }

    /**
     * 逐个注册命令并吞掉重名异常。
     *
     * <p>短别名（如 {@code /v}、{@code /vs}）可能已被其它插件占用，而 Brigadier 注册在冲突时会抛
     * {@link IllegalArgumentException}；若放任抛出会中断命令调度器的构建流程，连累其它插件的命令。</p>
     */
    private void registerQuietly(Commands commands, LiteralCommandNode<CommandSourceStack> node,
                                 String description, List<String> aliases) {
        try {
            commands.register(node, description, aliases);
        } catch (IllegalArgumentException e) {
            getLogger().warning("[MikuVanish] 命令 " + node.getName() + " 注册失败（与其它插件重名）：" + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        // 直接同步恢复所有可见性、碰撞与 metadata（禁用期间调度器不再执行本插件任务）。
        manager.restoreAll();
        sync.unregister();
        MikuVanishAPI.unregister();
    }

    SyncBridge sync() {
        return sync;
    }

    VanishManager manager() {
        return manager;
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ---- VanishService 实现 ----

    @Override
    public boolean isVanished(UUID player) {
        return manager.isVanished(player);
    }

    @Override
    public Set<UUID> getVanishedPlayers() {
        return manager.vanishedPlayers();
    }

    @Override
    public VanishState getState(UUID player) {
        return manager.mirror().get(player);
    }

    @Override
    public void setVanished(UUID player, boolean vanished) {
        manager.toggle(player, vanished);
    }
}