---
type: landing
lane: fix-history-keeps-its-rules
status: landed-installed
created: 2026-09-24
---

# Lane fix-history-keeps-its-rules (2026-09-24)

Before this change, `seon.schema/projection-registry` ran the stored-nil
authoring rule (`assert-non-nilable-value-schema!`) over every declaration it
compiled. That included an as-of view's historical rows. Now the rule runs only
when a new or changed declaration is admitted, and a projection build checks
only what it needs to compile.

## Broken or unproven first

- **A second authoring rule still runs during projection builds:
  `assert-config-display!`** (`src/seon/schema.clj`, in the per-agent dial
  display-label check). It is called from `declaration-projection`, from the
  full branch of `build-projection`, and for changed keys in
  `projection-with-declarations`, which is the path an as-of view takes. If
  that rule ever tightens, it will break old views the same way. I did not
  move it, because `test/seon/config_test.clj:62` asserts that
  `build-projection` refuses, and that file is not mine. Its owner should move
  the rule to `projection-with-schema` and update that test.
- **`seon.schema.datahike-test/unsupported-database-attributes-refuse-at-one-rule`
  is red. My change does not cause it** (run be72a15bc926).
  - The test calls `schema/register!` outside a registration delta. That now
    refuses with `:seon.schema/registration-outside-delta` for every form that
    is not nilable. I checked this live for `[:string {:db.secondary/only true}]`.
  - Only the nilable case passes, because `register!` checks nil before the
    delta.
  - The test is stale against `register!`. Its owner is
    `test/seon/schema/datahike_test.clj`. I filed no issue because I do not own
    the issue path.
- **Publication race.** The hook's first two `refresh-source!` calls for
  `schema.clj` and `internal.cljc` were refused with `:stale-branch-head`,
  because other lanes were publishing at the same time. Test publications that
  came after them loaded only the test namespace. To install the change, I made
  the hook's own call through MCP myself:
  `(seon.cluster/refresh-source! root [schema.clj internal.cljc] "default" …
  {:seon.source/gate? true})`. The MCP call timed out at 120 s, but the change
  loaded: `build-projection {:tmp.fhk/a [:or :nil :string]}` changed from
  refused to built. I did not see the gate's own result map.
- **Not in scope: as-of projection keying cost.** This is the issue's second
  half, owned by `seon.db`. A cold as-of projection still costs 263–273 ms per
  time point on default, and a warm one costs 3.7 ms.

## Change

| File | Change |
|---|---|
| `src/seon/schema.clj` | `projection-registry` no longer calls `assert-non-nilable-value-schema!`. |
| `src/seon/schema.clj` | `projection-with-schema` calls it on `structural-schema definition`, but only when the definition differs from the projection's current form. |
| `src/seon/schema/internal.cljc` | The docstring describes the rule as an admission gate. |

- `projection-with-schema` is the one-replacement admission entrance. The edit
  hook's `schema.edn/admit` reaches it, as do SCI evaluation, turn settlement
  and program rows. `register!` and `seon.schema.admission` already call the
  rule themselves.
- `build-projection`, `load-projection` and replacement from a base
  (`projection-with-declarations`) no longer judge the rule.

## Evidence (default, pid 70372)

Live as-of reads:

| Read | Before | After |
|---|---|---|
| `projection-from-database (as-of db 536870928)` | refused with `schema/register! :seon.flow/callback-result: [:or :nil …]` | built; the old form `[:or :nil :map …]` is readable, 273 ms cold, 3.7 ms warm |
| `projection-from-database (as-of db 536871170)` | refused | 263 ms |
| `seon.db/q` over the as-of view | — | 3378 rows, 7.4 ms |

Admission of a new declaration:

- `projection-with-schema` of a new `[:or :nil :string]` is still refused with
  `:seon.schema/nilable-value-schema`, in 0.33 ms.
- An admitted replacement costs 2.0–2.5 ms. `structural-schema` adds about
  2 µs to that.

Tests:

- `--test seon.schema-test/a-nilable-value-schema-is-refused-at-admission-never-at-build`:
  10 pass, 1657 ms (run 69ff5bf90c9b). This replaces
  `a-nilable-value-schema-gets-one-verdict-at-build-and-replacement`. It
  asserts that admission through `projection-with-schema` refuses both nilable
  spellings, and that both a full build and a derived build of an existing
  population containing them succeed.
- `--ns seon.schema.admission-gate-test`: 53 pass, 8.3 s for the whole
  namespace (run ea8dc9944650). The register and hook verdicts for both nilable
  spellings are unchanged.

## Size

- Source: +18 −9 (`schema.clj` +12 −4, `internal.cljc` +6 −3).
- Tests: +13 −9.

No RESET NEEDED.
