---
type: prd
status: draft
tags: [prd, web, agent]
---

# UX-component toolkit + markdown-everywhere + under-the-hood visibility

Owner-directed (2026-06-26): give agents PRE-MADE easy fns that render common
scenarios (e.g. `(explain-pros-cons! {:seon.ui/pros […]})`), render ALL agent
free-text as HTML-from-markdown by default, and show the user MORE of what's
happening under the hood (todos updating live, the message→todo flow). Owner's
guardrail: *only build what's genuinely useful AND beautiful — ditch low-value
components.* This proposal is the agreed design between Session R (Runtime) and
Session U (UI/UX); R must agree to the R-lane parts (see Open questions).

## Headline

A TWO-LAYER toolkit on ONE markdown path:

- **Layer 1 (U)** — pure `:seon.ui/*` hiccup components in the shared
  `seon.ui.components` (cljc). No effects, just `map → hiccup`.
- **Layer 2 (R)** — thin effectful agent VERBS in a new `seon.agent.ui` that
  build a Layer-1 component and transact the resulting LITERAL hiccup onto the
  calling agent's `:seon.render.live-tile/content` (the welcome-wiring move at
  `live_tile.cljs`; literal hiccup bypasses SCI). So an agent calls
  `(explain-pros-cons! {…})` and the human's hero tile shows the card — zero
  hiccup authoring by the agent.

They meet only at the registered `:seon.ui/*` contract + the `seon.derive`
enrichment, so neither lane steps on the other.

## Build NOW (high value, research-independent)

### 1. Markdown everywhere — ONE path

All agent free-text renders markdown→HTML by default. One mechanism, two entry
points (same parser):

- `seon.ui.markdown/md->hiccup` (exists) — BLOCK content (card bodies, chat,
  rationale): paragraphs/lists/headings/code/tables.
- `seon.ui.markdown/inline` (NEW, U) — lift the already-private `inline->hiccup`
  (`markdown.cljs:48`) to public: inline spans only (bold/italic/`code`/links),
  NO block `<p>` margins — for tight one-line surfaces (todo titles, commentary,
  kv values) so markdown doesn't add paragraph spacing to single lines.

Switch the 5 raw sites the mapping found:

| site | → | lane |
|---|---|---|
| `web/tile.cljs:112` status-view purpose | `md/inline` | U |
| `web/tile.cljs:141` commentary content | `md/inline` | U |
| `web/tile.cljs:167` todos-view title | `md/inline` | U |
| `web/tile.cljs:~202` toolkit-view doc | `md/inline` | U |
| `render/default.cljs:244` recent-msgs content | `md/inline` | R (outside web/) |

`chat.cljs:248/256` already use `md->hiccup` (the reference symmetric pattern).
Safety: the serializer escapes the OWASP-5 by default and `md->hiccup` blocks
`javascript:`/`data:` URLs — markdown-everywhere opens no injection surface even
though the text is agent-authored. Constraint: splice `md/inline`'s seq with
`into` (never a bare lazy `for`) so `live-tile/valid-hiccup?` accepts the tile.

### 2. Message→todo visibility — derive, don't store

Answers the owner's question. **Today:** a human message auto-creates a todo
whose title is the RAW clipped message text (`clip-title`, `message.cljs:225`) —
not a nice "Respond: …"; the agent CONTEXT block already shows a `✉` marker
(`todo/internal.cljs`) but the UI tile does NOT; agents DO mark these complete
(`complete!`). **Fix:** a new `seon.derive/agent-todos [db agent-id]` (R lane)
enriching each todo — ALL derived, no schema/stored flag:

- `:seon.ui/respond?` — a `:seon.agent.todo/message` ref is present (message-origin)
- `:seon.ui/age` — from created-at / completed-at
- `:seon.ui/recently-changed?` — status flip within ~10s of HEAD (drives a tx highlight)

Both the UI todos-view (U) and the context `open-todos-block` (R) consume it →
the tile renders `✉ Respond: "…"` + age + a recently-changed highlight, matching
the marker the context already shows.

### 3. Three component+verb pairs (the proven core)

| component (U, `seon.ui.components`) | verb (R, `seon.agent.ui`) | scenario |
|---|---|---|
| `md-card` `{:seon.ui/title :seon.ui/body :seon.ui/tone}` | `show-card!` | the workhorse "render this markdown to the human" |
| `pros-cons` `{:seon.ui/title :seon.ui/pros [..] :seon.ui/cons [..]}` | `explain-pros-cons!` | tradeoffs (owner's example) |
| `decision-summary` `{:seon.ui/recommendation :seon.ui/rationale :seon.ui/options [..]}` | `recommend!` | "here's my recommendation" |

## DEFER (ditched from the now-list, per the owner's lens)

`kv-table`, `steps`, `data-table` and their verbs (`show-status!`/`show-steps!`/
`show-table!`). Reasons: `kv-table` is a special-case of `data-table`; `steps`
overlaps the todos tile (already shows progress advancing); all three are
speculative. We let the in-flight **agentic-benchmark research** (what tasks the
big suites grade → what people actually build with agents) show which scenarios
recur before building a catalog. Owner: "more ideas for what people will be
building" should drive the rest.

## Lane split

| Item | Owner |
|---|---|
| `:seon.ui/*` schema vocabulary (a tiny new `seon.ui` cljc — keyword-ns = real ns; register shared shapes ONCE) | **R** |
| `seon.agent.ui` effectful verbs (transact literal hiccup onto live-tile) | **R** |
| `seon.derive/agent-todos` enrichment | **R** |
| `render/default.cljs:244` + context `open-todos-block` markdown/✉ switch | **R** |
| `seon.ui.components` hiccup components (Layer 1) | **U** (shared cljc) |
| `seon.ui.markdown/inline` public lift | **U** |
| `tile.cljs` markdown switches + todos-view enrichment render + the live tile | **U** |

## Open questions for R (need your agree before R-lane parts land)

1. Agree to own `seon.agent.ui` (the effectful verbs), the `:seon.ui/*` schema,
   and `seon.derive/agent-todos`?
2. The verbs transact LITERAL hiccup onto `:seon.render.live-tile/content` — is
   that the right write path, or should it route through `/call`? (Literal hiccup
   bypasses SCI; the welcome-wiring already does this with a symbol.)
3. `seon.ui.components` is shared cljc — fine for U to own, or co-own?
4. Sequencing vs your lean-context work: the verbs become most valuable once
   agents are steered to USE them — should the system-text "BUILD YOUR
   ENVIRONMENT" block mention them as available tools?

## Status

Markdown-everywhere + the todo-visibility derive are the immediate builds (U side
holds the `tile.cljs` edits until the in-flight debug-overlay agent finishes, to
avoid a shared-file collision). The three component+verb pairs follow once R
agrees the lane. Deferred components wait on the benchmark research.
