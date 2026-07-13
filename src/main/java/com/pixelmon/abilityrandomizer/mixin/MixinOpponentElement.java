package com.pixelmon.abilityrandomizer.mixin;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pixelmon.abilityrandomizer.client.IAbilityDataHolder;
import com.pixelmon.abilityrandomizer.config.AbilityRandomizerConfig;
import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmonmod.pixelmon.api.battles.BattleType;
import com.pixelmonmod.pixelmon.api.util.helpers.ResourceLocationHelper;
import com.pixelmonmod.pixelmon.client.ClientProxy;
import com.pixelmonmod.pixelmon.client.gui.ScreenHelper;
import com.pixelmonmod.pixelmon.client.gui.battles.ClientBattleManager;
import com.pixelmonmod.pixelmon.client.gui.battles.PixelmonClientData;
import com.pixelmonmod.pixelmon.client.gui.battles.pokemonOverlays.OpponentElement;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws an "ability info" panel to the right of the opponent's info container during
 * <strong>normal wild SINGLE battles only</strong>.
 *
 * <p>Injected at {@code TAIL} of {@link OpponentElement#drawElement} so it renders on top of the
 * finished opponent overlay, inheriting the blend state Pixelmon already enabled. Two lines are
 * shown, centered inside the {@code abilityinfo.png} frame:</p>
 * <pre>
 *   &lt;Opponent Name&gt; Ability:   (bold)
 *   &lt;Ability Name&gt;              (plain)
 * </pre>
 *
 * <h2>Coordinate space</h2>
 * {@code drawElement} runs inside {@code PixelmonWidget.drawElementScaled}, which has already pushed
 * {@code matrix.scale(scale, scale, scale)}. All drawing therefore uses <em>unscaled</em> logical
 * coordinates (exactly like Pixelmon's own code in {@code drawElement}); the active matrix applies
 * the GUI scale. Multiplying by {@code scale} here would double-scale and mis-place the panel.
 * Likewise text uses a fixed {@code 12.0F} font size (not {@code 12 * scale}).
 *
 * <p>{@code remap = false} because {@code drawElement} is a Pixelmon method.</p>
 */
@Mixin(value = OpponentElement.class, priority = 1500)
public abstract class MixinOpponentElement {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    @Unique
    private static final ResourceLocation abilityRandomizer$ABILITY_INFO =
        ResourceLocationHelper.of("pixelmonabilityrandomizer", "textures/gui/battle/abilityinfo.png");

    static {
        if (abilityRandomizer$ABILITY_INFO == null) {
            LOGGER.error("[AbilityRandomizer] Failed to resolve abilityinfo.png resource location; "
                + "battle-HUD ability panel will render without its background frame.");
        }
    }

    /**
     * z-level for the panel. {@code zLevel} is declared on the superclass {@code PixelmonWidget}, and
     * Mixin cannot {@code @Shadow} that inherited field on {@code OpponentElement} (it crashes with
     * "field zLevel was not located in the target class"). The battle overlays draw at z 0, so a
     * literal 0 keeps our panel on the same layer as the opponent frame.
     */
    @Unique
    private static final float abilityRandomizer$Z_LEVEL = 0.0F;

    /** Panel draw width: opponent's 160px slot scaled to abilityinfo.png's 624/1534 width ratio. */
    @Unique
    private static final float ABILITYRANDOMIZER_PANEL_WIDTH = 160.0F * 624.0F / 1534.0F; // 65.085

    /** X of opponent.png's visible right edge (its frame ends before the nominal 160 due to padding). */
    @Unique
    private static final float ABILITYRANDOMIZER_OPPONENT_VISIBLE_RIGHT = 160.0F * 1461.0F / 1534.0F; // 152.38

    /** Extra leftward overlap (panel px) so the panel bites 1px further into the opponent overlay. */
    @Unique
    private static final float ABILITYRANDOMIZER_OVERLAP = 1.0F;

    /** Ability text size — a touch smaller than the 16px opponent name so it sits neatly in the frame. */
    @Unique
    private static final float ABILITYRANDOMIZER_FONT_SIZE = 11.5F;

    /** Tight line spacing (panel px) between stacked text lines. */
    @Unique
    private static final float ABILITYRANDOMIZER_LINE_HEIGHT = 8.0F;

    /**
     * Vertical center of the text block, relative to panelY. Base is the frame's inner content
     * center (~21), nudged down by user tuning of (24 + 32) source-image px. Image->panel vertical
     * scale is 50/457, so the nudge is 56 * (50/457) ~= 6.1 panel px (total center ~27.1).
     */
    @Unique
    private static final float ABILITYRANDOMIZER_TEXT_CENTER_Y = 21.0F + (24.0F + 32.0F) * (50.0F / 457.0F);

    /**
     * Text-only horizontal nudge (does NOT move the frame image): 3 source-image px right. Image->panel
     * horizontal scale is 160/1534, so this is 3 * (160/1534) ~= 0.31 panel px.
     */
    @Unique
    private static final float ABILITYRANDOMIZER_TEXT_X_NUDGE = 3.0F * (160.0F / 1534.0F);

    /** Wrap the ability name onto extra lines (at word boundaries) once it exceeds this length. */
    @Unique
    private static final int ABILITYRANDOMIZER_WRAP_LIMIT = 13;

    /** Font/leading multiplier applied when the ability name exceeds the wrap limit (e.g. 1.0 -> 0.75). */
    @Unique
    private static final float ABILITYRANDOMIZER_SHRINK_FACTOR = 0.75F;

    @Inject(method = "drawElement", at = @At("TAIL"), remap = false)
    private void abilityRandomizer$drawAbilityInfo(GuiGraphics graphics, float scale, CallbackInfo ci) {
        AbilityRandomizerConfig cfg = ConfigProxy.get();
        if (cfg == null || !cfg.isShowAbilityInBattleHud()) {
            return;
        }

        ClientBattleManager bm = ClientProxy.battleManager;
        if (!abilityRandomizer$shouldShow(bm)) {
            return;
        }

        OpponentElement self = (OpponentElement) (Object) this;
        PixelmonClientData enemy = self.getEnemy();
        if (enemy == null) {
            return;
        }

        // Resolve the ability name in the CLIENT's language from the stored translation key.
        String key = ((IAbilityDataHolder) enemy).abilityRandomizer$getAbilityKey();
        String abilityName = (key == null || key.isEmpty()) ? "???" : I18n.get(key);

        // opponent.png (1534x457 source) is drawn at (x, y-3) as 160x50. Draw abilityinfo.png
        // (624x457) at the SAME per-pixel scale so its frame art is NOT stretched:
        //   width  = 160 * 624 / 1534 = 65.085  (horizontal scale 160/1534, matching opponent)
        //   height = 50                          (vertical scale 50/457, matching opponent)
        float panelW = ABILITYRANDOMIZER_PANEL_WIDTH;
        float panelH = 50.0F;
        // Anchor to opponent's VISIBLE right edge, not the nominal 160. opponent.png has ~73px of
        // transparent right padding in-source, so its drawn frame ends at x + 160*1461/1534 = 152.38.
        // Starting here makes the two boxes touch with no gap. abilityinfo.png content is flush-left.
        float panelX = self.getX() + ABILITYRANDOMIZER_OPPONENT_VISIBLE_RIGHT
            - ABILITYRANDOMIZER_OVERLAP + cfg.getAbilityPanelXOffset();
        float panelY = self.getY() - 3.0F + cfg.getAbilityPanelYOffset();

        if (abilityRandomizer$ABILITY_INFO != null) {
            ScreenHelper.drawImage(graphics, abilityRandomizer$ABILITY_INFO, panelX, panelY, panelW, panelH, abilityRandomizer$Z_LEVEL);
        }

        float centerX = panelX + panelW / 2.0F + ABILITYRANDOMIZER_TEXT_X_NUDGE;

        // Long ability names (past the wrap limit) both wrap AND shrink so the extra lines still fit.
        boolean shrink = abilityName.length() > ABILITYRANDOMIZER_WRAP_LIMIT;
        float fontSize = shrink ? ABILITYRANDOMIZER_FONT_SIZE * ABILITYRANDOMIZER_SHRINK_FACTOR
                                : ABILITYRANDOMIZER_FONT_SIZE;
        float lineHeight = shrink ? ABILITYRANDOMIZER_LINE_HEIGHT * ABILITYRANDOMIZER_SHRINK_FACTOR
                                  : ABILITYRANDOMIZER_LINE_HEIGHT;

        // Build the stacked lines: a bold "Ability:" header, then the ability name wrapped onto
        // extra lines at word boundaries when it is long (never splitting inside a word).
        List<String> lines = new ArrayList<>();
        lines.add(ChatFormatting.BOLD + "Ability:");
        for (String part : abilityRandomizer$wrapByWord(abilityName, ABILITYRANDOMIZER_WRAP_LIMIT)) {
            lines.add(part);
        }

        // Vertically center the whole block around the frame's content center, with tight leading.
        float blockTop = (panelY + ABILITYRANDOMIZER_TEXT_CENTER_Y) - (lines.size() * lineHeight) / 2.0F;
        for (int i = 0; i < lines.size(); i++) {
            // drawScaledCenteredString centers on x and handles its own font-size matrix push/pop.
            ScreenHelper.drawScaledCenteredString(
                graphics, lines.get(i), centerX, blockTop + i * lineHeight, -1, fontSize);
        }
    }

    /**
     * True only for a normal wild SINGLE battle in progress: exactly one opponent, catchable
     * (excludes trainer/PvP battles), not a raid/horde/double/triple/rotation, not already ended.
     */
    @Unique
    private boolean abilityRandomizer$shouldShow(ClientBattleManager bm) {
        if (bm == null || bm.battleEnded) {
            return false;
        }
        if (bm.battleType != BattleType.SINGLE) {
            return false;
        }
        if (!((ClientBattleManagerAccessor) bm).abilityRandomizer$getCatchPossible()) {
            return false; // trainer battle, PvP, or otherwise uncatchable
        }
        return bm.displayedEnemyPokemon != null && bm.displayedEnemyPokemon.length == 1;
    }

    /**
     * Greedy word-wrap: packs whole words onto lines no longer than {@code limit} characters, so a
     * name is only split at spaces — never mid-word. A single word longer than {@code limit} is left
     * whole on its own line (we never hyphenate/truncate). Names within the limit return one line.
     *
     * <p>Example ({@code limit=13}): {@code "Supreme Overlord"} -> {@code ["Supreme", "Overlord"]};
     * {@code "Keen Eye"} -> {@code ["Keen Eye"]}.</p>
     */
    @Unique
    private List<String> abilityRandomizer$wrapByWord(String text, int limit) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= limit) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
