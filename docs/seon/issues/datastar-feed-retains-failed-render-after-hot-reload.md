---
type: issue
status: open
severity: high
tags: [issue, web, flow]
---

# Invalidate a cached failed Datastar render after its owner reloads

## Problem

An agent feed that cached a transcript error continued serving the old complete
event after `seon.agent.ctx.transcript` hot-reloaded with a working correction.
The corrected transcript acquisition succeeded directly against the same
immutable database value, and a relevant eval transaction committed afterward,
but a newly connected `/agent/{id}/feed` still received the cached error event.

## Evidence

On 2026-07-18, a generated agent history containing 50 turns and 400
maximum-sized eval projections reproduced `datahike result-weight budget
exceeded`. After deleting the redundant full-history current-namespace query,
an instrumented `transcript-block` completed with 57 bounded database calls and
zero failed members. The feed nevertheless returned its earlier
`transcript render failed` event both immediately after hot reload and after a
transaction changed `:seon.eval/narration` on one retained eval.

## Owner

`seon.web.datastar` owns the one feed subscription registry and its cached
complete event. Strengthen that mechanism; do not add a second feed or renderer.

## Acceptance

- A render that previously returned an error is recomputed after its owning
  namespace reloads.
- A relevant committed transaction also replaces the cached error event.
- Successful complete-event reuse remains bounded and unchanged when neither
  code nor observed database attributes changed.
- A real agent feed transitions from an induced error card to healthy content
  without restarting the Bun pod or reloading the browser page.
