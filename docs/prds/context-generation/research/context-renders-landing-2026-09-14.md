---
type: research
status: active
tags: [research, render, context, test]
---

# Context render rules — 2026-09-14

Work in progress. No final verification claim yet.

## Grounding and boundary

Read AGENTS.md, the run-2 landing, both explain-probe `:text` values,
the self-churning-read issue, and turn PRD §§14, 15, 18–18d end to end.
The model explicitly mistook generated settings/runtime rereads for its
own repeated actions and reported mid-word documentation cuts with
unusable requery hints. The owner's correction places printer work last:
one deterministic structural printer, no scenario-specific elision rules.

The lane preserves concurrent edits in render.clj, transcript.clj outside
render-runtime-ai, CSS, web-debug tests, and the core-functions lane.
Default remains the stored run-2 session; no reseed, stop, or refork.

## Dependency ledger

- SCI: `reference-code/sci/src/sci/core.cljc:330–369`, reusable contexts,
  copy-on-write forks, and evaluation. First-party result ownership:
  `src/seon/turn.clj` evaluate-sources and `src/seon/sci/eval.clj` bind-result!.
- Datahike: `reference-code/datahike/src/datahike/db.cljc:132–154`,
  transaction reports and inclusive as-of/exclusive since; transaction
  API at `reference-code/datahike/src/datahike/api/impl.cljc:30`.
  First-party read evidence and rendering live in `src/seon/db.clj`.
- Printer: `src/seon/print.cljc` owns nodes, fitting, elision and emission;
  `src/seon/render/value.clj` projects values before emitting that grammar.
- Schema pairs: `src/seon/render.clj` project-node selects declared pairs;
  `src/seon/repl.clj` owns evaluation grammar; the history walk calls it.

## Baseline

Read-only MCP JVM probe on default, basis 536881414: generated opening
preview **14,923 UTF-8 bytes / 4,660 estimated tokens**, ten evaluations.
This is a regenerated preview against the existing record, not a rewrite
of the stored historical opening. Reproducible script:
[context_renders_probe_2026_09_14.clj](context_renders_probe_2026_09_14.clj);
exact bytes: [context_renders_before_2026_09_14.edn](context_renders_before_2026_09_14.edn).
The live record has 137 evaluations and zero turns left.
Run-2's historical measurement was 458 added bytes per ordinary system
refresh (run-2 landing); the revised loop measurement is pending.

## Publication and verification

The initial edit-hook publication refused because source changed during
analysis (logged digest-before/digest-after differ). No successful live
adoption is inferred from that attempt. Tests use HEAD-plus-owned-paths
snapshots; the initial fast loop also exposed two already-stale help
expectations (empty notes now use `get` and show `[]`), corrected in place.

## Current verification boundaries

- The initial virtual-turn proof observed three turns with **zero settings
  or runtime rereads and zero added system bytes**. The expanded invariant
  gate is not green yet.
- Printer iteration: `bin/test-fast --paths` with `seon.print-test` and
  `seon.render.value-test` passed **47 tests / 212 assertions**. The
  generated-value property found and fixed a trailing-child distinction:
  a fully elided child is not the parent's omitted tail. Each generated
  requery is evaluated against a real canonical SCI context.
- Root's generated `seon.cluster.status/agents` and `snapshot` reads
  actually depend on turn/evaluation families. Their two rendering
  functions were explicitly authorized on September 14. Their generated
  forms now retain identity and adopted source only; statistics are
  documented as on-demand reads. The invariant reports forbidden reads
  rather than storing them.
- Collection-bound query attributes lack precise evidence in the current
  DB owner. See
  [bound-pull-selector-evidence-retains-all-attributes.md](../../../seon/issues/bound-pull-selector-evidence-retains-all-attributes.md).
  The authorized fix uses Datahike's `resolve-ins` and `collect` to bind
  every collection input row. No fixture-specific bypass was introduced.
  Complete explicit index patterns already retained by the DB
  remain authoritative, exactly as in the existing freshness check.
- The derived `seon.repl/frame` still needs its prompt-assembly call in
  `render/web.clj`, outside the original owned paths. Permission is pending.
- No paid rerun, isolated gate, platform gate, or final live-adoption proof
  has been claimed. No lane commit has been made yet.

## Pair and plan regression, 2026-09-14 evening

The combined fast iteration ran **77 tests / 494 assertions**, with one
failure and no errors. Printer, compact-plan and HTML golden assertions
passed. The remaining failure is the whole-plan response selecting both
its five-required-attribute component-view pair and the one-required-
attribute agent identity pair. Details and the bounded proposed owner fix:
[derived-map-render-pairs-compete-with-less-specific-entity-pairs.md](../../../seon/issues/derived-map-render-pairs-compete-with-less-specific-entity-pairs.md).
The regression now places transaction reports, directory maps, individual
steps and whole plans at the profile's depth boundary. Pair selection runs
before structural cutting for every reached map. Derived completed steps
declare their pair directly, including their completion timestamp shape.

The generated-read fault test passed in the 54-test turn/value iteration;
that iteration's sole error was an invalid current-step fixture input,
subsequently corrected. A later grammar-only snapshot accidentally omitted
the runtime selector edit and correctly rejected the old churning runtime
read. This is an incomplete test snapshot, not a foreign failure.

A detached `tmp/context-renders-wt` at `6a0781d21` held HEAD plus this
lane's changes, with only the render-runtime-ai hunk copied from the shared
transcript file. It excluded the other lane's uncommitted ledger-strip
changes. The complete requested fast selection ran **112 tests / 972
assertions: six failures, four errors**. Two failures were stale help
expectations after the plan read changed; those are corrected. Remaining
boundaries include the confirmed pair ambiguity, generated root/query
evidence, and an unresolved provider-continuation event timeout. The REPL
grammar namespace passed in this complete snapshot.

The isolated platform run prepared its base and began executing tests, then
received TERM during the orchestrator's cleanup. It has no final verdict.
A concurrent follow-up fast run was also interrupted. Neither interruption
is an implementation failure or a green result.

Read-only live check at basis **536881641**: default PID **23557** remains
alive; Juniper still has **137 evaluations**. No stop, restart, reseed or
paid provider rerun was performed.

## Remaining implementation boundaries

The scope requests concern concrete existing owners:

- `seon.db/query-index-patterns`: resolve collection-bound attribute inputs
  through Datahike's binding mechanism, so namespace-count reads carry
  precise evidence. `seon.db/diff` also needs a value-to-value arity for the
  requested changed-path rendering; its current API replays an identified
  database query across bases.
- `seon.cluster.agent/render-identity-ai` and
  `seon.cluster.status/render-ai`: root's statistics must become on-demand
  reads, leaving generated context dependent on stable facts.
- `seon.render.web/derive-context!`: append `seon.repl/frame` after saved
  history, so turns-left is derived without rewriting any evaluation.
- `seon.render/schema-producers`: apply existing required-attribute
  specificity to derived maps too; preserve ambiguity at equal specificity.

Changed-value rendering is not implemented yet. Its full-value link also
requires the system-turn path to retain the actual result in the supplied
agent SCI context. The current preview path forks and discards that result;
printing a synthesized handle would not prove a working requery. The
printer's generated property currently exercises real SCI-bound values,
not stored system evaluations. These are explicit remaining requirements.

## Resume after cleanup

The collection-bound regression passes under armed contracts: its evidence
covers both supplied attributes, an unrelated write leaves it current, and
a write to either supplied attribute invalidates it. The complete DB fast
iteration ran **41 tests / 272 assertions**, with one remaining failure in
the existing unhanded-query cost check. A HEAD-only DB comparison
established that this cost failure predates the evidence fix: unchanged
HEAD `4f9d8286e` measured **5,635,710,666 ns wrapped / 35,316,833 ns raw**.
The existing projection issue records this separate verification boundary.

The invariant-only iteration exposed two test/provenance distinctions.
The fixture's initial message must be shown after consumption before
measuring an interval with no outside events. Virtual replies carry
evaluation author `:system` but declared turn situation `:call`; generated
execution uses `:generate`. The generated-read guard now uses that existing
provenance, and the regression explicitly allows both ordinary and virtual
authored history reads. Measured system byte counts derive from actual
appended evaluations; they are not printed constants.

The corrected invariant-only fast run passed **29 tests / 706 assertions**
across `seon.loop-proof-test`, `seon.turn-test`, and
`seon.turn-continue-test`. It observed **3 virtual turns, 0 generated
rereads, 0 added system bytes**. One plan write appended exactly
`(seon.plan/plan {})`; one message appended exactly the generated inbox
pull. Its fixture opening contained **9 evaluations / 7,771 bytes**
(stored and acquired prompt equal). This fixture measurement is separate
from the default Juniper baseline above.

The isolated gate ran **74 tests / 1,007 assertions: one failure and one
error**. The failure is the unchanged HEAD DB projection-cost regression.
The error was an incomplete lane snapshot: it omitted the help-trial
helper's settings update. With that helper included, its fast tests passed
**2 tests / 22 assertions**; the targeted isolated rerun passed **2 tests /
24 assertions**.
`bin/test --platform --paths …` passed **84 tests / 505 assertions**.
The full DB namespace is not claimed green, and its timing assertion was
neither removed nor relaxed.

Live read-only check: default remains PID **23557**, with **137** Juniper
evaluations at basis **536881772**. Installed source
`6aa8b9e7-280b-5bea-9d8e-343fc8e1f435` differs from published source
`6aa8bb9f-bca3-5cd7-aeb5-47c6c03cdb97`; the adoption log reports source
changing during adoption. No live convergence claim is made.

Re-read the current code, schema and test diffs. Preserve debug-turns' new
`acquire-context!` caller assertions in `loop_proof_test.clj` and its prompt
helpers. The loop regression now checks every system-authored evaluation,
performs a system refresh after each of three virtual turns, adds an
exactly-one-plan-read case, and checks exactly one generated inbox read
after a message. The previous extra agent-authored inbox read is no longer
part of that one-read case. These expanded assertions await the invariant
dependency fixes and a fresh gate.

Live JVM binding probe: Datahike `resolve-ins` followed by `collect` returns
the two attribute rows from `:in $ [?attribute ...]` without implementing a
second binding mechanism. The pending DB fix can reuse those functions.
Schema formatting was repaired with an EDN equality assertion; declarations
did not change. No production edits crossed the requested scope boundaries.
The stopped lane worktree and scratch files were removed after checking
that no lane test process still held them. Production edits remain in the
shared working tree; no invariant commit or completed gate is claimed.
