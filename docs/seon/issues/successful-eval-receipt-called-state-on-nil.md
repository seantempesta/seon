---
type: issue
status: open
severity: blocker
tags: [issue, agent, cljs]
---

# Skip the receipt reread branch after a successful eval write

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — U9 deletion.** The failing child-side
`eval.cljs/record-eval!` path is removed by U9.

## Problem

`record-eval!` reads an existing receipt only when terminalizing an eval fails.
After a normal successful write that read is nil, but the adjacent status
binding still calls `receipt-state` because nil has no error message.
Instrumentation correctly rejects nil where an eval-row map is required.

## Evidence

A real agent successfully committed its first eval, then the turn closed with
Malli invalid input at `seon.eval.internal/receipt-state [nil]`. Final evidence
contained that one successful eval and no later forms.

## Owner

`seon.eval/record-eval!` owns the optional post-failure receipt read and status
derivation.

## Acceptance

- Receipt state is derived only when the optional read actually occurred and
  succeeded.
- Existing successful and failed receipt tests remain green.
- A multi-form real agent reply advances past its first successful eval.

## Triage — 2026-07-23

DISSOLVES into the reconciled cutover unit that deletes the `eval.cljs`
self-host and its child-side `record-eval!` path.
