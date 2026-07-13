package com.pixelmon.abilityrandomizer.core;

import java.util.List;
import java.util.Optional;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.config.ConfigProxy.Mode;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine.PoolDisplay;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.pixelmonmod.pixelmon.api.command.PixelmonCommandUtils;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.pokemon.ability.Ability;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds the "Abilities:" line for the {@code /wiki} command so it reflects the randomizer instead
 * of the vanilla species ability pool.
 *
 * <ul>
 *   <li>Mode 1: the species/line pool (normal abilities, then hidden ones marked as HA), in the same
 *       format vanilla uses.</li>
 *   <li>Mode 2: a fixed "This Pokémon can have any ability." message.</li>
 *   <li>Vanilla mode or an excluded species: {@code null}, so the caller shows the vanilla line.</li>
 * </ul>
 */
public final class WikiAbilityDisplay {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    /** Translation key vanilla uses for the "Abilities: %s" line. */
    private static final String ABILITIES_KEY = "pixelmon.command.wiki.abilities";
    /** Translation key vanilla uses for the hidden-ability suffix, e.g. " (HA)". */
    private static final String HA_SUFFIX_KEY = "pixelmon.command.wiki.ha";

    private WikiAbilityDisplay() {
    }

    /**
     * @return the replacement abilities component, or {@code null} to leave the vanilla line in place.
     */
    public static MutableComponent buildAbilitiesComponent(Pokemon pokemon) {
        if (pokemon == null || !ConfigProxy.isLoaded()) {
            if (ConfigProxy.isDebug() && pokemon != null) {
                LOGGER.info("[AbilityRandomizer] WikiAbilityDisplay: config not loaded, returning null");
            }
            return null;
        }
        Mode mode = ConfigProxy.effectiveMode();
        if (mode == Mode.VANILLA || !AbilityRandomizerEngine.isActiveFor(pokemon)) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] WikiAbilityDisplay: vanilla mode or inactive for {}, returning null",
                    AbilityRandomizerEngine.safeName(pokemon));
            }
            return null;
        }

        if (mode == Mode.FULLY_RANDOM) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] WikiAbilityDisplay: Mode2 - any ability for {}",
                    AbilityRandomizerEngine.safeName(pokemon));
            }
            // Literal (not a translation key) so the exact text always shows regardless of which
            // language files the client has loaded.
            return PixelmonCommandUtils.format(ABILITIES_KEY,
                Component.literal("This Pokémon can have any ability"));
        }

        // Mode 1: show the randomized pool.
        Optional<PoolDisplay> poolOpt = AbilityRandomizerEngine.getMode1PoolForDisplay(pokemon);
        if (poolOpt.isEmpty()) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] WikiAbilityDisplay: Mode1 pool empty for {}, falling back to vanilla",
                    AbilityRandomizerEngine.safeName(pokemon));
            }
            return null; // pool not available - fall back to vanilla display
        }
        PoolDisplay pool = poolOpt.get();

        MutableComponent abilities = Component.literal("");
        List<Ability> normal = pool.getNormal();
        for (int i = 0; i < normal.size(); i++) {
            abilities.append(normal.get(i).getTranslatedName());
            if (i != normal.size() - 1) {
                abilities.append(Component.literal(", "));
            }
        }

        List<Ability> hidden = pool.getHidden();
        for (int i = 0; i < hidden.size(); i++) {
            if (i == 0 && !normal.isEmpty()) {
                abilities.append(Component.literal(", "));
            }
            abilities.append(hidden.get(i).getTranslatedName()).append(Component.translatable(HA_SUFFIX_KEY));
            if (i != hidden.size() - 1) {
                abilities.append(Component.literal(", "));
            }
        }

        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] WikiAbilityDisplay: built Mode1 abilities display for {}",
                AbilityRandomizerEngine.safeName(pokemon));
        }
        return PixelmonCommandUtils.format(ABILITIES_KEY, abilities);
    }
}
