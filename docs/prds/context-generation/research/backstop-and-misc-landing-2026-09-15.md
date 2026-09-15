---
type: research
status: active
tags: [research, turn, test, render]
---

# Backstop and misc landing — 2026-09-15

## Job 1 — admitted turn parts

Read AGENTS.md end to end, the roadmap README and working edge, the named
backstop issue, the turn proc and graph completion await. Applied the
Clojure, REPL, Flow, provider, and canonical-testing skills.

The existing completion observer now receives the work admitted at the provider
and evaluation seams. It uses the resolved attempt timeouts and already chosen
finite retry delays; backup targets contribute their own timeout. Each admitted
form starts its own evaluation allowance. The existing lifecycle allowance
covers permit/stop/settlement progress and is added to admitted work. Neither
arming nor permit acquisition caps this at the evaluation limit. Disarm joins
the same active observer. A fault names provider response, evaluation completion,
turn permit, or proc stop acknowledgement.

Dependency ledger: core.async Flow's transform/stop protocol is at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168`;
SCI's evaluation interrupt seam is `reference-code/sci/doc/interrupt.md`.
First-party admission owners are `seon.turn/provider-targets`, `call-turn`, and
`evaluate-sources`; `seon.ai/delays` already produces the bounded retry schedule.
No second retry calculation or graph lifecycle mechanism was introduced.

### Verification

- MCP JVM observation on default before edits: evaluation 10,000 ms; provider
  timeout 180,000 ms; maximum retries 2; maximum total retry delay 3,000 ms;
  lifecycle completion allowance 600,000 ms. The resolved provider schedule
  was empty because a backup was configured.
- Canonical armed fast loop: `seon.loop-proof-test seon.turn-continue-test`,
  **5 tests / 392 assertions / 0 failures / 0 errors**. The extra continuation
  scenario spends 20 seconds in the simulated external provider with a 10-second
  evaluation limit, then completes its real SCI turn with one provider attempt,
  no fault, and two turns remaining.
- Canonical armed part/disarm regression: **1 test / 9 assertions / 0 failures /
  0 errors**. Two retries yield 90,300 ms; primary plus backup yields 70,000 ms.
  Missing provider and evaluation parts each report their own name at a 200 ms
  bound through the graph's completion await.
- Isolated `bin/test --paths` gate at `dabd311d0` plus the six owned code/test
  paths: **6 tests / 403 assertions / 0 failures / 0 errors**, coordinator 206 s.
- Default's reloaded JVM Vars return provider work **360,000 ms** (primary plus
  backup) and exactly `Agent "probe" run "probe-turn" did not publish provider
  response within 200 ms.`. This is a hot-reloaded-Var proof. Concurrent source
  edits prevented adoption from recording convergence; no full-adoption claim
  is made at this checkpoint. Final publication is checked after job 2.

### Existing verification boundary

`seon.cluster.agent-test` remains red at its existing consumer fixture boundary.
The initial expanded run reproduced parallel counting and the routing fixture's
missing process `8111-1700000000000`; it was terminated after recording that
known boundary. A HEAD-only canonical probe at `dabd311d0` independently reported
**1 test / 3 assertions / 1 failure / 0 errors**, with
`{:settled? true :answered-once? true :ledger-equals-runs? false
  :receipts-unique? true :fences-quiet? true :per-agent-serial? false}`.
No edits to that old suite were retained. Evidence belongs to the existing
[consumer issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md).
The reproducible probe is
[backstop_misc_baseline_2026_09_15.clj](backstop_misc_baseline_2026_09_15.clj).

Another lane held uncommitted install-gate changes in `src/seon/turn.clj` at
entry. Work was prepared at detached HEAD in `tmp/backstop-and-misc-wt`, with
`reference-code` linked; the own-path patch applied cleanly after that lane
landed `747995bf4`. The isolated gate names its earlier HEAD-plus-owned-paths
boundary. Default was never stopped, reforked, or reseeded.

### Exact source/test diff

| Path | Added | Removed |
|---|---:|---:|
| `src/seon/turn.clj` | 55 | 21 |
| `src/seon/cluster/agent.clj` | 1 | 2 |
| `resources/seon/schemas/seon.config.agent.edn` | 1 | 1 |
| `test/seon/loop_proof_test.clj` | 3 | 3 |
| `test/seon/turn_continue_test.clj` | 13 | 2 |
| `test/seon/turn_backstop_test.clj` | 56 | 0 |
