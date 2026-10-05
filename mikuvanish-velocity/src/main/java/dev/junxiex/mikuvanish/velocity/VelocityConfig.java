package dev.junxiex.mikuvanish.velocity;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 代理端配置（plugins/MikuVanish/config.yml）。
 *
 * <p>首次启动时把 jar 内的同名资源（含注释）释放到数据目录，随后用 Velocity 自带的
 * Configurate 读取；读取失败一律回退默认值，不影响插件启用。读取使用平台原生库，
 * 不手写 YAML 解析。</p>
 */
record VelocityConfig(boolean pingHideVanished, long saveIntervalSeconds) {

    private static final String FILE_NAME = "config.yml";
    private static final boolean DEFAULT_PING_HIDE_VANISHED = true;
    private static final long DEFAULT_SAVE_INTERVAL_SECONDS = 5;

    static VelocityConfig load(Path dataDir, Logger logger) {
        Path file = dataDir.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDir);
                try (InputStream in = VelocityConfig.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
                    Files.copy(in, file);
                }
                logger.info("[MikuVanish] 已生成默认配置：{}", file);
            }
            ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
            return new VelocityConfig(
                    root.node("ping-hide-vanished").getBoolean(DEFAULT_PING_HIDE_VANISHED),
                    Math.max(1, root.node("save-interval-seconds").getLong(DEFAULT_SAVE_INTERVAL_SECONDS)));
        } catch (IOException | RuntimeException e) {
            logger.error("[MikuVanish] 读取配置失败，本次使用默认值", e);
            return new VelocityConfig(DEFAULT_PING_HIDE_VANISHED, DEFAULT_SAVE_INTERVAL_SECONDS);
        }
    }
}