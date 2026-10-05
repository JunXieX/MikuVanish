package dev.junxiex.mikuvanish.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家隐身状态翻转事件：仅在 vanished 布尔值翻转时由代理端发布。
 *
 * <p>代理端通过 Velocity 事件总线发布（纯 POJO，Velocity 事件无需实现接口）；
 * 跨服移动、首次建立状态记录均不发布。</p>
 */
public final class VanishStateChangeEvent {

    private final UUID uuid;
    private final String username;
    private final boolean vanished;
    private final String serverId;

    public VanishStateChangeEvent(UUID uuid, String username, boolean vanished, String serverId) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.username = Objects.requireNonNull(username, "username");
        this.vanished = vanished;
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    /** 变更后的状态：true = 刚进入隐身，false = 刚解除隐身。 */
    public boolean vanished() {
        return vanished;
    }

    /** 玩家当前所在后端服务器 id（可能为空串）。 */
    public String serverId() {
        return serverId;
    }
}