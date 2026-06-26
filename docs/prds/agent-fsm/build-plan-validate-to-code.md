---
type: prd
status: active
tags: [prd, agent, flow]
---

# Build Plan — Validate-to-Code (the overnight experiment)

The design is complete and committed (`architecture.md` + `agent-runtime-spec.md`
+ 10 research docs). This is the playbook for turning it into **tested, live-proven
code** — the living document taken to the extreme: every design assertion becomes a
passing test + a live proof, or it gets revised. No guessing.

## Dual-track (CLJ + CLJS) — this revives the JVM

Not a CLJS-only effort. The JVM is revived as **the server**, the convergence we
always meant. Build across three lanes:

- **CLJ (JVM server):** wire-writer, web renderer/serve (datastar/SSE/hash-gate/
  `/call`), Integrant lifecycle, logging, heavy processing — *data only*. Much of
  `seon.web` STAYS; wire it to route execution to the agents.
- **CLJS (agents):** the data-driven loop, run model, sandboxed-exec service
  (eval / render-fn / interaction execution → returns data).
- **`.cljc` (shared):** schema/data-model, `derive-state`, the transition table —
  pure data + fns both tracks consume.

They meet at the wire. The web/render is **not** "ported to CLJS" — it's revived on
the JVM and wired to delegate untrusted EXECUTION to the CLJS sandboxes.

## The loop (each iteration — the agent's judgment, not a fixed recipe)

Each pass, decide what **most de-risks or improves the design right now**. The
move is NOT always "write a test." The candidates — pick the one that helps most:

- **Refine** — make a concept clearer, simpler, more composable.
- **Repair** — fix something wrong (including a design assumption that didn't
  survive contact with code).
- **Rebuild** — once you learn a better way, *overwrite* the old one. Don't be
  precious: in-place, no `*-v2`. A clean rewrite beats a patched mess.
- **Optimize** — when a measure (latency, write-churn, memory) says to.
- **Test / prove** — lock in a behavior or de-risk an assumption with a
  re-runnable test + a LIVE proof (eval the running system; observe it).
- **Lock in an idea** — when something's settled, encode it so it can't drift (a
  test, a schema, a clean abstraction).

Then: **confirm live** (observed, not inferred); **commit at a clean point**
(working + coherent); and if a pass didn't actually make things better, **revert
it** — progress only counts when it's real. Update the design doc to match reality
the moment they diverge. Adversarially verify (ultracode workflows) before
believing something is done.

**North star:** each pass should leave the design *simpler and more composable*;
concepts should come to feel *natural and universal* — one mechanism serving many
uses, not bolted-on special cases. If a piece feels special-cased or awkward,
*that* is the next thing to refine. **Friction is the signal.**

Cadence: full `bin/test-cljs` ONCE per checkpoint (not per edit; concurrent runs
collide on `out/test/`). Fresh world via `bin/seon nuke --yes` when state must be
pristine.

## De-risk order (each item ends in passing tests + a live proof)

1. **Phase 1 — the data-driven loop** (mechanism-agnostic, single-process):
   - `seon.agent.run` entity + lifecycle; `seon.agent.turn/run` ref (was `wake`);
     drop `:seon.agent/sessions` (runs link back via `:seon.agent.run/agent`).
   - **Derived state** (Malli: spec the `derive-state` `:=>` fn, enum as a return
     shape, no phantom attr — `research/malli-derived-state`): `:idle/:running/
     :paused/:terminated` from `terminated-at` / open-run / `paused-at`.
   - **Transition-table FSM** (`{state {event → mutation}}`) + the `transition` fn;
     `run-loop!` = fold over events derived from run data.
   - **Two bounds**: `turn-limit` DERIVED (`default-turn-limit` + inbound-count;
     stored override only on explicit bump) + `deadline`; the one **ticker** (cron
     fire + deadline watchdog).
   - **cron-as-data**: `:seon.agent/schedules` vector of `{cron, fn}` maps.
   - **`state-snapshot` fingerprint** fn (validate the whole agent in one call).
   - Renames (atomic, fresh world): `:active→:running`; `wake→run`; `ctx→sections`;
     `max-turns-per-loop→default-turn-limit`; drop `wait-note`.
   - **Live proofs**: a wake opens a run → `:running`; budget/deadline closes →
     `:idle`; a cron fires a run; the snapshot reads correct; transitions are data.

2. **Correctness fixes** (each a test + a live proof — from the Gemini validation):
   - **Buffer worker writes, commit on main atomically** (keystone — closes
     fencing-bypass + in-flight split-brain).
   - **Crash-recovery on boot**: boot resets `:running` agents → `:idle`, closes
     orphaned runs `:crashed`.
   - **Atomic wake**: idle→running + run-create as ONE tx asserting prior `:idle`
     (no double-run from message+cron).
   - **Pause vs. absolute deadline**: pause stores `remaining-ms`, resume re-extends.
   - **Async listener dispatch** in the tx-feed pump; **reconnect `since-t` replay**.

3. **Phase 2 — Tier-1 worker isolation**: offload SCI `eval-batch!` to a warm pool
   (`min 4/max 8`, `concurrentTasksPerWorker 1`); terminate-on-deadline; recycle =
   terminate + re-read DB; bootstrap-failure breaker. **Live proof**: a sync
   `(while true)` eval is killed, the main loop keeps serving (the spike already
   proved the primitive; now wire it into `eval-batch!` and test it end to end).

4. **Single render path** (derived, never stored): one `render-context` fn (ai+html
   twin, inputs from db); web renderer derives → fast-hash → push-only-when-changed;
   **prompt==view byte-identity test**; migrate tests to assert via the one fn
   (#9 — kill the inspector-path/ctx-sections divergence). Live proof: live-tile +
   your-entity render in the real prompt; a broken tile → clean fallback.

5. **Interactivity**: port the render-time rewrite (`reactive/transform`) + the
   `/call` route + **namespace-as-route into the owning agent's sandbox** to the pod
   (replace the JVM `seon.*` prefix-whitelist). Live proof: an agent-authored button
   round-trips through `/call` → sandbox → transact → reactive push.

6. **Feeds**: one-SSE-per-feed + a feed registry; one feed crashing = one dead tile.

7. **Tune by measuring** (no guessing): heartbeat cadence, `default-deadline-ms`.

## Claims to validate-to-code (the "no guessing" list)

Each must become a passing, re-runnable test:
- derived state == the four primitives' projection (every combination).
- atomic wake never produces two open runs (concurrent message+cron).
- fencing: a write tagged with a superseded run-id is rejected.
- terminate() kills a sync runaway; the main loop stays responsive.
- crash-recovery: a forced `:running`+open-run boot state resets to `:idle`.
- pause past deadline → resume does NOT insta-kill.
- prompt bytes == inspector left-pane bytes (same fn, same db value).
- a section/tile that throws → clean fallback, never `⚠`/malli code.
- a `/call` with a non-granted fn is refused (capability surface).
- reconnect after a UDS drop replays missed wakes (no silent `:idle`).

## Done

A piece is done when: implemented in-place, its tests pass, a LIVE proof was
observed, the open-risk it closed is struck from `architecture.md`, and the doc
matches reality. The experiment as a whole is done when the de-risk list is green
and the architecture's claims are all backed by re-runnable tests.

## Constraints (CLAUDE.md + session gotchas)

- Main tree (single agent); **commit after each unit**; opus for code, never haiku.
- ONE live cluster → serialize wire-server restarts; fresh world = `bin/seon nuke`.
- Concurrent `bin/test-cljs` collide → ONE consolidating run per checkpoint.
- Pure fns of the db; namespaced keys; Malli on every public fn; report code smells.
- Adversarially verify before believing "done"; honesty > impressiveness.
