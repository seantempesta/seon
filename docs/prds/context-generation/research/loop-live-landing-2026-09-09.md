---
type: research
status: active
tags: [research, runtime, agent, test, wave/agent-context]
---

# Live ordinary-turn verification — 2026-09-09

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
