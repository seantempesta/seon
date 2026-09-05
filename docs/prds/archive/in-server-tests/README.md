---
type: prd
status: superseded
note: "Superseded 2026-08-13 by the active one-test-infrastructure specification and its isolated shared-base/worker gate."
tags: [prd, testing, runtime, operator]
---

# In-server test execution: one runner, three callers

## Closing note (2026-08-13)

This proposal is superseded by the active
[one-test-infrastructure specification](../../sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md),
which absorbs its four open questions and rules that ordinary tests run in an
isolated suite rather than against a user's live cluster. At HEAD, `bin/test`
owns one isolated operator root and prepares one shared published base
(`bin/test:21-24,437-464`); `seon.cluster/source-base!` acquires that immutable
program value (`src/seon/cluster.clj:1294-1333`); and the one runner coordinates
bounded worker JVMs and the platform-first tier (`src/seon/test/runner.clj:1498-1516,1518-1679`).
The historical one-runner principle survives there, but this PRD's live-cluster
dispatcher, reload contract, and independent queue no longer own current work.

## Decision

Owner-approved direction (2026-08-03), kept by the 2026-08-06 cleanup ruling:
one test-run mechanism with three callers — `bin/test` dispatching eligible
fast-tier work into a live cluster; a human at the attached prepl calling the
same function; and root calling it as ordinary program-graph work. The spawned
JVM remains the owner for `--full`, tests declared
`:seon.test/long`, process-unclean tests, and the no-live-system fallback.
Grounding and measurements remain in
[in-server-tests-2026-08-03.md](../../sci-execution-runtime/research/in-server-tests-2026-08-03.md),
but its operator-integration and missing-test-call-edge claims are historical,
not current dependencies.

The measured prize remains single-namespace iteration: 10.32 s spawned versus
28–51 ms warm in-process (roughly 250×). It does not predict the full fast-tier
speedup; test bodies remain the bulk of that gate.

## Current re-grounding (2026-08-06)

The 2026-08-03 brief was verified against the current tree before this refresh.
These are the stale or still-unbuilt edges, with the old brief location and the
current owner on both sides:

- **The classpath blocker remains, but the launch owner has consolidated.** The
  old brief said the live JVM used `-M:dev` and needed `-M:dev:test`
  (`README.md:28-30`, pre-refresh). The current launcher still selects
  `-M:dev` or `-M:dev:seon-cache`, not the test alias
  (`script/seon/fresh_operator.clj:266-286`); `test/` still enters only through
  `deps.edn:121-134`. The launch regression currently requires
  `-M:dev:seon-cache` (`test/seon/dev/fresh_operator_test.clj:948-963`), so the
  implementation must preserve the immutable cache alias while adding the test
  classpath. This is no longer safely described as an isolated one-line edit.
- **`:seon.test/long` is still not indexed.** Slice 1 described the lift as
  upcoming work (`README.md:41-43`, pre-refresh). The current test schema has no
  long attribute (`resources/seon/schemas/seon.test.edn:1-17`), and the indexer
  emits test identity, namespace, source, calls, and keywords while dropping
  the retained long marker (`src/seon/fn.clj:293-324`). The runner therefore
  still loads Vars and reads namespace/Var metadata to split the tier
  (`src/seon/test/runner.clj:370-409`).
- **Test call edges landed; the old research blocker is gone.** The linked
  research reported zero test call edges. Current indexing writes
  `:seon.fn/calls` on `:seon.test` rows (`src/seon/fn.clj:315-324`), and
  `seon.fn/tests-reaching` derives direct, transitive, and explicit-subject
  coverage (`src/seon/fn.clj:403-440`), with the behavior proven in
  `test/seon/fn_test.clj:759-813`. Process-cleanliness may now be derived from
  that graph once the exact disqualifying leaves are declared; this PRD does
  not wait for another test-call-edge mechanism.
- **`seon.operator/test!` and general test reload do not exist.** Slice 2 called
  `test!` the ninth operator verb and composed it with the then-proposed
  `reload!` (`README.md:44-53`, pre-refresh). The current `seon.operator`
  explicitly says the attached prepl has no namespace-refresh mechanism and
  owns the consolidated in-JVM operation contracts
  (`src/seon/operator.clj:1-16`); its current public operations run from
  `start!` through `refork!`, with no test verb
  (`src/seon/operator.clj:56-131,744-791`). The only live `:reload` sequence is
  the source-publication path's explicit owner list
  (`script/seon/fresh_operator.clj:1937-1963`), not a reusable dependency-ordered
  test reload. The archived operator-integration PRD is therefore removed from
  the implementation dependency edge.
- **`bin/test` and changed-test still always spawn.** Slice 3 described live
  advertisement dispatch and deletion of duplicate analysis
  (`README.md:54-58`, pre-refresh). Current `bin/test` always clones an isolated
  operator root and launches `-M:test -m seon.test.runner`
  (`bin/test:157-250,327-369`). `seon.dev.changed-test` still runs its own
  clj-kondo namespace analysis (`script/seon/dev/changed_test.clj:73-155`) and
  shells `bin/test` separately for operator and writer boundaries
  (`script/seon/dev/changed_test.clj:341-414`); its regression asserts that
  spawned path (`test/seon/dev/changed_test_test.clj:16-35`).
- **The reusable runner seam remains valid, but liveness does not.** The old
  brief correctly separated reusable `run!` from process termination
  (`README.md:31-35`, pre-refresh). `run!` still returns the schema'd report
  value without exiting (`src/seon/test/runner.clj:437-487`), while `-main`
  owns namespace loading, long-test selection, recording, `System/exit`, and
  the fatal silence backstop (`src/seon/test/runner.clj:607-672`; the halt is at
  `src/seon/test/runner.clj:296-318`). An in-server caller needs a distinct
  nonfatal liveness arm around this one runner, not reuse of `-main`.
- **The root-agent return path is superseded by the messaging redesign.** Slice
  4 assumed an ordinary direct eval with no receipt beyond that eval
  (`README.md:59-64`, pre-refresh). The 2026-08-06 ruling instead requires
  one-value send/complete, wait on the send's returned value, explicit
  addressing, and a terminal `result/<eid>` receipt handle
  (`../../sci-execution-runtime/plan/README.md:778-807`). The current string-only
  `my.message/send` and note-based `my.run/wait` show that wave has not landed
  yet (`src/my/message.clj:17-59`; `src/my/run.clj:16-47`). Root invocation and
  report handoff therefore wait on that messaging implementation rather than
  coding against today's superseded constructors.

## Current dependencies and queue edge

### Landed owners this PRD builds on

- `src/seon/test/runner.clj:437-487` owns the one structured run and
  `resources/seon/schemas/seon.test.runner.edn:18-53` owns its report shape.
  `-main` remains the disposable-process shell; it is not the in-server API.
- `src/seon/fn.clj:293-324,403-440` owns indexed tests, test call edges, and the
  dependent-test query. `resources/seon/schemas/seon.test.edn:1-17` is the one
  schema owner to extend with the declared long reason and any declared
  process-cleanliness leaf facts.
- `src/seon/operator.clj:1-16` owns in-JVM operator operations shared with
  terminal/prepl callers. `script/seon/fresh_operator.clj:266-286` owns the
  launched cluster classpath, and `bin/test:157-369` owns spawned isolation,
  fallback, result-cluster behavior, and process exit.
- `script/seon/dev/changed_test.clj:73-230,341-427` owns today's affected-test
  selection and execution. It must consume the program graph and the same
  in-server/spawned dispatcher; it must not retain a second analysis or runner.
- The landed 2026-08-05 quality-gate ruling says shared definitions, schemas,
  and tests merge only after their dependent tests pass
  (`../../sci-execution-runtime/plan/README.md:520-531`). The host JVM runner stays
  distinct from SCI candidate-context accretion testing; only indexed
  dependencies and the result shape are shared.

### Genuine waits in the active queue

1. **Deletion sweep landing.** The active working edge records the code sweep
   in flight (`../../sci-execution-runtime/plan/unsettled.md:19-41`). This PRD
   touches the same operator launcher and test feedback owners, so
   implementation starts from the landed sweep rather than racing a removed
   seam.
2. **Quiet-tree green bare gate.** Changing `bin/test` changes the graduation
   gate itself. Capture a green bare `bin/test` on the post-sweep quiet tree
   before dispatch changes, then preserve the spawned result on the same tree.
   Foreign `src/` or `test/` churn may delay that proof but is not a design
   dependency.
3. **Messaging implementation wave.** Root-agent invocation waits for the
   ruled one-value `send`/`complete`, wait-on-send custody, and `result/<eid>`
   resolution. The active edge places that wave after the result-id archaeology
   and SCI probe return (`../../sci-execution-runtime/plan/unsettled.md:28-41`).
   Launcher, runner, operator, and human-prepl slices may be designed now, but
   root's end-to-end proof cannot graduate against the current string protocol.

## Implementation order

1. Extend the live launch alias composition so both cached and uncached cluster
   JVMs include `test/`; update the exact child-command regression. Prove a
   freshly started expendable cluster resolves a test resource.
2. Declare and index the non-blank `:seon.test/long` reason. Derive tier and
   process-cleanliness selection from program facts before loading a test
   namespace; no hand-maintained namespace roster.
3. Add one nonfatal in-server request around `seon.test.runner/run!`: load or
   reload the selected namespace closure in declared dependency order, run on
   an interruptible named thread, return the structured report or flat error,
   and retry the proven soft-reference class-eviction failure once. The open
   owner decision below determines whether the shared request owner is
   `seon.test.runner` or `seon.operator`.
4. Teach `bin/test` to choose the live fast path only when the selected cluster
   is unambiguous and answering; preserve spawned execution for `--full`, long,
   process-unclean, result-cluster, absent, and unreachable cases. Make
   changed-test call the same dispatcher and query program facts instead of
   rebuilding the graph.
5. After the messaging wave, let root invoke the test request as ordinary
   program-graph work and complete with the one report value. If root delegates
   the run to a namespace owner, it sends exactly one request value and waits on
   that send. The terminal eval's trailing `result/<eid>` is the durable handle
   for the report; a later turn may pass that handle as an ordinary value. Do
   not synthesize a reply or use a bare wait.
6. Prove store, heap, thread, and fixture-lease growth stays flat across
   repeated in-server runs; keep the full spawned gate as the load-order,
   classpath-freshness, process, and boot proof.

## Falsifiers

- `require :reload` (or the ruled replacement) restores a deliberately evicted
  namespace in an expendable cluster; recurrence routes to the spawned path.
- A cooperatively wedged test is interrupted, returns a flat error, and the
  cluster still answers prepl and web requests. A non-cooperative test is
  classified process-unclean; no in-server path calls `halt` or `Thread.stop`.
- The same namespace selection produces the same pass/fail verdict in-server
  and spawned, and an edit after JVM boot is visible on the next run.
- `--full`, every declared long or process-unclean test, an ambiguous live
  cluster selection, and a missing/unreachable cluster take the ruled spawned
  or refusal path without guessing.
- Repeated in-server runs return heap, thread, and fixture-lease observations
  to baseline.

## What not to build

- no second runner, reporter, changed-test analyzer, or namespace roster;
- no `System/exit`, `Runtime.halt`, or fatal backstop in a live cluster;
- no in-server `--full`, process/boot tests, or classpath mutation machinery;
- no separate test-code capability handler — effects inside tests use the existing effect
  owner; and
- no direct root-agent shortcut around one-value messaging, wait custody, or
  terminal eval receipts.

## Graduation

On a quiet post-sweep tree, `bin/test seon.blob-test` against the explicitly
selected live cluster returns in under two seconds end to end; the same
structured report is returned from an attached prepl; root invokes the same
function, completes with the one report value, and can resolve its
`result/<eid>` handle on the next turn. A delegated run uses one request value
and wait-on-send. The spawned fallback passes with no live cluster, `--full`
remains process-fresh, and the bare gate stays green. Fifty repeated in-server
runs leave heap, thread, and fixture-lease observations at baseline.

## Open design questions (2026-08-06)

1. **How is process-unclean test execution declared?**
   - **Option A (recommended):** declare a process-boundary fact on the exact
     spawning, cluster-lifecycle, and operator-root mutation leaves, then derive
     each test's spawned requirement through indexed call reachability. This
     adds a small leaf contract and automatically classifies new callers.
   - **Option B:** declare spawned execution on each intrinsically
     process-global test row. This is direct and easy to query but requires
     every new test author to repeat the classification instead of deriving it
     from the function graph.
2. **What is the reload contract before an in-server run?**
   - **Option A (recommended):** derive the selected namespaces' downstream
     closure from indexed namespace facts and `require :reload` that ordered
     closure for every explicit run. This guarantees fresh edits at a measured
     reload cost and centralizes reload in the test owner.
   - **Option B:** trust hot-reloaded Vars, require only missing test
     namespaces, and refuse when published source and loaded code disagree.
     This minimizes reload side effects but makes staleness a surfaced refusal
     the caller must resolve.
3. **Which live cluster may `bin/test` use automatically?**
   - **Option A (recommended):** add an explicit `--live-cluster NAME`; without
     it dispatch in-server only when exactly one answering cluster exists.
     This is deterministic across sovereign clusters but adds one flag for
     multi-cluster developers.
   - **Option B:** use only the answering `default` cluster and otherwise
     spawn. This is simpler but leaves other explicitly chosen development
     clusters unable to serve the fast path.
4. **Does an agent-run report create separate test-result facts?**
   - **Option A (recommended):** keep the full report in the terminal eval
     receipt and address it through `result/<eid>`; reserve
     `seon.test.runner/record!` for opt-in external gate history. This avoids
     duplicating one report across receipt and test-run entities.
   - **Option B:** also transact `:seon.test.run/*` and
     `:seon.test.result/*` facts into the agent's cluster. This makes historical
     coverage queries direct but creates two durable representations whose
     identity and retention must be reconciled.
