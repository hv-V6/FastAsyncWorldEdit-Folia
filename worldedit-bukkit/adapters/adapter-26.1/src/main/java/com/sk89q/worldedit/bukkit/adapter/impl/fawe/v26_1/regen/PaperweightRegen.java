package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_1.regen;

import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.bukkit.util.PaperSupport;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.google.common.collect.ImmutableList;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.adapter.Refraction;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.generator.BiomeProvider;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static net.minecraft.core.registries.Registries.BIOME;

public class PaperweightRegen extends Regenerator {

    private static final String REGEN_WORLD_NAME = "faweregentempworld";

    private static final Field serverWorldsField;
    private static final Field paperConfigField;
    private static final Field generatorSettingBaseSupplierField;


    static {
        try {
            serverWorldsField = CraftServer.class.getDeclaredField("worlds");
            serverWorldsField.setAccessible(true);

            Field tmpPaperConfigField;
            try { //only present on paper
                tmpPaperConfigField = Level.class.getDeclaredField("paperConfig");
                tmpPaperConfigField.setAccessible(true);
            } catch (Exception e) {
                tmpPaperConfigField = null;
            }
            paperConfigField = tmpPaperConfigField;

            generatorSettingBaseSupplierField = NoiseBasedChunkGenerator.class.getDeclaredField(Refraction.pickName(
                    "settings", "e"));
            generatorSettingBaseSupplierField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //runtime
    private ServerLevel originalServerWorld;
    private ServerLevel freshWorld;
    private LevelStorageSource.LevelStorageAccess session;

    private Path tempDir;

    public PaperweightRegen(
            World originalBukkitWorld,
            Region region,
            Extent target,
            RegenOptions options
    ) {
        super(originalBukkitWorld, region, target, options);
    }

    /**
     * Pump the fresh world's chunk task queue while a regen step has budget left.
     *
     * <p>Folia - region threading: this must not be called from a region's tick context. Verified
     * against Folia 26.1.2: {@code ServerChunkCache$MainThreadExecutor#pollTask()} reads
     * {@code TickRegionScheduler.getCurrentRegionizedWorldData().world} and compares it with its own
     * level, throwing {@code "Polling tasks from non-owned region"} on mismatch and NPE-ing when
     * there is no region data at all (as on the global thread).</p>
     *
     * <p>The regen world is a detached temporary world that no region ever ticks, so it can never
     * satisfy that check. Rather than letting an exception escape on every tick, we detect the
     * condition and stop pumping: returning normally is what ends the repeating drain task cleanly,
     * and generation itself continues through the chunk source regardless.</p>
     */
    @Override
    protected void runTasks(final BooleanSupplier shouldKeepTicking) {
        while (shouldKeepTicking.getAsBoolean()) {
            if (!pollFreshWorldTask()) {
                return;
            }
        }
    }

    /**
     * Poll one task from the fresh world's chunk source, tolerating the absence of a region context.
     *
     * @return true if a task was processed and the caller may continue pumping
     */
    private boolean pollFreshWorldTask() {
        try {
            return this.freshWorld.getChunkSource().pollTask();
        } catch (final IllegalStateException nonOwnedRegion) {
            // Folia: this temporary world has no owning region on this thread.
            return false;
        } catch (final NullPointerException noRegionData) {
            // Folia: no region data on this thread (e.g. the global region).
            return false;
        }
    }

    @Override
    protected boolean prepare() {
        this.originalServerWorld = ((CraftWorld) originalBukkitWorld).getHandle();
        seed = options.getSeed().orElse(originalServerWorld.getSeed());
        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        if (!PaperSupport.isPaper()) {
            throw new UnsupportedOperationException("Regen requires Paper");
        }

        //world folder
        tempDir = java.nio.file.Files.createTempDirectory("FastAsyncWorldEditWorldGen");

        //prepare for world init (see upstream implementation for reference)
        World.Environment environment = originalBukkitWorld.getEnvironment();
        org.bukkit.generator.ChunkGenerator generator = originalBukkitWorld.getGenerator();
        LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(tempDir);
        ResourceKey<LevelStem> levelStemResourceKey = getWorldDimKey(environment);
        session = levelStorageSource.createAccess(REGEN_WORLD_NAME);

        MinecraftServer server = originalServerWorld.getCraftServer().getServer();
        WorldOptions originalOpts = originalServerWorld.worldGenSettings.options();
        WorldOptions newOpts = options.getSeed().isPresent()
                ? originalOpts.withSeed(OptionalLong.of(seed))
                : originalOpts;
        WorldGenSettings newWorldGenSettings = new WorldGenSettings(
                newOpts,
                originalServerWorld.worldGenSettings.dimensions()
        );

        PaperWorldLoader.LoadedWorldData loadedWorldData = new PaperWorldLoader.LoadedWorldData(
                REGEN_WORLD_NAME,
                UUID.randomUUID(),
                new PaperWorldPDC((CraftPersistentDataContainer) originalBukkitWorld.getPersistentDataContainer()),
                originalServerWorld.serverLevelData
        );

        BiomeProvider biomeProvider = getBiomeProvider();

        SavedDataStorage savedDataStorage = new SavedDataStorage(session.getDimensionPath(originalServerWorld.dimension())
                .resolve(LevelResource.DATA.id()), server.getFixerUpper(), server.registryAccess());

        //init world
        freshWorld = Fawe.instance().getQueueHandler().sync((Supplier<ServerLevel>) () -> new ServerLevel(
                server,
                server.executor,
                session,
                newWorldGenSettings,
                originalServerWorld.dimension(),
                new LevelStem(
                        originalServerWorld.dimensionTypeRegistration(),
                        originalServerWorld.getChunkSource().getGenerator()
                ),
                originalServerWorld.isDebug(),
                seed,
                ImmutableList.of(),
                false,
                levelStemResourceKey,
                environment,
                generator,
                biomeProvider,
                savedDataStorage,
                loadedWorldData
        ) {

            private final Holder<Biome> singleBiome = options.hasBiomeType() ? DedicatedServer.getServer().registryAccess()
                    .lookupOrThrow(BIOME).asHolderIdMap().byIdOrThrow(
                            WorldEditPlugin.getInstance().getBukkitImplAdapter().getInternalBiomeId(options.getBiomeType())
                    ) : null;

            @Override
            public @Nonnull Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
                if (options.hasBiomeType()) {
                    return singleBiome;
                }
                return super.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
            }

            @Override
            public void save(
                    final ProgressListener progressListener,
                    final boolean flush,
                    final boolean savingDisabled
            ) {
                // noop, spigot
            }

            @Override
            public void save(
                    final ProgressListener progressListener,
                    final boolean flush,
                    final boolean savingDisabled,
                    final boolean close
            ) {
                // noop, paper
            }
        }).get();
        freshWorld.noSave = true;
        removeWorldFromWorldsMap();
        if (paperConfigField != null) {
            paperConfigField.set(freshWorld, originalServerWorld.paperConfig());
        }
        return true;
    }

    @Override
    protected void cleanup() {
        try {
            session.close();
        } catch (Exception ignored) {
        }

        //shutdown chunk provider
        try {
            Fawe.instance().getQueueHandler().sync(() -> {
                try {
                    freshWorld.getChunkSource().getDataStorage().cache.clear();
                    freshWorld.getChunkSource().close(false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception ignored) {
        }

        //remove world from server
        try {
            Fawe.instance().getQueueHandler().sync(this::removeWorldFromWorldsMap);
        } catch (Exception ignored) {
        }

        //delete directory
        try {
            SafeFiles.tryHardToDeleteDir(tempDir);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return new ChunkCache<>(BukkitAdapter.adapt(freshWorld.getWorld()));
    }

    //util
    @SuppressWarnings("unchecked")
    private void removeWorldFromWorldsMap() {
        try {
            Map<String, World> map = (Map<String, World>) serverWorldsField.get(Bukkit.getServer());
            map.remove(REGEN_WORLD_NAME);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private ResourceKey<LevelStem> getWorldDimKey(World.Environment env) {
        return switch (env) {
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

}
