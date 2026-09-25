---
type: landing
status: current
created: 2026-09-25
scope: Publication refused HEAD — the static indexer qualified a bare Malli predicate schema into clojure.core/ifn?
---
# Lane fix-publication-invalid-schema (2026-09-25)

The static indexer (`seon.fn/qualify-schema-symbols`, the producer of every
`:seon.fn/spec`) qualified each bare clojure.core symbol in a contract. A bare
`ifn?` in schema position is Malli's own default-registry predicate schema.
`clojure.core/ifn?` names no schema, so the whole projection was refused. Fix:
the walker keeps a simple symbol that `m/default-schemas` names as it is. It
still resolves symbols inside `[:fn …]` exactly as before.

## Broken / open first

- **Refusal shape (task 4) is not applied. It needs a schema EDN edit held by
  render-dispatch-opus.** The contract loop in `seon.schema/projection-registry`
  (schema.clj:631-632) rethrows Malli's bare `:malli.core/invalid-schema`,
  naming neither the function nor its contract. `:seon.schema/invalid-schema` is
  typed `:seon.schema/key`, so a qualified-symbol identity does not fit it.
  Proposed patch, for root to sequence:
  - resources/seon/schemas/seon.schema.edn: add
    `:invalid-contract :qualified-symbol` and `:invalid-contract-error [:map
    {…render pair… :error/message "must identify the function whose contract is
    not a valid Malli schema"} [:seon.schema/invalid-contract
    :seon.schema/invalid-contract] [:seon.error/message :seon.error/message]]`.
  - schema.clj:631: `(try (mr/schema registry identity) (catch Exception e
    (throw (ex-info (str "Function contract " identity " is not a valid Malli
    schema: " (ex-message e)) {:seon.schema/invalid-contract identity
    :seon.error/message …} e))))`. The outer `refuse-unresolved-reference!`
    still reads the whole cause chain. Add the error to
    `projection-registry`'s result alternatives. About 10 lines.
- default health is still `:faulted`. The two recorded `seon.schema/projection-registry`
  faults (signature d69148fb…, last at 15:23:19Z) stay open. They are history,
  not current failure. Root resolves them. Other open faults predate this lane.
- `:seon.sci.eval/function` is **not** the cause, so the root/render-dispatch
  redefinition is independent of this fix.

## Diagnosis (exact form)

- Failing contract: `seon.cluster.agent-test/routing-trial`,
  test/seon/cluster/agent_test.clj:1831, added by 146b8a5fb (14:48Z, after the
  last good publication). It read
  `[:=> [:cat [:sequential :keyword] ifn?] :map]`.
- The indexer turned it into `[:=> [:cat [:sequential :keyword] clojure.core/ifn?] :map]`.
- Smallest reproduction at default's REPL:
  `(m/schema [:=> [:cat [:sequential :keyword] 'clojure.core/ifn?] :map])` →
  `{:type :malli.core/invalid-schema, :data {:form clojure.core/ifn?, :schema clojure.core/ifn?}}`,
  which is identical to the init refusal.
- Census: 2093 stored specs contain no other bare predicate symbol in schema
  position. fn_test.clj:347 had pinned the broken string
  `"[:=> [:cat clojure.core/fn?] clojure.core/string?]"` in a fixture that never
  compiled.

## Bootstrap gap (why the test form also changed)

The first init with only the fn.clj fix failed identically (same signature d69148fb…, 6 s, 15:23:20Z; raw output shape as tmp/orchestrator/init-big3.txt).
Publication analyses with the indexer already *loaded*, so the fix to the
indexer cannot publish itself while its own input is refused. agent_test.clj:1831
now reads `[:fn ifn?]`, the house form of every other stored contract. That lets
the loaded indexer publish HEAD, which then loaded the fixed fn.clj. A bare
Malli predicate is admitted from now on.

## Evidence

- `bin/seon init --dev default` → exit 0, 25.6 s. New `:seon.source/commit-id
  #uuid "6ab6920a-9255-5705-9ff4-c9af4d830ed6"`, reconcile converged. Raw output
  is in tmp/fix-publication-invalid-schema/init-2.txt.
- `bin/seon status` shows no `:stale-vars` count (it was 3 before the init).
- Loaded Var after adoption: `(#'seon.fn/qualify-schema-symbols '[:cat ifn? [:fn ifn?]] {})`
  → `[:cat ifn? [:fn clojure.core/ifn?]]`.
- A scratch-namespace probe (tmp.fix-publication-invalid-schema) covered the same walker.
  Aliases in `[:fn str/blank?]` and `:gen/gen g/foo` still expand. The fixed
  routing-trial contract compiles with `m/function-schema`.
- Init timing: 25.6 s wall. The top armed costs are `seon.cluster/refresh-source!`
  (75.7 s inclusive over 3 calls), `seon.schema/call-with-projection` (42.0 s over 113),
  `seon.db/transact!` (10.3 s over 3) and `seon.fn/analyzed-artifacts` (10.0 s over 6).
  This lane did not introduce these costs. The walker is O(form size), per contract, as before.
- **Not run:** focused tests (load rule).

## Root runs

- `bin/test-check default --test seon.fn-test/static-index-preserves-the-jvm-row-contract`
  (the class regression: a bare Malli predicate stays bare, and the indexed spec compiles).
- `bin/test-check default --test seon.cluster.agent-test/wake-routing-conservation-property`
  (the routing-trial caller; its contract form changed).

## Files, lines, commit

- src/seon/fn.clj (+24/−13)
- test/seon/fn_test.clj (+7/−3)
- test/seon/cluster/agent_test.clj (+1/−1)
- this note

Net source +11, test +4. RESET NEEDED: no. Released paths: all of the above.
