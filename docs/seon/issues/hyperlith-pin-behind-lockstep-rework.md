---
type: issue
status: open
severity: friction
tags: [issue, runtime, wave/upstream-delta]
---

# Hyperlith pin is 23 commits behind the upstream lockstep rework

## Problem

The vendored `reference-code/hyperlith` pin (`b08a8e8`) predates upstream's
architectural rework (inspected via `git fetch` on 2026-08-07; upstream
`origin/master` at `34dbe2e`). Upstream moved to a "lockstep" model:
actions perform no effects and enqueue thunks via a `tx!` that is a member
of the ctx map; one serial batch loop drains the queue each tick (50 ms
default) and applies every thunk inside one write transaction; rendering
all connections in a stable sorted order is implicit in the batch loop;
per-connection backpressure skips frames for slow clients. The http server
moved from http-kit to aleph.

This matters because hyperlith is the live grounding example cited by the
[system-composition report](../../prds/sci-execution-runtime/research/environment-mechanism-system-composition-2026-08-07.md)
and the [seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md):
a stale pin means future readers ground against a superseded architecture.

## Adoptable deltas (evaluate against our own falsifiers, per the sweep cadence)

1. **Tick-coalesced write batching** — N queued mutations → one transaction
   per tick. For Seon: candidate for high-churn NON-receipt write paths
   only; effect receipts keep receipt-before-dispatch durability ordering.
   Measure transact amplification before/after; do not adopt blind.
2. **Deterministic broadcast order** — connections sorted before render
   fan-out. Check against the render pipeline's multed keyframe delivery.
3. **`tx!`-in-ctx** — already absorbed: it is the seon.env design's
   "effects entry as an environment member" confirmation, no action needed.

## Owner

`reference-code/hyperlith` pin (orchestrator-coordinated bump), plus a
follow-up read of the new `start-batch-loop!`/`render-handler` in
`src/hyperlith/core.clj` and `src/hyperlith/impl/datastar.clj` at the new
pin when the render-pipeline owner next touches fan-out.

## Acceptance criteria

- Pin bumped to a reviewed upstream commit and the two grounding docs'
  citations re-verified against it (the cited `ctx-start` merge behavior
  survives the rework — verified during the 2026-08-07 inspection).
- Deltas 1 and 2 each get a measured verdict (adopt/reject with numbers)
  recorded in the active PRD's research directory, or an explicit
  not-applicable note.
