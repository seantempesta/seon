---
type: prd
status: active
tags: [prd, database, agent]
---

# Async-facade completion PRD

## Problem

`seon.db` is now the asynchronous authority session: `db`, `query`, `entity`,
`pull` return Promises. Callers written against the removed synchronous
facade still treat returned Promises as values. Consequences range from
always-true predicates to Promise objects rendered into agent context.
Evidence:
[[../database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]].

Closed already: `seon.runtime.recovery` (commit `a2b0c815`; `later-run?` was
`(boolean <Promise>)`, unconditionally true).

Remaining inventory (file:line in the audit):

| Consumer | Sites | Character |
|---|---|---|
| `seon.warn` | ~15 across the check registry | worst remaining; also carries a dual acquisition path (pre-acquired `::data` branch vs direct-query fallback) and `warn.cljs:1064` guidance naming the removed `seon.db/*conn*` |
| `seon.eval` | 7 clusters (752, 875, 883, 1640, 2832-2852, 2960) | inside the eval owner; coordinate with any lane touching eval |
| `seon.render` | 684 | one site |
| `seon.agent.testrun` | 192, 205 | capability fn |
| `seon.agent.web.internal` | 528-536 | capability fn |
| `seon.handlers.message` | 43 | one site |
| `my.skills` | 324-331 | toolkit |
| `my.canvas` (pinned) | 149-153 | `state` already migrated; `pinned` missed |

## Recommended solution

Fix each consumer in place to the standard idiom (`^:async` fns + `await`,
errors as `:seon/error` values, one acquired database value threaded through
related reads). While inside `seon.warn`, collapse the dual acquisition path
to the pre-acquired `::data` branch and rewrite the `*conn*` guidance —
same-owner work, one commit series. Recovery's commit is the template.

Order: `seon.warn` (worst, self-contained) → `seon.eval` clusters →
the five one-site fixes (mechanical, one commit) → re-run the audit scan to
zero.

## Acceptance

Full `bin/test-cljs`; audit inventory re-scan returns zero sync reads; live
cluster proof that a warn check, a render, and an eval round-trip through the
authority (recovery-style `.then` probe or post-B7 `await`).

## Open questions

None — the idiom is settled; this is execution.
