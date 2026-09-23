---
type: landing
status: partial — stop interrupts the in-flight turn (landed); MCP blob-only is an exact patch for the cluster.clj holder
created: 2026-09-23
tags: [agent-platform, stop, flow, mcp, blob, seconds-not-minutes]
---

# Lane mcp-and-stop (2026-09-23)

Schedule #19 (MCP results blob-only) and the `bin/seon stop` hang.
Proof root: `tmp/mcp-stop-root`, booted from a `git archive` of HEAD `b1e9b5918`
(`tmp/mcp-stop-src`, `reference-code`, `target`, `.cpcache` and the kondo cache
symlinked) with this lane's two files copied in. The working tree does not boot:
another lane's uncommitted `src/seon/render/web.clj` hunk declares a bare
`:vector` return schema (`[:=> [:cat :map :seon.db/database-value] :vector]`),
which refuses publication with `:malli.core/child-error` in
`seon.fn/add-contract-facts` (`tmp/mcp-stop-evidence/boot-1.log`).

## (2) Stop interrupts the in-flight turn — LANDED (agent.clj)

- `seon.cluster.agent/turn-step` is the agent graph's turn proc Var: it publishes
  the transform's thread in the handle's `:seon.agent/turn-thread`
  (`AtomicReference`) for one transform, and on exit clears it and the
  thread's interrupt status under the holder's monitor.
- `disarm!`: `flow/stop` → `interrupt-turn!` (only a thread inside a transform)
  → `await-turn-completion!` bounded by the declared disarm dial
  `:seon.config.agent/turn-completion-backstop-ms` (joins an active turn bound
  that fires first; no longer adds the interrupted work's allowance) →
  `cancel-turn-backstop!` → `record-interruption!` (the boot writer function
  `seon.turn/recover-tx`; no-op when the turn closed itself) → cleanup.
- Why not interrupt every proc thread (first attempt): an interrupted `alts!!`
  leaves its handler registered (`clojure/core/async.clj:356` deref of the
  promise), so the stop command that arrives later is consumed by the dead
  handler and the proc never exits. Observed: scratch stop hung, every agent
  proc parked at `flow/impl.clj:295` (`tmp/mcp-stop-evidence/stop-hang.json`).

Evidence:

| probe | result |
|---|---|
| live root turn blocked in (stubbed) provider, `bin/seon --root tmp/mcp-stop-root stop` (first version) | **920 ms** wall, exit 0, `:process-exit? true`; turn `958adc16c4b1` closed at the stop instant by a 2-datom close tx (recover-call); next boot `:seon.boot/recovered-runs 0` |
| live root turn in its CPU-bound prelude, final version | **16,921 ms**, exit 0; turn `abbc66b94499` closed at stop by the 2-datom close; next boot `recovered-runs 0` |
| same, second probe | client bound 30,137 ms fired, JVM exited ~40 s later — prelude ignores the interrupt (see issue) |
| `bin/test-check` run `eeac7adde609` (disarm-interrupts…, loud backstop, withheld stop transition), executor-tracking version | passed, 27 assertions, 38,174 ms wall |
| run `b9a2a47f0d6e` same members | behavior green, 2 duration fails 7,431 / 7,898 ms over 5 s → measured `:seon.test/long` declared |
| run `bba05ce63419` after the `turn-step` change | 3 members **reused**, not executed — see limits |

Timing phases of the fixture regression (marks): arm 1,495 ms, trigger→prelude
5,400 ms, interrupted disarm 1,405 ms.

## (1) MCP results blob-only — NOT LANDED (owner path held)

The writer and the `get_value` reader are `seon.cluster/mcp-project` and
`seon.cluster/mcp-get-value` in `src/seon/cluster.clj` (held by lane
publication-work), not under `script/seon/dev/`. Exact change:
`tmp/mcp-and-stop/cluster-mcp-blob-only.patch` (replace the stage +
`with-publication!` + `:seon.dev.mcp.artifact/*` transaction with
`blob/put!`; `mcp-get-value` reads `blob/get` by digest; a missing digest
returns the declared `:seon.dev.mcp/value-not-found` refusal naming GC
collection). Follow-ups for their owners: retire
`resources/seon/schemas/seon.dev.mcp.artifact.edn` (no writer survives the
patch), assert zero basis advance in `test/seon/cluster/mcp_test.clj`.

Proof in the scratch JVM (patched defns loaded from
`tmp/mcp-and-stop/patched-forms.clj`):

| probe | result |
|---|---|
| HEAD: read-only `(vec (range 5000))` | max-tx 536871004 → 536871005 (+1 tx), 1 artifact row |
| patched: read-only `(vec (range 6000))` | 5 ms; max-tx 536871005 → 536871005, **delta 0**; digest `4aca5c65…`, 310,968 B, retrievable |
| `get_value` offset 5990 | 34 ms, window `[5990 … 5997]` of 6000 |
| `get_value` unknown digest | typed `:seon.dev.mcp/value-not-found` with the collected/never-stored message |

A GC sweep collecting the unreferenced blob was not exercised.

## TIMINGS (over 1 s)

| operation | wall ms | note |
|---|---|---|
| scratch start, working tree | 24,426 | refused (web.clj hunk) |
| scratch start, snapshot (from zero) | 107,150 | ready 90,771; dependency classes miss `:pins-unavailable` |
| scratch starts after stop | 66,260 / 66,700 | ready 27,327 / 29,131 |
| scratch start after agent.clj change | 175,820 | ready 141,828 |
| `init --changed` one test file | 10,701 | publication |
| `bin/test-check --ns seon.cluster.agent-test` | 129,683 | 11 errors at HEAD: fixtures lack `:seon.agent/branch` |
| focused test-check runs | 16,157–47,345 | runner overhead ≈ 22 s per request |
| `bin/test-check --policy all --test …` | 204,380 | ran more than the named members; client bound fired |
| root message → provider call | 57,145 | issue below |
| stop during prelude | 16,921 / ~70,000 | issue below |

Every row over 10 s is a defect. New issue:
[a-turn-spends-seconds-before-its-provider-call](../../../seon/issues/a-turn-spends-seconds-before-its-provider-call.md).

## Limits and findings

- The final `turn-step` version's regressions were reused (run `bba05ce63419`)
  rather than executed, although `disarm!` changed — a selection-reuse
  question for `seon.test` owners. Its live stops (above) exercise it.
- Remaining `seon.cluster.agent-test` members fail at HEAD because fixture
  agent rows lack the now-required `:seon.agent/branch`; only the disarm
  members were converted (`created-agent-tx`, `arm-in-cluster!`).
- `seon.turn/phase` converts the stop's `InterruptedException` into a
  phase-failed value (turn.clj, not owned); `runtime_status` on the scratch
  failed with "count not supported on this type: Date"
  (`seon.cluster/mcp-runtime-observation`, cluster.clj:629).
- The docstring of `seon.cluster/disarm-agents!` still says orderly stop waits
  for the active pass (cluster.clj, not owned).
- After a scratch reboot the root re-answered a probe message with the
  configured `deepseek-flash` provider before the stub was reinstalled (turn
  `8c7e6131a04c`, one attempt, error recorded): possibly one paid call.
- No change to `script/seon/dev/mcp.clj`; C1's hunk was not received.
- RESET NEEDED: no. `default` untouched. Scratch root stopped.
