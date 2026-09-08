---
type: research
status: active
tags: [research, operator, performance, test]
---

# Hook publication coalescing — unfinished checkpoint, 2026-09-08

The owner resumed this assignment with the cohosted-cluster defect after
the initial stop below. The new guard selects the named running instance
without requiring a single-instance JVM. A regression publishes a real
SCI probe function, starts default and beta, changes its body, adopts onto
default, and checks both clusters' source commits and SCI behavior. This
does not claim that shared host JVM Vars can have different definitions
per cluster; the regression targets independent program facts and SCI
contexts. The issue's additional browser acceptance remains unverified.

This is an unverified implementation checkpoint, not a completed landing.
The assignment's explicit foreign-gate-breakage stop rule fired. No green
platform gate, successful live adoption, or before/after convergence timing
is claimed.

## Read authorities and dependency ledger

Read `AGENTS.md` end to end, including the default-cluster and hook
paragraphs; the turn PRD section 10 end to end; `bin/seon-hook` end to end;
`.claude/seon-hook.edn` end to end; and the incremental publication and
development adoption path in `src/seon/cluster.clj`. Read all of
`src/seon/cluster/source.clj`.

- `bin/seon-hook` already uses a pending-state file lock, one admitted
  process, and snapshot consumption for Gemini review. The draft reuses
  that lock and extracts its worker launcher for both consumers.
- Babashka process waiting and destruction:
  `reference-code/babashka-process/src/babashka/process.cljc:116` and `:165`.
  Publication waits use its bounded dereference; editors tail one
  newline-terminated result per publication and reap their tail process.
- `src/seon/fn.clj:1390` classifies file changes. `reconcile-tx` at `:1931`
  and `index!` at `:2005` already replace exact program definitions while
  retaining identity. The draft uses that reconciliation on the existing
  source publication branch for same-identity metadata changes.
- `src/seon/cluster/source.clj` already publishes through a scratch branch
  and Datahike's expected-current-commit check; that remains the owner.
- Development adoption already derives namespaces from changed program
  identities and orders only those namespaces using `:seon.ns/requires`.
  No broader namespace reload was found by source inspection. Its reload
  loop was not changed; a live reload-count proof remains outstanding.

## Measurements before changes

On the inherited default JVM, `bin/seon status` printed lifecycle-lock
waits through **36,552 ms**. The holder was PID 54144, running an
`init --dev default --changed ...` operation admitted at
2026-09-08T19:38:06.564Z. This is a contention observation, not an
edit-to-convergence measurement.

MCP JVM mode answered `(+ 1 1)` in 1 ms. Reading the current source
snapshot and cached artifact took 469 ms. A subsequent changed-file
planner census took 817 ms. These are the MCP envelope's execution times.

The census compared the artifact at `data/clusters/build/current-src.edn`
with `current-source-snapshot`, then called `seon.fn/build-artifact` and
`seon.fn/plan-file-change` on each differing path. It sampled **three file
plans**, not historical publication attempts:

| File | Plan | Reasons |
|---|---|---|
| `src/seon/problems.clj` | complete rebuild | Removed and added identities; changed calls and source; cardinality-many/component changes and additions |
| `src/seon/render/web.clj` | complete rebuild | Changed `:seon.fn/keywords` and source; cardinality-many change |
| `test/seon/cluster/agent_namespace_test.clj` | incremental upsert | No rebuild reason |

Thus 2/3 sampled plans fell back, with one clearly structural edit and
one body-metadata edit. The prior incremental owner also rebuilt whenever
any unreported source path differed. No historical frequency is inferred
from this small sample. The draft adds progress lines naming publication
mode and rebuild reasons for a future actual-publication census.

## Draft changes

- Configurable `:current-source :quiet-seconds`, default 5, and
  `:timeout-seconds`, default 180.
- One source worker drains the union of pending paths after the quiet
  window. Edits during publication form the next batch. Every covered hook
  receives the same publication identity and terminal result file.
- Snapshot differences add missed paths to incremental analysis instead
  of triggering full analysis solely because the hook did not name them.
- Scalar changes retain the existing upsert path. Same-identity metadata
  changes reuse the updated manifest with the existing program reconciler.
  Identity, schema, contract, and analysis changes retain complete fallback.
- Tests were added/updated for five real hook invocations sharing one
  absent-JVM refusal, missed paths, and metadata edits avoiding complete
  analysis. These new assertions have **not run to a verdict**.
- The pre-existing schema-hook regression hit its 30-second subprocess
  bound twice. Its subprocess allowance was changed to 120 seconds;
  this adjustment is also unverified.

## Gate evidence and exact stop boundary

The baseline scoped gate used HEAD
`f7977c0e286ab0365dc6bc94ed79c50d87a0f7a3`, before these source edits.
It ran 19 tests and 112 assertions: zero assertion failures, one error.
`seon.dev.edit-feedback-test/split-schema-edits-run-admission-before-publication`
exceeded its 30-second subprocess deadline, reproduced in isolated
confirmation. There is no evidence attributing that timeout to another
lane. Its retained root was `tmp/test-runs/run.jbjTxv` at completion.

The implementation gate was:

```text
bin/test --paths bin/seon-hook .claude/seon-hook.edn src/seon/cluster/source.clj src/seon/cluster.clj test/seon/cluster/boot_test.clj test/seon/dev/edit_feedback_test.clj -- seon.dev.edit-feedback-test seon.cluster.source-test seon.cluster.boot-test
```

It overlaid the owned paths onto HEAD
`38d2fc91bdf42d944ead9e8d3998ca33cabc1415` and exited **before test loading**:

```text
Cannot open <nil> as a Reader.
(slurp (:seon.dev-cache/test-classpath-file selection))
```

Verified cause: the executing, uncommitted `bin/test` consumes the new
`:seon.dev-cache/test-classpath-file` field, while the isolated HEAD
snapshot's `dev_cache.clj` predates the uncommitted producer change. The
cache result printed digest, source-digest, namespace count, status, and
path, but no test-classpath-file. Both foreign files were dirty when
inspected. Neither was edited by this assignment. This is already recorded
in [the shared gate issue](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

The assignment required stopping at this boundary. No subsequent gate was
started until the owner explicitly resumed the assignment. No other lane
was messaged, resumed, or modified. Shared workers belonging to later
edit requests are not killed.

On resumption, the compatibility fix `f7aeaffbf` let the launcher consume
the older snapshot cache result. The same scoped gate was relaunched at
HEAD `e3efb12cc62af61b1e0f6081edb5ac46c641b198`, root
`tmp/test-runs/run.isvFwA`. Its snapshot took 3 seconds, dependency cache
and classpath preparation 58 seconds, and worker checkouts 2 seconds.
The new cohosted regression uses the now-required
`test-support/preserving-instrumentation-state`; its definition was still
an uncommitted foreign edit when that snapshot was taken. This dependency
was reported without copying or changing the foreign file.

That resumed gate exited 1 during shared-base analysis, before any test:
`test/seon/cluster/boot_test.clj:1037:8`,
`Unresolved var: test-support/preserving-instrumentation-state`.
The complete gate error was written to
`/var/folders/d6/78_m9wb92wg1f3qbt85s1r400000gn/T/clojure-4914760642410709072.edn`;
the exact diagnostic is preserved here because that system file is
disposable. The named helper is present in the dirty working-tree
`test/seon/test_support.clj` and absent in the gate's HEAD snapshot.
The assignment's stop rule fired again. No platform gate was started.
All owned shell sessions have returned.

## Live adoption and remaining work

Several new hook batches delivered explicit refusals to their callers.
Initially, both `default` and `beta` were registered in JVM PID 14049;
MCP confirmed the two names. The existing adoption guard requires the
development cluster to be the JVM's only running instance. `beta` was
left untouched.

A later publication reached analysis and refused the concurrent tree at
`src/seon/turn.clj:135:63`: `Unmatched bracket: unexpected )`. That file
was dirty and outside this assignment. It was not edited.

Remaining: review and run the draft regressions, obtain the scoped and
platform green gates, restart/adopt the new publication code under the
normal operator once the shared development environment permits it,
measure one editor and five editors touching five files within two seconds,
record actual publication fallback counts and reload counts, and verify
database/tree convergence plus browser responsiveness. Before/after
convergence timings are **not available**. Existing authority prose still
describes the old hook behavior and needs updating when this change is
verified and accepted.

## Files in this checkpoint

`bin/seon-hook`, `.claude/seon-hook.edn`, `src/seon/cluster/source.clj`,
`src/seon/cluster.clj` (publication helpers only),
`test/seon/cluster/boot_test.clj`, `test/seon/dev/edit_feedback_test.clj`,
and this note.
