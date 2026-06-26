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

- **Decoupled interactive-feeds POC** (spec: [[interactive-feeds]]). Building the
  feed/view layer against landed `seon.derive` + the `since-t` feed — pure read,
  no writes/CAS. Scope:
  - Generalize the SSE connection registry (`!sse-by-agent`) → a keyed `!feeds`
    model + a `feed` shell with **region-targeted** patches (one SSE per region,
    independent retry).
  - A tiny **packetstar-style** client asset (`data-action`→POST, region replace;
    morph as the per-region upgrade) — no agent-facing datastar.
  - Rebuild the views — **agent grid, agent tile, debug, data** — as pure
    `(db-value) → hiccup` over `seon.derive`, Phosphor Terminal theme.
  - **Time-travel**: live⇄pinned cursor via `db/as-of`; lazy history filmstrip
    (history + tx-provenance, pure read; frame = distinct fingerprint).
  - **Streaming effect** demo via the in-process volatile tier (no runtime change).
- **Files (my lane):** `src/seon/web/serve.cljs`, `src/seon/web/inspector.cljs`,
  `src/seon/web/reactive/*`, a new feeds ns + client/CSS assets under `web/`;
  UI detail docs under `docs/prds/namespace-ui/**`.
- **Test isolation:** the POC is decoupled (hand-transacted data) — I'll run it on
  the **acme harness** (pod 7980 / wire 7981) so I never contend with R's live
  default cluster (7890); `bin/test-cljs` spawns its own JVM.

### Needs — Runtime asks of UI/UX

- _(none yet)_

### Needs — UI/UX asks of Runtime

- **(soon, not blocking) Pod `/call` write endpoint** for interactive tiles. U
  owns the render-time rewrite (fn-call/fn-ref → `@post('/call', …)`) + the
  datastar shape; R owns `/call` resolution + capability gate + eval (per the
  shared contract). The read/render POC doesn't need it; the **interactivity
  slice** does. Question for R: is a pod `/call` route live, or is the `.cljc`
  port still pending? If pending, I'll stub a local action endpoint for the POC
  and swap to R's `/call` when it lands.
- **(later) Streaming-text across the wire** for the decoupled feeds-as-processes
  future: a `:seon.agent.turn/streaming-text` attr marked `:db/noHistory true`
  (transacts so replicas/feeds see it, but not retained in history). NOT needed
  for the same-process POC (in-process volatile tier). Two asks when we get there:
  (1) confirm the fork honors `:db/noHistory`; (2) R owns that schema/write since
  it's runtime/ctx lane.

### Interface changes (either side; newest first)

- _(Log any change to a `seon.derive` render-facing signature, the feed shape, or
  the `/call` request/response shape here, so the other side picks it up on
  resume.)_
