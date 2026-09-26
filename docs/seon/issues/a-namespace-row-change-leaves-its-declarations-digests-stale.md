---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, digest, namespace, merge, write-back, writer]
---

# A namespace row change leaves its declarations' stored digests stale

A function or test digest includes its namespace's resolver context
(`seon.db/definition-digest-of`, `src/seon/db.clj:4301`; `seon.fn/stored-namespace-context`,
`src/seon/fn.clj:275`). When an agent evaluates an `ns` form that changes the requires,
aliases, refers or imports, the namespace row is replaced but no other row of that namespace
is re-digested, and the writer does not notice: `write-digest-error` (`src/seon/db.clj:4317`)
checks only function and test rows the transaction touched.

Reproduced 2026-09-26 (lane merge-ns-rows-opus, default pid 27723, MCP branch
`merge-ns-rows-opus`): evaluating `my.plan-test`'s own `ns` form plus `[my.plan]` changed the
namespace digest (`52cc108f…` → `2a3dca57…`), and all 31 declarations of the namespace kept
their stored digests (23 tests, 8 functions). `definition-digest-of` of
`my.plan-test/current-refuses-an-unknown-agent-by-name` under the new row is `dbf5ea50…`;
the store holds `a618c951…`. Splicing the new `ns` form into `test/my/plan_test.clj` and
reindexing produced, for all 31, exactly the digest the writer's own reader
(`declaration-value`) derives under the new namespace row (31/31 equal), and none of the
stored ones.

Consequences: changed-program detection misses every declaration whose meaning the require
change altered; write-back's equivalence proof refuses `:not-equivalent` naming the whole
namespace; test reuse keyed by those digests trusts evidence for an older resolver context.

Wanted: the transaction that changes a namespace row's resolver context leaves every
declaration of that namespace with the digest its definition produces (derived at the
writer's reader, `declaration-value` + `definition-digest-of`), and the writer refuses a
report that does not. Options and prices: landing
`docs/research/agent-platform/landing/merge-ns-rows-2026-09-26.md`.
