---
type: prd
status: completed
tags: [prd, agent, testing]
---

# Phase 1.5 handoff — fixture-walking in run-vars (2026-05-26)

## Chosen approach

**Option B (hybrid):** replicate `cljs.test`'s fixture-walk inside
`run-vars`; keep the synthetic `#js {:sym sym}` testing-var stand-in.
Option A (route through `cljs.test/test-vars`) requires real `Var`
instances which self-host CLJS can't synthesize — that's exactly why
the original code went a different way. The walk is ~40 LOC and mirrors
the `:async` branch of upstream `test-vars-block`:

- Group resolved vars by ns.
- Per ns: lookup `cljs-test-once-fixtures` + `cljs-test-each-fixtures`
  via `goog.getObjectByName` (`lookup-fixtures` helper, only map-form
  supported — fn-form is incompatible with async per cljs.test docs).
- Run `:once :before` (in registration order) → for each var: `:each
  :before` → drive test body → `:each :after` (reverse order) → after
  all vars, `:once :after` (reverse order).
- Each fixture invocation is awaited (`run-fixture-fn!`) so async
  fixtures returning a Promise are first-class. Errors inside fixtures
  surface as `:error` events; the batch continues.

## Before / after — `seon.db-test`

Before (commit `7a5ae04`): 26 tests, ~1 pass, **60 errors + 1 fail**
(every assertion that depended on the `:once :before` Malli schema
registration failed at the schema-gate).

After (this patch):

```clojure
(:seon.test.runner/summary dbr)
;=> {:type :summary, :test 26, :pass 220, :fail 5, :error 0}
```

26 tests, 220 passing assertions, 5 failing assertions across **2
deftest vars** (`transact!-throws-synchronously-on-unregistered-attr`
+ `transact!-throws-synchronously-on-bad-value`). Both are the
throw-vs-envelope contract drift documented as BLOCKED in the audit
(rows 5 + 6 of §2): `db/transact!` was made `^:async` at commit
`ed72acb` and returns an `{:seon.db/ok? false :seon.db/error …}`
envelope instead of throwing synchronously — the tests still
`(try … (catch :default e e))` and expect a non-nil `ex`. Phase 1.5's
accepted leftover; the fix is a test rewrite, not a runner change.

Self-test suite (`seon.test.runner-test`) still green: 7 tests, 26
pass, 0 fail, 0 error.

New regression test (`seon.test.fixture-support-test`): 1 test, 4
pass, 0 fail, 0 error. Asserts the exact lifecycle sequence
`[:once-before :each-before :probe-a-body :each-after :each-before
:probe-b-body :each-after :each-before :probe-c-async-body :each-after
:once-after]` — fails immediately if any fixture wrapper is removed
or reordered, INCLUDING that `:each :after` fires AFTER the
`(async done …)` body's `(done)` callback resolves (probe-c is async).

## What is NOT covered

- **Async fixture bodies** are awaited (`thenable?` check in
  `run-fixture-fn!`) but no test exercises a fixture whose `:before`
  RETURNS a Promise. The regression test's async probe only exercises
  the test BODY being async — sync fixtures wrap an async test. The
  async-fixture path is reachable but unverified. Low priority — no
  current seon test uses an async fixture.
- **Fn-form fixtures** (`(defn my-fix [f] … (f) …)`) intentionally
  unsupported — cljs.test itself forbids them with async tests
  (`disable-async` throws `::async-disabled`). Zero seon files use
  them (`grep use-fixtures` confirms one map-form site in db_test).
- **Multi-ns batches**: when `run-vars` is called with vars spanning
  multiple namespaces in one call, fixtures fire per-ns correctly
  (group-by ns drives the outer loop) but no test exercises this
  shape. `run!`'s `::ns` selector is single-ns by design.

## Verifier should poke at

Re-run `(r/run-ns! {::r/ns 'seon.db-test ::r/record? true})` after a
fresh pod restart and confirm `{:test 26 :pass 220 :fail 5 :error 0}`.
Then DELETE `lookup-fixtures` from `src/seon/test/runner.cljs` and
re-run `(r/run-ns! {::r/ns 'seon.test.fixture-support-test})` — that
single deftest MUST go red (the lifecycle assertion is exact-sequence
equality). If it stays green after the deletion, the regression test
is decorative and needs to be redesigned.
