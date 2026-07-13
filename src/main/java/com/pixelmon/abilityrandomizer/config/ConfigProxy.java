package com.pixelmon.abilityrandomizer.config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.pixelmonmod.pixelmon.api.config.api.data.ConfigPath;
import com.pixelmonmod.pixelmon.api.config.api.yaml.AbstractYamlConfig;

import info.pixelmon.repack.org.spongepowered.CommentedConfigurationNode;
import info.pixelmon.repack.org.spongepowered.ConfigurationOptions;
import info.pixelmon.repack.org.spongepowered.loader.HeaderMode;
import info.pixelmon.repack.org.spongepowered.reference.ConfigurationReference;
import info.pixelmon.repack.org.spongepowered.reference.ValueReference;
import info.pixelmon.repack.org.spongepowered.yaml.NodeStyle;
import info.pixelmon.repack.org.spongepowered.yaml.YamlConfigurationLoader;

/**
 * Loads, holds and exposes the {@link AbilityRandomizerConfig} instance.
 *
 * <p>The randomization seed is resolved per world/server at runtime (see
 * {@code WorldSeedResolver} and the server-start handler), not stored in this global config -
 * that is what keeps each world's abilities unique. The config only holds an OPTIONAL fixed-seed
 * override.</p>
 *
 * <p>Uses Pixelmon's repackaged SpongePowered YAML config library directly (instead of
 * {@code YamlConfigFactory}) so the generated config file does not carry the
 * "© PixelmonMod 2012-2025" header line.</p>
 */
public final class ConfigProxy {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    private static AbilityRandomizerConfig config;

    /**
     * The seed currently in effect, set when a world loads. 0 means "not yet resolved" - pools built
     * before a world is loaded would use 0 and then rebuild once the real seed is set.
     */
    private static volatile long runtimeSeed;

    private ConfigProxy() {
    }

    /**
     * Load (or create) the config YAML without the PixelmonMod header.
     *
     * <p>Replicates the essential parts of {@code YamlConfigFactory.getInstance()} but
     * leaves the header blank instead of branding every file.</p>
     */
    public static void reload() {
        try {
            ConfigPath annotation = AbilityRandomizerConfig.class.getAnnotation(ConfigPath.class);
            if (annotation == null) {
                throw new IOException("AbilityRandomizerConfig is missing @ConfigPath");
            }

            Path configFile = Paths.get(annotation.value());
            if (!configFile.toFile().exists()) {
                LOGGER.debug("[AbilityRandomizer] Config file does not exist, creating: {}", configFile.toAbsolutePath());
                configFile.getParent().toFile().mkdirs();
                configFile.toFile().createNewFile();
            }
            LOGGER.debug("[AbilityRandomizer] Loading config from: {}", configFile.toAbsolutePath());

            // Build a YAML loader with an empty header — no PixelmonMod branding.
            LOGGER.debug("[AbilityRandomizer] Building YAML configuration loader");
            ConfigurationReference<CommentedConfigurationNode> base =
                ConfigurationReference.fixed(
                    YamlConfigurationLoader.builder()
                        .headerMode(HeaderMode.PRESERVE)
                        .nodeStyle(NodeStyle.BLOCK)
                        .commentsEnabled(true)
                        .defaultOptions(
                            ConfigurationOptions.defaults()
                                .header("")   // <— empty header, no "© PixelmonMod 2012-2025"
                                .nativeTypes(nativeTypeSet())
                        )
                        .defaultOptions(opts -> opts.shouldCopyDefaults(true))
                        .path(configFile.toAbsolutePath())
                        .build()
                );

            if (base == null) {
                throw new IOException("Config loaded as null");
            }

            ValueReference<AbilityRandomizerConfig, CommentedConfigurationNode> reference =
                base.referenceTo(AbilityRandomizerConfig.class);

            AbilityRandomizerConfig instance = reference.get();
            if (instance == null) {
                throw new IOException("Config instance is null");
            }
            LOGGER.debug("[AbilityRandomizer] Config instance created, wiring internal references");

            // Wire the internal references via reflection (same pattern as YamlConfigFactory,
            // but we are in a different package so we cannot access protected fields directly).
            Field baseField = AbstractYamlConfig.class.getDeclaredField("base");
            baseField.setAccessible(true);
            baseField.set(instance, base);

            Field configField = AbstractYamlConfig.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(instance, reference);

            LOGGER.debug("[AbilityRandomizer] Saving config to disk");
            instance.save();

            config = instance;
            LOGGER.debug("[AbilityRandomizer] Config instance set; debugLogging={}, fixedSeed={}",
                instance.isDebugLogging(), instance.getFixedSeed());
            LOGGER.info("[AbilityRandomizer] Configuration loaded successfully");
        } catch (Exception e) {
            LOGGER.error("[AbilityRandomizer] Failed to load configuration; falling back to defaults", e);
            config = new AbilityRandomizerConfig();
        }
        // If the user forced a fixed seed, start with it; otherwise leave unresolved until a world loads.
        runtimeSeed = config.getFixedSeed();
    }

    /** Build the native-types set required by Configurate. */
    private static Set<Class<?>> nativeTypeSet() {
        Set<Class<?>> types = new HashSet<>();
        types.add(String.class);
        types.add(Integer.class);
        types.add(Byte.class);
        types.add(Double.class);
        types.add(Boolean.class);
        types.add(Long.class);
        types.add(Map.class);
        types.add(List.class);
        return types;
    }

    // ------------------------------------------------------------------
    // Public accessors
    // ------------------------------------------------------------------

    public static AbilityRandomizerConfig get() {
        return config;
    }

    public static boolean isLoaded() {
        return config != null;
    }

    /** The optional fixed-seed override from the config (0 = use the per-world seed). */
    public static long getConfiguredFixedSeed() {
        return config == null ? 0L : config.getFixedSeed();
    }

    /** Set the seed in effect for the currently-loaded world/server. */
    public static void setRuntimeSeed(long seed) {
        runtimeSeed = seed;
    }

    public static long getEffectiveMode1Seed() {
        return runtimeSeed;
    }

    public static boolean isDebug() {
        return config != null && config.isDebugLogging();
    }

    /** The randomization mode actually in effect after resolving the two toggles. */
    public static Mode effectiveMode() {
        if (config == null) {
            return Mode.VANILLA;
        }
        // Mode 1 wins when both are enabled.
        if (config.isMode1SpeciesConsistent()) {
            return Mode.SPECIES_CONSISTENT;
        }
        if (config.isMode2FullyRandom()) {
            return Mode.FULLY_RANDOM;
        }
        return Mode.VANILLA;
    }

    public enum Mode {
        /** No randomization - vanilla Pixelmon abilities. */
        VANILLA,
        /** Mode 1: consistent per-species (per evolution line) pools. */
        SPECIES_CONSISTENT,
        /** Mode 2: fully random per individual. */
        FULLY_RANDOM
    }
}