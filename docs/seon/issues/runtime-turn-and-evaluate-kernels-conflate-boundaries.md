---
type: issue
status: open
severity: friction
tags: [issue, runtime, sci, wave/runtime-boundary-refactor]
---

# Split the turn and evaluation kernels at durable boundaries

## Problem

The two central runtime functions each own several independently testable
mechanisms inside one deeply nested body. Their size makes local edits depend
on distant branches and obscures which construction owns a refusal, retry,
session delta, or terminal transaction.

## Evidence

`src/seon/cluster/loop.cljc:899-1521` defines `turn` as 623 lines. Its single
`case` owns open/claim, prompt derivation, provider retries, reply parsing,
plan freezing, form reduction, refusal settlement, release, and close.

`src/seon/sci/eval.clj:1230-1505` defines `evaluate` as 276 lines in
the current working snapshot. It owns context selection, interrupt arming,
reader events, SCI analysis/evaluation, session-definition capture, schema
publication, value admission, printing, error normalization, and disarming.

Several open issues already land at branches inside these functions
(`failed-eval-definitions-have-no-session-image-delta.md`,
`sci-evaluate-throws-when-a-guarded-context-is-re-armed.md`, and
`terminal-refusal-error-fact-fails-on-oversized-data.md`). That concentration
is the refactor signal; another conditional at either site is not a class fix.

## Progress 2026-08-03 (`04fe5f247`, `db0d78368`) — the `evaluate` half, partly

Arming, the deadline, the interrupt question, and error normalization are no
longer inside `evaluate`: `seon.sci.kernel` owns them for BOTH guarded
entrances (`evaluate` for a form, `kernel/invoke` for a named live Var, which
is how every renderer runs). `evaluate` lost its arm re-keying wrapper, its
`failure-value` copy, its `invoke` pass-through, and its `interrupted?`
pass-through. `sci-evaluate-throws-when-a-guarded-context-is-re-armed.md`,
named above as part of the concentration signal, is resolved and archived.

Still open here: the reader, session-delta, schema-publication, and admission
boundaries inside `evaluate` remain inline, and `turn` is untouched — that
half was outside the kernel-merge lane's owned paths.

## Owner

The existing `seon.cluster.loop/turn` and `seon.sci.eval/evaluate` mechanisms,
kept as the only orchestration entries.

## Acceptance

- `turn` is a thin dispatch over named pure transaction/value planners for its
  existing durable boundaries; provider I/O and database commits remain at
  their one established leaves.
- `evaluate` is a thin guarded lifecycle over named reader, evaluation,
  session-delta, admission, and normalization transformations.
- No second loop, compatibility namespace, mutable state machine, or stored
  phase enum is introduced.
- Existing state-transition properties and live turn evidence pass unchanged,
  and each extracted boundary has one direct falsifier.
