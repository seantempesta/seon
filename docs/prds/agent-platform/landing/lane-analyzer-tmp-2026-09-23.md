# Lane analyzer-tmp (2026-09-23)

Defect: `docs/seon/issues/analyzer-refuses-a-checkout-file-outside-source-roots-as-a-foreign-kondo-entry.md`.

## Decision (B1 §2e)

The mirror keeps the source's absolute path and `analyzed-source-path` reads it
back, so this run's `defined` map already names each namespace's real source.
"Foreign" means an entry for a checkout file outside every source/dependency
root that THIS analysis did not define: a leftover from another run. An entry
of a namespace this run defines at that path was written by this run from the
current bytes. The fix adds `(nil? current)` to the `::foreign` condition
(`current` non-nil and different is already `::moved`). Work stays
proportional to the used namespaces, with one extra map lookup per entry. No tmp special-case.
§2e cases unchanged: deleted/renamed/`.clj`↔`.cljc` go through `forget-namespaces!`;
mirror-backed entries still map to the checkout path; agent forms still lint
`:cache false`; `::moved`, `::source-absent`, `::stdin` and `::modified` come first or are untouched.

## Evidence (default pid 90963, JVM mode, ns `tmp.analyzer-tmp`)

- Before (fixture source written to `tmp/analyzer-tmp-probe/<uuid>/sample/s1.clj`,
  `(seon.fn/rows {:seon.fn/roots [root]})`): threw "clj-kondo cache entries changed
  again during analysis." with `::reason ::foreign` for `sample.s1`, 570 ms.
- Adopted: `bin/seon init --dev default --changed src/seon/fn/analyzer.clj`
  → source commit `6ab3568a-21c7-5017-9880-920782fdf19f`. The first try was refused
  "The source head changed before publication." (a concurrent publication moved `:current-src`).
- After: same form returns 6 rows, 475 ms.
- Foreign kept: `stale-cache-entries` on the shared `.clj-kondo/.cache/v1` with a
  result that uses but does not define `sample.s1` → `[[::foreign nil]]`; with the
  defining result → `[]`.
- `bin/test-check default --policy named --test seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`:
  the analyzer throw is gone. The test is still RED in its evaluated half, "Parity turn refused":
  `seon.db/transact!` refused `[47444 :seon.program/definition-digest]`,
  Entity `#:seon.ns{:name sample.s1}`. This is the identity-only namespace-row
  class (`docs/seon/issues/turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md`,
  its unconverted `source-rows` residue in `src/seon/turn.clj`), outside this lane.

## Timings

| operation | wall ms | justification |
|---|---|---|
| init --dev (refused) | 36061 | DEFECT >10 s. The profile window includes other lanes' concurrent test runs (`seon.test-support/with-database` x40, 97 s inclusive), so it can't be attributed to this adoption. Covered by the orchestrator's publication-path notes |
| init --dev (adopted) | 37554 | same; profile shows `seon.test/run` x1 at 126 s inclusive running in the window |
| test-check named | 32646 | DEFECT >10 s: in-process run on a branch of default, measured while other lanes' runs were active; the test body itself refuses early |
| probes | 475–586 | sub-second |

Hot-path probe: parent 570 ms (throws), self 475 ms (returns). The added check is one map lookup.

## Changed paths

`src/seon/fn/analyzer.clj` (+5 −2 src lines: net +3, comment and docstring included),
this note, the issue note. No contract touched. RESET NEEDED: no.
