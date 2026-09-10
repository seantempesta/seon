---
type: research
status: complete
tags: [context, render, documentation, testing]
---

# Context nits — 2026-09-09

Read the named issue and chart PRD end to end, including §9.4. AGENTS.md
has no §10 heading: read its opening verbatim turn-PRD §10 lane rules.
Read the roadmap entry and working edge and the Clojure, REPL, testing,
and Datastar skills. This bounded assignment has no delegated agents.

## Dependency ledger

Datahike's pull implementation at
`reference-code/datahike/src/datahike/pull_api.cljc:246` returns absent
pull results as nil. The existing first-party owners are
`src/seon/note.clj:61` and `src/seon/render/transcript.clj:1077`.
SCI's macro vars and namespace installation own `doc` and `dir` through
`src/seon/sci/eval.clj`'s existing program-documentation projection;
`reference-code/sci/src/sci/core.cljc:260` owns interning. Contract
reports originate in `src/seon/instrument.clj:293` and cross the one
`src/seon/sci/eval.clj` evaluation boundary before value rendering.

## Slice 1

The trigger selector names message id, content, and the sender's agent id.
The notes form extracts the reverse edge with `[]` as the empty value.
The canonical database fixture executes both generated forms through real
SCI with armed contracts, then adds a note and executes the same read.
Exact source and shown bytes are in
[the capture](context-nits-slice1-bytes-2026-09-09.txt).

Path-isolated gate: 6 tests / 176 assertions, zero failures or errors.
Separate path-isolated platform gate: 84 tests / 505 assertions, green.
`SEON_TEST_WORKERS=3`; no full suite. Capture rerun: 1 test / 8 assertions.
The loop regression's expected runtime form was updated with its selector.

Live initial MCP JVM probe returned the bare sender `#:db{:id 36216}`
and nil notes in 1128 ms. Default PID remains 83040; no lifecycle operation
was performed. Adoption and final page observations are recorded below.

## Slice 2

`dir` now returns `:schemas` and `:functions`; each referenced schema is
named once in the schema map and each function retains its input/output
contract structure with named references. Namespace declarations join
that map through the existing evaluated reverse pull. `doc` returns
summary, body, final example source, and expanded input/output forms.
The four requested my.* docs follow the convention; the plan's final
example is one `do` form containing its three demonstrated writes.

Fast gate: 2 tests / 58 assertions, green. Isolated documentation/settings
gate: 2 tests / 60 assertions, green. Platform: 84 / 505, green.
The directory read-evidence regression's isolated gate is 1 test / 15
assertions, green, including invalidation after a new schema declaration.
The older `seon.sci.eval-test` printed-documentation expectations are
already tracked in the existing retired-storage test issue; this slice
uses the canonical documentation and directory regression owners.

Default's JVM evaluation using its real cluster handle returned the new
`my.message/send` documentation and shown text in 19 ms. The MCP SCI
transport separately returned a projection failure naming an immutable
database input to `seon.sci.kernel/invoke`; it is not treated as a
successful observation. The first explicit adoption reached SCI acquisition
and instrumentation but refused its final marker because source changed.
The loaded runtime/notes functions were verified updated; complete
convergence is checked again after the final edits.

Reproducible live capture: load
[context_nits_probe_2026_09_09.clj](context_nits_probe_2026_09_09.clj),
then call `(context-nits-probe-2026-09-09/capture! "final")`.

## Slice 3

The shared evaluation-result boundary attaches `:seon.error/doc` from
the named function's program row before rendering. Both returned and
thrown contract violations cross this point. The optional field is
declared in `resources/seon/schemas/seon.error.edn`; the existing
`seon.error/instrumentation-prose` renderer includes it. The first fast
regression caught that renderer dropping the doc, so the final regression
checks the live error map AND the shown example, no message writes, and
an unrelated error without a function doc.

Fast: 2 tests / 43 assertions, green. Isolated gate including the error
owner: 35 tests / 182 assertions, green. Separate platform: 84 tests /
505 assertions, green. Live default refused the invalid
message call in 33 ms and returned 984 UTF-8 shown bytes, including the
same documentation summary. Exact bytes:
[contract refusal](context-nits-contract-2026-09-09.edn).

The read-only [live capture](context-nits-live-2026-09-09.edn) records the
runtime, notes, directory and four docs: shown byte counts respectively
773, 2, 2332, 791, 664, 899, 397. This capture observed hot-reloaded
definitions before the complete adoption marker converged, and explicitly
records both source IDs rather than claiming convergence.

The [final capture](context-nits-final-2026-09-09.edn) includes the newly
declared optional error-doc field in the output contract. Shown sizes are
773, 2, 2395, 850, 723, 899, 397 bytes. At 03:13 UTC final explicit
adoption completed: the independently queried adopted and published IDs
both equal `6aa22005-3cf7-52c7-a38b-511c0e8a17f5`. The read-only capture
and refused-call capture were repeated after convergence. The earlier
03:09 query had unequal IDs; it is not the final state. The shared hook publication
`6bc12f9d-0c4c-47e7-b160-d08e33f4c2df` separately reported operator exit
124. Neither failure was repaired through a foreign session or a restart.

Production slices: `1d47ebb7f`, `d25a12eda`, `4652cc0e7`. A final
documentation-only checkpoint records convergence after the last adoption
client exited successfully. All owned shells have ended; successful gate
roots and fast-test snapshots were removed by their runners. No manual
scratch cluster or worktree was needed. Default retained PID 83040.

The slice-2 debug HTML was fetched and its text read: the would-be system
turn shows notes as `[]` and the explicit runtime trigger sender as
`#:seon.agent{:id "root"}`. Saved history still shows its original nil
and database id, as required by immutable historical shown text.
The URL was fetched and checked again after final convergence. No browser
was available for a visual-paint claim.

Owned production paths across the three commits: `src/seon/note.clj`,
`src/seon/render/transcript.clj`, `src/seon/sci/eval.clj`,
`src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`,
`src/my/message.clj`, `src/my/agent.clj`, `src/my/plan.clj`, `src/my/note.clj`.
Owned regressions: `test/seon/render/context_nits_test.clj`,
`test/seon/loop_proof_test.clj`, `test/seon/sci/documentation_test.clj`,
`test/seon/render/page_settings_test.clj`, `test/seon/directory_test.clj`.
The two issue updates, this note, probe and linked captures are the
documentation changes. The resolved trigger/notes issue is archived.

## Shared and tool boundaries

MCP runtime status timed out with health and Flow unknown; JVM evaluation
answered. Recorded in the existing
[component-probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
CUA reported no available browser. The assigned debug URL's served HTML
was fetched successfully; this proves HTTP content, not browser paint.
The evidence lane's `src/seon/db.clj` and `test/seon/read_evidence_test.clj`
edits are excluded from these gates and untouched. An existing shared
publication held the operator lifecycle lock; no foreign session was
operated. Untracked build/, workers/, and config/virtual-turns.edn were
preserved.
