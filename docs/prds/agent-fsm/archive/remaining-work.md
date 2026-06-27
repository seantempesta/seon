---
type: prd
status: active
tags: [prd, agent, flow]
---

# Agent-FSM — status & remaining work (2026-06-26)

The forward plan after the overnight validate-to-code build. Per-pass history
with live proofs: `night-loop-log.md`. Current-state design: `architecture.md`.
Schema / run-model / FSM: `agent-runtime-spec.md`. Revert point: `c84e8fc`.

## Done + live-proven (`feature/agent-fsm`, 12 commits)

The entire core agent runtime, turned into tested + live-proven code:

- **run-model loop** — run entity, transition-FSM, DERIVED state, two bounds
  (turn-limit + deadline), the one ticker (deadline watchdog + cron firing).
- **correctness cluster** — crash-recovery on boot, atomic-wake (datahike CAS),
  async tx-feed listeners, pause/resume with banked `remaining-ms`.
- **single render path** — prompt == inspector view, byte-identical by
  construction; renders derived-never-stored.
- **interactivity** — `/call` + namespace-as-route into the owning sandbox +
  capability gate (granted `:seon.fn` only).
- **+ a caught-and-fixed `/call` RCE** (adversarial review), and a **test-runner
  false-green fix** (the runner now fails loudly on truncation + tail-retries).
- **gym live-drive harness** rewritten to the run model.

(Full detail + the live proofs for each: `night-loop-log.md`.)

## In flight (finishing up)

- **Agent/loop cleanup** (delegated, running): kill the `seon.agent.fsm` ns
  (`transitions`/`transition` → `seon.agent.loop`; `derive-state` → `seon.agent`),
  rename `state-snapshot` → `derive-status` (it's a derived READ, not a stored
  snapshot), and re-add the **zero-forms / empty-streak halt** to `run-loop!`
  (so an unresponsive LLM closes `:no-forms` instead of spinning to turn-limit).
  [tasks #15, #16]

## Remaining work (prioritized)

### 1. Phase 2 — per-agent isolation (BIG; threat model RESOLVED)

Owner decision: per-agent isolation is needed **now**, even for a single trusted
user — to contain an agent's **mistakes** (an errant op destroying the host), not
just malice. So `worker_threads`-only is out; we want a real container/VM boundary
per agent, with **one pinned stateful worker/VM per agent** (accumulates runtime
state — not a stateless pool).

**GATED on the microVM experiment** (`research/microvm-isolation-experiment.md`):
validate shared-RO datahike reads over virtio-fs + vsock writes + snapshot-fork
before committing the design (the make-or-break is LMDB read coherence across the
VM boundary). Until that runs, **eval stays in-process** (today's behavior). The
keystone *buffer-worker-writes-commit-atomically* fix folds into the isolated
write path. `architecture.md` §Isolation gets rewritten once the experiment lands.
[task #6]

### 2. reconnect-since-t replay (the last correctness gap) [#11]

A UDS drop between pod↔wire-server silently loses wake messages → an agent sits
`:idle` with unread mail. `subscribe-tx` needs a `since-t` basis to replay the
gap. Two-sided (wire-server CLJ + pod CLJS); live-prove by dropping the UDS.

### 3. Hygiene / flagged smells (low-risk)

- `inspector.cljs` uses `datahike.api` directly (`d/q`/`d/datoms`/`d/entity`) —
  swap to `db/query`/`db/entity` (the "only inside `src/seon/db/`" rule).
- Missing `:malli/schema`: `run/this-process-run?`, `loop/drive-run!`,
  `wire/ping!`. `wire/!own-write-ids` `def` → `defonce ^:private`.
- The `:node-test` runner's deeper `^:async`-under-load wedge (the backstop +
  tail-retry mask it; a fuller fix = per-ns process isolation, or lighten
  `inspector-chips-test`'s 4× `boot-seed!`).
- 8 paid/todo gym scenario EDNs carry old-model attrs → run-model conversion
  (+ a terminal verb per scenario) before the next paid sweep.

### 4. Open design items (when relevant)

- `:seon.agent.turn/basis-t` on turns → exact `db-as-of(t)` historical re-render
  of "what the agent saw at turn N".
- Domain-ns interactive handlers need an agent→ns ownership map (today `/call`
  handlers are home-ns-only).
- `origin-forge` guard (`warn-on-seed-origin-forge!`) is lower-value after the
  `agent-view` deletion — revisit.
- **Feeds** (one-SSE-per-feed registry, the video-wall UI) — architecture §Feeds;
  a deferred feature, not on the critical path.

## Open decisions awaiting the owner

- The microVM experiment result → Phase-2 direction.
- Whether `derive-status` is the right rename (vs `status`).
