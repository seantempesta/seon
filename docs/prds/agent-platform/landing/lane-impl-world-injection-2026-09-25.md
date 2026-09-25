---
type: landing
lane: impl-world-injection-opus
status: committed-unrun
date: 2026-09-25
---

# Lane impl-world-injection (2026-09-25)

Two inputs are now values the caller passes: the dependency analysis given to
`seon.fn/analyze-forms`, and the recording writer given to `seon.test/run`.
Each has a declared contract and a default, and a caller's argument wins.
Under the LOAD RULE no test request ran, and nothing was loaded into default.

## Broken or unproven first

- **The assignment's premise was inverted.** `analyze-forms` did not always read the
  shared kondo cache. It read **no** cache.
  - A submitted batch is linted from stdin (`-`), which is not checkout source, so
    `seon.fn.analyzer/analyze` runs kondo with `:cache false`.
  - As a result, a host call into any dependency outside kondo's built-ins refused as
    `:seon.fn/dependency-analysis-missing-error`, even with the cache filled.
  - Probe on default (pid 37460), 25 ms: `(seon.fn/analyze-forms db [{… "(defn probe-x [] (clojure.core.async/chan 1))" …}])`
    threw "clj-kondo holds no analysis of clojure.core.async", although
    `.clj-kondo/.cache/v1/clj/clojure.core.async.transit.json` exists.
  - This is likely the source of the open 19:16Z fault "no analysis of sci.impl.utils".
  - The slice therefore also fixes this: by default the shared cache is now read (and
    never written) for exactly the namespaces the batch left unanalyzed.
  - **Behaviour change:** turns that call `clojure.core.async` macros now classify them
    as host-bound instead of refusing with a core fault.
- **Why the cache is read, not linted.** kondo has no read-only cache mode. `update-defs`
  writes every linted namespace into the cache it reads
  (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:165`, clj-kondo 57252e07).
  - During probing I passed the shared cache root for a stdin analysis once. It wrote
    `probe.x.transit.json` into the shared cache, and I deleted that one file.
  - The default therefore reads entries with `clj-kondo.impl.cache/from-cache-1`
    (`:23`), which never writes.
- **The turn path cannot receive an injected analysis yet.** `seon.turn/analyze-submitted`
  (turn.clj:3431) calls the 2-arity. turn.clj is held by another lane.
  - The one-line change for turn.clj's owner:
    `(seon.fn/analyze-forms database requests (:seon.fn/dependency-analysis cluster))`.
    With it, a turn world could carry `{}`.
  - Until then there is no turn regression for "the turn stores and delivers the fault
    and does not re-open". Also, turn_test's fixture environment has no connection, so
    the fault panics there without being stored (lane-fix-turn-hang round 5).
- **Slice 2 covers admission and stop only.** Completion (`runner/commit-results!`) and
  exit (`runner/record-exit!`) call `db/transact!` inside runner.clj, which another lane
  holds, so the injected writer does not reach them.
  - Carrying the writer into those two functions is a signature change in runner.clj.
- **Slice 2's regression is unmeasured.** Its `:seon.test/long-ms 20000` is borrowed
  from its sibling's measured 13.8 s (one definition plus one nested request). Root should
  measure it and correct the bound.
- **Slice 2 asserts the `:panic` path only.** It does not cover the `:record` "false
  success" path.
  - Under `:record`, the admission refusal is returned as the run's value (never
    `passed?`), by code inspection only.
  - The caller-mistake half is already asserted by source_test's
    `work-on-an-acquired-branch-runs-as-that-branchs-own-work-under-an-agents-custody`
    (foreign recording connection, returned).
- **Uncertain in slice 2's regression.** It assumes that a `:db.fn/call` throwing
  `(ex-info … {:error …})` reaches `seon.db/transact!`'s Datahike-abort branch, which is
  `rejected-value` and carries `:seon.error/throwable`.
  - I could not probe that without a projection-carrying disposable connection.
  - If it lands in the outcome-unknown branch instead, `db/transact!` itself panics and
    the test fails loudly at the first assertion.
- **Out-of-scope finding.** 75 decoy fixture namespaces (tmp/ mirrors) sit in the
  shared kondo cache. Issue:
  `docs/seon/issues/tmp-fixture-sources-write-decoy-entries-into-the-shared-kondo-cache.md` (6c7117400).
- Default is `:faulted` with "JVM RESTART NEEDED (reference-code/sci)" from another lane.
  **RESTART NEEDED** before root runs these checks. It is not a reset.

## Slice 1: dependency analysis as a value (d4b3c0e13)

- `analyze-forms` takes `([database requests] [database requests dependencies])`.
  - `dependencies` maps each namespace to kondo's cached namespace map.
  - Its contract is `[:? [:maybe [:map-of :symbol :map]]]`.
  - `nil` means the default, `shared-dependency-analysis`: kondo's own entries read from
    `.clj-kondo/.cache/v1`, `:clj` then `:cljc`, for the analysis's
    `::analyzer/unanalyzed-namespaces` only.
  - A supplied value wins, and `{}` means no dependency is analyzed.
- `with-dependency-analysis` removes the supplied namespaces from
  `::unanalyzed-namespaces` and marks each usage whose cached def is `:macro true`.
  `host-bound-callers` is unchanged.
- REPL proof, scratch namespace, real batch
  `(ns probe.y (:require [clojure.core.async :as a]))` with go-loop, chan and an unrequired call:

  | analysis given | `host-bound-callers` |
  |---|---|
  | none (installed behaviour) | refuses `[<! chan go-loop]` with `:seon.fn/dependency-analysis-missing-error` |
  | shared cache read | `#{probe.y/looping}` |
  | `{}` | the same declared refusal |

  - Costs: the kondo run took 8.4 ms and the cache read 0.53 ms (one namespace).
  - `m/validate :seon.fn/dependency-analysis-missing-error` accepts the real refusal.
  - `[:cat … [:? …]]` accepts both arities.
- Regression, folded into the same class test `seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound`:
  - After the fixture fills its own cache from the core.async jar, a batch
    (`require` alias, then `defn looping`) given `{}` throws the declared schema: its
    metadata, schema validation and targets `[<! go-loop]`.
  - The same batch given `{'clojure.core.async <that fixture cache's entry>}` settles
    `:seon.fn/host-bound? true`.
  - The regression does not depend on the checkout cache's state.
- Size: src +42 −4, test +27.

## Slice 2: the recording writer as a value (b7c6dd3c8)

- `:seon.test/recording-writer` is declared in `seon.test.edn`: `[:fn {…:gen/schema [:=> [:cat :seon.db/connection :seon.db/tx-data] :map]} clojure.core/ifn?]`,
  following the `:seon.instrument/callable` pattern. It is an optional member of
  `:seon.test/request`.
- `run` destructures it with `:or {write! db/transact!}` and uses it for the admission
  write and for `release-not-started!`. `own-write!` is unchanged.
- Regression `seon.test.one-request-test/a-refused-recording-write-is-a-stored-core-fault-carrying-its-whole-refusal`:
  - The writer appends a throwing `:db.fn/call` to the admission.
  - Under `:panic` the run throws. The receipt shows committed, operation
    `seon.test/admit-run`, the cause's ex-data equal to the writer's whole refusal, and
    the next cause identical to the refusal's throwable.
  - The error row exists by signature, and nothing was reserved for the member.
- Size: src +7 −5 (test.clj), edn +5, test +40.

## Duplicates

No other function takes this world through a parallel ad-hoc input:
- `seon.fn/rows`' `::analyzer/cache-root` is a real kondo cache that kondo lints into.
  That is a different input (a place kondo writes) and cannot collapse into a read value.
- The fixture writer `test-support/transacted!` is the fixtures' own checked writer, not
  the runner's.

Deletions: none. Net: source +45, schema +5, tests +67.

## Root runs (serially, after the JVM restart and adoption of d4b3c0e13, b7c6dd3c8)

```
bin/test-check default --test seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound
bin/test-check default --test seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch
bin/test-check default --test seon.fn-test/defining-forms-share-one-form-local-kondo-batch
bin/test-check default --test seon.test.one-request-test/a-refused-recording-write-is-a-stored-core-fault-carrying-its-whole-refusal --include-long
bin/test-check default --ns seon.test.one-request-test --include-long
bin/test-check default --test seon.cluster.source-test/work-on-an-acquired-branch-runs-as-that-branchs-own-work-under-an-agents-custody --include-long
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
```

Lint: clj-kondo reports 0 errors on fn.clj, fn_test, test.clj and one_request_test.
The warnings are the ones HEAD already has. The scratch namespace
`lane-impl-world-injection.probe` was removed from default. No branches or shells remain.
