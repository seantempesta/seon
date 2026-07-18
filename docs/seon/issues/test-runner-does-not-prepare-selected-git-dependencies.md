---
type: issue
status: open
severity: friction
tags: [issue, flow, pod, cljs]
---

# Prepare selected git dependencies before test compilation

## Problem

After either Seon dependency alias advances a git dependency whose
`:deps/prep-lib` output is absent, `bin/test-cljs` fails while building its
classpath instead of preparing the selected dependency. The one development
operator is expected to make ordinary source and test commands self-contained;
agents and fresh downstream checkouts should not need to rediscover an alias-
specific `clojure -X:deps prep` sequence.

## Evidence

After both Datahike pins advanced to
`417649383c65e13f15ea41d394fb1ed742477965`, the first focused CLJS run fetched
that reachable commit and stopped with `The following libs must be prepared
before use: [org.replikativ/datahike]`. Running
`clojure -X:deps prep :aliases '[:cljs]'` prepared the selected basis and the
same focused runner then compiled normally. The failed proof is retained at
`tmp/test-cljs-20260715-022253-25246.log`.

Older packaging research already documents Datahike's generated Java boundary
and the former shell operator's prep subsystem. The current Babashka operator
must settle whether prep belongs in artifact reconciliation or the shared
boundary runner; it must not restore a separate shell-only mechanism.

## Owner

The one artifact/dependency reconciliation path used by `bin/seon up`,
`bin/test-cljs`, and the writer runner. Default and ACME must derive the same
selected aliases and prep fingerprints from their artifact flavor.

## Acceptance

- A fresh dependency cache can run `bin/seon up`, `bin/test-cljs`, and
  `bin/test-writer` without a manual prep command.
- Prep uses the exact selected alias basis and runs only when its declared
  ensure output is absent or stale.
- Concurrent commands serialize one prep instead of racing generated classes.
- Default and ACME source/artifact flows share the same mechanism.
- A focused regression advances a fixture git dependency with
  `:deps/prep-lib`, starts from no ensure output, and proves the requested
  command continues into its normal compile/test boundary.

## Implementation evidence

The shared owner now exists as `seon.dev.artifact/prepare-dependencies!` under
the checkout-wide source-artifact lock. `build-source!` uses its unlocked inner
operation while holding that same lock; `bin/test-cljs` selects `[:cljs]`,
`bin/test-writer` selects `[:writer]`, and downstream CLJS preparation includes
its `SEON_EXTRA_SRC` local-root basis. Focused artifact proof passed 12 tests
and 46 assertions.

Fresh-cache CLJS proof removed fork commit `4e72595f` from the gitlib cache and
then invoked only public `bin/test-cljs`. The retained transcript shows checkout,
standard `:deps/prep-lib` Java preparation, compile, and the 10-test/72-assertion
Node pass in one command at
`tmp/test-cljs-20260715-034817-65956.log`.

The public writer path was also exercised directly with
`bin/test-writer seon.db.branch-test`. It selected and completed the shared
`[:writer]` preparation owner, then passed 4 tests and 16 assertions with zero
failures/errors.

This issue remains open until the same fresh-cache acceptance is observed
through `bin/seon up`, a genuinely fresh writer dependency, and a downstream
ACME basis, and until the declared fixture proves stale ensure-output
invalidation rather than only a missing fresh checkout.
