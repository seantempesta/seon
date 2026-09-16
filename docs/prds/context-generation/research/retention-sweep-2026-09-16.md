---
type: research
status: decision-required
date: 2026-09-16
tags: [blob, storage, bounded-execution, exclusive-sweep]
---

# Retention sweep: root inventory and deletion authority

The defect is confirmed on default PID 53378. No production change has been
made. The proposed move of reference derivation outside the sweep permit is
unsafe without validation at deletion; a cluster-local inventory also cannot
represent the physical root after its writing branch is retired. This is the
AGENTS.md §2.5 owner design gate, not a foreign-edit or test failure.

## Reads and dependency ledger

Read end to end: the assigned issue, the recording-latency section of
`gate-recording-refusals-2026-09-16.md`, the supplied AGENTS.md instructions,
`src/seon/blob/retention.clj`, `src/seon/blob.clj`, `src/seon/schedule.clj`,
the three assigned schema files, Datahike `gc_guard.cljc`, and the four
`tmp/orchestrator/wave2/` rule files. Read the requested versioning slice,
Konserve key-listing implementations, Datahike GC, the issues README,
the active plan README, and the original September 8 retention landing.
Applied data-oriented-clojure, datahike, and repl skills. Git archaeology:
`e9e15a585` introduced retention, including the physical-byte semantics and
current-only reference policy; it is not an unimplemented proposal.

| Question | Answer and source |
|---|---|
| Does Datahike already collect unreachable keys? | Yes. `reference-code/datahike/src/datahike/gc.cljc:83` owns reachability GC; `:125` acquires the sweep permit, `:144` reads the roster, `:152` accepts an external reachable extension, and `:167` invokes Konserve sweep. The public API in this checkout is `datahike.api/gc-storage`, used at `src/seon/cluster/registry.clj:540`, not `datahike.api/gc-storage!`. |
| Does Seon already preserve external blobs through that GC? | Yes. `src/seon/cluster/registry.clj:519` delegates collection and `:547` adds `referenced-blobs` with history enabled. It is a whole-store/history collector, not oldest-first byte-budget retention. |
| Can Konserve list only binary keys cheaply? | No such facility exists in the inspected FileStore path. `reference-code/konserve/src/konserve/core.cljc:690` delegates to `-keys`; `protocols.cljc:54` exposes only that operation. FileStore `filestore.clj:287` lists backing files; `impl/defaults.cljc:372` walks them and `:397` reads metadata before type is available. Passing a type filter does not introduce an index. |
| Would switching to native GC remove the walk? | No. `reference-code/konserve/src/konserve/gc.cljc:22` calls `k/keys`; Datahike holds the exclusive permit around mark and sweep. Native GC removes duplication but does not provide the requested low-latency byte policy. |
| Where do writes become reachable? | `src/seon/blob.clj:164` publishes staged bytes; `:271` holds the blob permit across publication and the caller's root commit. `reference-code/datahike/src/datahike/versioning.cljc:220` holds the roster permit before source/roster reads through publication. |
| What protects reference derivation? | `src/seon/cluster/registry.clj:373` explicitly requires the collector's exclusive permit across roster/reference derivation and deletion. `gc_guard.cljc:237` only admits publishers before/after that exclusion. |

## Live evidence

All probes used MCP JVM mode, session `retention-sweep`, explicit
`(seon.operator/connection "default")`. `bin/seon status` and MCP runtime
status both identified the same live PID 53378. No test JVM was launched.

| Observation | Result |
|---|---:|
| Installed attributes whose namespace is `seon.blob` | 0 |
| Configured root byte budget | 536,870,912 bytes |
| Existing retention task | entity 43303; schedule ref 43302 |
| Existing inventory, called directly outside a permit | 70,308.003625 ms |
| Binary blobs inventoried | 3,625 |
| Physical blob bytes inventoried | 66,549,759 bytes |
| Current reference derivation across five branches | 172.702375 ms |
| Referenced digests | 114 |
| Reachability sample elapsed | 60,422.284833 ms |
| Reachability sample verdicts | 115 sweep-in-progress; 4 admitted |

The 119 samples show the gate closed 96.64% of observations. Each admitted
permit was released immediately. Both probe futures completed; neither held
a sweep permit. Exact executed forms are retained in
[the probe script](retention_sweep_probe_2026_09_16.clj).

The five branches were `:db`, `:cluster-beta`, `:current-src`,
`:cluster-default`, and `:test-results`. The inventory/reference measurement
is observational: these were not captured atomically and were never used to
authorize deletion. The background scheduled collector remained active.
The initial probe had a reader/compiler error from unqualified `ProcessHandle`;
the corrected `java.lang.ProcessHandle` form returned normally in 5 ms.

This measurement times the existing pieces, not a new full `reclaim!` call:
starting another exclusive sweep would add another 70-second writer stall.
The prior assignment's full-store count (380,285) and gate occupancy (134/139
closed) are historical evidence, not remeasured counts in this lane.

## Why the proposed shape needs an owner decision

1. An inventory written into the supplied cluster connection is branch-local.
   `registry/retire-branch!` (`src/seon/cluster/registry.clj:291`) removes that
   branch while its bytes survive until GC. Unioning facts from live branches
   therefore loses exactly the orphan bytes retention must discover. The
   existing immortal main connection is carried in the store value
   (`src/seon/cluster/store.clj:380`); blob callers currently supply only their
   cluster connection. A root catalog needs explicit custody carried to those
   callers, not a process-global lookup at write time.
2. A digest unreferenced at candidate derivation can become referenced before
   the later sweep permit is acquired. A blob publisher can reuse that same
   digest, or a roster publisher can expose an older commit referring to it.
   Merely putting `dissoc` inside the permit does not validate the stale
   decision. Existing guard semantics permit both interleavings. Candidate
   selection may occur outside the permit, but deletion must validate the
   observed roots at its authority (or recompute references under exclusion).
3. `:seon.blob/size` is payload size (`blob.clj:141`), while the current budget
   counts physical bytes including metadata (`retention.clj:24`). Reusing that
   size as the physical total silently changes the dial's established meaning.
   New physical-size/write-instant facts must have explicit semantics.
4. The already-written blobs have no inventory facts. An empty new catalog is
   unknown coverage, not zero bytes. Initial inventory needs one explicit
   bounded maintenance/rebuild operation, or an owner-authorized root reset.
   Native GC deletions must also update or invalidate catalog coverage.
5. The guard has no bounded sweep-acquisition/cancellation API. Its queued
   request cannot simply be abandoned on timeout: it could later be granted
   without a releaser (`gc_guard.cljc:244`). A count-limited delete batch also
   does not bound a blocking filesystem call. The promised hold bound needs
   a real dependency/IO contract, not another tuned timer.

## Three concrete options

1. **Remove automatic byte retention; use existing native GC (recommended
   smallest constraint).** Remove the minute seed and retire its live schedule
   link; delete the redundant byte-retention path after updating its callers
   and tests. Existing explicit/weekly root collection remains. Guarantee:
   no minute retention sweep can starve roster writes. Cost: approximately
   1–2 engineering hours including in-process verification. Give up automatic
   oldest-first 512 MiB enforcement; native GC still has its own whole-store
   maintenance pause and preserves historical blob references.
2. **Preserve the full requested byte policy with root-owned facts and guarded
   validation.** Carry main-store custody to blob publication; record physical
   size, digest, and write instant there; integrate catalog lifecycle with
   native GC; derive candidates outside exclusion and validate roster/head
   evidence at deletion. Add bounded/cancellable acquisition and deletion
   semantics at the dependency seam. Trigger on catalog writes, coalescing via
   the existing Flow machinery. Guarantee: root-wide inventory survives branch
   retirement and no stale candidate deletes a newly referenced blob. Cost:
   approximately 1–2 engineering days across blob, store/environment,
   registry, and dependency owners. Give up the current bounded-lane scope;
   initial coverage requires explicit inventory or the orchestrator's reset.
3. **Make current byte retention explicit maintenance only.** Remove/retire
   the minute schedule, retain the existing callable algorithm and its lock
   correctness for deliberate maintenance. Guarantee: ordinary operation has
   no recurring retention walk; requested manual calls retain the existing
   oldest-first physical-byte result. Cost: approximately 30–60 minutes plus
   verification. Give up automatic enforcement and the zero-enumeration/
   bounded-hold target; manual retention still pauses writers.

Estimates are planning judgments, not measured implementation times. No option
has been silently selected or installed. The exact stopping rule is AGENTS.md
§2.5: “when a decision would create hours of cross-owner work or its guarantees
cannot be stated simply, STOP before production edits and bring the owner
exactly three concrete options”. The new root custody and deletion validation
cross that boundary; foreign edits are not the cause of this stop.

## Existing schedule retirement

For options 1 or 3, remove the seed entry and execute this ordinary transaction
on default; it preserves task identity and firing history. `task-rows`
(`src/seon/schedule.clj:210`) requires the schedule link, and schedule's
listener observes its retraction (`:710` relevant attributes). This does not
interrupt a sweep already running; allow that invocation to settle first.

```clojure
(seon.db/transact!
 (seon.operator/connection "default")
 [[:db/retract
   [:seon.schedule.task/id "root/maintenance/blob-retention"]
   :seon.schedule.task/schedule
   [:seon.schedule/id "root/maintenance/blob-retention-schedule"]]])
```

This transaction is provided, **not executed**. No stop, refork, restart, root
reset, or task mutation was performed by this lane.

## Verification boundary and follow-up regression

No production definitions were changed, so no new candidate-query timing,
after-change `reclaim!` timing, or passing class regression is claimed.
No in-process `seon.test/run` was invoked. The gate request names the five
existing requested namespaces; it is pending implementation/owner decision,
not evidence of a green gate.

Option 2's class regression must use the canonical fixture and real shared
store: N non-blob keys, a complete under-budget root catalog, zero key
enumeration and zero sweep acquisition; above budget, oldest-unreferenced
first. Include branch retirement without inventory loss, candidate selection
followed by publication of the same digest, physical-versus-payload bytes,
and explicit failure on missing initial coverage. These distinguish correct
root inventory from a fast query over incomplete or stale facts.

Unrelated working-tree edits were preserved. No foreign session was operated.
Only this landing note, the assigned issue, the committed probe script, and
the requested disposable gate-request file are lane-owned output.

The Markdown hook reported 29 repository-wide citation errors, including
stale gitlink citations in `agents-md-audit-2026-09-15.md`; none of the shown
errors named this lane's note. This is a recorded verification boundary,
not a reason for stopping the implementation design.

## Output bytes and checks

Probe script clj-kondo: 0 errors, 0 warnings (18 ms). All five requested
test namespace files exist. Both futures completed and their private probe
Vars were removed from `user`. No shell, JVM, or scratch root was created.
The probe script is the only extra committed path beyond the assigned paths;
it preserves executable evidence as required by AGENTS.md §4.

| Output | Bytes |
|---|---:|
| `docs/prds/context-generation/research/retention-sweep-2026-09-16.md` | 12640 |
| `docs/seon/issues/blob-retention-sweep-starves-every-roster-writer.md` | 5553 |
| `docs/prds/context-generation/research/retention_sweep_probe_2026_09_16.clj` | 2550 |
| `tmp/orchestrator/gate-requests/retention-sweep.txt` | 197 |
