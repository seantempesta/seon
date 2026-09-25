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
