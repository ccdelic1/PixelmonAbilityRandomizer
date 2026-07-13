package com.pixelmon.abilityrandomizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.core.WikiAbilityDisplay;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.command.impl.wiki.MainStrategy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.network.chat.MutableComponent;

/**
 * Makes the {@code /wiki} command's "Abilities:" line reflect the randomizer: the per-species
 * pool in Mode 1, or an "any ability" message in Mode 2. When randomization does not apply
 * (vanilla mode / excluded species / pool not ready) we leave the vanilla line untouched.
 *
 * <p>{@code remap = false} because the target is a Pixelmon class.</p>
 */
@Mixin(MainStrategy.class)
public abstract class MixinWikiMainStrategy {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    @Inject(method = "abilities", at = @At("HEAD"), cancellable = true, remap = false)
    private void abilityRandomizer$abilities(Pokemon pokemon, CallbackInfoReturnable<MutableComponent> cir) {
        MutableComponent replacement = WikiAbilityDisplay.buildAbilitiesComponent(pokemon);
        if (replacement != null) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] MixinWikiMainStrategy: replacing /wiki abilities display");
            }
            cir.setReturnValue(replacement);
        }
    }
}
