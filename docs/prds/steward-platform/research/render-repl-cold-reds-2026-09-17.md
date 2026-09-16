---
type: research
status: verification-pending
created: 2026-09-17
tags: [steward-platform, render, repl, tests, verification]
---

# Render/REPL cold reds from batch 58b

Bounded lane `render-repl-cold-reds`. Four items, all verified RED cold in
[batch 58b](../../../../tmp/orchestrator/gate-results/batch-58b/named.md).
AGENTS.md, `tmp/orchestrator/wave2/repl-rule.txt` and the named issue were
read end to end. No test JVM was launched; `default` was never stopped,
reforked or restarted.

**Verification boundary.** Every verdict below is an IN-PROCESS
`seon.test/run` on the shared development cluster `default` (pid 17352, fresh
store), each on its own daemon thread with
`{:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
:seon.test/remaining-ms 100000}`, with the test namespace reloaded first
through `seon.test`'s own loader (`#'seon.test/with-test-loader`). The shared
fixture base was constructed ONCE from a plain MCP evaluation
(`(future @@#'seon.test-support/database-base)`) before any db-backed run,
per the BASE CONSTRUCTION RULE. These are iteration results; the
orchestrator's cold gate is the proof. Gate request:
[`tmp/orchestrator/gate-requests/render-repl-reds.txt`](../../../../tmp/orchestrator/gate-requests/render-repl-reds.txt).

## Broken things first

1. **current-src publication is refused tree-wide and nothing is adopting.**
   Every hook publication since ~04:04 came back refused. Two distinct
   causes were observed in `tmp/source-publications/*.edn`:
   `Operator exit 124` (the bound expired; `logs/current-source-failure.log`
   says only "Publication did not finish within its declared bound") and
   `:seon.fn/index-refused` — static analysis errors in ANOTHER lane's
   in-flight test edits: "Unresolved namespace support. Are you missing a
   require?" at `test/seon/cluster/store_transact_test.clj:89`,
   `test/seon/issue_test.clj:159`, `test/seon/rereads_panel_test.clj:42`
   (list elided by the hook's token budget). The `default` cluster carries
   NO `:seon.source/commit-id` at all on the fresh store. One lane's
   half-converted fixture sweep therefore blocks adoption for every lane.
   Not this lane's to repair; reported, not worked around.

2. **A second render-boundary defect, previously masked.** Fixing item 4's
   throw made
   `seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`
   reach an assertion it had never reached. It fails. Filed as
   [a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md](../../../seon/issues/a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md).

## Per test

### 1. `seon.render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces`

**Cause — stale test, not a render regression.** `563034709` (2026-09-15)
added `(not ai?)` to the pair-selection entrance in
`seon.render.value/value-node*` (`src/seon/render/value.clj:321`) and
rewrote this deftest's assertions and its NAME to match. `cecfaf428`
(2026-09-16) removed that guard again and names `563034709` as the root
cause: with it, `prepare` had no selected renderer to hand `shown-result`,
so settlement recorded none
([attempt-and-eval-facts-2026-09-16.md:159](attempt-and-eval-facts-2026-09-16.md)).
That lane updated `test/seon/render/value_test.clj` and
`test/seon/data_shapes_test.clj` and left this file behind. HEAD after the
render-selection lane's `b67a9dfe3` and the peer's `ac95db78a` renders
`#:probe{:database "database … at basis transaction 536870920 …", :report
"Wrote 0 facts on 0 entities."}` — the declared pairs, which is the ruled
behavior.

**Fix.** Restore the declared-face assertions and the deftest's name
(`nested-values-render-their-declared-faces`), with a comment naming both
commits so the next reader does not re-litigate it. `clojure.edn` became
unused and was dropped.

**Verdict.** Before: 7 fail / 0 error. After: **11 pass / 0 fail / 0 error**.

### 2. `seon.returned-error-test/returned-refusal-is-a-schema-first-error-with-unchanged-history`

**Cause — stale expectation.** The test required the shown refusal to start
with `"Expected:"`. No producer emits that two-line grammar any more:
`seon.db/render-rejection-ai` (`src/seon/db.clj:3100`) delegates to
`seon.error/render-ai` (`src/seon/error.clj:1558`), whose refusal text is
§2.4's evidence-complete diagnostic. `rg` over `src/` finds no surviving
`"Expected:"` producer. The observed 241-byte text is

```
seon.db/transact! refused transaction data at [0 3]: expected a string
([:string {:description "The current human-readable work title.", :min 1}]),
got an integer 42. Fix: Supply a string at [0 3]. Example: No docstring
example is available.
```

which names the layer and member that refused, the expected shape and the
offending value — exactly what the ruling requires. The expectation is the
stale half. The test's own final assertion still exercises the legacy
spelling as SAVED shown text, which is the only place it legitimately
survives.

**Fix.** Assert the current grammar: the refusal names `seon.db/transact!`,
the expected shape, and the offending `42`.

**Verdict.** Before: 1 fail. After: **14 pass / 0 fail / 0 error**.

### 3. `seon.render.page-settings-test/effective-settings-and-authored-plan-examples-work-through-sci`

**Cause — a scrape that read absence as an answer, plus two stale dials.**
The deftest `load-file`d
`docs/prds/context-generation/research/context_page_probe_2026_09_09.clj`,
whose `plan-examples` walks my.plan's docstring with
`(str/index-of documentation "(seon.db/transact!")`. The plan-derivation
rewrite (`3cd566973`, not on this branch; its result is `src/my/plan.clj`)
replaced those transact! examples with `my.plan/add!`, `update!`,
`complete!` and `current!` calls, so the scrape returns `[]`, `(first
examples)` is nil, and `seon.repl/source-text`'s armed contract throws —
the ERROR at `instrument.clj:414`. Two banned substitutes in one line: a
regex-shaped text walk standing in for a program-graph query, and
maintained code under a PRD directory.

The separate `:76` failure: `(nat-int? (:my.agent/turns-left values))`.
`0dca8534e` removed turn accounting from the settings groups
(`src/seon/agent.clj:75`); `:my.agent/turns-left` is declared
`:seon.wake/context-inert` (`resources/seon/schemas/my.agent.edn:4`) and is
read at its owner, `seon.turn/turns-left` (`src/seon/turn.clj:2717`).
`(doc my.plan)`'s `:example` is likewise now the add! call, so the
`":my.plan/agent"` and `"datomic.tx"` assertions were stale too.

**Fix.** A local `authored-examples` queries the program rows for my.plan's
public functions and splits each docstring through the one owner of that
grammar, `seon.sci.eval/docstring-parts` (`src/seon/sci/eval.clj:1157`),
in `:seon.fn/doc-order`. Each Example's top-level forms are evaluated one
per evaluation — `my.plan/item` authors two, and `evaluation/evaluate`
answers "Evaluation requires exactly one reader event" for a two-form
source, which is correct: an agent sees them as two successive evaluations.
Effects are then asserted from facts (more than one step, one completed,
one current) rather than from ids hard-coded out of the retired examples.
The removal example and its assertions went with the retired protocol;
my.plan has no remove. The `load-file` is gone.

**Verdict.** Before: 1 fail + 1 error. After: **31 pass / 0 fail / 0 error**.
Sibling `settings-without-overrides-use-the-declared-pair` re-run green.

### 4. `seon.render/invocation-unknown` passes nil where the typed unknown requires a map

**Cause — as filed**, with one correction. `src/seon/render.clj:1022`
unconditionally assoc'd `:seon.error/value (:seon.sci.admit/value result)`,
and `:seon.render/unknown-request` requires a map there, so the boundary
whose purpose is to make a refusal total threw instead. The issue guessed a
time-limited producer; the live probe shows the same absence when ADMISSION
refuses an over-bound failure value. Either way the key must be conditional.

**Fix.** Move the key under the `cond->`: absent is no key, never a stored
nil (§3).

**Verdict.**
`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`
before: 12 pass / 0 fail / **1 error**; after: 22 pass / **1 fail** / 0
error. The throw is gone and ten further assertions now run. The one
remaining failure is a different root cause, measured live:

```clojure
;; result handed to invocation-unknown for my.render-probe/contracted
{:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 8388639}
```

The instrument refusal for a render producer embeds the offending argument
— a render unit carrying a Datahike database value — so its `pr-str` is
8.4 MB and `seon.sci.admit/admit-value` (`src/seon/sci/admit.clj:732`)
refuses it. `seon.sci.kernel/failure-value` (`src/seon/sci/kernel.clj:462`)
preserved the refusal's own `:seon.error/kind` correctly; admission is where
it is lost. A producer that merely THROWS is unaffected, because its failure
value carries no offending argument. Filed, not fixed here: the repair
belongs in `seon.instrument`'s refusal construction, outside this lane's
items.

## Issue status

* [a-time-limited-render-producer-passes-nil-where-the-typed-unknown-requires-a-map.md](../../../seon/issues/a-time-limited-render-producer-passes-nil-where-the-typed-unknown-requires-a-map.md)
  — **resolved**, with the correction above recorded on it.
* [a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md](../../../seon/issues/a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md)
  — **new, open, friction**.

## Shared-tree note

One edit to `test/seon/returned_error_test.clj` was observed to disappear
from the working tree minutes after it was written, while HEAD advanced by
several foreign commits; it was re-applied and committed. Nothing was
reverted or restored by this lane. `test/seon/cluster/prompt_test.clj` was
left dirty by another lane and was not touched.
