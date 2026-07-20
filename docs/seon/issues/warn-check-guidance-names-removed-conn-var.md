---
type: issue
status: open
tags: [issue, agent, database, web]
severity: friction
---

# Warn check guidance names removed `seon.db/*conn*` var

## Observed

`src/seon/warn.cljs:1064` (`check-error-cluster`) renders this agent-facing
repair example when any warn check throws:

```clojure
"(" nm " {:seon.db/db (deref seon.db/*conn*)})"
```

`seon.db/*conn*` was removed with the local-connection cut; the asynchronous
authority facade has no such var. An agent following this guidance fails.
`test/seon/ctx_test.cljs:87` guards only `ctx/system-text`, so this
warn-generated string escapes the existing regression.

Related, same owner: every `seon.warn` check still consumes the async
`seon.db/query`/`installed-schema` Promises synchronously through its
direct-query fallback branches (`(or (::data ...) (db/query ...))`); the
pre-acquired `::data` injection path is the surviving mechanism. Full site
list: [[../../prds/database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]]
section 2. That audit also found `seon.runtime.recovery/later-run?`
(`src/seon/runtime/recovery.cljs:595`) returns `(boolean <Promise>)` — always
true — corrupting recovery-notice derivation.

## Acceptance

- The warn-check error example shows the current async idiom (value acquired
  via `await (db/db)` upstream, passed as `:seon.db/db`).
- `seon.warn` has one acquisition path (pre-acquired `::data`); the
  synchronous direct-query fallbacks are deleted or awaited.
- A regression covers warn-generated guidance text for removed vars, not only
  `ctx/system-text`.
