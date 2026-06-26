---
type: prd
status: draft
tags: [prd, web, agent, database]
---

# Interactive Feeds — composable live views, the poor-man's client, time-travel

How the human-facing UI is built: composable **live feeds** rendered from the
database, made interactive by a **dead-simple POST call system**, time-travelable
for free via datahike `as-of`, and authored as plain **hiccup + Tailwind** with no
agent-facing datastar concepts. This is the view/interaction successor to the
render twin in [[context-render]] and [[single-render-path-design-2026-06-25]];
it consumes the `:seon.render/html` half they produce and serves it.

**Sequencing:** this lands AFTER the agent-loop work ([[agent-loop]] + the
Snap-to-Tx units in [[architecture]]) is done. It begins as a **decoupled
proof-of-concept** — rebuilding today's views (agent grid, agent tile, debug,
data) in the simpler model to measure how composable we can get — then integrates
into the live system and is tested as a unit.

## TL;DR — the whole model in one breath

**You mutate the database; the re-render flows back.** An interaction POSTs to a
plain server function; that function `transact!`s a fact; the tx feed fans out;
each affected feed re-renders its region from the new db value and streams the
HTML down; the client applies it. There is no client-side state machine, no
agent-facing datastar expression language, no per-attribute subscription graph.
The render is a pure function of a db value; the data change IS the update event
(we get hyperlith's `refresh-all!` for free — the tx-log is the refresh signal).

The only places that depart from "just re-render from the DB" are **effects**
(streaming LLM tokens, progress, presence) — transient, high-frequency values
that don't belong in durable history. Those ride a **volatile tier** that pings
the feed; see *Effects & streaming*.

## What we borrowed (hyperlith, read in `reference-code/hyperlith`)

The framework author iterated to exactly the shape the owner wanted. Two findings
shaped this design:

- **The server-side change-detection hash was *removed*** (`50a3773 "Remove last
  event hash mechanism"`). The live path now just re-renders and streams the whole
  view on every refresh; brotli's streaming **window** makes near-identical
  resends tiny, and the **client** applies the diff. Server-side "which datoms
  changed" tracking was wasted work.
- **The `poor-mans-datastar` branch replaces `datastar.js` with a 35-line
  `packetstar.js`** — open an `EventSource`, replace a region's HTML on each
  message, and POST any element's `data-action` URL on `mousedown`. Views drop
  *all* datastar expressions; an interactive element goes from
  `:data-on:pointerdown "@post(...)"` to just `:data-action "<url>"`. That is the
  owner's "shove down the compressed render + simple function-based POST" instinct,
  already proven.

We borrow: the shim page + reconnect boilerplate, the tiny `data-action`→POST
client, the **brotli streaming-window** compression + SSE writer ("shove down the
*compressed* render"), and the `defview`/`defaction` ergonomic split. We adapt
their **core.async mult** to our `db/listen!` tx feed, and their **manual
`refresh-all!`** to the tx commit itself (the DB is the bus). We keep their
client-vs-server simplicity but make patches **region-targeted** (not whole-body)
so feeds compose — see *Composability*.

## The composability primitives — everything is a tile

**One primitive: the tile.** A *tile* is a composable live section — a region
bound to a feed rendering a view, optionally interactive. There is **no special
"the live tile"** standing apart from hardcoded widgets: the agent's main render,
the running commentary (demoted chat), status, todos, debug, and the data browser
are **all tiles** — differing only in which view they render and how much space
they take. A page is a **shell of tiles**; an **app** is a named arrangement of
them (a layout + each tile's time cursor). Proving that every surface is the same
composable primitive is the point of the feature.

A tile is assembled from these parts (the build-once primitives):

- **Region** — the tile's DOM element with a stable `id` (`#tile-<key>`). A patch
  targets one region, so a tile updating never disturbs a sibling.
- **Feed** — the tile's transport: `subscribe → derive → render → stream`.
  Independent SSE connection (one dead tile is one dead tile, auto-retried),
  independent cadence, independent live/pinned cursor. The architecture's
  *feeds-as-processes* role at tile granularity.
- **View fn** — the tile's render: pure `(db-value) → hiccup`, Tailwind inline, no
  client concepts. Just the `:seon.render/html` twin from [[context-render]].
- **Action fn** — the tile's interactions: plain Clojure, POST-invoked via `/call`,
  ends in a `transact!`; the resulting tx re-renders the affected tiles.
- **Input tile** — a tile the server **never overwrites**; the user owns it and it
  POSTs on submit. How native browser behavior is preserved (see below).
- **Shell** — the boot HTML: loads the tiny client, declares the tiles, opens each
  tile's stream. Written once; never by an agent.

```clojure
;; a view fn is just hiccup + tailwind — nothing else
(defn agent-tile [db agent-id]
  (let [{:seon.agent/keys [state] :as st} (derive/derive-status {:seon.agent/id agent-id})]
    [:div.rounded.bg-base-800.p-3.text-xs.font-mono
     [:div.flex.items-center.gap-2
      [:span {:class (status-dot-class state)} "●"] [:span (name state)]]
     [:div.mt-2.text-base-300 (purpose db agent-id)]
     ;; interactive: a plain URL, no datastar expression
     [:button.mt-2.text-amber-400 {:data-action (call-url `pause-agent! agent-id)} "pause"]]))

```

### Default layout — tiles all the way down

The product surface is **~2/3 hero tile + ~1/3 rail of tiles**. The agent's main
render is the hero — **the primary communication medium** (the inversion: the tile
leads, chat is demoted to a *commentary* tile in the rail, a live-updating ticker,
not the main surface). The rail stacks other tiles (commentary, status, todos),
each a first-class tile, not a special-cased widget. A **fullscreen toggle**
expands the hero to the whole canvas — the immersive mode for when the user trusts
the agent and wants only its surface. Because every slot is a tile, the layout is
just data: resize, swap, add, or save an arrangement as a named app.

## Keeping native browser interactions — the input tile beside the live hero

The owner's concrete target: an **input tile the user types in** while the **hero
tile renders live** beside it. The naive whole-region-replace would wipe the
user's half-typed text and cursor on every tile update. The structural fix is
**region isolation**, not a fancier client:

- The **input lives in its own region** that the server never streams into. The
  user types (fully native — focus, selection, IME all work); on submit it POSTs
  the message. Nothing the server does touches that region.
- The **tile is a sibling region** that streams live (including the agent's
  streaming response). It updates without ever re-rendering the input.

So two render paths compose on one page, each correct: the input is a static,
user-owned region; the tile is a live, server-owned region. **The reply box is not
part of the steppable/streamed surface** — which is also semantically right (you
don't scrub your own input box).

The **upgrade path**, only if a single region must *both* update live *and* hold
stateful inputs: swap that region's apply-step from replace to **morph**
(idiomorph, preserving focus/scroll by `id`). The client supports either per
region; the POC defaults to replace + isolation and reaches for morph only where
proven necessary. This is the one open client decision (below).

## Time-travel — Snap-to-Tx for views (free, via `as-of`)

The loop already threads ONE frozen db value per turn (Snap-to-Tx, [[architecture]]).
A feed does the same: it renders against a **basis-t**.

- **Live feed** — basis-t = HEAD; re-render on each (coalesced) tx.
- **Pinned feed** — basis-t = `T`; render `(db/as-of T)`; ignores new commits. A
  pinned feed does **no reactive work** — render once, then idle.
- **A frame is a distinct rendered fingerprint** — not a distinct tx. Most txes
  don't change a given tile; identical output ⇒ no new frame. This dissolves the
  "most txes are unrelated to this render" problem: the live path lets the client
  absorb no-ops, and history dedups by fingerprint.
- **History filmstrip** — the scrub points are computed **lazily when the scrubber
  opens**, never on the live hot path: walk this agent's txes (provenance carries
  `:seon.db/agent-id`), render each `as-of`, fast-hash, keep distinct. Stepping to
  a past frame is `(db/as-of t)` re-render — cached forever because the past is
  immutable.
- **"Keep the past the past" = true replay, and it is the cacheable case.** A past
  frame re-resolves both the data AND the render-fn source `as-of t` (code-as-data:
  the fn is `:seon.fn/source`, itself a datom on the timeline). Both inputs are
  immutable, so the frame is byte-identical forever — compute once, cache, never
  invalidate. Only HEAD re-renders.
- **An "app" = a saved arrangement of tiles.** Each tile's saved cursor is a small
  `:seon.view` entity with proper values, e.g.
  `{:seon.view/name "morning" :seon.view/tile <tile-ref> :seon.view/basis-t 4821}`
  (omit `:seon.view/basis-t` ⇒ live HEAD; add `:seon.view/render-fn 'my.view/variant`
  for a variant renderer). An app is a named list of these + a layout; flipping
  between apps moves the cursors. Branching = fork the arrangement. This is the
  only new schema, and it is tiny.

### Caching discipline (datahike-grounded)

From [[datahike-primer]] (and the architecture's explicit warning): **never key a
memo on a db *value*** — `equiv-db` walks the EAVT index and faults konserve nodes
in. The frame cache keys on **basis-t** (and the content cache on the render
**hash**), never on a db value:

```clojure
(defonce ^:private !frames (atom {}))                 ; {[feed-key basis-t] {:html :hash}}
;; past basis-t ⇒ memoize forever (immutable inputs); HEAD ⇒ always recompute.

```

## Effects & streaming responses

Most updates are durable facts → DB → re-render. **Effects are the exception**:
streaming LLM tokens, typing indicators, progress, presence. These are transient
and high-frequency; writing them per-token into the durable single-writer is
write-amplification and history bloat. They use a **volatile tier** (the project's
three-tier rule: durable datoms / blobs / volatile live values).

Two storage strategies, recommended per phase:

- **POC / same-process (recommended first):** the streaming producer appends
  chunks to an **in-process volatile buffer** (a `globalThis` atom keyed by feed)
  and pings that feed's coalescer (~100ms). The view fn reads `(db-value +
  volatile-buffer)`, so the tile shows the growing partial. On completion: one
  `transact!` of the final message (a durable fact), clear the buffer; the next
  tx-driven render shows the durable version, byte-identical to the last streamed
  frame. Token-rate writes never touch the durable DB.
- **Decoupled feeds-as-processes (later):** a separate feed-process can't see
  another process's volatile buffer, so the partial must cross the wire. Stream to
  a single coalesced `:seon.agent.turn/streaming-text` attr marked
  **`:db/noHistory true`** — it transacts (so replicas/feeds see it via the normal
  tx feed) but is NOT retained in history (no bloat, no `as-of` replay of partial
  states, which is correct). Coalesce checkpoints (~100ms), not per-token. The
  final message is a normal history-kept fact.

**Pre-impl check:** verify the datahike fork honors `:db/noHistory` (attr present
in current db, absent from `(db/history)`) before relying on strategy 2. Strategy 1
needs no new primitive and is the POC default.

The general rule this establishes: **the view fn is a pure function of (db-value,
volatile-context).** Durable facts arrive via the DB and trigger re-render;
transient effects arrive via the volatile tier and ping the feed. Same render
path, two source tiers — no second rendering mechanism.

## The server mechanism (maps onto what exists)

Today this lives in the pod's `seon.web.serve` / `seon.web.inspector` (Node, raw
SSE, native async); the architecture's endgame moves the renderer role to the JVM,
but the mechanism is identical and portable.

- **One feed connection registry** generalizing today's `!sse-by-agent` (already
  multiplexed by `agent-id` / `::index` / `::data`) into `!feeds` keyed by an
  arbitrary feed-key.
- **The refresh signal is `db/listen!`** — on tx, the listener calls the existing
  `schedule-push!` coalescer (~100ms) for each affected feed. No manual
  `refresh-all!`; the commit is the signal.
- **Push = render the region's view fn → compress (Node zlib brotli) → stream** as
  a region-targeted patch. Keep the **hash as the frame fingerprint** (its primary
  job is history/scrub identity); using it to skip an identical push is cheap
  insurance, not the core mechanism (the client tolerates no-op applies).
- **`/call` is the one POST route** (already designed, [[architecture]] *Interactivity*):
  `data-action` resolves the owning agent by **namespace-as-route**,
  sandbox-invokes the fn (capability-checked, Malli-validated), which `transact!`s
  (work-fenced by the in-tx `:db.fn/cas` for agent writes) → feeds re-render. The
  agent writes a normal Clojure fn; the framework wires the POST.

## The decoupled proof-of-concept (first deliverable)

Goal: **measure how simple and composable the model is** by rebuilding today's
views in it, decoupled from the agent loop (drive it with hand-transacted data).

- Rebuild as composed regions + view fns + action fns: **agent grid**, **agent
  tile**, **debug view**, **data view**. Each a pure `(db-value) → hiccup` over
  `seon.derive` + `seon.db`, Tailwind inline.
- Ship the tiny region-targeted client (packetstar-style: EventSource per region,
  `data-action`→POST, region replace; morph available per region).
- Build the **chat-input + live-tile** composition end to end (input region +
  streaming tile region) as the interaction proof.
- Demonstrate **time-travel** on one feed (live ⇄ pinned, a filmstrip) and one
  **streaming effect** (strategy 1).
- Success criteria: a new view is one view fn + one shell line; a new interaction
  is one action fn + one `data-action`; no view fn references a client concept; the
  whole client is < ~50 lines.

## Datahike primitives used (lean on the fork, don't reinvent)

- `db/as-of` — historical frame render (time-travel, true replay).
- `db/since` / `since-t` — feed reconnect replay (lossless across drops).
- tx provenance (`:seon.db/agent-id` on the tx entity) — filter the filmstrip to an
  agent's frames cheaply.
- `:db.fn/cas` — action writes are work-fenced at the writer (agent `/call`s).
- `:db/noHistory` — the streaming-text checkpoint attr (strategy 2; verify first).
- **basis-t as the cache key** — never a db value (`equiv-db` fault hazard).

## Open decisions

- **Replace vs morph default.** Recommend **region-replace + input isolation** as
  the default (tiniest client, native input behavior by construction); add **morph**
  only for a region that must update live *and* hold stateful inputs. Owner call.
- **Filmstrip granularity** — DISSOLVED: a frame is a distinct fingerprint (every
  moment the tile looked different), neither every-tx nor turn-boundary.
- **Persist the frame index?** Record `(basis-t, hash)` change-points as
  lightweight facts (full-history scrubbing across restarts, tiny storage) vs keep
  it in-memory per-connection (scrub only back to connect time). Recommend persist —
  same "log what was shown" category as the turn log.
- **Streaming storage tier** — strategy 1 (volatile) for the POC; strategy 2
  (`:db/noHistory`) when feeds become separate processes.

## Non-goals / guardrails

- **No agent-facing datastar.** A view is hiccup; an interaction is a `data-action`
  URL. The only datastar/packetstar lives in the one shim + client, written once.
- **No second rendering mechanism.** Feeds render the same `:seon.render/html` twin
  from [[context-render]]; this PRD is transport + composition + time-travel, not a
  new renderer.
- **No render blobs stored.** Frames derive from `as-of`; only facts (and the
  optional `(basis-t, hash)` frame index) persist.
- **No per-token durable writes.** Streaming uses the volatile tier or a coalesced
  `:db/noHistory` checkpoint; the final message is the only durable fact.
- **No memoizing on a db value.** Key caches on basis-t / render hash.
- **Build after the loop.** This integrates once [[agent-loop]] + Snap-to-Tx are
  done; the POC may proceed decoupled in parallel.
