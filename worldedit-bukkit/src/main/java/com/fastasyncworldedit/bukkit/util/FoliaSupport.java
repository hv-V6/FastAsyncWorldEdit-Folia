package com.fastasyncworldedit.bukkit.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * Detects whether the server is Folia and exposes the differences that matter to FAWE.
 *
 * <p>Folia is a Paper fork that replaces the single server tick loop with regionised ticking: the
 * world is split into regions that tick independently and in parallel, each pinned to one thread at
 * a time. Consequences that reach every part of FAWE:</p>
 *
 * <ul>
 *     <li>There is no single "main thread". {@code Bukkit.isPrimaryThread()} is true only on the
 *     <em>global</em> region thread, which owns no ordinary chunks.</li>
 *     <li>{@code Bukkit.getScheduler()} exists but its sync methods throw
 *     {@code UnsupportedOperationException}; the region-aware schedulers must be used instead.</li>
 *     <li>World access is only legal from the thread that owns the region holding the data.
 *     {@code Bukkit.isOwnedByCurrentRegion} is the compiler-enforced check.</li>
 *     <li>Tick counters are per region, not global.</li>
 * </ul>
 *
 * <p>This class is internal and may change at any time.</p>
 */
@ApiStatus.Internal
public final class FoliaSupport {

    private static final boolean FOLIA;

    static {
        // RegionScheduler only exists on Folia. Probing for one of its API types is the supported way
        // to detect the platform, and avoids depending on a specific server class name.
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            folia = true;
        } catch (final ClassNotFoundException ignored) {
            folia = false;
        }
        FOLIA = folia;
    }

    private FoliaSupport() {
    }

    /**
     * @return true when running on Folia (or another regionised Paper fork exposing the same API)
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Whether the calling thread is one of Folia's region tick threads.
     *
     * <p>{@code Bukkit.isPrimaryThread()} is <b>not</b> the right question on Folia: it reports true
     * only for the global region thread, which owns no ordinary chunks. World work is legal from any
     * region tick thread, subject to owning the relevant region. Moonrise's
     * {@code TickThread.isTickThread()} answers the general question, but it lives in the server
     * (NMS) classes that this module does not compile against, so it is resolved reflectively and
     * cached once.</p>
     *
     * @return true if the calling thread is a region tick thread
     */
    public static boolean isTickThread() {
        if (!FOLIA) {
            return org.bukkit.Bukkit.isPrimaryThread();
        }
        if (TICK_THREAD_CHECK != null) {
            try {
                return (Boolean) TICK_THREAD_CHECK.invokeExact();
            } catch (final Throwable ignored) {
                // Fall through to the conservative answer below.
            }
        }
        // Conservative fallback: treat the global tick thread as a tick thread. Region threads
        // cannot use this path when the NMS helper is missing, so callers get a safe "no" rather
        // than a false positive that would let them touch the world off-region.
        return org.bukkit.Bukkit.isGlobalTickThread();
    }

    /**
     * {@code ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()} resolved reflectively.
     * Null when unavailable (non-Folia, or an unexpected server layout).
     */
    private static final java.lang.invoke.MethodHandle TICK_THREAD_CHECK = resolveTickThreadCheck();

    private static java.lang.invoke.MethodHandle resolveTickThreadCheck() {
        if (!FOLIA) {
            return null;
        }
        try {
            final Class<?> tickThread = Class.forName("ca.spottedleaf.moonrise.common.util.TickThread");
            return java.lang.invoke.MethodHandles.publicLookup()
                    .findStatic(tickThread, "isTickThread", java.lang.invoke.MethodType.methodType(boolean.class));
        } catch (final Throwable ignored) {
            return null;
        }
    }

}
