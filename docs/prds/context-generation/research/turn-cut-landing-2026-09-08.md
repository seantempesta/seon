---
type: research
status: incomplete
date: 2026-09-08
tags: [research, runtime, sci]
---

# Turn-cut: incomplete landing

No requested deletion row is complete. Work stopped at development SCI
acquisition of an independently installed function; see
[the exact acquisition boundary](../../../seon/issues/uncontracted-live-function-blocks-development-acquisition.md).

## Authority and amendments

Read the supplied AGENTS.md and the turn-loop PRD r11 end to end, including
the later §12 amendments; read run-loop-unpacked §5.6 and the listened-
attributes landing note. Applied the data-oriented-clojure, datahike,
clojure-testing, and repl skills. The final location ruling is main-root
`default`. No reset-request lines apply. Private data and atoms must be
objects carried by agent proc state; each turn forks the current shared
base. All verification must use virtual replies, without a provider request.

## What was changed

The shared-function probe exposed an existing installation defect:
`gate-function-install` reads the case count from a handle which does not
carry it. The database config has 25; the handle has no value. The reader
now uses configuration facts from the held database value. A regression
configures three cases and asserts the installation, closure, and recorded
case count with the handle copy removed. This is a prerequisite repair,
not completion of row 5. Its [issue remains open](../../../seon/issues/function-install-case-count-is-read-from-an-absent-handle-key.md)
until verification completes.

The reproducible [ordinary-proc probe](turn_cut_probe_2026_09_08.clj) uses
two new agents, source submissions, the real SCI evaluator and database,
and a fixed reply at `seon.ai/complete`. Its first source turn opened but
did not close within the 20-second event bound. The cross-agent call was
therefore not reached. The answer to whether shared installation works
today is **not proven**, not yes and not no.

## Measured observations

Both MCP tools called without root or cluster arguments selected main-root
`default`; `eval_clj` reported only `default` in running instances. No MCP
default repair was needed on the observed system.

A disposable SCI dependency probe returned `{:a 2 :b false :base false}`:
an intern in one fork was absent from its sibling and base. This proves
only SCI fork behavior, not private-state persistence through turns.

The first baseline gate, five explicit namespaces, was interrupted before a
tally was available. The later `bin/test seon.cluster.turn-test` captured
the working tree, selected 60 tests, and was stopped after the shared-state
boundary was identified. No green tally is claimed. Bare and platform gates
remain unrun for the repair. New versus inherited test failures are unknown;
one pre-edit installation fault was reproduced live. Datoms per completed
virtual turn and byte identity remain unmeasured.

## Literal reference inventory

Dated inventory before the deletion work; counts are literal occurrences
in `.clj`, `.cljc`, and `.edn` files, not alias-expanded semantic references.
No deletion row changed these counts.

| literal | src before/after | resources before/after | test before/after |
|---|---:|---:|---:|
| `:seon.cluster.work/situation` | 25/25 | 10/10 | 56/56 |
| `:seon.cluster.run/process` | 77/77 | 29/29 | 109/109 |
| `:seon.cluster.agent/run` | 23/23 | 2/2 | 32/32 |
| `:seon.cluster.run/trigger` | 14/14 | 8/8 | 37/37 |
| `:seon.cluster.run/opening-commit-id` | 6/6 | 4/4 | 3/3 |
| `:seon.cluster.run/plan-digest` | 11/11 | 6/6 | 28/28 |
| `:seon.cluster.run/undisposed-at` | 5/5 | 2/2 | 1/1 |
| `:seon.context.capture` | 13/13 | 11/11 | 21/21 |
| `:seon.context.contribution` | 69/69 | 35/35 | 54/54 |
| `:seon.def/` | 77/77 | 39/39 | 113/113 |

## Remaining work

All seven cuts, their real-harness regressions, the three-write proof,
private data/atom/handle isolation, shared installation, boot interruption,
byte identity, bootstrap's configured Juniper fixture, and the final rename
remain unfinished. The function-capture reconstruction question was raised
under AGENTS.md §2.5: deleting stored SCI roots affects cold acquisition of
installed closures as well as private data. No capture policy was changed.

Unrelated concurrent edits to `src/seon/repl.clj`, its schema, and its test
were preserved. The lane's source changes are limited to
`src/seon/cluster/loop.clj` and `test/seon/cluster/turn_test.clj`, plus this
note, its probe, and the two linked issues.

The explicit gate exited 143 after TERM; its coordinator required the
runner's reap backstop and exited 137. The new regression reached its END
event in 4,898 ms, but no completed tally is available and that event alone
is not a pass claim. No lane-owned background shell or JVM remains. The
obsolete scratch root and both interrupted isolated test roots were removed
after the process-table and lane-status checks found no holders.
