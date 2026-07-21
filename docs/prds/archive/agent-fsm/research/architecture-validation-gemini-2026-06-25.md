---
type: research
status: active
tags: [research, agent, flow, database]
---

# Architecture Validation (Gemini, 2026-06-25)

Gemini read the architecture + spec docs AND the live source (`loop.cljs`,
`turn.cljs`, `ctx.cljs`, `db.cljs`, `wire.cljs`, `eval.cljs`, `render/sci.cljs`,
`client.cljs`, `web/serve` + `inspector`) and produced a critical validation.
Ten findings below, with the file it cites and the fix direction. These feed
`architecture.md`'s open-risks list.

## Run model

1. **Paused vs. absolute deadline** (`agent-runtime-spec.md:55,68`) — `deadline`
   is an absolute `:inst`, so pausing doesn't stop the clock; an agent paused
   past its deadline is insta-killed `:deadline-exceeded` on resume. **Fix:** on
   pause, store relative `remaining-ms` and re-extend the absolute deadline on
   resume.
2. **Fencing bypass in eval writes** (`db.cljs:376`) — `owns-run?` guards
   `close-run!`/`renew!`/`beat!`, but **arbitrary `db/transact!` from agent
   `eval-batch!` code does not.** A superseded/slow worker can keep writing after
   a new run owns the agent → corruption. **Fix:** reject writes whose tx-meta
   `run-id`/`turn-id` is no longer the agent's active run (at `transact!` or the
   wire-server). [Pairs with #9.]
3. **Wake/ticker race → orphaned open run** (`loop.cljs:347`) — the
   `setTimeout 0` in `wake-handler` + the ticker can both read `:idle` before
   either commits → two `run` entities; the agent pointer wins last, the other
   stays `:open` with no driver. **Fix:** make idle→running + run-creation ONE
   tx that asserts state was `:idle` (optimistic lock).
4. **Crash recovery / FSM desync** (`client.cljs:1932`) — a process crash
   mid-turn leaves `state :running` + run `:open`; boot only arms wake triggers,
   so the agent ignores all messages (not `:idle`) → deadlock (permanent if the
   run had no deadline). **Fix:** boot scans `:running` agents → reset `:idle`,
   close their runs `:crashed`. (This IS the "watchdog restart to known-good
   state" the design promises — it must exist on boot.)

## Integration

5. **Prompt render blocks the main thread** (`turn.cljs:415`) — `render-context`
   (scans namespaces, reads files, string-templates) runs sync on the main
   thread before offloading eval; a large context stalls the event loop → SSE
   unresponsive. **Fix:** offload the prompt render to a worker too (not just
   `eval-batch!`).
6. **Inspector first-paint blocking** (`inspector.cljs:257`) — the "fallback to
   `render-context`" for a not-yet-rendered agent runs on the main thread during
   an HTTP request → same stall. **Fix:** offload, or seed a rendered turn at
   `bootstrap-turn!`.

## DB-as-bus

7. **Render-blob history bloat** (`agent-runtime-spec.md:279`) — writing the full
   twin (hiccup+text) + metadata + heartbeat every turn bloats the bitemporal
   index and hammers the write ceiling (~324–1040 ent/s). **Fix:** store renders
   in a content-addressed blob store (hash in the DB), not inline datoms; treat
   as ephemeral where possible.
8. **Listener pump blocking** (`wire.cljs:357`) — the tx-feed pump fires native
   listeners sequentially+synchronously; one slow listener (e.g. a complex
   inspector layout) halts the pump → delays wake signals for ALL agents.
   **Fix:** dispatch listener callbacks async (`setTimeout 0`/microtask).
9. **Reconnection data loss** (`wire.cljs:370`) — UDS reconnect (2s) doesn't
   replay missed txs; messages committed during the gap are lost → an agent with
   unread messages silently stays `:idle`. **Fix:** `subscribe-tx` takes a
   `since-t` basis to replay the gap.

## Isolation / eval offload

10a. **Re-bootstrap respawn-storm DoS** (`worker-threads-spike:91`) — a worker
   terminated mid-runaway loses its warm compile-state; a buggy loop that keeps
   timing out makes workers spend all their time re-bootstrapping (`init-bootstrap!`
   + `replay-program-graph!`) → DoS for other agents. **Fix:** a bootstrap-failure
   breaker (already in the pool patterns); consider incremental warm spares /
   serialized compile-state.

10b. **In-flight write split-brain on terminate** (`worker-threads-spike:251`) —
   if agent code calls `db/transact!` mid-eval and the watchdog terminates the
   worker while that write is in-flight, the wire-server commits it but the turn
   is marked failed → DB mutated, FSM says failed → duplicate/inconsistent on
   restart. **Fix:** buffer worker writes and have the MAIN thread commit them
   atomically only after the worker returns successfully (also closes #2). This
   is the single most important structural fix.

## Minor

11. **Dead-letter accumulation** (`agent-runtime-spec.md:124`) — no clear/ack for
   hop-exhausted warnings → they pile up in the warnings section, wasting
   context. **Fix:** `clear-warnings!` or auto-age-out old dead-letters.
