package dev.junxiex.mikuvanish.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家隐身状态记录（跨平台共享模型）。
 *
 * @param uuid      玩家 UUID
 * @param username  玩家名（用于展示）
 * @param vanished  是否处于隐身
 * @param serverId  玩家当前所在后端服务器 id（代理端维护）
 * @param updatedAt 状态最后变更时间戳（毫秒），仅供展示与诊断；跨服同步以代理端接收顺序为准，
 *                  不用于冲突裁决（避免各后端机器间的时钟偏差导致误判）
 */
public record VanishState(UUID uuid, String username, boolean vanished, String serverId, long updatedAt) {

    public VanishState {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(serverId, "serverId");
    }

    /** 返回一个仅变更所在服务器的副本。 */
    public VanishState withServer(String serverId) {
        return new VanishState(uuid, username, vanished, serverId, updatedAt);
    }
}