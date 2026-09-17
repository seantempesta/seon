---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, program-graph, test, wave/test-fixture]
---

# A blocked publication reports that findings exist, never which ones

## Problem

`seon.fn/assert-clean-analysis!` (`src/seon/fn.clj:1062`) throws
`"Static program analysis found blocking errors."` and carries the actual
findings only in `ex-data` under `:seon.fn/findings`. Every consumer between
it and the reader prints the message alone, so the one thing the check knows —
which file, line and finding type refused — never reaches the agent. This is
the project's named recurring class in its diagnostic form: the check fires,
and its report says nothing about its subject.

## Evidence

2026-09-16, during the no-default-cluster continuation. A `bin/test-fast
--paths` snapshot that omitted one caller file left HEAD's
`test/seon/repl_parity_test.clj:29` calling the now-single-arity
`seon.config/effective` with one argument. That is an `:invalid-arity`
finding, one of the six blocking types (`src/seon/fn.clj:1011-1017`).

What the agent saw instead, for every test in the run:

```
ERROR in (apply-compiles-once-and-round-trips-through-database-facts) (test_support.clj:285)
clojure.lang.ExceptionInfo: Fixture setup was refused.
```

`seon.test-support/checked-fixture-result` has since been changed to name the
refusal's kind and message, which turned that line into

```
Fixture setup was refused by :seon.test-support/database-base-unavailable:
Canonical fixture base construction failed: Static program analysis found blocking errors.
```

— one layer better, and still not the file and line. Diagnosis took a full
kondo run over `src` and `test` plus a `git grep` of HEAD, where the finding
itself was already in hand at the throw.

## Owner

`src/seon/fn.clj` (`assert-clean-analysis!`) and the fixture base builder that
wraps its message.

## Acceptance

The thrown message names a bounded sample of the blocking findings —
`filename:row:col type` and the finding's own message, first few plus a count
of the rest — so a snapshot refusal is actionable from the test output alone.
A regression asserts that a blocking finding's file and row appear in the
message a caller sees, not only in `ex-data`.
