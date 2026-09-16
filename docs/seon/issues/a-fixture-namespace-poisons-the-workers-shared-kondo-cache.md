---
type: issue
status: resolved
severity: blocker
tags: [test, analysis, fixture, runner]
---

# A fixture namespace named `seon.error` poisons the worker JVM's kondo cache

## Observed

Batch 47 on `fb6261d58`, worker `pool-2`
(`tmp/orchestrator/gate-results/batch-47/named.log:142`):
`seon.adoption-rows-test/adoption-identities-carry-no-nil-member` ERRORs at
`src/seon/fn.clj:917` with `Static program analysis found blocking errors.`
and exactly three `:unresolved-var` findings against the worker checkout's
own `src/seon/cluster.clj`:

```
row 278  col 10-26  Unresolved var: error/diagnostic
row 2579 col 21-34  Unresolved var: error/prepare
row 2595 col 22-37  Unresolved var: error/recording
```

All three vars exist in `src/seon/error.clj` at HEAD. The same test is green
in-process on `default` (5 assertions, 0 failures).

## Cause

Not a stale cache carried into the checkout: the gate snapshot has no
`.clj-kondo/.cache` at all (`tmp/test-runs/run.EpXtBI/.clj-kondo/` holds
`config.edn`, `imports`, `inline-configs`, `metosin` only), so every worker
builds its cache from zero and `seon.test.cache/worker-checkout!`
(`src/seon/test/cache.clj:61`) copies none.

The cache is poisoned by another test in the SAME worker JVM.
`seon.fn-test/keyword-usage-is-indexed-per-declaration`
(`test/seon/fn_test.clj:1188`) writes a decoy source file

```clojure
(ns seon.error)
(def message :m)
```

under its fixture root and analyzes it with the cache enabled. clj-kondo
keys its dependency cache by NAMESPACE NAME, so the run writes
`.clj-kondo/.cache/v1/clj/seon.error.transit.json` — 258 bytes, one var
`message`, `:filename` pointing at
`tmp/fn-test/9e447582-.../seon/error.clj`. The real 13 KB entry never gets a
chance to exist in that JVM. Any later analysis in the same checkout resolves
`seon.error` through the stub.

The signature is exact: `src/seon/cluster.clj` makes exactly three var CALLS
into that alias — `error/diagnostic`, `error/prepare`, `error/recording` —
and all three are the reported findings; its 15 `:seon.error/message` keyword
usages are unaffected because keywords need no var.

`seon.fn.analyzer/discard-obsolete-cache-entries!`
(`src/seon/fn/analyzer.clj:127`) cannot save this: it deletes an entry only
when the recorded `:filename` is `<stdin>`, disagrees with a supplied
canonical source, or no longer exists. The fixture file still exists, and the
first call passes `{}` for `canonical-sources`, so the stub survives.

Whether the red appears is pure worker-scheduling luck — it needs
`fn-test` and `adoption-rows-test` on the same pooled JVM in that order. The
third red in the same batch (`seon.fn-test/file-artifacts-and-manifests-are-byte-digested-and-deterministic`)
is a separate elision-path difference, not this.

## Done when

- a test fixture cannot write a cache entry under a first-party namespace
  name: either fixture analysis runs with `:cache false` (the analyzer already
  supports it, `src/seon/fn/analyzer.clj:204`), or fixture sources are
  namespaced so they cannot collide with `src/` (`sample.*`, not `seon.*`);
- one regression proves the class: analyze a decoy `seon.<something>` fixture,
  then analyze a real `src/` file that calls that namespace's vars, and assert
  the analysis is clean.

## Resolved

Fixed at the analyzer seam, not per test
(`src/seon/fn/analyzer.clj`). ONE cache rule now decides every analysis:
only the checkout's OWN declared source may read or write the checkout's
shared dependency cache — `(not (every? checkout-source? paths))` sets
`:cache false`. The declared roots are derived from `deps.edn` (`:paths`
plus every alias's `:extra-paths`, discarding the test alias's `.` entry
and anything else not strictly inside the checkout), never a list
maintained in the analyzer; the previous stdin special case (2026-08-29)
dissolves into the same rule, since `-` is not declared source either.
A fixture root under `tmp/` is therefore isolated by construction.

`discard-obsolete-cache-entries!` stays: with cache writes owned, it is
the repair for entries an older build, another tool, or a pre-fix fixture
left behind — a stub whose recorded file is gone once the fixture root is
swept is deleted by its existing "file no longer exists" clause.

Live proof (development JVM, forms evaluated and namespaces reloaded
directly — default's adoption was refused by a foreign lane's in-flight
edit, so this is NOT an adopted-source proof):

- old rule (`invoke-kondo` with the cache on for the fixture root):
  `seon.error.transit.json` 13,570 bytes → 194 bytes, and the next analysis
  of `src/seon/await.clj` reported `Unresolved var: error/diagnostic` —
  the class reproduced exactly;
- new rule: the same decoy analysis leaves the entry byte-identical and
  `src/seon/await.clj` reports no unresolved var.

Regression: `seon.fn-test/fixture-analysis-never-writes-the-checkouts-dependency-cache`
(`test/seon/fn_test.clj`), in-process 3 assertions, 0 failures, 0 errors.
The existing `keyword-usage-is-indexed-per-declaration` keeps its decoy.

In-process runs of the cache-touching namespaces
(`seon.fn.analyzer-test`, `seon.public-contract-test`) are green except two
reds that fail IDENTICALLY with the old rule forced back on, so neither is
attributable here: `seon.fn.analyzer-test/ordered-forms-use-existing-context-and-original-row-numbers`
(expects `seon.run/complete` to resolve; `analyze-forms` passed `:cache
false` before and after this change) and
`seon.fn-test/keyword-usage-is-indexed-per-declaration` (its two database
assertions read empty `:seon.fn/keywords`).
