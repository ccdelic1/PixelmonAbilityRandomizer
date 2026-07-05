package com.pixelmon.abilityrandomizer.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.pixelmon.abilityrandomizer.config.AbilityRandomizerConfig;
import com.pixelmon.abilityrandomizer.config.ConfigProxy;
import com.pixelmon.abilityrandomizer.config.ConfigProxy.Mode;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.pokemon.ability.Ability;
import com.pixelmonmod.pixelmon.api.pokemon.ability.AbilityRegistry;
import com.pixelmonmod.pixelmon.api.pokemon.species.Species;
import com.pixelmonmod.pixelmon.api.pokemon.species.Stats;
import com.pixelmonmod.pixelmon.api.pokemon.stats.evolution.Evolution;
import com.pixelmonmod.pixelmon.api.pokemon.type.Type;
import com.pixelmonmod.pixelmon.api.registries.PixelmonSpecies;
import com.pixelmonmod.api.registry.RegistryValue;

import net.minecraft.resources.ResourceKey;

/**
 * Core logic that decides and applies the randomized ability for a Pokemon.
 *
 * <p>This is invoked from a mixin at the tail of {@code Pokemon.resetAbility()}. That method is
 * Pixelmon's single funnel for freshly assigning an ability: it runs on creation (wild spawns,
 * starters, /pokespawn, /pokegive without an explicit ability, hatched eggs...) and on evolution,
 * but crucially it is NOT run when a Pokemon is loaded from NBT that already stores an ability.
 * That is why our changes persist across logout / restart / server reboot: once we set an ability
 * it is written to NBT under "Ability" and restored directly on load.</p>
 *
 * <h2>Mode 1 (species-consistent)</h2>
 * We let vanilla pick the ability <em>slot</em> (respecting hidden-ability rarity), then remap the
 * ability identity through a per-evolution-line pool indexed by that slot. Because the pool is keyed
 * to the line's base species and indexed by slot, every individual of the line draws from the same
 * pool and an evolving Pokemon keeps the same slot, hence the same ability.
 *
 * <h2>Mode 2 (fully random)</h2>
 * Each individual gets an ability chosen from a per-UUID shuffle of all legal abilities, so it is
 * unique per individual yet stable for that individual across reloads and evolution.
 */
public final class AbilityRandomizerEngine {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");

    /** All caveat-relevant types, checked when gathering a line's type set. */
    private static final ResourceKey<Type>[] CAVEAT_TYPES = caveatTypes();

    /** Regional form tags Pixelmon recognises (see {@code FormTags}). */
    private static final String[] REGIONAL_TAGS = {"alolan", "galarian", "hisuian", "paldean"};

    /** Pool cache keyed by base-species name + regional tag (lower-case). */
    private static final ConcurrentHashMap<String, Pool> POOL_CACHE = new ConcurrentHashMap<>();

    /**
     * Cache of resolved regional tag per form (key {@code species|form}); value is the tag or ""
     * for the base/non-regional line. Avoids re-walking evolution lines on every spawn.
     */
    private static final ConcurrentHashMap<String, String> EFFECTIVE_TAG_CACHE = new ConcurrentHashMap<>();

    /** Cached list of legal ability canonical names (Mode 2 draws from this). Rebuilt on invalidate. */
    private static volatile List<String> allowedNamesCache;

    /** Seed the caches were built against, so a config change forces a rebuild. */
    private static volatile long cacheSeed = Long.MIN_VALUE;

    /** Largest ability-registry size we have ever observed; used to detect a partial (mid-sync) registry. */
    private static final AtomicInteger maxRegistrySize = new AtomicInteger(0);

    /** Whether the most recent {@link #getAllowedNames()} computation saw a complete registry. */
    private static volatile boolean lastRegistryComplete = false;

    /** Cached blacklist set — rebuilt once per cache lifecycle alongside {@link #allowedNamesCache}. */
    private static volatile Set<String> cachedBlacklist;

    private static boolean loggedNoAbilities = false;
    private static boolean loggedFirstAssign = false;

    private AbilityRandomizerEngine() {
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Type>[] caveatTypes() {
        return new ResourceKey[] {
            Type.FLYING, Type.GROUND, Type.FIRE, Type.GRASS, Type.NORMAL, Type.WATER
        };
    }

    /** Drop all cached pools / allowed lists. Call after the config reloads or when the world changes. */
    public static void invalidateCaches() {
        POOL_CACHE.clear();
        EFFECTIVE_TAG_CACHE.clear();
        allowedNamesCache = null;
        cachedBlacklist = null;
        maxRegistrySize.set(0);
        lastRegistryComplete = false;
        loggedNoAbilities = false;
        loggedFirstAssign = false;
    }

    /**
     * Randomize using the Pokemon's own recorded ability slot. Used by the wild-spawn safety-net
     * hook (entity-join), which runs after the entity is fully in the world - later than
     * {@code resetAbility}, so the ability registry is reliably populated by then.
     */
    public static void apply(Pokemon pokemon) {
        if (pokemon == null) {
            return;
        }
        int slot;
        boolean ha;
        try {
            slot = pokemon.getAbilitySlot();
            ha = pokemon.hasHiddenAbility();
        } catch (Exception e) {
            slot = -1;
            ha = false;
        }
        apply(pokemon, slot, ha);
    }

    /**
     * Tail hook for {@code Pokemon.resetAbility()}.
     *
     * @param pokemon    the Pokemon whose ability vanilla just (re)assigned
     * @param vanillaSlot the ability slot vanilla settled on ({@code Pokemon.slot})
     * @param vanillaHa   whether vanilla settled on the hidden ability ({@code Pokemon.ha})
     */
    public static void apply(Pokemon pokemon, int vanillaSlot, boolean vanillaHa) {
        if (pokemon == null || !ConfigProxy.isLoaded()) {
            return;
        }
        Mode mode = ConfigProxy.effectiveMode();
        if (mode == Mode.VANILLA) {
            return;
        }
        try {
            Stats form = pokemon.getForm();
            Species species = pokemon.getSpecies();
            if (form == null || species == null) {
                return;
            }
            if (isExcluded(species)) {
                return;
            }
            refreshCachesIfSeedChanged();

            // If the ability registry is not populated yet (e.g. mid registry-sync after a world
            // reload), do nothing and — crucially — do not cache the empty result, so the next
            // Pokemon retries once the registry is ready.
            if (getAllowedNames().isEmpty()) {
                if (ConfigProxy.isDebug()) {
                    LOGGER.warn("[AbilityRandomizer] Registry not ready when randomizing {}; will retry",
                        safeName(pokemon));
                }
                return;
            }

            if (mode == Mode.SPECIES_CONSISTENT) {
                applyMode1(pokemon, form, vanillaSlot, vanillaHa);
            } else {
                applyMode2(pokemon, form);
            }
        } catch (Exception e) {
            // Never let a randomization error break Pokemon creation.
            LOGGER.error("[AbilityRandomizer] Error while randomizing ability for {}", safeName(pokemon), e);
        }
    }

    // ------------------------------------------------------------------
    // Mode 1 - species-consistent pool
    // ------------------------------------------------------------------

    private static void applyMode1(Pokemon pokemon, Stats form, int vanillaSlot, boolean vanillaHa) {
        Pool pool = getOrBuildPool(form);
        if (pool == null || (pool.normal.length == 0 && pool.hidden.length == 0)) {
            return;
        }

        String chosen;
        if (vanillaHa && pool.hidden.length > 0) {
            int idx = Math.floorMod(vanillaSlot < 0 ? 0 : vanillaSlot, pool.hidden.length);
            chosen = pool.hidden[idx];
        } else if (pool.normal.length > 0) {
            int idx = Math.floorMod(vanillaSlot < 0 ? 0 : vanillaSlot, pool.normal.length);
            chosen = pool.normal[idx];
        } else {
            // No normal abilities in the pool but a hidden one exists.
            chosen = pool.hidden[0];
        }

        boolean asHidden = vanillaHa && pool.hidden.length > 0;
        int storeSlot = asHidden
            ? Math.floorMod(vanillaSlot < 0 ? 0 : vanillaSlot, pool.hidden.length)
            : (pool.normal.length > 0 ? Math.floorMod(vanillaSlot < 0 ? 0 : vanillaSlot, pool.normal.length) : 0);

        assignAbility(pokemon, chosen, storeSlot, asHidden);
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Mode1 {} -> {} (slot {}, hidden {})",
                safeName(pokemon), chosen, storeSlot, asHidden);
        }
    }

    private static Pool getOrBuildPool(Stats form) {
        Species base = null;
        try {
            base = form.getBaseEvolution();
        } catch (Exception e) {
            // Some evolution registry values can be uninitialized right after a world reload;
            // fall back to treating this form's own species as the line root.
            if (ConfigProxy.isDebug()) {
                LOGGER.warn("[AbilityRandomizer] getBaseEvolution failed for {}; using own species", form.getName(), e);
            }
        }
        Species rootSpecies = base != null ? base : form.getParentSpecies();
        // Regional variants (Alolan, Galarian, Hisuian, Paldean) get their own pool, separate from
        // the base-region line. Cross-regional evolutions into a distinct non-regional species
        // (e.g. Galarian Meowth -> Perrserker) are stitched back to their regional parent's line.
        String regionalTag;
        try {
            regionalTag = effectiveRegionalTag(form, rootSpecies);
        } catch (Exception e) {
            regionalTag = form.getRegionalTag();
        }
        String rootKey = rootSpecies.getName().toLowerCase(Locale.ROOT)
            + "|" + (regionalTag == null ? "" : regionalTag);

        Pool cached = POOL_CACHE.get(rootKey);
        if (cached != null) {
            return cached;
        }
        Pool pool = buildPool(rootSpecies, regionalTag);
        // Only cache a usable pool that was built from a COMPLETE registry. An empty or partial-
        // registry pool must not be cached, or it would permanently disable randomization for this
        // line until a config reload (this was the cause of abilities reverting to vanilla).
        if (pool != null && (pool.normal.length > 0 || pool.hidden.length > 0) && lastRegistryComplete) {
            POOL_CACHE.put(rootKey, pool);
        }
        return pool;
    }

    /**
     * The regional tag whose pool a form should draw from.
     *
     * <ul>
     *   <li>If the form is itself a regional form, that tag.</li>
     *   <li>Otherwise, if the species is a cross-regional evolution reachable only from a regional
     *       parent's evolution line (and not from the base-region line), that parent's tag.</li>
     *   <li>Otherwise the base-region line ({@code null}).</li>
     * </ul>
     */
    private static String effectiveRegionalTag(Stats form, Species base) {
        String own = form.getRegionalTag();
        if (own != null) {
            return own;
        }
        Species self = form.getParentSpecies();
        if (base == null || base.getName().equalsIgnoreCase(self.getName())) {
            return null; // this form is the base species itself
        }
        String cacheKey = self.getName().toLowerCase(Locale.ROOT) + "|" + form.getName().toLowerCase(Locale.ROOT);
        String cached = EFFECTIVE_TAG_CACHE.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String selfKey = self.getName().toLowerCase(Locale.ROOT);
        String resolved = "";
        // Only stitch to a regional line if the species is NOT part of the base-region line.
        if (!collectLineSpeciesNames(base, null).contains(selfKey)) {
            for (String tag : REGIONAL_TAGS) {
                if (hasRegionalForm(base, tag) && collectLineSpeciesNames(base, tag).contains(selfKey)) {
                    resolved = tag;
                    break;
                }
            }
        }
        EFFECTIVE_TAG_CACHE.put(cacheKey, resolved);
        return resolved.isEmpty() ? null : resolved;
    }

    private static boolean hasRegionalForm(Species species, String tag) {
        for (Stats candidate : species.getForms()) {
            if (tag.equals(candidate.getRegionalTag())) {
                return true;
            }
        }
        return false;
    }

    private static final Pool EMPTY_POOL = new Pool(new String[0], new String[0]);

    private static Pool buildPool(Species rootSpecies, String regionalTag) {
        List<String> allowed = getAllowedNames();
        if (allowed.isEmpty()) {
            // Registry not ready - return an (uncached) empty pool so it retries later.
            return EMPTY_POOL;
        }

        Stats rootForm = formForRegion(rootSpecies, regionalTag);
        if (rootForm == null) {
            rootForm = rootSpecies.getDefaultForm();
        }

        int normalCount = Math.max(1, rootForm.getAbilities().getAbilities().length);
        int hiddenCount = rootForm.getAbilities().getHiddenAbilities().length;
        if (hiddenCount > 1) {
            hiddenCount = 1; // vanilla species use at most one hidden ability slot
        }

        AbilityRandomizerConfig config = ConfigProxy.get();
        boolean typeRestrictions = config.isEnableBlacklist() && config.isEnableTypeRestrictions();

        // Line-type gathering walks the evolution graph, which can hit transient registry state
        // right after a world reload. A failure here must not abort the whole pool - fall back to
        // no type filtering so the Pokemon still gets a randomized ability.
        Set<ResourceKey<Type>> lineTypes;
        try {
            lineTypes = gatherLineTypes(rootSpecies, regionalTag);
        } catch (Exception e) {
            if (ConfigProxy.isDebug()) {
                LOGGER.warn("[AbilityRandomizer] Line-type gathering failed for {}; skipping type caveats",
                    rootSpecies.getName(), e);
            }
            lineTypes = java.util.Collections.emptySet();
        }

        // Candidate names filtered so no member of the line can hold an illegal ability.
        List<String> candidates = new ArrayList<>();
        for (String name : allowed) {
            if (AbilityFilter.passesLineTypeCaveats(AbilityFilter.normalize(name), lineTypes, typeRestrictions)) {
                candidates.add(name);
            }
        }
        if (candidates.isEmpty()) {
            // Every candidate got filtered out (should be impossible). Rather than hand back a
            // vanilla ability, fall back to the unfiltered legal list.
            candidates = new ArrayList<>(allowed);
        }

        String seedKey = rootSpecies.getName() + "|" + (regionalTag == null ? "" : regionalTag);
        java.util.Random rng = new java.util.Random(mixSeed(ConfigProxy.getEffectiveMode1Seed(), rootKeyHash(seedKey)));
        Collections.shuffle(candidates, rng);

        int available = candidates.size();
        int actualNormal = Math.min(normalCount, available);
        int actualHidden = Math.min(hiddenCount, Math.max(0, available - actualNormal));

        String[] normal = new String[actualNormal];
        for (int i = 0; i < actualNormal; i++) {
            normal[i] = candidates.get(i);
        }
        String[] hidden = new String[actualHidden];
        for (int i = 0; i < actualHidden; i++) {
            hidden[i] = candidates.get(actualNormal + i);
        }
        Pool pool = new Pool(normal, hidden);
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Built Mode1 pool for line '{}'{}: normal={}, hidden={}",
                rootSpecies.getName(), regionalTag == null ? "" : " (" + regionalTag + ")",
                java.util.Arrays.toString(normal), java.util.Arrays.toString(hidden));
        }
        return pool;
    }

    // ------------------------------------------------------------------
    // Mode 2 - per-individual random
    // ------------------------------------------------------------------

    private static void applyMode2(Pokemon pokemon, Stats form) {
        List<String> allowed = getAllowedNames();
        if (allowed.isEmpty()) {
            return;
        }
        AbilityRandomizerConfig config = ConfigProxy.get();
        boolean typeRestrictions = config.isEnableBlacklist() && config.isEnableTypeRestrictions();

        UUID uuid = pokemon.getUUID();
        long seed = mixSeed(ConfigProxy.getEffectiveMode1Seed(),
            uuid == null ? System.identityHashCode(pokemon) : (uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()));

        List<String> shuffled = new ArrayList<>(allowed);
        Collections.shuffle(shuffled, new java.util.Random(seed));

        for (String name : shuffled) {
            if (AbilityFilter.passesTypeCaveats(AbilityFilter.normalize(name), form, typeRestrictions)) {
                assignAbility(pokemon, name, -1, false);
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Mode2 {} -> {}", safeName(pokemon), name);
                }
                return;
            }
        }
        // Nothing legal for this type (extremely unlikely) - leave vanilla ability in place.
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Resolve the ability by name and place it on the Pokemon, then restore the intended slot/HA
     * bookkeeping (setAbility resets the slot to -1 for non-native abilities).
     *
     * @param storeSlot the slot to record, or -1 to leave it as a custom (slot-less) ability
     */
    private static void assignAbility(Pokemon pokemon, String name, int storeSlot, boolean hidden) {
        Optional<Ability> ability = AbilityRegistry.getNewAbility(name);
        if (ability.isEmpty()) {
            ability = AbilityRegistry.getAbility(name);
        }
        if (ability.isEmpty() || ability.get() == null) {
            return;
        }
        Ability current = pokemon.getAbility();
        if (current != null && current.getName().equalsIgnoreCase(ability.get().getName())
            && pokemon.getAbilitySlot() == storeSlot) {
            // Already correct - avoid needless markDirty churn.
            return;
        }
        pokemon.setAbility(ability.get());
        pokemon.setAbilitySlot(storeSlot);
        pokemon.setHA(hidden);
        if (!loggedFirstAssign) {
            loggedFirstAssign = true;
            LOGGER.info("[AbilityRandomizer] Randomization active - first assignment: {} -> {}",
                safeName(pokemon), ability.get().getName());
        }
    }

    private static boolean isExcluded(Species species) {
        AbilityRandomizerConfig config = ConfigProxy.get();
        List<String> excluded = config.getExcludedPokemon();
        if (excluded.isEmpty()) {
            return false;
        }
        String name = AbilityFilter.normalize(species.getName());
        String stripped = AbilityFilter.normalize(species.getStrippedName());
        for (String entry : excluded) {
            String norm = AbilityFilter.normalize(entry);
            if (!norm.isEmpty() && (norm.equals(name) || norm.equals(stripped))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The list of legal ability canonical names (all registered minus the blacklist).
     *
     * <p>Cached only once it is non-empty. If Pixelmon's ability registry is momentarily empty
     * (e.g. it is reset and re-synced during a world reload), we return the empty list WITHOUT
     * caching it, so the next call recomputes once the registry is populated again. Caching an
     * empty list here was what silently disabled randomization after a relog.</p>
     */
    private static List<String> getAllowedNames() {
        List<String> cached = allowedNamesCache;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        // Resolve or rebuild the blacklist (lazy, rebuilt after invalidateCaches).
        Set<String> blacklist = cachedBlacklist;
        if (blacklist == null) {
            blacklist = AbilityFilter.buildGlobalBlacklist(ConfigProxy.get());
            cachedBlacklist = blacklist;
        }

        List<Ability> all = AbilityRegistry.getAllAbilities();
        int registrySize = all.size();
        // Atomically track the largest registry seen so far — the accumulateAndGet
        // guarantees no thread can shrink the stored maximum below any value another
        // thread observed.
        int bestSeen = maxRegistrySize.accumulateAndGet(registrySize, Math::max);
        // "Complete" == this is the largest registry we have ever seen. A smaller size means the
        // registry is still being (re)synced after a world reload, so any list built now would be
        // partial and must not be cached.
        boolean complete = registrySize > 0 && registrySize >= bestSeen;
        lastRegistryComplete = complete;

        List<String> names = new ArrayList<>();
        for (Ability ability : all) {
            if (ability == null) {
                continue;
            }
            String canonical = ability.getName();
            if (canonical == null || canonical.isEmpty()) {
                continue;
            }
            if (!blacklist.contains(AbilityFilter.normalize(canonical))) {
                names.add(canonical);
            }
        }
        if (names.isEmpty() || !complete) {
            if (!loggedNoAbilities) {
                LOGGER.warn("[AbilityRandomizer] Ability registry not ready (size {}, best seen {}); "
                    + "will retry on the next Pokemon", registrySize, bestSeen);
                loggedNoAbilities = true;
            }
            return names; // do NOT cache a partial/empty result
        }
        loggedNoAbilities = false;
        allowedNamesCache = names;
        return names;
    }

    /**
     * The form of a species matching the given regional tag, or the default form when the tag is
     * null or the species has no matching regional form.
     */
    private static Stats formForRegion(Species species, String regionalTag) {
        if (regionalTag != null) {
            for (Stats candidate : species.getForms()) {
                if (regionalTag.equals(candidate.getRegionalTag())) {
                    return candidate;
                }
            }
        }
        return species.getDefaultForm();
    }

    /**
     * Walk an evolution line (base species plus all descendants), staying within the given regional
     * tag, and return every visited form. Shared by type-gathering and species-membership checks.
     */
    private static List<Stats> collectLineForms(Species root, String regionalTag) {
        List<Stats> forms = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Species> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root.getName().toLowerCase(Locale.ROOT));

        int guard = 0;
        while (!queue.isEmpty() && guard++ < 64) {
            Species species = queue.poll();
            Stats form = formForRegion(species, regionalTag);
            if (form == null) {
                continue;
            }
            forms.add(form);
            for (Evolution evolution : form.getEvolutions()) {
                String toName = evolution.to;
                if (toName == null || toName.isEmpty()) {
                    continue;
                }
                // The evolution "to" can be a full spec string; take the leading species token.
                String speciesToken = toName.split(" ")[0].trim();
                if (speciesToken.isEmpty()) {
                    continue;
                }
                Optional<RegistryValue<Species>> next = PixelmonSpecies.get(speciesToken);
                if (next.isPresent() && next.get().getValue().isPresent()) {
                    Species nextSpecies = next.get().getValue().get();
                    if (visited.add(nextSpecies.getName().toLowerCase(Locale.ROOT))) {
                        queue.add(nextSpecies);
                    }
                }
            }
        }
        return forms;
    }

    /**
     * Collect the caveat-relevant types present anywhere in an evolution line, so Mode 1 pools stay
     * legal for every member. Stays within the given regional tag.
     */
    private static Set<ResourceKey<Type>> gatherLineTypes(Species root, String regionalTag) {
        Set<ResourceKey<Type>> types = new HashSet<>();
        for (Stats form : collectLineForms(root, regionalTag)) {
            for (ResourceKey<Type> type : CAVEAT_TYPES) {
                if (form.isType(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    /** Lower-cased species names present in an evolution line, staying within the given regional tag. */
    private static Set<String> collectLineSpeciesNames(Species root, String regionalTag) {
        Set<String> names = new HashSet<>();
        for (Stats form : collectLineForms(root, regionalTag)) {
            names.add(form.getParentSpecies().getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static void refreshCachesIfSeedChanged() {
        long seed = ConfigProxy.getEffectiveMode1Seed();
        if (seed != cacheSeed) {
            synchronized (AbilityRandomizerEngine.class) {
                if (seed != cacheSeed) {
                    invalidateCaches();
                    cacheSeed = seed;
                }
            }
        }
    }

    private static long mixSeed(long seed, long value) {
        long h = seed ^ (value * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 32);
        h *= 0xD6E8FEB86659FD93L;
        h ^= (h >>> 32);
        return h;
    }

    private static long rootKeyHash(String name) {
        long h = 1125899906842597L; // prime
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        return h;
    }

    private static String safeName(Pokemon pokemon) {
        try {
            Species species = pokemon.getSpecies();
            return species != null ? species.getName() : "<unknown>";
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    // ------------------------------------------------------------------
    // Public API for display surfaces (e.g. the /wiki command)
    // ------------------------------------------------------------------

    /** Whether randomization applies to this Pokemon: a mode is enabled and the species is not excluded. */
    public static boolean isActiveFor(Pokemon pokemon) {
        if (pokemon == null || !ConfigProxy.isLoaded() || ConfigProxy.effectiveMode() == Mode.VANILLA) {
            return false;
        }
        try {
            Species species = pokemon.getSpecies();
            return species != null && !isExcluded(species);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The Mode 1 ability pool for a Pokemon, resolved to {@link Ability} instances for display.
     * Empty when not in Mode 1, when the species is excluded, or when the pool cannot be built yet
     * (registry not ready) - callers should then fall back to the vanilla display.
     */
    public static Optional<PoolDisplay> getMode1PoolForDisplay(Pokemon pokemon) {
        if (!isActiveFor(pokemon) || ConfigProxy.effectiveMode() != Mode.SPECIES_CONSISTENT) {
            return Optional.empty();
        }
        try {
            Stats form = pokemon.getForm();
            if (form == null) {
                return Optional.empty();
            }
            refreshCachesIfSeedChanged();
            if (getAllowedNames().isEmpty()) {
                return Optional.empty();
            }
            Pool pool = getOrBuildPool(form);
            if (pool == null || (pool.normal.length == 0 && pool.hidden.length == 0)) {
                return Optional.empty();
            }
            List<Ability> normal = resolveAbilities(pool.normal);
            List<Ability> hidden = resolveAbilities(pool.hidden);
            if (normal.isEmpty() && hidden.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PoolDisplay(normal, hidden));
        } catch (Exception e) {
            LOGGER.error("[AbilityRandomizer] Failed to resolve display pool for {}", safeName(pokemon), e);
            return Optional.empty();
        }
    }

    private static List<Ability> resolveAbilities(String[] names) {
        List<Ability> list = new ArrayList<>();
        for (String name : names) {
            Optional<Ability> ability = AbilityRegistry.getNewAbility(name);
            if (ability.isEmpty()) {
                ability = AbilityRegistry.getAbility(name);
            }
            ability.ifPresent(list::add);
        }
        return list;
    }

    /** Whether this Pokemon is currently a wild (unowned) Pokemon. */
    public static boolean isWild(Pokemon pokemon) {
        try {
            return pokemon != null && pokemon.getOwnerPlayerUUID() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Public, resolved view of a Mode 1 pool for UI display. */
    public static final class PoolDisplay {
        private final List<Ability> normal;
        private final List<Ability> hidden;

        private PoolDisplay(List<Ability> normal, List<Ability> hidden) {
            this.normal = normal;
            this.hidden = hidden;
        }

        public List<Ability> getNormal() {
            return normal;
        }

        public List<Ability> getHidden() {
            return hidden;
        }
    }

    /** Immutable pool of ability canonical names for one evolution line. */
    private static final class Pool {
        private final String[] normal;
        private final String[] hidden;

        private Pool(String[] normal, String[] hidden) {
            this.normal = normal;
            this.hidden = hidden;
        }
    }
}
