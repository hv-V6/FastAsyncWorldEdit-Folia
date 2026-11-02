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
     * Whether the calling thread is one of Folia's tick threads.
     *
     * <p>{@code Bukkit.isPrimaryThread()} looks like the wrong question on Folia — there is no single
     * primary thread — but it is in fact exactly right. Verified by decompiling Folia 26.1.2's
     * {@code CraftServer}:</p>
     *
     * <pre>
     *   public boolean isPrimaryThread() {
     *       return TickThread.isTickThread();   // Moonrise
     *   }
     * </pre>
     *
     * <p>So on Folia it means "am I any region tick thread?", not "am I the one server thread". That
     * is the predicate FAWE needs in order to decide whether it may do server work inline. An
     * earlier attempt resolved {@code TickThread.isTickThread()} reflectively and silently failed,
     * falling back to {@code isGlobalTickThread()} (false on ordinary regions) — which made every
     * region thread look off-thread and caused bulk edits to fan out to FAWE's own pool and deadlock
     * the region. Going through the public Bukkit API avoids that class-loading hazard entirely.</p>
     *
     * @return true if the calling thread is a region (or global) tick thread
     */
    public static boolean isTickThread() {
        // On Paper/Spigot this is the single server thread, which is the same concept.
        return org.bukkit.Bukkit.isPrimaryThread();
    }

}
