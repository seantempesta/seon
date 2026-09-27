---
type: research
status: audit, measurements and priced design; no production source changed
created: 2026-09-27
lane: test-agents-audit-opus
head: 79c10321d (file:line citations); default JVM pid 53665 loaded 441988b49
scope: how Seon admits or serializes concurrent test work, measured in one JVM, and test agents on Flow through the one REPL path
---
# Test agents: concurrent tests in one JVM

**What exists.** A test request is one call, `seon.test/run` (`src/seon/test.clj:2205`). It builds a clean world
branch, admits its members through the writer, then runs members **one at a time** on the caller's thread. Each member
gets its own branch through `seon.cluster.agent/acquire-context!` and its own virtual body thread
(`bounded-result`, `:218`). Its result is recorded as facts on the `:seon.test.run` entity.

**Data flow.** Request → selection (Datalog over test entities and their recorded evidence) → admission (`admit-run`
as `:db.fn/call` in the recording writer) → per-member acquire, body, record, release → tally.

**The Clojure approach proposed here.** A test request becomes an ordinary agent, and each member becomes a one-body
agent with its own Flow graph. The body is one entry through the one REPL path, and its evidence settles as facts on
the run entity. Members of every request run at once. Two JVM-wide bounds, each declared once, limit them: the
process root's existing `:compute` executor for CPU, and the kernel's existing tenured-heap admission for memory.

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
     - Tests use `with-redefs` 263 times in 85 test files.
     - Tests re-arm instrumentation.
     - The runner restores every live cluster's schema after each member.
- **Recommended: option B (§6).**
  - A request is an agent and each member is a one-body agent on Flow.
  - Body execution is bounded by the process root's `:compute` executor, sized once to the available processors and
    unused today.
  - Admission is bounded by the kernel's tenured-heap pressure.
  - Tests that mutate JVM-global state are derived from their direct analysis and routed to the isolated host until
    they are converted.
  - Estimated change: about −750/+215 source lines.

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
| S18 | JVM-global mutation by tests: `with-redefs` 263 uses in 85 files, `with-redefs-fn` 48 in 32, `alter-var-root` 26 in 13, `intern` 29 in 14, `load-file` 14 in 11, `:reload` in 7; `preserving-instrumentation-state` in 15; direct `instrument/apply!`/`restore!` in 9; cluster boot in 13 | — | **Artifact, and a law violation** (AGENTS "Test the real system…": never `with-redefs`, `alter-var-root`, `intern`, `load-file`) | This is the one thing that makes concurrent members incorrect rather than merely slow |
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

## 5. Design: test agents

### 5.1 Entities and facts, not rows

A test run is the existing `:seon.test.run` entity (`resources/seon/schemas/seon.test.edn`, `:seon.test.run/id`
`:343`), with `:seon.test.run/members` as components. Its **test agent** is an ordinary `:seon.agent` entity, and the
run points to it:

- `:seon.agent/id` `"test/<run-id>"`, minted by the run's owner.
- `:seon.agent/branch`: the request world branch that `clean-request-handle` already creates.
- The run entity takes one new optional ref, `:seon.test.run/agent`.

Each member is also an agent: `"test/<run-id>/<ordinal>"`, on the member branch that `member-result` already acquires.
It carries one ref to its member entity. Admission and retirement use the existing writer-checked facts:

- Admission is the reservation that `admit-run` writes today.
- Retirement is `:seon.agent/admission-closed-tx`, the fact `seon.cluster.host/retire!` writes
  (`src/seon/cluster/host.clj:155-180`).
- Completion is the member's result facts (`commit-results!`, `runner.clj:1813`) plus its exit fact (`record-exit!`,
  `:1867`).

No row, list or counter is kept beside them. "Open members of a run" is a query:

- a member with an admission and no exit fact is open;
- a request is done when it has no open members.

### 5.2 Execution: reuse the agent and Flow machinery

**Run agent.** It is created through the same host path as an MCP branch agent: `seon.cluster.host/host!`
(`host.clj:124`) → `acquire-context!` (`agent.clj`) → `start!` (`host.clj:70`) → `flow/start-graph!`
(`flow.clj:101`). Its graph derives work from listened facts:

- A wake says only "look" (sliding buffer 1, as in `graph-definition`, `agent.clj:578`).
- The turn pass reads admitted members that have no member agent yet, and hosts one per member up to admission.

**Member agent.** It is one graph with one `:io` proc step. It acquires its branch as `member-result` does today and
runs `apply-seeds!` (`test.clj:132`). Then it submits **one entry through the one REPL path**:

- `seon.sci.eval/evaluate-batch` with the §4 "Test body" stage values (§5.5).
- The thunk (`runner/run-var!` or `sci.eval/run-test`) is submitted to the process root's **`:compute` executor**. The
  proc parks on its future. Its virtual thread holds nothing.

**Settle.** The member agent settles its result (`commit-results!`) and its exit (`record-exit!`) on the recording
connection, then retires: admission closed, `join-graph!` (`flow.clj:138`) awaits actual executor termination, and
`release-context!` (`agent.clj:959`) unlinks the branch. The run agent wakes on the member's settled facts, not on a
channel payload. When no members are open, it writes the run's completion and retires the same way.

**Bound.**

- The per-body bound stays the SCI arm's `:seon.test/long-ms` or 5,000 ms interrupt.
- The request bound is the run agent's deadline, from `:seon.test/check-time-limit-ms`.
- A body alive past its bound keeps its member agent. The graph does not join, so the branch is held, and the member is
  recorded unfinished. This is what `bounded-result`'s watcher does today, done by Flow's executor join instead of a
  second thread mechanism.

### 5.3 How N clusters run at once, and what bounds them

- **N clusters** means N run agents, each on its own branch with its own writer (S5). Their members are independent
  member agents. There is no request-level serialization: S1 disappears, because members of one request run as
  concurrently as members of different requests.
- **CPU bound.** The process root's `:compute` executor (`resources/seon/operator/runtime.clj:17-22`) is
  `(flow/bounded-platform-executor (.availableProcessors …))`. It is declared once per process and shared by every
  cluster in the JVM. At most `availableProcessors` bodies execute at once; the rest wait in the JDK executor's queue.
  This gives the dead `:seon.config.flow.compute/concurrency` dial a consumer, or retires it (task
  `seon.config.edn:9`).
- **Heap bound.** `seon.sci.kernel/memory-pressure` (`kernel.clj:161`) already refuses new arms from 70% of tenured
  occupancy and resumes below 55%. Member admission asks it before hosting a member agent. A refused member stays
  admitted-not-started, and the run agent re-derives it on its next wake.
- **No lane cap and no slot.** The bounds are the machine's cores and heap. Root no longer serializes namespaces; it
  submits all 35 at once.
- **Estimated effect, not measured.** At 18 cores with bodies CPU-bound, the r5 run (35 namespaces, ≈25 min serial)
  becomes roughly the sum of body CPU time divided by 18, plus the fixed per-request cost (S2) paid once per request in
  parallel. §3.2 measured 3.5× from request-level concurrency alone at 7 cores busy.

### 5.4 What platform, destructive and host-bound tests still need

1. **Host-global members.**
   - Definition: a test whose own analysis (its body and the `seon.test-support` helpers it calls directly, not its
     transitive reach, §3.4) names `clojure.core/with-redefs`, `with-redefs-fn`, `alter-var-root`, `intern`,
     `load-file`, `require` with `:reload`, `seon.instrument/apply!`/`restore!`,
     `seon.test-support/preserving-instrumentation-state`/`preserving-schema-registry`, or a cluster boot.
   - The classification is derived the same way `effect-reach` is derived (`test.clj:2406`). It is not a hand list.
   - Such members join `isolated-members` and run on the isolated host (`bin/test --platform`) until they are
     converted. The conversion is the law's own requirement.
   - When the classification lands, `ambient-snapshot`/`ambient-drift`/`restore-live-cluster-schema!` (S17) have no
     subject left in the development JVM and are deleted.
2. **Platform, destructive and file-backed members** (`:seon.test/platform`, `:seon.fn/destroys` reach,
   `:seon.test/fixture-observation`) are unchanged. They need an OS process, a flock or store deletion, so a separate
   JVM stays the honest boundary. That JVM runs the same test agents inside it.
3. **Adoption during a run.** Adoption is a JVM-global event (S12). It still makes every in-flight member unknown, and
   that is correct detection. Root batches adoption between test rounds.
4. **Wall-clock assertions.** A test with a wall-clock assertion (§3.3.2) states its bound under admission. With the
   compute bound, a body no longer competes with more runnable bodies than there are cores.

### 5.5 Interface points with the one-path spec

`docs/research/agent-platform/one-repl-path-spec-2026-09-27.md` is rev 3 in another lane. It is not edited here.

1. **§2 `:seon.eval/request`.** A member agent supplies:
   - `:seon.eval/read-next`: one synthetic entry;
   - `:seon.eval/evaluate`: the thunk on the root `:compute` executor;
   - `:seon.sci.eval/ctx`: the member's fork;
   - `:seon.db/database-value`: the member branch head after seeds.
2. **§4 entrant table, row "Test body"** (observe/derive/settle = no/no/no; "evidence is the runner's `record-tx`").
   Test agents keep no/no for observe and derive.
   - **Settle:** the member's settlement is its result and exit facts on the run entity.
   - **Question for the spec owner:** is settle a boolean the entrant completes afterwards (no spec change: the member
     step calls `commit-results!` after the batch), or should `:seon.eval/settle` accrete as a stage value beside
     `:read-next` and `:evaluate`? The second keeps "an entrant is a choice of stage values" literal.
3. **§7.3 S13** ("`bounded-result` :218 … the `:else` branch becomes the `:evaluate` thunk of a one-entry batch.
   Bound, thread and release stay `bounded-result`'s").
   - Test agents supersede the second sentence: bound, thread and release move to the member agent's graph, and
     `bounded-result` is deleted.
   - Order: S13 lands first as specified. The test-agent slice then replaces `bounded-result`'s `FutureTask` and
     watcher (`test.clj:218-338`) with the member graph and `join-graph!`.
4. **§4 "The MCP reply projection."**
   - A test agent's reply is its run's batch entries, and it is projected once.
   - MCP `test` is `run-form` evaluating `(my.test/…)`, which makes one delivery shape for both sides (§4 of this
     note).
   - `my.test/run` returns the whole run value; the results-only projection goes.

### 5.6 Machinery reused, deleted and added

**Reused:**

- `seon.cluster.host/host!`/`retire!`
- `agent/acquire-context!`, `release-context!`, `acquire-once!`, `graph-definition`
- `seon.flow/var-process`, `start-graph!`, `join-graph!`
- the process-root executors
- `kernel/memory-pressure`
- `admit-run`, `commit-results!`, `record-exit!`
- `clean-request-handle`, `apply-seeds!`, `arm-first-loads!`, selection
- `evaluate-batch` (one-path)

**Deleted (source):**

| What | Lines |
|---|---|
| `test.clj` `bounded-result` (`:218-338`) | 121 |
| `member-result` (`:1968-2086`) | 119 |
| The sequential batch loop and exit bookkeeping inside `run`: `run-batch`, the batch `loop`, `release-not-started!`, `held-handle` (`:2330-2480`) | ≈150 |
| `attempt-request-context` (`:186-217`) plus effect-reach: a member agent has a real agent and turn | 32 + 12 |
| `runner.clj` `ambient-snapshot` (65), `set-drift` (10), `drift-directions` (23), `ambient-drift` (12), `live-cluster-schema-states` (18), `restore-live-cluster-schema!` (87), `schema-restore-drift` (11), drift lines in `run-vars!` (≈10) | ≈236 |
| `bin/test-check`'s own prepl request form and tally printing; it becomes the MCP `test` client | ≈80 of 134 |
| **Total** | **≈750** |

**Added (estimated):**

| What | Lines |
|---|---|
| Test run/member agent blueprint and step | ≈100 |
| Run-completion derivation from listened member facts | ≈40 |
| Host-global classification from direct analysis | ≈30 |
| MCP `test` tool as a `run-form` wrapper | ≈25 |
| `seon.test.edn` for `:seon.test.run/agent` and the member ref | ≈20 |
| **Total** | **≈215** |

**Net ≈ −535 source lines.** These counts are measured line spans of the named functions; the additions are
estimates.

**Tests.** A regression per class:

- two concurrent requests from two clusters, with no cross-attribution;
- the compute bound holds (`availableProcessors`+1 bodies, one queued);
- memory-pressure refusal leaves a member admitted-not-started;
- a body past its bound keeps its member agent until exit.

The 85 files using `with-redefs` are converted or classified host-global, file by file, by their owners.

## 6. Options (simplest viable first)

**A. Concurrent requests, members still sequential.** Keep `seon.test/run`. Root submits one request per namespace at
once. Classify host-global members from direct analysis into the isolated host. Delete S17.

- *Guarantee:* N clusters' non-platform tests run concurrently and correctly in one JVM; heap is bounded by the
  kernel's existing admission.
- *Cost:* ≈30 added, ≈236 deleted. Half a day.
- *Sacrifice:* CPU is bounded only by the number of requests. Each request stays sequential and pays its 2.8 s fixed
  cost. There are no test agents and no MCP parity, and `bounded-result` survives as a second thread mechanism beside
  Flow. Measured throughput is 3.5× at N=6.

**B. Test agents on Flow (recommended).** Option A's classification and deletion, plus §5.

- *Guarantee:* every member of every request is a one-body agent on the one path with its own graph, bounded by cores
  (the root `:compute` executor) and heap (kernel pressure), both declared once. The same request and delivery come
  from a Seon agent, MCP and the shell. Exit is proved by the graph's executor join.
- *Cost:* ≈−750/+215 source lines. Two slices after one-path S13:
  1. classification and S17 deletion (option A);
  2. run and member agents, MCP `test`, and deletion of `bounded-result`, `member-result` and the batch loop.

  About two days, plus owner-side conversion of the `with-redefs` files.
- *Sacrifice:* a member costs an agent graph (a few virtual threads) and a branch. Today's per-member acquire is
  32 ms serial and 147 ms at N=6. Branch creation is serialized store-wide by Datahike's roster permit (S6). A body
  that blocks on its writer holds a compute slot.

**C. Option B plus shared per-request work.**

- Key selection facts, the request world and `program-written-since?` by content digest in `seon.cache`, so N requests
  over the same program pay S2 once.
- Run members declared pure on one request branch with `seon.db/with` speculative values instead of one branch each.
- Batch roster publication for member branches inside one `:roster` permit.
- *Guarantee:* B's guarantee, with near-zero fixed cost per request and no S6 contention.
- *Cost:* B plus ≈150 lines and a `datahike.versioning` fork change for batched `branch!`. A purity declaration or
  derivation would also be needed. About two more days.
- *Sacrifice:* a speculative value isolates only what `with` sees, so a member that writes must still get a branch.
  Purity is a new classification to keep honest, and it touches a maintained fork.

B is recommended. It is the owner's "test agents, the one path". Its first slice is exactly A, so A lands value
immediately. C is deferred until measurements of B show S2 or S6 dominating.

## 7. Findings (for root to file; this lane edits no task files)

1. **F1.** Selection of 8 all-green members with 0 executions takes 1.2 s: `selection-admission` 1,178 ms,
   `selection-facts` 1,015 ms.
2. **F2.** `seon.test.runner/reach-memberships` over all 2,377 tests takes 31.6 s, over the ten-second bound.
3. **F3.** `runner.clj:435` prints drift through `pr-str`, unbounded. One failure message measured 14,262 characters.
   This violates "nothing prints unbounded".
4. **F4.** `ambient-drift` and `restore-live-cluster-schema!` misattribute concurrent requests' instrumentation
   changes: 486 Vars in one case, and false reds in 2 of 5 concurrent runs. Each member also writes every live
   cluster's projection.
5. **F5.** `seon.instrument-test/a-root-replaced-outside-a-reload-is-named` is persistently red: a replaced root
   `seon.instrument-test/doubled` from `NO_SOURCE_PATH` leaks in the JVM.
6. **F6.** `seon.fault-test/a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent` asserts `< 1000 ms` wall
   time. It measured 1,026 and 1,040 ms under 6× concurrency.
7. **F7.** The Datahike `gc-guard` `:roster` permit serializes branch creation store-wide: 23 ms rises to 66 ms at N=6.
8. **F8.** The process-root `:compute` executor is unused (active 0), and `:seon.config.flow.compute/concurrency` is a
   dead dial (existing task, `seon.config.edn:9`).
9. **F9.** Tests break the law against JVM-global test state (S18): 263 `with-redefs` uses in 85 test files.
10. **F10.** Default's health is `:faulted`, with 12 open faults, including 8 `seon.sci.kernel/acquire-arm` at 21:44.
    Not examined here.
11. **F11.** The auto-memory entry "bin/_test-slot caps at 2" is stale. The launcher and its slots were deleted on
    2026-09-22 (`8bc917872`).
12. **F12.** A branch `:agent-8072af9fb04a`, held by agent `"root"` under default's roster (last transaction
    21:59:45), is not this lane's and was left in place.

**Verification limits.**

- Measurements come from one development JVM, which other lanes shared (load average 7), so timings carry that
  contention.
- The runs were not isolated per request from other cluster activity.
- §5's line counts for additions are estimates.
- No design here was tried as code; option B's scaling estimate is not measured.
