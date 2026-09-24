---
type: landing
status: landed
lane: fix-render-pair-selection
created: 2026-09-24
issue: docs/seon/issues/a-declared-render-pair-on-an-all-optional-map-is-never-selected.md
---

# Lane fix-render-pair-selection (2026-09-24)

`seon.schema/shape-row-in` builds the shape rows, and `matching-shapes-in` reads them.
`seon.render/schema-producers` picks a declared pair from those rows. Before this change,
a map schema got a shape row only if it had a required attribute or was an entity
(`:seon.db/attributes`). The fix gives a row to a map that declares a `:seon.render/*`
pair too. The row is indexed by the map's declared entries, the same way all-optional
entity rows already are (`shape-projections`, `shape-index-attributes`). Selection still
needs one of the schema's own declared attributes to be present and the value to validate
against that schema. Structural resemblance alone does not select a row.

## Broken or unresolved first

- **The live default projection does not pick up the fix yet.** RESET NEEDED, or a
  declaration change that forces a full projection rebuild.
  - `seon.db/carried-projection` memoizes projections by declaration content and revision
    (`src/seon/db.clj:1410-1424`). Incremental rebuilds (`shape-projections-with`) keep
    unchanged rows.
  - A change to the builder code (`shape-row-in`) is not part of that key. After adoption,
    `(@#'seon.schema/shape-row-in registry :seon.problems/problems)` returns the new row,
    but the cached live projection still has none, and `schema-producers` still answers
    `[]` on the live problems value.
  - This follows the law "key derivations by what they read": the derivation also reads
    the builder's code. The gap is recorded in the issue (open, reassigned to that owner).
    Fixing it crosses `src/seon/db.clj`, which is not owned here.
- **Class 9 of the red-baseline census is a different mechanism.** The fix does not turn
  those tests green.
  - The failing tests expect the owning namespace's SCI fixture renderer (selection
    stage 3). The value `{:seon.ns/name …}` already has a required-attribute shape row.
  - The failures show namespace-stage misses: "B:…" expected, attribute map got, and
    stage statuses `[:no-match :no-match :no-match :no-match :selected]`.
  - The run that covered them also has foreign errors: "SCI context did not acquire the
    tested program" and "Host-bound declaration seon.test/admit-run must change through
    the loaded source files".
  - `bin/test-check default --ns seon.render-simplification-test`: run 3f160a95418b,
    pass 33 / fail 11 / error 24, 124.9 s. That is before and after this change;
    shape-row selection is not involved.
- **Foreign reds in the hook's change-set run** (run 60d2c758dbcc, pass 263 / fail 28 /
  error 10): the my.agent, my.note, my.plan and my.message fixture writes are refused for
  `:seon.agent/branch` and `:seon.message/to`, as recorded in the fix-problems-prose-bound
  landing. None of them is a seon.schema-test red.

## Census (default pid 99265, JVM `eval_clj`, live projection)

- 358 schemas declare `:seon.render/{ai,html,form}`.
- 11 of them are not map shapes. They are handled by `attribute-producer`, not by shape
  rows:
  - 8 `[:and {render…} :seon.db/ref]` attribute schemas, for example `:seon.agent/plan`,
    `:seon.message/to` and `:seon.error/agent`
  - `:my.note/notes` (a vector)
  - `:seon.runtime/turns` (a set)
  - `:seon.print/elision` (an `:or`)
- 347 are map or `:and` shapes. 346 had a row before the fix; `:seon.problems/problems`
  was the only one without.
- Sample check: a Malli sample was generated for each of the 347 (seed 7, size 3).
  - 205 generated.
  - 142 did not generate, mostly error schemas with `:fn` members. All 142 have required
    attributes, so they have rows.
  - All 205 samples validate. 204 match their own schema; the one that does not is
    `:seon.problems/problems`.
  - 203 declare an AI renderer. 202 have it selected by `schema-producers`; the one that
    does not is `:seon.problems/problems` (`[]`).

| schema class | has required key? | row before | selected before | selected after |
|---|---|---|---|---|
| required-attribute maps/errors (≈300) | yes | yes | yes | yes |
| all-optional entities, e.g. `:my.agent/settings` | no | yes (entity rule) | yes | yes |
| `:seon.problems/problems` | no | **no** | **no** | yes (fresh projection) |
| attribute-scoped `[:and … :seon.db/ref]` (8) plus 3 non-map schemas | n/a | n/a | via attribute | unchanged |

## Evidence

- The live problems value has the keys failed-tests-unknown, failed-tests,
  error-signatures, failed-runs and errored-receipts. It validates against
  `:seon.problems/problems`.
- Before: `matching-shapes-in` returns `[]` and `schema-producers` returns `[]`.
- After, on the live projection with the new row and index entries: `matching-shapes-in`
  returns `[:seon.problems/problems]` and `schema-producers` returns
  `[seon.problems/ai-prose]`. `{:seon.problems/failed-tests []}` also matches.
- Timing over 20 iterations:

  | call | before | after |
  |---|---|---|
  | `matching-shapes-in` | 0.005 ms | 0.077 ms |
  | `schema-producers` | 0.080 ms | 0.171 ms |
  | `shape-row-in` for the one row | – | 0.012 ms |

  The added cost is the validation that selection now performs. It is far below the
  50 ms rule.
- Regression: `seon.schema-test/an-all-optional-map-declaring-a-render-pair-is-a-shape`.
  It checks three things:
  - An all-optional map with a declared pair selects its `:seon.render/ai`.
  - `{}` matches nothing.
  - An invalid value matches nothing.

  `bin/test-check default --test` on it plus `map-shapes-accrete-additional-top-level-attributes`
  and `matching-shapes-derive-required-attributes-through-and-refs`: run ef88b0ad8db6,
  pass 9 / fail 0, 2.5 s.
- Not proven red on the parent by a test run, because the lane cannot redefine default's
  Vars. The old `shape-row-in` produced no row for this form, as the live `:in-proj false`
  observation shows for the identical problems form.

## Lines and commits

- `src/seon/schema.clj` +14 / −10 (net +4).
- `test/seon/schema_test.clj` +22 / −0.
- Issue updated. Released paths: `src/seon/schema.clj`, `test/seon/schema_test.clj`.

## Verification limits

- The render path through SCI on live default was not exercised, because the cached
  projection is stale (see above). Paint was not observed.
- The bulk and platform tiers were not run.
