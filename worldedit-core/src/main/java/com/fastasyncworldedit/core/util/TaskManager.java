package com.fastasyncworldedit.core.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.task.RunnableVal;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public abstract class TaskManager {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    /**
     * @deprecated Use {@link #taskManager()} to get an instance.
     */
    @Deprecated(forRemoval = true, since = "2.0.0")
    public static TaskManager IMP;
    static TaskManager INSTANCE;
    private final ForkJoinPool pool = new ForkJoinPool();

    protected TaskManager() {
        INSTANCE = this;
    }

    /**
     * Gets an instance of the TaskManager.
     *
     * @return an instance of the TaskManager
     * @since 2.0.0
     */
    public static TaskManager taskManager() {
        if (INSTANCE == null) {
            INSTANCE = Fawe.platform().getTaskManager();
        }
        return INSTANCE;
    }

    /**
     * Run a repeating task on the main thread.
     *
     * @param runnable the task to run
     * @param interval in ticks
     */
    public abstract int repeat(@Nonnull final Runnable runnable, final int interval);

    /**
     * Run a repeating task asynchronously.
     *
     * @param runnable the task to run
     * @param interval in ticks
     * @return the task id number
     */
    public abstract int repeatAsync(@Nonnull final Runnable runnable, final int interval);

    /**
     * Run a task asynchronously.
     *
     * @param runnable the task to run
     */
    public abstract void async(@Nonnull final Runnable runnable);

    /**
     * Run a task on the main thread.
     *
     * @param runnable the task to run
     */
    public abstract void task(@Nonnull final Runnable runnable);

    /**
     * Get the public ForkJoinPool.
     * - ONLY SUBMIT SHORT LIVED TASKS<br>
     * - DO NOT USE SLEEP/WAIT/LOCKS IN ANY SUBMITTED TASKS<br>
     */
    public ForkJoinPool getPublicForkJoinPool() {
        return pool;
    }

    /**
     * Run a bunch of tasks in parallel using the shared thread pool.
     *
     * @deprecated Deprecated without replacement as unused internally, and poor implementation of what it's designed to do.
     */
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void parallel(Collection<Runnable> runables) {
        for (Runnable run : runables) {
            pool.submit(run);
        }
        pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    /**
     * Run a bunch of tasks in parallel.
     *
     * @param runnables  the tasks to run
     * @param numThreads number of threads (null = config.yml parallel threads)
     * @deprecated Deprecated without replacement as unused internally, and poor implementation of what it's designed to do.
     */
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void parallel(Collection<Runnable> runnables, @Nullable Integer numThreads) {
        if (runnables == null) {
            return;
        }
        if (numThreads == null) {
            numThreads = Settings.settings().QUEUE.PARALLEL_THREADS;
        }
        if (numThreads <= 1) {
            for (Runnable run : runnables) {
                if (run != null) {
                    run.run();
                }
            }
            return;
        }
        int numRuns = runnables.size();
        int amountPerThread = 1 + numRuns / numThreads;
        final Runnable[][] split = new Runnable[numThreads][amountPerThread];
        Thread[] threads = new Thread[numThreads];
        int i = 0;
        int j = 0;
        for (Runnable run : runnables) {
            split[i][j] = run;
            if (++i >= numThreads) {
                i = 0;
                j++;
            }
        }
        for (i = 0; i < threads.length; i++) {
            final Runnable[] toRun = split[i];
            Thread thread = threads[i] = new Thread(() -> {
                for (Runnable run : toRun) {
                    if (run != null) {
                        run.run();
                    }
                }
            });
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * Disable async catching for a specific task.
     */
    public void runUnsafe(Runnable run) {
        QueueHandler queue = Fawe.instance().getQueueHandler();
        queue.startUnsafe(Fawe.isMainThread());
        try {
            run.run();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        queue.endUnsafe(Fawe.isMainThread());
    }

    /**
     * Run a task on the current thread or asynchronously.
     * - If it's already the main thread, it will just call run()
     *
     * @param runnable the task to run
     * @param async    whether the task should run on the main thread
     */
    public void taskNow(@Nonnull final Runnable runnable, boolean async) {
        if (async) {
            async(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Run a task as soon as possible on the main thread.
     * - Non blocking if not calling from the main thread
     *
     * @param runnable the task to run
     */
    public void taskNowMain(@Nonnull final Runnable runnable) {
        if (Fawe.isMainThread()) {
            runnable.run();
        } else {
            task(runnable);
        }
    }

    /**
     * Run a task as soon as possible not on the main thread.
     *
     * @param runnable the task to run
     * @see Fawe#isMainThread()
     */
    public void taskNowAsync(@Nonnull final Runnable runnable) {
        taskNow(runnable, Fawe.isMainThread());
    }

    /**
     * Run a task on the main thread at the next tick or now async.
     *
     * @param runnable the task to run.
     * @param async    whether the task should run on the main thread
     */
    public void taskSoonMain(@Nonnull final Runnable runnable, boolean async) {
        if (async) {
            async(runnable);
        } else {
            task(runnable);
        }
    }


    /**
     * Run a task later on the main thread.
     *
     * @param runnable the task to run
     * @param delay    in ticks
     */
    public abstract void later(@Nonnull final Runnable runnable, final int delay);

    /**
     * Run a task later asynchronously.
     *
     * @param runnable the task to run
     * @param delay    in ticks
     */
    public abstract void laterAsync(@Nonnull final Runnable runnable, final int delay);

    /**
     * Cancel a task.
     *
     * @param task the id of the task to cancel
     */
    public abstract void cancel(final int task);

    // ---------------------------------------------------------------------------------------------
    // Region-aware task submission.
    //
    // On a single-threaded platform all of these collapse to the existing global behaviour. On Folia
    // (where the world is split into independently ticking regions, each owned by one thread) these
    // are the only correct way to touch world state: the work must execute on the thread that owns
    // the region containing the target chunk.
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the calling thread may currently read or write the region containing the given chunk.
     *
     * @param world  target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the calling thread owns that region
     */
    public boolean isRegionOwner(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ) {
        return Fawe.isMainThread();
    }

    /**
     * Whether the calling thread is a server tick thread, i.e. a thread that must never block.
     *
     * @return true if the calling thread is a tick thread
     */
    public boolean isTickThread() {
        return Fawe.isMainThread();
    }

    /**
     * Whether the chunk is loaded right now, and can therefore be touched without triggering a load.
     *
     * <p>This is the discriminator that makes up-front routing possible on Folia. Reading or writing
     * an already-loaded chunk from the region thread that owns it never blocks. Triggering a
     * <em>load</em> from a tick thread does: it parks the thread inside the server's synchronous
     * chunk load, so the region cannot tick and the watchdog eventually kills it.</p>
     *
     * <p>So before applying an edit on a tick thread, callers check this for the chunks involved.
     * If they are all loaded the apply can proceed inline; otherwise it has to be deferred off-tick
     * where blocking is legal.</p>
     *
     * @param world  target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the chunk is currently loaded
     */
    public boolean isChunkLoaded(
            @Nullable final com.sk89q.worldedit.world.World world,
            final int chunkX,
            final int chunkZ
    ) {
        // Single-threaded platforms cannot deadlock on a load, so treat every chunk as loadable.
        return true;
    }

    /**
     * Ensure a chunk is loaded, blocking until it is.
     *
     * <p><b>Off-tick only.</b> On Folia the load is performed through the server's asynchronous
     * chunk API; the caller must not be a region tick thread, because blocking a region on a load it
     * could itself complete is what stalls the region.</p>
     *
     * <p>Used by the up-front routing path: an edit that needs unloaded terrain is moved off-tick,
     * its chunks are loaded here, and the apply is then delivered to the owning region where it can
     * run without triggering a load.</p>
     *
     * @param world  target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the chunk is loaded
     */
    public boolean loadChunk(
            @Nullable final com.sk89q.worldedit.world.World world,
            final int chunkX,
            final int chunkZ
    ) {
        // Single-threaded platforms: a synchronous load is safe from the server thread.
        return true;
    }


    /**
     * Run a task on the thread that owns the region containing the given chunk.
     *
     * <p>If the caller already owns that region the task runs inline; otherwise it is queued to the
     * region and this method returns without waiting.</p>
     *
     * @param world    target world
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param runnable task to run
     */
    public void regionTask(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ,
                           @Nonnull final Runnable runnable) {
        task(runnable);
    }

    /**
     * Run a task on the thread that owns the region containing the given chunk, after a delay.
     *
     * @param world    target world
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param runnable task to run
     * @param delay    delay in ticks, must be positive
     */
    public void regionLater(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ,
                            @Nonnull final Runnable runnable, final int delay) {
        later(runnable, delay);
    }

    /**
     * Run a task on the thread that owns the region containing the given chunk and block on it,
     * returning its result.
     *
     * <p>This is the region-correct replacement for {@link #sync(Supplier)} where the work touches a
     * specific chunk. If the caller already owns the region, the supplier runs inline.</p>
     *
     * <p><b>Deadlock hazard on Folia:</b> blocking on work targeted at a <em>different</em> region
     * while holding a region thread can deadlock, because the target region may need this thread to
     * make progress. Prefer {@link #regionFuture} plus a continuation where possible.</p>
     *
     * @param world    target world
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param supplier work producing the result
     * @param <T>      result type
     * @return the supplier's result
     */
    public <T> T regionSync(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ,
                            @Nonnull final Supplier<T> supplier) {
        return sync(supplier);
    }

    /**
     * Submit a task to the thread that owns the region containing the given chunk and return a
     * future completed when it has run there.
     *
     * <p>Unlike {@link #regionTask}, this always returns a <em>pending</em> future rather than
     * running the task inline, so callers can safely collect a set of futures and join them. That
     * matters for multi-region edits: each chunk partition is submitted here and the caller joins
     * them once every owning region has applied its share.</p>
     *
     * @param world    target world
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param runnable task to run
     * @return future completed once the task has run on the owning region
     */
    public java.util.concurrent.CompletableFuture<Void> regionFuture(
            @Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ,
            @Nonnull final Runnable runnable) {
        final java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        regionTask(world, chunkX, chunkZ, () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Get the current tick for the region containing the given chunk.
     *
     * <p>On single-threaded platforms this is the global tick. On Folia each region keeps its own
     * counter, so a single global value would be wrong for throttling per-region work.</p>
     *
     * @param world  target world, may be null for the global tick
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return the current tick for that region
     */
    public long regionTick(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ) {
        return Fawe.instance().getTimer().getTick();
    }

    /**
     * Get the TPS of the region containing the given chunk, used to throttle per-region work.
     *
     * <p>On single-threaded platforms this is the server-wide TPS.</p>
     *
     * @param world  target world, may be null for the global TPS
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return the region's current TPS
     */
    public double regionTps(@Nullable final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ) {
        return Fawe.instance().getTimer().getTPS();
    }


    /**
     * Break up a task and run it in fragments of 5ms.<br>
     * - Each task will run on the main thread.<br>
     *
     * @param objects  the list of objects to run the task for
     * @param task     the task to run on each object
     * @param whenDone when the object task completes
     */
    public <T> void objectTask(Collection<T> objects, final RunnableVal<T> task, final Runnable whenDone) {
        final Iterator<T> iterator = objects.iterator();
        task(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                boolean hasNext;
                while ((hasNext = iterator.hasNext()) && System.currentTimeMillis() - start < 5) {
                    task.value = iterator.next();
                    task.run();
                }
                if (!hasNext) {
                    later(whenDone, 1);
                } else {
                    later(this, 1);
                }
            }
        });
    }

    /**
     * @deprecated Deprecated without replacement as unused internally, and poor implementation of what it's designed to do.
     */
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void wait(AtomicBoolean running, int timeout) {
        try {
            long start = System.currentTimeMillis();
            synchronized (running) {
                while (running.get()) {
                    running.wait(timeout);
                    if (running.get() && System.currentTimeMillis() - start > 60000) {
                        new RuntimeException("FAWE is taking a long time to execute a task (might just be a symptom): ").printStackTrace();
                        LOGGER.info("For full debug information use: /fawe threads");
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * @deprecated Deprecated without replacement as unused internally, and poor implementation of what it's designed to do.
     */
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void notify(AtomicBoolean running) {
        running.set(false);
        synchronized (running) {
            running.notifyAll();
        }
    }

    public void taskWhenFree(@Nonnull Runnable run) {
        if (Fawe.isMainThread()) {
            run.run();
        } else {
            Fawe.instance().getQueueHandler().sync(run);
        }
    }

    /**
     * Run a task on the main thread when the TPS is high enough, and wait for execution to finish.
     * - Useful if you need to access something from the Bukkit API from another thread<br>
     * - Usually wait time is around 25ms<br>
     */
    public <T> T syncWhenFree(@Nonnull final RunnableVal<T> function) {
        if (Fawe.isMainThread()) {
            function.run();
            return function.value;
        }
        try {
            return Fawe.instance().getQueueHandler().sync((Supplier<T>) function).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Run a task on the main thread when the TPS is high enough, and wait for execution to finish.
     * - Useful if you need to access something from the Bukkit API from another thread<br>
     * - Usually wait time is around 25ms<br>
     */
    public <T> T syncWhenFree(@Nonnull final Supplier<T> supplier) {
        if (Fawe.isMainThread()) {
            return supplier.get();
        }
        try {
            return Fawe.instance().getQueueHandler().sync(supplier).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Quickly run a task on the main thread, and wait for execution to finish.
     * - Useful if you need to access something from the Bukkit API from another thread<br>
     * - Usually wait time is around 25ms
     */
    public <T> T sync(@Nonnull final RunnableVal<T> function) {
        return sync((Supplier<T>) function);
    }

    /**
     * Quickly run a task on the main thread, and wait for execution to finish.
     * - Useful if you need to access something from the Bukkit API from another thread<br>
     * - Usually wait time is around 25ms<br>
     */
    public <T> T sync(final Supplier<T> function) {
        if (Fawe.isMainThread()) {
            return function.get();
        }
        try {
            return Fawe.instance().getQueueHandler().sync(function).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

}
