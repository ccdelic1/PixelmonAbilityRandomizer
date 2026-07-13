package com.pixelmon.abilityrandomizer.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.pokemon.ability.Ability;
import com.pixelmonmod.pixelmon.client.gui.starter.ChooseStarterScreen;
import com.pixelmonmod.pixelmon.config.starter.Starter;
import com.pixelmonmod.pixelmon.config.starter.StarterList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * Replaces the starter-screen welcome text with the selected Pokémon's ability
 * information when a starter is clicked (but not yet confirmed).
 *
 * <p>The two welcome-text lines are drawn via
 * {@code GuiGraphics.drawCenteredString(Font, String, int, int, int)} at
 * bytecode ordinals 3 and 4 inside {@code ChooseStarterScreen.render()}.
 * When {@code clickedIndex != -1} (a starter is selected), those arguments are
 * swapped for an ability summary.</p>
 *
 * <p>Results are cached per clicked index to prevent flickering: without
 * caching, each frame would create a fresh Pokémon (via {@code spec.create()})
 * which triggers {@code resetAbility()} and can produce a different ability
 * slot, making the text jump between values every frame.</p>
 *
 * <p>{@code remap = false} because the target is a Pixelmon class.</p>
 */
@Mixin(ChooseStarterScreen.class)
public abstract class MixinChooseStarterScreen {

    @Shadow
    private int clickedIndex;

    @Shadow
    boolean[] options;

    /** Cached clicked index; -1 forces recompute on first call. */
    @Unique
    private int abilityRandomizer$cachedIndex = -1;

    /** Cached first line (species name + ability name). */
    @Unique
    private String abilityRandomizer$cachedLine0;

    /** Cached second line (ability description). */
    @Unique
    private String abilityRandomizer$cachedLine1;

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    /**
     * Replaces the first welcome-text line with the bold species name and ability name.
     */
    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            ordinal = 3
        ),
        index = 1,
        remap = false
    )

    private String abilityRandomizer$replaceWelcomeLine0(String original) {
        if (this.clickedIndex == -1) {
            return original;
        }
        refreshCache(original);
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] MixinChooseStarterScreen: replaced welcome line 0 for clickedIndex={}",
                this.clickedIndex);
        }
        return abilityRandomizer$cachedLine0;
    }

    /** Maximum characters per rendered line of the ability description. */
    @Unique
    private static final int abilityRandomizer$MAX_LINE_CHARS = 60;

    /**
     * Replaces the second welcome-text line with the ability description.
     *
     * <p>Redirects the single {@code drawCenteredString} call so the description
     * can be wrapped onto multiple centered lines (each at most
     * {@link #abilityRandomizer$MAX_LINE_CHARS} characters) instead of running off
     * the edge of the screen. Each wrapped line is drawn one {@code font.lineHeight}
     * below the previous one.</p>
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            ordinal = 4
        ),
        remap = false
    )
    private void abilityRandomizer$drawWelcomeLine1(GuiGraphics graphics, Font font, String original,
            int x, int y, int color) {
        if (this.clickedIndex == -1) {
            graphics.drawCenteredString(font, original, x, y, color);
            return;
        }
        refreshCache(original);
        int lineY = y;
        for (String line : abilityRandomizer$wrap(abilityRandomizer$cachedLine1, abilityRandomizer$MAX_LINE_CHARS)) {
            graphics.drawCenteredString(font, line, x, lineY, color);
            lineY += font.lineHeight;
        }
    }

    /**
     * Wraps {@code text} into lines of at most {@code maxChars} characters, breaking
     * on spaces so words stay intact. A single word longer than {@code maxChars} is
     * hard-split at the character limit.
     */
    @Unique
    private static List<String> abilityRandomizer$wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add(text == null ? "" : text);
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            // A single word longer than the limit: hard-split it into chunks.
            while (word.length() > maxChars) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, maxChars));
                word = word.substring(maxChars);
            }
            int extra = current.length() == 0 ? word.length() : current.length() + 1 + word.length();
            if (extra > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * Rebuilds the cached text lines only when the selected starter changes.
     *
     * @param fallback the original welcome text to cache on any error.
     */
    @Unique
    private void refreshCache(String fallback) {
        if (this.clickedIndex == abilityRandomizer$cachedIndex) {
            return; // still on the same starter — reuse cache
        }
        abilityRandomizer$cachedIndex = this.clickedIndex;
        try {
            Starter starter = StarterList.getStarters().get(this.clickedIndex);
            Pokemon pokemon = starter.getDisplay(this.options);
            String speciesName = pokemon.getSpecies().getTranslatedName().getString();
            Ability ability = pokemon.getAbility();
            String abilityName = ability.getTranslatedName().getString();
            String abilityDescKey = ability.getTranslationKey() + ".description";
            String abilityDesc = I18n.get(abilityDescKey);

            abilityRandomizer$cachedLine0 = ChatFormatting.BOLD + speciesName
                + ChatFormatting.RESET + " has the ability "
                + ChatFormatting.BOLD + abilityName;
            abilityRandomizer$cachedLine1 = abilityDesc;
        } catch (Exception e) {
            abilityRandomizer$cachedLine0 = fallback;
            abilityRandomizer$cachedLine1 = fallback;
        }
    }
}