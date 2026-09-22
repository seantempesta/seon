---
type: evidence
status: committed be713ae30; armed fixture run of the regression blocked (publication paused)
---
# Lane db-validity (fix schedule #4), 2026-09-23

Commit `be713ae30`. Paths: `src/seon/db.clj`, `test/seon/owned_value_test.clj`.
Authority: `docs/research/agent-platform/swallowed-errors-census-2026-09-23.md`
(category-A rows for `seon/db.clj`) and review-validator-deletion-oversight §A
(`bb3a0c6c4`).

## What changed

**Census category A in `db.clj`. Each row is now a declared case or carries the whole cause.**

| census row | change |
|---|---|
| `:2012` `query-call-valid?` (R-DISCARD) | It used to answer `true` whenever `normalize-q-input` threw. Now it answers `true` only for the declared case: the result is `q`'s own dependency refusal (`::invalid-read`, `:seon.db/refused-read-operation seon.db/q`, `:seon.error/exception-class`), which already carries the caught Throwable. Any other parse failure escapes the guard, so Malli's `-safe-pred` (`reference-code/malli/src/malli/core.cljc:209`, rev `606083c5c`) makes it an `:invalid-guard` violation instead of health. The guard now has a complete contract. |
| `:2028` `query-guard-message` (R-DISCARD) | The fixed-message catch is deleted. The function is reached only after the guard parsed the same inputs and answered false, so it cannot fail differently. It now has a contract. |
| `:309 :1901 :2085 :2242 :2459 :2518 :2559 :2573` (R-HELPER via `dependency-error`) | The helper passes the Throwable to `seon.error.refusal/diagnostic`. Callers are unchanged. The schema-refusal branch (`:seon.schema/expected-value` in `ex-data`) stays as it was, as a declared case. |
| `pull-budget-error` (the census's message-only helper) | Also goes through `diagnostic`, and supplies the message only when one is present. |
| `:2794` `rejected-value` (R-DISCARD) | A failed conflict-owner lookup no longer becomes nil. It rides as `:seon.error/data :seon.db/conflict-lookup-failure`, a diagnostic built from the lookup's Throwable. The rejection itself also carries the transaction Throwable. |
| `:3865` `transact-call` `:else` (C / B-DECLARED) | The undeclared writer failure carries the Throwable through `diagnostic`. The panic path still chains it as the `ex-info` cause. |

**Callers' declared unions.** No change is needed. `q` returns
`[:or :seon.schema/value :seon.db/invalid-read-error]`, and the added members
(`:seon.error/exception-class`, `:seon.error/frame`, `:seon.error/chain`) are optional
members of `:seon.error/base` (`resources/seon/schemas/seon.error.edn:126,206,316`).
The probes below show each changed refusal passing its declared schema.

**Owner extraction for the regression.** `write-report-error` computed its identity
set and attribute plans inline. They are now `report-identity-attributes` and
`write-attribute-plans`, each with a contract. The test uses the writer's own
functions and does not copy them. Plans are still memoized through
`schema/projection-cache-value` under the same key; the probe showed them equal to
the cached value.

**Regression** `seon.owned-value-test/a-transaction-entity-root-reaches-the-arity-gate`.
Its basis is an existing arity child with no owner. The report adds
`[:db/current-tx :seon.fn/sym …]` and `[:db/current-tx :seon.fn/arities child]`,
so every non-`txInstant` datom sits on the transaction entity. The test calls
`write-owned-values-error` with a fresh accumulator and asserts it holds
`:seon.fn/sym`.

The child must already exist. If the arity child is new in the same transaction,
the pre-fix ancestor walk also reaches the tx root and both versions pass (probe P3a).
That shape does not tell the two versions apart.

## Evidence

All REPL probes ran in `default` (pid 43581, JVM mode, `seon.db` namespace). The
changed forms were loaded under `probe-` names, and all 11 probe vars were
`ns-unmap`ped afterwards. No existing Var was replaced. Probe sources are in
`tmp/db-validity/{new-fns,prefix-fn,head-fn,proof,proof2}.clj`.

**Default does not carry `bb3a0c6c4`.** `(resolve 'seon.db/identity-attribute-accumulator?)`
returned nil. Default started at `2026-09-22T21:19:47Z` and the commit is
`21:26:21Z`, so the loaded `write-owned-values-error` is the pre-fix one. Hook
publication is paused (`.claude/seon-hook.edn :current-source :enabled false`), so
no edit in this lane reached default.

- **P1, guard (default, loaded code = HEAD guard vs new guard).**
  - `(query-call-valid? [[db 42] 42])`: HEAD returns `true`. The new guard lets
    `IllegalArgumentException "Don't know how to create ISeq from: java.lang.Long"` escape.
  - New guard on `[[db 42] <dependency refusal>]`: `true`.
  - Well-formed query: `true`.
  - `:in $ ?x` with no argument: `false`.
  - HEAD `dependency-error` has no `:seon.error/exception-class`. The new one carries
    `java.lang.IllegalArgumentException` and frame `[clojure.lang.RT seqFrom "RT.java" 577]`.
  - `explain-shape-in :seon.db/invalid-read-error` gives nil (valid).
  - `(seon.db/q db 42)` took 0.07 ms.
- **P2, rejected-value.** With data `{:error :transact/unique :attribute 42 …}`,
  `unique-conflict` throws `ClassCastException`. HEAD dropped it: the data keys were
  `[attribute datom error …transaction]` only. The new version keeps
  `:seon.db/conflict-lookup-failure {:seon.error/exception-class java.lang.ClassCastException, :seon.error/operation seon.db/unique-conflict, :seon.error/message "class java.lang.Long cannot be cast…"}`
  and the outer `:seon.error/exception-class clojure.lang.ExceptionInfo`.
  `explain-shape-in :seon.db.write/validation-refusal` gives nil.
- **P3, regression discriminates.** Same report shape as the test, `probe.lane/g`:
  - Pre-`bb3a0c6c4` owner (extracted from `bb3a0c6c4^`): accumulator `#{}`.
  - `bb3a0c6c4` owner: `#{:seon.fn/sym}`.
  - Instrumented log: root `536871075`, identities `{:seon.fn/sym probe.lane/g}`, one changed root.
  - P3a: with a new arity child in the same transaction, both versions return
    `#{:seon.fn/sym}`.
- **HEAD load and owner proof (`be713ae30`).** Ran `git archive be713ae30` into
  `tmp/db-validity/snap` with `reference-code/*` symlinked, then
  `clojure -M:dev:test tmp/db-validity/proof.clj`. Exit 0.
  - `(require 'seon.db 'seon.owned-value-test …)` took 17,287 ms.
  - The regression's exact owner call on an in-memory store returned `:acc #{:seon.fn/sym}` in 61.8 ms.
  - `(seon.db/q db 42)` gave `{:seon.error/exception-class java.lang.IllegalArgumentException, :seon.db/invalid-read true}`.
  - The guard escapes on a non-refusal result.
  - `query-guard-message` rendered the `:in [$ ?x]` sentence.
  - An earlier run on the pre-commit tree (`proof2.clj`, 13,383 ms load) showed
    `pull-budget-error` carrying `:seon.error/exception-class`. Its schema explain
    failed only on the probe's invented budget name `:pull/nodes`, which is outside
    the declared enum.
  - The snapshot root was removed.
- **Lint.** `clj-kondo --lint src/seon/db.clj test/seon/owned_value_test.clj` reports
  one error, `parser.type/->Variable` at `db.clj:690`. The same error appears at HEAD
  before this lane (stale dependency cache), so nothing new.

## Verification limits

- **No armed fixture run of the new test.** Publication is paused, so default's
  program rows do not contain the test and `seon.test/run` cannot select it.
  `bin/test-fast` workers hold no execution handle, so `with-database` refuses there.
  The regression's owner call was proven in a fresh JVM at HEAD, and its
  discrimination was proven against both owners in default. The `with-database`
  wrapper itself has not run.
- `:seon.error/chain` and the root-cause frame come from F0. F0's
  `seon.error.refusal/diagnostic` is still uncommitted in the working tree, so both
  JVMs above ran HEAD's `diagnostic` (class and outermost frame only). Every changed
  site hands the Throwable to `diagnostic`, so F0 gives the whole chain with no
  further edit here.
- Not in scope and unchanged:
  - Census C rows `:1142` `read-evidence-current?` (answers false, meaning
    recompute) and `:3752` `agent-provenance?` (R-PRED, needs an owner ruling).
  - B rows `:594`, `:1707`, `:3283`, `:3816`, `:3818`.
- Schedule #24a (purge report validation at about 0.9 ms per entity) is in
  `write-owned-values-error`'s walk. This lane did not touch that walk, so it is
  not included.

## Timings over 1 s

| operation | wall ms | note |
|---|---|---|
| fresh JVM `require seon.db seon.owned-value-test …` (HEAD snapshot) | 17,287 (19.7 s process) | defect over 10 s; existing class `docs/seon/issues/source-load-is-118s-against-the-ten-second-law.md` (outside this lane's paths, not extended) |
| same, pre-commit snapshot (`proof.clj`) | 20,289 (23.0 s process) | same |
| same, `proof2.clj` | 13,383 (22.9 s process) | same |

Every REPL probe in default finished in 28 ms or less. No boot, publication or
adoption ran. Datahike caches were not consulted beyond each probe's own `with`
values. Dependency classes came from `~/.m2` through the shared `.cpcache`
computation.

RESET NEEDED: no. Default was never stopped, reset, adopted or reloaded.
