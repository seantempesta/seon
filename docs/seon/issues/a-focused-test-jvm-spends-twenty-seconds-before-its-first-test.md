---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, testing, performance, seconds-not-minutes]
---

# A focused test JVM spends twenty seconds before its first test

Lane publication-lock-deletion, 2026-09-22, a disposable launcher that arms
contracts exactly as `seon.test.fast` (`arm/initialize-contracts!`) and runs
two to four named namespaces with `clojure.test`: wall 21.8–30.1 s per run, of
which the tests themselves took 5.3–11.4 s (measured around
`clojure.test/run-tests`). `bin/test-fast --paths` run `e838074339ff`: 31.7 s
wall. The remainder is JVM start, `-M:test` classpath load and compilation,
packaged projection acquisition and arming 1,699 functions (the log shows
~4 s from projection acquired to contracts armed). No phase clock separates
JVM start from namespace compilation.

Every lane pays this per verification run. Wanted: a phase breakdown per run
(JVM start, require, projection, arming, tests), then the deletion that makes
a focused run seconds; a warm JVM (the REPL) is the obvious candidate.

Sighting 2026-09-22 (same lane, follow-up): three namespaces including five
`with-store` publication tests took 38.9 s of test time inside a 66.9 s run;
each `with-store` copies the canonical store into a fresh physical store.

Sighting 2026-09-23 (lane schema-changes-in-place): fourteen
`bin/test-fast --paths … -- seon.schema-in-place-test` runs took 28.8–88.2 s
wall for 8 tests whose test time is ≈20 s (run `d19c6cbc41a3`: projection
acquired 21:26:50.5Z after a 4 s snapshot phase, contracts armed 21:26:52.5Z,
first test 21:26:53.6Z, last 21:27:13.6Z; 53.5 s wall). Three runs refused at
snapshot admission without executing anything, after 36–57 s, because the
recording authority (default's current-src) was absent or its PREPL did not
answer within 30 s while default was replaced.
