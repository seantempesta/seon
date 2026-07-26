---
type: research
status: complete
tags: [agent, runtime, architecture, research, flow, testing]
---

# Core.async Flow testbed evidence, 2026-07-26

## Verdict and divergences first

Path A is technically viable: the public Flow API runs Seon's ordinary
`flow/process` launchers and its custom `flow.spi/ProcLauncher` together, a
database-backed proc reconstructs its state from surviving Datahike facts, and
bounded platform-thread capacity remains directly observable.

**Custody-window verdict: candidate B wins.** The provider call belongs to a
bounded attempt before a run becomes claimable; the successful attempt creates
the open run and freezes its plan in one transaction. The three strongest
reasons are:

1. B makes a committed open run without a plan unrepresentable. Candidate A
   preserves that invalid state and adds a compensating recovery protocol for
   it.
2. B preserves one meaning for the existing recoverable-run query: every
   recoverable run has a complete plan. A adds a pre-plan close transition,
   special pending-message derivation, and multiple run identities for one
   message.
3. The existing bounded provider-attempt facts already own provider request
   configuration, deadline, ordinal, and outcome. Extending that entity with
   message custody localizes the 78.5% provider window without occupying the
   agent's run pointer.

Candidate A did pass its requested measures, but at the higher semantic cost:
the plan-less/lapsed query found the otherwise invisible run, takeover advanced
its claim epoch, recovery closed it with a receipt, and the modified wake
derivation caused exactly one retry. Final state had one logical model-call
fact, no open run, and no orphan.

Candidate B needs the attempt to connect to the originating message and record
the holding process, claim epoch, and lease instant. Recovery selects an open,
lapsed attempt, advances its epoch, records `:crashed`, and re-derives the same
message wake. A successful holder fences that attempt and commits run identity,
cause, process, claim epoch, lease, plan digest, plan forms, and the agent run
pointer atomically. The existing run recovery query remains unchanged and
plan-only.

This changes the ordering of `open-run!`: it moves after the provider reply,
and the old separate absent-to-digest plan CAS is replaced by the successful
attempt fence plus atomic run-and-plan creation. The attempt transition must
reuse or generalize the existing claim-epoch/lease algebra rather than grow a
second implementation.

Step 2's message-identity work must coordinate exactly these identities:
attempt identity derives from `(originating-message-id, attempt-ordinal)`;
provider request identity derives from that attempt identity; and a
re-executed sending receipt `(run, ordinal, epoch)` must reproduce the same
originating message ID. Otherwise recovery creates a second legitimate attempt
lineage instead of retrying the first. The outbound message ID remains the
sending receipt's identity, not the provider-attempt ID.

The indexer-as-proc extension found one production-interface defect that must
be fixed before the production lane can treat edge generations as stable:

- **`seon.program.edge/analyze-function` generations currently depend on
  `*print-namespace-maps*`.** The analyzer hashes `pr-str` of a map with
  namespaced keys. A direct call produced generation
  `6290aa56075ea3b5a53f34ef5a4d6e649cff2c19ca2b35880e37205dbc419c17`,
  while the identical bundle on Flow's compute executor produced
  `afb7e22fe660ce975dda0c98e1a5082fbb099ad4d231cc29f9072e68572684ae`.
  The only difference was the dynamic print binding. The testbed adapter binds
  `*print-namespace-maps*` to `false`; the protected analyzer was not edited.
  The pure core must canonicalize independently of dynamic printer state.

The extension also exposed precise proc-interface friction:

- `compile-tx-data` accepts a database value plus the **complete desired
  program**, not one namespace event. The indexer proc therefore retains
  successfully compiled rows by namespace and presents their complete
  accumulated snapshot on every call. A malformed namespace is not admitted
  to that accumulator.
- `compile-tx-data` reconciles base function fields but does not exactly
  replace the analyzer's cardinality-many edge attributes. The testbed must
  compose `seon.program.edge/transition-tx` for the changed function bundles;
  without it, changing `inc` to `+` retains the stale `inc` call fact.
- Source enumeration, reader-conditional parsing, namespace description, and
  analyzer resolution are outside `compile-tx-data`. That boundary is
  appropriate for purity, but the production proc needs one explicit adapter
  whose output names both the complete program rows and changed edge bundles.
- Malformed source and analyzer failures throw rather than return error values.
  This works with Flow's two-class design—the exception becomes a core fault
  fact and later namespace events continue—but it is an explicit boundary
  conversion, not an error-value API.
- The isolated database must install `:seon.ns/source` and the optional
  `:seon.db.id/generator` attribute before compiling. Desired schema rows carry
  the established `:seon.db.id.generator/absent` sentinel; otherwise a partial
  schema basis can make reconciliation emit a retraction against an
  uninstalled attribute.

The earlier testbed proved these additional parts of the prior understanding
wrong or incomplete:

- **Flow Monitor is not a non-invasive report tap; the testbed now resolves
  that defect.** Its `report-monitoring` loop takes directly from the channels
  exposed by the graph it receives. Passing the real graph therefore makes
  application consumers and Flow Monitor compete. `seon.flow` now owns one
  `mult` per source channel, gives the fault-committer proc and application
  report consumer tap A, and exposes independent sliding tap B channels to
  Flow Monitor through a public-API-delegating, datafiable graph. A thrown eval
  step reached both a durable Datahike fault fact and Flow Monitor; an ordinary
  report reached both the application tap and Flow Monitor. The prior direct
  attachment was wrong.
- **Core fault overflow cannot be made observable with core.async's released
  dropping buffer alone.** `DroppingBuffer.add!*` silently ignores a new value
  at capacity and has no callback. The fan-out therefore uses a small
  core.async `Buffer` implementation with the same nonblocking bounded
  semantics plus a drop callback. The standing proof pauses the committer:
  six faults at capacity six all commit, while five faults at capacity two
  commit two fault facts and a durable drop count of three.
- **A wedged proc cannot report its own wedge.** Its proc loop is blocked
  waiting for the compute future, so `ping` times it out. A separate responsive
  capacity-observer proc can name the active proc, platform thread, wedge flag,
  and remaining permit count. Missing ping replies are evidence, but not enough
  to identify the cause without that observer.
- **`stop` is command delivery, not a join.** The public call clears the
  graph's channels immediately. The custom SPI launcher needs its own stopped
  event when a test or supervisor must know that its loop has exited.
- **Declared but unconnected outputs are `nil`.** A standard proc that emits to
  one can stall or fail during output delivery and retain its pre-step Flow
  state. Every emitted output in the testbed is connected to a real consumer.
- **Concurrent Datahike transaction reports do not imply one distinct
  `db-after` basis per caller.** In the eight-proc contention test all 240
  transaction IDs were unique and ordered, while only 119 to 132 distinct
  reported `db-after` bases appeared across observed runs. The maintained
  writer can publish a later effective committed database value in several
  reports. Transaction identity and committed fact count, not uniqueness of
  every report's `db-after`, are the correct proof.

One coordinate assumption was also wrong: Flow does not currently have a
separate Maven artifact. It is released from the `dev-flow-alpha` branch under
the same `org.clojure/core.async` artifact name.

## Exact coordinates

The maintained upstream refs and Maven metadata were checked on 2026-07-26.

| role | current coordinate | exact source |
|---|---|---|
| core.async latest stable | `org.clojure/core.async 1.9.865` | tag `v1.9.865`, commit `f281893a53a4f25cad07dd6a09af4ae9c9233a89` |
| Flow current release | `org.clojure/core.async 1.10.874-alpha3` | branch `dev-flow-alpha`; tag `v1.10.874-alpha3`, commit `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| Flow Monitor current release | `io.github.clojure/core.async.flow-monitor` with Git tag `v0.1.5` and SHA `376d6ec` | commit `376d6ec2c065e26a33b4da8101b74b8ef70b1a58` |

Seon pins Flow alpha3 in both root shared-coordinate aliases, `:writer` and
`:cljs`. That necessarily selects alpha3's core.async implementation instead
of stable `1.9.865`; Maven cannot select two versions of the same artifact in
one basis. Flow is JVM-only, but keeping the aliases on one coordinate avoids
an accidental split dependency graph.

The checked-out `reference-code/core.async` gitlink is the exact alpha3 tag.
The checked-out `reference-code/core.async.flow-monitor` gitlink is the exact
published `v0.1.5` commit. `:writer-test` consumes the latter through its
vendored `:local/root`; it is not added to the production writer basis.

Primary source anchors:

- [core.async stable README](https://github.com/clojure/core.async/blob/v1.9.865/README.md)
- [Flow alpha3 tag](https://github.com/clojure/core.async/tree/v1.10.874-alpha3)
- [Flow SPI at alpha3](https://github.com/clojure/core.async/blob/v1.10.874-alpha3/src/main/clojure/clojure/core/async/flow/spi.clj)
- [Flow Monitor v0.1.5](https://github.com/clojure/core.async.flow-monitor/tree/v0.1.5)

The `:ping-map-fn` seam is implemented at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:246`
and applied to public ping state at line 279. The custom launcher contract is
at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:11`.

## Testbed topology

`src/seon/flow.clj` contains only JVM Flow launchers and their SPI boundary:

- a standard `:compute` eval proc running on a supplied fixed platform-thread
  executor, acquiring a supplied semaphore, arming a fake interrupt function,
  and publishing disposable results;
- a standard responsive capacity-observer proc deriving occupancy from the
  process-local active-eval table and semaphore;
- a standard mailbox proc whose input is configured as a
  `(sliding-buffer 1)` tap; and
- a custom database proc whose ping and step both read facts through a
  supplied function, whose step commits through a supplied function, and whose
  channels contain wake values rather than durable state; and
- the report/error fan-out owner, a fault-committer proc governed by a supplied
  database-backed core-error mode reader, and a Flow Monitor graph view whose
  lifecycle and control calls delegate to the real graph.

The recurring proof is `test/seon/flow_test.clj`. The real child-process
crash helper is `test/seon/flow/kill_child.clj`; it lives under `test/` because
the experiment must run again.

The JVM indexer proof is `test/seon/flow/indexer_test.clj`, with four real
`.cljc` inputs under `test/seon/flow/fixtures/` (three valid namespaces and one
deliberately malformed namespace). Its graph is one mechanism, not a bespoke
runner:

- `:source-enumerator` is an `:io` proc. Its injected reader walks and reads
  the fixture files at proc initialization; later input may replace and emit
  exactly one namespace source.
- `:indexer` is a `:compute` proc on a supplied one-thread bounded platform
  executor. It calls the real `seon.program.edge/analyze-function`,
  `seon.db.program/compile-tx-data`, and
  `seon.program.edge/transition-tx`.
- `:page-committer` is the existing database-backed proc. Its fixed input
  buffer carries transaction pages only; durable program state remains in the
  throwaway Datahike connection.

## Expected-versus-observed scenario matrix

| scenario | expected | observed |
|---|---|---|
| 1. Public lifecycle controls custom launchers | `start`, `stop`, `pause`, `resume`, per-proc controls, and `ping` work through `clojure.core.async.flow` | Passed. Start returned report/error channels; all four procs began paused; graph and per-proc transitions were observed through public pings. The custom stopped callback was required for exit completion. |
| 2. Recreated Flow resumes from facts | Flow-local state may disappear while committed facts remain authoritative | Passed. The first graph committed durable count 2, the recreated database proc pinged count 2 with Flow count 0, and its next wake committed count 3. |
| 3. Fixed-buffer backpressure | A full fixed buffer parks the producer and loses no values | Passed. Capacity 2 retained two messages while the proc was paused, the six-message injection future remained incomplete, and resume drained values `0` through `5` in order. |
| 4. Exactly N wedges consume N slots | N host-call wedges reduce capacity by N and are named | Passed for parallelism 4 and N = 2. Available permits became 2; exactly the other two eval procs completed. Wedged proc pings timed out, while the observer ping and report named `#{:eval-0 :eval-1}`. |
| 5. Fake interrupt ends a spin | An armed interrupt ends compute without killing its proc | Passed. A looping work function called the fake interrupt until it returned a flat `:seon.error/kind :timeout` value. The same proc completed a later submission and restored its permit. |
| 6. Sliding mailbox is latest-only | A paused `(sliding-buffer 1)` tap never parks injection and retains the newest value | Passed. Injection of 100 snapshots completed while paused; datafy reported count 1 and capacity 1; resume delivered only `99`. |
| 7. Pause between transforms loses nothing | Pause takes effect after the active transform, then resume preserves order | Passed. Ordinal 3 was held explicitly, pause was sent, release let that transform finish, the proc then reported paused with stable progress, and resume completed `0` through `19` in order. |
| 8. Concurrent database submissions commit atomically | Multiple database procs sharing one connection retain every admitted transaction in writer order | Passed with a corrected assertion. Eight procs committed 30 facts each: 240 facts and 240 unique transaction IDs. Reported `db-after` bases were not unique per caller, as described in the divergence above. |
| 9. Two graphs are isolated in one JVM | Stopping one graph does not damage another graph's lifecycle or executor | Passed. Graph A and its executor stopped; graph B remained running and completed a new submission. Public operations on stopped A failed as expected. |
| 10. Forced process death preserves commits | SIGKILL loses process-local compute but not committed facts | Passed. A child JVM committed count 1 and published readiness, was killed with SIGKILL, and a new Datahike connection reopened count 1. A replacement Flow database proc then committed count 2. |
| 11. Flow Monitor renders live topology | The released monitor attaches to a running graph and exposes topology, buffer, and wedge evidence | Passed at the server/WebSocket boundary. HTTP returned 200 with the Flow Monitor title. The WebSocket's initial datafy and live ping messages named all four procs, `FixedBuffer`, and `:seon.flow/wedged-procs` containing `:eval`. |
| 12. Core fault fan-out does not compete | A throwing step reaches durable fault storage and Flow Monitor; a report reaches the application and monitor taps | Passed. The committed fact and Flow Monitor error both named `:eval`; a following successful report appeared once at each consumer. Flow Monitor was attached to the proxy graph through its released `start-server`. |
| 13. Fault tap is lossless within its bound | N faults admitted to a capacity-N tap survive a paused committer | Passed for N = 6. No facts existed while paused, no drops were recorded, and resume committed all six distinct fault messages. |
| 14. Fault overflow is loud and durable | Values beyond the committer tap bound do not disappear silently | Passed at capacity 2 with five faults. Two fault facts committed and the durable drop counter became 3. The monitor tap independently received all five because its test capacity was five. |
| 15. Agent errors remain values | A flat eval error value never enters Flow's core error channel | Passed. The synthetic interrupt returned `:seon.error/kind :timeout` in the normal eval report, the Flow error channel remained empty, and the proc handled its next submission. |
| 16. Indexer one-shot drain equivalence | Pipeline facts equal the direct pure-core composition for the same fixture sources | Passed. Three namespace pages committed the same namespace sources, require edges, functions, exact direct-call edges, generations, and schemas as a direct `compile-tx-data` plus `transition-tx` call. |
| 17. One-namespace incremental upsert | Re-injecting alpha changes only alpha and exactly replaces its edge facts | Passed. A Datahike `since` query resolved every changed program entity to `seon.flow.fixtures.alpha`; the function identity remained singular and its call set changed exactly from `clojure.core/inc` to `clojure.core/+`. |
| 18. Malformed source isolation | A reader failure becomes a durable core-fault fact while later namespaces still index | Passed. The shared fan-out committed a fault naming `:indexer`; a subsequent valid gamma event committed and the indexer public ping reported five completed pages. |
| 19. Slow committer backpressure | A full fixed buffer parks the indexer and loses no transaction page | Passed. An event latch proved all three pages had compiled, public indexer ping timed out while its third delivery was parked, release committed all three pages, and all three namespaces existed. |
| 20. Mid-drain graph recreation | Stopping and recreating the graph completes without duplicate or torn program facts | Passed. The first graph stopped while its first commit was held; after release, a fresh graph drained the complete fixture snapshot. Final facts equaled the direct result with exactly three function and three schema identities. |
| 21. Flow Monitor indexer topology | The released monitor names all three procs and observes drain progress | Passed. Its WebSocket stream named `:source-enumerator`, `:indexer`, and `:page-committer`, contained `namespace-indexed`, and the committer public ping reported count three. |
| 22. Candidate A recovers a plan-less claimed run | A lapsed plan-less run is named, closed with a recovery receipt, and wakes exactly one retry without duplicate logical work or an orphan | Passed with seed `20260726`. The current recoverable-run and pending-message queries were both empty after the injected kill. The candidate query named the exact run; takeover advanced epoch 1 to 2, closed it as failed-before-plan, and committed one receipt. The modified wake derivation admitted one retry. Two physical provider invocations represented the killed call plus exactly one retry, while the upserted logical model-call fact remained singular and no open run or pending wake remained. |
| 23. Candidate B keeps provider custody on an attempt | No open run exists until the provider reply; attempt recovery re-wakes work, and successful retry creates run plus plan atomically | Passed with seed `20260726`. The killed attempt was fenced and recorded `:crashed`; the message woke once for ordinal 1. Every retained database value contained zero open runs without plans. The run ID and plan digest datoms shared one transaction, the unchanged recoverable-run query saw the planned run, attempt outcomes were exactly `#{:crashed :success}`, and no recoverable attempt remained. |

The two error classes are now executable rather than documentary: exceptions
escaping a Flow step are core faults routed through the fan-out and committed;
agent-facing `:seon.error` maps are ordinary returned values and never enter
Flow's error channel.

Scenario 11 used the accepted report-channel/server evidence rather than a DOM
screenshot. The connected in-app Browser runtime reported that no browser was
available, so no browser page could be opened. The recurring HTTP and WebSocket
assertions exercise the same released monitor server and carry the graph data
that its ClojureScript UI renders.

## Proof commands and results

The exact dependency basis was first resolved with:

```sh
clojure -Stree -M:writer:host:writer-test
```

It selected direct `org.clojure/core.async 1.10.874-alpha3` and the vendored
Flow Monitor local root.

The recurring testbed was run directly on that basis:

```sh
clojure -M:writer:host:writer-test -e \
  '(require (quote seon.flow-test))
   (clojure.test/run-tests (quote seon.flow-test))'
```

Result after the error-design extension: 14 tests, 66 assertions, 0 failures,
0 errors.

The indexer-as-proc extension was run independently on the same basis:

```sh
clojure -M:writer:host:writer-test -e \
  '(require (quote seon.flow.indexer-test))
   (clojure.test/run-tests (quote seon.flow.indexer-test))'
```

Result: 3 tests, 11 assertions, 0 failures, 0 errors. The retained concise
receipt is
`tmp/plan-evidence/flow-indexer-proc-2026-07-26.log`.

The existing Flow proofs and the indexer extension were then run in one JVM:
17 tests, 77 assertions, 0 failures, 0 errors.

The custody-window simulation was run independently on the same basis:

```sh
clojure -M:writer:host:writer-test -e \
  '(require (quote seon.flow.custody-window-test))
   (clojure.test/run-tests (quote seon.flow.custody-window-test))'
```

Result: 2 tests, 26 assertions, 0 failures, 0 errors. Both candidates used
explicit seed `20260726`; the fake provider's ordinal zero was killed while its
Flow `:io` step held custody, and ordinal one returned the scripted reply.

The focused pre-existing JVM async paths were run on the same alpha3 basis:

```sh
clojure -M:writer:host:writer-test -e \
  '(doseq [n (quote [seon.db.executor-test
                     seon.db.host-interest-test
                     seon.db.writer-interest-test])]
     (require n))
   (apply clojure.test/run-tests
          (quote [seon.db.executor-test
                  seon.db.host-interest-test
                  seon.db.writer-interest-test]))'
```

Result: 42 tests, 773 assertions, 0 failures, 0 errors. The executor suite
printed its expected injected completion-failure log while asserting that
failure path.

An attempted repository-native `bin/test-writer seon.flow-test` stopped before
loading tests because the current compiled program artifact did not exist.
Creating it requires a coordinated `bin/seon up`/`down` source freeze. Several
owner-launched pod-deletion lanes were concurrently changing artifact inputs,
so this lane did not build an incoherent checkpoint or interrupt that cut.
The two results above are direct JVM runs on the exact `:writer:host:writer-test`
dependency basis, not claims that the artifact-gated wrapper ran.

## Design consequence

The production move should preserve the tested split:

- Flow owns process-local scheduling, bounded channels, workload executors,
  lifecycle commands, and disposable diagnostics.
- Datahike owns run, receipt, and other restart-relevant facts.
- Provider-call custody belongs to a bounded attempt. A run becomes claimable
  only when its identity and complete plan can commit in the same transaction.
  Attempt recovery re-derives the originating message wake; run recovery stays
  plan-only.
- A responsive observer, not a wedged proc, names capacity loss.
- Flow Monitor must receive the fan-out owner's datafiable graph view, never
  the source graph. The fault tap is bounded, lossless within its configured
  capacity, and commits overflow counts as facts; monitor taps are independently
  bounded and cannot delay fault commitment.
- Agent error values stay on the ordinary result path. Only exceptions escaping
  Flow machinery enter the core-fault path and obey the core-error config fact.
- The JVM indexer uses the same Flow graph, executor, fixed-channel
  backpressure, lifecycle, error fan-out, and Flow Monitor view. Its durable
  result is ordinary program facts, not proc state.
- A production incremental indexer cannot call `compile-tx-data` with one
  namespace row and call that reconciliation. It needs the complete desired
  population plus the changed bundles' exact `transition-tx`, and analyzer
  generation hashing must first be independent of dynamic printer bindings.

This testbed does not move the existing semaphore/eval path and is not proof
that the production mechanism has already adopted Flow.
