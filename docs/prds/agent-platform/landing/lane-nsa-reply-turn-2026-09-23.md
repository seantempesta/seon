---
type: landing
status: UNPROVEN on default: proof owed after the checkout restart. Premise refuted; one O(store) scan removed from non-defining evaluations; loop regression still RED on opening latency
lane: nsa-reply-turn
plan: docs/prds/agent-platform/plan/README.md §4 cut 3 (namespace-agent loop); follows lane-nsa-unblock-2026-09-23.md
issue: docs/seon/issues/issue-worker-opening-turn-closes-and-no-reply-turn-opens.md
created: 2026-09-23
---

# nsa-reply-turn: the reply turn does open — the opening is too slow

## What the trace showed (default pid 90963, named runs of seon.namespace-agent-loop-test)

Temporary diagnostics in the test captured, at the 20 s reply-wait timeout, the
worker's turns, `next-agent-work`, `clojure.core.async.flow/ping` of the worker's
graph, the routing map, and a 250 ms sampler of (evaluations, turns,
`next-agent-work`, the turn thread's Seon frames). The diagnostics were removed
before commit; the test file is unchanged.

- **Wakes are routed on C.** `(keys (agent/channels routing))` = `(49226)` = the
  worker's eid on C's connection. No H-id collision.
- **The turn proc steps.** Run `7bef96675a7b`: mailbox deliveries 10, the turn proc
  was mid-pass (absent from ping). Run `07cebab688d7`: mailbox 9 deliveries, turn
  proc 9 passes — prime + 8 generate passes + the close pass.
- **The opening is not counted as answering.** `latest-answering-turn-t` 0 is
  correct (a generated opening freezes no reply-size); `next-agent-work` answers
  `:open` through the issue arm (turns-left 3). No continuation rule parks it.
- **Sampler, run `8cba7ecf4c15`:** the opening's 8 forms took the whole 20 s,
  one generate+resume pass per form (≈2.3 s each). At ≈20 s the next pass had
  started `:open` (`seon.turn/system-plan` frames). The reply turn opens; it opens
  after the test stopped waiting.

So the defect is latency, not routing: every opening form costs seconds.

## Where the per-form time goes (armed profile cells, this JVM)

- `seon.turn/generate-turn` mean 1.9–2.1 s per pass; `settle-batch!` 0.7–0.8 s;
  `declared-sources` 0.3–0.7 s (the whole system plan re-rendered every pass to take
  its nth form: O(forms²) renders per opening).
- `seon.sci.eval/install-evaluated-rows!` 0.7 s mean, called after EVERY settled
  batch, including batches that installed nothing. With no installations,
  `(every? :installed [])` is true, so `installation-covers-program-change?` runs.
  Its `[:find [?entity ...] :where [?entity]]` over `(since (history after) t)` scans
  the store, not the change window.
- **Probe** (JVM, default's store, 513,000 datoms, `before` = `after`, no
  installations): `(installation-covers-program-change? d d [] p)` → `true` in
  **5,617 ms**; the `[?entity]` since-scan alone → 0 entities in **1,398 ms**.

## Fix (src/seon/turn.clj, resume-turn)

`resume-turn` builds the installations vector once and calls
`sci.eval/install-evaluated-rows!` only when it is non-empty. With nothing
installed, the call's only effect was advancing the program snapshot's db; skipping
it is conservative — `acquired-database?` decides by program revisions
(`sci/eval.clj:2532`), and a later defining batch compares from the older base,
which still passes every non-program change. +19/−16 lines, no new mechanism.

Hot path, same probe on parent and self: a non-defining settled batch paid
5,617 ms (default's store) / ≈0.7–0.9 s (the test's branch) on the parent; on this
commit it does not enter the check (0 ms). A defining batch is unchanged.

## Still red, and why

The per-form cost that remains (settle transaction ≈0.7 s, declared-sources
recomputed per pass, the evaluation) keeps the 8-form opening well above the
test's 5 s bound and near its 20 s wait. The owner fixes, outside this lane's
paths:

1. `src/seon/sci/eval.clj` `installation-covers-program-change?` (held by lane
   retained-ctx-loaded): derive `touched`/`entities`/`changed` from the change
   window (Datahike's tx range for `(basis-t before)`..`(basis-t after)`), not a
   `[?entity]` scan of a SinceDB. A defining batch still pays the store scan.
2. `seon.turn/generate-turn`: freeze the opening's declared plan once instead of
   re-deriving the whole plan per appended form (O(forms²) → O(forms)); a design
   change beyond this lane's 30-line bound.

## Verification boundary

- Named pair after the fix: NOT obtained. The first request refused
  "Java heap space" (default at 10.4/10.5 GB, 10.1 GB live after a GC); the second
  timed out at the prepl bound (150 s), after which pid 90963 was gone. The default
  RSS defect is already queued (fix-schedule 05:55Z, "Default RSS 12.3 GB").
- Incremental run for `seon.turn/resume-turn`: NOT obtained, same cause.
- The fix was adopted (`bin/seon init --dev default --changed src/seon/turn.clj …`,
  1,412 ms) and one named run on it (`0a209f26a818`) did not exit within its
  remainder while the JVM was loaded (another lane's `populate-source!` 122 s);
  its evidence is timing under load, not a verdict.

## TIMINGS (operations over 1 s)

| Operation | Wall ms | Proportional to / verdict |
|---|---:|---|
| `bin/seon init --dev default --changed` (test only) | 8,415 / 4,696 / 22,389 / 30,353 | whole-program `refresh-source!`; defect over 10 s, save-gate lane |
| `bin/seon init --dev` (turn.clj + test) | 92,040 then 6,538 | same; the 92 s one ran beside another lane's index! |
| named loop test (diagnostic runs) | 38,428 / 41,154 / 54,707 / 77,869 | 20 s reply wait after the opening latency above; defect, this note |
| named loop test on the fix | 129,412 | did not exit under JVM load; defect, reported |
| named pair request | 150,120 (prepl bound) | JVM heap exhaustion; defect, queued RSS item |
| probe `installation-covers-program-change?` | 5,617 | the store (513k datoms); the defect removed above for non-defining batches |
| my probe `(count (seon.db/datoms d :eavt))` | 23,698 | the store; my mistake — a full-index count in a probe |

## RESET NEEDED

Default's JVM (pid 90963) exited during the second named request after heap
exhaustion; whatever restarts it is the orchestrator's. Re-run on the new JVM:
`bin/test-check default --policy named --ns seon.namespace-agent-loop-test --ns seon.cluster.agent-arming-test`
and `--policy incremental --changed seon.turn/resume-turn`.
