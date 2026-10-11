---
title: From a commit to a paused agent Flow
type: research
status: baseline and fork-revision fix measured; further implementation awaits hybrid
---

# From a commit to a paused agent Flow

The existing path composes Datahike's branch, Seon's world map, the cached SCI
fork, the held task's current-state data, and the production agent graph.
The benchmark measures these owners separately with `seon.profile/measure`.
There is no model call. The baseline precedes the fork-revision fix below; further
flow implementation and CACHE-1/CACHE-2 changes await the design hybrid.

## Measurement protocol

`start-flow-benchmark/sample!` takes an execution handle, a commit id and an agent
already assigned a task in that commit. The fixture creates and assigns a task
through the ordinary owners, with a real red proof and two must-reads:
`seon.turn.world/world` and `seon.test.runner/reach-digests`. Preparation is outside
the measured start path. Each sample creates a real committed fork and retires it.

Run `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` only in the
disposable `bin/seon check` JVM. Its cold sample first empties `seon.cache`;
the warm sample starts a second fresh branch from the same commit. Default's
cache is never emptied. The script's `sample!` alone is also usable for live warm
measurements, with explicit root/default custody supplied by the caller.

Flow's `clojure.core.async.flow/start` starts paused; Seon's `start-graph!` resumes
unconditionally. The measurement therefore uses `seon.cluster.agent/graph-definition`
and the existing `seon.flow/graph-executor`, then calls Flow's create/start directly.
It observes all three procs with ping and stops and joins the owned executor before
releasing the branch. Paused state must be positively observed, not inferred from
the absence of a provider call. Graph timing includes settings, channels and the
verification ping; proc-thread allocations are separately visible in the JVM window.

Reported allocation is allocation, not retained heap. The thread counter excludes
work performed by the writer and graph threads. The JVM window includes those
threads and unrelated work. `:seon.profile/reads` counts armed database read entrances;
the separate datoms count is `seon.db/datoms` calls, not the number of index nodes or
datoms returned. No measurement here claims to count physical index page reads.

## Floor and cache plan

The numeric floors below are hypotheses to test, not measurements or guarantees.
They exclude JVM boot, fixture preparation and class loading.

| Part | Necessary work and size | Proportional to | Hypothesized warm floor | Owner and reuse |
|---|---|---|---|---|
| Branch | Share immutable index roots; roster pointer; one fork entity transaction; open connection | Stored index roots and fork fact, not program size | 1–10 ms; 0.1–1 MB on the caller; one branch operation and transaction | Datahike stores facts and index roots. No store copy. Source revision information must survive cache eviction by read-through derivation. |
| World | Small map of identity, connection, writer, environment, sink | World's declared members | 0.01–0.1 ms; below 0.02 MB; a few calls, no index reads | `seon.turn.world/world`; the map is owned, not cached. Its immutable configuration/projection inputs can be shared. |
| SCI | Fork a cached program context; attach independent mutable cells and custody | New fork state, plus changed declarations on a miss | 1–2 ms; below 1 MB | `:seon.sci.eval/base-context` and `:seon.sci.eval/overlay-context`, read-through; keys must denote loaded/program content and arming decisions. Weigh retained program structures, not only the shallow context map. |
| First context | Task declaration, proofs, must-read references, agent namespace, unread messages, branch cost | Task and shown references, not all declarations | 2–5 ms; 1–3 MB | Reach graph/index keyed by program content and requested proofs; contract references keyed by contract content plus semantic registry scope. Facts remain in Datahike; large immutable shown values belong in konserve blobs. |
| Paused Flow | Three procs, channels, own executor, start control messages | Proc/channel count | 1–5 ms; below 1 MB allocation, plus three platform-thread stacks | Flow owns lifecycle; live graphs are owned roster entries, not cache values. Blueprint data is cheap construction. |

Every derived entry uses `seon.cache/lookup`: a miss reads its owner and fills the
entry; eviction changes cost, never correctness. CACHE-1 must retain graph edges in
the identity: call-target changes can occur without a source definition digest
change. CACHE-2 must preserve Malli's local-registry shadowing and cross-registry
semantics; a form digest alone does not identify that result for arbitrary compiled
schemas.

## Measurements

Baseline candidate `6ce55cb0a`, based on HEAD `c6113d2f3`, through the isolated
check runner, run `0a02bec059c9`. MB below are decimal thread-allocated MB.
The test ran both samples and asserted task proofs, both references and all three
paused proc states. Four preliminary graph bounds were red because that measurement
was expanded to include handle construction and readiness; the final limits below
cover that complete operation. Fixture creation/assignment and the runner are
outside the five parts (whole run 7,560 ms).

| Part | Cold ms / MB / calls / reads (datoms) | Fresh warm fork ms / MB / calls / reads (datoms) | Time/allocation gap to proposed floor |
|---|---|---|---|
| Branch | 13.53 / 2.029 / 1,865 / 0 (0) | 13.71 / 0.740 / 7,536 / 0 (0) | Near the revised estimate; writer allocation is excluded from the thread figure. |
| World | 0.16 / 0.015 / 43 / 0 (0) | 0.19 / 0.015 / 43 / 0 (0) | Small map, already near the practical floor. |
| SCI | 1,056.23 / 1,787.485 / 81,241 / 29 (8) | 29.37 / 30.966 / 1,323 / 18 (4) | Warm >10×: one declaration-content scan and one overlay derivation on a program-equal fork. |
| First context | 121.42 / 142.787 / 4,521 / 138 (99) | 58.74 / 97.979 / 4,414 / 138 (99) | Warm >10×: one reached-graph rebuild; reference rendering also reads declaration datoms. |
| Paused Flow, including readiness | 2.05 / 0.643 / 357 / 0 (0) | 1.13 / 0.626 / 357 / 0 (0) | Already near the proposed floor; three owned platform threads remain live until stop/join. |

The sum of warm measured parts is **103.11 ms / 130.326 MB**. This excludes
profile counter collection between parts and resource cleanup, and is not an
end-to-end stopwatch measurement. Armed calls and the JVM allocation window can
include concurrent maintenance; branch calls varied from 1,865 to 7,536, so those
counts cannot be attributed exclusively to the caller.

Initial non-long regression limits (ms / allocated MB / calls), rounded above the
observed range: branch 25 / 4 / 10,000; world 1 / 0.03 / 50; cold SCI 1,300 / 2,100 /
100,000, warm SCI 45 / 45 / 2,000; cold context 160 / 180 / 6,000, warm context
80 / 120 / 6,000; graph 5 / 0.8 / 450. These are baseline limits, not target costs.

The algorithmic call floor is one world constructor; one cached SCI acquisition
plus a fork; one branch creation plus its transaction; task/agent lookups and one
lookup per shown proof/reference; and graph definition/create/start plus readiness.
Exact armed-call minima depend on the installed contracts and are not theoretical
constants. The measured world and graph give practical minima; the remaining
owners must remove program-size work before their call floor can be measured.

## Findings and limits

- Initial default observation: commit `c6113d2f3`, PID 8540; degraded by existing
  base-context and overlay-context cache discipline warnings, no open problems.
- A one-proof reach miss on default measured 108.74 ms, 203,093,536 allocated
  bytes, 1,128 armed calls and 11 database reads. This is a reach probe, not the
  full start benchmark. The owning graph load needs the real-fork comparison.
- The edit hook could not acquire the new script namespace in the existing
  default archive. The candidate check publishes and loads the script and test
  together; the hook's failure is not evidence that the benchmark ran.
- Full-tree clj-kondo baseline: 0 errors, 1,513 warnings, 6,789 ms.
- Confirmed CACHE-1 on live default, root JVM evaluation `result/ed82007fa3dda`:
  one real `seon.cluster.registry/fork!` with the source value supplied, then reopen
  the branch, answers identical proof digests but costs **84.27 ms / 126.259 MB /
  11 reads**. Every fork reach-attribute key names the fork-entity commit, while
  main's keys name its earlier program revisions. `fork!` closes its temporary
  connection after the fork-entity write; reopen loses that connection's revision
  ancestry. Remembering only the source commit is insufficient. The revision owner
  must provide content-valid read-through after close/reopen and eviction.


## Fork-revision fix and after measurements

`seon.cluster.registry/fork!` already remembers its source. Its own fork-fact
transaction then produces a new commit; closing the temporary connection without
remembering that value loses the native attribute revisions on reopen. The fix
calls `seon.db/remember-revisions!` with the transaction's `:db-after` before
returning. It changes neither the graph keys nor the SCI cache. The existing
`:seon.db/commit-revisions` kind holds just the native revision map, keyed by the
immutable commit and weighed at insertion as `64 * (4 + attribute-count)` bytes.
No database value or program copy is retained by this addition.

Datahike pin `cb3bce514507ecc96d36b6957ad10f6e892f51cf` supplies the native
cache context and attribute revisions. This preserves evidence already present in
the successful write; it adds no writer stamp or separate cache. Eviction of the
revision chain still falls back to conservative identity and can refill a duplicate
program derivation. Durable content identity/read-through after such eviction is
left to the hybrid; this fix does not claim to solve it.

The new `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`
warms ten real proof digests, creates a real fork, closes/reopens it and measures
the same request. Red candidate `71b9fb53`, run `701efe16775e`: identical answer,
**one graph rebuild / 210.577 MB**, failing both the no-rebuild and 5 MB bounds.
Green candidate `1254b98ecd518541cfefa374743d5772c7b504ba`, run `c90c93d90ab0`:
**zero graph rebuilds / below 5 MB**, all four selected Vars passed, 64 assertions.
The full benchmark in that same candidate produced these after rows:

| Part | Cold ms / MB / calls / reads (datoms) | Fresh warm fork ms / MB / calls / reads (datoms) | Warm before → after, ms / MB |
|---|---|---|---|
| Branch | 11.52 / 1.974 / 1,868 / 0 (0) | 10.75 / 0.746 / 6,863 / 0 (0) | 13.71 / 0.740 → 10.75 / 0.746 |
| World | 0.18 / 0.013 / 33 / 0 (0) | 0.09 / 0.013 / 33 / 0 (0) | 0.19 / 0.015 → 0.09 / 0.013 |
| SCI environment | 950.32 / 1,796.441 / 81,231 / 29 (8) | 29.45 / 29.406 / 1,323 / 18 (4) | 29.37 / 30.966 → 29.45 / 29.406 |
| First context | 91.34 / 118.158 / 4,707 / 138 (99) | 14.65 / 10.161 / 3,991 / 127 (88) | 58.74 / 97.979 → 14.65 / 10.161 |
| Paused Flow, including readiness | 2.35 / 0.642 / 357 / 1 (0) | 1.28 / 0.625 / 357 / 1 (0) | 1.13 / 0.626 → 1.28 / 0.625 |

Warm measured parts total **56.22 ms / 40.951 MB**, down from
**103.11 ms / 130.326 MB**. The first-context allocation fell 89.6%; its warm
regression bounds are tightened to **30 ms / 20 MB / 5,000 calls**, with a zero
reach-graph-rebuild assertion. Cold bounds remain unchanged. Small timing changes
in the unaffected world and Flow parts are sample variation, not attributed gains.

Reach misses **do not remain on the fresh warm fork** in this sample: both graph
construction and reach-component calls disappear. A cold first context still builds
the graph once, as expected after eviction. SCI still calls
`seon.db/declaration-content-key` and `seon.sci.eval/derive-overlay-ctx` once on the
warm fresh fork. Thus SCI's duplicate derivation remains and is still >10× the
proposed time/allocation floor. First context remains above its 2–5 ms target;
its 24 vector-query calls and 88 datoms entrances are the next measured work.

`seon.schema/contract-references` is still uncached. The cold SCI acquisition calls
it **3,604 times**, accounting for **262 ms self time**; the warm fresh fork calls
it **zero times** in this fixture. This does not prove repeated changed-contract
registration is cheap. CACHE-2 is deferred, not retired, and the existing schema
registration task is unchanged.

### Verification boundary and remaining evidence

- Full-tree pre-commit lint: `clj-kondo --lint src script test --cache false`,
  0 errors, 1,513 warnings, 6,516 ms.
- Baseline script, test and table landed in `a03902ef4`. The source change is only
  the successful fork-write path in `seon.cluster.registry/fork!`.
- Selected green Vars: `seon.test.runner-test/a-reopened-fork-reuses-its-programs-proof-reach`,
  `seon.test.runner-test/a-program-change-reads-only-the-reach-attributes-it-wrote`,
  `seon.test.runner-test/reach-digests-and-memberships-share-content-valid-work`,
  and `seon.start-flow-benchmark-test/commit-to-paused-flow-costs`.
- The candidate check took 36,741 ms overall: 8,103 ms ready, 1,018 ms adoption,
  9,458 ms tests including fixtures. These are platform-check costs outside the
  five measured parts, not an ordinary agent-start claim.
- Default remained on `c6113d2f3`; post-fix numbers come from the isolated candidate
  JVM, not an uncommitted reload into default. The live root/default pre-fix probe
  is recorded above. Default's lane edit hook cannot acquire `start-flow-benchmark`
  from that older archive; one acquisition-refusal fault is recorded for this lane
  (signature `3b59158eb8b31e6e578b5bd04c66db14ee74f418c7c7200f1a3dd3e90195a120`).
  The registry edit hook timed out; its result was unknown. Neither is counted as
  green evidence. Root's adoption/restart is needed for installed post-fix proof.
- Foreign boundary: other lanes' documents, `reference-code/http-kit`, and existing
  unclaimed working-tree paths were excluded from the HEAD-plus-owned-paths candidate.
  Broad tiers belong to root. The two additional registry lifecycle Vars below
  fail before reaching the changed owner, in their unchanged `with-source-store`
  fixture: its initial `seon.test-support/transacted!` has no carried projection.
  Candidate `327ccb567deeb4fddd0509af7487ee7bbb70e365`, run `a0b9cc7a158a`,
  passed all 65 assertions in the four slice Vars and reported these two errors:
  `seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection`
  and `seon.cluster.registry-test/a-reused-branch-name-never-takes-the-retired-forks-merged-base`.
  Unchanged HEAD `7bfcdc75c6880ec9b378393277ba26346f6dc220`, run
  `ec87f0ad2fda`, reproduces both errors (0 assertions, 2 errors, 4,778 ms),
  proving they predate this fix. Their fixture is outside this lane's ownership; the final slice gate names the
  four passing Vars individually.
- Cold means empty `seon.cache` in an already booted JVM; library caches and loaded
  classes remain. The fixture's captured commit equals its parent connection's
  current value. Historical commit acquisition, branch connection reopen time,
  retained heap, physical index reads, and test-member p50/p90 are not measured.
  No lower bound for those is claimed. Every sampled branch and Flow is released.
