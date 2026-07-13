package com.pixelmon.abilityrandomizer.client;

/**
 * Contract implemented (via mixin) by Pixelmon's {@code PixelmonClientData} so our battle-HUD
 * overlay can read the opponent's ability off the same object Pixelmon already ships to the client.
 *
 * <p>The value stored is the ability's <em>unlocalized translation key</em> (e.g.
 * {@code "pixelmon.ability.blaze"}), NOT a pre-localized name. Storing the key keeps the packet
 * small and lets each client resolve the ability in its own language, rather than baking in the
 * server's locale. Resolve it client-side with {@code net.minecraft.client.resources.language.I18n}.</p>
 *
 * <p>Prefixed method names avoid any collision with future Pixelmon members on the same class.</p>
 */
public interface IAbilityDataHolder {

    /** @return the opponent ability's translation key, or {@code null} if none was captured. */
    String abilityRandomizer$getAbilityKey();

    /** @param key the ability translation key to store ({@code null} clears it). */
    void abilityRandomizer$setAbilityKey(String key);
}
