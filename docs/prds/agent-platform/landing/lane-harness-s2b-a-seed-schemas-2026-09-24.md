---
type: landing
status: committed, not adopted
lane: harness-s2b-a-seed-schemas
created: 2026-09-24
spec: docs/research/agent-platform/one-test-harness/design-2026-09-24.md
---

# Lane harness-s2b-a-seed-schemas (2026-09-24)

This slice adds `:seon.test/seed` to the indexer's existing deftest row construction (`seon.fn/var-row`).
Seed validation sits beside the existing capability-graph check in `analyzed-files` and in `source-rows`.
It adds no new registry, walk or writer. Scope follows design-2026-09-24.md §2b / §9c S2b-a and
language-requirements 7.9b.

## Broken or open first

- **Over budget.** `src/seon/fn.clj` has +137 net lines (146 added, 9 removed), and the schemas add
  +33. That is above the ~100 guide. Where the lines go: seven named refusal rules
  (`seed-refused!` 12 lines, `assert-seed-contracts!` 27), the three entry forms (`seed-occurrences` 45),
  literal validation (`literal-seed-writes` 18), resolving names through aliases (`seed-symbol` 9), and
  wiring (about 20, 8 of them only re-indented).
  Smaller alternative for root to rule on: drop the form-3 `:seon.test.seed/calls`/`writes` extraction
  (about 15 lines). Those calls already sit on the test's own `:seon.fn/calls`, so the occurrence copy
  mirrors derived data the design asked for.
- **SCI evaluation seam not covered.** A deftest evaluated in an agent's SCI context builds its row
  from the loaded Var in `seon.sci.eval` through `analyze-form`, not through `var-row`. So it stores
  no occurrences and gets no seed validation there. The fix belongs to the owner of
  `src/seon/sci/eval.clj` (not owned here). `seon.fn/source-rows`, which that file calls at :468,
  is covered.
- **`build-artifact`** (single file, no database) derives occurrences and checks literals, but does
  not check that seed functions exist. Only `analyzed-files` (rows/manifest/index) and `source-rows` do.
- Deleting a seed function is refused only when the test file is re-analysed as a dependent. The
  named seed is a `:seon.fn/references` edge of its test, so `dependent-paths` should select it;
  this has not been proven on a branch.
- **Not installed.** Hook publication is paused and nothing here is adopted or loaded into default's
  `seon.fn`. **RESET NEEDED: no.** Root adopts `resources/seon/schemas/seon.test.seed.edn`, the
  `seon.test.edn` change and `src/seon/fn.clj` together at a checkpoint.

## What landed

- `resources/seon/schemas/seon.test.seed.edn` (new): `:seon.test.seed/input` (`:seon.db/db`),
  `/tx-data`, `/value` (open map: optional `/tx-data`, `:seon.config/manifest`), `/ordinal`,
  `/function` (indexed edge symbol), `/writes`, `/calls`, `/occurrence` (entity map), `/refused` (enum
  of seven rules), `/refused-error` (over `:seon.error/message`, rendered by `seon.error/index-refusal-prose`).
- `resources/seon/schemas/seon.test.edn`: `:seon.test/seed` is a vector of owned components
  (`:seon.db/component-schema :seon.test.seed/occurrence`), declared on `:seon.test/test`. Declaring it
  is enough for `program/shapes-in` to own and exactly replace it. Datahike's `retractAttribute`
  cascades its components (`reference-code/datahike/src/datahike/db/transaction.cljc:1091-1095`).
- `src/seon/fn.clj`:
  - `var-row` stores occurrences in authored order.
  - Form 1 records the written attributes. Each value is validated with
    `schema/explain-candidate-value` (Malli `explainer`).
  - Form 2 records the qualified symbol, resolved through the namespace's aliases.
  - Form 3 records the reached calls and the declared map keys.
  - An empty vector adds no key, so the definition digest does not change.
  - `assert-seed-contracts!` resolves each named seed against the batch rows plus the writer's
    database, minus removed rows. It then compares `:seon.fn/spec` with
    `(pr-str (schema/canonical-definition [:=> [:cat :seon.test.seed/input] :seon.test.seed/value] {}))`.
- `test/seon/fn/seed_admission_test.clj` (new): three regressions.

## Evidence (read-only probes on default, PID 39156; fn.clj loaded only into scratch ns `lane-s2b-a.fn`)

- clj-kondo reports `:seon.test/seed` metadata as the raw vector. Symbols in the metadata are
  var-usages attributed to the test (`:from-var`), so a named seed is already in the test's
  `:seon.fn/references`, the edge the reach walk follows (`test/runner.clj:765`). Keywords in the
  metadata carry no `:from-var`, so the occurrence stores the written keys itself.
- `build-artifact` on a three-form fixture produced
  `[{ordinal 0 writes #{:seon.test/skip-reason}} {ordinal 1 function probe.seed-test/week-37}
  {ordinal 2 calls #{probe.seed-test/helper} writes #{:seon.test/skip-reason}}]`.
  The occurrences survived `canonical-row` shape filtering. `build-artifact` took 59.7 ms;
  `build-projection` took 442.9 ms.
- The refusals each name their rule, test, ordinal and member. Rules covered: missing-function,
  uncontracted-function, contract-mismatch, unqualified-symbol, invalid-attribute-value (value 42 for
  `:seon.test/skip-reason`), undeclared-attribute and unknown-form. An empty `[]` is admitted without
  the key.
- Regressions run in scratch copies against the scratch `seon.fn`:
  - refusals: 6 pass, 697 ms;
  - empty seed vector: 1 pass, 436 ms;
  - the first two assertions of the storage test pass (order and reference edge).
  - Its `with-database` storage half was **not run** (load rule).
- Cost: the added work is one pass over a test's seed vector, plus one pull per named seed that is
  not in the batch. The time is dominated by `seon.schema/build-projection` (about 350 ms per
  `seon.fn/rows` call without `:seon.schema/projection`). That cost is foreign to this change; the
  tests build the projection once per deftest.

## Root runs (serially, after adopting the three paths)

    bin/test-check default --ns seon.fn.seed-admission-test
    bin/test-check default --changed seon.fn/var-row
    bin/test-check default --ns seon.fn-test

## Commits

See `git log -- resources/seon/schemas/seon.test.seed.edn`. Released paths: all owned paths listed above.
