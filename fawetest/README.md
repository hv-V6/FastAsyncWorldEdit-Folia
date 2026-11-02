# FAWE Folia verification harness

A throwaway test plugin used to verify the Folia port against a real server. **Not** part of the
shipped plugin and not intended for production.

## Why it exists

Folia makes ordinary testing impossible in two ways:

1. **The console can't edit.** Folia's console runs on the *global* region, which owns no ordinary
   chunks. `setblock` and similar fail with "That position is not loaded", and a command that touches
   another region's data throws.
2. **Edits must start on the owning region.** World data may only be touched by the thread owning the
   region that holds it, so a test must marshal itself onto that thread via `RegionScheduler`.

So the harness schedules each scenario on the region owning its target chunks — or deliberately
*off*-tick where that is the behaviour under test (multi-region edits need off-tick to load foreign
chunks).

## What it checks

| Scenario | Asserts |
|---|---|
| `basic` | single-block write and bulk region fill |
| `copy-paste` | clipboard copy and paste land blocks |
| `distant-region` | editing a far region works and is owned by the calling thread |
| `multi-region` | one edit spanning ~24×24 chunks across regions |
| `concurrent multi-region` | two overlapping multi-region edits at once (regression for the GET-cache deadlock) |
| `tick-foreign` | tick-thread edit whose volume needs unloaded chunks |
| `tick-foreign-applied` | the blocks from that edit actually landed |
| `regen` | `//regen` returns successfully |
| `regen-applied` | the region's bedrock marker is gone, i.e. terrain was regenerated |

The last two deliberately check **blocks written**, not just return values. An earlier version only
checked a boolean and passed while `NullPointerException`s were spamming the log every tick.

## Building

Compile against the Folia API, Folia server and the built plugin JAR, then jar it up:

```bash
javac -cp "<folia-api.jar>;<folia-server.jar>;<FastAsyncWorldEdit-Folia.jar>" \
      -d out src/foliatest/FoliaFaweTest.java
cp src/plugin.yml out/
jar cf FoliaFaweTest.jar -C out .
```

Drop `FoliaFaweTest.jar` and the FAWE Folia JAR into a Folia 26.1.2 server's `plugins/` directory and
start it. Results are printed to the log as a `FOLIA FAWE TEST REPORT` block roughly 20 seconds after
startup.

## Reading the output

```
PASS basic: single=STONE bulk=GOLD_BLOCK
PASS regen-applied: 5/5 sampled blocks replaced by generated terrain
```

Also watch for these, which indicate a regression even when scenarios pass:

- `FoliaWatchdogThread ... has not responded` — a region stalled; something is blocking a tick thread.
- `NullPointerException` in `pollTask` — the regen task pump lost its region context.
- `World mismatch` — a Folia level was asked for another world's region data.

A clean run has **zero** of all three.
