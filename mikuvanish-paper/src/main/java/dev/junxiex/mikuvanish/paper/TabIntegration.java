package dev.junxiex.mikuvanish.paper;

import dev.junxiex.mikuvanish.api.VanishState;
import dev.junxiex.mikuvanish.api.VisibilityPolicy;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.integration.VanishIntegration;
import org.bukkit.permissions.Permissible;

/**
 * 与 NEZNAMY/TAB 插件的兼容层。
 *
 * <p>通过实现 TAB 官方的 {@link VanishIntegration} SPI，让 TAB 的所有特性
 * （全局玩家列表、名牌、布局等）自动尊重 MikuVanish 的隐身与可见性判定，
 * 避免 TAB 把隐身玩家重新加回 tab 或名牌。</p>
 */
final class TabIntegration {

    private TabIntegration() {
    }

    static void register(VanishManager manager) {
        new Integration(manager).register();
    }

    private static final class Integration extends VanishIntegration {

        private final VanishManager manager;

        Integration(VanishManager manager) {
            super("MikuVanish");
            this.manager = manager;
        }

        @Override
        public boolean isVanished(TabPlayer player) {
            return manager.isVanished(player.getUniqueId());
        }

        @Override
        public boolean canSee(TabPlayer viewer, TabPlayer target) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) {
                return true;
            }
            VanishState state = manager.mirror().get(target.getUniqueId());
            if (state == null || !state.vanished()) {
                return true;
            }
            Object underlying = viewer.getPlayer();
            if (!(underlying instanceof Permissible permissible)) {
                return false;
            }
            return VisibilityPolicy.canSee(permissible::hasPermission, viewer.getUniqueId(), state);
        }
    }
}
