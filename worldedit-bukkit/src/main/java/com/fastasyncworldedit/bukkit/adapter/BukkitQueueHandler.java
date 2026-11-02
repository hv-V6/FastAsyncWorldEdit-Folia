package com.fastasyncworldedit.bukkit.adapter;

import com.fastasyncworldedit.bukkit.listener.ChunkListener;
import com.fastasyncworldedit.bukkit.util.FoliaSupport;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;

/**
 * Bukkit/Folia implementation of {@link QueueHandler}.
 *
 * <p>FAWE marks periods where it deliberately mutates world state from an asynchronous thread (the
 * "unsafe window"): physics are frozen so the server does not react to half-applied edits, and on
 * Spigot/Paper the async guard is lifted so the server does not reject the writes.</p>
 *
 * <p>Upstream FAWE does the latter by reflectively flipping {@code org.spigotmc.AsyncCatcher.enabled}
 * and disabling Timings. Neither works on Folia:</p>
 *
 * <ul>
 *     <li>{@code AsyncCatcher} has no {@code enabled} field on Folia; its only method,
 *     {@code catchOp}, unconditionally checks region ownership via Moonrise {@code TickThread}.
 *     Upstream's reflective lookup silently fails, leaving a null field that later throws.</li>
 *     <li>Timings is removed from the Folia server entirely.</li>
 * </ul>
 *
 * <p>On Folia there is nothing to disable: whether a write is legal depends solely on which region
 * thread is executing, and no flag can change that. The correct behaviour is therefore to keep the
 * physics freeze (which is ours to control) and skip the Spigot-specific toggling. Region
 * correctness is enforced instead by routing the work through the region scheduler — see
 * {@link com.fastasyncworldedit.bukkit.util.BukkitTaskManager}.</p>
 */
public class BukkitQueueHandler extends QueueHandler {

    private static final boolean FOLIA = FoliaSupport.isFolia();

    private volatile boolean timingsEnabled;

    public BukkitQueueHandler() {
        if (FOLIA) {
            // Nothing to initialise: Folia has no AsyncCatcher flag and no Timings to pause.
            return;
        }
        SpigotCompat.init();
    }

    @Override
    public void startUnsafe(boolean parallel) {
        ChunkListener.physicsFreeze = true;
        if (parallel && !FOLIA) {
            SpigotCompat.pauseGuards(this, true);
        }
    }

    @Override
    public void endUnsafe(boolean parallel) {
        ChunkListener.physicsFreeze = false;
        if (parallel && !FOLIA) {
            SpigotCompat.pauseGuards(this, false);
        }
    }

    /**
     * Isolated holder for the Spigot/Paper-only reflective toggling. Keeping this in its own class
     * means the reflective fields are only ever touched on platforms that actually have them, and a
     * failure here cannot break plugin startup on Folia.
     */
    private static final class SpigotCompat {

        private static java.lang.reflect.Field asyncCatcher;
        private static java.lang.reflect.Method timingsCheck;

        private static void init() {
            try {
                asyncCatcher = Class.forName("org.spigotmc.AsyncCatcher").getDeclaredField("enabled");
                asyncCatcher.setAccessible(true);
            } catch (final Throwable ignored) {
                asyncCatcher = null;
            }
            try {
                timingsCheck = Class.forName("co.aikar.timings.TimingsManager").getDeclaredMethod("recheckEnabled");
                timingsCheck.setAccessible(true);
            } catch (final Throwable ignored) {
                timingsCheck = null;
            }
        }

        private static void pauseGuards(final BukkitQueueHandler handler, final boolean pause) {
            try {
                if (asyncCatcher != null) {
                    asyncCatcher.setBoolean(asyncCatcher, !pause);
                }
                if (pause) {
                    handler.timingsEnabled = co.aikar.timings.Timings.isTimingsEnabled();
                    if (handler.timingsEnabled) {
                        co.aikar.timings.Timings.setTimingsEnabled(false);
                        if (timingsCheck != null) {
                            timingsCheck.invoke(null);
                        }
                    }
                } else if (handler.timingsEnabled) {
                    co.aikar.timings.Timings.setTimingsEnabled(true);
                    if (timingsCheck != null) {
                        timingsCheck.invoke(null);
                    }
                }
            } catch (final Throwable ignored) {
                // Best effort: these are performance/diagnostic guards, not correctness guards.
            }
        }
    }

}
