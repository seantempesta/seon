---
type: issue
status: open
severity: friction
tags: [issue, test, tooling, clj-kondo, wave/test-fixture, wave/parallel-stress-triage]
---

# A `bin/test-fast` JVM dies on the clj-kondo cache lock instead of waiting

## Evidence

2026-09-16, S11 continuation. One plain `bin/test-fast
seon.concurrency-independence-test` invocation (pid 52189, projection
acquired 23:07:08Z, no snapshot line in its log) produced two errors with
one cause:

- `seon.concurrency-independence-test/receipt-diagnostic-selects-only-present-failure-facts`
  failed inside `seon.test-support/checked-fixture-result`
  (`test/seon/test_support.clj:285`, reached from `with-branched-database`
  `:985`): the CANONICAL DATABASE BASE itself was a refusal, so every later
  fixture in that JVM would have read the same refusal;
- `n-agents-fold-independently-on-one-live-cluster` threw
  `java.lang.Exception: Clj-kondo cache is locked by other thread or process.`
  from `seon.fn.analyzer/discard-obsolete-cache-entries!`
  (`src/seon/fn/analyzer.clj:217`, reached from `invoke-kondo` `:250` and
  `seon.fn/build-manifest` `src/seon/fn.clj:1960`).

The log block is `tmp/s11-resume-fast-b.log`. An identical invocation
twelve minutes later (`tmp/s11-resume-fast-b2.log`, pid 60684) ran the same
two tests green: 2 tests, 2,871 assertions, 0 failures, 0 errors. Nothing in
the tree changed between the two runs, so the failure is contention, not a
property of the code under test.

## Why it is not already covered

`docs/seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md`
records this exact class and states it was fixed at its owner: `bin/test`
worker checkouts now COPY `.clj-kondo` instead of symlinking it, proved by
`seon.test-runner-test/worker-checkouts-own-the-writable-clj-kondo-cache`.
That rule covers a SNAPSHOT, and `seon.fn.analyzer` resolves the cache with
the RELATIVE paths `.clj-kondo` / `.clj-kondo/.cache`
(`src/seon/fn/analyzer.clj:13-14`), so which cache a JVM locks follows its
working directory. A `--paths` fast run gets a private one: this
continuation's run (pid 69860) owned
`tmp/test-runs/run.tsxdVc/.clj-kondo` — a real directory, not a link — and
even rooted its live cluster store under that snapshot. The failing 23:07Z
run was a PLAIN fast invocation with no `--paths`, so its working directory
was the shared checkout and its lock was the checkout's own
`.clj-kondo/.cache` — the one the edit hook's publications and any
in-process run also take. The lock itself is clj-kondo's, taken through
`with-thread-lock` plus `(with-cache root 6)`
(`src/seon/fn/analyzer.clj:215-216`): six retries, then a throw. Which
holder won it was not captured, so the competing writer is an OBSERVATION,
not an attribution.

## Why it matters

A contended lock becomes an uncaught exception inside the shared canonical
fixture base, which then reports as a test error on an unrelated regression.
Per AGENTS.md 2.3 a bound firing is a bug report naming what never arrived;
here it names nothing, and the reader spends the diagnosis on the test rather
than on the lock.

## Owner and acceptance

`seon.fn.analyzer/invoke-kondo` and `discard-obsolete-cache-entries!` own the
seam. Acceptance: the analyzer's cache acquisition either resolves a root
private to the invoking JVM (proved by a regression asserting two concurrent
analyses never name the same cache directory), or its bound failure is a flat
`:seon.error` value naming the cache root and the competing holder, and the
canonical fixture base surfaces that refusal as its own diagnostic rather than
as an uncaught exception on the first test that happens to run.
