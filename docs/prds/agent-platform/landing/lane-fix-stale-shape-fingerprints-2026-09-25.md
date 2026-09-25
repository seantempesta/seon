---
type: landing
lane: fix-stale-shape-fingerprints
status: complete
created: 2026-09-25
---

# Lane fix-stale-shape-fingerprints: a schema edit re-derives its dependents' shapes

Incremental publication (`seon.fn/desired-rows`) re-shaped only rows whose own form
changed. A shape's fingerprint carries its references' definition fingerprints
(schema_shape.clj:115-143), so dependents kept stale shapes. The fix widens the same
derivation to the projection's reverse closure
(`schema/dependent-schema-keys`, schema.clj:975, over
`:seon.schema.projection/reverse-schema-dependencies`) and to that closure's direct
function consumers (`:seon.schema.projection/reverse-function-dependencies`).

## Broken first

- **Default still carries the split** until root republishes. **RESET NEEDED** if a
  full `bin/seon init --dev default` leaves stored rows stale. The stored base commit
  holds eb3694… and 9a103b… for `:seon.env/environment`.
- **A hub-schema edit costs over one second.** Editing `:seon.db/lookup-ref-value`
  (closure: 797 schemas and 728 functions) re-derives 1525 rows in 3.05 s:
  - 2425 ms is canonical schema rows, dominated by `seon.test.accretion/generatable?`
    (1594 calls, 4457 ms inclusive with recursion);
  - 562 ms is contract facts;
  - 63 ms is the read.
  - The cost follows the changed closure, as ruled. The excess is
    `seon.test.accretion/generatable?`, which that owner must fix. Out of scope here;
    root should file or assign it.
  - 31 of 3391 schemas have a closure over 100.
- Not done: widening `index!`'s `previous-identities`. It decides removals only
  (`reconcile-tx-in`, fn.clj:3339). Every desired row is upserted (:3351), so the
  dependents reconcile because they are in `rows`. Widening it would add nothing.

## Evidence (read-only MCP JVM probes on default; fn.clj not loaded there)

- Leaf edit: `:seon.flow/work-launcher` gains a `:description` property.
  - `changed-schema-keys` returns `#{:seon.flow/work-launcher :seon.operator/request}`
    in 1.1 ms. The `:seon.operator/request` entry is packaged-vs-carried drift that
    already exists.
  - The closure is 8 schemas in 0.02 ms. Their functions number 47.
- Before, `desired-rows` took 255 ms and returned 2 rows.
- The added work, composed from the same owners at the REPL:
  - canonical rows for the closure: 3.8 ms;
  - `published-index-rows` for 47 functions without arities: 5.9 ms;
  - `add-contract-facts` over 55 rows: 93 ms. That figure includes `prepare-forms`
    (~70 ms), which the old path already pays once, so the marginal cost is ~30 ms.
  - Result: all 55 rows carry ONE `:seon.env/environment` fingerprint.
- Re-deriving stored default rows (`:seon.env/environment`, `seon.db/supplied-connection`,
  `seon.env/scope`) through `with-contract-facts` against the current projection gives
  all three 9a103b…. Stored: schema row eb3694….

## Changes

- src/seon/fn.clj:
  - new private `shape-dependent-rows`, which reads the closure's stored schema and
    function rows (minus rows already derived, and minus keys the projection no longer
    has) and drops their stale shapes;
  - `desired-rows` selects packaged forms by the closure, and appends the stored
    dependents for re-derivation by `add-contract-facts`.
  - +33/−2.
- test/seon/fn/shape_dependents_test.clj (new):
  `a-schema-edit-re-derives-its-dependents-shapes`. It asserts that a leaf edit
  re-derives the dependent schema row, a supplier and a consumer, all with one
  environment fingerprint. +49.

## Root runs

```
bin/test-check default --test seon.fn.shape-dependents-test/a-schema-edit-re-derives-its-dependents-shapes
bin/seon init --dev default
bin/test-check default --ns seon.call-preparation-test
```

Then run the census J list in lane-fix-sci-zero-arg-and-names-2026-09-25.md.

## Limits

- No test was run (LOAD RULE). Linted with 0 errors.
- fn.clj was not loaded into default (no redefinition of default's Vars). The new
  composition is proven only piecewise, by REPL calls to the same owners.
- The regression's bound is untested. The old `desired-rows` alone takes 255 ms on
  default.

## Follow-up: stored drift converges without a reset (second commit)

- **Root's hypothesis was refuted.** Root adopted a0d2db39a with a full
  `init --dev`, and default kept `:seon.env/environment` on eb3694….
  - Reconciliation does write a shape-only change. `reconcile-tx-in` over the
    re-derived environment row retracts the eb3694… ref and adds 9a103b… (6 tx
    operations, read-only probe).
  - The cause is upstream of it. Even an `:all` reanalysis publishes partially, with
    every file as a changed path (cluster.clj:1999-2066). `desired-rows` then keeps
    only rows whose authored content changed. A row whose only stale part is derived is
    never re-derived.
- **Fix:** the new private `drifted-schema-keys` gives the stored schema rows whose
  shape fingerprint differs from the projection's definition fingerprint
  (`:seon.fn.schema-shape/reference-fingerprints` on `prepare-forms`). Those keys seed
  the same closure as a changed schema.
  - `prepare-forms` is now computed once and handed to `add-contract-facts` (new
    arity), so the steady state adds only the 0.3 ms query.
- **Convergence publication on default** (composed read-only from the same owners):
  - 25 drifted keys, a closure of 27, and 70 functions, giving 97 rows and 688 tx
    operations.
  - Every row carries one environment fingerprint, 9a103b…, and
    `seon.db/supplied-connection` is among them.
  - Time: canonical rows 49 ms, read 10 ms, facts 111 ms, reconcile 37 ms.
- **Residue, not healed:** 39 still-referenced named shapes carry stale fingerprints.
  Some belong to keys whose own schema row is fresh, for example
  `:seon.flow/work-launcher`. Their function consumers heal only when a row reaching
  them is re-derived. Detecting them needs a liveness check over the shape-ref
  attributes: `:seon.fn.argument/schema`, `:seon.fn.arity/return-schema`,
  `.child/schema`, `.entry/schema` and more. That would be a hand-listed set, so it
  is left for root to rule on.
  - 13 stale named shapes are orphans (unreferenced).
- **Regression:** `a-publication-re-derives-shapes-an-earlier-one-left-stale`. It points
  the environment row at another shape, publishes with no change, and asserts the row
  and its supplier converge.

Root runs: `bin/test-check default --ns seon.fn.shape-dependents-test`, then
`bin/seon init --dev default` (any publication converges the drift), then
`bin/test-check default --ns seon.call-preparation-test`.

## Follow-up 2: the contract refused built-in keys (third commit)

- **Defect:** `drifted-schema-keys` declared its fingerprint map's keys as
  `:qualified-keyword`. The projection's definition fingerprints include built-in keys
  such as `:inst`, so the armed contract refused every call.
  - It now uses the registry's key schema, `:seon.schema/key`
    (seon.schema.edn:75, `:keyword`). `shape-dependent-rows` does the same.
- **Why default did not converge:** the second init found no change, so it returned the
  published commit (cluster.clj:2009) and never ran `desired-rows`. Nothing refused
  silently. Default's error facts hold no `seon.fn/drifted-schema-keys` error, and the
  environment row is still eb3694….
- **Publication hazard:** default has the refusing contract loaded (the Var and the
  4-arity `add-contract-facts` resolve). Publishing this fix through the loaded code
  runs the old `drifted-schema-keys` and refuses at `:inst`. Root must first reload
  `seon.fn` from the checkout (or restart default), then publish.
