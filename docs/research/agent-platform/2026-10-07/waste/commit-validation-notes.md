# commit-validation lane notes (2026-10-07)

Tasks: `seon.db/every-commit-validates-its-write-report-three-ways`,
`seon.db/hot-total-predicates-run-hundreds-of-millions-of-times` (resources/seon/tasks/seon.db.edn).

## What was measured (default pid 30874, running a8e3bde5b; agent `commit-validation-probe`)

Probe: per-cell profile counter diffs around `seon.mcp/call` eval_clj (jvm-mode helpers `cv-run`, `cv-sample2`).

- The "three validations" are ONE nested call: `validate-write-report` -> `write-report-error` ->
  `write-owned-values-error`. The 30/28/20 ms in the task are inclusive times of the same stack, not three passes.
- `(+ 1 2)` commit: transact! 28.7 ms; validate-write-report 1.3 ms (write-owned-values-error 1.0 ms);
  transaction-report-datom? 560 calls, < 0.1 ms total. Already under the 5 ms target.
- A NEW contracted defn (new function name, 4 runs): transact! 320-450 ms; validate-write-report 189-315 ms;
  write-owned-values-error 162-198 ms, of which `seon.schema.datahike/vector-attributes-in` is 143-177 ms
  (4,032 vector-shaped-attr-in? and 11,729 compiled-storage calls). report-arity-comparison 3-5 ms;
  write-attribute-plans 3.5-9 ms. Stack samples: 36 of 47 samples inside write-owned-values-error sit in
  vector-attributes-in > vector-shaped-attr-in? > compiled-storage (malli walk).
- Redefining an EXISTING function: validate-write-report 4-6 ms (projection unchanged, cache warm).
- Direct: on default's carried projection (3,389 forms), `vector-attributes-in` on a projection without its
  holder = 115 ms; asking `vector-attribute-in?` for 6 named attributes = 0.05 ms.

## Cause

A declaration write leaves a new projection (fresh compiled-cache holder, `seon.schema/with-compiled-cache`).
`vector-attributes-in` answers "which of ALL registry forms are vector-shaped", one `compiled-storage` malli
fold per form: whole-program work, recomputed once per new projection, i.e. on every defn commit. Its three
callers (all in seon.db: `write-entity-value`'s outside-cell refusal, `write-owned-values-error`'s `swept`
map, `logical-datoms`) only ever test the attributes of the datoms they hold.

## Fix (smallest correct)

`vector-attributes-in` takes the attributes its caller holds and answers the subset stored in cells: one
per-attribute question (`vector-shaped-attr-in?`, cached per projection per attribute) per DISTINCT attribute
the caller holds. The per-datom loops still test membership in one set (keeps the 3.0 M-question win its
docstring records). Every caller converted in the same commit. Cost: O(distinct attributes in the report or
entity) instead of O(registry forms) per new projection. No rule changes: the same attributes answer true.

Expected: new-defn validate-write-report ~190-315 ms -> ~35-140 ms (minus 143-177 ms).

## Not doing (and why)

- transaction-report-datom? 20 calls per datom: every armed function contracted on
  `:seon.db/transaction-report` (about 20 boundaries per commit, in and out) validates the whole `:tx-data`
  through the armed predicate leaf. Cost per trivial commit < 0.1 ms (95 ns/call armed). Removing the
  repetition needs either (a) weaker internal contracts for the in-writer report or (b) schema predicate leaves
  resolving to the unarmed function (`seon.schema/runtime-predicate`, platform-wide). Both are owner decisions;
  reported, not done. The law test the task names is added so the predicate is a tested contract leaf.
- write-attribute-plans builds a plan for every installed attribute per new projection (3.5-9 ms): same class,
  smaller; left for a follow-up (callers already fall back per attribute in write-entity-value).
- load-projection / carried-projection rebuild per declaration commit (~150 ms in transact! outside validation):
  projection owner's area (seon.schema), not this slice.
- seon.program/edn-round-trip-symbol?: 8 calls on a defn commit; not on this path.
