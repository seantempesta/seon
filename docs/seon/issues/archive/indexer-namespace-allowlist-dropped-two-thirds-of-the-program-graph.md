---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, reader, program-graph]
---

# The reader's namespace-stable allowlist dropped two thirds of the program graph

## Problem

`seon.fn/index!` — the one owner behind `bin/seon index` and
`seon.cluster/populate-ancestor!` — published 121 rows for a tree declaring
1242 functions, 382 of them contracted. Every cluster ever born from this
source, and every primed cluster, held a program graph missing most of the
system. Nothing reported it. Almost every runtime mechanism queries that graph,
so a corpus that looks populated while missing `seon.flow`, `seon.cluster.run`,
`seon.cluster.agent`, `seon.error`, `seon.problems`, `seon.render.block`, and
every `my.*` namespace is worse than an empty one.

Two independent causes compounded: attribution loss (below), and a contract
entry condition in `seon.fn/durable-row` that dropped every private helper.

## Root cause

`seon.sci.reader/next-reading-context` kept a hand-maintained
`namespace-stable-operations` set and cleared parse-time namespace attribution
after ANY top-level form outside it. Attribution never returned without a
further explicit `ns`/`in-ns`, so the FIRST ordinary top-level call in a file
erased every declaration below it: `(set! *warn-on-reflection* true)` at
`src/seon/flow.clj:23` cost all 22 of that file's contracted functions, and
`(schema.edn/load! {})` at `src/seon/cluster/agent.clj:78` cost all 13.

Without attribution, `function-declaration` produced a row carrying
`:seon.fn/spec` but no `:seon.fn/sym`, and `seon.fn/durable-row` dropped it in
silence — a check reading absence of signal as health. The reader itself never
failed: a whole-tree read of `src/`+`test/` returned zero reader errors, which
falsified the leading fail-closed-reader hypothesis outright.

This is item S4 of
[[../../prds/sci-execution-runtime/research/parser-merge-2026-07-29]], which
predicted the design conflict but not that it was already destroying the corpus.

## Second cause: a contract gated the graph, not just the dependable surface

`seon.fn/durable-row` admitted a function row only when the event carried BOTH
`:seon.fn/sym` and `:seon.fn/spec`, so 808 private helpers were never rows even
where attribution held. A contract is the right gate for what agents may DEPEND
on; it is the wrong gate for the graph, because `:seon.fn/calls` reachability
— workload derivation, test selection, usage signals, renderer discovery —
breaks the moment a chain passes through an unindexed `defn-`. `:seon.fn/spec`
is optional in `resources/seon/schema/program.edn` already, and
`:seon.fn/private?` already modeled privacy; the rows were simply dropped
(owner ruling, 2026-07-29).

Build-time admission is now every `defn`/`defn-`. Eval-time admission in
`seon.sci.eval/program-row` KEEPS the contract requirement, deliberately and
unchanged: an agent-authored durable declaration is exactly the depended-upon
case the selective-admission ruling governs, and a scratch `defn` in an agent's
namespace should leave a receipt and no row.

## Fix

Attribution now follows the last explicit valid `ns`/`in-ns`. Uncertainty is
DERIVED from the form itself: a form mentioning `ns` or `in-ns` in operator
position below its own head (`(do (in-ns 'other))`) clears attribution, because
static reading cannot prove where such a form leaves the namespace. Every other
call leaves the last explicit namespace standing. Evaluator namespace receipts
remain the runtime truth for a change only evaluation can see.

`seon.fn/rows` now REFUSES a file containing a function declaration it cannot
place, naming the file, the line, the source, and the reason
(`:seon.fn/namespace-unproven`), so a file that contributes nothing is loud
rather than invisible. That refusal is new in this fix, not a pre-existing
diagnostic that failed to scream: before it, nothing reported anything.

## Proof

- `src/`+`test/` read: **1242 top-level `defn`/`defn-` forms, 1242 rows, zero
  mismatched files, zero reader errors** — the per-file counts are equal, not
  merely close. 808 private, 379 contracted. 113 namespace rows, 569 test rows.
- Fresh cluster born on a scratch operator root: 146 `:seon.ns` rows, **1242**
  `:seon.fn` rows, **105** namespaces with functions — was 121 rows / 21
  namespaces. `seon.flow` 47, `seon.render.value` 53, `seon.error` 26,
  `seon.cluster.loop` 17, `seon.problems` 15, `seon.cluster.agent` 14. The
  primed and fresh paths share `seon.fn/index!`, so both were equally partial;
  the difference the owner saw between them was history, not indexing.
- Recurring coverage: `seon.fn-test/every-declared-function-in-the-tree-becomes-one-row`
  asserts, per source file, that the set of `defn`/`defn-` names counted FROM
  THE FORMS is a subset of the rows for that namespace, and that private
  helpers are rows marked private; `seon.fn-test/unplaceable-declaration-is-refused-loudly`
  asserts the loud refusal with its reason;
  `seon.sci.reader-test/namespace-tracking-has-repl-semantics-and-fails-closed`
  asserts retention across `set!`, `schema.edn/load!`, and
  `register-core-predicate!`, and still asserts absent attribution for a
  malformed or evaluation-only switch.

## Left to the parser wave

`seon.sci.reader` still owes S1 (original-source-exact spans; CRLF is
normalized and an inter-form comment is attributed to the following event), S2
and S3 (localized read-error events and recovery), S5, and S6 (closed request
and event schemas). None of them caused this defect; all remain in the
parser-merge plan.

## Post-resolution falsification

The independent review in
`docs/prds/sci-execution-runtime/research/indexer-review-2026-07-29.md`
confirmed that removing the contract gate and the old namespace-stable
allowlist repaired the current tree's 1,242 direct declarations. It falsified
the broader namespace and proof claims above:

- the replacement still recognizes special operations through hard-coded
  local names and silently misattributes qualified lookalikes; see
  [[../resolve-namespace-changes-by-executable-operator-identity]];
- an executable nested declaration can still disappear without a row or
  refusal; see
  [[../account-for-declarations-inside-executable-top-level-forms]]; and
- the recurring coverage test shares the production reader's event stream and
  set-collapses occurrences; see
  [[../make-function-coverage-independent-and-cardinality-preserving]].

This archived issue remains the historical owner of the old allowlist and
contract-gate incident. The three narrower open issues own the falsified
follow-on guarantees.
