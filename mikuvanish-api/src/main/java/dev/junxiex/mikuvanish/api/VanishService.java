package dev.junxiex.mikuvanish.api;

import java.util.Set;
import java.util.UUID;

/**
 * 跨平台隐身服务接口。代理端为权威实现，后端为镜像实现。
 *
 * <p>后端调用 {@link #setVanished} 会即时应用到本服并上报代理，由代理盖写
 * serverId 后广播全网，保证多服务器状态一致；代理端调用则直接更新权威并广播。</p>
 */
public interface VanishService {

    /** 目标玩家当前是否处于隐身（未知玩家返回 false）。 */
    boolean isVanished(UUID player);

    /**
     * 当前处于隐身状态的玩家集合（只读快照）。
     *
     * <p>范围随实现而异：代理端是权威记录，包含已离线但仍保持隐身的玩家（重连后会继续隐身）；
     * 后端是镜像，仅包含本服在线且处于隐身的玩家（即能在本服应用可见性的集合）。</p>
     */
    Set<UUID> getVanishedPlayers();

    /** 获取状态记录，未知玩家返回 {@code null}。 */
    VanishState getState(UUID player);

    /**
     * 设置目标玩家的隐身状态。
     *
     * @param player   目标玩家
     * @param vanished 是否隐身
     */
    void setVanished(UUID player, boolean vanished);
}