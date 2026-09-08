---
type: issue
status: resolved
severity: friction
tags: [issue, testing, instrumentation, wave/contract-gate]
---

# Thirteen `seon.sci.eval-test` reds appear only under the whole gate

Found 2026-09-08 by `instrumented-gate-backlog-2`
([landing note](../../prds/context-generation/research/instrumented-gate-backlog-2-landing-2026-09-08.md) §4).

Across the wave's two `bin/test --all` runs, the runner's confirmation phase
went from **3** `parallel-only` verdicts to **20**. Thirteen of the twenty are
`seon.sci.eval-test`:

```text
a-previously-defined-function-uses-the-current-evaluation-limit
a-re-entrant-evaluation-inherits-the-governing-arm
a-refusal-keeps-its-own-kind-at-both-entrances
a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed
agent-owned-sci-var-metadata-remains-mutable
agent-print-vars-are-captured-before-sci-bindings-unwind
analysis-failure-exposes-scis-unresolved-symbol-as-data
bare-dir-and-program-derived-doc-are-repl-native
compiled-runtime-metadata-cannot-be-changed-by-agent-code
compiled-runtime-roots-cannot-be-redefined-by-agent-code
every-public-capability-function-in-the-graph-resolves-in-the-ctx
instrumented-generated-form-does-not-advise-an-absent-program-row
schema-and-contract-declarations-have-bounded-allocation
```

## What was ruled out

- **The suite alone.** `bin/test seon.sci.eval-test` at `9cd1f9ab4` reports
  SEVEN reds, the same seven the census run recorded. None of the thirteen is
  among them.
- **The worker re-arm.** `bin/test seon.sci.eval-test seon.instrument-test
  seon.db-test` at the same commit reports **zero** `parallel-only`, and its
  worker log shows the re-arm firing twice for real
  (`RE-ARMING CONTRACTS worker= pool-1 installed= 0
  armed-at-initialization= 897` — a task had stripped every wrapper). The
  re-arm is therefore exercised, succeeds, and does not produce the class in
  a pool that reproduces its trigger.

## What is not ruled out

The two `--all` runs differ by three commits from two other lanes —
`c04765e10`, `f2a956dc6`, `f46f9d461` — touching
`src/seon/cluster/{wake,work,loop,agent}.clj`, `src/seon/error.clj`,
`src/seon/bootstrap.clj`, `src/seon/schedule.clj` and six schema resources.
They also differ in worker count: `--all` runs a five-worker pool while an
explicit selection runs one, so a hazard between two namespaces landing in
DIFFERENT workers has no reproduction in any selection tried here.

An attribution is a hypothesis until a probe confirms it, so none is made.

## Acceptance criteria

- One `bin/test --all` at a frozen tree, with a named cause for each of the
  thirteen, or a green result showing they were a property of the tree at the
  moment they were measured.
- `parallel-only` back to its previous floor, or each survivor explained: a
  red that depends on scheduling attributes to the wrong owner and the
  confirmation verdict is the only thing that says so.

## 2026-09-08 (`test-harness`) — a named cause, awaiting its `--all`

**The confirmation phase was not reproducing the pool worker's world.**
`seon.test.runner/confirm-parallel-failure!` initialized its worker with ONE
namespace — `[(symbol (::task-namespace task))]` — while every pool worker is
initialized with the WHOLE selection (147 namespaces under `--all`). A test
whose subject depends on what is LOADED — the program graph, the acquired SCI
ctx's bindings, which capability namespaces resolve in the ctx — therefore
answered a DIFFERENT QUESTION in the confirmation than in the pool.
`parallel-only` meant "green in a smaller world", which is not evidence about
scheduling at all.

This fits every fact this note already records:

- The thirteen names are exactly the load-sensitive kind —
  `every-public-capability-function-in-the-graph-resolves-in-the-ctx`,
  `bare-dir-and-program-derived-doc-are-repl-native`,
  `compiled-runtime-roots-cannot-be-redefined-by-agent-code`,
  `a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed`.
- "The suite alone" ruled nothing out: `bin/test seon.sci.eval-test` loads ONE
  namespace in both the pool and the confirmation, so the two worlds agree and
  no `parallel-only` can be produced by this cause.
- Likewise the three-namespace selection: three in both, worlds agree, zero
  `parallel-only`.
- `--all` runs a five-worker pool over 147 loaded namespaces against
  one-namespace confirmations — the only configuration tried where the two
  worlds differ, and the only one that produced the class.

**Fix.** The confirmation worker now loads the same namespace set the pool
worker loaded, leaving exactly ONE difference: the task runs alone. Regression:
`seon.test-runner-test/a-confirmation-loads-the-pool-workers-world`.

**Second half — the verdict now names a suspect.** Nothing declared which
state is shared between tasks in a pooled worker, so the worker now DERIVES it:
`ambient-snapshot` is taken either side of every task and the difference is
reported as that task's own fact (instrumented wrappers, malli's function
schema registry, running clusters, and the shared test SCI base ctx). A red
task carries the earlier tasks in its worker that changed any of it, and a
`parallel-only` line prints them — or says explicitly that nothing ambient
changed before it.

**Still open** until this note's own acceptance criterion is met: one
`bin/test --all` at a frozen tree with `parallel-only` back at its floor, or
each survivor explained. If the thirteen persist, the detector's known blind
spot is the next place to look: the SCI-base member counts vars per namespace,
so it sees a `def` into the base and does NOT see METADATA mutation of a shared
SCI Var — and `agent-owned-sci-var-metadata-remains-mutable` and
`compiled-runtime-metadata-cannot-be-changed-by-agent-code` are exactly that
shape.

## Resolved 2026-09-08 (`test-harness`) — the cause, named by the mechanism

The `bin/test --all` this note's acceptance criteria asked for was run at a
frozen worktree, with the runner now measuring the process-global state each
task leaves behind. **Eleven `seon.sci.eval-test` `parallel-only` verdicts, and
every single one names the same two leakers:**

```text
bin/test: confirmation parallel-only seon.sci.eval-test/… worker=pool-1
bin/test:   suspected leakers, earlier in worker pool-1 —
bin/test:     seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence
                [:seon.test.runner/snapshot-instrumented]
bin/test:     seon.db-test/instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary
                [:seon.test.runner/snapshot-instrumented]
```

Both strip contract wrappers from the pooled worker and never put them back —
`seon.db-test` removes **926** — so every later task in that worker asserts
those tests' timing instead of its own subject. That is the whole class, and
it fits every fact this note already recorded: `bin/test seon.sci.eval-test`
and the three-namespace selection could not reproduce it because neither
selection SCHEDULES those namespaces into the same worker.

Two things were wrong, and both are fixed:

1. **The confirmation was not reproducing the pool worker's world.** It
   initialized its worker with ONE namespace while every pool worker holds the
   whole selection, so a test whose subject depends on what is LOADED answered
   a different question there. It now loads the same set; regression
   `seon.test-runner-test/a-confirmation-loads-the-pool-workers-world`.
2. **Nothing measured what a task left behind.** The worker now snapshots its
   process-global state either side of every task and a red carries the
   earlier tasks in its worker that changed any of it; regression
   `seon.test-runner-test/a-task-that-changes-worker-global-state-is-named-as-the-leaker`.

`seon.db-test`'s strip is **fixed** (it was in the lane's owned paths and now
restores its entering wrappers). `seon.cluster.agent-test`'s belongs to another
lane and is tracked, with the exact hunk, in
[a-platform-test-leaves-its-worker-stripped-of-every-contract](a-platform-test-leaves-its-worker-stripped-of-every-contract.md),
which is where the remaining members of the class live.

Landing note:
[test-harness](../../prds/context-generation/research/test-harness-landing-2026-09-08.md) §2.
