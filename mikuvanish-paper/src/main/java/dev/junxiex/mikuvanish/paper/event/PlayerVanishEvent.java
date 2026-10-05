package dev.junxiex.mikuvanish.paper.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 玩家进入隐身状态时触发。可取消以阻止隐身生效。
 *
 * <p>在状态写入与可见性应用之前触发：此时查询 {@code MikuVanishAPI.isVanished} 仍是旧值。
 * 取消后镜像、代理端以及可见性都不会更新。</p>
 */
public class PlayerVanishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private boolean cancelled;

    public PlayerVanishEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}