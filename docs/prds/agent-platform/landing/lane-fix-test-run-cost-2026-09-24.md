---
type: landing
lane: fix-test-run-cost
created: 2026-09-24
status: no source change; three causes measured, owners named
---

# Lane fix-test-run-cost (2026-09-24)

## Broken first

- **PLATFORM: every commit that creates an entity writes about 106 MB.** Default's store
  holds a 105,624,664-character `:seon.test.failure/message` (entity 64270, tx 536872568)
  in its rightmost EAVT leaf. With branching factor 4096, each commit that creates an
  entity rewrites that 105,719,803-byte node. That is about 500 ms per commit for every
  writer, and the store is 61 GB. See
  [issue](../../../seon/issues/a-hundred-megabyte-failure-message-rides-every-new-entity-commit.md).
  **RESET NEEDED** (or a purge of that datom, which is a root decision). The producers are
  `src/seon/test.clj:206`, the `src/seon/instrument.clj` refusal text, and
  `src/seon/test/runner.clj:195`.
- The edits in this lane cannot reach the running JVM. Default runs committed archive
  `ce7384682…` with hook publication `:off`, so a runner.clj edit is not installed until
  root runs `start --head`.
- None of the three named costs has its cause inside runner.clj alone, so no source changed.

## The three costs (verified before any edit)

The named probe was a one-test `seon.test/run`, `:policy :named`, from JVM mode on default,
with `seon.profile/explain` and thread samples. The members were never-run tests, so each
executes once.

| Test | ms | member acquire / resolve / run / release |
|---|---:|---|
| `my.agents.root/largest-test` | 3,077 | 83 / 6 / 44 / 9 (`seon.fn/gate-sets` 1,019, foreign) |
| `my.fs-test/relationship-predicates-require-one-declared-arm` | 1,514 | 52 / 12 / 49 / 9 |
| `my.edit-test/form-operation-relation-is-structural-and-open` | 2,015 | 53 / 11 / 58 / 9 |
| `my.turn-test/a-disposition-is-an-ordinary-value` | 1,802 | 54 / 16 / 50 / 9 |

1. **`program-written-since?`** took 122–145 ms in-request (warm), and 119 ms / 257 MB
   directly on the head. The research figure of 1,596 ms was not reproduced warm. The cost
   is O(program-attribute history). It is not 1.6 s, and it is not proportional to the
   window. The O(1) basis-equality shortcut is **unsound** inside a transaction, and so is
   a comparison on attribute revisions alone (both were probed). Owner design:
   [issue](../../../seon/issues/the-admission-program-write-check-scans-program-attribute-history.md)
   (tested revisions in provenance, needing the schema and test.clj, about 20 lines).
2. **`ambient-snapshot`** took 15.8–16.3 ms / 75.5 MB, twice per member. Of that, 15.3 ms /
   75.3 MB is `seon.instrument/instrumented` (`ns-interns` over 467,616 mappings). The same
   set comes back in 5.5–7.7 ms / 0.17 MB through `.getMappings`. Owner: instrument.clj,
   see [issue](../../../seon/issues/the-drift-snapshot-copies-every-namespace-map-twice-per-test.md).
   The guarantee it protects (wrapper membership, running clusters) is kept. Nothing was
   weakened.
3. **`commit-results!` → `with-publication!`** took 494–607 ms, of which `record-tx` was
   2 ms. The admission transaction costs another ~1,000 ms outside `admit-run` (219–229 ms).
   Both are Datahike commits writing the 105 MB leaf. That was shown by fressian
   `writeString` samples and by bytes written (table in the issue). The cause is not in
   runner.clj.

## Verification limits

There were no after numbers because nothing changed. The retraction probe ran only on an
isolated branch that was then released. A purged store was not measured. This lane ran no
`bin/test-check`, because there was no edit to test. The scratch namespace
`lane.test-run-cost` in default's JVM was removed.

## Next slices (owners)

- test.clj: bound or blob the execution-failure text.
- instrument.clj: path-and-shape refusals, and `.getMappings` in `instrumented`.
- runner.clj (this lane's file): stringify `testing` contexts, one line plus a regression.
- root: purge or reset default.
