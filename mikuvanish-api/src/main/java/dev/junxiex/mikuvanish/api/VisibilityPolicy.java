package dev.junxiex.mikuvanish.api;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 跨平台共享的隐身可见性判定。
 *
 * <p>语义：持有 {@code mikuvanish.seevanished} 的观众可以看到所有隐身玩家；
 * 隐身者本人始终可见自己；其余观众看不到隐身玩家。</p>
 */
public final class VisibilityPolicy {

    /** 可见隐身玩家的权限（也是对外的唯一可见性开关）。 */
    public static final String SEE_BASE = "mikuvanish.seevanished";

    private VisibilityPolicy() {
    }

    /**
     * 判定观众是否能看到目标。
     *
     * @param viewerPerms 观众权限查询（{@code player.hasPermission(node)}）
     * @param viewerId    观众 UUID
     * @param target      目标隐身状态
     * @return 目标对观众是否可见
     */
    public static boolean canSee(Predicate<String> viewerPerms, UUID viewerId, VanishState target) {
        if (target == null || !target.vanished()) {
            return true;
        }
        if (viewerId != null && viewerId.equals(target.uuid())) {
            return true;
        }
        return viewerPerms.test(SEE_BASE);
    }
}