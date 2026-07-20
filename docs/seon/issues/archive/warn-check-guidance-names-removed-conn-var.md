---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database, web]
---

# Warn check guidance names removed `seon.db/*conn*` var

## Observed

`src/seon/warn.cljs:1064` (`check-error-cluster`) rendered this agent-facing
repair example when any warn check threw:

```clojure
"(" nm " {:seon.db/db (deref seon.db/*conn*)})"
```

`seon.db/*conn*` was removed with the local-connection cut; the asynchronous
authority facade has no such var. An agent following this guidance failed.
`test/seon/ctx_test.cljs:87` guarded only `ctx/system-text`, so this
warn-generated string escaped the existing regression.

Related, same owner: every `seon.warn` check still consumed the async
`seon.db/query`/`installed-schema` Promises synchronously through its
direct-query fallback branches (`(or (::data ...) (db/query ...))`); the
pre-acquired `::data` injection path is the surviving mechanism. Full site
list: [[../../../prds/database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]]
section 2.

## Resolution — commit `0887b1ea` (2026-07-20)

- `seon.warn` now has ONE acquisition path: every `check-<kind>` is pure
  over the pre-acquired `:seon.warn/data` map supplied by
  `seon.agent.ctx.warnings` (the sole acquisition owner). All ~15 direct
  async-facade fallback reads are deleted; `:seon.db/db` is dropped from
  `::check-request` and `::data` is required. No `^:async` can reach
  `run-checks`.
- The `check-error-cluster` repro string teaches the pure current idiom:
  `(<check> {:seon.warn/data {}})` — no database read, no removed var.
- The wider `:seon.warn/example` audit (14 sites, recorded in the commit
  message) also fixed `check-bad-ref`'s broken
  `(def eid (ffirst (seon.db/query ...)))` composition (two top-level
  forms under whole-form auto-await) and `check-failed-evals`'
  stale `(result :<eval-id>)` call (result vars bind only for successful
  evals; now a top-level `seon.db/pull` of the eval row).
- Regression: `seon.warn-test/guidance-examples-teach-current-idioms`
  asserts no example names `*conn*` and no example composes a db read
  inside another call; the throwing-check test additionally pins the pure
  `:seon.warn/data` repro text. Full `bin/test-cljs` green
  (1290 tests / 5881 assertions / 0 failures).
- `seon.runtime.recovery/later-run?` (also cited here) was closed
  separately in `a2b0c815`.
