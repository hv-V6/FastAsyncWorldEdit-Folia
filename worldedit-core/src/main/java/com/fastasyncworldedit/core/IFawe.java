package com.fastasyncworldedit.core;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.preloader.Preloader;
import com.fastasyncworldedit.core.regions.FaweMaskManager;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.image.ImageViewer;
import com.sk89q.worldedit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.UUID;

public interface IFawe {

    File getDirectory();

    TaskManager getTaskManager();

    Collection<FaweMaskManager> getMaskManagers();

    /**
     * @deprecated for removal with no replacement. Out of scope for FAWE.
     */
    @Deprecated(forRemoval = true, since = "2.13.1")
    default ImageViewer getImageViewer(Player player) {
        return null;
    }

    /**
     * Get a friendly name of the platform.
     *
     * <p>
     * The name can be anything (reasonable). An example name may be
     * "Bukkit" or "Forge".
     * </p>
     *
     * @return the platform name
     */
    String getPlatform();

    UUID getUUID(String name);

    String getName(UUID uuid);

    default String getDebugInfo() {
        return "";
    }

    QueueHandler getQueueHandler();

    /**
     * Get the preloader instance and initialise if needed
     *
     * @param initialise if the preloader should be initialised if null
     * @return preloader instance
     */
    Preloader getPreloader(boolean initialise);

    @Deprecated(forRemoval = true, since = "2.13.1")
    default boolean isChunksStretched() {
        return true;
    }

    FAWEPlatformAdapterImpl getPlatformAdapter();

    /**
     * Whether the calling thread is a server tick thread, i.e. a thread on which world work may be
     * performed inline.
     *
     * <p>On Folia each region ticks on its own thread and the global region ticks on another; all
     * are tick threads, but each may only touch the world data of the region it currently owns.
     * On single-threaded platforms this is simply the server thread.</p>
     *
     * @return true if the calling thread is a server tick thread
     */
    default boolean isTickThread() {
        return Fawe.isMainThread();
    }

    /**
     * Whether the calling thread currently owns the region containing the given chunk, i.e. whether
     * it may safely read or write that chunk right now.
     *
     * <p>On single-threaded platforms (Paper, Spigot) world access is legal from the one server
     * thread and this defaults to {@link Fawe#isMainThread()}. On Folia, world state is partitioned
     * into independently ticking regions owned by individual threads, so this must consult the
     * server's region ownership check (see the Bukkit implementation).</p>
     *
     * @param world  the world the chunk belongs to
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the calling thread may touch that chunk
     */
    default boolean isRegionOwner(com.sk89q.worldedit.world.World world, int chunkX, int chunkZ) {
        return Fawe.isMainThread();
    }

    /**
     * Whether the calling thread is allowed to mutate truly global (non-region) server state, such
     * as the plugin manager or configuration.
     *
     * <p>Defaults to {@link Fawe#isMainThread()}; on Folia this is the global region thread, which
     * is the only thread permitted to drain FAWE's global sync queue.</p>
     */
    default boolean isGlobalThread() {
        return Fawe.isMainThread();
    }

}
