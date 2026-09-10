---
type: research
status: active
tags: [research, runtime, agent, test, wave/agent-context]
---

# Live ordinary-turn verification — 2026-09-09

## Resumption — runtime-component stall, 2026-09-10 01:22–01:50 UTC

Read the replacement AGENTS.md and
`docs/seon/issues/turn-under-the-runtime-component-never-settles-on-default.md`
end to end. Re-read PRD §§3, 12, 14, the current roadmap, and the loop,
armer, installer, and dependency stop boundary. The data batch is not the
cause: both `open-for-agent` and `next-agent-work` correctly find the open
runtime turn, and Juniper's settings correctly select no-provider.

**Cause verified on both default and a fresh scratch boot:** Juniper had
an open `:call` turn but no armed graph. Only root remained in routing.
The shared live installer disarmed Juniper for cleanup, then wrote the
opening without starting its ordinary graph again. More subtly,
`disarm!` accepted the ready permit as completion even though Flow could
already have selected another wake. That proc could write after cleanup
and then stop. The old canonical proof explicitly armed before later
wakes and had no live armer/cleanup sequence.

Flow's `stop` sends control and closes report/error channels; it does not
join the proc (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174`).
Its stop transition runs after the active transform (`:209`, `:299`).
That existing transition now owns disarm completion. An idle permit no
longer proves stop. A queued proc must acknowledge stop or refuse teardown
under its bound. This corrects the former 100-interleaving regression,
which explicitly blessed cleanup before a queued proc had started.

`seon.turn` now caps the existing permit/active-transform completion
observer at the agent's evaluation time limit, retaining a smaller
configured backstop. No timer, scheduler, or fault route was added.
The shared `install-running!` owns the live installation sequence and
leaves Juniper armed; the research wrapper and recurring loop proof call
that same function. The controlled fixture installer remains available
for tests that explicitly own a stopped graph.

### Measurements and evidence

Before the fix, fresh source publication
`6aa20692-9b1e-5b41-83c3-d8e0b0b6acd4` reproduced the unarmed/open/zero-fault
state. A new message with unchanged production rearmed it and settled in
**713.563250 ms**. This independently falsifies the proposed runtime-ref,
closed-tx, and provider-selection causes for the observed stall.

The fixed fresh fork used publication
`6aa20891-bbaf-538d-8d97-0e51e5ead95a`. The seeded message settled in
**3,362 ms**, including eight stored opening evaluations; ordinary turn
`fbcdc2f3d4bf` opened at `01:36:50.816Z` and closed at `01:36:50.943Z`
(**127 ms**). A later message settled in **600.820375 ms**, stored one
refreshed evaluation, and changed turns-left **20 → 19**, with zero new
faults, evaluation errors, provider attempts, or unanswered wakes.

A scratch-only withheld-permit probe created a real open turn and set
its evaluation limit to **100 ms**. Its ordinary Flow error path committed
fault `ebeac556-06f0-4f04-b621-7a7b751dde12`, naming turn `9da757155232`
and the 100 ms bound; the durable fault was observed after **228.792375 ms**
(including dispatch and commit). The probe restored the setting and permit.

Default retained PID **83040** throughout. Adoption
`6aa2096b-260c-52f2-a928-40ed64609fbf` loaded the new stop-acknowledgement
behavior. Exactly one message was sent:
`loop-live/runtime-default-2026-09-09`, at `01:38:26.441Z`. Turn
`9bceed705a33` closed at `01:38:34.341Z`: **7,900 ms**, turns-left **19**,
four stored evaluations, and **zero new core faults**. One evaluation is
an independently recorded generated-runtime-pull error, described below.
The original `9fc9bc9ef8ad` is also closed. Default was never stopped,
reforked, restarted, or reseeded by this lane.

Exact artifacts (bytes as committed):

| Evidence | Bytes |
|---|---:|
| [Before](loop_live_runtime_before_2026_09_09.edn) | 325 |
| [Unchanged-code rewake](loop_live_runtime_rewake_before_2026_09_09.edn) | 590 |
| [Fixed message](loop_live_runtime_fixed_2026_09_09.edn) | 686 |
| [Seeded message](loop_live_runtime_seeded_2026_09_09.edn) | 2072 |
| [Fault deadline](loop_live_runtime_fault_bound_2026_09_09.edn) | 363 |
| [Default message](loop_live_runtime_default_2026_09_09.edn) | 1260 |

The probe script now understands inbox edges, runtime ownership, and
closed-tx. `observe-message` reads an existing message without resending,
uses transaction-reference pulls for times, and bounds its observations
at the closing transaction. The default live tool call timed out while
rendering the probe's failed no-evaluation-errors assertion; independent
fact observation verified settlement. Its one old backstop fault was
from `01:28:06.602Z`, before this lane's message, not a new regression.

### Gates and boundaries

The final isolated `SEON_TEST_WORKERS=1 bin/test --paths
src/seon/turn.clj src/seon/cluster/agent.clj test/seon/loop_proof_test.clj
test/seon/context_blocks_fixture.clj test/seon/cluster/agent_test.clj --
seon.loop-proof-test` passed **4 tests / 137 assertions / 0 failures /
0 errors** at HEAD `617e538f3` plus only these paths. The new runtime
fixture and a real open-turn permit failure are recurring proofs.
`bin/test --paths <the same paths> --platform` passed **84 / 505 / 0 / 0**.

The earlier three-worker gate hit the existing
[published-base connector defect](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md)
before fixture assertions; isolated confirmation passed. A separate
worktree/cache and one worker completed the gate. Concurrency inside the
graph-acquisition regression remains real. The broader agent namespace
and its HEAD-only baseline both report **20 / 112 / 10 failures / 3 errors**;
the corrected 100-interleaving stop test passes. Existing failures remain
in the [consumer issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md).

Cookbook temporarily held two disjoint `turn.clj` renderer-metadata hunks.
Implementation continued in a HEAD worktree until they landed at
`617e538f3`, then only this lane's patch was applied. No protected render,
REPL, help, or SCI files were edited. Its effective-settings read now
observes runtime turns; the old loop proof's no-refresh/one-occurrence
expectations were corrected while retaining the stored-prefix assertions.

Two independent findings are recorded, not hidden by loop success:
[generated runtime pull with nil identity](../../../seon/issues/runtime-block-generates-a-nil-agent-pull.md)
on default (evaluation `9811cc17667f`), and
[fixture schema re-admission after adoption](../../../seon/issues/fixture-schema-readmission-after-adoption-refuses-environment.md)
on scratch. Their protected owners were not changed.

The scratch root is downed and removed; both lane worktrees and retained
failed lane gate roots are removed after their runners exit. All command
sessions are reaped. No foreign session or root is operated.

## Initial slice — before the runtime-component resumption

Read AGENTS.md's verbatim §10 lane rules, the assigned issue end to end,
and turn PRD §§3, 12, 14 end to end, together with §§13–15, the roadmap,
and the turn loop. Skills: data-oriented-clojure, repl, clojure-testing,
seon-flow-architecture, datahike. `default` was only observed by MCP;
no stop, refork, or restart was performed on it.

## Dependency ledger and cause

- Datahike writer calls transaction functions against its current database:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
  `seon.turn/close-call` and `receipt-settle-call` correctly refuse an
  already-closed turn. The new `seon.db/transact!` validation was not the
  refusal in either decoded fault.
- Flow launches each graph independently; its serial transform belongs to
  that graph, not to a database agent identity:
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:252`.
  First-party owner `src/seon/cluster/agent.clj:618` formerly checked the
  routing entry, then created and published a graph without serialization.
  The live fixture's direct arm and the ordinary armer can create two
  graphs, each with its own turn completion permit. The canonical loop
  regression had only a manually controlled routing entry.
- `src/seon/turn.clj:4024` sent the intentional empty no-provider reply
  through the source reader's no-forms refusal. The old proof asserted a
  closed empty reply, but did not reject the resulting fault.
- `a4a0d457c` introduced the per-agent virtual selection. Current
  `ai/agent-overlay` correctly reads the settings component; the new
  ordinary-wake assertion removes the cluster setting and proves the
  component alone selects the virtual reply.

## Before evidence

The supplied default blob is 35,165 bytes and names Juniper, not root:
`seon.turn/settle! → call-turn/freeze! → close-call`, refusal
`:seon.turn/run-closed`. The scratch fresh boot (PID 94068) reproduced
`Batch refusal settlement was refused.` during fixture installation;
both the original and subsequent refusal writes named
`receipt-settle-call` / `:seon.turn/run-closed`.
The [scratch batch refusal summary](loop_live_batch_refusal_2026_09_09.edn)
retains those decoded outcomes, omitting their large request evidence.

The unchanged loop proof passed 1 test / 113 assertions while recording
no-forms faults. The strengthened proof fails on both new fault creation
and concurrent arms returning different graphs. The provider-refusal
regression uses the real `ai/complete` missing-credential outcome, then
the real `turn/settle!` writer with canonical schemas and validation armed.

## Changes and live verification

`arm!` and `disarm!` now serialize the routing entry's existing check,
graph construction, publication, and teardown on the routing monitor.
No second registry or completion mechanism was added. `call-turn` accepts
the intentional empty virtual reply as zero sources and lets ordinary
work derivation close it. Actual reader errors still settle as refusals.
Neither the settings read nor database validation required a change.

Scratch root: `tmp/loop-live-root`, cluster `loop-live`. The root-level
configuration disables ordinary turns absent an agent override; Juniper's
canonical settings supply its bound and no-provider selection. No paid
provider call is authorized or required.

The reproducible message/closure probe is
[`loop_live_probe_2026_09_09.clj`](loop_live_probe_2026_09_09.clj).
Its transaction listener is installed before sending, waits on a supplied
bound, and verifies stored evaluations, zero new faults, zero attempts,
and exactly 20 → 19 turns left.

Foreign edits observed in `src/seon/sci/eval.clj` and directory tests were
preserved. Scoped test snapshots include only the owned files.

The fixed scratch JVM was PID **96714**, started
**2026-09-09T22:55:21.367Z**, retained through development adoption.
Fresh publication was `6aa1e344-16df-5028-9b50-44df38ffa9d4`;
`init --dev loop-live --changed src/seon/turn.clj` converged to
`6aa1e497-d905-59f1-a800-fb4d6e2a02aa` without replacing that JVM.
The fresh configuration contained only
`{:seon.config.run/max-episode-runs :seon.config/absent}`; the shared
`juniper_fixture_2026_09_06.clj` installer supplied Juniper's component.
The message probe explicitly verifies absence of cluster no-provider and
presence of component no-provider before sending.

| Real proc observation | Fresh boot | Same JVM after adoption |
|---|---:|---:|
| Wake to closed ordinary turn, milliseconds | 2116.208291 | 1172.484125 |
| Stored refreshed evaluations | 2 | 2 |
| Turns-left | 20 → 19 | 20 → 19 |
| New faults / evaluation errors / provider attempts / unanswered wakes | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |

Both probes used a **10,000 ms** closure bound, and stored
`(my.message/inbox)` and `(my.agent/settings)`. A final query of all
`:seon.error/id` facts in this scratch cluster returned the empty set.
Exact results are [fresh](loop_live_fresh_2026_09_09.edn) and
[adopted](loop_live_adopted_2026_09_09.edn). The
[supplied blob](loop_live_fault_2026_09_09.edn) retains exactly 35,165 bytes
and SHA-256 `8ab5942fe7698af07d0937ed6fac24c52b8b210fc51990e73b6f659de630fd05`;
its [decoded value](loop_live_fault_decoded_2026_09_09.edn) is 7,440 bytes.

Fresh boot reproduced the refusal family before the fix; adoption is
therefore not necessary for that defect. This lane did not recreate the
older exact 282-second, pre-reply stall on PID 37586. It verifies the
specific duplicate-graph and empty-reply defects, and clean fresh/adopted
operation after the repair. Existing leaked graphs cannot be recovered
from a routing map that no longer references them. This change prevents
their creation; it does not claim to remove previously leaked instances
from default. No schema reset is required by this slice.

## Recurring gates and foreign boundary

`bin/test --paths src/seon/turn.clj src/seon/cluster/agent.clj
test/seon/loop_proof_test.clj -- seon.loop-proof-test` at `51a98c973`
passed **3 tests / 130 assertions / 0 failures / 0 errors**. It covers
concurrent real graph acquisition, a real missing-credential provider
refusal persisted through the validated writer, and the complete virtual
loop with the component-only ordinary wake and no-fault assertion.

The optional expanded fast run reported **23 tests / 222 assertions /
11 failures / 6 errors**. A HEAD-only snapshot independently reported
**20 agent tests / 98 assertions / 11 failures / 5 errors**; the extra
overlay error was another bounded wait in `park-wake-test`. The exact
foreign boundary is the existing `seon.cluster.agent-test` consumer
fixtures, including an unresolved process lookup ref
`[:seon.db.process/id "8111-1700000000000"]`, routing/terminal wait
failures, and retired episode observations. It is recorded in the
[consumer issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md).
That suite is not claimed green; no foreign tests or sessions were edited.

The final tests-only overlay against unchanged production at `3e84110bc`
reported **3 tests / 126 assertions / 3 failures / 0 errors**: the
ordinary-wake fault assertion and both concurrent-graph identity
assertions fail. The terminal-refusal test passes even before the fix,
independently falsifying the proposed validator cause for this outcome.

`bin/test --paths src/seon/turn.clj src/seon/cluster/agent.clj
test/seon/loop_proof_test.clj --platform` passed at `3e84110bc`:
**83 tests / 490 assertions / 0 failures / 0 errors**.
Both mandatory gates removed their successful isolated roots.
The scratch operator was downed through `bin/seon --root tmp/loop-live-root
down` (PID 96714, SIGTERM), and its root and lane probe exhaust were removed.
All lane command sessions were reaped. Default received no lifecycle call.
