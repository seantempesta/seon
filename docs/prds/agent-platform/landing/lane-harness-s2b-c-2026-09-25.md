---
type: landing
status: items 1 and 2 committed (code only, load rule); S2b-c proof STOPPED on ownership
lane: harness-s2b-c-opus
created: 2026-09-25
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §2 step 2, §2b "REPL route", §9c S2b-c
prior: lane-harness-s2b-a-seed-schemas-2026-09-24.md, lane-harness-s2b-b-2026-09-24.md, lane-harness-s4-2026-09-24.md
---

# Lane harness-s2b-c (2026-09-25)

**What exists and the approach.** An SCI deftest is analysed by `seon.fn/source-rows`, which
`seon.sci.eval/definition-rows` calls (eval.clj:468). Its settlement is analysed again by
`seon.turn/analyze-settlement`, which calls `seon.fn/analyze-form` and then `analyzed-form`.
Both fixes sit in the existing owners: one shared helper in `seon.fn`, and one digest comparison in
`seon.test/apply-seeds!`.

## Broken or open first

- **The S2b-c proof slice is not written. It stopped on ownership.** The §2b REPL route changes
  `my.test/run-owned` and its request and return schemas so they return the full `seon.test/run`
  result. It deletes the assigned-namespace selector in `owned-symbols`, exposes the SCI
  `(run-tests)` alias, and proves red, seed refusal and green from an agent assigned to another
  namespace. That work needs files that are not on my row:
  - `src/my/test.clj`;
  - `resources/seon/schemas/my.test.edn` (`:my.test/run-request` and its return);
  - `test/my/test_test.clj` (proof home), plus the callers `rg` finds of `run-owned`, `owned-symbols`
    and `my.test/run`: `src/seon/bootstrap.clj`, `src/seon/test/accretion.clj`,
    `test/my/examples_test.clj`, `test/seon/test/accretion_test.clj`, `test/seon/loop_proof_test.clj`,
    `test/seon/cluster/{agent_identity,reply}_test.clj`, `test/seon/context_blocks_fixture.clj`,
    `test/seon/{reader_fences,run4_replies}.edn` and `.agents/skills/clojure-testing/SKILL.md`.

  The selector's deletion (`owned-symbols` in `src/seon/test.clj`) is on my row, but deleting it
  without converting `run-owned` would break HEAD's load. Root must first transfer the files or
  re-cut the slice.
- **Item 1's premise was mostly false.** Seed occurrences and seed admission already reach the SCI
  path through `source-rows`. On MCP branch `harness-s2b-c`, SCI evaluation of
  `(deftest ^{:seon.test/seed [{:seon.test/skip-reason 42}]} bad-seed ...)` refused with
  `:seon.test.seed/refused :invalid-attribute-value`, ordinal 0. What was actually lost is the
  **seed keywords**: `analyzed-form` dissocs `:seon.fn/keywords` and re-derives them from kondo
  holders, and metadata keywords have none. On default, a row carrying
  `#{:seon.test/skip-reason}` came back from `analyze-form` with no `:seon.fn/keywords`. The
  occurrences survived.
- **The item 2 regression depends on the canonical writer.** Its seed writes its own test row
  through `seon.fn/reconcile-tx` over `source-rows` rows on the member branch. If the member's
  checked writer refuses a program-row write, the test reports "could not prepare its world"
  instead of the new message. That is a loud red, but it would name the wrong cause. The digest
  path itself has not executed: only the no-seed branch ran, in a scratch copy of `seon.test`.
- **Not adopted and not run (load rule).** Hook publication is paused. RESET NEEDED: no.

## Evidence (default pid 67823, JVM mode; scratch namespaces `lane-s2bc.fn` and `lane-s2bc.test` since removed; branch retired)

| probe | observation |
|---|---|
| `seon.fn/source-rows` on a seeded deftest (default's loaded fn) | `:seon.test/seed [{ordinal 0 writes #{:seon.test/skip-reason}}]`, 19 ms |
| SCI deftest with invalid seed value, MCP branch | refused `invalid-attribute-value`, 27 ms, 26.8 MB allocated |
| `analyze-form` before the fix | the row's `:seon.fn/keywords #{:seon.test/skip-reason}` was **dropped**; seed kept |
| scratch copy of the patched `fn.clj`: `source-rows` → `analyze-form` | both carry `:seon.fn/keywords #{:seon.test/skip-reason}` and the seed; load plus probe took 170 ms |
| `db/pull` of an absent `[:seon.test/sym ...]` | nil (so a digest that is created by a seed also differs) |
| scratch copy of the patched `test.clj` | compiles; `(apply-seeds! nil 'a/b #'inc)` gives nil |

Cost: a seeded member pays two `db/pull`s of one attribute. Members without seeds pay nothing
extra. Not timed under the harness.

## Commits

- `702f1c6c8`: item 1. `src/seon/fn.clj` adds `with-seed-keywords`, used by `var-row` and
  `analyzed-form`. New regression
  `seon.fn.seed-admission-test/an-sci-deftest-settles-with-its-seed-attributes-as-keywords`.
  +33 / −5.
- `4fd4c5b4b`: item 2. `src/seon/test.clj` `apply-seeds!` compares definition digests before and
  after the seeds and refuses with `:changes-test`. `resources/seon/schemas/seon.test.seed.edn`
  adds `:changes-test` to `:seon.test.seed/preparation`. `run-seeded` gets an optional test symbol.
  New regression `seon.test.seed-preparation-test/a-seed-that-redefines-its-own-test-refuses-by-name`.
  +64 / −36, most of it re-indentation.
- Net: +97 / −41.

## Root runs (serially, after adopting fn.clj, test.clj and seon.test.seed.edn together)

    bin/test-check default --ns seon.fn.seed-admission-test
    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --changed seon.fn/analyze-form
    bin/test-check default --changed seon.fn/var-row
    bin/test-check default --changed seon.test/bounded-result

Released paths: `src/seon/fn.clj`, `src/seon/test.clj`, `resources/seon/schemas/seon.test.seed.edn`,
`test/seon/fn/seed_admission_test.clj`, `test/seon/test/seed_preparation_test.clj`, this note.
`src/seon/sci/eval.clj` was not edited.
