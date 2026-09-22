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
