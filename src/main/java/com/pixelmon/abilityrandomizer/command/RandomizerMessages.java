package com.pixelmon.abilityrandomizer.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builders for the mod's chat messages ({@code /randomizerhelp} output and the on-join banner).
 *
 * <p>Each text segment is appended to an empty root component with its own explicit style. Because
 * Minecraft component siblings inherit only from their parent (not from the previous sibling), an
 * empty root means each segment's bold/italic/color is independent — no style bleeds between them.</p>
 */
public final class RandomizerMessages {

    private RandomizerMessages() {
    }

    /**
     * The {@code /randomizerhelp} message:
     * <blockquote>
     * <b><span style="color:blue">Pixelmon Ability Randomizer</span></b> by
     * <b><span style="color:red">CCDelic.</span></b><br>
     * <span style="color:lightgray">Report issues to the GitHub link on Modrinth.</span><br>
     * Use the command <i><span style="color:green">/abilityinfo</span></i> to see information on
     * specific <u>Pokemon</u> OR <u>Abilities</u>.
     * </blockquote>
     */
    public static Component help() {
        return Component.empty()
            .append(Component.literal("Pixelmon Ability Randomizer ")
                .withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
            .append(Component.literal("by ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("CCDelic.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
            .append(Component.literal("\n"))
            .append(Component.literal("Report issues to the GitHub link on Modrinth.")
                .withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n"))
            .append(Component.literal("Use the command ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("/abilityinfo ").withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC))
            .append(Component.literal("to see information on specific ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("Pokemon").withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
            .append(Component.literal(" OR ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("Abilities").withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
            .append(Component.literal(".").withStyle(ChatFormatting.WHITE));
    }

    /**
     * The on-join banner (modelled on the Pixelmon Speedup welcome message: bold-yellow greeting,
     * a blank line, then the help pointer):
     * <blockquote>
     * <b><span style="color:gold">Thanks for using Ability Randomizer by CCDelic!</span></b><br>
     * <br>
     * <span style="color:white">Use</span>
     * <i><span style="color:purple">/randomizerhelp</span></i>
     * <span style="color:white">for help. This message can be disabled in the config.</span>
     * </blockquote>
     */
    public static Component loginBanner() {
        MutableComponent message = Component.empty()
            .append(Component.literal("Thanks for using Ability Randomizer by CCDelic!\n")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(Component.literal("Use ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("/randomizerhelp ")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC))
            .append(Component.literal("for help. This message can be disabled in the config.\n")
                .withStyle(ChatFormatting.WHITE));
        return message;
    }
}
