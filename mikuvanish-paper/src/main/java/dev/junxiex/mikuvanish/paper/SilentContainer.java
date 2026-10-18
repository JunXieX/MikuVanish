package dev.junxiex.mikuvanish.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静默容器：让隐身者打开/关闭容器时不产生可被他人察觉的痕迹。
 *
 * <p><b>为什么不能只取消交互事件</b>：取消 {@code PlayerInteractEvent} 只挡住原版的
 * {@code use()} 逻辑，手动 {@code openInventory} 时 Paper 仍会在触发
 * {@code InventoryOpenEvent} 前调用 {@code Container#startOpen}，而开启音效、盖子/盒体动画
 * 与幽匿感测体可感知的 {@code GameEvent.CONTAINER_OPEN} 都发生在开启计数由 0 变 1 的那一刻。</p>
 *
 * <p><b>做法</b>：原版对旁观者不计数（{@code Container#startOpen} 会跳过
 * {@code openersCounter.incrementOpeners}）。因此在调用 {@code openInventory} 前后，
 * 临时把玩家的 NMS 游戏模式写成 {@code SPECTATOR} 再改回。该写入绕过
 * {@code setGameMode}，既不发送游戏模式数据包、也不触发
 * {@code PlayerGameModeChangeEvent}，客户端全程无感。</p>
 *
 * <p>与 OpenInv 的 SilentContainer 同源：使用真实库存，物品编辑直接落盘，大箱子自动合并两半；
 * 不需要协议库，也没有镜像背包的物品复制风险。</p>
 *
 * <p><b>降级</b>：反射不可用（服务端实现变更）时退化为普通 {@code openInventory}，
 * 容器仍能正常打开，只是会有开启音效，并在首次失败时记录警告。</p>
 */
final class SilentContainer implements Listener {

    private static final boolean FOLIA = MikuVanishPaper.isFolia();

    private final MikuVanishPaper plugin;

    /** 通过本类静默打开的玩家；用于在关闭时判断是否属于本插件的会话。 */
    private final Set<UUID> opened = ConcurrentHashMap.newKeySet();

    /** 已进入「待还原」状态的玩家 → 其真实游戏模式（关闭流程结束后在下一 tick 写回）。 */
    private final Map<UUID, Object> pending = new ConcurrentHashMap<>();

    private final Method getHandle;
    private final Field gameMode;
    private final Field gameModeForPlayer;
    private final Object spectator;

    private volatile boolean warned;

    SilentContainer(MikuVanishPaper plugin) {
        this.plugin = plugin;

        Method handleMethod = null;
        Field modeField = null;
        Field modeForPlayerField = null;
        Object spectatorMode = null;
        try {
            handleMethod = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
            Class<?> playerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            modeField = findField(playerClass, "net.minecraft.server.level.ServerPlayerGameMode", "gameMode");
            modeForPlayerField = findField(modeField.getType(), "net.minecraft.world.level.GameType",
                    "gameModeForPlayer");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> gameType = (Class<? extends Enum>) Class.forName("net.minecraft.world.level.GameType");
            spectatorMode = Enum.valueOf(gameType, "SPECTATOR");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("[MikuVanish] 无法初始化静默开箱（服务端实现可能已变更）：" + e);
            plugin.getLogger().warning("[MikuVanish] 静默开箱将退化为普通开箱（会有开启音效与动画）。");
            handleMethod = null;
            modeField = null;
            modeForPlayerField = null;
            spectatorMode = null;
        }
        this.getHandle = handleMethod;
        this.gameMode = modeField;
        this.gameModeForPlayer = modeForPlayerField;
        this.spectator = spectatorMode;
    }

    /**
     * 按类型定位字段：优先使用已知名；缺失时仅在「同类字段中类型匹配且唯一」时采用。
     *
     * <p>刻意不做「取第一个匹配项」的猜测：{@code ServerPlayerGameMode} 里
     * {@code gameModeForPlayer} 与 {@code previousGameModeForPlayer} 类型相同，
     * 猜错会直接写坏玩家的上一模式。宁可退化为普通开箱，也不冒这个风险。</p>
     */
    private static Field findField(Class<?> owner, String typeName, String preferredName)
            throws NoSuchFieldException {
        try {
            Field field = owner.getDeclaredField(preferredName);
            if (field.getType().getName().equals(typeName)) {
                field.setAccessible(true);
                return field;
            }
        } catch (NoSuchFieldException ignored) {
            // 落到按类型查找。
        }
        Field candidate = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!field.getType().getName().equals(typeName)) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchFieldException(owner.getName() + " 中存在多个类型为 " + typeName
                        + " 的字段，无法安全判定");
            }
            candidate = field;
        }
        if (candidate == null) {
            throw new NoSuchFieldException(owner.getName() + " 中找不到类型为 " + typeName + " 的字段");
        }
        candidate.setAccessible(true);
        return candidate;
    }

    private boolean available() {
        return getHandle != null && gameMode != null && gameModeForPlayer != null && spectator != null;
    }

    /**
     * 以「旁观者」身份打开容器。
     *
     * @return 是否成功打开（反射不可用或 {@code InventoryOpenEvent} 被取消时为 {@code false}）
     */
    boolean open(Player player, Inventory inventory) {
        if (!available()) {
            player.openInventory(inventory);
            return false;
        }

        UUID id = player.getUniqueId();
        Object mode;
        Object previous;
        try {
            mode = gameMode.get(getHandle.invoke(player));
            previous = gameModeForPlayer.get(mode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            player.openInventory(inventory);
            return false;
        }

        // 真实旁观者，或上一次静默关闭的还原要到下一 tick 才执行：原版本本就不计数，
        // 直接打开即可；同时也不能把「旁观者」当成原状态写回。
        if (previous == spectator) {
            InventoryView view = player.openInventory(inventory);
            if (view != null) {
                opened.add(id);
            }
            return view != null;
        }

        InventoryType current = player.getOpenInventory().getType();
        if (current != InventoryType.CRAFTING && current != InventoryType.CREATIVE) {
            // 旧界面若是本插件静默打开的，必须以旁观者身份关闭，否则它的 stopOpen 会把
            // 别人正打开着的这个容器一起关掉（连带关闭音效与盖子动作）。
            // 反之（普通打开的界面，如隐身前的）应当正常关闭，让计数正确递减。
            if (opened.contains(id)) {
                setSpectator(mode);
                try {
                    player.closeInventory();
                } finally {
                    restore(mode, previous);
                }
            } else {
                player.closeInventory();
            }
        }

        boolean success;
        try {
            gameModeForPlayer.set(mode, spectator);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            player.openInventory(inventory);
            return false;
        }
        try {
            InventoryView view = player.openInventory(inventory);
            success = view != null;
        } finally {
            restore(mode, previous);
        }
        if (success) {
            opened.add(id);
        }
        return success;
    }

    private void setSpectator(Object mode) {
        try {
            gameModeForPlayer.set(mode, spectator);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    /**
     * 关闭时按需压制 {@code stopOpen} 的递减。
     *
     * <p>本次会话从未把玩家计入开启计数，因此：</p>
     * <ul>
     *   <li>没有其他观众时无需处理——{@code decrementOpeners} 被 Paper 的
     *       {@code openCount == 0} 早退保护挡住，不会产生任何痕迹；</li>
     *   <li>有其他观众时若不压制，会把对方的计数减到 0，导致盖子提前合上并播放关闭音效。</li>
     * </ul>
     *
     * <p>需要压制时必须推迟到下一 tick 还原：{@code doCloseContainer()} 里的
     * {@code stopOpen()} 在 {@code InventoryCloseEvent} 之后才执行。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !available()) {
            return;
        }
        if (!opened.remove(player.getUniqueId())) {
            return;
        }
        // 关闭事件触发时本玩家仍在观众列表里，因此 <= 1 表示只有自己。
        if (event.getInventory().getViewers().size() <= 1) {
            return;
        }
        Object mode;
        Object previous;
        try {
            Object handle = getHandle.invoke(player);
            mode = gameMode.get(handle);
            previous = gameModeForPlayer.get(mode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            return;
        }
        if (previous == spectator) {
            return;
        }
        try {
            gameModeForPlayer.set(mode, spectator);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            return;
        }
        pending.put(player.getUniqueId(), previous);
        scheduleRestore(player, mode);
    }

    /** 玩家离线：立即还原，避免残留旁观者状态（离线后也不再需要压制关闭痕迹）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        opened.remove(event.getPlayer().getUniqueId());
        flushPending(event.getPlayer());
    }

    /** 新会话开始：清理上次异常退出（如崩溃）可能残留的标记。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        opened.remove(id);
        pending.remove(id);
    }

    /** 插件停用前还原所有待还原的玩家（停用后调度任务不会执行）。 */
    void shutdown() {
        for (UUID id : pending.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                flushPending(player);
            }
        }
        pending.clear();
        opened.clear();
    }

    /** 立即还原「待还原」的旁观者身份；仅在确认关闭流程已执行完毕时调用。 */
    private void flushPending(Player player) {
        Object previous = pending.remove(player.getUniqueId());
        if (previous == null || !available()) {
            return;
        }
        try {
            restore(gameMode.get(getHandle.invoke(player)), previous);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    private void scheduleRestore(Player player, Object mode) {
        Runnable task = () -> {
            Object previous = pending.remove(player.getUniqueId());
            if (previous != null) {
                restore(mode, previous);
            }
        };
        try {
            if (FOLIA) {
                player.getScheduler().execute(plugin, task, null, 0L);
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (RuntimeException e) {
            // 调度器已关闭（插件停用中）：直接执行，保证不残留旁观者状态。
            task.run();
        }
    }

    /** 还原游戏模式；仅当仍是旁观者时写入，避免覆盖期间的正常模式变更。 */
    private boolean restore(Object mode, Object previous) {
        try {
            if (gameModeForPlayer.get(mode) == spectator) {
                gameModeForPlayer.set(mode, previous);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().severe("[MikuVanish] 还原游戏模式失败，玩家可能残留旁观者状态：" + e);
            return false;
        }
    }

    private void warnOnce(Exception e) {
        if (warned) {
            return;
        }
        warned = true;
        plugin.getLogger().warning("[MikuVanish] 静默开箱反射失败，本次退化为普通开箱：" + e);
    }
}
