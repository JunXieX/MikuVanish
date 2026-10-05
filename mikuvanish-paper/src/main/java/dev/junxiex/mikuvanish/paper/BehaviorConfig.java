package dev.junxiex.mikuvanish.paper;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 行为拦截配置（config.yml）。
 *
 * <p>默认只拦截会直接暴露隐身者存在的几类行为（聊天 / 伤害 / 拾取 / 丢弃 / 索敌 / 痕迹播报），
 * 世界交互类（破坏、放置、红石、实体、世界、方块改动）默认放行；静默容器默认开启。
 * 这里的默认值须与 config.yml 保持一致，避免"注释与实际不符"。</p>
 */
final class BehaviorConfig {

    private final boolean chat;
    private final boolean damage;
    private final boolean pickup;
    private final boolean drop;
    private final boolean target;
    private final boolean announcements;
    private final boolean blockBreak;
    private final boolean blockPlace;
    private final boolean redstoneInteract;
    private final boolean entityInteract;
    private final boolean worldInteract;
    private final boolean blockModify;
    private final boolean silentContainer;

    BehaviorConfig(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration c = plugin.getConfig();
        this.chat = c.getBoolean("prevent.chat", true);
        this.damage = c.getBoolean("prevent.damage", true);
        this.pickup = c.getBoolean("prevent.pickup", true);
        this.drop = c.getBoolean("prevent.drop", true);
        this.target = c.getBoolean("prevent.target", true);
        this.announcements = c.getBoolean("prevent.announcements", true);
        this.blockBreak = c.getBoolean("prevent.block-break", false);
        this.blockPlace = c.getBoolean("prevent.block-place", false);
        this.redstoneInteract = c.getBoolean("prevent.redstone-interact", false);
        this.entityInteract = c.getBoolean("prevent.entity-interact", false);
        this.worldInteract = c.getBoolean("prevent.world-interact", false);
        this.blockModify = c.getBoolean("prevent.block-modify", false);
        this.silentContainer = c.getBoolean("silent-container", true);
    }

    boolean chat() {
        return chat;
    }

    boolean damage() {
        return damage;
    }

    boolean pickup() {
        return pickup;
    }

    boolean drop() {
        return drop;
    }

    boolean target() {
        return target;
    }

    boolean announcements() {
        return announcements;
    }

    boolean blockBreak() {
        return blockBreak;
    }

    boolean blockPlace() {
        return blockPlace;
    }

    boolean redstoneInteract() {
        return redstoneInteract;
    }

    boolean entityInteract() {
        return entityInteract;
    }

    boolean worldInteract() {
        return worldInteract;
    }

    boolean blockModify() {
        return blockModify;
    }

    boolean silentContainer() {
        return silentContainer;
    }
}
