package com.pixelmon.abilityrandomizer.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import com.pixelmonmod.pixelmon.api.util.helpers.RegistryHelper;
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

    /** Cached, sorted display (translated) names of allowed abilities, for command autofill. */
    private static volatile List<String> allowedDisplayNamesCache;

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
        LOGGER.debug("[AbilityRandomizer] Engine: invalidating all caches");
        POOL_CACHE.clear();
        EFFECTIVE_TAG_CACHE.clear();
        allowedNamesCache = null;
        allowedDisplayNamesCache = null;
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.apply: pokemon is null, skipping");
            }
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
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine.apply: {} (slot={}, ha={})",
                safeName(pokemon), slot, ha);
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.apply: pokemon={}, configLoaded={} - skipping",
                    pokemon != null ? safeName(pokemon) : "null", ConfigProxy.isLoaded());
            }
            return;
        }
        Mode mode = ConfigProxy.effectiveMode();
        if (mode == Mode.VANILLA) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.apply: vanilla mode - skipping {}", safeName(pokemon));
            }
            return;
        }
        try {
            Stats form = pokemon.getForm();
            Species species = pokemon.getSpecies();
            if (form == null || species == null) {
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine.apply: {} has null form or species - skipping",
                        safeName(pokemon));
                }
                return;
            }
            if (isExcluded(species)) {
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine.apply: {} is excluded - skipping",
                        safeName(pokemon));
                }
                return;
            }
            // The world registry is unreachable during the initial world-load datapack pass
            // (WorldOpenFlows.loadWorldDataBlocking): the integrated server has not started yet and
            // the client level is still null, so any type lookup — Stats.getTypes()/isType() used
            // by pool-building and the type caveats — routes through
            // RegistryHelper.registryAccess() -> ClientRegistryHelper and throws an NPE. The spawn
            // system creates throwaway template Pokemon in exactly this window (Pixelmon's spawn-set
            // import plus the SpawnCongregator / NMUS post-processors), so we would otherwise throw
            // once per template. Skip randomization here — and, as with the registry guard below, do
            // NOT cache — so abilities re-roll correctly once the world is live. This is why the bug
            // "fixed itself" after a reload: by then the registry is reachable.
            if (!isRegistryContextReady()) {
                if (ConfigProxy.isDebug()) {
                    LOGGER.warn("[AbilityRandomizer] World registry not ready when randomizing {}; will retry",
                        safeName(pokemon));
                }
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
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine.apply: Mode1 for {} (slot={}, ha={})",
                        safeName(pokemon), vanillaSlot, vanillaHa);
                }
                applyMode1(pokemon, form, vanillaSlot, vanillaHa);
            } else {
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine.apply: Mode2 for {}", safeName(pokemon));
                }
                applyMode2(pokemon, form);
            }
        } catch (Exception e) {
            // Never let a randomization error break Pokemon creation.
            LOGGER.error("[AbilityRandomizer] Error while randomizing ability for {}", safeName(pokemon), e);
        }
    }

    /**
     * Whether Pixelmon's world registry is currently reachable. This mirrors the resolution done by
     * {@link RegistryHelper#registryAccess()}: it succeeds once an integrated/dedicated server is
     * running or a client level exists, and fails during the initial world-load datapack pass when
     * neither is available yet. We probe it defensively (a failure surfaces as an NPE from
     * {@code ClientRegistryHelper}) so we can bail out of randomization before any type lookup is
     * attempted, rather than throwing once per template Pokemon.
     *
     * @return {@code true} if a registry access can be obtained; {@code false} if the world context
     *         is not ready yet.
     */
    private static boolean isRegistryContextReady() {
        try {
            return RegistryHelper.registryAccess() != null;
        } catch (Throwable t) {
            return false;
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine: pool cache hit for '{}'", rootKey);
            }
            return cached;
        }
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine: building new pool for '{}'", rootKey);
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine: regional tag for {} is '{}' (own)", form.getName(), own);
            }
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
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine: effective regional tag for {} resolved to '{}'",
                cacheKey, resolved);
        }
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
            if (ConfigProxy.isDebug()) {
                LOGGER.warn("[AbilityRandomizer] Engine: ability '{}' not found in registry for {}", name, safeName(pokemon));
            }
            return;
        }
        Ability current = pokemon.getAbility();
        if (current != null && current.getName().equalsIgnoreCase(ability.get().getName())
            && pokemon.getAbilitySlot() == storeSlot) {
            // Already correct - avoid needless markDirty churn.
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine: {} already has correct ability {} - skipping",
                    safeName(pokemon), ability.get().getName());
            }
            return;
        }
        pokemon.setAbility(ability.get());
        pokemon.setAbilitySlot(storeSlot);
        pokemon.setHA(hidden);
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine: assigned {} -> {} (slot={}, hidden={})",
                safeName(pokemon), ability.get().getName(), storeSlot, hidden);
        }
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
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine: {} excluded by config entry '{}'",
                        species.getName(), entry);
                }
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine: returning cached allowed names ({} abilities)", cached.size());
            }
            return cached;
        }
        // Resolve or rebuild the blacklist (lazy, rebuilt after invalidateCaches).
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine: rebuilding allowed names list");
        }
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
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine: allowed names list rebuilt with {} abilities (blacklist: {})",
                names.size(), blacklist.size());
        }
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
                    if (ConfigProxy.isDebug()) {
                        LOGGER.info("[AbilityRandomizer] Engine: seed changed from {} to {}, invalidating caches",
                            cacheSeed, seed);
                    }
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

    public static String safeName(Pokemon pokemon) {
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.isActiveFor: false (null/config/vanilla)");
            }
            return false;
        }
        try {
            Species species = pokemon.getSpecies();
            boolean active = species != null && !isExcluded(species);
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.isActiveFor: {} -> {}", safeName(pokemon), active);
            }
            return active;
        } catch (Exception e) {
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.isActiveFor: {} -> false (exception)", safeName(pokemon));
            }
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
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.getMode1PoolForDisplay: not active or not Mode1 for {}",
                    safeName(pokemon));
            }
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
                if (ConfigProxy.isDebug()) {
                    LOGGER.info("[AbilityRandomizer] Engine.getMode1PoolForDisplay: empty pool for {}",
                        safeName(pokemon));
                }
                return Optional.empty();
            }
            List<Ability> normal = resolveAbilities(pool.normal);
            List<Ability> hidden = resolveAbilities(pool.hidden);
            if (normal.isEmpty() && hidden.isEmpty()) {
                return Optional.empty();
            }
            if (ConfigProxy.isDebug()) {
                LOGGER.info("[AbilityRandomizer] Engine.getMode1PoolForDisplay: {} normal, {} hidden for {}",
                    normal.size(), hidden.size(), safeName(pokemon));
            }
            return Optional.of(new PoolDisplay(normal, hidden));
        } catch (Exception e) {
            LOGGER.error("[AbilityRandomizer] Failed to resolve display pool for {}", safeName(pokemon), e);
            return Optional.empty();
        }
    }

    /** Public exclusion check for command/UI surfaces. */
    public static boolean isSpeciesExcluded(Species species) {
        return species != null && isExcluded(species);
    }

    /**
     * The Mode 1 pool for a species' default form, as canonical ability names. Empty unless Mode 1 is
     * active, the species is not excluded, and the registry/pool is ready. Every member of an
     * evolution line resolves to the same line pool.
     */
    public static Optional<PoolNames> getMode1PoolNames(Species species) {
        if (species == null || !ConfigProxy.isLoaded()
            || ConfigProxy.effectiveMode() != Mode.SPECIES_CONSISTENT
            || isExcluded(species)) {
            return Optional.empty();
        }
        try {
            Stats form = species.getDefaultForm();
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
            return Optional.of(new PoolNames(pool.normal.clone(), pool.hidden.clone()));
        } catch (Exception e) {
            if (ConfigProxy.isDebug()) {
                LOGGER.warn("[AbilityRandomizer] getMode1PoolNames failed for {}", species.getName(), e);
            }
            return Optional.empty();
        }
    }

    /** Resolved Mode 1 pool for a species, for the {@code /abilityinfo <pokemon>} display. */
    public static Optional<PoolDisplay> getMode1PoolForSpecies(Species species) {
        Optional<PoolNames> names = getMode1PoolNames(species);
        if (names.isEmpty()) {
            return Optional.empty();
        }
        List<Ability> normal = resolveAbilities(names.get().normal);
        List<Ability> hidden = resolveAbilities(names.get().hidden);
        if (normal.isEmpty() && hidden.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PoolDisplay(normal, hidden));
    }

    /**
     * Every species whose Mode 1 pool includes the given ability (matched by canonical name),
     * flagged with whether it sits in that species' hidden slot. Sorted by national dex. Empty when
     * not in Mode 1. This walks all species (pools are cached per evolution line).
     */
    public static List<SpeciesAbilityMatch> findSpeciesWithAbility(String abilityCanonicalName) {
        List<SpeciesAbilityMatch> matches = new ArrayList<>();
        if (abilityCanonicalName == null || ConfigProxy.effectiveMode() != Mode.SPECIES_CONSISTENT) {
            return matches;
        }
        String target = AbilityFilter.normalize(abilityCanonicalName);
        for (Species species : PixelmonSpecies.getAll()) {
            Optional<PoolNames> names = getMode1PoolNames(species);
            if (names.isEmpty()) {
                continue;
            }
            boolean inNormal = containsNormalized(names.get().normal, target);
            boolean inHidden = containsNormalized(names.get().hidden, target);
            if (inNormal || inHidden) {
                matches.add(new SpeciesAbilityMatch(species, inHidden && !inNormal));
            }
        }
        matches.sort(Comparator.comparingInt(match -> match.species.getDex()));
        return matches;
    }

    private static boolean containsNormalized(String[] names, String targetNormalized) {
        for (String name : names) {
            if (AbilityFilter.normalize(name).equals(targetNormalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve a user-typed ability name to a registered ability, tolerating spacing/case
     * (e.g. "sap sipper", "SapSipper", "Sap Sipper"). Matches against all registered abilities,
     * so blacklisted abilities still resolve (their reverse lookup will simply be empty).
     */
    public static Optional<Ability> resolveAbility(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<Ability> direct = AbilityRegistry.getAbility(input.trim());
        if (direct.isPresent()) {
            return direct;
        }
        String norm = AbilityFilter.normalize(input);
        for (Ability ability : AbilityRegistry.getAllAbilities()) {
            if (ability == null) {
                continue;
            }
            if (AbilityFilter.normalize(ability.getName()).equals(norm)
                || AbilityFilter.normalize(ability.getTranslatedName().getString()).equals(norm)) {
                return Optional.of(ability);
            }
        }
        return Optional.empty();
    }

    /**
     * Sorted display (translated) names of the allowed (non-blacklisted) abilities, for command
     * autofill. Cached alongside the allowed-names list; only cached once that list is ready.
     */
    public static List<String> getAllowedAbilityDisplayNames() {
        List<String> cached = allowedDisplayNamesCache;
        if (cached != null) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        for (String canonical : getAllowedNames()) {
            Optional<Ability> ability = AbilityRegistry.getAbility(canonical);
            names.add(ability.isPresent() ? ability.get().getTranslatedName().getString() : canonical);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (allowedNamesCache != null) {
            allowedDisplayNamesCache = names;
        }
        return names;
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
        if (ConfigProxy.isDebug()) {
            LOGGER.info("[AbilityRandomizer] Engine.resolveAbilities: resolved {} of {} names", list.size(), names.length);
        }
        return list;
    }

    /** Whether this Pokemon is currently a wild (unowned) Pokemon. */
    public static boolean isWild(Pokemon pokemon) {
        try {
            boolean wild = pokemon != null && pokemon.getOwnerPlayerUUID() == null;
            if (ConfigProxy.isDebug() && pokemon != null) {
                LOGGER.info("[AbilityRandomizer] Engine.isWild: {} -> {}", safeName(pokemon), wild);
            }
            return wild;
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

    /** Public, canonical-name view of a Mode 1 pool (used by command lookups). */
    public static final class PoolNames {
        private final String[] normal;
        private final String[] hidden;

        private PoolNames(String[] normal, String[] hidden) {
            this.normal = normal;
            this.hidden = hidden;
        }

        public String[] getNormal() {
            return normal;
        }

        public String[] getHidden() {
            return hidden;
        }
    }

    /** A species that holds a queried ability, with whether it is that species' hidden ability. */
    public static final class SpeciesAbilityMatch {
        private final Species species;
        private final boolean hidden;

        private SpeciesAbilityMatch(Species species, boolean hidden) {
            this.species = species;
            this.hidden = hidden;
        }

        public Species getSpecies() {
            return species;
        }

        public boolean isHidden() {
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
