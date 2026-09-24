---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, tests, reader]
---

# seon.sci.reader-test expects declaration symbols as strings

At HEAD `e45e27d73` (before `b5a76953a`), `seon.sci.reader-test` fails 19 assertions: tests expect `:seon.fn/sym` and
`:seon.test/sym` as strings, the reader returns symbols (the owner's ruling: symbol references are Datahike symbols,
never strings). `declaration-facts-equal-the-current-var-lift` finds no row and two property tests fail. Found by lane
fix-ns-clause-reader (landing note `lane-fix-ns-clause-reader-2026-09-24.md`). Fix: update the retired assumptions in the
test to symbols; do not change the reader. Also unowned: the definition matchers these tests exercise are being retired by
the observation-based entry gate.
