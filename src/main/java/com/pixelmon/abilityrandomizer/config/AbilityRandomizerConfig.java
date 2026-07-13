package com.pixelmon.abilityrandomizer.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.pixelmonmod.pixelmon.api.config.api.data.ConfigPath;
import com.pixelmonmod.pixelmon.api.config.api.yaml.AbstractYamlConfig;

import info.pixelmon.repack.org.spongepowered.objectmapping.ConfigSerializable;
import info.pixelmon.repack.org.spongepowered.objectmapping.meta.Comment;

/**
 * Configuration for the Pixelmon Ability Randomizer.
 *
 * <p>The file is generated at {@code config/PixelmonAbilityRandomizer.yaml} the first
 * time the mod loads. It is a Pixelmon-style YAML config (same system Pixelmon itself
 * uses), so it hot-loads with the rest of Pixelmon's configs.</p>
 */
@ConfigSerializable
@ConfigPath("config/PixelmonAbilityRandomizer.yaml")
public class AbilityRandomizerConfig extends AbstractYamlConfig {

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    @Comment("Randomization mode settings")
    private ModeSection mode = new ModeSection();

    @Comment("Seed settings")
    private SeedSection seed = new SeedSection();

    @Comment("Ability blacklist settings")
    private BlacklistSection blacklist = new BlacklistSection();

    @Comment("Per-species exclusions")
    private ExclusionsSection exclusions = new ExclusionsSection();

    @Comment("Battle HUD ability display settings")
    private BattleHudSection battleHud = new BattleHudSection();

    @Comment("Chat message settings")
    private MessagesSection messages = new MessagesSection();

    // ------------------------------------------------------------------
    // Section inner classes
    // ------------------------------------------------------------------

    @ConfigSerializable
    public static class ModeSection {
        @Comment(
            "MODE 1 - Species-consistent ability pools (ENABLED by default).\n"
            + "Every Pokemon species gets a randomized-but-consistent ability pool: the same\n"
            + "number of normal abilities it normally has, plus a hidden ability if it normally\n"
            + "has one. Every individual of that species draws from the same pool, and the pool is\n"
            + "shared across the whole evolution line (e.g. Meowth and Persian share one pool).\n"
            + "The pool is stable across restarts and unique per world (see fixedSeed).\n"
            + "If both speciesConsistent and fullyRandom are enabled, speciesConsistent wins.\n"
            + "If both are disabled, abilities are left as vanilla Pixelmon (no randomization)."
        )
        private boolean speciesConsistent = true;

        @Comment(
            "MODE 2 - Fully random per-individual abilities (DISABLED by default).\n"
            + "Every individual Pokemon gets its own random ability, independent of species.\n"
            + "Fight ten Rattata and they will most likely all have different abilities.\n"
            + "Each individual's ability is stable for that individual (keyed to its UUID) so it\n"
            + "persists across logout/restart and does not reroll on its own.\n"
            + "Ignored when speciesConsistent is also enabled."
        )
        private boolean fullyRandom = false;

        @Comment("When true, extra debug messages are written to the log.")
        private boolean debugLogging = false;
    }

    @ConfigSerializable
    public static class SeedSection {
        @Comment(
            "OPTIONAL fixed seed for the randomized ability pools.\n"
            + "Leave at 0 (default) for the recommended behavior: each WORLD/SERVER gets its own random\n"
            + "seed, generated the first time it loads and stored inside that world's save\n"
            + "(<world>/PixelmonAbilityRandomizer-seed.txt). That makes the abilities persistent within a\n"
            + "world but different between worlds/servers.\n"
            + "Set this to any specific non-zero number ONLY if you want the exact same ability mapping\n"
            + "forced across every world and server (e.g. to share a known layout). This overrides the\n"
            + "per-world seed."
        )
        private long fixedSeed = 0L;
    }

    @ConfigSerializable
    public static class BlacklistSection {
        @Comment(
            "Master switch for the ability blacklist (ENABLED by default).\n"
            + "When true, no Pokemon can be given any ability in 'abilities' below,\n"
            + "and the type-based restrictions are also enforced."
        )
        private boolean enabled = true;

        @Comment(
            "Abilities that NO Pokemon may ever be randomized into.\n"
            + "Write each ability by its in-game name (spaces and capitalization are ignored -\n"
            + "'Slow Start', 'slowstart' and 'SlowStart' all match the same ability).\n"
            + "Note: the internal placeholder abilities 'ComingSoon' and 'Error' are always\n"
            + "excluded regardless of this list, because they are not real abilities."
        )
        private List<String> abilities = new ArrayList<>(Arrays.asList(
            // Self-detrimental / overpowered (kept from the original defaults)
            "Truant",
            "Slow Start",
            "Wonder Guard",
            "Klutz",
            "Revenant",
            // Species- or form-locked signature abilities (do nothing on any other Pokemon)
            "Zen Mode",
            "Schooling",
            "Shields Down",
            "Stance Change",
            "Zero to Hero",
            "Ice Face",
            "Power Construct",
            "Battle Bond",
            "Forecast",
            "Poison Puppeteer",
            "Hunger Switch",
            "Gulp Missile",
            "Disguise",
            "Flower Gift",
            "Commander",
            // Signature abilities with no coded battle effect on a random Pokemon
            "Multitype",
            "RKS System",
            "Honey Gather",
            "ComingSoon",
            // Doubles-only support abilities (inert in single battles)
            "Plus",
            "Minus",
            "Battery",
            "Power Spot",
            "Friend Guard",
            "Healer",
            "Symbiosis",
            "Costar"
        ));

        @Comment(
            "Type-based ability restrictions (only enforced when the blacklist is enabled).\n"
            + "These stop obviously-broken or redundant combinations:\n"
            + "  - Levitate is never given to a Flying type.\n"
            + "  - Lightning Rod / Volt Absorb / Motor Drive are never given to a Ground type.\n"
            + "  - Flash Fire is never given to a Fire type.\n"
            + "  - Sap Sipper is never given to a Grass type.\n"
            + "  - Normalize is never given to a Normal type.\n"
            + "  - Water Absorb / Storm Drain are never given to a Water type.\n"
            + "For Mode 1 the restriction applies across the whole evolution line, so no member\n"
            + "of a line can end up with an illegal ability."
        )
        private boolean enableTypeRestrictions = true;
    }

    @ConfigSerializable
    public static class ExclusionsSection {
        @Comment(
            "Pokemon listed here are LEFT ALONE - they keep their normal vanilla abilities and\n"
            + "are never randomized (in either mode). Empty by default.\n"
            + "\n"
            + "STRUCTURE: write the Pokemon's plain species name, exactly as it appears in-game,\n"
            + "one entry per list item. Capitalization and surrounding spaces are ignored.\n"
            + "Use only the base species name - do NOT include form or regional prefixes.\n"
            + "Examples:\n"
            + "  - Pikachu\n"
            + "  - Charizard\n"
            + "  - Mr. Mime\n"
            + "An excluded species is excluded across its whole evolution line only if you list each\n"
            + "stage you want excluded; listing 'Meowth' excludes Meowth but not Persian."
        )
        private List<String> pokemon = new ArrayList<>();
    }

    @ConfigSerializable
    public static class BattleHudSection {
        @Comment(
            "Show the opponent's ability on the battle HUD (ENABLED by default).\n"
            + "When enabled, an 'abilityInfo' panel appears to the right of the opponent's info\n"
            + "container, showing the Pokemon's name and its (randomized) ability.\n"
            + "Only shown in normal SINGLE WILD battles - never in trainer, PvP, double, triple,\n"
            + "rotation, raid or horde battles."
        )
        private boolean showAbilityInBattleHud = true;

        @Comment(
            "Horizontal nudge (in GUI pixels) applied to the ability panel, relative to the right\n"
            + "edge of the opponent's info container. Positive = right, negative = left. Default: 0."
        )
        private int abilityPanelXOffset = 0;

        @Comment(
            "Vertical nudge (in GUI pixels) applied to the ability panel, relative to the top of\n"
            + "the opponent's info container. Positive = down, negative = up. Default: 0."
        )
        private int abilityPanelYOffset = 0;
    }

    @ConfigSerializable
    public static class MessagesSection {
        @Comment(
            "Show a welcome banner in chat when a player joins (ENABLED by default).\n"
            + "The banner thanks the player and points them at /randomizerhelp. Set to false to\n"
            + "stop it from appearing on join."
        )
        private boolean showLoginBanner = true;
    }

    // ------------------------------------------------------------------
    // Getters (delegate to sections, null-safe)
    // ------------------------------------------------------------------

    public boolean isMode1SpeciesConsistent() {
        return mode != null && mode.speciesConsistent;
    }

    public boolean isMode2FullyRandom() {
        return mode != null && mode.fullyRandom;
    }

    public boolean isDebugLogging() {
        return mode != null && mode.debugLogging;
    }

    public long getFixedSeed() {
        return seed != null ? seed.fixedSeed : 0L;
    }

    public boolean isEnableBlacklist() {
        return blacklist != null && blacklist.enabled;
    }

    public List<String> getBlacklistedAbilities() {
        if (blacklist == null || blacklist.abilities == null) {
            return new ArrayList<>();
        }
        return blacklist.abilities;
    }

    public boolean isEnableTypeRestrictions() {
        return blacklist != null && blacklist.enableTypeRestrictions;
    }

    public List<String> getExcludedPokemon() {
        if (exclusions == null || exclusions.pokemon == null) {
            return new ArrayList<>();
        }
        return exclusions.pokemon;
    }

    public boolean isShowAbilityInBattleHud() {
        return battleHud != null && battleHud.showAbilityInBattleHud;
    }

    public int getAbilityPanelXOffset() {
        return battleHud != null ? battleHud.abilityPanelXOffset : 0;
    }

    public int getAbilityPanelYOffset() {
        return battleHud != null ? battleHud.abilityPanelYOffset : 0;
    }

    public boolean isShowLoginBanner() {
        return messages != null && messages.showLoginBanner;
    }

    // ------------------------------------------------------------------
    // Setters (used for seed write-back and tests)
    // ------------------------------------------------------------------

    public void setMode1SpeciesConsistent(boolean value) {
        if (mode != null) {
            mode.speciesConsistent = value;
        }
    }

    public void setMode2FullyRandom(boolean value) {
        if (mode != null) {
            mode.fullyRandom = value;
        }
    }

    public void setDebugLogging(boolean value) {
        if (mode != null) {
            mode.debugLogging = value;
        }
    }

    public void setFixedSeed(long value) {
        if (seed != null) {
            seed.fixedSeed = value;
        }
    }

    public void setEnableBlacklist(boolean value) {
        if (blacklist != null) {
            blacklist.enabled = value;
        }
    }

    public void setBlacklistedAbilities(List<String> value) {
        if (blacklist != null) {
            blacklist.abilities = value;
        }
    }

    public void setEnableTypeRestrictions(boolean value) {
        if (blacklist != null) {
            blacklist.enableTypeRestrictions = value;
        }
    }

    public void setExcludedPokemon(List<String> value) {
        if (exclusions != null) {
            exclusions.pokemon = value;
        }
    }
}
