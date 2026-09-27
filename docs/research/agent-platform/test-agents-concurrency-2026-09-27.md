---
type: research
status: audit, measurements and priced design, revised after review 57814415b (REVISE, six blockers accepted); prototype proven 13/13; no production source changed
created: 2026-09-27
lane: test-agents-audit-opus
head: 79c10321d (audit citations), 5205cd9b2 (spec rev 3); audit on default pid 53665, revision proofs on pid 84717
scope: how Seon admits or serializes concurrent test work, measured in one JVM, and test agents on Flow through the one REPL path
---
# Test agents: concurrent tests in one JVM

**What exists.** A test request is one call, `seon.test/run` (`src/seon/test.clj:2205`). It builds a clean world
branch, admits its members through the writer, then runs members **one at a time** on the caller's thread. Each member
gets its own branch through `seon.cluster.agent/acquire-context!` and its own virtual body thread
(`bounded-result`, `:218`). Its result is recorded as facts on the `:seon.test.run` entity.

**Data flow.** Request → selection (Datalog over test entities and their recorded evidence) → admission (`admit-run`
as `:db.fn/call` in the recording writer) → per-member acquire, body, record, release → tally.

**The Clojure approach proposed here.** A test request becomes a run agent, and each member becomes a one-body agent
with its own Flow graph. The body is one entry through the one REPL path. Its world is one value, entered on the
body's own thread. Its evidence settles through the batch's supplied settle stage. Two JVM-wide bounds limit members:

- a process roster of running bodies, sized once to the processors, whose nested requests take no slot;
- the kernel's tenured-heap admission, whose resume notice wakes waiting runs.

Lane `test-agents-audit-opus`, 2026-09-27. All measurements were taken on default (pid 53665, HEAD `441988b49`
loaded) through MCP `eval_clj` in JVM mode, on disposable branches. The namespace `tmp.test-agents-audit` was removed
afterwards.

## 1. Answer

- **The owner's premise mostly holds today.** No slot, lane cap or test lock remains: `bin/_test-slot` was deleted on
  2026-09-22 (`8bc917872`, −165 lines). Several requests already run concurrently in one JVM.
  - Six concurrent requests delivered 3.5× the serial throughput.
  - Each branch has its own Datahike writer, and admission reserves members through the writer. This is
    detect-don't-lock and it works.
- **What serializes today is the design:**
  1. Members inside a request run strictly one after another (`src/seon/test.clj:2421`).
  2. Every request pays about 2.8 s of fixed cost before its first body.
  3. Tests mutate JVM-global state, so concurrent worlds contaminate each other. This is the one true blocker.
     - Tests redefine Vars JVM-wide at 303 call sites (404 bindings, 96 files; §8).
     - Tests re-arm instrumentation.
     - The runner restores every live cluster's schema after each member.
- **Recommended: option B (§6), revised after Astra's review.**
  - A request is a run agent, and each member is a one-body agent on the one path.
  - Each body runs on its own virtual thread, and its `finally` is the exit fact.
  - The member's one world value is entered on that thread.
  - A process roster admits at most the processor count of top-level bodies, and nested requests take no slot.
  - Pending members wake on the kernel's resume notice.
  - The recording is the batch's supplied settle stage.
  - Tests that mutate JVM-global state (405 of 2,378, classified over fixtures and helpers) go to the isolated host.
  - Prototype `tmp/test-agents/src/seon/test/agent.clj`; proofs `tmp/test-agents/proof.clj`: 13 of 13 pass (§5).
  - Estimated change: ≈ −181 source lines now (+333 / −514), and ≈ −417 once S17 goes.

## 2. Audit: what admits or serializes concurrent work today

"Real" means the lock guards an external constraint. "Artifact" means our own design choice.

| # | Mechanism (file:line) | What it protects | Real or artifact | Measured cost |
|---|---|---|---|---|
| S1 | Members run sequentially in one request loop (`test.clj:2421`, `run-batch`; batches of `batch-limit` 64, `:2124`) | Nothing concurrent. It is a plain loop on the caller's thread | **Artifact.** The largest serializer | Parallelism equals the number of requests: 1 request ≈ 2.0 cores busy, 6 requests ≈ 7.0 of 18 |
| S2 | Per-request fixed work: `selection-admission`, `clean-request-handle` (`:1817`), `admit-run` | Selection of reusable evidence, a clean world, and the writer's reservation | Real work, but it is repeated for each request | Serial, 13 members: selection 1,178 ms (`selection-facts` 1,015), world 900 (`fork-cluster-ctx` + `base-ctx` 444), admission 701 (`program-written-since?` 688). ≈2.8 s before any body. At N=6, selection averages 2,483 ms |
| S3 | Writer reservation (`test.clj:2379`, `admit-run` inside `:db.fn/call`) | The same member never runs twice at once on one recording branch | **Real, and detect-don't-lock** | Two identical first requests on one branch: the second answered in 2.5 s with 8 `:seon.test/pending`, and the first ran all 8. The second does not wait; its caller must ask again |
| S4 | Checkpoint preparation push-back (`test.clj:1856`, "Another request is preparing this test world") | One preparation per (base, cluster, config, process) | Real (one writer per checkpoint), and detect-don't-lock | Only a cold first race refuses. It was not hit in these runs because the checkpoint was already prepared |
| S5 | Datahike writer: one `LocalWriter` per connection, with processing and commit `go-try` loops (`reference-code/datahike/src/datahike/writer.cljc:112-260`), dispatched on core.async's unbounded `:mixed` pool (`impl/dispatch.clj`, `:core-async-dispatch` → `:mixed`) | Serial transactions **per branch** | **Real** (Datahike's model: one writer per branch) | No cross-branch serialization. Sharing one recording connection between 2 requests: fault 11.2 s vs 11.6 s apart, refusal 5.9 vs 6.1. At N=6, `transact-call` averaged 149 ms against 95 ms serial and `commit-results!` 164 ms against 59 ms. That is contention for CPU and store I/O, not a writer queue |
| S6 | `gc-guard` `:roster` permit (`reference-code/datahike/src/datahike/gc_guard.cljc:99,131`) taken by every `d/branch!` (`versioning.cljc:212-235`) | The read-modify-write of the store's `:branches` key, and GC reachability | **Real** (a lost roster update corrupts the store roster), but it is store-wide exclusive | `registry/branch!` averaged 23 ms serial and 66 ms at N=6 (105 calls). Every member branch passes through it |
| S7 | `locking branch-open-monitor` (`src/seon/cluster/store.clj:554`) in `open-branch!` | Datahike would silently share one writer between two connections to one branch | Real | Not measurable in these runs. It is held for the duration of `d/connect` |
| S8 | `locking root-store-holder` (`src/seon/cluster.clj:956,986`), `locking held-flocks` (`store.clj:323,352`), `locking running-instances` (`cluster/boot.clj:498,566,676`) | One flock per store directory, and one instance per cluster name | Real (OS lock and process roster) | Taken at store open and cluster boot only. Not on the test path |
| S9 | Agent turn-thread holder (`src/seon/cluster/agent.clj:556,569,1399`) | An interrupt can never land after the transform returns | Real | Per agent. Not shared |
| S10 | Roster acquisition `acquire-once!` (`agent.clj:1006`, `swap-vals!` with a delay) | Same-key callers share one acquisition | Real and lock-free | 32 ms per member serial; `acquire-context!` 147 ms at N=6 |
| S11 | `locking write-serialization` (`src/seon/fs/jvm.clj:704`) | Recheck-and-rename of one file within this JVM | Real | File write-back only |
| S12 | Loaded program identity `sci.eval/loaded-identity`, read by `call-detecting-program-change` | An adoption during a body makes that member unknown | **Real, and detect-don't-lock** (nothing waits) | 0 in these runs. It is a JVM-global value, so one adoption makes every in-flight member of every request unknown |
| S13 | Kernel tenured-heap admission `memory-bounds` {enter 0.70, resume 0.55} (`src/seon/sci/kernel.clj:104-175`), checked at request entry (`test.clj:2255`) | JVM heap, shared by clusters | **Real, declared once, JVM-wide.** This is the right shape for a bound | Old gen after GC was 4,601 of 10,488 MB (44%). Eight open `seon.sci.kernel/acquire-arm` faults on default at 21:44 were not examined (§7 F10) |
| S14 | Process root executors (`resources/seon/operator/runtime.clj:17-22`): `:compute` is a fixed pool of `availableProcessors` (18); `:io` is virtual | CPU (compute) | Real bound, declared once, **but nothing uses `:compute`** (`ThreadPoolExecutor` active 0). The config dial `:seon.config.flow.compute/concurrency` is also dead (task in `resources/seon/tasks/seon.config.edn:9`) | — |
| S15 | Per-cluster work launcher (`src/seon/flow.clj:554-620`, io concurrency 64, queue 64 from config) | Effect submissions | Real bound, per cluster | Not on the test path |
| S16 | Dynamic bindings `seon.db/*conn*` (`db.clj:146`), `seon.test/*member*` (`test.clj:122`), `effect/*request-context*` (`effect.clj:43`), schema `*projection*` family (`schema.clj:1041-1045`) | Thread-local custody | Real. They do not serialize because they are thread-local | — |
| S17 | **JVM-global test state:** `ambient-snapshot`/`ambient-drift` (`src/seon/test/runner.clj:641-750`) on every `run-vars!` (`:396-437`); `restore-live-cluster-schema!` (`:543-629`) after every member (`test.clj:2064`) | They detect a test leaking global state after the fact, and restore every live cluster's projection | **Artifact.** They exist because tests mutate the JVM, and the checks assume nothing else runs meanwhile | 6.4 ms per snapshot (2,428 armed Vars). **False reds under concurrency (§3.3).** Each member advances every live cluster's projection, which is a JVM-global write |
| S18 | JVM-global mutation by tests (clj-kondo call sites, §8): `with-redefs` 208, `with-redefs-fn` 47, `intern` 23, `alter-var-root` 17, `load-file` 8, together 404 bindings in 96 files; `:reload` in 7; `preserving-instrumentation-state` in 15; direct `instrument/apply!`/`restore!` in 9; cluster boot in 13 | — | **Artifact, and a law violation** (AGENTS "Test the real system…": never `with-redefs`, `alter-var-root`, `intern`, `load-file`) | This is the one thing that makes concurrent members incorrect rather than merely slow |
| S19 | Isolated members (`test.clj` `isolated-members`, `host-exclusions` `:1927`) → `bin/test --platform`, a fresh JVM (`bin/test:29-72`) | Process, file and store-global subjects: 16 files declare `:seon.test/platform`, 32 declare `:seon.test/fixture-observation`, plus `:seon.fn/destroys` reach | Real (flock, process lifetime, store deletion) | A cold boot per platform run. Not measured here |

The machine pinning that motivated `bin/_test-slot` came from eight worker JVMs. One JVM with several requests does
not reproduce it: six requests used 7 of 18 cores, and GC took 585 ms across 20.7 s.

## 3. Measurements (default's JVM, disposable branches)

**Method.** Each request ran on its own fresh branch. The branch came from `acquire-context!` with
`{:seon.agent/isolate? true}` off default's handle, so no recorded green could be reused, and results were recorded on
that branch. Every branch was released and unlinked afterwards; all 11+ named branches were checked absent from the
roster.

The entry is exactly the documented in-JVM form:
`seon.test/run {:seon.test/execution h :seon.test/recording-connection (:seon.db/connection h) :seon.test/policy :named :seon.test/namespaces #{…}}`.

- CPU is process CPU time over wall time.
- Heap is the peak of the used-heap samples. It includes garbage; the honest heap figure is old gen after GC, 4.6 GB.
- GC is the change in the collectors' cumulative time.

### 3.1 Three different namespaces, serial versus concurrent

| Run | Members executed | Wall | Bodies Σ | Cores busy |
|---|---|---|---|---|
| `seon.fault-test` alone | 8 | 9.9 s | 5.6 s | 1.9 |
| `seon.error.refusal-test` alone | 13 | 6.0 s | 0.65 s | 1.7 |
| `seon.instrument-test` alone | 55 | 21.5 s | 12.0 s | 3.1 |
| **Serial sum** | 76 | **37.4 s** | | |
| **All three concurrent** | 76 | **22.0 s** (1.70×) | | 3.2 |
| └ per request | | fault 11.6 s, refusal 6.1 s, instrument 22.0 s | | |

Nothing broke. Wall time is bounded by the longest request, because members inside a request stay sequential. Peak
used heap was 10.3 GB against a 10.5 GB maximum. That is G1 growing toward its maximum, not the live set.

### 3.2 Scaling identical requests (`fault-test` + `refusal-test`, 21 members each)

| N concurrent | Wall | Serial equivalent | Throughput | Cores busy | GC | Per-request latency |
|---|---|---|---|---|---|---|
| 1 | 12.1 s | 12.1 s | 1.0× | 2.0 | 154 ms | 12.1 s |
| 3 | 15.6 s | 36.3 s | 2.3× | 4.0 | 359 ms | 15.6 s (+29%) |
| 6 | 20.7 s | 72.6 s | **3.5×** | 7.0 | 585 ms | 20.6 s (+70%) |

Profiled at N=6 (six `refusal-test` requests, 10.9 s), per call, concurrent versus serial:

- `transact-call`: 149 ms vs 95 ms.
- `commit-results!`: 164 ms vs 59 ms.
- `registry/branch!`: 66 ms vs 23 ms.
- `acquire-context!`: 147 ms vs 49 ms.
- `selection-admission`: 2,483 ms vs 1,178 ms.
- `seon.db/q`: 13.9 ms vs 10.6 ms (2,347 calls).

The bottleneck is per-request fixed work repeated N times, plus the store-wide roster permit (S6). No single writer
queue appears.

### 3.3 What interferes, and why

1. **Instrumentation is JVM-global.**
   - Concurrent with `seon.instrument-test`, `seon.error.refusal-test/every-producer-references-the-worlds-it-was-handed`
     went red with "Worker-global state changed": 486 Vars appeared wrapped with a foreign contract.
   - In the N=3 run, `seon.error.refusal-test/armed-refusal-returns-every-declared-family-without-refusing` went red:
     `:snapshot-planned :drift-removed #{seon.error/refusal seon.error.refusal/refusal}`. Another request running the
     same namespace had re-armed those two Vars without their plan.
   - The attribution is false, but the underlying hazard is real. While one test re-arms, other requests' bodies call
     Vars whose wrappers are not their program's.
2. **Wall-clock assertions under contention.**
   - At N=6, `seon.fault-test/a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent` asserts
     `(< elapsed-ms 1000.0)` for one synchronous fault write. It measured 1,026 ms and 1,040 ms.
   - That is contention on shared CPU and store I/O, not a defect in the fault writer. A test bound on wall time needs
     the admission bound of §5 to stay honest.
3. **A persisting JVM-global leak.** `seon.instrument-test/a-root-replaced-outside-a-reload-is-named` is red in every
   run, serial included. `seon.instrument-test/doubled` has a root from `NO_SOURCE_PATH`, left by an earlier run.
   `runtime_status` reports it too (`replaced-roots` 1).
4. **The same member requested twice at once** was served correctly by the writer's reservation (S3). The loser
   answers `pending` rather than waiting.
5. **A shared recording connection** (two requests recording on one branch) showed no measurable writer serialization.

### 3.4 Other timings from the same session

- **Recorded-green reuse alone is slow.** `seon.fault-test` on default, 8 members all reused, 0 executed: 1.2 s. It is
  all selection.
- **`seon.test.runner/reach-memberships` over all 2,377 tests took 31.6 s.** That exceeds the ten-second bound
  (finding F2).
- **Transitive reach cannot classify global mutation.**
  - `alter-var-root` is reached by 1,616 of 2,377 tests, and `seon.instrument/apply!` by 1,604, because production
    arming reaches them.
  - `with-redefs` is reached by 203 tests.
  - Classification must use the test's **direct** analysis (its body plus the `test-support` helpers it calls), not
    its closure.

## 4. Test-request paths that exist on only one side (the MCP parity ruling)

Owner ruling via root: requests and deliveries must be identical from a Seon agent and from MCP, and the MCP tool is a
thin wrapper that starts the same test agent. Today the two sides diverge:

| Path | Seon agent (SCI) | MCP | Shell | Divergence |
|---|---|---|---|---|
| Namespace run | `(my.test/run)` → `run-owned` (`src/my/test.clj:37-72`) | only through `eval_clj` SCI, the same form | — | Delivers **only `:seon.test/results`** (drops `passed?`, pending, unfinished and exclusions) |
| Change check | `(my.test/check …)` (`my/test.clj:6-30`) | through `eval_clj` SCI | — | Delivers the whole run result: a different shape from `run` |
| Direct request on the cluster's live connection | — (no agent may do this by default) | `eval_clj` JVM, the skill's documented form | `bin/test-check` (`bin/test-check:115`, a prepl form) | **Only outside agents and the shell.** It records on the cluster's own branch with no agent identity, delivers `seon.test/tally` **text** and an exit code |
| Save gate | — | — | `bin/test-check --gate` → `seon.cluster/refresh-source!` gate → `reaching-run` (`src/seon/cluster.clj:2999`) | **Shell and hook only.** Agents have merge acceptance (`src/my/program.clj:474`) instead |
| Isolated or platform tier | — | — | `bin/test --platform`, `--isolated` | Shell only (starts a JVM) |
| MCP test tool | — | **Does not exist** (tools: `eval_clj`, `runtime_status`, `branch`, `get_value`, `script/seon/dev/mcp.clj:767-798`) | — | Missing entrance |

The design in §5 closes these gaps:

- Every request is `(my.test/…)` on the requesting agent's path, which creates the test agent.
- MCP `test` is `run-form` (`script/seon/dev/mcp.clj:391`) evaluating that same form.
- The shell client is the MCP client.
- Delivery is the test agent's reply, projected once (one-path spec §4, "The MCP reply projection").

## 5. Design: test agents (revised after review 57814415b)

Astra's review (`docs/research/agent-platform/review-test-agents-2026-09-27.md`, commit `57814415b`) returned
**REVISE** with six blockers, and root accepted all six. This section is the revision.

The prototype is the code that lands: `tmp/test-agents/src/seon/test/agent.clj` (384 lines, 333 of them code), a new
namespace, `seon.test.agent`. It runs over the one-path orchestrator, `seon.batch/evaluate-batch` from
`tmp/one-path/src/seon/batch.clj`, spec rev 3.

Every blocker's fix is proved at the REPL by `tmp/test-agents/proof.clj`. It extends Astra's counterexamples and
returns one map of named proofs.

- **Result:** 13 of 13 pass. The latest run was on default (pid 84717), with the raw map in
  `tmp/test-agents/proof-results.edn`.
- **Conditions:** each proof is timed and validated against its proof schema, on disposable branches.
- **Arming:** entrances are called armed, with Malli `-instrument` against the installed carried projection's
  registry. The `armed-refuses` proof shows invalid inputs refused.

### 5.1 Entities and facts, not rows

A test run is the existing `:seon.test.run` entity, with `:seon.test.run/members` as components.

- **Admission** is the reservation `admit-run` already writes.
- **Pending members** are admitted-not-started member facts. The run proc's state is only a derivation of them.
- **Completion** is a member's result facts, written by its settle stage (§5.6), plus its exit.
- **The run's own stop event** (unfinished and pending at its deadline) is one separate transaction owned by the run.

Nothing else is kept. "Open members" and "done" are queries.

### 5.2 Blocker 1: exit is the body's own `finally`, and join follows it

**Design.**

- **Body thread.** `start-body!` runs the member's batch on its own virtual thread. That thread's `finally` sends the
  exit fact, which carries the batch, to the member graph's `:exit` port. Nothing else counts as exit: a timed-out
  wait, a cancelled Future and a joined graph are not exits.
- **Member proc** (`member-step`, one `:io` proc). It never waits on the body. On the exit fact it releases the
  member's branch (`release-context!`), then answers the run.
- **Run agent** (`run-step`, one `:io` proc; ports `:wake`, `:done` and `:deadline`).
  - At the deadline it records unfinished and pending once (`record-stop`) and answers the requester.
  - It keeps ownership of every unfinished member.
  - On each `:done` it stops and joins that member's graph (`seon.flow/join-graph!`), after the exit.
  - A member can therefore never join its own executor.

**Proofs.**

- **`b1-counterexample`** (Astra P10, reproduced): the graph's executor terminates while the compute body is still
  alive: `{:graph-exited? true :body-exited? false}`.
- **`b1-exit-owned`**, with a body held past a 300 ms deadline:
  - the answer names `:unfinished [:held]`;
  - at answer time the member branch is still in the store roster, and the run has not finished;
  - the stop event was recorded exactly once;
  - after the body returns, the order is exit → branch released (about 10 ms) → graph joined (about 0.2 ms), the join
    answers nil, and the branch has left the roster.

### 5.3 Blocker 2 and the owner's world: one world value, entered on the body's thread

**Design.**

- **The world value.** `member-world` is the member's one world value: the spec's per-entrant world, a
  `:seon.db/custody-request` extension. It holds:
  - `:seon.cluster/name` and `:seon.agent/branch`;
  - `:seon.db/connection`, `:seon.db/db` and `:seon.db/writer`;
  - `:seon.sci.eval/ctx`, `:seon.schema/projection` and `:seon.test/member`.
- **One world shape.** There is no second world shape. When the spec's world lands, `member-world` becomes its
  constructor for a test entrant (§5.7).
- **Entering it.** `enter` re-establishes the world on whatever thread runs the body:
  - custody through `seon.db/call-with-custody`;
  - the projection through `schema/call-with-projection`;
  - `seon.test/*member*` and `kernel/*host-world*`.
- **Where it runs.** `enter` is the batch's `:seon.batch/around` stage, so the one path, not the runner, enters the
  world.

**Proofs.**

- **`b2-world-carried`.**
  - On a bare virtual thread, none of connection, member, host world or projection is present.
  - Inside `enter`, all four are identical to the world value's, both on a virtual thread and on the process
    `:compute` executor (Astra's P4 setting).
- **`owner-world-own-branch`.** Inside a test agent's body:
  - the elided `(db/db)` reads the member's own branch;
  - an elided `db/transact!` writes there and is visible there;
  - evaluation in the member's SCI context (`sci/eval-string*`) reads that same branch.
  - The marker is absent from the requester branch and from the cluster.
- **`owner-two-clusters-never-cross`.** Two requesters stand for two clusters' branches.
  - Two test agents run at once, and each waits until both have written.
  - Neither sees the other's datom, from the host or from SCI.
  - Each recording lands only on its own requester (`{:r1 [true false] :r2 [false true]}`).
  - Limit: one store holds both "clusters". A second cluster in the same JVM was not booted.

### 5.4 Blocker 3: waits stay on `:io`; the bound is a process roster; nested members take no slot

**Design.**

- **No fixed pool for bodies.** The fixed `:compute` pool is not used for bodies, because a body can wait on a nested
  request.
- **Where bodies run.** Every body runs on its own virtual thread. The run and member procs are `:io` and only react
  to messages.
- **The CPU bound.** It is an admission count in the one process roster of running bodies (`body-roster`, keyed by
  member).
  - At most `limit` top-level bodies run at once. The prototype takes `limit` as a value; at landing it is the
    process's available processors, the consumer that the dead `:seon.config.flow.compute/concurrency` dial and the
    unused root `:compute` executor lacked (F8).
  - A request made from inside a member body is nested (`seon.test/*member*` is bound) and takes no slot, because its
    parent holds one while it waits.
  - A body's exit frees its slot and wakes every waiting run (`release!` → `wake-all!`, sliding-one wake channels).

**Proofs.**

- **`b3-counterexample`:** a fixed pool of two whose two parents each wait on two queued children. Both parents time
  out.
- **`b3-nested-progress`:** with `limit` 2, two top-level bodies fill both slots. Each synchronously runs a nested
  two-member request. All six bodies complete with nothing unfinished or pending, in about 300 ms.

### 5.5 Blocker 4: classify over the invocation graph; first loads get a positive ready value; keep S17

**Design.**

- **`host-global`.** It classifies over clj-kondo analysis of the test roots. For each test it walks:
  - its body;
  - every local helper and fixture it reaches (test-root Vars only);
  - every namespace-level usage (fixtures, load effects) of each namespace it reaches.

  Production Vars are a boundary: they count only when they are themselves mutators. The mutators are `with-redefs`,
  `with-redefs-fn`, `alter-var-root`, `intern`, `load-file`, `remove-ns`, `ns-unmap`, `instrument/apply!`,
  `restore!`, `remove!`, `preserving-instrumentation-state`, `preserving-schema-registry`, and cluster boot and stop.
- **`ready!`.** It makes first-load arming a positive ready value per key. The key is (loaded program identity,
  namespaces), the value is `arm-first-loads!`, and it is taken once by any number of concurrent callers before any
  body starts. This is the roster-delay pattern of `acquire-once!`.
- **S17 stays for now.** The runner's drift snapshot and restore are deleted only after a concurrent round of the
  runnable set shows zero drift reds.

**Proofs.**

- **`b4-fixture-classification`**, over the loaded archive's `test/`:
  - `seon.profile-test/an-sci-installation-owns-its-own-cell` is host-global through its namespace's
    `preserving-roots` fixture, which reaches `alter-var-root`. The direct-body classifier misses it.
  - Every direct hit is also a graph hit.
  - Counts: 2,378 tests, 405 host-global (direct: 277), **1,973 runnable concurrently**.
  - Cost: the clj-kondo analysis took 2,505 ms, over one second. At landing, classify over the stored analysis facts
    keyed by content in `seon.cache`, not a fresh lint. Classification itself took 121 ms.
- **`b4-first-load-ready`:** eight concurrent callers ran the loader once and all held the same ready value.

### 5.6 Blocker 5: pending members wait as facts; the kernel's resume notice wakes them; pressure is reactive

**Design.**

- **Admission under pressure.** `admit!` refuses while kernel pressure is held. A refused member stays
  admitted-not-started. It is not materialized, so it has no branch, context or graph.
- **The resume notice.** `resume-listener` is subscribed to every collector's completion notifications
  (`watch-resume!`). After a collection, a held pressure that clears (`kernel/memory-pressure` over the kernel's own
  state) wakes every waiting run. That includes a run with zero running members.
- **No second monitor.** It reuses the kernel's pressure state and the JVM's own notifications, and nothing polls.
- **Deadline.** The run's deadline answers pending members if the pressure never clears.
- **Pressure is reactive.** It stops new admissions after a collection crosses 70%. It does not reserve memory.
- **What bounds memory.** Live member worlds are at most `limit` top-level plus their nested members, and each is
  materialized only at admission.

**Proofs.**

- **`b5-pressure-resume`.** A run's only member was refused under a held pressure, with nothing running.
  - With the notice: one ordinary young collection, provoked by short-lived garbage and not a full GC, woke the run.
    The member ran in 286 ms.
  - Without the notice: the run reached its 3 s deadline with the member pending.
- **`b5-member-allocation`.** Materializing one member world (branch, connection, SCI fork) allocates 28.24 MB and
  takes about 31 ms. That is an allocation, not a retained size.
  - Retained size under load is open: task `4e824da5bfb7` puts a branch's index-node cache near 350 MB when it fills.
  - `limit` × that is the figure to measure before claiming any heap guarantee.

### 5.7 Blocker 6: recording is the batch's supplied settle stage

**Design.**

- `member-request` is the one-path spec's test entrant (rev 3, `seon.turn.batch/test-request`, S12):
  - one synthetic entry;
  - the body as the `:seon.batch/evaluate` stage;
  - the world entered by `:seon.batch/around`;
  - the recording **supplied as `:seon.batch/settle`**.
- `evaluate-batch` calls the settle stage exactly once. A settle failure is a diagnostic beside the kept value, which
  is the spec's totality (V2-3).
- The run's stop event is a separate owning event with its own transaction.

**Proof.** In `b6-record-stage`, three members ran:

- The recording was invoked 3 times.
- The two good members each made one transaction on the requester (2 in total).
- The third member's recording threw. Its body value `:boom` is kept, and the diagnostic `"settle: recording refused"`
  is delivered in the answer.

**Interface with the spec** (rev 3, `5205cd9b2`; not edited here):

1. **§2 `:seon.batch/request`.** The test agent supplies:
   - `:seon.batch/text` and a one-entry reader;
   - `:seon.batch/evaluate`, the body;
   - `:seon.batch/around`, entering the world value;
   - `:seon.batch/settle`.
2. **§6 "Test agents: the interface".**
   - Its `:seon.batch/commit!` and `:seon.batch/head` are the requesting agent's branch writer and value. In world
     terms, that is the requester world's `:seon.db/writer` and `:seon.db/db`.
   - The member facts `:seon.test.member/*`, with reach digest and counts, are what that settle stage writes at
     landing. The prototype's proofs supply simpler recordings.
3. **The world value.** The spec's one world per entrant (cluster, branch, connection, db, `:seon.db/writer`, SCI
   fork, agent and task, render profile, proc and settlement inputs, over `:seon.db/custody-request`) is the shape
   `member-world` builds. The test agent adds only `:seon.test/member`.
4. **S12** says "`bounded-result` :218 keeps bound and exit ownership". This revision supersedes that: exit and bound
   move to the member agent (§5.2), and `bounded-result` is deleted.
5. **Delivery parity.**
   - Every entrance calls `seon.test.agent/run-request!`.
   - `my.test` calls it from an agent's evaluation, and MCP SCI is the same agent evaluation (`run-form`).
   - **Not proven here:** `my.test` does not call the prototype yet, so identical delivery through MCP and `my.test`
     is by construction, not measured.

### 5.8 What platform, destructive and host-bound tests still need

- **Host-global tests:** 405 by the §5.5 classifier. They run on the isolated host until converted. The §8 seam census
  names each conversion.
- **Platform, destructive and file-backed members:** unchanged. They run in a separate JVM, which runs the same test
  agents.
- **Adoption during a run:** it makes in-flight members unknown, which is correct detection. Root batches adoptions
  between rounds.
- **Wall-clock assertions** (F6): measured under the admission bound.

### 5.9 Reused, deleted, added (updated estimate)

**Reused:**

- `seon.batch/evaluate-batch` (one-path) and the spec's test entrant;
- `agent/acquire-context!` and `release-context!`;
- `seon.flow/var-process`, `start-graph!` and `join-graph!`;
- `seon.await/await!`;
- `db/call-with-custody` and `schema/call-with-projection`;
- `kernel/memory-pressure`;
- `admit-run` and selection.

**Added:** the prototype, 333 lines of code, which is the landing code.

**Deleted in the same slice:**

| What | Lines |
|---|---|
| `bounded-result` | 121 |
| `member-result` | 119 |
| The sequential batch loop in `run` | ≈150 |
| `attempt-request-context` and effect reach (a member has a real agent) | 44 |
| `bin/test-check`'s own request form | ≈80 |
| **Total** | **≈514** |

The runner-side recording callers go with spec S12, and are counted there.

**Net for this slice: ≈ −181.** S17's 236 lines follow when zero drift reds are shown, for **≈ −417** in total.

The first estimate (−535) assumed S17 went at once and a 215-line addition. The measured prototype is larger, because
it owns exit, world entry, admission, wakes and classification.

## 6. Options (simplest viable first)

**A. Concurrent requests, members still sequential.** Keep `seon.test/run`. Root submits one request per namespace at
once. Route the 405 host-global tests (the invocation-graph classifier, §5.5) to the isolated host.

- *Guarantee:* N clusters' 1,973 runnable tests run concurrently in one JVM without shared-state interference; heap
  admission is the kernel's.
- *Cost:* the classifier (about 40 lines of the prototype). Half a day.
- *Sacrifice:*
  - CPU is bounded only by the number of requests, and each request stays sequential and pays its fixed cost.
  - `bounded-result` survives beside Flow.
  - S17 stays.
  - Measured throughput is 3.5× at N=6.

**B. Test agents on Flow (recommended).** §5, landed after spec S12, with A's classifier as its first slice.

- *Guarantee.* Each of the six review blockers is closed by a passing proof:
  - exit is the body's `finally`, and join follows it;
  - the one world value is entered on the body's thread;
  - top-level bodies are bounded by a process roster sized once to the processors, and nested requests make progress;
  - host-global tests are classified over fixtures and helpers;
  - pending members wake on the kernel's resume notice;
  - recording is the supplied settle stage, invoked once and total.
- *Cost:* ≈ −181 source lines (+333 / −514); S17 (−236) later. About two days after S12, plus owner-side conversions.
- *Sacrifice:*
  - Each member costs a branch and two small graphs: 28 MB allocated and about 31 ms to materialize.
  - Branch creation stays serialized store-wide by the roster permit (S6/F7).
  - The heap bound is reactive, not a reservation.
  - Delivery parity through `my.test` is by construction until `my.test` calls the entrance.

**C. Option B plus shared per-request work.**

- Adds:
  - content-keyed selection, request world and `program-written-since?` in `seon.cache`;
  - the stored-analysis classifier;
  - batched roster publication for member branches.
- *Guarantee:* B's guarantee, with near-zero fixed cost per request and no S6 contention.
- *Cost:* B plus ≈150 lines and a `datahike.versioning` fork change. About two more days.
- *Sacrifice:* it touches a maintained fork, and caches must key by content alone.

B is recommended, with A as its first slice. C waits until B's measured rounds show S2 or S6 dominating.

## 7. Findings, filed as tasks

Filed by this lane (root's follow-up). Each was searched against the existing tasks first. Every `:seon.task/must-read`
symbol was checked against clj-kondo's analysis of `src`.

| # | Finding | Task (file, name) |
|---|---|---|
| F1 | Selection of 8 all-green members with 0 executions takes 1.2 s (`selection-admission` 1,178 ms, `selection-facts` 1,015) | `seon.test.edn` `reuse-only-selection-costs-a-second` (new) |
| F2 | `seon.test.runner/reach-memberships` over all 2,377 tests takes 31.6 s | `seon.test.runner.edn` `reach-over-every-test-takes-thirty-seconds` (new) |
| F3 | `runner.clj:435` prints drift through `pr-str`, unbounded (14,262 characters) | `seon.test.runner.edn` `drift-prints-unbounded` (new) |
| F4 | `ambient-drift` and `restore-live-cluster-schema!` blame concurrent requests (486 Vars; false reds in 2 of 5 concurrent runs) | `seon.test.runner.edn` `drift-is-blamed-on-concurrent-requests` (new; its class is `f985bfc97116` `instrumentation-is-jvm-global`) |
| F5 | `seon.instrument-test/re-evaluating-a-defn-silently-strips-instrumentation` `eval`s `doubled` and never restores it, so `a-root-replaced-outside-a-reload-is-named` is red for the rest of the JVM's life | `seon.instrument.edn` `a-re-evaluation-test-leaves-a-replaced-root` (new) |
| F6 | The fault-write test's `< 1000 ms` wall bound fails under 6× concurrency (1,026 and 1,040 ms) | `seon.fault.edn` `fault-write-bound-is-wall-clock-under-contention` (new) |
| F7 | The store-wide `:roster` permit serializes branch creation (23 ms → 66 ms at N=6) | `seon.cluster.registry.edn` `branch-creation-is-serialized-store-wide` (new) |
| F8 | The process-root `:compute` executor is unused, and so is the compute dial | `seon.config.edn` `compute-concurrency-dial-has-no-executor` (existing; the executor sentence and this evidence were added) |
| F9 | 303 JVM-global redefinition sites in tests | `seon.test.edn` `b9933ca067dc` `tests-redefine-default-vars` (existing; the census numbers and this evidence were added) |
| F10 | 8 open `seon.sci.kernel/acquire-arm` faults: heap-pressure refusals (layer `:seon.sci.kernel/arming`) in the live default (pid 53665, last at 21:44Z) during test rounds. Old gen read 44% after GC later that session | `seon.sci.kernel.edn` `memory-pressure-crossed-during-test-rounds` (new) |

Not filed, per root:

- F11: root corrected the stale memory note ("bin/_test-slot caps at 2"; the launcher was deleted on 2026-09-22 in
  `8bc917872`).
- F12: `:agent-8072af9fb04a` is root's own branch.

Related existing task: `seon.cluster.registry.edn` `4e824da5bfb7`. It reports that each branch connection decodes its
own index nodes (about 350 MB per branch under load). That is the likely per-member heap cost of option B, so the heap
bound of §5.3 must be judged with it.

**Verification limits.**

- Measurements come from one development JVM, which other lanes shared (load average 7), so timings carry that
  contention.
- The runs were not isolated per request from other cluster activity.
- §5's addition count is the prototype's measured size; the deletion counts are line spans of the named functions.
- **The prototype is proved by focused proofs, not yet by a real test request.**
  - Its proofs use synthetic bodies and simpler recordings, not the `:seon.test.member/*` writer.
  - No throughput of option B was measured.
  - Default was restarted by root during the revision (pid 71396 → 84717). The proofs ran in the new JVM.
- **What the proof run leaves loaded.** It loads two new namespaces into default, `seon.batch` (from the one-path
  lane's file) and `seon.test.agent`, plus `tmp.test-agents.proof`. It leaves no branch: the store roster had no
  `agent-*` branch afterwards, the body roster was 0 running / 0 waiting, and the compute executor was 0 active /
  0 queued.

## 8. Seam census: what tests replace JVM-wide, and the seam that removes each

**Method.**

- clj-kondo analysis, not text search: `clj-kondo --lint test --cache false` with `:analysis {:var-usages true}`.
- The call sites are the `clojure.core` var-usages named `with-redefs`, `with-redefs-fn`, `alter-var-root`, `intern`
  and `load-file`, with their enclosing `:from-var`.
- Each binding target is resolved by the var-usage clj-kondo records at that symbol's exact row and column. rewrite-clj
  is used only to locate the binding vector or map at the call's position.
- Scripts: `census.clj` and `report.clj` in the lane scratchpad. The resolved census has 404 entries.

**Totals.**

- **303 call sites:** `with-redefs` 208, `with-redefs-fn` 47, `intern` 23, `alter-var-root` 17, `load-file` 8.
  - A text search counts 263 `with-redefs` occurrences because it includes comments and strings; clj-kondo counts 208
    calls.
- **404 bindings in 96 files, 265 enclosing definitions** (tests and helpers), **188 distinct targets.**
- **26 targets are unresolved:** a Var held in a local, such as `?candidate` or `?gate-var`, or a map built at run
  time.
- **23 targets are the test file's own Vars.**
- **36 targets are third-party:** `clojure.core`, `clojure.java.io`, `datahike`, `org.httpkit`, `babashka`.
- **By production namespace:** `seon.ai` 71, `seon.db` 53, `seon.schema` 17, `seon.turn` 16, `seon.sci.eval` 14,
  `seon.sci.kernel` 13, `seon.render` 11, `seon.schema.edn` 9, `seon.blob` 6, `seon.render.walk` 6,
  `seon.cluster.prompt` 5, `seon.fn.analyzer` 5, `seon.render.web` 5, `seon.config` 5.
- **92 of the 404 bindings are marked "one-path rewrite supplies the seam":** they replace a Var in `turn.clj`,
  `sci/eval.clj`, `sci/reader.cljc` or `cluster/reply.clj`, or a Var whose only caller lives there (`seon.ai/complete`
  ← `seon.turn/call-turn`; `seon.cluster.prompt/prompt` ← turn).
- **Seams S1–S10 below cover 190 of the 404 bindings.** S11–S12 cover the 48 `intern`/`alter-var-root`/`load-file`
  sites on test-owned Vars and scripts. The remainder is a tail of targets with at most three sites each.

### 8.1 Top targets and the smallest seam for each

The precedent is `89add17ce`: `seon.cluster.source/publish!` takes its writer as `:seon.source/transact!`, which
defaults to `seon.db/transact!`. The refusal test then passes a refusing writer instead of redefining the Var.

| Seam | Replaced Var (sites / tests / files) | Production caller | Smallest seam: the dependency as an explicit value | Owning file |
|---|---|---|---|---|
| S1 | `seon.ai/complete` (56 / 50 / 9) | only `seon.turn/call-turn` (`turn.clj:4917`) | The turn reads the provider call from its cluster handle, e.g. `:seon.ai/complete!` defaulting to `seon.ai/complete`. Tests that need only a reply text already have `seon.turn/virtual-turn!` (`turn.clj:2462`) with no seam | `turn.clj`: **one-path rewrite supplies the seam** |
| S2 | `seon.cluster.prompt/prompt` (5 / 5 / 2) | the turn's opening/prompt step | The prompt builder is a stage value of the turn | turn: **one-path rewrite supplies the seam**. This is the ledger's "agent-test prompt redefs blocked on turn.clj seam" |
| S3 | turn and evaluation internals (31 bindings): `seon.sci.eval/evaluate` 10, `seon.turn/next-agent-work` 5, `seon.sci.eval/install-row!` 3, `seon.turn/step` 2, `declared-sources` 2, and one each of `preview-sources`, `generate-turn`, `receipt-settle-tx`, `turn`, `evaluate-sources`, `more-agent-work?`, `opening-db`, `seon.cluster.reply/sources`, `seon.sci.eval/directory-value` | internal | The one-path request's stage values (`:seon.eval/read-next`, `:seon.eval/evaluate`, `:seon.eval/settle?`). A test that makes evaluation throw supplies a throwing `:seon.eval/evaluate` | `sci/eval.clj`, `turn.clj`, `cluster/reply.clj`: **one-path rewrite supplies the seam** |
| S4 | the HTTP leaf and credential: `seon.ai/credential` 6, `seon.ai/send-request` 5, `seon.ai/agent-overlay` 2 | only `seon.ai/complete` | `complete`'s target map carries the leaf, `:seon.ai/send!` (defaulting to `send-request`), and the environment it resolves credential names from, `:seon.ai/environment` (defaulting to the process environment). Credentials stay environment-variable names | `src/seon/ai.clj` |
| S5 | `seon.db/transact!` (14 / 13 / 9) | 73 callers | Each owner under test takes its writer as a value, as `publish!` does. The one declared writer key `:seon.db/writer` exists since `6eadf4b62`, which replaced `:seon.source/transact!` and `:seon.test/recording-writer`; each further owner reads it too | per caller: `fn.clj` (3 tests; **held by vector-shrink-opus**), `render/web.clj` (2), `reconcile.clj` (1), `render.clj` preview (1), `cluster/source.clj` (2; verify whether `89add17ce` already covers them), `turn.clj` (5: **one-path rewrite supplies the seam**) |
| S6 | refused or counted read: `seon.db/q` 10, `pull` 6, `datoms` 4, `pull-many` 2 | 333 / 191 / 15 callers | (a) **Counting** reads: the existing read-evidence sink `seon.db/*read-evidence-sink*` (`db.clj:169`) observes every read. This needs no production change. (b) **Injecting a refusal** into one query: no seam exists. The candidate is a database value whose read genuinely refuses (not verified). Otherwise the propagation is the declared error alternative of the caller's contract, proved by its producer's regression | `src/seon/db.clj` (**held by vector-shrink-opus**; class (a) needs no edit there) |
| S7 | counted invocation or derivation: `seon.sci.kernel/invoke` 10, `seon.schema/projection-from-database` 6, `seon.schema/load-projection` 4, `seon.render/acquire-context!` 6 | render, schema | Observe with the existing per-Var call counters (`seon.profile/begin` + `explain`, `profile.clj:205,230`) or the cache's per-kind statistics (`seon.cache`, `cache.clj:50`). This needs no production change. A test that injects an `acquire-context!` failure needs a real failing world | tests only; `render.clj` for the failure case |
| S8 | resource reader: `seon.schema.edn/read-schema-resource` 5, `clojure.java.io/resource` 5 | `seon.schema.edn/resource-population`; config's shipped document | `resource-population` takes the resource set as a value; config's default document is passed as a value | `src/seon/schema/edn.clj`, `src/seon/config.clj` |
| S9 | `seon.fn.analyzer/analyze` (5 / 4 / 2) | `seon.fn/build-artifact`, `analyzed-artifacts`, `runtime-analysis-batch` | These take the analyzer as `:seon.fn/analyze`, defaulting to the clj-kondo analyzer | `src/seon/fn.clj` (**held by vector-shrink-opus**) |
| S10 | `clojure.core/ns-resolve` (8 / 7 / 5) | late-bound resolution in `effect.clj`, `web/jvm.clj`, `blob.clj`, `render/web.clj`, `fn/analyzer.clj` | The request carries the resolved function (the search transport, the effect handler) instead of resolving a symbol at call time | per caller |
| S11 | `intern` 23 and `alter-var-root` on test-owned Vars (about 12): the instrumentation and runner probes in `instrument_test`, `profile_test`, `test_runner_test`, `test/runner_test`, `test/duration_test`, `test/seed_preparation_test`, `test/one_request_test`, `program_change_test`, `test_failure_facts_test`, `registry_isolation_test`, `mcp_test`, `sci/eval_test`, `agent_test` (`hot-reload-var-test`) | — | Their subject is JVM-global instrumentation itself (`f985bfc97116`). They are **host-global** until wrappers are per context, and run on the isolated host (§5.4). Probes of test-body behaviour define their probe in an SCI scratch namespace instead of interning a host Var | `src/seon/instrument.clj` |
| S12 | `load-file` 8: `bin/seon-hook` ×3, `help_trial_2026_09_09.clj` ×2, the bridge source, `script/seon/dev/docstring.clj`, and an absolute-path namespace | — | Put the hook and bridge code on the classpath as namespaces and `require` them, or run them in their own process as production does. The PRD `help_trial` script is out of place: its tests belong with the PRD or are deleted | `bin/seon-hook`, `script/seon/dev/*` |

### 8.2 Tests that convert once each seam exists

Paths are shown as `test/<path>` without the `_test.clj` suffix. A name is the enclosing definition clj-kondo records;
some are helpers such as `with-cluster`, `exercise-selection!` and `with-default-document`.

- **S1 provider call** (56 sites): `seon/cluster/agent` prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound; `seon/cluster/armed` a-message-committed-during-boot-arming-is-conserved, booting-spends-no-model-call, the-first-cluster-proc-fault-at-resume-becomes-a-fact; `seon/cluster/program_restart` an-agent-definition-survives-restart-and-another-agent-calls-it; `seon/cluster/turn` a-combined-evaluation-projects-every-terminal-receipt-datom, a-lost-model-call-leaves-a-durable-readable-reason, a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact, a-prose-only-provider-reply-settles-without-parking-the-turn-proc, a-real-evaluation-that-runs-away-is-stopped-and-recorded, a-refused-contract-commits-a-receipt-and-no-entity, a-refused-definition-stays-in-its-agents-defs, a-refused-delivery-becomes-a-durable-error-fact, a-run-prompts-from-its-opening-database-value, a-turn-delivers-what-a-form-asks-to-send-and-still-finishes, a-turn-hands-its-clusters-projection-to-every-database-call, a-waiting-disposition-frees-the-agent-and-keeps-its-note, a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end, accepted-first-party-definition-reaches-an-existing-agent-next-turn, agent-code-with-defn-and-println-folds-green-without-in-ns, an-ordinary-turn-advances-no-program-attribute-revision, an-unpaid-failure-with-a-backup-makes-exactly-two-calls, an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence, another-agent-sees-a-flat-contract-violation-after-live-install, contracted-redefinition-exactly-replaces-the-entity, delimiter-repair-is-span-local-and-precedes-intent, evaluation-follows-the-readers-parse-time-namespace, function-install-reads-the-case-count-from-cluster-facts, generated-opening-preserves-one-provider-turn-budget, generated-turn-agrees-with-durable-facts?, import-addition-is-ordinary-data-and-reacquires-exactly, import-only-ns-unmap-installs-exactly-after-its-context-commit, incompatible-clusters-alternate-runtime-schema-validation-without-bleed, mixed-plan-publishes-only-the-contracted-function, ns-unmap-retracts-the-owned-function-after-the-terminal-commit, reasoning-starvation-persists-usage-finish-and-the-named-error, refused-terminal-program-transactions-settle-and-do-not-refire, reply-reading-follows-evaluated-alias-and-dynamic-require-state, runtime-schema-key-changes-pass-the-one-usage-guarded-decision, runtime-schema-registration-commits-the-evaluated-form-and-attribute, runtime-schema-unregister-removes-one-unused-global-schema, runtime-tests-install-run-redefine-and-delete-exactly, streaming-writes-zero-datoms-test, successful-call-persists-the-providers-open-usage-document, turn-intent-is-the-complete-crash-falsifier; `seon/concurrency_independence` n-agents-fold-independently-on-one-live-cluster; `seon/gen/loop` completion-prose-does-not-erase-failed-evaluations; `seon/sci/eval_instrumentation` an-instrumented-dev-cluster-builds-an-attempt-ready-prompt; `seon/turn_continue` prove-session; `seon/turn_loop` prompt-and-call-resolve-once-record-settings-and-see-next-turn-config.
- **S2 prompt build** (5 sites): `seon/cluster/agent` disarm-has-a-declared-loud-turn-completion-backstop, disarm-interrupts-an-in-flight-turn-and-records-it-interrupted, prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound, release-joins-every-owned-graph-before-it-closes-the-branch; `seon/turn_loop` refused-generated-opening-captures-evidence-before-close.
- **S3 turn and evaluation stages** (31 sites): `seon/cluster/acquisition` acquisition-owns-only-its-isolated-resources; `seon/cluster/agent` a-failed-turn-transform-releases-its-permit-and-retires-its-backstop, hot-reload-var-test; `seon/cluster/armed` a-message-committed-during-boot-arming-is-conserved, an-escaped-throwable-becomes-a-fact-and-a-message, booting-spends-no-model-call; `seon/cluster/bootstrap_resume_child` -main; `seon/cluster/evaluate_sources` ordered-evaluation-retains-one-explicit-basis-without-publication; `seon/cluster/turn` a-combined-evaluation-projects-every-terminal-receipt-datom, a-refused-delivery-becomes-a-durable-error-fact, delimiter-repair-is-span-local-and-precedes-intent, generated-membership-failure-never-advances-the-run-to-call, import-only-ns-unmap-installs-exactly-after-its-context-commit, refused-terminal-program-transactions-settle-and-do-not-refire, turn-intent-is-the-complete-crash-falsifier, with-cluster; `seon/error_recording` root-first-turn-records-its-producers-declaration; `seon/help` help-is-first-stored-data-and-tracks-its-code-version; `seon/render/web_debug` passive-ledger-never-reconstructs-directory; `seon/render_simplification` authored-source-invocation-reuses-one-stored-run-across-presentations; `seon/render_source` preview-cache-is-memory-only-and-invalidates-on-read-changes, source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute; `seon/turn_loop` install-gate-failure-settles-the-started-receipt-as-a-failure; `seon/turn` a-history-read-refusal-cannot-suppress-declaration-divergence, a-refused-turn-write-is-bounded-and-commits-exactly-one-fault.
- **S4 HTTP leaf and credential** (13 sites): `seon/ai` a-missing-credential-is-loud-in-the-value, a-no-auth-config-entity-assembles-and-sends-without-authorization, a-provider-that-hangs-up-mid-body-settles-what-it-already-sent, an-extra-body-conflict-refuses-before-the-http-leaf, explicit-credential-selection-survives-provider-resolution, explicit-no-auth-omits-the-authorization-header, one-attempt-means-one-attempt, one-client-serves-concurrent-streams, payment-required-primary-is-terminal-with-a-configured-backup, the-deadline-fires-as-an-outcome-not-a-bug-report, transport-failure-is-an-ordinary-value; `seon/turn_loop` prompt-and-call-resolve-once-record-settings-and-see-next-turn-config; `seon/turn_work_cost` generated-state-agrees-with-the-writer.
- **S5 owner writer** (14 sites): `seon/cluster/evaluate_sources` ordered-evaluation-retains-one-explicit-basis-without-publication; `seon/cluster/publication_adoption` freshly-forked-host-already-has-its-published-source; `seon/cluster/source_nochange` unchanged-digest-keeps-the-published-commit-without-a-transaction; `seon/cluster/turn` a-refused-definition-stays-in-its-agents-defs, delimiter-repair-is-span-local-and-precedes-intent, refused-terminal-program-transactions-settle-and-do-not-refire, turn-intent-is-the-complete-crash-falsifier; `seon/fn/publication` incremental-indexing-reconciles-only-selected-file-entities; `seon/fn` progress-observation-cannot-change-index-transaction-shapes, refused-index-reads-never-become-publication-entities; `seon/reconcile` a-flat-write-refusal-is-the-reconcile-result; `seon/render/web` start-refuses-a-flat-process-write-before-binding, transaction-refusals-map-to-http-without-success; `seon/render_source` preview-cache-is-memory-only-and-invalidates-on-read-changes.
- **S6 refused or counted read** (22 sites): `my/plan` plan-reads-and-ownership-refuse-an-unreadable-database; `seon/cluster` boot-recovery-refuses-an-unreadable-open-turn-query; `seon/fn` a-refused-reference-read-refuses-gate-set-derivation, gate-set-walks-only-indexed-callers-once, refused-index-reads-never-become-publication-entities; `seon/reconcile` planning-pulls-only-the-provenance-managed-slice; `seon/render/root_pull` root-selector-is-concrete-and-declared-reverse-evidence-bearing, supplied-root-acquisition-is-the-only-membership-read, unrelated-entity-change-keeps-root-read-current; `seon/render/web` start-refuses-a-flat-process-write-before-binding; `seon/test/selection` exercise-selection!; `seon/test_provenance` verified-requires-the-subject-positive-assertions-and-exact-program; `seon/test` recording-distinguishes-run-replay-from-a-new-event; `seon/turn` an-episode-budget-read-refusal-reaches-next-work-verbatim.
- **S7 counted invocation or derivation** (26 sites): `seon/cluster/prompt` a-replay-under-a-selecting-budget-reconstructs-its-own-capture, provider-calibration-reports-over-budget-without-refusing, the-prompt-budget-selects-and-still-reports-its-verdict; `seon/cluster/publication_host` live-init-keeps-the-host-process-and-launches-no-jvm; `seon/cluster/turn` a-turn-hands-its-clusters-projection-to-every-database-call; `seon/config` converged-apply-uses-carried-projection-and-remains-exact; `seon/core_functions` auto-check-checks-the-function-instead-of-refusing-its-own-arguments; `seon/db/declaration_population` reads-require-their-carried-projection-including-transaction-results; `seon/db` counting-derivations; `seon/render/retained` adoption-of-an-unrelated-namespace-re-renders-zero-evaluations, equal-committed-database-skips-read-replay; `seon/render/walk` one-basis-projection-covers-the-complete-walk; `seon/render/web_context` a-new-message-does-not-reinvoke-the-identity-pair, context-and-page-do-not-demand-the-render-proc, read-only-mcp-return-preserves-root-page, selected-session-defers-prompt-acquisition, unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot; `seon/render/web` session-acquisition-failure-replaces-the-loading-panel, unrelated-transaction-reuses-debug-observation-and-render-call; `seon/render_simplification` shared-invocation-cache-reuses-identical-entity-inputs-across-call-ids, unchanged-retained-call-skips-discovery-and-invocation; `seon/schema/projection_writer` a-speculative-value-reuses-its-basis-projection-unless-it-writes-a-declaration, committed-reads-derive-once-per-declaration-population, speculative-values-derive-without-committed-identity; `seon/turn_loop` read-evidence-settlement-reuses-the-outer-transaction-codec.
- **S8 resource reader** (10 sites): `seon/config` packaged-defaults-use-the-same-shipped-document-authority, with-default-document; `seon/db/declaration_population` reads-require-their-carried-projection-including-transaction-results; `seon/incremental_publication` non-program-inputs-have-their-own-publication-owner; `seon/render/walk` one-basis-projection-covers-the-complete-walk; `seon/schema/declaration_population` an-unhanded-declaration-projection-refuses-without-reading-resources, resource-reads; `seon/schema/edn` one-resource-is-one-population, with-temporary-resources; `seon/sci/admit/declaration_population` reads-of.
- **S9 analyzer** (5 sites): `seon/cluster/publication_inputs` publication-inputs-are-file-facts-but-not-analysis-inputs; `seon/fn` defining-forms-share-one-form-local-kondo-batch, my-web-cascade-class-warns-without-vetoing-publication, publication-veto-is-exactly-the-cant-load-finding-classes.
- **S10 late-bound resolution** (8 sites): `seon/blob` large-binary-write-and-small-chunk-have-bounded-allocation; `seon/effect` interrupted-handlers-mark-the-open-receipt-without-a-result; `seon/fn/analyzer` canonical-analysis-rejects-obsolete-cache-authorities; `seon/render/web` runtime-evaluation-cannot-be-displaced-by-a-database-wake, runtime-evaluation-drops-a-closed-debug-pages-package; `seon/web/jvm` public-search-settles-one-receipt-with-provider-credits, search-projects-the-live-serper-shape-and-blobs-the-raw-response.
- **S11 and S12:** the test names listed with each row of §8.1.

**Order.**

1. S6(a) and S7 need no production change, so those tests can convert now.
2. S4, S8 and S10 are small owner slices, each named by its file.
3. S5 is unblocked: the one writer key `:seon.db/writer` landed in `6eadf4b62`.
4. S9 and S6(b) wait for vector-shrink-opus to release `fn.clj` and `db.clj`.
5. S1–S3 come from the one-path rewrite: 92 bindings, including 45 of turn_test's 56 `seon.ai/complete` sites.
6. Until then those tests are host-global under §5.4 and run on the isolated host, never concurrently in the
   development JVM.
