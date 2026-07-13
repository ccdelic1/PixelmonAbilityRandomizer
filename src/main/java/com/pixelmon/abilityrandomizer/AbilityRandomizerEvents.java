package com.pixelmon.abilityrandomizer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.pixelmon.abilityrandomizer.command.AbilityRandomizerCommands;
import com.pixelmon.abilityrandomizer.command.RandomizerMessages;
import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine;
import com.pixelmon.abilityrandomizer.core.WorldSeedResolver;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Server-side safety net for randomizing wild Pokémon.
 *
 * <p>The primary hook ({@code Pokemon.resetAbility()} via mixin) runs during Pokémon creation,
 * which can be <em>before</em> Pixelmon's ability registry has finished (re)syncing right after a
 * fresh game boot / world load — in which case the early wild spawns keep their vanilla ability.
 * This listener fires when a wild Pokémon <em>entity</em> is added to a server level, which is
 * comfortably after the registry is populated, giving those spawns a reliable second chance.</p>
 *
 * <p>It only touches wild (unowned) Pokémon, and {@link AbilityRandomizerEngine#apply(Pokemon)} is
 * idempotent (deterministic per species/individual), so re-running it never changes an ability that
 * is already correct and never disturbs player-owned Pokémon.</p>
 */
public final class AbilityRandomizerEvents {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    private AbilityRandomizerEvents() {
    }

    public static void register(IEventBus gameEventBus) {
        gameEventBus.addListener(AbilityRandomizerEvents::onServerStarted);
        gameEventBus.addListener(AbilityRandomizerEvents::onServerStopping);
        gameEventBus.addListener(AbilityRandomizerEvents::onEntityJoin);
        gameEventBus.addListener(AbilityRandomizerEvents::onPlayerLogin);
        gameEventBus.addListener(AbilityRandomizerCommands::onRegisterCommands);
        LOGGER.info("[AbilityRandomizer] Event listeners registered");
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Event listeners registered (debug enabled)");
        }
    }

    /**
     * Resolve the seed for the world that just loaded. Each world/server gets its own seed (stored
     * in its save), unless the config forces a fixed seed. Rebuild caches so pools reflect this
     * world's seed.
     */
    private static void onServerStarted(ServerStartedEvent event) {
        LOGGER.debug("[AbilityRandomizer] onServerStarted: server starting");
        long fixed = ConfigProxy.getConfiguredFixedSeed();
        long seed;
        if (fixed != 0L) {
            seed = fixed;
            LOGGER.info("[AbilityRandomizer] Using the configured fixed seed for all worlds");
        } else {
            seed = WorldSeedResolver.resolveForWorld(event.getServer());
            LOGGER.info("[AbilityRandomizer] Using this world's own seed for ability pools");
        }
        ConfigProxy.setRuntimeSeed(seed);
        LOGGER.debug("[AbilityRandomizer] onServerStarted: runtime seed set to {}", seed);
        AbilityRandomizerEngine.invalidateCaches();
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] onServerStarted complete: seed={}, mode={}",
                seed, ConfigProxy.effectiveMode());
        }
    }

    /**
     * Clear the resolved seed and caches when a world unloads, so the next world loaded in the same
     * game session starts fresh with its own seed.
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        LOGGER.debug("[AbilityRandomizer] onServerStopping: server stopping");
        ConfigProxy.setRuntimeSeed(ConfigProxy.getConfiguredFixedSeed());
        AbilityRandomizerEngine.invalidateCaches();
        LOGGER.debug("[AbilityRandomizer] onServerStopping: caches invalidated, seed reset");
    }

    /** Send the welcome banner when a player joins, unless disabled in config. */
    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ConfigProxy.isLoaded() || !ConfigProxy.get().isShowLoginBanner()) {
            return;
        }
        try {
            player.sendSystemMessage(RandomizerMessages.loginBanner());
        } catch (Exception e) {
            LOGGER.error("[AbilityRandomizer] Failed to send login banner", e);
        }
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof PixelmonEntity pixelmon)) {
            return;
        }
        try {
            Pokemon pokemon = pixelmon.getPokemon();
            if (pokemon == null || !AbilityRandomizerEngine.isWild(pokemon)) {
                if (ConfigProxy.isDebug() && pokemon != null) {
                    LOGGER.info("[AbilityRandomizer] onEntityJoin: skipping {} (not wild / no pokemon)",
                        pokemon != null ? AbilityRandomizerEngine.safeName(pokemon) : "null");
                }
                return;
            }
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] onEntityJoin: wild spawn safety net for {}",
                    AbilityRandomizerEngine.safeName(pokemon));
            }
            AbilityRandomizerEngine.apply(pokemon);
        } catch (Exception e) {
            LOGGER.error("[AbilityRandomizer] Error in wild-spawn safety-net listener", e);
        }
    }
}
