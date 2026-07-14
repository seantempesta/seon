---
type: issue
status: open
severity: cleanup
tags: [issue, agent, database]
---

# eval/transact on a non-primary (scratch) conn returns ok? but doesn't commit

## Problem

A `seon.db/transact!` issued via the `seon.eval` / `seval` eval path against a
NON-primary (scratch) connection returns `{:seon.db/ok? true}` yet the write
never lands. The active `*conn*` binding is LOST across the `cljs.js` await
boundary (the bootstrap-CLJS eval engine), so the transact resolves against a
default/dropped conn — the caller sees `ok?` but the scratch DB is unchanged.
Silent data loss for scratch-conn workflows (e.g. a drive harness on a fresh
scratch agent).

## Where

- `seon.eval` / `seval` eval path — the `cljs.js` await boundary where `*conn*`
  is rebound and then lost.
- `seon.db/transact!` — returns `ok?` from the resolved conn, not the intended
  scratch conn.

## Acceptance Criteria

- A transact issued under a scratch `*conn*` via eval either commits to THAT
  conn or returns a loud error — never `ok?`-without-commit.
- Regression test: bind a scratch conn, transact via eval, read it back on the
  SAME conn and confirm the datom is present.

## Related

- [[concepts/reactive-context]]
