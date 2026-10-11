---
title: "Sol plan: starting an agent's Flow from a point in time"
type: research
status: independent proposal for hybrid design
basis: "default archive c6113d2f3; live JVM probes on 2026-10-11; no implementation changes"
---

# Design

Start from an immutable program commit and a task value, assemble one private SCI fork and
one explicit world, prepare the first current-state data, then attach the ordinary agent
Flow in its native paused state. One cluster Flow proc applies desired task state to this
assembly and issues resume only for running intent. Attempts, custody, fork points and
results are facts; the roster owns live objects; channels merely request another derivation.
The important optimization is preserving the identity of unchanged declaration content
through branch creation, reopen and eviction, so the existing projection, SCI and reach
caches actually share work. Keep the assembly a composition of existing owners, with no
second runtime, scheduler or restoration format. A test run uses this assembly; its members
fork the requesting agent's environment at the captured commit and use the same world
constructor without a graph or an attempt per member.

## What this adds to the existing designs

- Commit acquisition has real work: `datahike.api/commit-as-db` reads a stored commit,
  attaches index roots and can open secondary resources. It is neither a zero-cost operation
  nor a full database copy. Release materialized native resources after their last read.
- Separate **program basis** from **task control basis**. An attempt on main names the exact
  program commit and task it starts. A historical program commit need not contain that new
  attempt. Initial assembly receives those values explicitly; it must not accidentally read
  a task from today's main through `seon.task/main` while claiming a historical snapshot.
  Later turns deliberately acquire current task intent and the agent's own branch head.
- A paused graph must have a prepared context. R1 currently says assembly renders nothing,
  but a paused graph never enters its transform. Prepare context before start, through the
  same `seon.context/current-state` owner used by the turn; do not invent a paused-only render.
  Return the data as readiness evidence. Rendering does not record a synthetic evaluation.
- Fix revision identity at its producer, rather than changing every cache to hash the entire
  program on every read. Whole-program hashing would move the cost without removing it.

## Data, location, keys and cost

Definitions: P = declaration population, D = changed declarations and affected callers,
R = reached proof graph, M = task/references actually shown, G = procs/channels.
MB means decimal thread allocation, **not retained heap**. Armed calls are approximate
under concurrent work. Floors below are engineering hypotheses, not physical guarantees.

| Part / owner | Inputs and home; cache key | Measured now | Necessary work / practical warm floor |
|---|---|---|---|
| Commit / `datahike.api/commit-as-db` | Store + commit UUID; commit and index roots in konserve through Datahike | Sol: 0.72 ms, 1.02 MB, primary indexes only | One commit lookup, root attachment and schema lookup; O(index-root count), not O(P); roughly 0.5–1 ms, 1 MB here |
| Branch / `seon.cluster.registry/fork!` | Exact source value + UUID, target branch, store; pointer and fork fact in Datahike | Sol: 52.50 ms, 5.28 MB, 1,349 calls; Astra: 13.71 ms, 0.74 MB | One native branch publication + fork transaction + connection acquisition; observed feasible range 14–53 ms, not an inherent 45 ms minimum |
| Attempt / `seon.task/attempt-tx` | Task, agent, branch, pinned program basis; main facts | Prior design: combined creation/assignment 32 ms, 0.45 MB | One admission transaction, roughly 30 ms on that store; exclude it when the benchmark starts with an admitted attempt |
| World / `seon.turn.world/world` | Identity, connection/writer, environment, SCI ctx, sink, render profile; live owned map | Sol: 0.11 ms, 0.013 MB, 35 calls, zero reads | O(declared world members), one constructor; 0.01–0.2 ms, under 0.02 MB; no cache for this cheap owned map |
| SCI / `seon.sci.eval/base-ctx` | Loaded JVM program, branch declarations/projection and armed dials; cached program fork sources, owned agent fork | Sol: 30.13 ms, 40.25 MB, 1,218 calls, 17 reads; raw `sci.core/fork`: 0.01 ms, 1,224 bytes | Hit: a few content lookups + independent SCI/kernel cells; 1–2 ms, under 1 MB. Miss: O(D + affected edges), cold base O(P) once per loaded program |
| First context / `seon.context/current-state` | Task and proofs, agent facts, reference declarations, messages, costs; facts in Datahike, derived reach/reference data in cache | Astra fresh warm fork: 58.74 ms, 97.98 MB, 4,414 calls, 138 reads; Sol's one-proof fork: 56.83 ms, 105.36 MB | O(M) lookups + reached digest lookup; 2–5 ms, 1–3 MB for the benchmark task; no O(P) rebuild on an equal-program fork |
| Paused Flow / `seon.cluster.agent/graph-definition` | Explicit execution/world, proc Vars, channels; live graph/executor in roster | Sol: 2.33 ms, 0.106 MB, 237 calls, zero reads; all three procs pinged paused. Astra complete construction: 1.13 ms, 0.626 MB | O(G) channels/init/control and owned proc threads; 1–5 ms, under 1 MB allocation; stack/native memory measured separately |
| Large immutable evidence / `seon.blob/inline-or-stage` | Produced stored value + configured threshold; digest in facts, bytes in konserve | No separate blob timing in this round | Zero blob operations when no large value is produced; otherwise O(value bytes), lazy reads via `seon.blob/read-chunk` |

Prepared-attempt warm assembly floor: **about 20–35 ms and 3–11 MB allocated** on the
faster observed branch path; **about 55–65 ms** at Sol's live branch latency. A new attempt
adds roughly one 30 ms transaction. These are component sums, not measured end-to-end times.
Astra's present warm sum is **103.11 ms / 130.33 MB**, excluding inter-part profiling and
cleanup. Cold SCI in its isolated benchmark costs **1,056 ms / 1,787 MB**: cold admission
must not be disguised as warm acquisition. Retained heap and writer/proc allocation remain
separate obligations; the current evidence cannot establish a retained-memory floor.

Cache inputs:

- Projection and program acquisition: content identities of their declared attributes,
  scoped to the store; loaded program identity and arming dials belong in the SCI key.
  Existing kinds: `:seon.sci.eval/base-context`, `:seon.sci.eval/overlay-context`.
- Proof reach: declaration behavior **and graph facts**, schema forms and requested proof
  names. Preserve `seon.test.runner/reach-revision-key`'s complete input set: retargeting calls
  can change reach without changing source definition digests.
- Contract references: compiled contract semantics, canonical registry and local shadowing.
  A form digest alone cannot identify `seon.schema/contract-references` for arbitrary schemas.
  Prefer reusing compiled objects and Malli's own cached derivations before another cache kind.
- All read-through uses `seon.cache/lookup`. A miss reconstructs from authoritative inputs;
  eviction changes cost, never the answer. Fork sources contain program bindings, never an
  agent's private mutable values, result objects, connection or error recorder.
- No whole-context cache initially: fix the expensive parts first. Add a read-evidence-keyed
  render cache only if the remaining O(M) rendering is a measured recurring cost.

## Library guarantees and code disposition

Source pins read: Datahike `cb3bce5145`, SCI `f455b3b4a6`, core.async `dc35f3e0d7`,
konserve `e433475641`, Malli `dd13ff8113`. The SCI generation isolation is maintained-fork
behavior; Flow's paused start and control lifecycle are native library behavior.

Reuse:

- `datahike.api/branch!`, `datahike.api/commit-as-db`, `datahike.api/release-materialized-db`,
  `datahike.api/merge!`: shared roots, historical values, resource release, serialized merge
  with caller-supplied tx-data. Merge does not resolve entity conflicts for Seon.
- `sci.core/init`, `sci.core/fork`: shared persistent namespace structure and independent
  generations. Existing private object values are not deep-copied by fork.
- `clojure.core.async.flow/create-flow`, `clojure.core.async.flow/start`,
  `clojure.core.async.flow/resume`, `clojure.core.async.flow/pause`,
  `clojure.core.async.flow/ping`, `clojure.core.async.flow/stop`: lifecycle and observation.
  `seon.flow/graph-executor` and `seon.flow/join-graph!` provide actual proc-exit ownership.
- `datahike.core/listen!`: register before the initial desired-state read; sliding-one wakes
  are sufficient because every pass derives authoritative facts again.
- `konserve.core/get`, `konserve.core/multi-assoc`: immutable bytes and ordered publication;
  backend-wide atomic multi-key transactions are not assumed.
- `malli.core/validator`, `malli.core/-instrument`, `malli.core/deref`: contracts and reference
  semantics; do not reproduce Malli's registry traversal rules beside its compiled schemas.

Change in place:

- `seon.db/content-revisions`, `seon.db/content-revision`, `seon.db/recorded-revisions`:
  revision lookup must reconstruct after close/reopen and eviction. Today the missing entry
  derives nil, and a branch's fork-entity commit becomes the conservative revision.
  Investigate persistence/restoration of Datahike's native revision information at
  `datahike.writing/db->stored`, `datahike.writing/stored->db` and
  `datahike.db/advance-cache-context`; do not add a parallel Seon change index.
- `seon.sci.eval/base-ctx`, `seon.sci.eval/copy-base-ctx`,
  `seon.sci.eval/derive-overlay-ctx`: reuse equal-program sources under the corrected identity.
- `seon.cluster.host/host!` becomes the existing proposed `seon.cluster.host/assemble!`;
  `seon.cluster.agent/arm!` and `seon.cluster.agent/arm-graph!` compose into the proposed
  `seon.flow/start-flow!`. Extend `seon.flow/start-graph!` so initial resume follows intent.
- `seon.context/held-task` and `seon.context/current-state` accept an explicit initial task
  basis through a named request; existing turns supply their deliberately current task basis.
- `seon.cluster.agent/armer-step` becomes proposed `seon.flow/db<->flow`;
  `seon.test/bounded-result` uses the shared member assembly and world constructor.

Delete `seon.cluster.host/start!`'s per-branch armer graph and duplicate home transactions;
fold agent creation and attempt admission into one main write. Remove the obsolete reach
revision-retirement machinery `seon.test.runner/retire-replaced-revision!` once entries are
ordinary weighed content values. Delete `seon.schedule/schedule-step` from each agent graph
only when the separately approved cluster clock replaces its behavior. This plan adds no
timer, lock, retry, cache beside Caffeine, or test scheduler beside Flow.

## Phases, proofs and what they unblock

Each phase becomes a bounded card with named schemas/contracts and a red tagged proof.
Split a card if the owner cannot implement and prove it in about fifteen minutes.
Proposed test names below are not claims that tests already exist. Wall bounds belong in
the benchmark's profile evidence; deterministic miss/read/interpretation counts are assertions.

1. **Revision read-through.** Proposed `seon.db-test/a-reopened-fork-reuses-unchanged-content`:
   fork, write its fork fact, close/reopen, evict only owned test-cache entries, then request
   content identities. Equal attributes share keys; one changed graph attribute gets a new
   key; no guessed equality from source digest. Add merge and historical-commit table rows.
   Initial acquisition may read the necessary attribute facts once; repeat reads derive zero
   populations. Unblocks all caches. First benchmark goal: no declaration scan on warm fork.
2. **Reach reuse.** Proposed `seon.test.runner-test/an-equal-program-fork-reuses-proof-reach`:
   identical digests and zero `reached-graph` derivations on the fresh warm fork; graph retarget
   changes the result under unchanged source. Benchmark reach under 5 ms / 3 MB for one proof.
   Unblocks cheap first contexts and test selection. Keep one regression per behavior class.
3. **SCI reuse.** Extend `seon.sci.acquisition-cost-test/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`
   with reopen and fresh sibling rows: zero interpretation and zero declaration-content scan,
   independent bindings/custody, changed armed dials produce the appropriate fork source.
   Benchmark warm acquisition under 5 ms / 2 MB; unblocks cheap member and agent assembly.
4. **Explicit task basis and shared assembly.** Proposed
   `seon.cluster.host-test/a-paused-assembly-has-its-pinned-task-and-context`: main advances
   after the historical commit is selected; assembly shows the handed task, proofs and refs,
   and its world holds the chosen connection and no database value. No provider call or
   synthetic turn. Bound world under 1 ms / 0.03 MB; context under 10 ms / 5 MB on the fixture.
   Unblocks inspectable historical starts and members using the same constructor.
5. **Paused graph attachment.** Proposed
   `seon.cluster.agent-test/a-started-paused-agent-is-ready-before-resume`: ping positively
   observes every production proc paused, context ready; running intent resumes the same
   graph; stop joins actual exit before release. Benchmark graph under 5 ms / 1 MB.
   Unblocks intent-driven starts without an unconditional resume followed by pause.
6. **One desired-state owner.** Extend R1's proposed
   `seon.cluster.agent-test/two-reconciler-passes-launch-one-attempt`: two passes, coalesced
   wakes, and recovery after admission-before-start yield one attempt and one graph;
   replay derives from facts. Table rows cover held/paused/running/stopped/cancelled.
   Launch writes one main admission transaction, one native branch and one fork transaction;
   no branch armer. Target prepared start under 100 ms / 15 MB, new attempt under 150 ms / 20 MB.
   Unblocks root tasks, problem tasks and ordinary task attempts through this path.
7. **Test runs use the path.** Proposed `seon.test-test/a-test-run-forks-the-requesting-agents-environment`:
   private definition is visible to members at the captured commit, member redefinition
   cannot change the requester, custody is distinct, results return as data and are recorded
   before completion. Fifty members create one run attempt, not fifty tasks. Reuse green only
   under current reach/input evidence. Setup per empty warm member under 50 ms / 10 MB;
   keep sequential execution until retained memory and actual exit are proved cheap.

## Evidence, risks and uncertainty

Sol JVM probes: `result/e7951a7a4a2a9` (508 ms evaluation, 784.3 MB request allocation)
and `result/ee3643330e0b7` (76 ms evaluation, 11.1 MB request allocation). Both disposable
branches were retired; the graph executor exited before release. Exact method: one real
`seon.cluster.registry/fork!` with explicit source value; reopen via
`seon.cluster.store/open-branch!`; measure world, `base-ctx` and one proof via
`seon.profile/measure`; production blueprint created/started without resume, pinged, stopped,
executor shut down/awaited, channels closed. All probes used root/default JVM custody;
no source reload, provider call, cache clear, reset or restart occurred.

The one-proof main miss was 338.38 ms / 619.69 MB; fork miss 56.83 ms / 105.36 MB;
repeat 0.80 ms / 0.63 MB. Main keys named `6acb6426…`; fork keys named its fork-fact
commit `6acb690f…`; answers matched. The wider first-context evidence, exact fixture and
cold/warm protocol are in [start-a-flow-benchmark](start-a-flow-benchmark.md).

Measure first: revision identity across eviction/reopen, then counts of declaration scans,
overlay interpretations and reach rebuilds. Separately measure retained heap after repeated
start/stop, native thread stacks, writer allocations and p90 durable-write latency.
Do not increase test parallelism while one main reach miss allocates 620 MB.

Uncertain: whether native attribute revision metadata can survive commit serialization
without introducing self-referential commit identity; inspect `datahike.writing/create-commit-id`
before choosing that representation. If unavailable, derive a content identity once from
native indexed facts on a miss; report its cold O(P) cost rather than calling it constant.
Historical code runs against today's compiled host-bound platform, not the historical JVM.
Multi-predecessor joins need Seon's semantic diff and conflict ownership; they are not required
to prove the single-commit start path. SCI forks isolate Var bindings, not arbitrary mutable
objects reachable through those bindings; tests must declare ownership of such effects.

Default was degraded at entry (cache near 512 MB, overlay warning, existing recorded error).
Two exploratory pulls used nonexistent attributes and returned declared read errors; subsequent
probes used source-verified symbols and shapes. No assignment rows were present on main, so
Sol did not claim an independently measured task-ready start; Astra's fixture supplies it.
Foreign boundary: benchmark script/test/report were another lane's uncommitted files;
they were read as evidence only, never loaded or edited. Full-tree lint: zero errors,
1,513 warnings. Documentation changes no runtime declaration or reaching test.
