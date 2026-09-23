---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, testing, performance, call-preparation]
---

# call_preparation_test rebuilds a declaration projection per call

`seon.call-preparation-test/projection` calls
`(schema/declaration-projection (assoc (schema/declaration-population) :sample/marker :int))`
every time it is called. Many tests call it more than once (`environment-for`,
`probe-ctx`, the `cp/snapshot` sites). Each call builds a complete projection.

Observed by lane arity-snapshot on 2026-09-23 on default pid 24492 (armed,
HEAD `ad41853a0`). Running the namespace's 20 tests as a scratch copy took
about 39 s per copy. The profile shows 55 `seon.schema/declaration-projection`
calls totalling 18.8 s across two copies, which is about 340 ms each.

The rest is fixture branch acquisition under armed contracts. Individual tests
take 0.3-2.9 s. That exceeds the five-second default bound only in aggregate,
but it makes the namespace a 40 s operation.

Wanted:

- The test derives its projection once per fixture body and passes it
  explicitly, which is the "values carry their world" law.
- The projection is keyed by the population it is built from, so a memo that
  depends on it (for example `seon.call-preparation`'s per-projection
  snapshot memo) is not defeated by a fresh object per call.

Evidence: `docs/prds/agent-platform/landing/lane-arity-snapshot-2026-09-23.md`
(TIMINGS).
