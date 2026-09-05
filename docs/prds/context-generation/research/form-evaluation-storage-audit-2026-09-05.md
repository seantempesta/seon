---
type: research
status: working
---

# Form and evaluation storage audit (2026-09-05)

This is a dated comparison of the current model with a unified form entity. It is research evidence, not a settled authority. Different attributes and lifetimes do not by themselves require different entities; the current split must earn its place against the actual consumers and transaction invariants.

## What the current code stores

`resources/seon/schemas/seon.cluster.run.form.edn` stores the immutable run input: a qualified form identity, run ref, ordinal, author, source, parse namespace, and optional `refreshes` ref. `src/seon/cluster/run.clj:616-619` derives that qualified identity, and `src/seon/cluster/run.clj:700-725` writes one entity per ordered source when the plan is frozen. Generated runs append a form only at the next ordinal (`src/seon/cluster/run.clj:827-850`).

`resources/seon/schemas/seon.cluster.eval.edn` stores the evaluation outcome: an unqualified `:seon.cluster.eval/id`, run and ordinal, timestamp, optional source/namespace, result or error, interruption time, read evidence, and test facts. `src/seon/cluster/run.clj:607-614` makes the identity distinct from the qualified form identity because the agent-facing problem resolver searches identity attributes. The start transaction creates the row without a terminal attribute (`src/seon/cluster/run.clj:1040-1089`); settlement refuses when any terminal attribute is already present (`src/seon/cluster/run.clj:1665-1710`).

The loop currently duplicates source and namespace into the evaluation row. It constructs the result from a completed evaluation at `src/seon/cluster/loop.clj:228-307`, and joins result, delivery, and error facts at `src/seon/cluster/loop.clj:672-701`. Before execution it starts rows for all frozen sources (`src/seon/cluster/loop.clj:1449-1462`). Consequently, an evaluation row with no terminal attribute proves admitted intent and an outstanding slot, not that execution began. Recovery marks dangling rows interrupted and leaves terminal rows untouched (`src/seon/cluster/run.clj:30-48`).

## Model comparison

The current two-entity model gives the input and outcome separate identities, which makes an agent-facing problem handle easy to resolve and lets a form exist before an evaluation row. Its cost is repeated source/namespace data and two identity-resolution paths for one `(run, ordinal)` pair. The current code does not prove that this cost is necessary.

A unified form entity could retain the frozen source and add optional evaluation attributes: result/error, interruption, read basis/evidence, ending namespace, and test facts. The settle-once fence could still be based on terminal-attribute presence; the form identity could remain qualified while a separate agent-facing problem key could be an ordinary non-identity value or an explicit query projection. This preserves the facts that matter without duplicating source and namespace. The design question is whether existing consumers require an independently addressable evaluation identity or a row that can be created before its form is visible.

Current consumers and implications:

- `src/seon/cluster/loop.clj:1126-1205` reads form source and namespace before admission. A unified entity satisfies this directly.
- `src/seon/cluster/loop.clj:1449-1462` pre-creates evaluation rows for every source. The form already proves admitted intent; the evaluation timestamp does not prove execution start. A simplification candidate is deleting the duplicated start row and deriving unfinished work from the run, frozen forms, and terminal outcomes, but the generated-run recovery predicate still needs a transaction-level falsifier.
- `src/seon/cluster/run.clj:1040-1089,1665-1710` starts and settles by `(run, ordinal)` and terminal presence. These invariants can be expressed on one entity; no separate entity is proven by them.
- Recovery at `src/seon/cluster/run.clj:30-48` currently marks dangling evaluation rows interrupted. If those rows are removed, recovery must derive the unexecuted suffix from the run and prior terminal outcomes; no per-form interruption mirror should be added without proving that derivation insufficient.
- Evaluation/result rendering uses the result/error and ending namespace projection assembled at `src/seon/cluster/loop.clj:228-307`; it does not inherently require a second entity.
- Curation and problem routing use the agent-facing evaluation identity (`src/seon/cluster/run.clj:607-614` and callers of `seon.cluster.work/problem-id`). A unified model must preserve a stable queryable handle, but that handle need not force duplicate source storage.
- Provider retries are genuinely separate facts: `:seon.ai.attempt` derives identities and stores each request/outcome at `src/seon/cluster/loop.clj:909-939,1018-1108`. This is the case where one form/evaluation cannot replace many attempts; retries must remain separate attempt entities.

## Falsifiers and next evidence

The two-entity choice is justified only if a unified form cannot preserve all of these properties: a frozen source before execution, an explicit pending state for pre-created work, one terminal settlement, crash recovery, a stable agent-facing problem handle, and independent provider attempts. A useful falsifier is a transaction-level test that unifies source and outcome and exercises pre-created later ordinals, crash interruption, settlement retry, problem resolution, and curation refreshes. A second falsifier is a measured store comparison: source duplication must be shown to matter after accounting for Datahike history and index copy-on-write.

The authorized simplification candidates are deleting duplicated evaluation
source/parse-namespace fields first, then deleting the pre-created evaluation
identity/timestamp if the recovery predicate proves equivalent. Preserve the
distinct actual evaluation namespace and ending namespace where consumers need
them. The remaining falsifiers are the generated-run crash suffix, stable
problem/effect references, curation refreshes, and provider-attempt/effect
independence.

One recovery counterexample narrows that last question. `src/seon/cluster/run.clj:1839-1865`
leaves a generated run open while asserting `:seon.cluster.run/interrupted-at`,
and there is no corresponding retract of that attribute. Therefore “run has an
interruption and form has no terminal outcome” is not enough to identify an
abandoned form: a form appended after recovery would be falsely classified as
abandoned. A unified model can still avoid a per-form interruption attribute,
but recovery must compare transaction order: the immutable form source datom
transaction (from `:seon.cluster.run.form/source`) must precede the current
`interrupted-at` datom transaction for the nonterminal form to be abandoned.
A post-recovery form has a later source transaction and remains pending; a later
crash replaces the cardinality-one interruption with the newest recovery point.
This is a falsifiable transaction-level invariant, not a settled design claim.

## Fault-storage measurement

The focused file-store regression, with a same-policy collector baseline,
measured 52,750,724 bytes before the repeated fault, 53,263,771 after one, and
143,665,214 after 500. The one-fault increase was 513,047 bytes; the 500-fault
increase was 90,914,490 bytes. The existing collector returned `671`, leaving
56,242,009 bytes, while all 500 fault facts and the retained blob remained
retrievable (maximum inline value: 658 characters). This does not support an
unqualified “kilobytes” acceptance claim: collection reclaimed most of the
storm growth, but the remaining footprint and per-fault overhead still need an
owner decision. It is consistent with the configured persistent-set index
(`src/seon/cluster/store.clj:155-174`) and `:keep-history? true` retaining
historical datoms and index roots pending reachability collection. The
vendored leaf UUID is derived from leaf contents
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:272-282`),
while GC marks temporal roots when history is enabled
(`reference-code/datahike/src/datahike/gc.cljc:61-70`). The measurement is
therefore an unexplained pre-collection footprint: the evidence does not prove
that every prior leaf copy remains reachable, nor that the identical payload
was written as 500 independent blobs. A follow-up must measure before and
after the existing registry collector and verify that the 500 facts and their
blob remain retrievable.

The recurring fixture performs that collector pass on its isolated store,
records post-collection bytes, and verifies all 500 facts plus the retained
blob remain readable. A direct one-test JVM run preserved the measurement in
`tmp/fault-storage-direct.log`; the focused post-collector gate also passed 2
tests with 18 assertions. Storage growth acceptance remains unproven.
