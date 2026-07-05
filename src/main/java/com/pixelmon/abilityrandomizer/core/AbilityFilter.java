package com.pixelmon.abilityrandomizer.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.pixelmon.abilityrandomizer.config.AbilityRandomizerConfig;
import com.pixelmonmod.pixelmon.api.pokemon.species.Stats;
import com.pixelmonmod.pixelmon.api.pokemon.type.Type;

import net.minecraft.resources.ResourceKey;

/**
 * Central place for deciding which abilities are legal.
 *
 * <p>Handles two things:
 * <ul>
 *   <li>The flat blacklist (config list plus the always-excluded internal placeholders).</li>
 *   <li>The type-based caveats (e.g. Levitate never on a Flying type).</li>
 * </ul>
 */
public final class AbilityFilter {

    /**
     * Non-functional placeholder "abilities" that must never be handed out regardless of
     * user config, because they are not real abilities.
     */
    private static final Set<String> ALWAYS_EXCLUDED = normalizedSet(
        "ComingSoon",
        "Error"
    );

    private AbilityFilter() {
    }

    /** Normalize an ability (or user-typed) name: lowercase, strip everything non-alphanumeric. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> normalizedSet(String... names) {
        Set<String> set = new HashSet<>();
        for (String n : names) {
            set.add(normalize(n));
        }
        return set;
    }

    /**
     * Build the set of normalized ability names that are globally forbidden (placeholders plus,
     * if the blacklist is enabled, the user's blacklist). Type caveats are handled separately
     * because they depend on the target Pokemon's types.
     */
    public static Set<String> buildGlobalBlacklist(AbilityRandomizerConfig config) {
        Set<String> blocked = new HashSet<>(ALWAYS_EXCLUDED);
        if (config != null && config.isEnableBlacklist()) {
            for (String name : config.getBlacklistedAbilities()) {
                blocked.add(normalize(name));
            }
        }
        return blocked;
    }

    /**
     * Whether the given ability (by normalized name) may legally be placed on the given form,
     * considering only the type caveats. Returns true if there is no type conflict.
     */
    public static boolean passesTypeCaveats(String normalizedAbility, Stats form, boolean typeRestrictionsEnabled) {
        if (!typeRestrictionsEnabled || form == null) {
            return true;
        }
        switch (normalizedAbility) {
            case "levitate":
                return !form.isType(Type.FLYING);
            case "lightningrod":
            case "voltabsorb":
            case "motordrive":
                return !form.isType(Type.GROUND);
            case "flashfire":
                return !form.isType(Type.FIRE);
            case "sapsipper":
                return !form.isType(Type.GRASS);
            case "normalize":
                return !form.isType(Type.NORMAL);
            case "waterabsorb":
            case "stormdrain":
                return !form.isType(Type.WATER);
            default:
                return true;
        }
    }

    /**
     * Whether the given ability may legally be placed on a Pokemon whose evolution line collectively
     * has the supplied set of caveat-relevant types. Used by Mode 1 so that no member of a line ends
     * up with an illegal ability.
     */
    public static boolean passesLineTypeCaveats(String normalizedAbility, Set<ResourceKey<Type>> lineTypes,
                                                boolean typeRestrictionsEnabled) {
        if (!typeRestrictionsEnabled || lineTypes == null || lineTypes.isEmpty()) {
            return true;
        }
        switch (normalizedAbility) {
            case "levitate":
                return !lineTypes.contains(Type.FLYING);
            case "lightningrod":
            case "voltabsorb":
            case "motordrive":
                return !lineTypes.contains(Type.GROUND);
            case "flashfire":
                return !lineTypes.contains(Type.FIRE);
            case "sapsipper":
                return !lineTypes.contains(Type.GRASS);
            case "normalize":
                return !lineTypes.contains(Type.NORMAL);
            case "waterabsorb":
            case "stormdrain":
                return !lineTypes.contains(Type.WATER);
            default:
                return true;
        }
    }
}
