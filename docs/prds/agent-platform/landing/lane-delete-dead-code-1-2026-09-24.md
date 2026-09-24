---
type: landing
status: partial
lane: delete-dead-code-1
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/{testing,reading,execution}-2026-09-24.md
---

# Lane delete-dead-code-1: 2026-09-24

This lane deleted one of the three targets: the `analyzer/analyze-forms` family. The
other two still have test callers in files that other lanes hold, so they were not deleted.

## Still broken or not deleted

- **`seon.test.arm` (254 lines) is not deleted.** No src, bin or script code calls it.
  But `test/seon/instrument_test.clj` requires it (`:46`) and two tests call it:
  - `cold-arming-keeps-acquired-predicates-without-an-ambient-binding` (`:929`) calls
    `packaged-test-projection`.
  - `the-selection-is-declared-vars-with-schemas-and-nothing-else` (`:1135`) calls
    `arming-decision` and `arm-contracts!`.

  Default's rows agree: these two tests are the only callers outside `seon.test.arm`
  itself. Both tests check behaviour that stays after the deletion (instrument's
  cold arming and its selection), so they must be rewritten against a surviving owner
  (`seon.instrument/apply!` or `schema.edn/packaged-forms` → `declaration-projection`).
  They cannot simply be deleted. `instrument_test.clj` is held by a1-arming (ledger
  row 20), and `src/seon/instrument.clj` has uncommitted foreign edits.
  `.agents/skills/clojure-testing/SKILL.md:38` also names `arm-contracts!` as the
  enforcement, and it does not own that claim. The skill must change in the same slice.
  **Needs:** instrument_test.clj and the clojure-testing skill assigned to one lane.
- **`error/log-line` and `problems/log-report` are not deleted.** `log-line`'s only
  src caller is `log-report`, and `log-report` has no src caller. They are called only
  by tests:
  - `test/seon/test_failure_facts_test.clj:273`. The file has 44 lines of uncommitted
    edits from another lane, so it is held.
  - `test/seon/problems_test.clj` (`:237`, `:290`, `:376`, `:426-464`)
  - `test/seon/cluster/agent_namespace_test.clj:127-138`
  - `test/seon/error_test.clj`: the `rendered` helper `:676` and its callers `:694`,
    `:1403`, the-log-line-is-one-line-and-derived `:1401`, and `:144`.

  Most of these assertions sit inside tests of surviving behaviour (problem derivation,
  failure readers), where they are one `is` among many. Each file needs a hunk edit, and
  test_failure_facts_test.clj is held. **Needs:** that file released, then one lane edits
  the four test files and both src functions together.
- **Stale issue owner.** `docs/seon/issues/runtime-lint-does-not-resolve-namespace-aliases.md`
  names `seon.fn.analyzer/analyze-forms` as its owner. That function is now deleted. The
  same filter lives on in `seon.fn/runtime-analysis-batch` (`fn.clj:868`, which calls
  `analyzer/referenced-program-namespaces`). This lane does not own the issue file, so
  it was not updated.
- **Not installed on default.** pid 90471 runs a committed archive, so this deletion
  changes nothing there until the next restart. **RESET NEEDED** (or `start --head`)
  to adopt it.

## Proof it was dead

- **rg** across src/ test/ script/ bin/ resources/ .claude/ .agents/ docs/seon/ for
  every deleted symbol: `analyze-forms`, `require-specs`, `namespace-prelude`,
  `newline-count`, `source-spans`, `touches-span?` and `relative-finding`. No hit
  outside analyzer.clj and analyzer_test.clj except:
  - `seon.fn/analyze-forms`, a different live function.
  - `seon.fn/source-spans`, a keyword.
  - `seon.render.ns`'s own private `require-specs`/`stored-arglists`.
  - The issue file above.
- **Default's program rows** (read-only JVM query of `:seon.fn/calls` and
  `:seon.fn/references`, both `:db.type/symbol`). Callers found:
  - `analyze-forms`: only the two analyzer tests.
  - The helpers: only one another.
  - `program-prelude` and `referenced-program-namespaces`: also called by
    `seon.fn/runtime-analysis-batch`, so they were kept, with their helpers
    `function-stub` and `stored-arglists`.

## Change

Commit `0cb5fec13`:
- `src/seon/fn/analyzer.clj`: deleted old lines 658-722 (`require-specs`,
  `namespace-prelude`) and 784-872 (`newline-count` through `analyze-forms`). −154, +0.
- `test/seon/fn/analyzer_test.clj`: deleted old lines 209-311
  (`ordered-forms-use-existing-context-and-original-row-numbers` and
  `reply-analysis-does-not-contaminate-build-analysis`). −103, +0.
- Net: src −154, test −103.

## Evidence

- clj-kondo on both files: 0 errors. One warning that was already there
  (`analyzer.clj:474` shadowed `methods`).
- A `git archive` snapshot of HEAD `e45e27d73` plus this diff, with `reference-code`
  and `.clj-kondo/.cache` symlinked, run with `clojure -M:test`:
  - `seon.fn.analyzer`, `seon.fn` and `seon.fn.analyzer-test` loaded in 36.9 s (cold
    JVM, whole-tree compile).
  - `seon.fn.analyzer-test` under the packaged projection: **7 tests, 35 assertions,
    0 failures, 0 errors**. Before the deletion it had 9 tests and 45 assertions.
  - The whole JVM run took 51.3 s wall with 3.6 GB max RSS. That is cold-JVM
    acquisition, not a probe of this change. The snapshot was deleted afterwards.
- No hot path changed, so no before/after timing was taken.

## Verification limits

- The tests were not run through `seon.test/run` on default. Default runs an archive
  that does not have this commit.
