---
type: landing
status: in progress
created: 2026-09-22
tags: [agent-platform, cold-gate, turn, bounds]
---

# Turn shapes and continuation completion

Initial HEAD `462c6fe68`. No default operation, worktree, cold gate, or foreign edit.
MCP runtime tools are discoverable; default prohibition takes precedence over the
usual status/evaluation start protocol. Proof is the isolated fast JVM.

## Reproduction before production changes

`bin/test-fast --paths src/seon/turn.clj test/seon/turn_continue_test.clj test/seon/cluster/turn_test.clj test/seon/rereads_test.clj test/seon/loop_proof_test.clj -- seon.turn-continue-test`

- Namespace baseline: `tmp/turn-shapes-before.log`, snapshot `run.tBrNjp`, PID
  84121, started 2026-09-22 07:46:23 local. First alphabetical member blocks.
  Samples `tmp/turn-hang-threads.txt`, `tmp/turn-hang-threads.json`,
  `tmp/turn-hang-threads-2.json`. Identity checked before TERM; shell exited 143.
- Exact requested member: temporarily removed other test Vars' `:test` metadata
  in the owned test file; no body or production edit. Log
  `tmp/turn-shapes-done-before.log`, PID 84664, start 07:48:39. Sample
  `tmp/turn-hang-done-threads.txt` and virtual-thread-aware JSON sibling.
  Identity checked before TERM; shell exited 143. No recorded tally claimed.
- Missing event: added `:seon.turn/closed-tx` datom while installing Juniper's
  scenario, before continuation assertions. Main thread is in
  `context_blocks_fixture.clj:202` (`submit!` local `await!`), called at `:219`
  after `agent-already-running`, via `install!:274` and `prove-session:81`.
  Core.async `alts!!` (`async.clj:345-356`) waits on event or timeout. The supplied
  timeout is **600000 ms**, from `config/default.edn:158`, longer than the cold
  worker's 270-second bound. This explains the missing test-level diagnosis.
- First virtual-thread sample: turn proc traverses
  `seon.render/render-program-evidence:723` → Datahike pull-many; second sample
  has no turn-proc transform stack, but completion observers remain at
  `turn.clj:5452`. Exact done sample captures evaluation in
  `call-preparation/contract-transaction:604` → Datahike query merge execution,
  through `evaluate-sources`, `resume-turn`, `generate-turn`, `step`.
  These are observations, not yet proof of the escaping fault's cause.
- Test configuration only changed to an explicit 5000-ms lifecycle bound:
  `tmp/turn-shapes-bounded-before.log`, run `ec639f48a076`: 1 executed, 0 reused,
  2 assertions, 1 failure, 1 error, 13577.163417 ms. Teardown names the absent
  proc-stop acknowledgement. Fixture cleanup masks the primary exception.
- First shape candidate: `tmp/turn-shapes-done-after1.log`, run `a7bea3fde82c`:
  1 executed, 0 reused, 2 assertions, 1 failure, 1 error. Still red. Printed
  primary completion fault names turn `f4c23ce4f31a` and the 5000-ms bound.

## Foreign boundary and pending scope

The shared wait and masking cleanup live in `test/seon/context_blocks_fixture.clj`
(not assigned). Asked owner to include that file; alternatives are staying within
owned callers or handing the fixture fix to the orchestrator. No edit there pending
scope decision. Other lanes' dirty files are excluded by HEAD-plus-owned-paths.

Read `lane-error-facets-2026-09-22.md`: its phase hand-off asks that callback output
not claim the phase-failed member. HEAD already documents phase as unchecked and
has an explicit polymorphic exemption. Clarified that callback results do not
promise that member. Do not reintroduce an `:any` union pretending to check it;
the matching-data-branch wrapper fix belongs to the error-facets lane.

## Dependency evidence

Pinned core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`; caller supplies the
channel and timeout to `alts!!`. It publishes whichever operation completes;
there is no implicit shorter bound. Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`;
Datahike `6dd49e5eab243a42ebd8d847830d037d0dae3c6a`.

## Current changed paths and limits

- `src/seon/turn.clj`
- `test/seon/turn_continue_test.clj` (includes temporary focused selection)
- this landing note

No completed continuation proof, three-run duration measurement, final combined
run, HEAD load, publication/adoption, browser observation, or commit claimed yet.

## Contract-proof prerequisite discovered during the owned-path run

`tmp/turn-shapes-cluster1.log`, snapshot `run.dUs5Xl`: source at
`src/seon/turn.clj:4001` contains the new operation-input contract, but
`attempt-model-identity-survives-descriptor-retraction` still refuses the old
`:seon.turn.loop/cluster` contract (`log:201`). Likewise the generated-read
call still reports the old evaluator shape (`log:1384`).
`instrument/compiled-wrapper:740` selects the supplied projection's stored
function contract ahead of authored metadata. The fixture carries the old exported
program. HEAD-plus-paths is source isolation, not proof of candidate contract adoption.
Asked owner for exactly three options: expand scope to the fixture/overlay owner
(recommended), have the orchestrator prepare the matching publication, or limit
this slice to diagnostic/source work with proof explicitly incomplete.

Duration observation: `tmp/turn-cluster-duration-threads.json`, PID 86316,
main thread in `render-program-evidence:723` → `db/pull-many` →
`validate-pulled-result` → `pulled-entity-schema-key`, from web context derivation,
cluster prompt, `call-turn`, `step`, cluster turn test. The renderer traverses the
transitive call closure per new call evidence, even with no program change.
No attribution of exclusive cost or three-run quiet measurement is claimed.
The renderer/database owners are outside this assignment; B2 remains deferred.

Additional owned edits: `test/seon/cluster/turn_test.clj` replaces an infinite
render-proc stop take with a named 5000-ms `await-event!`; `test/seon/rereads_test.clj`
adds a focused regression checking actual captured turn-attribute read evidence
without requiring an evaluator result. Temporary test selection has been removed.

## Checkpoint awaiting the scope decision

The two-namespace run completed all 94 bodies before termination: printed (not
recorded) tally **439 assertions, 56 failures, 54 errors**. No run ID/final recorded
facts were obtained. PID 86316 identity (start 07:52:55) was checked before TERM;
shell exited 143. Retain the failed snapshot for unresolved recording/fixture
inspection. Do not read this as candidate-contract evidence.
`tmp/turn-shapes-cluster1-durations.json` contains parsed progress intervals:
60 cluster tests completed, 24 exceeded 5000 ms, range 1.229–15514.583 ms. Later
fast failures do not prove speed; this is neither a quiet-machine measurement nor
the requested three post-fix repetitions.

Current source load: `clojure -M -e "(require 'seon.turn)"`, exit 0;
`tmp/turn-shapes-load.log`. clj-kondo across the four edited Clojure files reports
0 errors, 50 warnings, 2 informational findings; `tmp/turn-shapes-lint.edn`.
`git diff --check` passes. All owned shell sessions have exited. No operation
was made against default PID 38968 or other lanes' JVMs.

Current candidate changes, excluding this note: turn +26/-8, continuation +10/-2,
cluster turn +2/-1, rereads +18/-0. The check consumes an operation-specific map of
captured read evidence, not a handwritten pulled entity. All three production
callers select precisely that input. No stored attribute schema is widened.
The attempt writer consumes an explicit recording context; process/caps/error
settings remain optional as in the existing successful-attempt call boundary.

Remaining: matching candidate fixture/contract adoption, primary-fault-preserving
shared wait, continuation success and explicit before/after regression evidence,
three honest duration runs and any justified owner fix, requested five-namespace
recorded green. B2 context/turn redesign has not started. No finished-cut claim.
