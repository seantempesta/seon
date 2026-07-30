---
type: research
status: complete
tags: [sci, program-graph, testing, repl]
---

# Runtime registration live proof

## Question

Does the recurring system prove the agent path rather than only the row
canonicalizers: independent-form failure isolation, agent-authored function,
schema, and test publication, process reopen, and a second agent call?

## Dependency ledger

- Runtime evaluation and installation:
  `src/seon/sci/eval.clj:321-345,467-555,726-900`.
- Terminal program transaction:
  `src/seon/cluster/loop.cljc:67-103,771-810` and
  `src/seon/cluster/run.cljc`.
- Static form analysis: `src/seon/fn/analyzer.clj:184-319` at pinned
  clj-kondo `794a508d53df319bfb2f4db666315de6a3e56fff`.
- Database-backed reacquisition:
  `test/seon/cluster/program_restart_test.clj`.
- Per-form lint isolation: `test/seon/cluster/loop_test.clj:45-94`.

## What was already real

`an-agent-definition-survives-restart-and-another-agent-calls-it` is a real
file-store cluster test. Agent A publishes a contracted function, a global
schema, and a `deftest`; the cluster stops; a new process opens the same
branch; `sci.eval/acquire!` reconstructs all three from database facts; and a
fresh agent B calls agent A's function. The schema row has no namespace-owner
attribute, its validator is rebuilt from the reopened database value, and the
test Var's `:test` function executes.

The old wait predicate was weaker than those claims. It returned as soon as
the three program rows existed, so the test could race shutdown against later
forms. The recurring proof now inserts one unresolved form at ordinal 9 and
waits for all 15 receipts plus the final run close. It asserts that the bad
ordinal is the flat `:seon.cluster.loop/lint-rejected` value, while fourteen
valid forms—including the later test, namespace mutations, and completion—
settle. It then performs the same stop, reopen, acquisition, and second-agent
call.

Focused command and result:

```text
bin/test seon.cluster.turn-test seon.cluster.program-restart-test
Ran 41 tests containing 262 assertions.
0 failures, 0 errors.
```

## Fixture defect exposed by stricter linting

The first run of that focus produced 11 failures in `seon.cluster.turn-test`.
Its in-memory `with-cluster` fixture installed the canonical schema but no
packaged program rows. Production freeze derives clj-kondo's known function
context from database `:seon.fn` rows, so ordinary `my.run/complete` and
`my.message/send` forms were reported as unresolved only in this incomplete
fixture. Seeding every turn example with roughly 1,300 packaged function rows
would make a unit fixture expensive and would duplicate the source-populated
integration boundary already owned by `program-restart-test`.

The turn fixture now explicitly bypasses static lint while retaining real SCI
evaluation, terminal transactions, and namespace-state semantics. The one
source-populated restart test owns their integrated composition. No function
or namespace hand list was introduced.

## Genuine remaining REPL mismatch

Freeze-time batch lint receives the namespace row that existed before the
plan. It cannot know resolver state produced by executing a computed earlier
form. The existing turn regression demonstrated the exact counterexample:

```clojure
(require (if true '[clojure.set :as sets]
                  '[clojure.string :as sets]))
{:x ::sets/after-dynamic-require}
```

SCI accepts the second form after the first form executes. clj-kondo correctly
cannot infer the dynamic target while analyzing both before execution, so the
current freeze path replaces the valid second form with an unresolved-
namespace refusal. A static hand list, replayed require-source registry, or
special cases for `require`, `alias`, `eval`, and `apply` cannot close this
class: arbitrary earlier Clojure can change namespace state.

## Recommended production repair

Keep reply splitting and exact source freeze unchanged. Move runtime form
analysis from plan freeze to the existing ordered reduce, immediately before
each source enters `sci.eval/evaluate`. Build the analyzer namespace prelude
from that run context's current SCI namespace bindings, and obtain callable
program identities from the same immutable database value used for the form.
Then:

1. a clean form reaches SCI byte-for-byte;
2. an error-bearing form becomes one flat lint-refusal receipt at its ordinal;
3. the next form analyzes against namespace effects installed by every prior
   successfully committed form; and
4. a refused terminal transaction never advances the run context, so analysis
   observes the same commit-first boundary as evaluation.

Warm single-form analysis is already measured at 5–32 ms in
`plan/unsettled.md`. This removes the impossible requirement that static batch
analysis predict future REPL state and uses the one sequential state the
runtime already owns. A recurring test should restore the computed-require
example with lint enabled and assert both exact source receipts.

## Resolution — ordered lint landed

The runtime now freezes the reply's exact sources and invokes the one
clj-kondo analyzer on each form immediately before SCI evaluation. The
analyzer prelude is rebuilt from the namespace row and function rows at the
current database value, after every prior form's terminal transaction and
namespace installation. There is no batch-lint API left to accidentally
predict future REPL state (`src/seon/cluster/loop.cljc`, `lint-form` and the
`:resume` reduce).

The computed-`require` example is now part of the source-populated
stop/reopen proof. It resolves `::dynamic/after-require`, an independent
invalid middle form becomes one flat refusal, the other sixteen forms settle,
and the dynamic alias survives fresh acquisition
(`test/seon/cluster/program_restart_test.clj`). This proof also exposed a
smaller projection defect: persisted renamed refers need both `:refer` and
`:rename` in clj-kondo's synthetic `ns` form. `seon.fn.analyzer` now derives
both from the same `:seon.ns/refers` rows, following clj-kondo's maintained
namespace analyzer (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer/namespace.clj:273-280`).

Focused proof on 2026-07-30: 54 tests / 340 assertions / 0 failures / 0
errors across analyzer, loop, source-populated restart, and turn semantics. A
live default-cluster Var reload admitted the dynamic require and then its
alias-dependent form in 48 ms; that probe changed loaded Vars only, not the
cluster's sovereign database program graph.

## Packaged edit boundary

Packaged file edits and runtime declarations are deliberately separate. The
edit hook advances only `current-src`; an existing cluster remains on its
prior commit. The live publication/reset proof is recorded at the current
working edge in `plan/unsettled.md`. This lane did not mutate production
source or the live default cluster.
