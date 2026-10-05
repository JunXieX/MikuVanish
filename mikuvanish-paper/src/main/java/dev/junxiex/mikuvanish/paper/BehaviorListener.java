package dev.junxiex.mikuvanish.paper;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import dev.junxiex.mikuvanish.api.VanishState;
import dev.junxiex.mikuvanish.api.VisibilityPolicy;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.EnderChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Set;
import java.util.UUID;

/**
 * 隐身行为拦截：隐身玩家不应以可感知的方式影响未看见他的玩家与世界。
 *
 * <p>拦截规则以「观众能否看见隐身者」为准：能被看见的隐身者（如管理员互相可见）
 * 不拦截其对应方向的交互。各项拦截由 {@link BehaviorConfig} 独立开关控制。</p>
 */
final class BehaviorListener implements Listener {

    /** 红石元件：右键即改变可见状态（标签未覆盖的部分，与 Tag 判定互补）。 */
    private static final Set<Material> REDSTONE_COMPONENTS = Set.of(
            Material.LEVER, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR);

    /** 发声方块：交互会向周围玩家播放声音。 */
    private static final Set<Material> AUDIBLE_BLOCKS = Set.of(
            Material.NOTE_BLOCK, Material.BELL, Material.JUKEBOX);

    /** 纯 UI 方块：右键只打开客户端界面、不改变世界状态，因此放行。 */
    private static final Set<Material> UI_BLOCKS = Set.of(
            Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE, Material.GRINDSTONE, Material.LOOM,
            Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE, Material.STONECUTTER, Material.BEACON);

    /**
     * 清单外「右键会改变方块外观」的方块（能用 Tag 表达的见 {@link #isVisibleBlockInteract}）。
     */
    private static final Set<Material> VISIBLE_INTERACT_BLOCKS = Set.of(
            Material.CAKE, Material.COMPOSTER, Material.TNT, Material.RESPAWN_ANCHOR,
            Material.CHISELED_BOOKSHELF, Material.DRAGON_EGG, Material.LECTERN,
            Material.SWEET_BERRY_BUSH, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);

    /** 放置实体的物品：放置行为由 block-place（EntityPlaceEvent）判定，此处不重复拦截。 */
    private static final Set<Material> ENTITY_PLACEMENT_ITEMS = Set.of(
            Material.ARMOR_STAND, Material.PAINTING, Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME,
            Material.END_CRYSTAL, Material.STRING,
            Material.MINECART, Material.CHEST_MINECART, Material.FURNACE_MINECART,
            Material.HOPPER_MINECART, Material.TNT_MINECART, Material.COMMAND_BLOCK_MINECART);

    /** 桶类物品：倒/取液体由 world-interact 判定，此处不重复拦截。 */
    private static final Set<Material> BUCKET_ITEMS = Set.of(
            Material.BUCKET, Material.WATER_BUCKET, Material.LAVA_BUCKET, Material.MILK_BUCKET,
            Material.POWDER_SNOW_BUCKET, Material.AXOLOTL_BUCKET, Material.COD_BUCKET,
            Material.SALMON_BUCKET, Material.PUFFERFISH_BUCKET, Material.TROPICAL_FISH_BUCKET,
            Material.TADPOLE_BUCKET);

    private final VanishManager manager;
    private final BehaviorConfig config;

    BehaviorListener(VanishManager manager, BehaviorConfig config) {
        this.manager = manager;
        this.config = config;
    }

    // ---- 聊天 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.chat() || !manager.isVanished(event.getPlayer().getUniqueId())) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        // 以 "!" 开头可绕过隐身发言限制（供管理员紧急沟通）。
        // 原样放行而非重建消息：保留富文本格式与消息签名完整性（"!" 前缀会显示出来）。
        if (plain.startsWith("!")) {
            return;
        }
        event.setCancelled(true);
        // Adventure 的 audience 发送在 Paper 上线程安全，可直接从异步事件调用。
        event.getPlayer().sendMessage(Component.text("隐身时无法发言。", NamedTextColor.RED));
    }

    // ---- 伤害（双向，仅玩家来源） ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!config.damage()) {
            return;
        }
        Player victim = asPlayer(event.getEntity());
        Player attacker = resolveAttacker(event.getDamager());

        // 隐身者被玩家（含投射物）攻击：攻击者看不见他则取消。
        // 环境/怪物伤害（attacker 为 null）不受限——隐身者仍会正常受到摔落、岩浆等伤害。
        if (victim != null && attacker != null && manager.isVanished(victim.getUniqueId())
                && !viewerCanSee(victim.getUniqueId(), attacker)) {
            event.setCancelled(true);
            return;
        }
        // 隐身者打人：仅当受害者是玩家且看不见攻击者时取消（打怪不受限，怪物索敌已由 onTarget 处理）。
        if (victim != null && attacker != null && manager.isVanished(attacker.getUniqueId())
                && !viewerCanSee(attacker.getUniqueId(), victim)) {
            event.setCancelled(true);
        }
    }

    // ---- 物品拾取 / 丢弃 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.pickup()) {
            return;
        }
        Player picker = asPlayer(event.getEntity());
        if (picker != null && manager.isVanished(picker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        // 丢弃的物品会实体化并对他人可见，隐身时禁止。
        if (config.drop() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---- 生物索敌 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!config.target()) {
            return;
        }
        Player target = asPlayer(event.getTarget());
        if (target != null && manager.isVanished(target.getUniqueId())) {
            event.setTarget(null);
        }
    }

    // ---- 存在痕迹播报 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (config.announcements() && manager.isVanished(event.getEntity().getUniqueId())) {
            event.deathMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (config.announcements() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRaidTrigger(RaidTriggerEvent event) {
        // 隐身者不应触发袭击（会向全服广播并显示袭击动画）。
        if (config.announcements() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---- 方块破坏/放置 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (config.blockBreak() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (config.blockPlace() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        // 盔甲架、船、矿车、画等实体放置对他人可见。
        Player player = event.getPlayer();
        if (config.blockPlace() && player != null && manager.isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---- 方块交互：静默容器 / 红石设备 / 发声方块 / 可感知的方块改动 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!manager.isVanished(player.getUniqueId())) {
            return;
        }
        Action action = event.getAction();

        if (action == Action.PHYSICAL) {
            // 踩压力板、耕地、绊线、蛋糕等物理触发（非右键行为，独立判定）。
            if (config.redstoneInteract()) {
                event.setCancelled(true);
            }
            return;
        }
        // 只处理右键：左键属破坏流程，取消左键事件会连带阻止破坏动作，
        // 把 redstone-interact 与 block-break 两个开关耦合在一起。
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // 方块状态只在需要时取一次（带方块实体的方块会生成快照，不算廉价）。
        boolean needState = config.silentContainer() || config.blockModify();
        BlockState state = needState ? block.getState() : null;
        Material type = block.getType();

        // 静默容器：可正常打开箱子/木桶/潜影盒/漏斗/末影箱，但不产生开启动画与音效。
        // 一次右键会对主手、副手各触发一次本事件，两手都要取消（阻止原版 use() 产生痕迹），
        // 但库存只在主手打开一次。
        if (config.silentContainer()) {
            Inventory container = resolveSilentContainer(player, state);
            if (container != null) {
                event.setCancelled(true);
                if (event.getHand() == EquipmentSlot.HAND) {
                    player.openInventory(container);
                }
                return;
            }
        }

        // 红石设备交互：按钮、拉杆、门、活板门、栅栏门、中继器、比较器、阳光探测器，
        // 右键即改变可被他人看到的状态。
        if (config.redstoneInteract()
                && (Tag.BUTTONS.isTagged(type) || Tag.DOORS.isTagged(type)
                || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || REDSTONE_COMPONENTS.contains(type))) {
            event.setCancelled(true);
            return;
        }
        // 发声方块交互：音符盒、钟、唱片机，动作会被周围玩家听到。
        if (config.worldInteract() && AUDIBLE_BLOCKS.contains(type)) {
            event.setCancelled(true);
            return;
        }

        // 可感知的方块改动：纯 UI 方块与容器放行（前者只有客户端界面，
        // 后者由 silent-container 决定是否静默），其余按下述两类拦截。
        if (config.blockModify()
                && !UI_BLOCKS.contains(type) && !Tag.ANVIL.isTagged(type)
                && !(state instanceof Container) && !(state instanceof EnderChest)
                && (isVisibleBlockInteract(type) || holdsBlockModifyingItem(player))) {
            event.setCancelled(true);
        }
    }

    /** 右键会改变方块外观的交互（清单 + 标签）。 */
    private static boolean isVisibleBlockInteract(Material type) {
        return VISIBLE_INTERACT_BLOCKS.contains(type)
                || Tag.BEDS.isTagged(type) || Tag.ALL_SIGNS.isTagged(type)
                || Tag.ALL_HANGING_SIGNS.isTagged(type) || Tag.CANDLES.isTagged(type)
                || Tag.CANDLE_CAKES.isTagged(type) || Tag.FLOWER_POTS.isTagged(type)
                || Tag.CAMPFIRES.isTagged(type) || Tag.CAULDRONS.isTagged(type)
                || Tag.BEEHIVES.isTagged(type);
    }

    /**
     * 主/副手是否持有「作用于方块」的物品（锄地、去皮、铲路、骨粉催熟、刷怪蛋、打火石、涂蜡等）。
     *
     * <p>这类改动由物品驱动，既不产生 {@code BlockPlaceEvent} 也不产生 {@code EntityPlaceEvent}，
     * 只能在交互事件里拦。放置类物品（方块、船/矿车/盔甲架/画/展示框）与桶类分别由
     * {@code block-place}、{@code world-interact} 负责，此处排除以免开关相互耦合。</p>
     */
    private static boolean holdsBlockModifyingItem(Player player) {
        return isBlockModifyingItem(player.getInventory().getItemInMainHand())
                || isBlockModifyingItem(player.getInventory().getItemInOffHand());
    }

    private static boolean isBlockModifyingItem(ItemStack item) {
        Material type = item.getType();
        return !type.isAir() && !type.isBlock()
                && !BUCKET_ITEMS.contains(type) && !ENTITY_PLACEMENT_ITEMS.contains(type)
                && !Tag.ITEMS_BOATS.isTagged(type) && !Tag.ITEMS_CHEST_BOATS.isTagged(type);
    }

    /**
     * 解析可静默打开的容器库存。
     *
     * <p>原理：取消 {@code PlayerInteractEvent} 后原版不会执行容器的 {@code use} 逻辑
     * （即不增加「开启计数」），因此不会向周围玩家广播开启动画、不播放音效；随后由调用方
     * 手动 {@code openInventory} 真实库存，玩家仍可正常查看/操作，仅缺失他人可感知的痕迹。
     * 比较器信号反映容器内容物、与是否开启无关，故不受影响。</p>
     *
     * <p>被上方方块遮挡的箱子/末影箱返回 {@code null} 交还原版：原版同样拒绝打开
     * （{@code isBlocked()} 复刻了原版判定），这样不会绕过原版的开启限制。</p>
     *
     * @param state 调用方已取的方块状态（避免重复生成快照）
     * @return 命中可静默打开的容器时返回其库存，否则 {@code null}（交还原版处理）
     */
    private Inventory resolveSilentContainer(Player player, BlockState state) {
        // 潜行且手持物品时原版优先执行物品行为（放置方块等）而非打开容器，交还原版处理。
        if (player.isSneaking()
                && (player.getInventory().getItemInMainHand().getType() != Material.AIR
                || player.getInventory().getItemInOffHand().getType() != Material.AIR)) {
            return null;
        }
        if (state instanceof EnderChest enderChest) {
            if (enderChest.isBlocked()) {
                return null;
            }
            // 末影箱库存为玩家私有共享，不来自方块容器。
            return player.getEnderChest();
        }
        if (state instanceof Container container) {
            if (container instanceof Chest chest && chest.isBlocked()) {
                return null;
            }
            if (container.isLocked() && !player.hasPermission("mikuvanish.bypass.lock")) {
                // 上锁容器仍走原版权限判定，交还原版处理。
                return null;
            }
            // 方块已放置时 getInventory() 返回世界中的真实库存（大箱子会合并两半），
            // 不是快照副本，因此查看与操作都会正确落盘。
            return container.getInventory();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // 注意：PlayerInteractAtEntityEvent 继承本事件且共用 HandlerList，
        // 因此本监听器同时覆盖「带点击位置」的实体交互，无需单独注册。
        if (config.entityInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---- 其它可被他人感知的玩家动作 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        // 采集南瓜/蜂巢等，动作对他人可见。
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        // 激流冲刺产生可见粒子。
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEggThrow(PlayerEggThrowEvent event) {
        // 投掷蛋生成的生物/粒子对他人可见，取消孵化。
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setHatching(false);
        }
    }

    // ---- 射击 / 投掷物 / 钓鱼 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        // 箭矢（含弩射出的烟花）会以实体形式被他人看到。
        if (config.worldInteract() && event.getEntity() instanceof Player player
                && manager.isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLaunchProjectile(PlayerLaunchProjectileEvent event) {
        // 雪球、蛋、末影珍珠、喷溅/滞留药水、经验瓶、三叉戟、风弹等（箭矢走 onShootBow）。
        if (config.worldInteract() && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        // 只拦抛竿（FISHING）：鱼漂与钓线对他人可见。
        // 其余状态放行——隐身瞬间已抛出的鱼漂必须能正常收回，否则会留下漂浮的鱼漂实体。
        if (config.worldInteract() && event.getState() == PlayerFishEvent.State.FISHING
                && manager.isVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // 注：骑乘、驯服、拴绳、剪羊毛均通过右键实体触发，已被上面的
    // PlayerInteractEntityEvent 统一拦截，无需单独监听（避免冗余）。

    // ---- 工具方法 ----

    private static Player asPlayer(Entity entity) {
        return entity instanceof Player p ? p : null;
    }

    /** 解析伤害来源玩家（含投射物）。 */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    /**
     * 判断旁观者 {@code observer} 是否能看见隐身者 {@code vanishedId}。
     *
     * <p>按 {@link VisibilityPolicy} 的权限策略判定，而不是 {@code Player#canSee}：后者读取
     * 实体的隐藏状态，Folia 下需在 viewer 自己的 region 线程读取，而行为事件运行在
     * 受害者/发起者所在 region，且事件必须当场决定是否取消。此处需要的是「策略上谁可见」，
     * 与 hide/show 使用同一套判定，保证拦截范围与实际可见性一致。</p>
     *
     * @param observer 观察者；为 {@code null} 时视为「无人能看见」
     */
    private boolean viewerCanSee(UUID vanishedId, Player observer) {
        if (observer == null) {
            return false;
        }
        VanishState state = manager.mirror().get(vanishedId);
        return VisibilityPolicy.canSee(observer::hasPermission, observer.getUniqueId(), state);
    }
}
