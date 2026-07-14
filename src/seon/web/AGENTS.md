# src/seon/web — the pod's HTTP/SSE front door

**Read before editing:** `docs/seon/architecture/ui.md` (the live channel +
routing + page tree), `observability.md` (cluster lifecycle, the
`/agents/run` door + debug surfaces),
`docs/prds/namespace-ui/design-system.md` (Phosphor Terminal theme). Skills:
`datastar-web-ui` (SSE/morph/signals), `browser-automation` (verification —
note the browser 503s long-lived SSE; verify feeds with a node gunzip client).

## Systems at play

- **`serve.cljs`** — the one browser-facing HTTP+SSE server, and the
  **`POST /agents/run` door** (`handle-agent-run!`): start-or-reuse an agent
  IN THE POD'S OWN CLUSTER (optional `agent_id`; durable database, survives pod
  restarts), deliver input via the real wake path, await derived `:idle`,
  return the truthful reply + termination metadata. No scratch database and no
  conn/schema root swap—isolation is a whole cluster. Extend this recipe,
  don't fork it.
- **`router.cljs`** — reitit router derived from `:seon.route/*` datoms; the
  route table is data. `/call` is the ONE action door; the capability gate
  authorizes the fn. New pages = new route datoms + layouts, not new
  handlers.
- **Route truth (the ONE place — link here, don't restate):** `/` is root's
  fleet/agent view (`datastar/serve-root!`); `/agent/{id}` +
  `/agent/{id}/feed` is an ordinary agent view; `POST /agents` is the browser
  birth door and `/agent/{id}/call` is the canvas action door. Route changes
  are `:seon.route/*` datom seeds, so a
  `bin/seon cluster reset default` is required for a new/renamed route to land.
- **`datastar.cljs`** — the live channel: one tx-listener on the replica
  derives the WHOLE element (`view = f(db-as-of t)`) and pushes one gzip
  datastar **morph**; idiomorph diffs client-side; a coalescing throttle
  collapses tx bursts. There is NO server-side tree diff (`!last-tree` is
  dead — don't rebuild it). The time-travel bar here is the worked example
  of as-of rendering.
- **`view_unit.cljs`** — one bounded render-unit cache/invalidation owner for
  shared database-derived HTML. Extend it rather than adding a page-specific
  memoizer, feed registry, or dependency graph.
- **`debug.cljs`** — `/agent/{id}/debug` renders real per-block prompt text,
  HTML twins, token breakdown, and historical `turn`/`turn-diff` projections;
  `/data` is the live indexed database browser with its own shared feed.

## Rules that bite

- **No agent code ever touches an SSE connection.** Agents write facts; the
  UI-host derives and streams. If a feature seems to need an agent to push
  UI, it needs a datom + a render fn instead.
- Reconnect = repaint `view = f(db)`; the browser stream needs no `since-t`
  replay (that's the pod↔database-server feed's mechanism).
- All sizes shown anywhere are TOKENS (`seon.ai.tokens/estimate`), never
  chars.
- UI style: density over whitespace, `text-xs`, warm blacks/cream/amber,
  dot+text status, monospace. Agent-visible utility classes must be on the
  curated safelist (agents guess non-safelisted classes → invisible UI).

## Vendored grounding

`reference-code/reitit/` (router-as-data), `reference-code/datastar/` +
`reference-code/datastar-clojure/` (SSE semantics, morph), `reference-code/
hyperlith/` (the whole-page-morph pattern this channel follows).
