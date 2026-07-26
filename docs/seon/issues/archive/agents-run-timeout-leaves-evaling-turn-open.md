---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, web]
---

# Close the active turn when an agents-run timeout supersedes its run

## Problem

When `POST /agents/run` reached its request timeout during a healthy
multi-turn run, the run closed as `:superseded` and released run-holding process/current
run custody, but its active turn remained durably
`:seon.agent.turn/status :running` and
`:seon.agent.turn/phase :evaling`.

The agent is no longer wedged behind a retained lease, but the database now
contains an orphaned nonterminal turn whose run is terminal. This violates the
nothing-wedges contract that every opened turn reaches a visible terminal
state.

## Evidence

The default-cluster drive7 live drive on 2026-07-24 used agent
`slick-dryers-brake` and a request timeout of 900,000 ms.

- Run entity `8038`, ID `psqzapw9ha40`, opened normally.
- Eight consecutive DeepSeek turns reached provider `:success`, evaled one
  schema registration each, persisted terminal `:seon.eval/status :done`
  receipts with `:seon.eval/ok? true`, and published `:done` turns.
- The final turn `amxh80vcpebv` persisted terminal eval receipt
  `fhof36g97du6` for
  `(seon.schema/register! :my.drive7.energy/checksum :int)`.
- At the request deadline, `/agents/run` returned
  `timed_out=true`, `closed_reason="timeout"`, eight turns, and eight evals.
- Transaction `536874097` retracts the run's `:open` status, asserts
  `:closed`, asserts `:seon.agent.run/closed-reason :superseded`, retracts
  process custody, and retracts the agent's current-run ref.
- The current database value at transaction `536874098` (commit ID
  `6a632d80-3617-52b1-ba7e-3bcd802d3169`) has run
  `psqzapw9ha40` closed with
  `:seon.agent.run/closed-reason :superseded`. The agent has no
  `:seon.agent/run` ref and the run has no run-holding process.
- In that same current value, turn `amxh80vcpebv` remains
  `:seon.agent.turn/status :running` and
  `:seon.agent.turn/phase :evaling`. It has no terminal error and never
  reached `:evaled` or `:published`, even though its eval receipt is terminal
  and successful.
- Transaction `536874098` also persists core-fault entity `8089` with
  message
  `:db.fn/cas failed on datom [7966 :seon.agent/run nil], expected 8038
  {:error :transact/cas, :old nil, :expected 8038, :new 8038}`. Its recorded
  basis transaction is `536874097`. The timeout path therefore races a second
  current-run CAS after custody has already been retracted.

The exact lifecycle, attempt, and eval datoms are retained in
`tmp/orchestrator/drive7-gate.log`.

## Resolution

Commit `f6dd94682` makes observation-timeout settlement one fenced database
transition. It begins with the agent current-run CAS, run claim-epoch CAS, and
active turn phase CAS; then it publishes the turn as `:interrupted`, closes the
run as `:superseded`, and retracts run-holding process and current-run custody together.
There is no web-only run close.

The portable driver also refreshes the minimal durable run authority after a
late phase write loses its fence. A run-holding process displaced by the timeout therefore
observes the already-closed run and does not attempt a second stale settlement
CAS or record a core fault.

Focused proof covers the exact active-turn timeout transaction and the
late-run-holding process refresh path: the writer selection is 7 tests / 39 assertions and
the CLJS selection is 14 tests / 64 assertions; the artifact-backed exact
selections are 2 tests / 14 assertions on the writer and 2 / 11 in CLJS.

The later isolated `planschema` run `q5ddb6i4pp4z` supplied an independent live
terminality check after six successful evals and one separate planner refusal:
all seven turns are `:published` and terminal, the run is closed, and both the
agent current-run and run run-holding process queries are empty. Evidence is in
`tmp/orchestrator/planschema-gate.log`.

## Owner

The `/agents/run` request-settlement timeout and run-supersession owner must
terminalize the active turn using the same epoch/phase-fenced lifecycle
transition that ordinary completion and visible phase errors use. Run close,
turn close/publication, run-holding process release, and current-run retraction must form
one consistent transition; no web-only cleanup path may close only the run.

## Acceptance

- A focused test times out `/agents/run` while its active turn has a terminal
  successful eval receipt but has not yet published.
- The resulting transaction leaves the run closed, the active turn terminal
  and published with an explicit timeout/supersession outcome, and retracts
  both run-holding process and agent current-run custody.
- The settlement path performs no stale second CAS after the terminal
  transition and persists no core fault.
- No current or history query can observe a terminal run with a
  `:running` turn after the timeout transition.
- The artifact-backed timeout regression leaves every turn terminal, releases
  custody, and records no stale-CAS core fault.
- A rebuilt live multi-turn run independently leaves no running turn or
  retained custody after settlement.
