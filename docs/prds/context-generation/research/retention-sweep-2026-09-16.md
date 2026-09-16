---
type: research
status: implemented
date: 2026-09-16
tags: [blob, storage, bounded-execution, exclusive-sweep]
---

# Retention sweep: root inventory and deletion authority

Owner selected option 1; implementation `5a10f5dfa` is complete. The final
landing below supersedes the initial decision request and its pending status.

## Initial investigation, before the owner decision

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

At the investigation checkpoint this transaction was provided, **not executed**. No stop, refork, restart, root
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

## Final landing — option 1, 2026-09-16

Implementation commit **`5a10f5dfa`** removes automatic byte-budget retention.
The owner explicitly chose the existing reachability collector and accepted
loss of oldest-first byte-budget enforcement. No root catalog, new GC path,
compatibility shim, dependency modification, or replacement timer was added.

Deleted `src/seon/blob/retention.clj`, its test namespace, its schema resource,
and `resources/seon/schemas/seon.config.blob.edn`. Removed the dial/default,
minute schedule seed, and unused handler-request key. Kept `seon.blob.edn`
and `src/seon/blob.clj` unchanged. The remaining weekly compact task retains
`seon.operator/collect!` → `registry/collect!` → `datahike.api/gc-storage`,
with schema-derived historical/current blob references extending the mark.

The requested recursive reference scan over `src test resources config
 docs/seon/architecture AGENTS.md .agents .claude script bin` returned **zero
matches** for `blob\.retention|blob/max-bytes|blob-retention`.
The implementation diff is **31 insertions / 177 deletions**, seven files.

### Default retirement and live before/after

Executed the exact retirement transaction in the earlier section through
`seon.db/transact!` and `(seon.operator/connection "default")`. The transaction
succeeded; a following attempt to turn a Datom into a vector threw in the
probe's reporting code. A separate pull verified entity 43303 retained its
identity, owner, and function ref but **no task-to-schedule link**. Root's
active schedule query returned exactly the five remaining tasks, including
weekly compact. This preserves prior firing evidence instead of retracting
history. The schedule entity can remain inert without an attached task.

The [executable removal proof](retention_sweep_removal_proof_2026_09_16.clj)
records the transaction, bounded sample, and in-process test forms.

| Live default PID 53378 | Before | After |
|---|---:|---:|
| Observation duration | 60,422.284833 ms | 90,360.562125 ms |
| Admitted samples | 4 / 119 | 178 / 178 |
| Gate open | **3.3613445%** | **100%** |
| Sweep-in-progress samples | 115 | 0 |
| Retention firing count across after interval | — | **51 → 51** |
| Scheduler calls to `konserve.core/keys` across after interval | — | **0** |

Sampling began after a nonqueuing permit probe confirmed the earlier sweep
had released. It used `try-reachability-permit!` in blob mode and immediately
released every admitted permit. A temporary transparent wrapper counted
`k/keys` invocations with a `seon.schedule$` JVM stack frame; it delegated all
calls unchanged and restored the original Var root in `finally`. This is
finite live observation, not a promise that weekly native GC never holds the
gate. The old 70.308-second retention walk no longer has an execution path;
there is no replacement `reclaim!` to time.

`config/default.edn` no longer provides the dial. Clusters newly forked or
reforked from the resulting publication have neither a retention seed nor a
live byte-budget declaration/default. Other existing sovereign clusters were
not changed or reforked by this lane; they require the same retirement
transaction or the orchestrator's refork. Default was updated in place and
never stopped, restarted, or reforked.

### REPL and test evidence

Before editing source, evaluated the portfolio without the removed task and
`execution-context` without the removed key in the live JVM. A representative
`execution-context` call returned the ordinary operator/config request without
the byte dial; the portfolio contained five tasks. Evaluated the new class
regression through `seon.test`'s loader and ran it before editing its file.
The first attempted test form used the fixture as a macro; corrected it to
its real `(with-database (fn [connection] ...))` signature before any test run.

Every recorded test below used the canonical entry point, from a future so
an MCP request timeout could not interrupt fixture-base initialization:

```clojure
(seon.test/run (#'seon.test/resolve-test 'namespace/test-name)
               (seon.operator/connection "default"))
```

| Test / stage | Pass | Fail | Error | Stored run entity |
|---|---:|---:|---:|---:|
| `seon.schedule-test/root-maintenance-seed-is-complete-and-has-no-minute-task`, before file edit | 6 | 0 | 0 | 52273 |
| Same test, exact edited source form re-evaluated through the test loader | 6 | 0 | 0 | 53851 |
| Same test, full namespace reload after adoption | 6 | 0 | 0 | 53854 |
| `seon.schedule-test/one-nominal-fire-calls-the-handler-once-without-a-turn`, after adoption | 12 | 0 | 0 | 53855 |
| `seon.cluster.registry-test/blob-lifetime-follows-schema-derived-history-reachability` | 2 | 0 | 0 | 53849 |
| `seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references` | 1 | 0 | 0 | 53850 |

The class regression asserts nonempty declared tasks, exact equality between
seeded database rows and declarations (including function, cron and zone),
absence of an every-minute task, continued native collection, and idempotent
seeding. It uses `test-support/with-database`, the canonical populated fixture.
The existing registry tests use real file stores: historical references keep
bytes alive until their last referencing branch is retired; current references
keep blobs alive when history is disabled. Neither regression was rewritten.

The final full namespace reload was
`(#'seon.test/with-test-loader #(require 'seon.schedule-test :reload))`;
registry tests were likewise reloaded through the same loader. The final four
unique tests passed **21 assertions / 0 failures / 0 errors**. No test JVM,
`bin/test`, or `bin/test-fast` was launched. The hook's automatic selector
reported zero selected tests; that was not counted as proof.

### Adoption, checks, and exact boundary

An initial whole-namespace reload encountered the old config contract still
requiring the removed key. During that window, the changed test and the two
changed schedule forms were read from their exact files and re-evaluated
through the appropriate existing namespaces/loader. That was a hot-Var proof,
not a claim of converged adoption.

The first queued hook reported operator exit 124. Explicit
`bin/seon init --dev default --changed src/seon/schedule.clj` waited behind an
existing publication's lifecycle lock (last observed wait 102,541 ms), then
exited **0**, completing schema, program, loaded-definition, SCI, and JVM
instrumentation adoption. No foreign process/session/file was operated.
Published head and default's adopted source both became
**`6aaa25da-35e2-5a96-892d-cf6554f5ce68`**. The default database then reported
no schema form for the removed dial, no `:seon.fn/source` for the removed
reclaimer, five active root tasks, and no schedule link on the retired task.
The post-adoption test namespace reload completed and the tests passed.
**No RESET NEEDED.**

`clj-kondo` over schedule, its test, and the removal proof: **0 errors,
0 warnings**, 51 ms. `git diff --check` passed. The repository-wide Markdown
hook still reports the previously recorded unrelated citation errors; this
lane did not edit their owners. The orchestrator's batched gate is pending;
`tmp/orchestrator/gate-requests/retention-sweep.txt` contains the four existing
namespaces `seon.schedule-test`, `seon.blob-test`,
`seon.cluster.registry-test`, `seon.dev.fresh-operator-test`, plus `platform`.
The deleted retention test namespace is not requested.

Extra paths beyond the initial exclusive list: `config/default.edn` and the
config schema deletion were explicitly authorized by the option-1 decision;
the removal proof preserves executable evidence; the issue moved into
`docs/seon/issues/archive/` under the issue lifecycle rule. Protected paths
were left untouched. The implementation commit and this evidence are separate
path-limited commits. All lane futures completed; temporary probe Vars were
removed and the key-enumeration wrapper restored. The operator command exited;
no scratch cluster root, worktree, or extra JVM was created. Fixture stores
were released and cleaned by their tests.

### Final file bytes (2026-09-16)

| File | Bytes after removal |
|---|---:|
| `config/default.edn` | 33183 |
| `src/seon/schedule.clj` | 33930 |
| `test/seon/schedule_test.clj` | 19475 |
| `src/seon/blob/retention.clj` | deleted |
| `test/seon/blob/retention_test.clj` | deleted |
| `resources/seon/schemas/seon.blob.retention.edn` | deleted |
| `resources/seon/schemas/seon.config.blob.edn` | deleted |
| `docs/prds/context-generation/research/retention_sweep_removal_proof_2026_09_16.clj` | 3201 |
| `docs/seon/issues/archive/blob-retention-sweep-starves-every-roster-writer.md` | 6714 |

## Batch 30 follow-up — 2026-09-16

The orchestrator reports cold `seon.schedule-test`, `seon.blob-test`, and
`seon.cluster.registry-test` green, and platform green with results recorded.
The named gate actually snapshotted `d2cf09d1a`, according to
`tmp/orchestrator/gate-results/batch-30/named.md` (launch HEAD was
`56f0a4ca8`); both include retention removal `5a10f5dfa`. Retained root:
`tmp/test-runs/run.bI3xVy`. The operator lifecycle test returned false at
lines 1145 and 1160. Its second assertion compares adoption commit IDs,
not config maps.

**Verified independent cause; stop boundary reached.** Both exact assertion
forms read raw `@connection` through `seon.db/pull`. On default PID 53378,
MCP JVM session `retention-sweep`, this returns a flat
`:seon.schema/missing-projection` error, not an entity. Selecting expected
keys from that error gives **0 keys versus 77 expected**. Reading a source
commit ID from the same error gives nil. Both raw-pull forms and the same
projection refusal exist in `5a10f5dfa^`; the retention commit changed none
of those owners. No production code or assertion was changed.

Holding the `[:*]` selector unchanged and using `(seon.db/db connection)`
returns the stored entity. Its exact desired-versus-stored diff is:

```clojure
{:seon.config/applied-manifest-digest
 {:expected "d86c39ca18732e5e5c0299366dbeabfcb9e41102a0f138668b07c0c1d777380e"
  :actual   "6a15ad6347a09296721cc97477ebcb47017509776cd3f25324e12863fb1b6d6a"}}
```

All **76 other desired keys match**. The removed dial is absent from the
compiler's desired row. Recomputing the digest of current effective config
with only `:seon.config.blob/max-bytes 536870912` restored produces exactly
`6a15ad6347a09296721cc97477ebcb47017509776cd3f25324e12863fb1b6d6a`.
Thus default's recorded digest is from its pre-removal config application;
this lane did not config-apply or otherwise mutate its config. That expected
live historical difference is not evidence that fresh `config apply` loses
an attribute. The compiler and `apply-compiled!` still construct and reconcile
the entire desired row (`src/seon/config.clj:398`, `:454`).

For completeness, default's correctly read adopted commit was
`6aaa2969-773b-581f-97c0-4c773523c0a7` while the published head was
`6aaa2c37-2e5e-5207-a259-4160de394da8`. Concurrent publication means the
corrected adoption comparison also differed at that observation. This is a
boundary on inference: the retained cold root was not reopened, so its
underlying config/commit states beyond the reproduced raw-pull failure are
not claimed. The stop instruction takes precedence over rerunning the
110-second test or repairing a foreign owner.

Filed [the separate operator proof issue](../../../seon/issues/fresh-operator-config-proof-pulls-an-unprojected-database.md).
The [read-only probe](retention_batch30_probe_2026_09_16.clj) preserves both
map reads and the digest reconstruction. No test JVM or in-process test
was launched in this follow-up. The gate request retains the existing
namespaces and records the batch-30 status and separate red. All protected
files and unrelated issue-schema edits were preserved.
