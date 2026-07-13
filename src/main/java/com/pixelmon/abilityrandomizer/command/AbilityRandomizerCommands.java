package com.pixelmon.abilityrandomizer.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.config.ConfigProxy.Mode;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine.PoolDisplay;
import com.pixelmon.abilityrandomizer.core.AbilityRandomizerEngine.SpeciesAbilityMatch;
import com.pixelmonmod.api.registry.RegistryValue;
import com.pixelmonmod.pixelmon.api.pokemon.ability.Ability;
import com.pixelmonmod.pixelmon.api.pokemon.species.Species;
import com.pixelmonmod.pixelmon.api.registries.PixelmonSpecies;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers the mod's chat commands:
 *
 * <ul>
 *   <li>{@code /abilityinfo <Pokemon|ability>} — one greedy-string argument. If it resolves to a
 *       species, its Mode 1 randomized ability pool is listed (hidden ability flagged). Otherwise it
 *       is resolved as an ability and every species holding that ability is listed. Autofill offers
 *       both species names (like {@code /wiki}) and allowed ability names.</li>
 *   <li>{@code /randomizerhelp} — prints {@link RandomizerMessages#help()}.</li>
 * </ul>
 *
 * <p>Both are permission level 0 (available to everyone).</p>
 */
public final class AbilityRandomizerCommands {

    /** Bullet prefix for list lines. */
    private static final String BULLET = "\n  • ";

    private AbilityRandomizerCommands() {
    }

    /** Listener for {@link RegisterCommandsEvent} (registered on the game event bus). */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("abilityinfo")
                .requires(source -> true)
                .then(Commands.argument("query", StringArgumentType.greedyString())
                    .suggests(QUERY_SUGGESTIONS)
                    .executes(ctx -> runAbilityInfo(ctx.getSource(), StringArgumentType.getString(ctx, "query"))))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "Usage: /abilityinfo <Pokemon | ability>").withStyle(ChatFormatting.YELLOW), false);
                    return 0;
                })
        );

        dispatcher.register(
            Commands.literal("randomizerhelp")
                .requires(source -> true)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(RandomizerMessages::help, false);
                    return 1;
                })
        );
    }

    /** Combined species + allowed-ability name autofill for the greedy query argument. */
    private static final SuggestionProvider<CommandSourceStack> QUERY_SUGGESTIONS = (ctx, builder) -> {
        List<String> options = new ArrayList<>(PixelmonSpecies.getFormattedEnglishNameSet());
        options.addAll(AbilityRandomizerEngine.getAllowedAbilityDisplayNames());
        return SharedSuggestionProvider.suggest(options, builder);
    };

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private static int runAbilityInfo(CommandSourceStack source, String rawQuery) {
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Usage: /abilityinfo <Pokemon | ability>")
                .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        // Species takes precedence: /abilityinfo <Pokemon>.
        Optional<Species> species = resolveSpecies(query);
        if (species.isPresent()) {
            sendPokemonInfo(source, species.get());
            return 1;
        }

        // Otherwise treat it as an ability: /abilityinfo <ability>.
        Optional<Ability> ability = AbilityRandomizerEngine.resolveAbility(query);
        if (ability.isPresent()) {
            sendAbilityInfo(source, ability.get());
            return 1;
        }

        source.sendFailure(Component.literal("No Pokemon or ability found matching \"" + query + "\".")
            .withStyle(ChatFormatting.RED));
        return 0;
    }

    // ------------------------------------------------------------------
    // /abilityinfo <Pokemon>
    // ------------------------------------------------------------------

    private static void sendPokemonInfo(CommandSourceStack source, Species species) {
        String speciesName = species.getTranslatedName().getString();
        Optional<PoolDisplay> pool = AbilityRandomizerEngine.getMode1PoolForSpecies(species);
        if (pool.isEmpty()) {
            source.sendSuccess(() -> explainNoPool(species, speciesName), false);
            return;
        }

        // Empty root so ONLY the explicitly-bold header is bold; the list lines inherit no style.
        MutableComponent message = Component.empty()
            .append(Component.literal(speciesName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" randomized ability pool:").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        for (Ability ability : pool.get().getNormal()) {
            message.append(bullet()).append(abilityLine(ability, false));
        }
        for (Ability ability : pool.get().getHidden()) {
            message.append(bullet()).append(abilityLine(ability, true));
        }
        source.sendSuccess(() -> message, false);
    }

    /**
     * One pool line: "{@code <Name>: <description>}" — name white, Pixelmon's ability description in
     * light gray, neither bold — with a hidden-ability tag appended when applicable. The description
     * is a translatable component so each client resolves it in its own language.
     */
    private static MutableComponent abilityLine(Ability ability, boolean hidden) {
        MutableComponent line = Component.literal(ability.getTranslatedName().getString() + ": ")
                .withStyle(ChatFormatting.WHITE)
            .append(Component.translatable(ability.getTranslationKey() + ".description")
                .withStyle(ChatFormatting.GRAY));
        if (hidden) {
            line.append(hiddenTag());
        }
        return line;
    }

    private static Component explainNoPool(Species species, String speciesName) {
        Mode mode = ConfigProxy.effectiveMode();
        if (mode == Mode.SPECIES_CONSISTENT) {
            if (AbilityRandomizerEngine.isSpeciesExcluded(species)) {
                return Component.literal(speciesName
                    + " is excluded from randomization; it keeps its vanilla abilities.")
                    .withStyle(ChatFormatting.YELLOW);
            }
            return Component.literal("The ability pool for " + speciesName
                + " isn't ready yet. Try again in a moment.").withStyle(ChatFormatting.YELLOW);
        }
        return Component.literal(modeExplanation(mode)).withStyle(ChatFormatting.YELLOW);
    }

    // ------------------------------------------------------------------
    // /abilityinfo <ability>
    // ------------------------------------------------------------------

    private static void sendAbilityInfo(CommandSourceStack source, Ability ability) {
        String abilityName = ability.getTranslatedName().getString();
        Mode mode = ConfigProxy.effectiveMode();
        if (mode != Mode.SPECIES_CONSISTENT) {
            source.sendSuccess(() -> Component.literal(modeExplanation(mode)).withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        List<SpeciesAbilityMatch> matches = AbilityRandomizerEngine.findSpeciesWithAbility(ability.getName());
        if (matches.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No Pokemon have ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(abilityName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" in their randomized pool.").withStyle(ChatFormatting.WHITE)), false);
            return;
        }

        // Empty root so ONLY the explicitly-bold header is bold; the species lines inherit no style.
        MutableComponent message = Component.empty()
            .append(Component.literal(abilityName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" appears in these Pokemon's pools (" + matches.size() + "):")
                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        for (SpeciesAbilityMatch match : matches) {
            message.append(bullet())
                .append(Component.literal(match.getSpecies().getTranslatedName().getString())
                    .withStyle(ChatFormatting.WHITE));
            if (match.isHidden()) {
                message.append(hiddenTag());
            }
        }
        source.sendSuccess(() -> message, false);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Optional<Species> resolveSpecies(String query) {
        Optional<RegistryValue<Species>> value = PixelmonSpecies.getLocalized(query);
        if (value.isEmpty()) {
            value = PixelmonSpecies.get(query);
        }
        if (value.isPresent() && value.get().getValue().isPresent()) {
            Species species = value.get().getValue().get();
            if (!species.is(PixelmonSpecies.MISSINGNO)) {
                return Optional.of(species);
            }
        }
        return Optional.empty();
    }

    private static String modeExplanation(Mode mode) {
        if (mode == Mode.FULLY_RANDOM) {
            return "Fully-random mode (Mode 2) is active: every individual Pokemon gets its own random "
                + "ability, so there is no fixed per-species pool to list.";
        }
        return "Randomization is currently disabled; Pokemon use their vanilla abilities.";
    }

    private static MutableComponent bullet() {
        return Component.literal(BULLET).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent hiddenTag() {
        return Component.literal(" (Hidden Ability)").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC);
    }
}
