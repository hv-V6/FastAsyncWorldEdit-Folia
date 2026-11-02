# FastAsyncWorldEdit — Folia Fork

A fork of [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) (FAWE) ported to
run correctly on **[Folia](https://github.com/PaperMC/Folia) stable 26.1.2**.

Folia replaces Paper's single tick loop with **regionised multithreading**: each world is split into
regions that tick independently and in parallel, and each region is owned by exactly one thread at a
time. That invalidates the central assumption FAWE was built on — that there is one server thread on
which all world access happens.

> **Why this document exists.** A port like this is mostly judgement calls under uncertainty. The
> reasoning below records *what* changed and *why*, including two approaches that were tried and
> **reverted because they were wrong**. That history is the useful part; the diffs alone don't explain
> why the obvious fixes don't work.

---

## Status

Built and verified against **Folia 26.1.2** (`26.1.2-DEV-HEAD@62dc0f2`), **Java 25**.

| Scenario | Result |
|---|---|
| Plugin loads and enables on Folia | ✅ |
| Single + bulk block writes | ✅ |
| Copy / paste (clipboard) | ✅ |
| Edit in a distant region | ✅ |
| Multi-region edit (~24×24 chunks) | ✅ 7/7 sampled chunks |
| Two **concurrent** multi-region edits | ✅ 2/2, no deadlock |
| Tick-thread edit over **unloaded** terrain | ✅ 230 ms, 5/5 chunks written |
| `//regen` | ✅ 3.7 s, blocks verified replaced |

All runs: **zero errors, zero watchdog stalls, zero NPEs.**

### Not yet verified

These need a connected client to exercise honestly, and are **not** claimed as working:

- `//undo` / `//redo` through a real player actor (history is recorded; the replay path is untested)
- Brushes (wand/tool dispatch)
- Schematics (`//schem load` / `save`)
- Graceful degradation of WorldGuard / PlotSquared / Towny / GriefPrevention — **none of these
  support Folia**, so they should be skipped rather than loaded

---

## Building

Requires **JDK 25**.

```bash
./gradlew :worldedit-bukkit:shadowJar
# -> worldedit-bukkit/build/libs/FastAsyncWorldEdit-Folia-2.16.3-folia-SNAPSHOT.jar
```

The build targets Folia exclusively:

- The Bukkit module compiles against `dev.folia:folia-api:26.1.2.build.8-stable`.
- The NMS adapter compiles against `dev.folia:dev-bundle:26.1.2.build.8-stable` via paperweight's
  `foliaDevBundle(...)`.

> **The `-Folia` JAR is not usable on Paper or Spigot.** Its adapter is compiled against Folia's
> server internals and calls Folia-only APIs (`RegionScheduler`, `RegionizedTaskQueue`, `TickThread`,
> `RegionizedWorldData`). This is a deliberate Folia-only fork, not a dual-platform build.

---

## The core problem

FAWE's architecture assumes one thing above all: **a single main thread that owns all world state.**
That assumption is encoded in a single predicate:

```java
// worldedit-core/.../core/Fawe.java — the original
public static boolean isMainThread() {
    return instance == null || instance.thread == Thread.currentThread();
}
```

`instance.thread` is captured once at startup. This one boolean decides whether work runs **inline**
or is **queued**, and it gates:

- `TaskManager` — `sync`, `syncWhenFree`, `taskNowMain`, `taskWhenFree`
- `QueueHandler` — whether to execute a chunk apply inline or hand it to a pool
- `SingleThreadQueueExtent` — the same decision, at the chunk level
- `BukkitWorld.checkLoadedChunk` — `getChunkAt` vs `getChunkAtAsync`

On Folia there is no such thread. There are *N* region threads plus a global thread, and world data
belongs to whichever region is ticking it. So the entire notion had to be replaced.

---

## The threading model that replaced it

Three questions, previously collapsed into one boolean:

| Predicate | Question it answers | Folia equivalent |
|---|---|---|
| `isTickThread()` | May this thread do server work inline at all? | `Bukkit.isPrimaryThread()` |
| `isRegionOwner(world, cx, cz)` | May this thread touch **this chunk**? | `Bukkit.isOwnedByCurrentRegion(...)` |
| `isGlobalThread()` | May this thread touch global state? | `Bukkit.isGlobalTickThread()` |

### A discovery worth recording

`Bukkit.isPrimaryThread()` looks like the wrong question on Folia — there is no single primary thread.
It is in fact **exactly right**. Decompiling Folia's `CraftServer`:

```java
public boolean isPrimaryThread() {
    return TickThread.isTickThread();   // Moonrise
}
```

So on Folia it means "am I *any* region tick thread?". I initially assumed (and wrote in my notes)
that it was true only on the global region. That was wrong, and the mistake cost real debugging time —
an earlier version resolved `TickThread.isTickThread()` reflectively, the lookup failed silently, and
it fell back to `isGlobalTickThread()` (false on ordinary regions). The result was that **every region
thread looked off-thread**, so bulk edits kept fanning out to a pool and deadlocking.

Verified with a diagnostic printed from a real region thread:

```
DIAG thread=Folia Region Scheduler Thread #1
     faweIsMainThread=false   bukkitIsPrimary=true
     isGlobalTickThread=false ownsChunk=true
```

Using the public Bukkit API removed the reflection hazard entirely.

---

## What was changed, and why

### 1. Build system

| File | Change | Why |
|---|---|---|
| `gradle/libs.versions.toml` | Added `folia` / `foliaApi` | Folia artifacts live under `dev.folia` |
| `worldedit-bukkit/build.gradle.kts` | `paper-api` → `folia-api` | folia-api is a superset; adds the region schedulers |
| `adapters/adapter-26.1/build.gradle.kts` | `paperDevBundle` → `foliaDevBundle` | Paper's bundle lacks Folia's internals |
| `settings.gradle.kts` | Only build `adapter-26.1` | Older Paper adapters cannot be region-safe |
| `buildlogic.common-java.gradle.kts` | `release = 25` | Folia 26.1.2 is Java 25 bytecode |
| `worldedit-bukkit/build.gradle.kts` | Artifact named `-Folia-`, version `-folia` | Must not be mistaken for an upstream build |
| `plugin.yml` | `folia-supported: true` | Folia refuses to load plugins that omit it |

### 2. `BukkitTaskManager` — the scheduler

Upstream calls `Bukkit.getScheduler()` in all seven methods. On Folia `CraftScheduler` **throws**
`UnsupportedOperationException` from every `runTask*` / `schedule*` variant (confirmed by decompiling
it). Replaced by routing on *target*:

| Method | Routes to |
|---|---|
| `task` / `later` / `repeat` | `GlobalRegionScheduler` — genuinely global work |
| `regionTask` / `regionLater` / `regionSync` | `RegionScheduler` — chunk-scoped work |
| `async` / `laterAsync` / `repeatAsync` | `AsyncScheduler` — must not block a tick |

Folia's schedulers return `ScheduledTask`, not an `int`, so a task-id table was added for `cancel`.

### 3. `QueueHandler` — chunk submission and joining

Originally: two **global** sync queues, drained by one repeating global task that threw
`IllegalStateException("Not main thread")` when it ran anywhere else. On Folia that task runs on the
global region, so it threw on every tick.

- Guard changed to `isGlobalThread()`.
- Added `submit(chunk, world)`, which dispatches each chunk to **its own region** via `regionFuture`.
- Join policy is now explicit: an **off-tick** thread blocks until all partitions complete; a **tick**
  thread drains only completed partitions and **never parks** on foreign region work (that would stall
  the tick and trip the watchdog).

### 4. `SingleThreadQueueExtent` — where an edit is actually applied

This is the heart of the port. The inline-apply decision became:

```java
if (ownsRegion) {
    // only if the chunk is loaded — loading it here would park this thread
    if (loaded || !isTickThread) { apply inline }
    else { route off-tick, then back to the owning region }
}
else { submit to the owning region }
```

The **loaded vs unloaded** distinction is the key insight (see "Two rejected approaches").

### 5. NMS adapter (`adapter-26.1`)

| Site | Problem | Fix |
|---|---|---|
| `addTicket` | Used `MCUtil.MAIN_EXECUTOR`, which on Folia **always hops to the global region** | Run inline if we own the chunk, else queue to the owning region |
| `sendChunk` | Used `MinecraftServer.execute`, which **throws** on Folia | Build and send on the owning region |
| `preCaptureStates` et al. | Capture state moved to `RegionizedWorldData` | Read the current region's data |
| `PaperweightFaweWorldNativeAccess` | `MinecraftServer.currentTick` **removed** | Read `regionData.getCurrentTick()`, tolerate absence |
| `PaperweightGetBlocks.getChunk` | Loaded while holding `synchronized(this)` | Load outside the monitor, publish under it |
| `PaperweightRegen.runTasks` | `pollTask()` requires region ownership | Detect and stop pumping instead of throwing |

---

## The four hard bugs

Each was found by *running* the plugin on a live Folia server, not by reading code. All four are
invisible to a compiler.

### 1. Regression deadlock in `flush()`

`flush()` handed chunk work to a shared blocking pool whenever `isMainThread()` was false — which on
a region thread is *always*. The region thread then blocked in `Future.get()` waiting for work that
only it could legally perform. Folia's watchdog killed the tick after ~5 s.

**Fix:** decide by *region ownership of that specific chunk*, and apply inline when we own it.

### 2. Cross-region chunk load deadlock

`addTicket` used `MCUtil.MAIN_EXECUTOR`. Decompiled: on Folia that is
`isMainThread() ? run : MinecraftServer.getServer().execute(run)` — **always the global region**. The
global region owns no ordinary chunks, so ticketing another region's chunk is a cross-region access,
and anything waiting on it deadlocks.

**Fix:** run inline when `TickThread.isTickThreadFor(level, cx, cz)`, else queue to the owning region.

### 3. Lock-while-blocking on the chunk-GET cache

```java
synchronized (this) {
    if (levelChunk == null) {
        levelChunk = ensureLoaded(serverLevel).get();   // blocks inside the monitor
    }
}
```

With two concurrent multi-region edits: one worker blocks in `get()` waiting for a load only the owning
region can finish; that region's thread calls `trim()` (reachable via `ChunkHolder.getOrCreateGet` →
`hasSection` → `getChunk`) and blocks on the same monitor. Neither side progresses. Observed as a
region thread in state `BLOCKED` inside `PaperweightGetBlocks.trim`.

**Fix:** load outside the monitor, publish under it, keep whichever instance won.

### 4. `//regen` — two Folia API differences

**a) `Level#getCurrentWorldData()` is not a getter.** It returns the *calling thread's* region data and
**throws** on mismatch:

```java
if (world != this) throw new IllegalStateException("World mismatch: ...");
```

Regen builds a detached `faweregentempworld` while the thread ticks the real world, so the tick read
threw and regen failed in 88 ms. Capture-state access had the identical problem.

**b) `ServerChunkCache$MainThreadExecutor#pollTask()` requires region ownership** —
`getCurrentRegionizedWorldData().world == this.world`. The regen world is never ticked by any region,
so a tick thread got `"Polling tasks from non-owned region"` and the global thread NPE'd *every tick*.

**Fix:** tolerate a world mismatch (tick degrades to 0, capture state reads empty) and stop pumping
instead of throwing each tick.

---

## Two rejected approaches (the honest part)

I shipped **two wrong fixes** before the right one. Both were caught by re-running the full suite and
reverting, and they're recorded because the failure mode is instructive.

### Attempt 1 & 2: reject the load with an exception

Reasoning: a load from a tick thread deadlocks, so refuse it loudly at the load site
(`ensureCanLoadChunk` → `ForeignRegionEditException`). It "worked" — **zero stalls** — but broke
edits that were previously fine:

| | allow load (status quo) | reject load |
|---|---|---|
| watchdog stalls | **3** | **0** |
| `basic` / `copy-paste` / `distant-region` | **PASS** | **FAIL** |

It traded working edits for zero stalls. The mistake was reasoning about *ownership* when the real
discriminator is **loaded vs unloaded**:

- Touching an **already-loaded** chunk from its owning region thread never blocks.
- Triggering a **load** from a tick thread parks it in `syncLoad` → `managedBlock`, so the region
  can't tick, the load never completes, and the watchdog kills it.

I tried the same wrong idea twice (at `getChunkImmediatelyAsync`, then at `ensureLoaded`). Both were
reverted to the last verified-good commit.

### The approach that worked: route *before* the apply

Decide **before** applying, and separate loading from writing:

1. **Off-tick**, load the chunk (blocking and async loads are both legal there).
2. Hand the **write** back to the owning region — writing chunk data from any other thread is a
   cross-region write.

Keeping those steps apart matters: calling `chunk.call()` straight from the async thread would be
exactly the corruption this port must never cause.

Result: tick-thread edit over unloaded terrain went from **16 s + 3 stalls → 230 ms, 0 stalls**, with
the blocks still landing 5/5.

---

## Verified behaviour

Nine scenarios, driven by a harness plugin that schedules each case on the region owning its chunks
(Folia's console runs on the global region, which owns no ordinary chunks, so console-driven testing
is not representative):

```
PASS basic:                  single=STONE bulk=GOLD_BLOCK
PASS copy-paste:             src=EMERALD_BLOCK dst=EMERALD_BLOCK dstCorner=EMERALD_BLOCK
PASS distant-region:         type=REDSTONE_BLOCK ownedByThisThread=true
PASS multi-region:           7/7 sampled chunks applied
PASS concurrent multi-region: applied=2/2 failures=0      (two 20x20-chunk edits at once)
PASS tick-foreign:           completed in 230ms           (tick-thread edit over unloaded terrain)
PASS tick-foreign-applied:   5/5 sampled chunks written
PASS regen:                  returned=true in 3736ms
PASS regen-applied:          5/5 sampled blocks replaced by generated terrain
```

Two of these deliberately verify **blocks landed**, not just that the call returned:
`tick-foreign-applied` and `regen-applied` (which paints the region with bedrock first and confirms
the marker is gone). An earlier version only checked a boolean return — and passed while NPEs spammed
the log. "Didn't throw" is not "worked".

---

## Known limitations

1. **Deferred routing completes asynchronously.** A tick-thread edit over unloaded terrain returns
   before its partitions are written. Placement is correct and ordered *per chunk*; only the
   "everything is on disk" completion point moves later.
2. **Region-thread edits that need foreign chunks** are routed automatically, but the caller doesn't
   wait. Callers needing a hard completion guarantee should run the edit off-tick themselves
   (`AsyncScheduler`).
3. **Third-party region plugins don't work.** WorldGuard, PlotSquared, Towny and GriefPrevention have
   no Folia support. They are not gracefully degraded yet.
4. **Player-facing paths unverified** — undo/redo, brushes, schematics (see Status).

---

## Repository layout

Everything upstream is preserved; this fork adds one branch on top of upstream history.

- `folia/26.1.2` — the port
- `FOLIA-PORT-NOTES.md` — working notes, including the pre-port analysis and the corrected
  misconceptions
- `fawetest/` — the verification harness plugin (see below)

### Verification harness

`fawetest/` contains the test plugin used to produce the results above. It exists because ordinary
testing can't reach these code paths:

- Folia's console runs on the **global** region, which owns no chunks — console commands can't edit.
- Edits must be initiated from the region that owns the target chunks.

The harness schedules each scenario on the owning region (or off-tick where that's the point), and
verifies results by reading blocks back on the owning regions.

---

## Credits & licence

FAWE is by the IntellectualSites team; this fork is a derivative work under the same licence
(GPLv3 — see `LICENSE.txt`). All upstream history, authorship and copyright are preserved. The Folia
port is my own work; bugs in it are mine, not upstream's.

Folia is a PaperMC project. `dev.folia` artifacts are published by PaperMC.
