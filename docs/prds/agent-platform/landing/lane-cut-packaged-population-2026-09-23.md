# Cut 1.3f #41 — packaged population through `clojure.core.cache.wrapped`

Lane cut-1.3f-41, 2026-09-23. README §4 row 1.3f, §7 "Priority to namespace agents"; AGENTS.md "No stamps".

## Change

`src/seon/schema/edn.clj`: the `(atom nil)` + `locking` single-slot cache and
`forget-packaged-population!` are deleted. `packaged-population` is one
`cache/lookup-or-miss` over `packaged-populations`, a core.cache wrapped LRU
(threshold `packaged-population-cache-size` = 2, as data; the same library and
pattern as `seon.db`'s projection memo, `src/seon/db.clj:1276`). A race may
derive twice; `resource-population` is pure. `packaged-population` gains its
contract `[:=> [:cat] :map]`. The var is renamed so a reload does not keep the
old `defonce` atom.

Key: `[resource-url (declaration-stamp)]`, not a digest of the forms. A forms
digest needs the forms, which are exactly what a miss computes, so it cannot be
the lookup key. The stamp (sorted name/length/mtime of the declaring files) is
the cheap identity of the inputs, and it is what the deleted cache already used.

Callers of the deleted `forget-packaged-population!` (rg, whole tree): two
tests, converted to `(cache/seed @#'schema.edn/packaged-populations {})`:
`test/seon/schema/declaration_population_test.clj`,
`test/seon/sci/admit/declaration_population_test.clj`.

Net: src −18 (+15/−33), test +2.

## Evidence (default, pid 90963, MCP eval_clj JVM, ns `tmp.lane-cut-41`)

- clj-kondo on the three paths: 0 errors (8 pre-existing `identity` shadow warnings).
- Parent (installed) `packaged-population`: cold 30.5 ms (forget + read), warm
  0.84 / 0.85 / 0.83 ms.
- New mechanism, evaluated in a throwaway ns over default's own
  `resource-population` and `declaration-stamp`: cold 55.4 / 43.9 ms, warm
  1.7–2.2 ms. `identical?` across hits is true. Result `=` installed
  `packaged-forms`. One entry.
- Interleaved on the same load, 200 calls per sample: parent warm 3.85 / 1.14 /
  3.80 ms, new warm 6.70 / 1.27 / 1.19 ms. Both are dominated by the armed
  `declaration-stamp` (4318 ms across 1407 calls in that probe). They are
  equal within the noise of concurrent lanes' test runs; there is no
  regression over 20 % / 50 ms at the best samples.
- The contract `[:=> [:cat] :map]` compiles under the packaged declaration
  projection. The probe took 2.0 s: `declaration-projection` over the packaged
  forms, proportional to the whole program, which this probe chose to exercise
  and which the change itself does not add.

## Verification limits: adoption and tests NOT run

`bin/seon init --dev default --changed src/seon/schema/edn.clj` was refused
twice with "The source head changed before publication."
(`seon.cluster.source/publish!`), after 69.7 s and then 180.6 s, because
concurrent lanes were running publications and test runs. Both runs are over
ten seconds. They are recorded in
`docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`.
The edit is therefore NOT loaded in default: `forget-packaged-population!` still
resolves there. `bin/test-check default --policy incremental --changed
seon.schema.edn/packaged-population` was not run, because it would exercise the
old loaded code. The two converted tests are unrun. Pending: adopt this file,
then run that one request plus `--ns seon.schema.declaration-population-test
--ns seon.sci.admit.declaration-population-test --ns seon.schema.edn-test`.
RESET NOT NEEDED.
