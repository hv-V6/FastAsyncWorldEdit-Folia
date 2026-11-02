package foliatest;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Extended Folia test harness.
 *
 * <p>Each scenario runs on the region thread that owns the chunks it touches, which is the only
 * legal place to perform world work on Folia. Results are collected into one report so a run either
 * fully passes or names exactly which stage failed.</p>
 */
public final class FoliaFaweTest extends JavaPlugin {

    private final List<String> report = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> start(), 40L);
        getLogger().info("TEST: armed");
    }

    private void start() {
        final World world = Bukkit.getWorld("world");
        if (world == null) {
            getLogger().severe("TEST: no 'world'");
            return;
        }
        final long t0 = System.nanoTime();

        runOnRegion(world, 0, 0, () -> scenarioBasic(world), "basic");
        runOnRegion(world, 0, 0, () -> scenarioCopyPaste(world), "copy-paste");
        runOnRegion(world, 320, 320, () -> scenarioDistantRegion(world), "distant-region");
        // The important one: one edit whose chunks belong to several different Folia regions.
        runOnRegion(world, 0, 0, () -> scenarioMultiRegion(world), "multi-region");
        // Two overlapping multi-region edits at once: this is what deadlocked on the chunk-GET
        // monitor (getChunk held `synchronized (this)` across a blocking chunk load).
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> scenarioConcurrent(world), 100L);
        // Tick-thread edit that must reach foreign chunks: measures the failure mode.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> runOnRegion(world, 0, 0, () -> scenarioTickForeign(world), "tick-foreign"), 260L);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> runOnRegion(world, 12, 12, () -> scenarioRegen(world), "regen"), 400L);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
        }, 120L);

        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            getLogger().info("=========== FOLIA FAWE TEST REPORT ===========");
            for (final String line : report) {
                getLogger().info("  " + line);
            }
            getLogger().info("==============================================");
            getLogger().info("TEST COMPLETE in " + ((System.nanoTime() - t0) / 1_000_000) + "ms, scenarios=" + report.size());
        }, 600L);
    }

    private void runOnRegion(final World world, final int cx, final int cz, final Scenario body, final String name) {
        Bukkit.getRegionScheduler().execute(this, world, cx, cz, () -> {
            try {
                // Diagnostic: what does FAWE believe about this thread?
                if ("basic".equals(name)) {
                    getLogger().info("DIAG thread=" + Thread.currentThread().getName()
                            + " faweIsMainThread=" + com.fastasyncworldedit.core.Fawe.isMainThread()
                            + " bukkitIsPrimary=" + Bukkit.isPrimaryThread()
                            + " isGlobalTickThread=" + Bukkit.isGlobalTickThread()
                            + " ownsChunk=" + Bukkit.isOwnedByCurrentRegion(world, cx, cz));
                }
                body.run();
            } catch (final Throwable t) {
                report.add("FAIL [" + Thread.currentThread().getName() + "]: " + t);
                getLogger().severe("TEST scenario failed: " + t);
                t.printStackTrace();
            }
        });
    }

    @FunctionalInterface
    private interface Scenario {
        void run() throws Exception;
    }

    private static BlockState state(final com.sk89q.worldedit.world.block.BlockType type) {
        return type.getDefaultState();
    }

    /** Basic single-block and bulk region writes. */
    private void scenarioBasic(final World world) throws Exception {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int x = 0, y = 120, z = 0;

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlock(BlockVector3.at(x, y, z), state(BlockTypes.STONE));
            final Region r = new CuboidRegion(BlockVector3.at(x, y + 2, z), BlockVector3.at(x + 7, y + 5, z + 7));
            session.setBlocks(r, state(BlockTypes.GOLD_BLOCK));
            session.flushQueue();
        }

        final String a = world.getBlockAt(x, y, z).getType().toString();
        final String b = world.getBlockAt(x + 7, y + 5, z + 7).getType().toString();
        report.add((a.equals("STONE") && b.equals("GOLD_BLOCK") ? "PASS " : "FAIL ")
                + "basic: single=" + a + " bulk=" + b);
    }

    /** Copy a small volume and paste it elsewhere in the same region. */
    private void scenarioCopyPaste(final World world) throws Exception {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int sx = 40, sy = 120, sz = 40;
        final int dx = 60, dy = 120, dz = 40;
        final Region srcRegion = new CuboidRegion(BlockVector3.at(sx, sy, sz), BlockVector3.at(sx + 3, sy + 3, sz + 3));

        // Build a distinctive source shape.
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlocks(srcRegion, state(BlockTypes.EMERALD_BLOCK));
            session.flushQueue();
        }

        // Copy into a clipboard.
        final BlockArrayClipboard clipboard = new BlockArrayClipboard(srcRegion);
        clipboard.setOrigin(srcRegion.getMinimumPoint());
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            Operations.complete(new ForwardExtentCopy(we, srcRegion, clipboard, srcRegion.getMinimumPoint()));
        }

        // Paste at the destination.
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            Operations.complete(new ForwardExtentCopy(
                    clipboard, clipboard.getRegion(), clipboard.getOrigin(),
                    session, BlockVector3.at(dx, dy, dz)));
            session.flushQueue();
        }

        final String src = world.getBlockAt(sx, sy, sz).getType().toString();
        final String dst = world.getBlockAt(dx, dy, dz).getType().toString();
        final String dstCorner = world.getBlockAt(dx + 3, dy + 3, dz + 3).getType().toString();
        report.add((src.equals("EMERALD_BLOCK") && dst.equals("EMERALD_BLOCK") && dstCorner.equals("EMERALD_BLOCK")
                ? "PASS " : "FAIL ")
                + "copy-paste: src=" + src + " dst=" + dst + " dstCorner=" + dstCorner);
    }

    /** A distant region, proving edits work outside spawn and independently of other regions. */
    private void scenarioDistantRegion(final World world) throws Exception {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int cx = 320, cz = 320;
        final int x = cx << 4, y = 120, z = cz << 4;

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlock(BlockVector3.at(x, y, z), state(BlockTypes.REDSTONE_BLOCK));
            session.flushQueue();
        }

        final String got = world.getBlockAt(x, y, z).getType().toString();
        final boolean owned = Bukkit.isOwnedByCurrentRegion(world, cx, cz);
        report.add((got.equals("REDSTONE_BLOCK") ? "PASS " : "FAIL ")
                + "distant-region: type=" + got + " ownedByThisThread=" + owned);
    }

    /**
     * Verify the cross-region guard fires instead of hanging the server.
     *
     * <p>Deliberately performs, from a region thread, a bulk edit whose far chunks are owned by other
     * regions. Before the guard existed this stalled the region until Folia's watchdog killed it.
     * Now it must raise {@code ForeignRegionEditException} promptly, and — critically — the region
     * must stay alive and keep ticking afterwards, which is what we assert by scheduling follow-up
     * work on the same region.</p>
     */
    private void scenarioForeignRegionGuard(final World world) {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int y = 140;

        // From chunk (0,0)'s region, try to fill a volume reaching into distant chunks.
        final BlockVector3 min = BlockVector3.at(0, y, 0);
        final BlockVector3 max = BlockVector3.at(40 * 16 - 1, y, 40 * 16 - 1);

        final long t0 = System.nanoTime();
        boolean threw = false;
        Throwable caught = null;
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlocks((Region) new CuboidRegion(min, max), state(BlockTypes.COAL_BLOCK));
            session.flushQueue();
        } catch (final Throwable t) {
            threw = true;
            caught = t;
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;

        final boolean isGuard = caught != null
                && (caught.getClass().getName().contains("ForeignRegionEditException")
                    || (caught.getCause() != null
                        && caught.getCause().getClass().getName().contains("ForeignRegionEditException")));

        report.add((threw && isGuard ? "PASS " : "FAIL ")
                + "foreign-guard: threw=" + threw
                + " type=" + (caught == null ? "none" : caught.getClass().getSimpleName())
                + " in " + ms + "ms (expected a fast, actionable failure)");

        // Prove the region survived and is still ticking: this region task must actually run.
        Bukkit.getRegionScheduler().execute(this, world, 0, 0, () ->
                report.add("PASS foreign-guard-survival: region still ticking after the rejection"));
    }

    /**
     * Two overlapping multi-region edits launched concurrently from the async scheduler.
     *
     * <p>This is the scenario that exposed a lock-while-blocking deadlock:
     * {@code PaperweightGetBlocks.getChunk()} held {@code synchronized (this)} across
     * {@code ensureLoaded(...).get()}, so a region thread calling {@code trim()} on the same object
     * blocked forever while the loader waited on that region.</p>
     */
    private void scenarioConcurrent(final World world) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();

        final com.sk89q.worldedit.world.block.BlockType[] types = {
                BlockTypes.OBSIDIAN, BlockTypes.BRICKS
        };
        final int[][] offsets = {{0, 48}, {8, 56}};

        final long t0 = System.nanoTime();
        // Do NOT await here: this method may be invoked from the global region thread, and blocking
        // a tick thread is exactly the mistake FAWE must avoid (it would trip Folia's watchdog).
        // Instead, report from whichever edit finishes last.
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(2);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                try {
                    final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
                    final int ox = offsets[idx][0] * 16;
                    final int oz = offsets[idx][1] * 16;
                    final int y = 150 + idx * 4;
                    try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
                        session.setBlocks(
                                (Region) new CuboidRegion(
                                        BlockVector3.at(ox, y, oz),
                                        BlockVector3.at(ox + 20 * 16, y, oz + 20 * 16)),
                                state(types[idx]));
                        session.flushQueue();
                    }
                    applied.incrementAndGet();
                } catch (final Throwable t) {
                    failures.incrementAndGet();
                    getLogger().severe("TEST concurrent edit " + idx + " failed: " + t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        final long ms = (System.nanoTime() - t0) / 1_000_000;
                        report.add((applied.get() == 2 ? "PASS " : "FAIL ")
                                + "concurrent multi-region: applied=" + applied.get() + "/2"
                                + " failures=" + failures.get()
                                + " in " + ms + "ms");
                    }
                }
            });
        }
    }

    /**
     * Regenerate a region from a region tick thread.
     *
     * <p>Regen builds a throwaway world and generates chunks into it, so it inherently loads chunks.
     * This records whether it works, throws cleanly, or stalls the region.</p>
     */
    private void scenarioRegen(final World world) {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final Region region = new CuboidRegion(BlockVector3.at(200, 100, 200), BlockVector3.at(215, 130, 215));

        // Mark the area first so a successful regen is observable: afterwards these must no longer
        // be bedrock.
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlocks((Region) region, state(BlockTypes.BEDROCK));
            session.flushQueue();
        }

        final long t0 = System.nanoTime();
        Throwable caught = null;
        boolean ok = false;
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            ok = we.regenerate(region, session, com.sk89q.worldedit.world.RegenOptions.builder().build());
            session.flushQueue();
        } catch (final Throwable t) {
            caught = t;
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        report.add(((caught == null) ? "PASS " : "FAIL ")
                + "regen: returned=" + ok
                + " type=" + (caught == null ? "none" : caught.getClass().getSimpleName())
                + (caught == null ? "" : " (" + caught.getMessage() + ")")
                + " in " + ms + "ms");

        // Verify on the owning regions that the marker is gone, i.e. regen actually wrote blocks.
        final int[][] samples = {{200, 200}, {207, 207}, {215, 215}, {205, 101}, {210, 130}};
        final java.util.concurrent.atomic.AtomicInteger replaced = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(samples.length);
        for (final int[] s : samples) {
            Bukkit.getRegionScheduler().execute(this, world, s[0] >> 4, s[1] >> 4, () -> {
                try {
                    if (!world.getBlockAt(s[0], 110, s[1]).getType().toString().equals("BEDROCK")) {
                        replaced.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            report.add((replaced.get() == samples.length ? "PASS " : "FAIL ")
                    + "regen-applied: " + replaced.get() + "/" + samples.length
                    + " sampled blocks replaced by generated terrain");
        });
    }

    /**
     * A bulk edit started from a region tick thread whose volume reaches into other regions.
     *
     * <p>Documents current behaviour: it must not take the server down. Whatever happens
     * (clean exception, partial apply, or stall) is recorded so the routing design can be judged
     * against it.</p>
     */
    private void scenarioTickForeign(final World world) {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int y = 160;
        final long t0 = System.nanoTime();
        Throwable caught = null;
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlocks(
                    (Region) new CuboidRegion(BlockVector3.at(0, y, 0), BlockVector3.at(40 * 16 - 1, y, 40 * 16 - 1)),
                    state(BlockTypes.COAL_BLOCK));
            session.flushQueue();
        } catch (final Throwable t) {
            caught = t;
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        report.add("INFO tick-foreign: completed=" + (caught == null)
                + " type=" + (caught == null ? "none" : caught.getClass().getSimpleName())
                + " in " + ms + "ms");

        // Returning without throwing is not enough: on a tick thread the join policy does not wait
        // for foreign-region partitions, so verify the data really landed. Sample across the volume,
        // reading each point on the region that owns it.
        final int[][] samples = {{0, 0}, {100, 100}, {300, 300}, {500, 500}, {620, 620}};
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(samples.length);
        for (final int[] s : samples) {
            Bukkit.getRegionScheduler().execute(this, world, s[0] >> 4, s[1] >> 4, () -> {
                try {
                    if (world.getBlockAt(s[0], 160, s[1]).getType().toString().equals("COAL_BLOCK")) {
                        ok.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            report.add((ok.get() == samples.length ? "PASS " : "FAIL ")
                    + "tick-foreign-applied: " + ok.get() + "/" + samples.length
                    + " sampled chunks actually written from a tick thread");
        });
    }

    /**
     * A single edit whose chunks are spread across several Folia regions, driven from an async
     * thread (loading another region's chunks is only legal off-tick).
     *
     * <p>Folia partitions the world into regions that tick independently; with the default
     * {@code threaded-regions.grid-exponent} of 4 a region covers 16x16 chunks, so a wide edit
     * necessarily crosses region boundaries. The edit is issued from an <b>async</b> thread rather
     * than from a region thread: loading a chunk in another region requires Folia's async chunk
     * pipeline, and blocking on that is only legal off-tick. Each chunk must then be applied by the
     * region that owns it.</p>
     */
    private void scenarioMultiRegion(final World world) throws Exception {
        // Kick off from the async scheduler: no tick is stalled by our waiting, and chunk loads for
        // foreign regions are permitted.
        Bukkit.getAsyncScheduler().runNow(this, scheduled -> {
            try {
                runMultiRegionEdit(world);
            } catch (final Throwable t) {
                report.add("FAIL multi-region: " + t);
                getLogger().severe("TEST multi-region failed: " + t);
                t.printStackTrace();
            }
        });
    }

    private void runMultiRegionEdit(final World world) throws Exception {
        final com.sk89q.worldedit.world.World we = BukkitAdapter.adapt(world);
        final int y = 130;

        // ~24 chunks wide in both directions, centred on spawn: deliberately crosses many 16-chunk
        // region boundaries rather than staying inside one region's section.
        final BlockVector3 min = BlockVector3.at(0, y, 0);
        final BlockVector3 max = BlockVector3.at(24 * 16 - 1, y, 24 * 16 - 1);

        getLogger().info("TEST multi-region: starting async bulk edit (tickThread=" + Bukkit.isPrimaryThread() + ")");

        final long t0 = System.nanoTime();
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(we).build()) {
            session.setBlocks((Region) new CuboidRegion(min, max), state(BlockTypes.LAPIS_BLOCK));
            session.flushQueue();
        }
        final long writeMs = (System.nanoTime() - t0) / 1_000_000;

        // Verify on the region threads that own the sample chunks, since reading world state is only
        // legal there.
        final int[][] samples = {
                {0, 0}, {1, 1}, {17, 3}, {33, 33}, {95, 95}, {180, 200}, {383, 383}
        };
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(samples.length);
        for (final int[] s : samples) {
            final int cx = s[0] >> 4;
            final int cz = s[1] >> 4;
            Bukkit.getRegionScheduler().execute(this, world, cx, cz, () -> {
                try {
                    if (world.getBlockAt(s[0], y, s[1]).getType().toString().equals("LAPIS_BLOCK")) {
                        ok.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);

        report.add((ok.get() == samples.length ? "PASS " : "FAIL ")
                + "multi-region: " + ok.get() + "/" + samples.length + " sampled chunks applied"
                + " (write took " + writeMs + "ms)");
    }

}
