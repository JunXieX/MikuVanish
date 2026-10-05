package dev.junxiex.mikuvanish.paper.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 玩家退出隐身状态时触发（不可取消）。
 *
 * <p>在状态写入之后触发：镜像已更新为「非隐身」，但可见性应用
 * （showPlayer/metadata 清理）已排入各玩家的 region 线程、于下一 tick 生效。</p>
 */
public class PlayerUnVanishEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;

    public PlayerUnVanishEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
