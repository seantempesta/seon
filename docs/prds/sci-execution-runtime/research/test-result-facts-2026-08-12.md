---
type: research
status: complete
tags: [research, test, database, sci]
---

# Test-result fact acceptance evidence

## Authorities and dependency ledger

Rulings 10 and 40 in the complete
[self-generating-context PRD](../plan/self-generating-context-prd-2026-08-11.md)
were read end to end before implementation. The runner is grounded in Clojure
1.12.5's `clojure.test/report`, `test-var`, and `test-vars` implementation at
`reference-code/clojure/src/clj/clojure/test.clj:353-403,708-737`. Current
cardinality-one replacement and explicit cardinality-many retraction follow
the maintained Datahike fork at
`reference-code/datahike/src/datahike/db/transaction.cljc:584-616,785-810,1072-1078`.

The one completion writer is `seon.test.runner/commit-results!`. Both
`bin/test` result recording and the agent-facing `seon.test/run` call it, and
both return projections pulled from the committed transaction's `:db-after`.

## Isolated live proof

The proof used operator root `tmp/ruling40-operator`, cluster `ruling40`, and
door-mode namespace `my.agents.ruling40-proof`, after a complete `bin/seon
--root tmp/ruling40-operator init` and fresh boot.

The agent fork defined a deliberately failing test and evaluated:

```clojure
(seon.test/run #'live-result-fact)
```

The settled value was:

```clojure
#:seon.test{:sym "my.agents.ruling40-proof/live-result-fact"
             :pass-count 0
             :fail-count 1
             :error-count 0
             :run-basis-t 536870934
             :run-at #inst "2026-08-12T21:09:52.447-00:00"
             :failing-assertions
             ["2aedcfc7384b8431d724c9edc5560b62bea68e369a57f38678002809b3ee8240"]
             :failure-message
             "live red evidence\nexpected: (= 5 (+ 2 2))\nactual: (not (= 5 4))"}
```

The same Var was redefined green and rerun. Its settled value and a subsequent
`seon.db/pull` were equal:

```clojure
#:seon.test{:sym "my.agents.ruling40-proof/live-result-fact"
             :pass-count 1
             :fail-count 0
             :error-count 0
             :run-basis-t 536870935
             :run-at #inst "2026-08-12T21:10:00.852-00:00"}
```

The green pull contains neither `:seon.test/failing-assertions` nor
`:seon.test/failure-message`. This proves that a rerun replaces the complete
current result on the existing test row rather than leaving stale red facts or
appending a second result mechanism.

## Recurring proof and foreign gate boundary

The three new owning regressions passed directly: latest result replacement,
agent-fork return/commit identity, and the two program-graph derivations. In
the focused `bin/test seon.test-runner-test seon.fn-test` checkpoint, all new
tests completed successfully, including isolated confirmation of the two
database-owning runner tests.

The combined checkpoint then stopped at an unrelated in-flight schema
boundary: `seon.fn-test/indexing-refuses-an-already-populated-branch` and
`seon.fn-test/indexing-uses-a-prebuilt-manifest-without-analysis` report that
`:my.background/invalid-call-error` names `seon.error/render-ai`, whose
projected function contract is absent in that scratch index. The final tally
was 36 tests, 254 assertions, one failure and one error. No file in that
foreign owner was changed or worked around here.
