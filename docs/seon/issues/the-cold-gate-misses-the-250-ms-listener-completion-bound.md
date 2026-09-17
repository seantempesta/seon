---
type: issue
status: open
severity: blocker
created: 2026-09-18
tags: [issue, testing, datahike, writer, bounded-execution, wave/contract-gate]
---

# The cold gate misses the 250 ms listener-completion bound that the fast loop meets

## Problem

`seon.db-test/a-throwing-datahike-listener-cannot-strand-a-committed-write`
(`test/seon/db_test.clj:1235`) applies
`{:seon.config.db/write-time-limit-ms 250}`, registers a throwing Datahike
listener, and asserts the committed report wins that bound. It passes in the
fast loop and fails in every cold gate observed, reporting
`:seon.db/write-bound-exceeded` with `:seon.db/write-wait-elapsed-ms` of 254
or 255 — the bound itself, so the promise was never delivered inside it.

## Evidence (2026-09-18)

- Cold: `tmp/orchestrator/gate-results/manifest-merge-gate.log` (255 ms) and a
  second cold gate at HEAD `e65ac88eb`, run root `tmp/test-runs/run.IVzccp`
  (254 ms; 62 tests, 2 failures, both in this one test).
- Fast, same HEAD: `bin/test-fast --paths dev_cache.clj -- seon.db-test` —
  58 tests, 0 failures.
- Both runs select the SAME dependency classes:
  `dev-cache-digest=25e1db910881ca5c7ca86d808c9e7239e537c0b01cc0585ca89e7ceb43b3bc46`,
  built at 14:56 on 2026-09-17 from the post-fix
  `reference-code/datahike/src/datahike/writer.cljc` (fix `e11845ba`, written
  14:41), and `dev_cache.clj`'s `valid-cache` re-hashes those recorded source
  URLs on every reuse. A stale-AOT explanation is refuted: see
  [the pins note](the-gate-snapshot-cannot-read-dependency-pins-so-fork-aot-classes-go-stale.md).

## What is not yet known

Whether the cold gate's first write genuinely needs more than 250 ms (cold
JVM, cold store, competing gate JVMs) or whether the listener completion path
behaves differently there. The next observation should record the write's own
phase timings in the cold worker rather than only the elapsed refusal, and the
test should assert the completion EVENT — the listener diagnostic and the
delivered report — rather than a tuned 250 ms deadline that stands in for it
(AGENTS §2.3: a bound firing is a bug report naming what never arrived).
