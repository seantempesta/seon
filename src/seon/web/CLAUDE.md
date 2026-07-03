# src/seon/web — the pod's HTTP/SSE front door (.cljs = active; .clj = paused JVM track)

**Read before editing:** `docs/seon/architecture/ui.md` (the live channel +
routing + page tree), `observability.md` (cluster lifecycle, the
`/agents/run` door + debug surfaces),
`docs/prds/namespace-ui/design-system.md` (Phosphor Terminal theme). Skills:
`datastar-web-ui` (SSE/morph/signals), `browser-automation` (verification —
note the browser 503s long-lived SSE; verify feeds with a node gunzip client).

## Systems at play

- **`serve.cljs`** — the one browser-facing HTTP+SSE server, and the
  **`POST /agents/run` door** (`handle-agent-run!`): start-or-reuse an agent
  IN THE POD'S OWN CLUSTER (optional `agent_id`; durable store, survives pod
  restarts), deliver input via the real wake path, await derived `:idle`,
  return the truthful reply + termination metadata. NO scratch store, NO
  conn/schema root swap — isolation is a whole cluster (`bin/seon cluster
  create`). Extend this recipe, don't fork it.
- **`router.cljs`** — reitit router derived from `:seon.route/*` datoms; the
  route table is data. `/call` is the ONE action door; the capability gate
  authorizes the fn. New pages = new route datoms + layouts, not new
  handlers.
- **`datastar.cljs`** — the live channel: one tx-listener on the replica
  derives the WHOLE element (`view = f(db-as-of t)`) and pushes one gzip
  datastar **morph**; idiomorph diffs client-side; a coalescing throttle
  collapses tx bursts. There is NO server-side tree diff (`!last-tree` is
  dead — don't rebuild it). The time-travel bar here is the worked example
  of as-of rendering.
- **`debug.cljs`** — `/agent/{id}/debug`: per-block prompt text + HTML twins
  + token context-bar, derived via `inspect/ctx-preview` (present-tense
  today; historical turns come via the observability build).

## Rules that bite

- **No agent code ever touches an SSE connection.** Agents write facts; the
  UI-host derives and streams. If a feature seems to need an agent to push
  UI, it needs a datom + a render fn instead.
- Reconnect = repaint `view = f(db)`; the browser stream needs no `since-t`
  replay (that's the pod↔wire-server feed's mechanism).
- All sizes shown anywhere are TOKENS (`seon.ai.tokens/estimate`), never
  chars.
- UI style: density over whitespace, `text-xs`, warm blacks/cream/amber,
  dot+text status, monospace. Agent-visible utility classes must be on the
  curated safelist (agents guess non-safelisted classes → invisible UI).

## Vendored grounding

`reference-code/reitit/` (router-as-data), `reference-code/datastar/` +
`reference-code/datastar-clojure/` (SSE semantics, morph), `reference-code/
hyperlith/` (the whole-page-morph pattern this channel follows).
