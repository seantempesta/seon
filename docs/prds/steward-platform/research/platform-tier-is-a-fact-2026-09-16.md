---
type: research
status: blocked on one concurrently-held schema file; design verified, hunks exact
created: 2026-09-16
tags: [testing, seon.test.runner, bin/test, seon.program, facts-over-inference]
---

# The platform tier and the bare namespace set, as facts

Assignment: land D1 and D2 of
[test-execution-model-2026-09-16](test-execution-model-2026-09-16.md) §0.
Read end to end first: [AGENTS.md](../../../../AGENTS.md) §0–§5, and that note's
§0 D1/D2, §1.5 and §5.

**Outcome: no source edit landed.** Both defects require one attribute family in
`resources/seon/schemas/seon.test.edn`, which the `pulled-ref-is-a-ref` lane
holds uncommitted. The assignment's stricter stop rule names that file, so this
note reports the exact hunks instead. The design below is verified against the
published manifest, not inferred.

## Why the schema entry is a hard prerequisite, not an ordering preference

`seon.program/shapes-in` derives a row family's owned attributes from its
declared entity map, and the indexer writes `(select-keys row owned-attributes)`
(`src/seon/program.cljc:861`, again at `:990`). An attribute that
`:seon.test/test` does not declare is **silently dropped** from the published
row — the docstring at `src/seon/program.cljc:128` records that exact accident
twice on 2026-09-16 (`7cfe02790`, `925ca19fe`).

So landing the lifting seam first would produce a checker that reads absence as
health: `test-markers` would lift `:seon.test/platform`, `select-keys` would
drop it, and `platform-declarations` would answer NOT-PLATFORM for every
platform test while the gate stayed green. There is no half of this slice that
is safe to land alone.

## D1 — what `long` does, and the same shape for `platform`

`:seon.test/long` is lifted at ONE seam, not two:
`seon.program/test-marker-attributes` (`src/seon/program.cljc:102`) lists the
attributes and `seon.program/test-markers` (`:110`) applies the rule
(deftest wins per attribute, namespace inherited otherwise). Both indexers call
it — the static one at `src/seon/fn.clj:620` and the evaluator at
`src/seon/sci/eval.clj:420`. The runner then reads the manifest row
(`long-declarations`, `src/seon/test/runner.clj:676`) and refuses drift against
the Var (`verify-long-declarations-indexed!`, `:695`).

`:seon.test/platform` is the same metadata shape today — a non-blank reason
string read by `marker-reason` (`src/seon/test/runner.clj:653`) through
`platform-reason` (`:759`), consumed by `test-selection` (`:763`). Confirmed
absent from the published rows: of 1,833 indexed test rows in the newest base
(`target/test-published-bases/f75ae5a6…/base/manifest.edn`), **54 carry
`:seon.test/long` and 0 carry `:seon.test/platform`.**

### Hunk 1 — `resources/seon/schemas/seon.test.edn`, beside `:long` (line 16)

```clojure
:platform
[:string {:min 1
          :description "The declared reason this test is a platform regression: a moving part whose breakage poisons the bulk tier. Indexed from the test Var's or its namespace's :seon.test/platform metadata, so the tier partition reads the fact the coordinator already holds instead of re-reading Var metadata."}],
```

### Hunk 2 — same file, the `:seon.test/test` entity map, beside line 109

```clojure
[:seon.test/platform {:optional true} :seon.test/platform]
```

Both are pure accretion: a new optional key on an open map, no reset, no
migration.

### Then, in this lane's own files

- `src/seon/program.cljc:102` — `test-marker-attributes` becomes
  `[:seon.test/long :seon.test/long-ms :seon.test/platform]`, and its docstring
  stops saying "Both". One edit lifts platform at BOTH seams; no change to
  `seon.fn/var-row` or `seon.sci.eval` is needed, which is the point of that
  seam existing.
- `src/seon/test/runner.clj` — `platform-declarations` beside
  `long-declarations` (`:676`) keyed on `:seon.test/platform`;
  `verify-platform-declarations-indexed!` beside `:695` with the same
  one-direction refusal (a Var that declares while the row does not would run a
  platform regression in the bulk tier, losing the fail-fast the tier exists
  for); `test-selection` (`:763`) takes `::platform-declarations` and reads
  `(get-in declarations [(str test-symbol) :seon.test/platform])` in place of
  `(platform-reason test-var)`; `platform-reason` (`:759`) is **deleted**, and
  `marker-reason` (`:653`) stays — `fixture-observation!` (`:739`) and both
  drift checks are its remaining callers.
- `run-coordinator!` (`:3700`) calls the new verifier next to the `long` one.

### Regression, beside `a-namespace-declared-long-reaches-every-test-row`

`test/seon/test/runner_test.clj:445`. Same fixture shape: write a namespace file
declaring `^{:seon.test/platform "…"}` on the ns form and an overriding reason
on one deftest, `build-artifact` it, assert both rows carry the fact; then assert
`test-selection` puts that symbol in `::platform` from the declarations map with
no Var metadata present. `one-rule-answers-both-lifting-seams` (`:485`) gains the
platform cases.

## D2 — the bare namespace set cannot be `find`, and cannot be `:seon.test/ns` either

`bin/test:660-666` munges `find test -name '*_test.clj'` into symbols. It is the
banned filename convention. But replacing it with "the distinct `:seon.test/ns`
of indexed tests", as the assignment proposed, is **measurably wrong in both
directions**. Measured against the newest published base (218 namespaces with
test rows; `find` yields 217):

| Divergence | Namespaces | Consequence of the naive fix |
|---|---|---|
| Has test rows, no `_test` filename | `my.examples-fixture`, `seon.test-runner-failure-fixture` | **The bare gate turns permanently red.** `seon.test-runner-failure-fixture/failing-example` asserts `(= 5 (+ 2 2))` on purpose; its ns docstring says "Selected explicitly by runner tests; not discovered by the full gate." The filename is what excludes it today. |
| `_test` filename, no test rows | `seon.repl-parity-test` | **Silent coverage loss.** Its deftests are emitted by its own `defparity` macro (`test/seon/repl_parity_test.clj:120`), so the static analyzer mints `:seon.ns/name` and 14 `:seon.fn/sym` rows and zero `:seon.test/sym` rows. The loaded-Var reader sees them; the manifest never will. |

The second class is the project's own recurring failure class: a check whose
subject is absent answering "fine". The runner already knows unindexed tests
exist — `split-resolved-tasks` (`:845`) routes them to the serial worker — but
namespace *discovery* from `:seon.test/ns` would drop the namespace before that
accommodation could fire.

### The derivation that is actually correct

```
bare namespaces = { ns | ns has a :seon.ns/name row in a manifest artifact whose
                         :seon.fn.file/relative-path lies under the declared
                         "test" source root }
                  minus { ns declaring :seon.test/fixture }
```

The root is a declaration (`seon.fn/source-roots`, `src/seon/fn.clj:26`;
`seon.test.selection/graph-roots`, `src/seon/test/selection.clj:23`), and the
artifact path is an indexed fact — not a `_test` suffix. Measured: this set is
**231 namespaces and a strict superset of all 217 `find` yields**, including
`seon.repl-parity-test`. Of the 14 extras, `grep -c '(deftest'` says **12 carry
zero deftests** (`seon.test-support`, the `*-child` process entry points, the
`render-simplification` fixtures, …) and cost only a `require` their consuming
namespaces already pay — `test-vars-in` (`:642`) filters on `:test` metadata, so
they contribute no tasks. Exactly **two** need the marker, and they are the two
in the table above.

### The missing fact

`:seon.test/fixture` — a non-blank reason, namespace-declared, added to
`test-marker-attributes` so the same one rule lifts it onto every deftest row in
that namespace. It is the honest form of what the filename is doing today: "this
namespace's deftests are material for another test's assertions, never gate
members."

`:seon.test/usage` is **not** this marker — checked: it marks executable
documentation examples and sits on ordinary gate tests
(`seon.db-test/diff-replays-one-read-by-derived-identity`,
`my.message-test/inbox-lists-this-agents-messages-newest-last`).

### Hunk 3 — `resources/seon/schemas/seon.test.edn`, beside `:long`

```clojure
:fixture
[:string {:min 1
          :description "The declared reason this namespace's tests are fixture material another test asserts over, never gate members: a deliberate failure, an assertionless example, a usage sample. Declared once on the ns form and lifted onto every test row, so bare selection excludes it by fact instead of by a filename that does not end in _test."}],
```

### Hunk 4 — same file, the `:seon.test/test` entity map

```clojure
[:seon.test/fixture {:optional true} :seon.test/fixture]
```

### Then

- `test-marker-attributes` gains `:seon.test/fixture`.
- `test/my/examples_fixture.clj` and `test/seon/test_runner_failure_fixture.clj`
  declare it on their `ns` forms.
- `src/seon/test/runner.clj` — `bare-namespaces` derives the set above from the
  manifest the coordinator already reads. The manifest read
  (`run-coordinator!`, `:3679`) moves **above** the worker launch, which is
  legal: `manifest.edn` is written by both base-preparation paths
  (`:3845`, and `seon.test.cache`) before the coordinator is launched at all
  (`bin/test:786`), so nothing waits on it. With no namespace arguments and a
  non-explicit mode, the coordinator derives them and announces the count;
  `test-selection` and `initialize-worker!` are unchanged.
- `bin/test` — delete `:659-666` entirely; make the `:669` empty guard fire only
  when `explicit_selection -eq 1`. The `:696-698` worker cap is already
  explicit-only, so it needs no change.
- `--list-namespaces` is **not** needed. The shell needs the count only for a cap
  it already skips in bare mode.

### Regression

Beside the D1 one: assert `bare-namespaces` over a synthetic manifest returns
every namespace rooted under `test`, including one carrying only `:seon.ns/name`
and no `:seon.test/sym` (the macro-generated class), and excludes one whose rows
carry `:seon.test/fixture`.

## Verification boundary

**No test was run. No test JVM was launched. No `bin/test` or `bin/test-fast`
invocation was made. No prepl evaluation was issued. No file outside this note
was modified** — `git status` is unchanged apart from this file.

Every number above comes from reading two committed published-base manifests
under `target/test-published-bases/` with `bb`, from `grep`/`find` over the
working tree, and from source read at the `file:line` cited inline. The
`find`-versus-facts comparison was run against both the newest base
(`f75ae5a6…`, 218 test namespaces) and an older one (`6dc7ecaa…`, 217), and the
two fixture namespaces and the macro-generated namespace diverge identically in
both, so the divergence is structural, not an artifact of one stale base.

**Unverified by execution:** that the reordered manifest read in
`run-coordinator!` leaves worker startup overlap intact, and that the 12
deftest-free extra namespaces load cleanly in a coordinator that requires them.
Both need a cold gate this lane was not permitted to run.

---

## Implementation, 2026-09-16 (later the same day)

The three files this note was blocked on were free. The design above landed
unchanged except where measurement corrected it; the corrections are named
below rather than silently absorbed.

### What landed

`resources/seon/schemas/seon.test.edn` — hunks 1–4 verbatim: `:seon.test/platform`
and `:seon.test/fixture` as `[:string {:min 1 …}]` reasons beside `:long`, and
both as optional keys on the `:seon.test/test` entity map. Pure accretion on an
open map; no reset, no migration. The resource and its loaded consumer
(`src/seon/program.cljc`) were edited in ONE edit-hook publication
(`404cd646-8093-4134-b145-69fc5ddd3dac`), per AGENTS §7.

`src/seon/program.cljc` — `test-marker-attributes` is now
`[:seon.test/long :seon.test/long-ms :seon.test/platform :seon.test/fixture]`.
One edit; `seon.fn/var-row` (`src/seon/fn.clj:620`) and `seon.sci.eval`
(`src/seon/sci/eval.clj:420`) were untouched, which is the whole point of that
seam.

`src/seon/test/runner.clj`:
- `platform-declarations` beside `long-declarations`, keyed on
  `:seon.test/platform`.
- `verify-platform-declarations-indexed!` beside the long verifier, refusing the
  same one dangerous direction with `::platform-declaration-drift`.
- `test-selection` takes `::platform-declarations` and reads the row. Both
  markers now come from the same authority; the docstring says so.
- `platform-reason` **deleted**. `marker-reason` **stays** — `fixture-observation!`
  and both drift checks are its remaining callers, exactly as this note predicted.
- `bare-namespaces` derives the set from the manifest, and `test-source-root`
  takes the root from `seon.fn/source-roots` rather than spelling it inline.
- `program-manifest` extracted, and the manifest read moved to the TOP of
  `run-coordinator!`'s outer `let`, above the worker launch, because the workers
  are handed the derived namespace set. The stale `SELECT building the program
  graph` announcement became `SELECT partitioning tiers over the program graph`.
- `run-coordinator!` calls the new verifier beside the long one.

`bin/test` — the `find test -name '*_test.clj'` munge is deleted. The empty
guard now fires only on `explicit_selection -eq 1`; the worker cap at `:696`
already was explicit-only and needed no change. No `--list-namespaces` entry was
needed, as predicted.

One defect this note did not anticipate, found before it could reach a gate:
`bin/test` runs under `set -euo pipefail`, and its `#!/usr/bin/env bash` can
resolve to macOS's bash 3.2, where expanding an EMPTY array under `nounset` is
an "unbound variable" error. Deleting the `find` made `namespaces` genuinely
empty in bare mode for the first time, so `"${namespaces[@]}"` at the
coordinator launch would have aborted every bare gate before the JVM started.
Both expansion sites (`:581` for `test-fast`, `:785` for the coordinator) now
use the guarded `${namespaces[@]+"${namespaces[@]}"}` form; `:581` carried the
same latent defect already. Verified directly:
`/bin/bash -c 'set -euo pipefail; a=(); printf "%s" "${a[@]}"'` exits 1, the
guarded form exits 0 and still expands a populated array element-wise.

`test/seon/test_runner_failure_fixture.clj` and `test/my/examples_fixture.clj`
declare `:seon.test/fixture` on their `ns` forms, in the
`(ns ^{…} name "docstring" …)` shape `seon.concurrency-independence-test:1`
already uses for `:seon.test/long`.

### Regressions

`test/seon/test/runner_test.clj`:
- `the-platform-tier-partitions-on-the-indexed-fact-not-var-metadata` — a
  namespace-declared reason reaches both rows with the deftest's own reason
  winning; `test-selection` puts both in `::platform` FROM the rows; removing one
  row moves that test to `::selected` **while its Var still carries the
  metadata**, which is the assertion a Var-reading partition cannot pass; and the
  verifier refuses that same drift.
- `the-bare-namespace-set-is-derived-from-indexed-facts` — over a manifest of
  canonically built artifacts, the derived set admits a namespace with test rows
  AND one with only `:seon.ns/name` (the macro-generated class), excludes the
  `:seon.test/fixture` namespace, and never admits a `src`-rooted one; a manifest
  with no test-rooted namespace refuses with `::no-bare-namespaces` instead of
  running nothing and reporting success.
- `the-deliberate-failure-fixtures-declare-their-exclusion` — both fixture
  namespaces carry a non-blank reason. Losing either turns the bare gate red.
- `one-rule-answers-both-lifting-seams` gains the platform and fixture cases.

No regression asserts a literal namespace count.

### Corrections to this note's measurements

Re-measured against the newest base at implementation time
(`64af2f0f…`, published 16:08): **232** namespaces indexed under `test/`, not 231
— `seon.incremental-publication-test` was added at 16:12, after that base was
written, and appears in `find` but not in the base. That is a base-staleness
artifact, not a structural divergence: every gate publishes its own base from its
own snapshot. `seon.repl-parity-test` is present in the derived set of this base,
confirming the macro-generated class the note found. The 14 extras over `find`
are the same 14 the note listed.

### Verification boundary

Iterated with `bin/test-fast seon.test.runner-test seon.fn-test seon.program-test`
on the working tree. **`bin/test` was never run; no cold gate, no `--all`, no
`--platform`.** No prepl evaluation was issued against `default`; the edit hook's
own publications are the only cluster traffic this lane produced.

First invocation: **103 tests, 777 assertions, 0 failures, 2 errors.** Zero
failures means every assertion in the three new regressions passed. The two
errors are `Fixture setup was refused.` from
`seon.test-support/checked-fixture-result`, raised inside
`seon.test.runner-test/a-committed-retraction-survives-the-restore-and-is-named-a-committed-change`
— a foreign lane's test landed with
[the-drift-restore-undoes-a-committed-schema-retraction](../../../seon/issues/the-drift-restore-undoes-a-committed-schema-retraction.md),
and a member of the open class
[a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter](../../../seon/issues/a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter.md),
which is exactly why its cause is unreadable from the log. Not attributed to
this slice, and falsified rather than assumed: this slice adds two OPTIONAL
string attributes to the canonical population, so breaking it would have
refused every `with-database` test in the run, not two — 101 of the 103 passed.

A second invocation, run to recover the error identities the first one's
`tail` had truncated, wedged on the SUITE LIVENESS BUG (300s without reporter
progress) at `executor-submissions-carry-the-callers-handed-projection`, also
not this slice's test, with 21 `bin/test` processes on the machine. That run did
confirm `the-platform-tier-partitions-on-the-indexed-fact-not-var-metadata`
green in 117 ms.

**Unverified by execution, unchanged from this note's original list and now the
first thing a cold gate will exercise:**
1. That the 12 deftest-free extra namespaces (`seon.test-support`, the `*-child`
   process entry points, the `render-simplification` fixtures, …) `require`
   cleanly in the coordinator and every worker. They contribute no tasks
   (`test-vars-in` filters on `:test` metadata), but they are now REQUIRED where
   the filename convention previously never named them.
2. That the manifest read moved above the worker launch leaves worker-startup
   overlap intact. The read is a `slurp` of a file both base-preparation paths
   write before the coordinator is launched, so it cannot block, but the
   measured startup overlap is not proven by a fast iteration.
