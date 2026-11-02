# FAWE → Folia 26.1.2 Port: Architecture Analysis

Working notes from deep-read of the FAWE codebase (`2.16.3-SNAPSHOT`, upstream `c4c57f2`) against
Folia stable 26.1.2 (`dev.folia:folia-api:26.1.2.build.8-stable`).

## 1. Target platform facts (verified against local Folia 26.1.2 build)

Verified by `javap` against `_folia/stable/folia-api/build/libs/folia-api-26.1.2.local-SNAPSHOT.jar`
and `_folia/stable/folia-server/build/libs/folia-server-26.1.2.local-SNAPSHOT.jar`.

| Fact | Evidence |
|---|---|
| `Bukkit.isPrimaryThread()` on Folia means "is this **any** region tick thread?" — **not** "is this the one server thread". Verified by decompiling `CraftServer`: it is literally `return TickThread.isTickThread();`. | `javap -c CraftServer` |
| `Bukkit.getScheduler()` **exists** but `CraftScheduler` throws `UnsupportedOperationException("...")` for `runTask*`/`schedule*` variants. Only async variants partially work. | `javap -c CraftScheduler` |
| Schedulers available: `RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`. | `Server.getRegionScheduler()` etc. |
| `org.spigotmc.AsyncCatcher` has **no `enabled` field** in Folia — only `catchOp(String)`, which hard-fails via `TickThread.isTickThread()`. | `javap org.spigotmc.AsyncCatcher` |
| Region ownership test is `io.papermc.paper.threadedregions.TickThread.isTickThreadFor(Level, chunkX, chunkZ)` (Moonrise) / `Bukkit.isOwnedByCurrentRegion(...)`. | `javap TickThread` |
| `co.aikar.timings.Timings` exists in folia-api but **Timings is removed from folia-server** (0 entries). | `jar tf folia-server.jar \| grep aikar` |
| `RegionScheduler.runDelayed/runAtFixedRate` require `delay >= 1` tick and a **loaded** chunk. `execute()` queues onto the region tick task queue. | `FoliaRegionScheduler.java` |
| `RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(level, cx, cz, run)` executes inline if already on the owning region. | `RegionizedTaskQueue.java` |
| Folia server requires **Java 25**; FAWE currently compiles with `sourceCompatibility = 21`. | `_folia/stable/build.gradle.kts` |

## 2. FAWE's global-single-thread assumptions (the core of the port)

### 2.1 `Fawe.isMainThread()` — the root primitive
`worldedit-core/.../core/Fawe.java:210`
```java
public static boolean isMainThread() {
    return instance == null || instance.thread == Thread.currentThread();
}
```
`instance.thread` is captured once in the constructor (`Fawe.java:102`, `this.thread = Thread.currentThread()`)
and can be reset by `setMainThread()` (`Fawe.java:462`).

On Folia there is no single "main" thread. This one predicate gates:

- `QueueHandler.sync(...)` / `syncWhenFree(...)` — if "main thread", runs **inline**; else enqueues to the
  global `syncTasks` queue drained by a repeating global task (`QueueHandler.java:266-387`).
- `SingleThreadQueueExtent.submitUnchecked` — if "main thread", calls `chunk.call()` **inline**;
  otherwise submits to the blocking executor (`SingleThreadQueueExtent.java:258`).
- `TaskManager.taskNowMain`, `taskNowAsync`, `taskWhenFree`, `sync`, `syncWhenFree`
  (`TaskManager.java:194-374`).
- `BukkitWorld.checkLoadedChunk` — `world.getChunkAt(...)` vs `getChunkAtAsync(...)` (`BukkitWorld.java:404`).

**This must become region-aware.** The single boolean "am I the server thread" becomes
"am I the tick thread that owns the region containing this chunk/world/entity".

### 2.2 `QueueHandler` — one global sync queue + global tick budget
`worldedit-core/.../queue/implementation/QueueHandler.java`
- `syncTasks` / `syncWhenFree` are two **global** `ConcurrentLinkedQueue<FutureTask>` (lines 84, 89).
- Drained by `run()` (line 110), which **throws `IllegalStateException("Not main thread")`** if not main
  (line 111-113), and is scheduled by `TaskManager.taskManager().repeat(this, 1)` in the constructor (line 101).
- `getAllocate()` (line 141) computes a per-tick millisecond budget from `FaweTimer` TPS, a *global* TPS.

On Folia:
- The "drain sync tasks" loop must run **per region**, draining only the tasks targeting that region.
- The budget must be derived from the region's own TPS (`Bukkit.getRegionTPS(world, cx, cz)`), not a global TPS.
- `FaweTimer` (a single global tick counter driven by a repeating global task) cannot represent per-region ticks.

### 2.3 `FaweTimer` — global tick counter
`worldedit-core/.../util/FaweTimer.java` — `tick`, `tickStart`, `history[]`, `getTPS()`, `isAbove(tps)`.
Driven once per tick globally (`Fawe.java:141`). Used by `QueueHandler.getAllocate()` and
`ChunkListener`/`ChunkListener9` rate limiting.

On Folia each region has its own tick loop and its own TPS. A single global timer is wrong: it makes
one lagging region throttle all regions, and makes `isAbove()` meaningless.

### 2.4 `BukkitTaskManager` — directly on `BukkitScheduler`
`worldedit-bukkit/.../util/BukkitTaskManager.java`
Every method (`repeat`, `repeatAsync`, `async`, `task`, `later`, `laterAsync`, `cancel`) calls
`plugin.getServer().getScheduler()` — which **throws on Folia**.

Also `BukkitServerInterface.schedule()` (`BukkitServerInterface.java:137-139`) calls
`scheduleSyncRepeatingTask`, and `BukkitBlockCommandSender` uses `callSyncMethod` (`:199`).

`TaskManager` is an abstract class with a global singleton `INSTANCE` (`TaskManager.java:29`) — one
instance for the whole server. On Folia, task submission needs coordinates.

### 2.5 Removed / broken server internals
`BukkitQueueHandler.startUnsafe/endUnsafe` (`worldedit-bukkit/.../adapter/BukkitQueueHandler.java`)
reflectively flips `org.spigotmc.AsyncCatcher.enabled` and toggles Timings.
- `AsyncCatcher.enabled` **does not exist** on Folia (`Static` block silently swallows the
  `NoSuchFieldException`, leaving `asyncCatcher == null`; every `startUnsafe(true)` call then NPEs inside
  the `try/catch` and prints a stack trace — currently masked).
- Timings classes are absent from `folia-server`.

On Folia the analogous concept is different: you don't disable an "async catcher"; you either are on the
owning region thread or you are not. The "unsafe" window concept (physics freeze + async allowed) must be
re-thought per region.

### 2.6 NMS adapter (26.1) — region-hostile accesses
`worldedit-bukkit/adapters/adapter-26.1/.../fawe/v26_1/`
- `PaperweightPlatformAdapter` uses `ServerLevel.getChunkSource()`, `ChunkMap.getVisibleChunkIfPresent(...)`,
  `ChunkHolder`, `ThreadingDetector`, and `fieldLock`.
- `PaperweightGetBlocks.set` mutates `LevelChunkSection[]` under `synchronized (nmsChunk)` and
  `synchronized (super.sectionLocks[i])` — these are FAWE's own locks, not Folia's.
- `PaperweightFaweAdapter.sendFakeChunk` calls `entityPlayer.connection.send(nmsPacket)` — **packet send
  from whatever thread FAWE is on**. On Folia, packets must be sent from the player's region thread.
- `PaperweightFaweAdapter.generateFeature/Structure` wraps work in `TaskManager.taskManager().sync(...)`
  which on Folia would go to the global queue, not the region that owns the target chunk.
- Lighting: `PaperweightStarlightRelighter`, `getLightEngine().queueSectionData(...)`,
  `.getLayerListener(...).getDataLayerData(...)` — all region-owned state.
- `regen/PaperweightRegen.java` — regenerates chunks; inherently region-owned.

### 2.7 `PaperweightPlatformAdapter.addTicket` — routes chunk loads to the GLOBAL region
`worldedit-bukkit/adapters/adapter-26.1/.../vim26_1/PaperweightPlatformAdapter.java:329-335`
```java
private static void addTicket(ServerLevel serverLevel, int chunkX, int chunkZ) {
    // Ensure chunk is definitely loaded before applying a ticket
    io.papermc.paper.util.MCUtil.MAIN_EXECUTOR.execute(() -> serverLevel
            .getChunkSource() ...
```
Verified: on Folia `MCUtil.MAIN_EXECUTOR` is `lambda$static$0` =
`isMainThread() ? run.run() : MinecraftServer.getServer().execute(run)` — i.e. it always hops to the
**global region**, never to the region that owns `(chunkX, chunkZ)`. Any chunk ticket or chunk load issued
through it for a non-global chunk is a cross-region access.

### 2.8 `ChunkHolder` / section locks are FAWE-private, not Folia's
`synchronized (nmsChunk)` and `synchronized (super.sectionLocks[i])` in `PaperweightGetBlocks#set`
serialise *FAWE's* writers. They do NOT satisfy Folia's requirement that only the region-owning thread
touches `LevelChunk`/`LevelChunkSection`. Two FAWE worker threads writing two chunks in the same region
would corrupt region state even though FAWE's own locks are held.

### 2.9 Per-actor queue is a UUID-keyed async pool
`worldedit-core/.../util/task/AsyncNotifyKeyedQueue.java:20` — a **static** `KeyQueuedExecutorService<UUID>`
shared by all players, on a `ForkJoinPool` of `QUEUE.PARALLEL_THREADS`. This serialises per-UUID but runs on
arbitrary pool threads, so `runAction(...)` work lands off-region. On Folia the per-player serialisation must
be bound to the player's region thread (`EntityScheduler`).

### 2.10 `FaweLimit` / `checkAction()` reads a global tick
`Actor.checkAction()` uses `Fawe.instance().getTimer().getTick()` — a single global counter. With per-region
ticks, "the tick I last actioned on" is region-relative.

## 6. Port implemented — status

Working branch `folia/26.1.2`. Target: `dev.folia:folia-api` / `dev.folia:dev-bundle` **26.1.2.build.8-stable**,
Java 25, Folia-only.

### Build
- `worldedit-bukkit` compiles against `dev.folia:folia-api` (superset of paper-api; adds the region schedulers).
- `adapter-26.1` compiles against Folia's dev bundle via `PaperweightUserDependenciesExtension.foliaDevBundle(...)`
  (a first-class paperweight API, defaults to `dev.folia:dev-bundle:<version>`).
- Only the 26.1 adapter is built; the older Paper adapters are excluded in `settings.gradle.kts`.
- `release = 25`, toolchain 25. `plugin.yml` declares `folia-supported: true`.

### Runtime bugs found and fixed by testing on a real 26.1.2 server

These were all found by running the plugin, not by reading code:

1. **`QueueHandler.run()` threw `IllegalStateException: Not main thread`.** Its global drain task now runs on
   Folia's *global* region, where the old `isMainThread()` (captured thread identity) is false. Fixed by
   introducing `Fawe.isGlobalThread()` / `IFawe.isGlobalThread()` and guarding on that.

2. **Region deadlock in `flush()`.** `SingleThreadQueueExtent.submitUnchecked` decided "apply inline" from
   `Fawe.isMainThread()`. On a Folia region thread that is false, so chunk work was submitted to the shared
   blocking pool while the region thread blocked in `Future.get()` — the pool could never legally do the
   write, and Folia's watchdog killed the tick after ~5s. Fixed by testing **region ownership of that specific
   chunk** (`Fawe.isRegionOwner(world, cx, cz)`) and applying inline when we own it.

3. **Cross-region chunk load deadlock.** `PaperweightPlatformAdapter.addTicket` used
   `MCUtil.MAIN_EXECUTOR`, which on Folia is verified to be
   `isMainThread() ? run : MinecraftServer.getServer().execute(run)` — i.e. always the *global* region. Issuing
   a ticket for a chunk owned by another region is a cross-region access, and callers blocking on the load
   deadlock. Fixed: run inline when `TickThread.isTickThreadFor(level, cx, cz)`, else queue to the owning
   region via `RegionizedTaskQueue.queueTickTaskQueue`.

4. **`ensureLoaded` blocked the region thread on its own load.** Fixed to take the synchronous
   `serverLevel.getChunk(...)` path when we are the owning region thread (which is correct and required), and
   to fail fast rather than hang when we are not.

5. **`MinecraftServer.execute` throws `UnsupportedOperationException` on Folia.** `sendChunk` used it to build
   and send chunk packets. Fixed: build and send on the region that owns the chunk, inline when already there,
   else queued to that region. Packet delivery also has to happen region-side because connections are not
   thread-safe.

6. **Folia moved mutable per-world state off `ServerLevel`.** `captureBlockStates` / `captureTreeGeneration` /
   `capturedBlockStates` now live on `RegionizedWorldData`, reached via `ServerLevel.getCurrentWorldData()`;
   `MinecraftServer.currentTick` is gone in favour of `worldData.regionData.getCurrentTick()`. Both fixed.

### Compiler-visible API differences (Folia 26.1.2 vs Paper 26.1)
Only three, all fixed:
- `ServerLevel.captureBlockStates` / `captureTreeGeneration` / `capturedBlockStates` → `getCurrentWorldData()`
- `MinecraftServer.currentTick` → `getCurrentWorldData().regionData.getCurrentTick()`
- `OfflinePlayer.getRespawnLocation(boolean)` added (broke a test stub)

### Verified at runtime (Folia 26.1.2-DEV-HEAD@62dc0f2, Java 25)

Harness plugin schedules work on the region owning the target chunks (console runs on the global region and
cannot touch ordinary chunks, so console-driven testing is not representative).

```
PASS basic:         single=STONE bulk=GOLD_BLOCK
PASS copy-paste:    src=EMERALD_BLOCK dst=EMERALD_BLOCK dstCorner=EMERALD_BLOCK
PASS distant-region: type=REDSTONE_BLOCK ownedByThisThread=true   (chunk 320,320)
```

Zero ERROR lines, zero watchdog tick kills, no cross-region access exceptions.

### Still to verify
- `//undo` / `//redo` through a real player actor (needs a connected client; history is recorded, replay path unproven).
- Brushes, `//schem load/save`, `//regen`, `//paste -a`.
- Concurrent edits in two regions at once under load.
- Graceful degradation of WorldGuard / PlotSquared / Towny / GriefPrevention (none support Folia).

### Known remaining risk
An edit whose region spans multiple Folia regions still needs partitioning by owning region. The current fix
makes single-region edits correct and prevents cross-region deadlocks; multi-region partitioning is the next
substantial piece.

## 7. Historical analysis (pre-port)

- FAWE `gradle/wrapper` = Gradle **9.8.2**; `libs.versions.toml` uses `paperweight = 2.0.0-SNAPSHOT`,
  `paperApi = 1.21.1-R0.1-SNAPSHOT`, adapters `26.1`/`26.2`/`26.3`.
- Folia **does publish a dev bundle**: `dev.folia:dev-bundle:26.1.2.build.8-stable`, a 25 MB zip with
  `config.json` (`minecraftVersion: 26.1.2`, mache `io.papermc:mache:26.1.2+build.1`),
  `data/paperclip-mojang.jar`, and `patches/`. It is a normal paperweight dev-bundle and can be consumed by
  `paperweight.userdev`.
- `folia-api` is published at `dev.folia:folia-api:26.1.2.build.8-stable`.
- Local runnable server exists at `E:\minex\_folia\localserver` (currently Folia 26.3) and a fresh 26.1.2
  bundler jar at `_folia/stable/folia-server/build/libs/`.

## 4. Port strategy (to be refined)

Rather than sprinkling `if (isFolia)` everywhere, introduce a **platform threading abstraction** in core:

- `RegionContext` / `TickThreadContext`: resolve "the region that owns (world, chunkX, chunkZ)" and
  "am I currently executing on the thread that owns it".
- Make `Fawe.isMainThread()` semantics explicit: keep it for truly global work (configuration, startup),
  and add `Fawe.isTickThreadFor(world, chunk)` / `Fawe.isGlobalTickThread()`.
- `TaskManager` gains coordinate-aware overloads: `task(World, x, z, Runnable)`, `later(...)`, `sync(...)`.
  The Bukkit implementation routes to `RegionScheduler` / `EntityScheduler` / `GlobalRegionScheduler`.
- `QueueHandler` sync queues become **per-region** (keyed by world + region section), drained from a
  per-region tick task rather than one global repeating task.
- Per-region tick budget replaces the global `FaweTimer` TPS read (keep a global timer for console/global
  statistics, but never for throttling a region).
- `BukkitQueueHandler` "unsafe" window reimplemented without `AsyncCatcher`/Timings.
- NMS adapter: gate chunk mutation on region ownership; hop to the owning region before mutating; send
  chunk packets via the player's `EntityScheduler`.

## 5. Open questions

1. Should the fork keep Bukkit/Paper support, or become Folia-only? (Affects how much `if` branching.)
2. Folia 26.1.2 ships `folia-api` at Java 25 bytecode; FAWE targets 21. Do we raise FAWE to 25?
3. `worldedit-core` currently has no Bukkit dependency — how much region logic can live there vs the Bukkit
   module?
4. Are we allowed to depend on `paperweight` dev-bundle for Folia, or must we compile against `folia-api`
   only (which lacks NMS internals)? → The dev bundle is required for the NMS adapter.
