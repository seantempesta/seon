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

## The loop (each iteration)

1. **Pick the next target** from the de-risk order below (smallest shippable unit).
2. **Build real code** for it (in-place, no `*-v2`; CLAUDE.md "don't be a dumbass").
3. **Write/append tests** that pin the BEHAVIOR (not exact strings) — re-runnable.
4. **Run** the affected tests; then **confirm LIVE** (eval against the running pod /
   REPL — a datom read back, a render, a kill observed) — live proof, not inference.
5. **Commit** the unit (message says what was validated + the live proof).
6. **Update `architecture.md`/spec to match built reality** (it's a living doc — fix
   any drift between the map and the territory the moment it appears).
7. **Adversarially verify** (ultracode): fan out agents to find bugs / refute the
   claim / test edge cases before believing it's done.

Cadence: full `bin/test-cljs` ONCE per unit at the natural checkpoint (not per edit;
concurrent runs collide on `out/test/`). Fresh world via `bin/seon nuke --yes` when
state must be pristine.

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
