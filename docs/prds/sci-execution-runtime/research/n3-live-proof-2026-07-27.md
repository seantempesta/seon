---
type: research
status: active
tags: [prd, research]
---

# N3 live integration proof — phase 1 (2026-07-27)

Script: `scripts/n3-live-drive-2026-07-27.clj` (run: source `.env`,
then `clojure -M:dev -e "(load-file …)"`; fresh root per run because a
cluster branch found in the roster is never re-forked).

## The run that passed (16:37, commit 020966ea4, gate 166/731/0)

- boot → tower up (8 dials from facts): **1.28 s**
- trigger → run opened + claimed: **0.65 s**
- trigger → plan frozen (real DeepSeek reply, 4 forms): **2.68 s**
- fold: 4/4 receipts `:done` — `(in-ns …)` contained by the per-run
  fork, the `defn` landing in `my.agents.alice`, a `println` whose
  output rides the receipt, and `(my.run/complete "55")`
- trigger → run closed by the disposition: **2.41 s**; faults nil;
  teardown clean.

## What the five rounds bought (each failure a finding)

1. **Fixture-vs-live-boot class**: message/eval/form/agent families
   had no entity maps → not installable live (fixtures masked it).
   Fixed 38ab48470 + the non-vacuous class-killer; it then caught
   `:seon.cluster.run/error` within the hour (020966ea4).
2. **Roster semantics**: an existing cluster branch is found, never
   re-forked — drives need a fresh root (or a retire) after source
   changes the ancestor digest.
3. **Invisible no-credential stall**: the model-call error died with
   the turn. Now `:seon.cluster.run/error` closes the run in the same
   transaction and the next prompt reads it back.
4. **Base-ctx gaps**: evaluation now happens IN `my.agents.<id>` by
   construction (one derivation shared with the prompt); sci `*out*`/
   `*err*` bind to a bounded writer captured as
   `:seon.cluster.eval/output` receipt evidence.

## Owed next (phase 2)

The kill -9 half: crash a JVM mid-turn, reboot, prove the run reads
`:interrupted` and the next prompt carries the derived warning —
nothing re-executes. Then the flow-graph `step` drive (live graph, the
one uncovered function) rides the same session.

## Phase 2 — the kill -9 drill (COMPLETE, same day)

Scripts: `scripts/n3-crash-{child,verify}-2026-07-27.clj`; orchestration:
child in background → await TRIGGERED → sleep 1.2s → `kill -9` → verify.
The verify only WATCHES production wiring (commit a6d426983 removed
every hand-performed step).

- Child murdered at +1.2s holding a CLAIMED run, no plan (mid-model-call).
- Reboot: `start!` reported `{:recovered-runs 1, :recovery-operations 2}`
  — dead custody released BY FACT (the lease was still ~50s in the
  future), milliseconds after boot.
- Wreckage exact: run open, no plan, no receipts; agent busy; trigger
  reads answered (the run IS the answer); a survivor's direct close
  refuses `::not-the-holder`.
- The LOOP settled the orphan itself (claim-then-close through the
  ordinary transitions) and drove a NEW run to completion — one real
  DeepSeek call — within **3.1s of reboot**. Receipts exist only for
  the new run; the crashed run closed and was never re-planned; the
  interrupted warning was derivably in the new run's prompt (the
  shadowing fix ba723b2d1 held).

Interrupted+adapt is proven over real facts: nothing re-executes,
nothing retries, one derived warning, the agent adapts.
