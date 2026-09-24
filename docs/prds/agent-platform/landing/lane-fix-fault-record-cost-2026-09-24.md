---
type: landing
status: current
created: 2026-09-24
scope: Lane fix-fault-record-cost. The fault writer's per-projection walk over every declared error schema is replaced by the fault's own declared schema. The 1,716 ms test red is not fully reproduced or explained.
---
# Lane fix-fault-record-cost — landing (2026-09-24)

Inside the writer transaction, `seon.error/commit-call` decides which
diagnostic attributes an occurrence stores. It did this with a whole-population
walk: 294 declared error schemas and 5,585 armed storability checks, cached on
the projection. Any fresh projection (after a declaration change or
publication) paid for that walk on its first fault. The walk now covers only
the fault's own declared schema and `:seon.error/base`.

## Broken or not done first

1. **The 1,716 ms red is not reproduced.** Root's run of
   `record-mode-stores-the-whole-fault-and-wakes-the-responsible-agent` failed
   at fault_test.clj:81. I ran the same fixture path on default: an isolated
   child from the cluster handle, then `test-support/with-database`,
   `apply-config!` and `fault!`. Results:
   - 390 ms for the first fault. The removed walk was 147 ms of that.
   - 168–261 ms for each warm fault.
   The fix removes roughly 100–150 ms from the cold case. That does not account
   for the rest of the 1.7 s, so root must re-run the test. What remains, by
   inclusive armed time on a warm fault:
   - The writer: `seon.db/transact!` takes 128–165 ms. Of that,
     `write-report-error` and `write-owned-values-error` are about 40 ms, and
     `commit-call` is 26 ms. The rest is unarmed Datahike.
   - `error/prepare`: 31–40 ms over two calls. `record!` prepares, then
     `recording` prepares again. `record!` is in `seon.fault`, which this lane
     does not own.
   - Suspected but not proven: the first call after a reload or adoption
     compiles each armed Var's wrapper once. There is also the per-projection
     wrapper recompile described in
     `docs/research/agent-platform/projection-call-cost-2026-09-24.md`
     (the `supplied-wrapper` thrash, 14–23× per call). Both belong to
     `seon.instrument`, not to this lane.
2. **Stored attributes changed for one schema.** Before the fix, "diagnostic"
   meant any attribute that some `:seon.db/attributes` error schema declares.
   Now it means the fault's own schema's attributes, and only when that schema
   declares `:seon.db/attributes`. I compared the old and new sets for all 294
   schemas. Exactly two differ:
   - `:seon.program/deletion-refused-error` no longer stores
     `:seon.db/transaction-refused`. Its declaration says
     `:seon.db/attributes false` ("transient final-transaction evidence"), so
     the old behaviour was the defect.
   - `:seon.db.write/validation-refusal` loses
     `:seon.db.write.attempt/request-id`. Such a fact never stores under that
     schema, because `prepare` renames it to `:seon.db.write/error` and drops
     the key.
   Replacing an existing occurrence uses the same set. That is safe because an
   occurrence's identity comes from the signature, and the signature includes
   the declared schema.
3. Not loaded into default, and no test run (LOAD RULE). Hook publication is
   paused. Root adopts at a checkpoint. RESET NEEDED: no.

## Evidence (MCP JVM, default pid 39156)

| probe | before | after |
|---|---|---|
| diagnostic-attribute derivation, cold projection (fresh `:seon.schema.projection/compiled` atom), 2 samples | 109.0 / 100.4 ms (420 attributes) | 0.79 / 0.71 ms (18 attributes, `:seon.flow/exception-error`) |
| same derivation, warm registry | 116 ms | 1.3 ms |
| first `fault!` in a fixture (after the probe JVM's last adoption) | 390 ms (147 ms of it in `projection-cache-value` → `storable-attribute-in?` ×5,585) | expected ≈ 245 ms (not run: the change is not loaded) |
| warm `fault!` | 168–261 ms | unchanged (the walk was already cached) |
| `error/prepare`, fresh vs. warm projection | 5.0 / 4.4 ms | unchanged |

The old behaviour was probed on the fixture. A
`:seon.program/deletion-refused-error` fault stored
`:seon.db/transaction-refused true` on its occurrence. The new regression
asserts that it does not.

## Changes

- `src/seon/error.clj` `commit-call`: the population-wide
  `::declared-schema-attributes` cache is replaced by
  `[::diagnostic-attributes declared-schema]`, computed over
  `[declared-schema :seon.error/base]`. The occurrence now selects
  `(select-keys source diagnostic-attributes)` directly. +10/−8 lines.
  `declared-schema-keys` stays, because `observation-selector` and
  `call_preparation` still use it.
- `test/seon/fault_test.clj`: new regression
  `a-stored-occurrence-carries-its-own-declared-schemas-attributes`. The
  negative case is deletion-refused, which must not store
  `transaction-refused`. The positive case is `:seon.db/transaction-refused-error`,
  which stores it along with `request-id`. +32 lines.
- Net: src +2, test +32. The bound is not raised.

## Root runs

    bin/test-check default --test seon.fault-test/record-mode-stores-the-whole-fault-and-wakes-the-responsible-agent
    bin/test-check default --test seon.fault-test/a-stored-occurrence-carries-its-own-declared-schemas-attributes
    bin/test-check default --ns seon.fault-test
    bin/test-check default --ns seon.error-test

If the first command is still over 1,000 ms, record the
`seon.profile/explain` lines from the failing run. The prime suspect is
first-call wrapper compilation in `seon.instrument` after adoption, which
needs its own lane.
