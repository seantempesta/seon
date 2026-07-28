---
type: issue
status: open
severity: friction
tags: [issue, agent-runtime, architecture]
---

# A turn's model work can outlive its own run lease

## Problem

A `:call` turn claims its run with a 60-second lease and then does remote work
whose worst case already exceeds that lease. Failover and backoff make the
worst case strictly worse, but they did not create the gap: one primary call
at the shipped 60-second deadline reaches the lease boundary on its own.

When the lease expires while the turn is still working, another process (or
the same process on a later pass) may take the run over. The turn that is
still running then holds an epoch nobody honours, and its plan transaction
refuses on the CAS fence. Nothing is corrupted — the fence is doing its job —
but a paid model answer is thrown away, which is exactly the outcome the
no-retry ruling is trying to make rare.

## Evidence

- `src/seon/cluster/loop.cljc`, the `:open` and `settle-interruption!`
  transitions: the lease is `(Date. (+ (inst-ms now) 60000))`, a literal.
- `config/default.edn`: `:seon.config.ai/timeout-ms` is `60000` — the model
  deadline alone equals the whole lease.
- Failover adds a second call under the same claim, so the worst case is now
  two deadlines plus the transaction work between them.
- Backoff adds bounded waiting under the same claim. The shipped budget
  (`:seon.config.ai.retry/maximum-total-delay-ms 3000`) was chosen to be small
  relative to the lease precisely because of this issue, and that choice is a
  mitigation, not a fix — it is a constant tuned against another constant.
- `src/seon/cluster/run.cljc` already refuses a held run as `::lease-expired`,
  so the failure is detected; it is the WORK that is lost, not the facts.

## Escalation (2026-07-28 trigger-conservation audit): the loss REPEATS

The consequence is not one discarded answer — it is an unbounded
duplicate-paid-call cycle. `work/next-work` selects `:call` for a run
whose `:seon.cluster.run/process` equals ours WITHOUT checking the
lease (`src/seon/cluster/work.cljc:173-178`), nothing in the loop ever
calls `heartbeat-tx` or re-claims (zero call sites outside
`run.cljc`), and a `::lease-expired` plan refusal leaves the run in
exactly the held-unplanned state that re-derives `:call`. So once the
pass START time is past `lease-until` (starvation behind other agents'
serial turns is enough — the pass's `now` is pinned at pass start, so
only the open→call GAP matters, not in-pass duration), every rewake
pass makes one more PAID model call and one more refused freeze,
forever, until process restart settles the run as an interruption.

REPL-proven at the transition level: `tmp/trigger-conservation-probe.clj`
P2 — `next-work` returns `:call` at lease+30s, `plan-tx` refuses
`::lease-expired`, `next-work` returns the identical `:call` again.
Full analysis:
`docs/prds/sci-execution-runtime/research/trigger-conservation-2026-07-28.md`
(property b violation).

This raises the severity: the fix must make the cycle unconstructible
(custody verified/renewed at `:call` entry, or `next-work` refusing to
derive paid work for an expired holder), not merely rare.

## Why it is filed rather than fixed here

The honest fix is not a bigger number. Under the runtime contract a lease is a
clock standing in for an observable event — "is the holder still alive and
still working?" — and the process record plus a heartbeat can answer that
directly. Either the turn renews its claim as it works (an event the loop
observes), or the lease is derived from the work the turn declared it was
about to do. Both are interface changes to the claim contract, which is N2/N3
owned and out of the failover unit's boundary.

## Acceptance criteria

- A turn that legitimately takes longer than one lease period keeps its claim
  without any caller choosing a timeout, or the claim contract states in one
  place why it may not.
- The 60-second lease literal has one owner and is either derived or a
  provenanced config fact, not a number repeated at two transitions.
- A falsifier drives a turn whose model work exceeds the lease and asserts the
  answer is not discarded.
