---
type: issue
status: resolved
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

## Resolution (2026-09-17, `7854d35b2`)

The test asserts the completion EVENT and declares no bound of its own.

- The tuned `{:seon.config.db/write-time-limit-ms 250}` overlay is gone; the
  shipped default (`config/default.edn:11`, 600000 ms) governs, so no clock
  the test invents can stand in for the event.
- The submission runs in a `future` awaited through
  `seon.test-support/await-event!` (`test/seon/test_support.clj:772`), whose
  `Future` branch waits the declared `event-backstop-seconds` and throws
  naming the wait when the promise is never realized. A stranded write is
  therefore still a loud failure, not a pass.
- The observed facts are: the realized answer is the committed report
  (`:db-after` present); an explicit separate assertion that it is NOT
  `:seon.db/write-bound-exceeded`; the listener's own diagnostic
  (`:datahike/listener-error` at `:error` with the listener key and the
  identical exception); the durable fact; and a following write that commits
  through the same writer with an advanced branch head.

The property was never a latency one: Datahike delivers the result promise at
`reference-code/datahike/src/datahike/writer.cljc:410` and only then notifies
listeners at `:427`, so a throwing listener cannot strand a committed write by
construction.

Tally: `bin/test-fast --paths test/seon/db_test.clj -- seon.db-test` — 58
tests, 438 assertions, 0 failures, 0 errors (this test 183 ms). The cold
`bin/test` proof is the orchestrator's and is still owed.
