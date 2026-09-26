---
type: issue
status: open
severity: friction
tags: [issue, test-harness, performance, config]
---

# Test-world preparation spends seconds in config apply and unmemoized reads

## Problem

A test request's world is a checkpoint branch (`seon.test/clean-request-handle`,
commit 41f2f0b53). Its first preparation writes boot's cluster facts through
`seon.cluster/seed-cluster-facts!`. Measured on default (pid 39156,
2026-09-24), one preparation took 1,700 ms and 1,807 ms. Of that,
`seon.config/apply-compiled!` took about 780–920 ms, most of it in
`seon.reconcile/plan`, and four `seon.db/transact!` calls took 1,166 ms.
The config being applied is the cluster's own effective configuration, onto
a published commit that holds no config row.

Before 41f2f0b53 (S2a, fe5dcc072) the same writes ran on every request. The
S2a landing estimated them at "tens of ms"; the measurement is two orders
larger. The checkpoint now pays them once per content identity. A new base,
overlay, config or process still pays them again.

Two reads the overlay (`seon.test/program-overlay-tx`) depends on are also
slow for what they return:

- `seon.program/program-attributes` recomputes from the projection on each
  call: 50–68 ms warm, never memoized by projection identity.
- `seon.db/datoms` over the 10,779 `:seon.program/definition-digest` datoms
  took 28–44 ms. `seon.db/q` over the same range took 0.5 ms, and raw
  Datahike took 2 ms. The overlay uses `q` for that reason.

## Update (lane harness-s2-overlay-s2c, after the two-level world)

The checkpoint now holds boot's facts only, keyed by base, cluster, effective
config and process, so the config apply runs once per such identity (measured
950 ms). The overlay is written per request onto the request's private branch.
That one 15-datom transaction measured 935 ms and 896 ms, of which
`seon.db/write-report-error` took 834 ms over six writes. Each write is on a
fresh branch connection whose projection was just derived
(`schema/projection-from-database`), so per-projection plans such as
`write-attribute-plans` are presumably rebuilt on every write. Unverified: name
the cache key before fixing.

## Wanted

- A first preparation under one second. Name which part of
  `seon.config/apply-compiled!` is proportional to the whole config, and
  remove it.
- `seon.program/program-attributes` memoized as a function of its projection.
- The checked writer's final check under one second on a fresh branch connection
  (`seon.db/write-report-error`).
- `seon.db/datoms` output checking that does not cost 20× the read.

## Evidence

Profile lines from the preparation probe: `seon.config/apply-compiled!`
778 ms x1, `seon.reconcile/plan` 622 ms x2, `seon.db/transact!` 1,166 ms x4.
Overlay probe: `seon.program/program-attributes` 50.9 ms, `seon.db/datoms`
30.5 ms, `seon.fn/published-index-rows` 52.8 ms for two identities.

## Evidence 2026-09-26 (dogfood-repl-opus): a one-test `my.test/check` from a branch agent spends 1.6 s outside its member

Default archive 10d6fdff5 (pid 39759), hosted agent on branch `dogfood-repl-opus-7`, one REPL test
(`user/word-count-ignores-surrounding-and-repeated-whitespace`, 3 assertions), measured with the agent's
own `(seon.profile/begin)` / `(seon.profile/explain m 14)`:

```
1736 ms elapsed.
1736 ms in my.test/check x1 · 1719 ms in seon.test/run x1
1119 ms in seon.db/with-declarations x13680
 981 ms in seon.test/clean-request-handle x1
 865 ms in seon.db/q x84 · 772 ms in seon.db/transact! x3
 492 ms in seon.schema/call-with-projection x7 · 427 ms in seon.sci.eval/acquire! x2
 345 ms in seon.test/changed-definition-symbols x2 · 284 ms in seon.test/select x1
```

The member itself was acquire 35 ms + resolve 3 ms + run 24 ms + release 10 ms. Three consecutive identical
requests on an unchanged program took 1,555, 1,730 and 1,720 ms, each with `:seon.test/reused-count 0`: green
evidence for an unchanged program and test was never reused, and `clean-request-handle` paid its ~1 s each
time. `seon.db/with-declarations` ran 13,680 times for one member. Its cost is proportional to something
other than the change (the program? the selection?), which the explanation cannot say.
