---
type: orchestrator
status: active
tags: [orchestrator, agent, web, index]
---

# Two-session coordination — Runtime ⟷ UI/UX

Two independent Claude Code sessions work `feature/agent-fsm` in parallel and
coordinate **through this file + git** — commits are the messages; there is no
live cross-session channel. **On resume, read the other lane's _Now_ / _Needs_ /
_Interface changes_ first.** Keep commits small and clear; never edit the other
lane's files — if you need a change there, write it under _Needs_ and the owner
makes it. Main tree, no worktrees (shared-tree + awareness).

## Sessions & lanes

| Lane | Owner | Edits freely | Must NOT edit |
|---|---|---|---|
| **Runtime** | Session R ("everything else") | agent loop / run / `seon.derive` / ctx / turn / eval; `seon.db` / store / wire; `seon.server.*`; schema; gym; instrumentation | `src/seon/web/**`, `docs/prds/namespace-ui/**` |
| **UI/UX** | Session U | `src/seon/web/inspector.cljs`, `src/seon/web/serve.cljs`, `src/seon/web/reactive/*`, CSS/JS assets, `docs/prds/namespace-ui/**` | the runtime files above |

## The one shared contract — derive = data, render = presentation

- **R owns `seon.derive`** — the pure read layer (`derive-state`, `current-run`,
  `run-turn-count`, `agent-turn-count`, `last-beat`, `armable-agent-ids`,
  `derive-status`; each takes an explicit db value) — and the **`since-t` tx
  feed**. R keeps these stable for U.
- **U consumes it** — feeds/renderers call `seon.derive` fns and subscribe to the
  feed, then present (hiccup / datastar / SSE). Rendering is **pure read — no
  writes, no CAS**. The pattern is `subscribe → derive → hash → push`.
- **Interactivity (`/call`) is split:** U owns the render-time rewrite (a
  fn-call/fn-ref → standard datastar `@post('/call')`) and the datastar shape; R
  owns `/call` resolution + the capability gate + eval.
- **Rule:** R does not change a `seon.derive` render-facing signature or the
  `/call` request/response shape without an _Interface changes_ entry below. U
  does not add runtime reads/writes — it asks under _Needs_.

## Boot pointers (either session)

- `architecture.md` — current-state design + the distributed model + a "needs
  baking" section.
- `research/datahike-primer.md` — how the runtime treats the DB (read before
  touching reads).
- `docs/seon/concepts/reactive-context.md` — render = function of the DB; derive,
  never store.
- `docs/prds/namespace-ui/design-system.md` — Phosphor Terminal theme (colors,
  typography, density).
- `agent-runtime-spec.md` / `remaining-work.md` — the run model + status.

## Live coordination — each side edits ITS subsection and commits

### Now — Runtime (R)

- Snap-to-Tx collapse, final piece: **Unit 2** (per-turn db-value threading +
  in-tx CAS work-fence) in flight. Then a combined live-proof + gym
  metric-validation.
- **Landed + stable to build on:** `seon.derive` (one acyclic read layer), the
  `since-t` lossless tx feed, the run model (run / turn / transition table in
  `seon.agent.loop`), the single render path (`ctx/render-context` — prompt ==
  inspector view, byte-identical).

### Now — UI/UX (U)

- _(U fills this in: what it's building, which files it's touching.)_

### Needs — Runtime asks of UI/UX

- _(none yet)_

### Needs — UI/UX asks of Runtime

- _(U fills this in — e.g. "need `seon.derive/foo` to also return X", or a new
  feed slice.)_

### Interface changes (either side; newest first)

- _(Log any change to a `seon.derive` render-facing signature, the feed shape, or
  the `/call` request/response shape here, so the other side picks it up on
  resume.)_
