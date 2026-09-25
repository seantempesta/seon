---
type: landing
status: committed, code only (owner load rule); nothing adopted or run through seon.test/run
lane: harness-s2b-b-opus
created: 2026-09-24
design: docs/research/agent-platform/one-test-harness/design-2026-09-24.md §2 step 2, §2b, §5, §9c S2b-b
prior: docs/prds/agent-platform/landing/lane-harness-s2b-a-seed-schemas-2026-09-24.md
---

# Lane harness-s2b-b (2026-09-24)

**What exists and the approach.** S2b-a stores a deftest's `:seon.test/seed` as ordered
occurrences. Nothing ran those seeds. Now `seon.test/bounded-result` applies them on the member's
own branch, on the body thread, inside `sci.eval/call-detecting-program-change` (the design's §5
exclusion). This happens after the test Var resolves and before the body enters. Entries run in
order, and each one reads the database the previous entry left. A refusal stops preparation, so
the body never enters. On the graph side, the keywords a seed uses become the test row's
`:seon.fn/keywords`, which the existing reach walk already reads as schema edges.
This adds no new walk, cache or registry.

## Broken or unproven first

- **Not run.** Under the load rule I made no test request, no adoption and no reload. clj-kondo
  reports 0 errors. The new `apply-seeds!` form compiled in a throwaway JVM namespace, and its
  pure branches returned the expected values there. I then removed that namespace. The write
  path (`db/transact!`, `config/apply!` on a member branch) has **not** been executed. The
  regressions below are the first proof.
- **Host bodies see seeds only through fixtures.** A host body still runs `runner/run-var! v {}`,
  which has no custody (S4 owns that). Seeds therefore reach a host body through `*member*`,
  which is re-pointed at the prepared `:seon.db/db` and `:seon.source/commit-id`. Canonical
  `with-database` branches from that commit. A bare `(db/db)` in a host body still has no
  connection.
- **`held-handle` wins over `*member*`.** `test-support/execution-handle` first looks up the
  context-state entry for `db/*conn*`, and that entry still carries the pre-seed commit. An SCI
  body that calls `with-database` under its member custody would branch without the seeds. The
  fix would be to update the entry in `cluster/agent.clj`, which is not owned here.
- **SCI-evaluated deftests store no occurrences and no seed keywords** (S2b-a's gap, routed to
  S2b-c: `sci/eval.clj analyze-form`). Their seeds still *run*, because runtime reads the Var's
  evaluated metadata, not the rows.
- **Program-changing seeds are not refused.** A seed that writes program rows runs its body under
  the member's already-acquired SCI program. Per §2 step 2 it needs ordinary reacquisition. That
  work is not here.
- **File/external-byte invalidation: not applicable yet.** `:seon.test.seed/input` declares
  only `:seon.db/db`, so no seed receives captured file bytes. A seed that reads a file on its own
  is untracked; the analyzer does not refuse it.
- **No checkpoint reuse for equal seeds.** Seeds are applied per member. Sharing a completed
  checkpoint across equal ordered inputs (§2b last paragraph) is still to come.
- **Named seeds run the value in the Var's metadata.** Metadata is evaluated when the test is
  defined, and program adoption reloads dependents (`require :reload`), so the test is re-evaluated
  when its seed namespace changes. A seed Var redefined at a raw REPL without reloading the test
  would run stale. This is the same staleness as any captured function value.
- Seed time counts toward the body's 5 s bound. Preparation has no separate clock row yet.

## Premises verified (default pid 67823, JVM mode, throwaway namespaces since removed)

| probe | observation |
|---|---|
| deftest metadata evaluation | `[{:a/b 1} [{:a/b 2}] ns/week (fn ...)]`: map, vector, a function value for the named seed (`(e 7)` gives `{:w 7}`), inline fn callable. 8 ms |
| S2b-a installed on default | `:seon.test/seed` is installed (component, many); `var-row` source mentions it; zero stored occurrences |
| reach edges on a three-form fixture via `seon.fn/rows` | inline helper in `:seon.fn/calls`; named seed in `:seon.fn/references`; **`:seon.fn/keywords` nil** (the gap fixed here). 174 ms |
| dispatch edges | five `:seon.effect/capability` owners (`my.edit/form!` → `seon.edit.jvm/edit`, …): the handler is in both `:seon.fn/calls` and `:seon.fn/references`, so reach already follows dispatch. No change was needed |
| `:seon.test.seed/value` validation (`schema/explain-candidate-value`) | valid `{tx-data [...]}` → nil; `{tx-data "nope"}` → explanation; `nil` → explanation; `{}` → nil |
| new error shape | `[:and :seon.error/base [:map ordinal? error-test-symbol]]` compiled under default's registry; the produced refusal validates (`m/explain` nil) |

## Invalidation proof, by input (§9c S2b column)

- **helper**: an inline seed's calls are the test's `:seon.fn/calls`, and a named seed's own row
  carries its calls. Both are walked (verified above).
- **order and literal/inline bodies**: these live in the deftest source, which is covered by
  `:seon.program/definition-digest`, the leaf of reach.
- **dispatch**: capability handlers are already calls and references (verified above).
- **schema-write**: new here. Every qualified keyword in the seed vector joins the test's
  `:seon.fn/keywords`. That feeds `reach-row` `::reach-keys` → `reach-schema-keys`, and also the
  schema-dependent query at `fn.clj` (`[?function :seon.fn/keywords ?keyword]`).
- **file**: not applicable (see above).

## Changes

- `src/seon/test.clj`: new private, contracted `apply-seeds!`. `bounded-result` calls it after
  resolution and binds the returned member. A refusal becomes the member's failed result, naming
  the seed and its ordinal. +44 / −3.
- `src/seon/fn.clj` `var-row`: the seed vector's qualified keywords are unioned into
  `:seon.fn/keywords`. +5 / −1.
- `resources/seon/schemas/seon.test.seed.edn`: `:seon.test.seed/preparation`
  (`:no-member :invalid-value :write-refused`) and `:seon.test.seed/preparation-error` over
  `:seon.error/base`. +4.
- `test/seon/test/seed_preparation_test.clj` (new, 47 lines), three class regressions:
  1. order: entry 1 reads entry 0's write, and the body's `*member*` sits at the prepared commit;
  2. a refused write and an invalid value each prevent entry, with the ordinal named;
  3. literal and inline seed attributes are the test row's keywords.
- **Net:** 100 added, 4 removed.
- **Timings:** none measured through the harness (load rule). The added work per member is one
  vector pass, plus one checked write per entry that has tx-data.

## Root runs (serially, after adopting these four paths)

    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --ns seon.fn.seed-admission-test
    bin/test-check default --changed seon.test/bounded-result
    bin/test-check default --changed seon.fn/var-row

RESET NEEDED: no. Released paths: all four above plus this note.
