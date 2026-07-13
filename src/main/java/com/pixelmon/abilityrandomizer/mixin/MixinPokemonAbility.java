package com.pixelmon.abilityrandomizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hooks the tail of {@link Pokemon#resetAbility()} - Pixelmon's single funnel for freshly
 * assigning an ability (creation and evolution). Vanilla runs first, picking the slot and
 * honouring hidden-ability rarity; we then remap the ability identity through our randomizer.
 *
 * <p>{@code remap = false} because the target is a Pixelmon class, not a Minecraft class, so
 * there are no SRG mappings to apply.</p>
 */
@Mixin(Pokemon.class)
public abstract class MixinPokemonAbility {

    @Shadow
    protected int slot;

    @Shadow
    protected boolean ha;

    /**
     * Guards against re-entrant calls: if Pixelmon's {@code setAbility()} triggers
     * {@code resetAbility()} internally, the recursive invocation is silently skipped.
     */
    private static final ThreadLocal<Boolean> IN_RANDOMIZATION =
        ThreadLocal.withInitial(() -> false);

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    @Inject(method = "resetAbility", at = @At("RETURN"), remap = false)
    private void abilityRandomizer$afterResetAbility(CallbackInfo ci) {
        if (IN_RANDOMIZATION.get()) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] MixinPokemonAbility: skipping re-entrant call");
            }
            return;
        }
        IN_RANDOMIZATION.set(true);
        try {
            if (ConfigProxy.isDebug()) {
                Pokemon self = (Pokemon) (Object) this;
                LOGGER.info("[AbilityRandomizer] MixinPokemonAbility: resetAbility hook for {} (slot={}, ha={})",
                    AbilityRandomizerEngine.safeName(self), this.slot, this.ha);
            }
            AbilityRandomizerEngine.apply((Pokemon) (Object) this, this.slot, this.ha);
        } finally {
            IN_RANDOMIZATION.set(false);
        }
    }
}
