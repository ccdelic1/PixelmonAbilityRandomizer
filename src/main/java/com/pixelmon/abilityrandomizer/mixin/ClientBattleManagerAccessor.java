package com.pixelmon.abilityrandomizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.pixelmonmod.pixelmon.client.gui.battles.ClientBattleManager;

/**
 * Exposes {@code ClientBattleManager.catchPossible}, which Pixelmon declares {@code private}.
 *
 * <p>We use it as the wild-vs-trainer discriminator: catching is possible in wild battles and not
 * in trainer/PvP battles, so {@code catchPossible} lets us hide the ability panel outside normal
 * wild encounters without a server-side battle-type packet. {@code battleType} is already public.</p>
 *
 * <p>{@code remap = false} because the field belongs to a Pixelmon class, not vanilla Minecraft.</p>
 */
@Mixin(ClientBattleManager.class)
public interface ClientBattleManagerAccessor {

    @Accessor(value = "catchPossible", remap = false)
    boolean abilityRandomizer$getCatchPossible();
}
