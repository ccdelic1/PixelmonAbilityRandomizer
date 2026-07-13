package com.pixelmon.abilityrandomizer.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Resolves the randomization seed for the currently-loaded world/server.
 *
 * <p>The seed is stored in a small file inside the world save directory
 * ({@code <world>/PixelmonAbilityRandomizer-seed.txt}). This makes the ability pools:
 * <ul>
 *   <li><b>persistent</b> within a world/server (the file is read on every load), and</li>
 *   <li><b>unique per world/server</b> (a brand-new save has no file, so a fresh seed is generated).</li>
 * </ul>
 * Storing it in the world save (rather than the global config) is what stops every world from
 * sharing the same abilities.</p>
 */
public final class WorldSeedResolver {

    private static final Logger LOGGER = LogManager.getLogger("PixelmonAbilityRandomizer");
    private static final String SEED_FILE_NAME = "pixelmonabilityrandomizer-seed.txt";

    private WorldSeedResolver() {
    }

    /**
     * Read the per-world seed, generating and storing a new one if this world does not have one yet.
     * Never returns 0 (0 is reserved as the "unresolved" sentinel elsewhere).
     */
    public static long resolveForWorld(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path seedFile = worldRoot.resolve(SEED_FILE_NAME);
        LOGGER.debug("[AbilityRandomizer] WorldSeedResolver: resolving seed for world at {}", worldRoot);

        // Existing world: read the stored seed.
        try {
            if (Files.exists(seedFile)) {
                LOGGER.debug("[AbilityRandomizer] WorldSeedResolver: found existing seed file");
                for (String line : Files.readAllLines(seedFile)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    try {
                        long seed = Long.parseLong(trimmed.split("\\s+")[0]);
                        if (seed != 0L) {
                            LOGGER.debug("[AbilityRandomizer] WorldSeedResolver: read existing seed {}", seed);
                            return seed;
                        }
                    } catch (NumberFormatException ignored) {
                        // fall through to regenerate
                    }
                    break;
                }
            } else {
                LOGGER.debug("[AbilityRandomizer] WorldSeedResolver: no seed file exists, will generate new");
            }
        } catch (Exception e) {
            LOGGER.warn("[AbilityRandomizer] Could not read the per-world seed file; generating a new one", e);
        }

        // New world (or unreadable file): generate and persist a fresh seed.
        long seed = new Random().nextLong();
        if (seed == 0L) {
            seed = 1L;
        }
        LOGGER.debug("[AbilityRandomizer] WorldSeedResolver: generated new seed {}", seed);
        try {
            Files.createDirectories(worldRoot);
            Files.write(seedFile, java.util.List.of(
                Long.toString(seed),
                "# Pixelmon Ability Randomizer - this world's ability-pool seed.",
                "# It keeps this world's randomized abilities stable across restarts.",
                "# Delete this file to re-roll this world's ability pools."
            ));
            LOGGER.info("[AbilityRandomizer] Generated a new per-world seed for this save");
        } catch (Exception e) {
            LOGGER.warn("[AbilityRandomizer] Could not write the per-world seed file; abilities will be "
                + "stable this session but may change after a restart", e);
        }
        return seed;
    }
}
