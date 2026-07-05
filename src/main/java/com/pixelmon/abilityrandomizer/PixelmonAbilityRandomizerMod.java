package com.pixelmon.abilityrandomizer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point for the Pixelmon Ability Randomizer side-mod.
 *
 * <p>The actual ability randomization is applied by {@link AbilityRandomizerEngine} via a mixin on
 * {@code Pokemon.resetAbility()}. This class only wires up config loading.</p>
 */
@Mod("pixelmonabilityrandomizer")
public class PixelmonAbilityRandomizerMod {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    public PixelmonAbilityRandomizerMod(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("[AbilityRandomizer] Mod constructor called");
        modEventBus.addListener(this::commonSetup);
        // Wild-spawn safety net runs on the game event bus (not the mod bus).
        AbilityRandomizerEvents.register(NeoForge.EVENT_BUS);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[AbilityRandomizer] Loading configuration");
        ConfigProxy.reload();
        AbilityRandomizerEngine.invalidateCaches();
        if (ConfigProxy.isLoaded()) {
            LOGGER.info("[AbilityRandomizer] Config loaded. Effective mode: {}", ConfigProxy.effectiveMode());
        } else {
            LOGGER.error("[AbilityRandomizer] Config failed to load!");
        }
    }
}
