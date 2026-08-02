---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, eval, agent, database]
---

# State live definitions left behind by a failed evaluation

## Problem

A form can mutate the live cluster context with `def` and then throw. The
definition remains callable until process loss, but the failure return carries
no session-image delta. A cold cluster silently loses the name instead of
restoring it or durably stating why it is absent.

## Evidence

- `src/seon/sci/eval.clj:1262-1277` executes a non-declaration form directly
  against the live context.
- `src/seon/sci/eval.clj:1315-1338` computes `changed-session-defs` only after
  evaluation returns successfully.
- The catch path at `src/seon/sci/eval.clj:1342-1365` returns the admitted
  error and diagnostics but never diffs the live intern roots.
- The independent disposable-context probe
  `tmp/adversarial-wave2-probe.clj` evaluated
  `(do (def silent-drop (fn [] 9)) (throw (ex-info "boom" {})))`. It returned
  `:seon.sci.eval/evaluation-failed`; `silent-drop` resolved in the same live
  context, while `:seon.sci.eval/session-defs` was absent.
- Because `src/seon/cluster/loop.cljc:394-406` can transact only the supplied
  session rows, the terminal failure transaction contains neither a source,
  a faithful value, nor an `unrestorable` statement for `silent-drop`.

## Owner

The evaluation result boundary that snapshots live SCI intern changes for the
terminal session-image transaction.

## Acceptance

- Every evaluation outcome diffs live intern roots after SCI has stopped
  running, including throw and time-limit outcomes.
- Each changed name reaches the terminal transaction as a faithful value,
  replay-proven source, deletion, or explicit `unrestorable` fact.
- A recurring process-loss regression proves that a definition executed
  before a later throw is either faithfully restored or explicitly absent;
  silent disappearance is impossible.

## Resolved 2026-08-01

Resolved by `d57f5977c`. The evaluator snapshots the live intern delta from
the caught failure after SCI stops running, and the terminal transaction uses
the same session-image path as a successful evaluation. The recurring
`failed-evaluation-delta-restores-values-but-never-replays-source` and
`time-limited-evaluation-reports-its-earlier-def-delta` tests in
`test/seon/sci/session_image_test.clj` prove faithful restore and explicit
unrestorable state across a fresh context.
