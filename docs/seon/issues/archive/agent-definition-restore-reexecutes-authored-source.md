---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, agent, sci, database]
---

# Restore agent definitions without re-executing authored source

## Problem

The agent's definitions are durable facts, but two restore paths reconstruct
function roots by evaluating the original agent-authored form. A later turn or
a cold process can therefore run authored code again even though the form
already has a settled receipt. This violates the ruled append-only REPL-history
model: agent-authored forms execute once and later forks read their results.

## Evidence

- `src/seon/cluster/loop.clj:220-305` deliberately prefers
  `:seon.def/source` over a store-faithful value for a successful definition.
- `src/seon/sci/eval.clj:1421-1503` queries those desk rows for every turn and
  calls `sci/eval-form` when a row has source.
- `test/seon/sci/desk_test.clj:232-329` pins both behaviors: a fresh turn
  restores `(def helper (fn ...))` from source, and the restore ladder asserts
  that an ordinary definition retains source while dropping its admitted
  value.
- `src/seon/sci/eval.clj:1367-1400` also calls `sci/eval-form` to restore an
  agent-authored contracted function from its durable program row after cold
  acquisition.
- SCI's fork is already copy-on-write: it creates a new generation over the
  inherited namespace map, and a later root mutation copies the inherited Var
  before binding it (`reference-code/sci/src/sci/core.cljc:344-350`;
  `reference-code/sci/src/sci/impl/utils.cljc:356-379`). The missing mechanism
  is a durable, fact-safe Var-root representation and native installer, not
  another interpreter context.

The ruled design and proposed owner are recorded in
`docs/prds/sci-execution-runtime/research/env-once-execution-design-2026-08-11.md`.

## Owner

The one definition settlement and SCI namespace-root installation mechanism
shared by `seon.cluster.loop`, `seon.sci.eval`, and the maintained SCI fork.

## Acceptance

- A side-effecting or nondeterministic wrapper around a returned function is
  observed exactly once, at the original receipt, across later turns and a
  cold JVM restart.
- Fresh forks install faithful values, atom snapshots, and supported function
  roots from database facts without calling `sci/eval-form` on the authored
  source.
- Unsupported roots settle with one flat, explicit unrestorable reason; no
  source-replay fallback exists.
- One recurring restart regression covers both an agent's desk definition and
  an agent-authored contracted function.

## W1 Lane C progress — 2026-08-11

Implementation commit `9623a26d6` removes both source-replay restore arms.
Ordinary roots and fresh atom snapshots install directly from `:seon.def`
facts; function roots use SCI's native fact-safe projection and installation
seam. The maintained SCI fork commit is
`fcbd8862800e638dc0f8f5521111f999279cbcd2`.

Verified evidence:

- SCI's focused root-data regression passes with 5 assertions, and
  `script/test/jvm sci.namespaces-test` passes on Clojure 1.10.3 and 1.11.1
  with 41 tests and 165 assertions on each version.
- The Seon zero-`sci/eval-form`, fresh-atom, flat-unrestorable, and cold
  contracted-function regressions pass when selected directly.
- A load-only proof of `seon.sci.eval` and `seon.cluster.loop` exits zero.
- The committed fork benchmark was run before and after on OpenJDK 26.0.1.
  At 10 aliases/10 definitions, fact installation was 21.416 µs median and
  49.625 µs p95 before, versus 24.084 µs and 74.292 µs after; source replay
  was 343.792 µs and 1239.458 µs before, versus 290.833 µs and 1093.917 µs
  after. At 25/50, fact installation was 78.5/109.625 µs before and
  59.208/91.166 µs after, while source replay was 1371.541/1829.666 µs before
  and 1148.0/1704.959 µs after. These are median/p95 measurements from
  [the committed benchmark](../../prds/sci-execution-runtime/research/env-once-fork-rehydration-benchmark.clj).

## Resolution — 2026-08-11

W1 integration removed the schema-population blocker and strengthened the
recurring restart proof so its `sci/eval-form` counter spans both cold
`cluster-ctx` acquisition and `fork-for-turn`. The writer now settles an
agent-authored contracted function alongside the ordinary desk function; the
reader's second JVM restores both from facts, observes results `5` and `42`,
and reports zero authored-form evaluations.

`bin/test seon.cluster.turn-test seon.cluster.loop-test
seon.cluster.run-test seon.sci.eval-test seon.sci.desk-test
seon.render.history-test` ran the cold proof in 89.681 seconds. The writer was
forcibly killed, the separate reader JVM reopened the database, both function
roots and the atom snapshot restored from facts, the explicit unrestorable
value remained flat, and clearing left no desk facts. No source-replay
fallback remains in either cold acquisition or turn rehydration.
