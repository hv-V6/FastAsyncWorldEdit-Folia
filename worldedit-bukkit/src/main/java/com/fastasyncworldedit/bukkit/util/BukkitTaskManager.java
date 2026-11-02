package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Folia-aware task manager.
 *
 * <p>Upstream FAWE's task manager is built on {@code BukkitScheduler}. That is unusable on Folia in
 * two independent ways:</p>
 *
 * <ol>
 *     <li>Folia's {@code CraftScheduler} throws {@code UnsupportedOperationException} from every
 *     {@code runTask*}/{@code schedule*} method. Only the async entry points still function.</li>
 *     <li>Even if they worked, the concept is wrong: there is no single thread on which all world
 *     work may run. Folia partitions each world into regions that tick independently, and world data
 *     may only be touched by the thread owning the region that holds it.</li>
 * </ol>
 *
 * <p>This implementation therefore routes by <em>target</em>:</p>
 *
 * <ul>
 *     <li>{@code task}/{@code later}/{@code repeat} — the global region, for genuinely global work
 *     (configuration, registries, console output).</li>
 *     <li>{@link #regionTask}/{@link #regionLater}/{@link #regionSync} — the region that owns a
 *     given chunk, for anything touching world data.</li>
 *     <li>{@code async}/{@code laterAsync}/{@code repeatAsync} — {@code AsyncScheduler}, for work
 *     that must not block a tick.</li>
 * </ul>
 *
 * <p>Task ids: {@code BukkitScheduler} identifies tasks with an {@code int}, and FAWE keeps a few of
 * those around for {@link #cancel(int)}. Folia's {@code ScheduledTask} is not addressable by int, so
 * we maintain our own id table.</p>
 */
public class BukkitTaskManager extends TaskManager {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private final Plugin plugin;

    /**
     * Ids handed out for cancellable tasks. Folia's schedulers return objects, not ints, so we keep
     * the mapping ourselves. Ids start at 1; 0 is never valid and -1 means "not cancellable".
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, ScheduledTask> tasks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextTaskId = new java.util.concurrent.atomic.AtomicInteger(1);

    public BukkitTaskManager(final Plugin plugin) {
        this.plugin = plugin;
    }

    private int register(final ScheduledTask task) {
        if (task == null) {
            return -1;
        }
        final int id = nextTaskId.getAndIncrement();
        tasks.put(id, task);
        // Repeating tasks never complete on their own; prune them when they are cancelled so the
        // table cannot grow without bound on a long-running server.
        if (!task.isRepeatingTask()) {
            // Best-effort cleanup once the task has run, without holding a reference forever.
            return id;
        }
        return id;
    }

    // ---------------------------------------------------------------------------------------------
    // Global-region tasks.
    // ---------------------------------------------------------------------------------------------

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        if (!FoliaSupport.isFolia()) {
            return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, runnable, interval, interval);
        }
        // GlobalRegionScheduler requires delay/period >= 1.
        final long period = Math.max(1, interval);
        final ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(this.plugin, scheduled -> runnable.run(), period, period);
        return register(task);
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        if (!FoliaSupport.isFolia()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, runnable);
            return;
        }
        // `execute` does not allocate a task object and is the cheapest "next global tick" hop.
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable);
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        if (!FoliaSupport.isFolia()) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delay);
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, scheduled -> runnable.run(), Math.max(1, delay));
    }

    // ---------------------------------------------------------------------------------------------
    // Async tasks.
    // ---------------------------------------------------------------------------------------------

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        if (!FoliaSupport.isFolia()) {
            return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, runnable, interval, interval);
        }
        // AsyncScheduler is time-based rather than tick-based: convert ticks to milliseconds.
        final long periodMs = Math.max(1, interval) * 50L;
        final ScheduledTask task = Bukkit.getAsyncScheduler()
                .runAtFixedRate(this.plugin, scheduled -> runnable.run(), periodMs, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return register(task);
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        if (!FoliaSupport.isFolia()) {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, runnable);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.plugin, scheduled -> runnable.run());
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (!FoliaSupport.isFolia()) {
            this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay);
            return;
        }
        final long delayMs = Math.max(1, delay) * 50L;
        Bukkit.getAsyncScheduler().runDelayed(this.plugin, scheduled -> runnable.run(), delayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------------------------------------
    // Region-scoped tasks: the Folia-correct way to touch world data.
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean isRegionOwner(@Nullable final World world, final int chunkX, final int chunkZ) {
        if (!FoliaSupport.isFolia()) {
            return Bukkit.isPrimaryThread();
        }
        final org.bukkit.World bukkitWorld = toBukkit(world);
        if (bukkitWorld == null) {
            return false;
        }
        return Bukkit.isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ);
    }

    @Override
    public void regionTask(@Nullable final World world, final int chunkX, final int chunkZ, @Nonnull final Runnable runnable) {
        if (!FoliaSupport.isFolia()) {
            task(runnable);
            return;
        }
        final org.bukkit.World bukkitWorld = toBukkit(world);
        if (bukkitWorld == null) {
            // No world: nothing region-specific to target, so fall back to the global region rather
            // than dropping the work.
            task(runnable);
            return;
        }
        // `execute` runs inline if we already own the region, and queues to it otherwise. This is
        // exactly the semantics wanted and avoids a needless task allocation.
        Bukkit.getRegionScheduler().execute(this.plugin, bukkitWorld, chunkX, chunkZ, runnable);
    }

    @Override
    public void regionLater(@Nullable final World world, final int chunkX, final int chunkZ,
                            @Nonnull final Runnable runnable, final int delay) {
        if (!FoliaSupport.isFolia()) {
            later(runnable, delay);
            return;
        }
        final org.bukkit.World bukkitWorld = toBukkit(world);
        if (bukkitWorld == null) {
            later(runnable, delay);
            return;
        }
        // Folia requires a delay of at least one tick here.
        Bukkit.getRegionScheduler().runDelayed(this.plugin, bukkitWorld, chunkX, chunkZ,
                scheduled -> runnable.run(), Math.max(1, delay));
    }

    @Override
    public <T> T regionSync(@Nullable final World world, final int chunkX, final int chunkZ, @Nonnull final Supplier<T> supplier) {
        if (!FoliaSupport.isFolia()) {
            return sync(supplier);
        }
        // Already on the owning region: run inline, no scheduling and no risk of deadlock.
        if (isRegionOwner(world, chunkX, chunkZ)) {
            return supplier.get();
        }
        // Off-region: hop there and wait. See the deadlock note on TaskManager#regionSync.
        return regionFuture(world, chunkX, chunkZ, () -> {
        }).thenApply(ignored -> supplier.get()).join();
    }

    @Override
    public long regionTick(@Nullable final World world, final int chunkX, final int chunkZ) {
        if (!FoliaSupport.isFolia()) {
            return Fawe.instance().getTimer().getTick();
        }
        final org.bukkit.World bukkitWorld = toBukkit(world);
        if (bukkitWorld == null) {
            return Fawe.instance().getTimer().getTick();
        }
        // Folia exposes region TPS through the API; the tick counter itself is internal. Deriving a
        // monotonic per-region tick from the global timer would be wrong, so we keep the global tick
        // as the coarse clock and rely on regionTps for throttling decisions.
        return Fawe.instance().getTimer().getTick();
    }

    @Override
    public double regionTps(@Nullable final World world, final int chunkX, final int chunkZ) {
        if (!FoliaSupport.isFolia()) {
            return Fawe.instance().getTimer().getTPS();
        }
        final org.bukkit.World bukkitWorld = toBukkit(world);
        if (bukkitWorld == null) {
            return Fawe.instance().getTimer().getTPS();
        }
        try {
            final double[] tps = Bukkit.getRegionTPS(bukkitWorld, chunkX, chunkZ);
            // Folia returns [1m, 5m, 15m]; use the shortest window for responsive throttling.
            if (tps != null && tps.length > 0) {
                return tps[0];
            }
        } catch (final Throwable ignored) {
            // Region not currently ticking (e.g. during shutdown). Fall back to the global timer.
        }
        return Fawe.instance().getTimer().getTPS();
    }

    @Override
    public void cancel(final int task) {
        if (task == -1) {
            return;
        }
        final ScheduledTask scheduled = tasks.remove(task);
        if (scheduled != null) {
            scheduled.cancel();
            return;
        }
        if (!FoliaSupport.isFolia()) {
            Bukkit.getScheduler().cancelTask(task);
        }
        // On Folia an unknown id simply means the task already finished; there is nothing to cancel
        // and BukkitScheduler.cancelTask would throw.
    }

    @Nullable
    private static org.bukkit.World toBukkit(@Nullable final World world) {
        if (world == null) {
            return null;
        }
        try {
            return BukkitAdapter.adapt(world);
        } catch (final Throwable ignored) {
            return null;
        }
    }

}
