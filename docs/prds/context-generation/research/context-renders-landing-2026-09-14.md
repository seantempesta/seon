---
type: research
status: active
tags: [research, render, context, test]
---

# Context render rules — 2026-09-14

Work in progress. No final verification claim yet.

## Commits

- `d0817d49b` — deterministic printer scalar ordering and intact map
  coordinates; isolated gate 49 tests / 238 assertions.
- `1f18b99fc` — change-only rereads, isolated gate 8 tests / 250 assertions.
  Three idle turns: zero appended evaluations and bytes. Plan change: 269
  emission bytes for 77 subtree bytes; message: 452 for 124.
- `0c70a1cb4` — do not promote turn-dependent agent queries into generated
  reads. Isolated gate 4/216 tests/assertions; three turns including the
  production wildcard query add zero generated evaluations and system bytes.

## Printer: coordinates and scalar ordering

The printer now orders mixed numeric representations without cross-type
numeric comparison. Map keys remain coordinates: when a key exceeds the
profile, the printer omits whole entries with one explicit count rather
than changing the key. String elisions name the bound that actually cut.
The generated-value regression includes ratios, decimals, finite doubles,
symbols, characters, and scalar map keys. It parses every output, compares
two renders byte-for-byte, and evaluates every emitted requery in real SCI.
Fast gate: 49 tests / 234 assertions. Isolated path gate: 49 tests / 238
assertions, zero failures or errors. This slice changes no opening reads;
the previous loop proof remains zero system bytes over three idle turns.

## DB projection performance gate

Native non-string Datahike attributes cannot use the schema bridge's EDN
string codec. Read decoding now checks the installed physical schema before
forcing the operation's logical projection. Query find-attribute derivation
no longer constructs a whole projection merely to cache its answer. String
fallbacks still consult the logical schema, including temporal-origin cases.
There is no global cache or scenario-specific query path.

The unchanged full DB fast gate passed 41 tests / 272 assertions; the
isolated gate passed 41 tests / 276 assertions, including the unchanged
ten-unhanded-queries ≤ twice raw cost assertion and codec/evidence tests.
This closes the earlier DB timing verification boundary. Opening/read
membership is unchanged; the last loop proof is zero system bytes over
three idle turns.

## Change-only rereads (rule 2)

Changed generated evaluations store a plain EDN changed-path map. The full
live result stays behind the committed evaluation handle. The REPL emits
the fixed system comment, unchanged read form, delta, and executable
`(get-in result/e… [])` hint. Timing is omitted from changed emissions so
identical changes remain byte-identical. Historical shown values reconstruct
by applying their saved deltas; no second durable full-value copy exists.

Measured by the real virtual loop: plan objective change **269 emission
bytes / 77 changed-subtree bytes**; message arrival **452 / 124 bytes**.
Both responses parse as EDN, evaluate their full-value hints, and fit the
same eight-times-subtree assertion. Three no-event turns still append
**0 evaluations / 0 system bytes**. Fast loop/grammar: **7 tests / 245
assertions**. Isolated loop/grammar gate including the generated EDN diff
property: **8 tests / 250 assertions**, all green. The full DB namespace's
previously recorded projection-cost failure remains for the performance
slice; its assertion has not been relaxed or removed.

- `e81b119f2` — retain stored system results in the agent's SCI context.
  **RESET NEEDED for `e81b119f2`**: boot must supply the context-state carrier.
  Fast 6/232 and isolated 6/236 tests/assertions pass; zero no-event system bytes.

- `8df86358b` — preserved compact pairs and plan (rules 4 and 5), with the
  whole-item printer and marker groundwork described below. Fast runs:
  53/425 and 56/335 tests/assertions, both green; zero no-event system bytes.

- `0dca8534e` — no-self-dependency invariant, collection-bound read evidence,
  and stable opening reads. Three virtual turns: **0 generated evaluations,
  0 added system bytes**, versus the recorded historical **458 bytes per
  refresh**. One plan write and one message each append only their own read.
  The invariant fast proof and platform gate pass; the separate pre-existing
  DB projection-cost gate failure remains explicitly recorded below.

## Preserved slice checkpoint

The resumed slice lands rule 4's compact transaction/directory/plan pairs
inside response values and rule 5's compact plan. It also preserves the
whole-item printer work and the fixed reread marker. These are foundations,
not a claim that change-only rereads or stored-result requery ownership are
complete. The owner explicitly requested this preserved mixed slice be
committed before continuing the remaining rules separately.

The printer orders collection members structurally before rendering visible
children. Rendering every member to obtain a sort key made the saved broad
query exceed the loop's existing deadline; the structural comparison fixes
that work without changing the deadline or presentation limits.
Fast verification: **53 tests / 425 assertions, zero failures or errors**
across value, print, and loop-proof tests. The loop measures **3 virtual
turns / 0 generated rereads / 0 added system bytes**; plan and message
changes each select only their own read.

The final value/plan/help/REPL-grammar/help-trial fast run passes **56 tests /
335 assertions, zero failures or errors**, including the final specificity
lookup change. These fast results are the owner's requested commit
checkpoint; isolated and platform gates for the completed lane follow.

The additional render-simplification suite is red on unchanged HEAD too:
**21 tests / 122 assertions**, with stale attribute-pair, identity-source,
and preview-custody expectations. Its result is not counted as green for
this slice. The owned nested-response regression exercises current pair
contracts instead. The concurrent CSS and transcript changes belong to
debug-turns and are excluded from this commit.

## Next slice dependency

### Live run 3 and reset boundary

The context-ownership slice (`Retain system results in the agent SCI
context`) passes its isolated fast run: **6 tests / 232 assertions**;
its `bin/test --paths … -- seon.loop-proof-test seon.help-trial-test`
gate passes **6 tests / 236 assertions**. Stored system handles evaluate
through SCI, and acquisition returns the same context across repeated
calls and graph arming. Its no-event loop is still **0 added system bytes**.
**RESET NEEDED for this commit**: the cluster handle now owns
`:seon.agent/context-state`, created at boot, never lazily fabricated.

Owner-observed run 3 on default reached **21 turns with only 2 system turns**:
the opening and one plan reread. There was **zero context churn**. At
04:07Z the hot-adopted context acquisition function met a handle built
before `:seon.agent/context-state` existed. Juniper fault `d217ae07…` and
root fault `ea7ee3f1…` stopped their turn procs. **RESET NEEDED** for the
context-ownership commit below: the carrier is boot-time state. No lazy
fallback is introduced; the orchestrator owns the single default refork.
The lane has not stopped, restarted, or reseeded default.

Read-only MCP inspection of fault
`7ab85b9a-1107-446f-9bd8-c5694ecc7455` confirms its evidence includes the
offending form, with no elision:

```clojure
(seon.db/q '[:find (pull ?e [*]) :where [?e :my.plan.item/id]])
```

The original stored evaluation is **agent-authored**, turn `0ab4ce1cb9ca`,
not an opening render. Its wildcard evidence includes the excluded
families. Since-diff incorrectly promoted it into a generated read.
The general correction admits only turn-independent retained agent reads
to generation; turn-dependent queries remain available on demand.
Declared opening reads still cross the fault guard. The plan opening
already uses the explicit selector behind `(seon.plan/plan {})`.

The promotion correction's isolated gate passes **4 tests / 216 assertions**.
The three-turn no-event proof now includes that exact wildcard plan query as
an agent evaluation. It still appends **0 generated evaluations / 0 system
bytes**; a subsequent plan write appends exactly the declared plan read.

Change-only rereads require working full-value handles. The existing
[system-result issue](../../../seon/issues/system-turn-drops-live-results-after-saving-shown-text.md)
identifies the ownership seam: `system-turn` discards its preview context;
`seon.cluster.agent/arm!` creates the retained agent context later. Context
acquisition must serve stored openings too and be reused when arming. The
owner has now authorized context acquisition and `arm!`, `seon.db/diff`,
schema-producer specificity, and the DB/schema projection performance owner.
The marker now excludes virtual `:call` turns, matching the invariant's
existing-provenance distinction; that later-slice edit is not committed.

## Grounding and boundary

Read AGENTS.md, the run-2 landing, both explain-probe `:text` values,
the self-churning-read issue, and turn PRD §§14, 15, 18–18d end to end.
The model explicitly mistook generated settings/runtime rereads for its
own repeated actions and reported mid-word documentation cuts with
unusable requery hints. The owner's correction places printer work last:
one deterministic structural printer, no scenario-specific elision rules.

The lane preserves concurrent edits in transcript.clj outside
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
refresh (run-2 landing); the revised no-event loop adds zero system bytes.

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
shared working tree at that earlier checkpoint. The subsequent invariant
commit is `0dca8534e`, with its actual gate boundary recorded above.
