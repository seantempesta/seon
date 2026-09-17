---
type: research
status: in-progress
created: 2026-09-17
tags: [reset, datahike, schema, integration]
---

# Reset edge integration — worktree checkpoint

**Not ready to merge. RESET NEEDED.** The implementation checkpoint is retained in
`tmp/reset-batch-wt`, branch `reset-batch`. The current base includes
`024991490`; the documentation commits have been rebased onto it. The
orchestrator owns merge, the cold gate, platform proof and the reset. Default
was not restarted, reset, adopted or otherwise mutated by this lane.

The complete named Datahike modeling study was read end to end, including its
correction table and standing patterns. Its row-by-row disposition is maintained
in [the reset plan](../../steward-platform/plan/reset-batch-2026-09-17.md#modeling-study-correction-ledger--bd5923a8c-1i-controls),
not a second edit schedule here. The changes to the implementation include
identity-only removal and rename detection; exact analyzer-input provenance;
optional empty memberships; agent archive facts and a writer refusal of agent
identity retraction; canonical arity shape links replacing AST maintenance; and
the capability symbol as a final deletion obligation. Indexed symbol edges and
preserving issue status were already selected. G5 complete owning values,
and the remaining message/wake §1h integration remain outstanding. The four
maintenance constructors and their empty/partial outcomes now have armed proof.

## Iteration evidence

- Fast 16: 38 tests, 288 assertions, one parity failure, no errors. All eleven
  reset regressions passed.
- Fast 17: 97 tests, 676 assertions, 33 failures, 10 errors. The reset and program
  namespaces passed; remaining failures were in the function namespace.
- Fast 18, after rebasing the SCI acquisition change: 187 tests, 1,784 assertions,
  51 failures, 6 errors. It includes newly exposed schema/provenance and reader
  regressions; it is not a green claim.
- Fast 19 stopped on an unmatched parenthesis in a newly added regression.
- Fast 20 stopped on a duplicate key while consolidating historical fault
  function attribution onto `:seon.instrument/fn`. Both initialization defects
  were corrected before fast 21.

Commands use `bin/test-fast --paths <owned paths> --` from this
worktree, one invocation at a time. `tmp/test-slots` points at the main checkout's
slots. No cold gate was run. The logs above are local iteration records; retain
final evidence under this research directory before landing.

Fast 22 completed 189 tests and 1,811 assertions with zero failures and zero
errors. It predates the subsequent §1h edits and rebase, so it does not prove
the current complete worktree.

## Corrections discovered by the iteration

Runtime redefinition still split edge assertions from the declaration map after
edges became values. It now exact-reconciles one complete row. `declare` spans
must not attribute references to a later function of the same name; the analyzer
excludes forward declarations when selecting the containing definition while
preserving method/protocol implementation attribution. The canonical parity
regression covers that distinction.

Two-keyword value collections can trigger Datahike's lookup-ref heuristic. The
existing encoder emits explicit member assertions, including expanded transaction
function output; see [the issue](../../../seon/issues/two-keyword-value-members-are-mistaken-for-one-lookup-ref.md).
Rejected issue notes must not prime incomplete identity rows; see
[the issue](../../../seon/issues/refused-issue-notes-mint-incomplete-identity-rows.md).

Historical faults now use the existing `:seon.instrument/fn` symbol. Error
recording no longer creates function or namespace identities for an observed
stack frame. This is the plan's existing duplication correction, required by
G3's removal of identity minting. Call preparation and read replay also had
string-valued function lookups that the new installed symbol type exposes.

The program operation's already-admitted native SCI call moved into the existing
SCI evaluation owner. The program mutation owner still decides the operation;
this does not introduce another evaluation path or bypass the database writer.

## Boundaries and remaining proof

The message-wake-model lane owns `seon.message.edn` and `seon.wake.edn`; neither
was edited here. Seam 1 at `a50424f6b` is now incorporated by rebase; its remaining work must
join §1h in the same publication. The original
`context_capture_history_test.clj` residue is tracked, clean and already landed
at `79c106925`; there is no unfinished test to discard.

G5 still needs the before/after owning-root traversal, typed child schemas,
complete traversal beyond 1,000 children, and the child-only/unlink/multiple-parent/
cycle proofs. A budget-location question was sent to the owner: a schema-projection
node budget (recommended), a budget supplied by every transaction constructor,
or a database-size-derived termination bound. No universal living-ref enforcement
or dependency fork change has been introduced.

The equivalent-population reverse-walk measurement is recorded below; reset-boundary
live proofs remain owed. Never interpret the dated live counts from the research as
measurements of this branch. Merge only after the completed group is green and
reviewed: `git merge --ff-only reset-batch` from `steward-platform`.

## Updated proof boundary

Fast 26 completed **125 tests / 900 assertions / one failure / zero errors**.
The only failure is the unchanged two-times-raw query-cost assertion. Its exact
measurements and the pending alternatives are in the reset plan. Functional
origin deletion-survival, optional empty maintenance, historical fault identity,
program history and real SCI mutation regressions passed. G5 has not been
implemented; the branch must not be merged or reset on this evidence.

The post-rebase launcher rejects setting `SEON_TEST_SLOTS`, even to its own
three-slot default. Fast 24 exited 64 without taking a slot. Subsequent runs
omit the override and use the declared three slots; the worktree's slot-directory
link still points at the shared main-checkout directory. No bound was raised.

The pinned grep is now also empty for `:seon.effect/capability-fn`; effect
receipts carry the exact dispatched handler symbol. Fast 27 verifies this follow-up: **152 tests / 1,500 assertions / zero failures
and zero errors**, across reset edges, fn, program, schema, schedule, render
entity pairs and effect. It does not include the two subsequent deletion tests.
The newer targeted run verifies those separately.


## Edge implementation checkpoint

Fast 28 passed **32 tests / 180 assertions / zero failures / zero errors**:
`seon.reset-edges-test`, `seon.reset-edge-parity-test`, `seon.effect-test`.
This includes the new test-retraction and capability-handler refusal cases.
All three reverse closures matched over 29,622 equivalent relation edges:

| Seed | Reached | Ref ms | Symbol ms |
|---|---:|---:|---:|
| seon.turn/open? | 2,095 | 45.509917 | 49.247542 |
| seon.db/q | 2,704 | 53.527750 | 55.465583 |
| seon.id/id | 2,222 | 39.164167 | 48.154334 |

The same snapshot reports 70 namespace referrers for `seon.turn` and 102
attribute/contract referrer groups for `:seon.db/connection`. These are canonical
fixture measurements, not the research note's dated live counts. Assertions
compare the complete refusal to the database-derived expected relations.

The message/wake follow-up landed at `57581f12f` during this run. Rebase and
integration verification follow this checkpoint; fast 28 does not claim that
later snapshot. G5 and the recorded relative-query-cost failure still prevent
merge/reset readiness. The owner design options are in the reset plan.
