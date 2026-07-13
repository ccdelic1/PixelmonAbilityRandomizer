package com.pixelmon.abilityrandomizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pixelmon.abilityrandomizer.client.IAbilityDataHolder;
import com.pixelmonmod.pixelmon.api.pokemon.ability.Ability;
import com.pixelmonmod.pixelmon.battles.controller.participants.PixelmonWrapper;
import com.pixelmonmod.pixelmon.client.gui.battles.PixelmonClientData;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Attaches the opponent's ability to Pixelmon's {@code PixelmonClientData} so the battle HUD
 * overlay ({@link MixinOpponentElement}) can display it.
 *
 * <p>Pixelmon captures {@code getBattleAbility()} in this constructor for Illusion detection but
 * discards the ability afterwards, and never serializes it. We store the ability's <em>translation
 * key</em> and append it to the network packet.</p>
 *
 * <h2>Why this is a COMMON mixin (not client-only)</h2>
 * {@code encodeInto()} runs <strong>server-side</strong> (the {@code PixelmonClientData(PixelmonWrapper)}
 * constructor and {@code convertToGUI()} are called on the server to build battle packets), while
 * {@code decodeFrom()} runs client-side. A client-only mixin would inject {@code readUtf()} into
 * {@code decodeFrom()} without the matching {@code writeUtf()} in {@code encodeInto()} ever firing,
 * causing a buffer underrun / packet corruption on every battle start. Listing this mixin under
 * {@code "mixins"} keeps encode/decode symmetric on both physical sides. Because our write is the
 * LAST thing appended at {@code RETURN}, the rest of Pixelmon's byte layout is untouched.
 *
 * <p>The extra field is a translation key (e.g. {@code "pixelmon.ability.blaze"}) — short, and
 * resolved into the client's own language at render time.</p>
 *
 * <p>{@code remap = false} on every injector because the targets are Pixelmon methods, not
 * Minecraft methods that require SRG remapping.</p>
 */
@Mixin(value = PixelmonClientData.class, priority = 1500)
public abstract class MixinPixelmonClientData implements IAbilityDataHolder {

    /** Opponent ability translation key, or {@code null} when unknown. */
    @Unique
    private String abilityRandomizer$abilityKey;

    @Override
    public String abilityRandomizer$getAbilityKey() {
        return this.abilityRandomizer$abilityKey;
    }

    @Override
    public void abilityRandomizer$setAbilityKey(String key) {
        this.abilityRandomizer$abilityKey = key;
    }

    /**
     * Capture the (already-randomized, real) battle ability when the server builds client data.
     *
     * <p>Under Illusion, Pixelmon swaps the visible species/nickname earlier in this constructor,
     * but {@code getBattleAbility()} still returns the REAL ability — which is the desired value.</p>
     */
    @Inject(
        method = "<init>(Lcom/pixelmonmod/pixelmon/battles/controller/participants/PixelmonWrapper;)V",
        at = @At("RETURN"),
        remap = false
    )
    private void abilityRandomizer$captureAbility(PixelmonWrapper pixelmon, CallbackInfo ci) {
        try {
            Ability ability = pixelmon.getBattleAbility();
            this.abilityRandomizer$abilityKey = ability != null ? ability.getTranslationKey() : null;
        } catch (Throwable t) {
            // getBattleAbility() can be in a transient state during early battle setup; fail soft.
            this.abilityRandomizer$abilityKey = null;
        }
    }

    /** Append the ability key as the final field (empty string == "unknown"). */
    @Inject(method = "encodeInto", at = @At("RETURN"), remap = false)
    private void abilityRandomizer$encode(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeUtf(this.abilityRandomizer$abilityKey != null ? this.abilityRandomizer$abilityKey : "");
    }

    /** Read the trailing ability key written by {@link #abilityRandomizer$encode}. */
    @Inject(method = "decodeFrom", at = @At("RETURN"), remap = false)
    private void abilityRandomizer$decode(FriendlyByteBuf buffer, CallbackInfo ci) {
        String key = buffer.readUtf();
        this.abilityRandomizer$abilityKey = key.isEmpty() ? null : key;
    }
}
