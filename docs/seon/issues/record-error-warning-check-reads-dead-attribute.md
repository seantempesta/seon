---
type: issue
status: open
severity: minor
tags: [issue, pod, database]
---

# record-error warning check reads a dead attribute

## Problem

`seon.warn/check-record-errors` (`src/seon/warn.cljs:859`) and the warnings
context block (`src/seon/agent/ctx/warnings.cljs:86`) query
`:seon.eval/record-error`, but nothing writes that attribute anymore. Commit
`346e70fa` ("Keep eval publication atomic", 2026-07-17) deliberately deleted
`record-eval!`'s two-stage fallback that stamped it, along with its
`schema/register!`. The check can never fire; it is dead residue whose
docstring ("Stamped … by seon.eval/record-eval!") is now false. A comment in
`src/seon/repair.cljc:102` also cites the removed precedent.

## Evidence

- `rg 'record-error' src/` — only readers remain (warn.cljs,
  ctx/warnings.cljs, a repair.cljc comment); no writer, no registration.
- `git show 346e70fa -- src/seon/eval.cljs` shows the stamp and
  `(schema/register! :seon.eval/record-error :string)` removal.
- Post-atomicity, `record-eval!` returns the whole failure as a
  `:seon.error` value and (as of the seon.log routing unit, 2026-07-20)
  records it via `seon.error/record!` — the partial-record class the check
  guarded against no longer exists.

## Owner

`seon.warn` owns the check registry; `seon.agent.ctx.warnings` owns the
context block's query list.

## Acceptance

`check-record-errors` and its ctx/warnings query row are deleted (or
redesigned against a real current failure class), the check registry entry at
`warn.cljs:1036` removed, the stale repair.cljc comment updated, and
`bin/test-cljs` stays green with no other reader of
`:seon.eval/record-error` remaining.
