package dev.junxiex.mikuvanish.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MikuVanish 对外 API 入口。
 *
 * <p>由各平台主类在启用时注入实例，其它插件通过本类查询或修改隐身状态。</p>
 *
 * <p>线程约束：查询方法线程安全，任意线程可调用；{@link #setVanished} 需在服务端
 * tick 线程调用（Paper 主线程 / Folia 任意 region 线程均可，内部会自行调度到正确线程；
 * 异步线程请先切回 tick 线程，因为内部会触发 Bukkit 事件）。代理端任意线程调用均可。</p>
 *
 * <pre>{@code
 * if (MikuVanishAPI.isAvailable() && MikuVanishAPI.get().isVanished(uuid)) { ... }
 * MikuVanishAPI.get().setVanished(uuid, true, 2);
 * }</pre>
 */
public final class MikuVanishAPI {

    private static volatile VanishService service;

    private MikuVanishAPI() {
    }

    /** 由平台实现调用，注册服务实例。 */
    public static void register(VanishService instance) {
        service = Objects.requireNonNull(instance, "instance");
    }

    /** 注销服务（插件禁用时）。 */
    public static void unregister() {
        service = null;
    }

    /** 服务是否可用（插件已加载）。 */
    public static boolean isAvailable() {
        return service != null;
    }

    /** 获取服务实例。 */
    public static VanishService get() {
        VanishService s = service;
        if (s == null) {
            throw new IllegalStateException("MikuVanish 尚未加载");
        }
        return s;
    }

    // ---- 便捷静态方法 ----

    public static boolean isVanished(UUID player) {
        return get().isVanished(player);
    }

    public static Set<UUID> getVanishedPlayers() {
        return get().getVanishedPlayers();
    }

    public static VanishState getState(UUID player) {
        return get().getState(player);
    }

    public static void setVanished(UUID player, boolean vanished) {
        get().setVanished(player, vanished);
    }
}
