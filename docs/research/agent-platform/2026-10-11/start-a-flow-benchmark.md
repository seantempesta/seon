---
title: From a commit to a paused agent Flow
type: research
status: baseline measured; CACHE-1 and CACHE-2 pending
---

# From a commit to a paused agent Flow

The existing path composes Datahike's branch, Seon's world map, the cached SCI
fork, the held task's current-state data, and the production agent graph.
The benchmark measures these owners separately with `seon.profile/measure`.
There is no model call. No production implementation changes precede this baseline.

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
| Branch | Share immutable index roots; roster pointer; one fork entity transaction; open connection | Stored index roots and fork fact, not program size | 30–50 ms; 1–6 MB; one branch operation and transaction | Datahike stores facts and index roots. No store copy. Source revision information must survive cache eviction by read-through derivation. |
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
| Branch | 13.53 / 2.029 / 1,865 / 0 (0) | 13.71 / 0.740 / 7,536 / 0 (0) | Faster than the earlier 30–50 ms estimate; writer allocation is excluded from the thread figure. |
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
