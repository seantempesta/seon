---
type: prd
status: active
tags: [prd, agent, web]
---

# Live Tiles — three surfaces, one render (2026-06-11, revised)

**Rewritten 2026-06-11 after user review.** The decisions below marked
DECIDED(user 2026-06-11) are law — do not re-litigate. This replaces
the earlier draft of this file (render levels, hiccup-flatten floor,
`set-tile!` sugar, and the post-Friday timing call are all superseded;
see §9 for what was dropped and why).

**The vision:** every agent owns ONE live tile — the thing it is
currently conveying to its human (a chart, a status, a list, whatever
the human asked for). The tile shows up at three zoom surfaces driven
by ONE mechanism, and the agent always knows what its human currently
sees because the same wired value renders into its own context every
turn. Build target: **the demo on 2026-06-12** — default tile + agent
view (bubbles + expanded tile) + welcome wiring + debug overlay +
route swap (user: achievable for tomorrow).

## 0. Honest starting inventory (verified in source 2026-06-11)

| Already live | Where |
|---|---|
| Per-agent tile slot: `:seon.render/html` on the agent entity — qualified symbol OR literal hiccup, late-resolved every render | `seon.render/render-agent-tile` (render.cljs:475), `html-render` (render.cljs:146) |
| Default tile renderer: status dot + turn count + errors + last 5 messages | `seon.render.default/view` |
| Mission control IS a tile grid, SSE-morphed per commit | `inspector/agents-dash-fragment` + `agent-grid-tile` + `push-index!` |
| The debug view: raw AI context left, entity cards + tile right | `/agent/<id>` two-pane inspector (`inspector-shell`, inspector.cljs:690) |
| Chat input + send path: `POST /chat?agent=<id>` → `message!` → wake | `chat-bar-fragment` + `serve.cljs/handle-chat!` |
| Derived conversation query with from-kind labels (`user` / `assistant` / `agent-<id>`) | `seon.render.default/recent-messages` |
| Section twins: one section, `:seon.render/ai` + optional `:seon.render/html` | `:seon.ctx/section` schema (ctx.cljs:90) |
| Capabilities teaching of the tile (raw transact of `:seon.render/html`) | ctx.cljs ~908 "Your live tile" block |
| ONE composer for prompt text, sections sorted by `:seon.ctx/priority` | `seon.ctx/assemble-context` + `substrate-default-ctx` |
| Phosphor Terminal design system | `docs/prds/namespace-ui/design-system.md`, `seon.ui.components` |

What's missing: the consumer view (bubbles + expanded tile), the
root-grid default tile worth glancing at, the awareness section, the
welcome experience, and the explicit live-tile key + twin contract.

## 1. The three surfaces — DECIDED(user 2026-06-11)

One mechanism (§2) renders all three:

```text
SURFACE 1 — DEFAULT TILE   /agents               root grid, small
SURFACE 2 — AGENT VIEW     /agent/<id>           consumer split screen
SURFACE 3 — DEBUG VIEW     /agent/<id>/debug     today's inspector, kept exactly
```

### Surface 1 — default tile (root view, small)

The agent's **purpose + ID + last message, nicely formatted** — the
agent's last REPLY rendered as readable text, not raw message data.
This is what an uncustomized agent's tile shows; a customized agent's
wired tile content renders here at compact size (§2 container rule).

### Surface 2 — agent view at `/agent/<id>` (the consumer view)

Split screen:

- **LEFT — chat bubbles**, human ↔ agent, with a message-input box.
  **Agent-to-agent messages render INLINE in the same stream, styled
  differently** — dimmer, smaller, labeled with the other agent's id
  (`agent-<id>`). The demo shows multi-agent coordination; the human
  watches their agent confer with peers without leaving the
  conversation. Data source is the existing derived conversation
  query (from = me OR to ∋ me — `recent-messages` already labels by
  from-kind); nothing new is stored.
- **RIGHT — the SAME live tile, expanded.** Identical wired value as
  the root grid, given room (container size selects the expanded
  blocks, §2).

### Surface 2b — FOCUS MODE (optional, user 2026-06-11): full-screen tile + chat bar

If the tiles land well: a toggle on the agent view collapses the
split into **the live tile at full viewport + a chat bar** (input +
the last reply only). The chat history panel toggles back in on
demand — "generally users only care about the last message." This is
the same one-render mechanism at a third container size: the tile
gets the whole canvas, the agent needs no awareness that the mode
exists (it just gets more room; the `::ai` twin is unchanged).
Demo-optional, not demo-floor: build after T2 if T1/T2 prove out —
it is a layout + toggle, no new agent-facing mechanism, no new
namespace (hot-reload-safe). The demo beat: the agent's WORKSPACE as
the primary surface, conversation reduced to an input bar — "crazy
to think how the default experience is just normal text" (user).

### Surface 3 — debug view, kept exactly as today

The current two-pane inspector (raw context sections left, rendered
context/entity cards right, chat bar) moves UNCHANGED to
`/agent/<id>/debug`. "What the agent sees exactly" remains a
first-class feature.

**Debug overlay — DECIDED(user 2026-06-11) that it exists;
DECIDED(user 2026-06-11): trigger = the `⚙ debug` header button AND
the backtick `` ` `` keybinding** (single key, never typed in a chat
input that has focus guard). The debug view is ALSO reachable from
the consumer view as an overlay WITHOUT changing URLs.
The overlay is a full-viewport layer that loads `/agent/<id>/debug`
content (an iframe is the zero-duplication floor; a fetched fragment
is the polish). Esc or the button closes it.

**Route swap:** mission-control tap goes to `/agent/<id>` (today it
goes to the debug two-pane). Cross-links: `⚙ debug` in the agent-view
header, `← chat` in the debug header.

## 2. The tile mechanism — DECIDED(user 2026-06-11)

### One attr, value matches `:seon.render/html` semantics

ONE attr on the agent's entity wires the tile. Its value follows the
EXISTING `:seon.render/html` pattern exactly (deliberate uniformity —
agents already know this vocabulary):

- **raw hiccup literal** for static content — `[:h1 "…"]` …
- **a qualified fn symbol** for dynamic content, late-resolved at
  every render via `seon.eval/lookup-value` (works for substrate AND
  agent-defined fns — same single path as today).

**Key naming — DECIDED(user 2026-06-11): `:seon.render.live-tile/content`**,
homed in the ns that owns the mechanism (schemas-live-with-their-owner).
Used everywhere as of U1 — including the migration of the agent
entity's current tile use of `:seon.render/html`, which today
double-serves as both the agent tile slot AND the generic
per-entity-card render slot (drift finding, §8). U1 implementation
note: the canonical value-or-fn SHAPE is registered under this key in
`seon.render.live-tile` (which loads first), and `:seon.render/html`
references it — register!'s compilability guard rejects forward
references, so the shape definition had to live in the
earlier-loading ns.

### No render levels — container queries over ONE render

DECIDED(user 2026-06-11): small-vs-large is **CSS container queries
over ONE render**, not level-aware fns. The fn/hiccup emits compact
AND expanded blocks in one document; substrate CSS shows the right
one for the container size. The substrate provides the wrapper
classes and conventions so agents just tag blocks:

```clojure
;; agents tag blocks; substrate CSS does the rest
[:div.seon-tile
 [:div.seon-tile-compact  [:span "3 workouts this week"]]
 [:div.seon-tile-expanded [:svg …full chart…] [:table …]]]
```

Substrate-owned CSS (one place, agents never write media/container
queries): `.seon-tile { container-type: inline-size; }` plus
`@container` rules that show `.seon-tile-compact` below the breakpoint
and `.seon-tile-expanded` at or above it. Untagged content renders at
every size (degrades sanely — fonts clamp, overflow fades). The exact
breakpoint is a CSS constant, tuned once against the real grid cell.

### Dynamic fns return a map with the AI twin

A tile FN returns a map carrying the html twin AND a text twin:

```clojure
{:seon.render/hiccup [...]        ;; what the human sees
 :seon.render/ai     "3 workouts this week: Mon 4200kg, …"}
;; the AI twin is how the agent knows what its human sees —
;; say what the content MEANS; your human sees the picture,
;; you see your words.
```

Mechanically: `:seon.render/html-response` gains an optional
`:seon.render/ai` `:string` entry. This matches the section-twin
shape (`:seon.ctx/section` already pairs `:seon.render/ai` with
`:seon.render/html`) — one twin idea everywhere.

### Agent awareness is DERIVED, not stored

DECIDED(user 2026-06-11): the context composer invokes the SAME wired
value at prompt-assembly time and embeds a section — "your human
currently sees: …". Nothing is stored about "what I showed"; the
section renders only when a tile is wired; when nothing is wired,
nothing renders (reactive-context doctrine — nothing to clear).

The one rule — **"you see exactly what's wired"**:

- wired value is a FN → the section body is the returned
  `:seon.render/ai` text. A fn that omits the twin gets its returned
  hiccup shown verbatim instead (same rule as below — and the raw
  markup in its own context is itself the nudge to add the twin).
- wired value is RAW HICCUP → the section body is the literal hiccup
  verbatim. Hiccup is readable EDN the agent wrote; no flattening
  machinery, no translation layer.

The **section header always includes the wired value's identity** —
the fn's fully-qualified name (its source is one
`:seon.fn`/catalog lookup away) or "literal hiccup on your entity" —
so the agent always sees HOW to change the display:

```text
## Your live tile (what your human currently sees)
Wired: my.workouts/chart-tile   (a fn on your entity; default was the substrate welcome)
3 workouts this week: Mon 4200kg, Wed 3800kg, Fri 4400kg — trending up.
To change it: redefine the fn, or transact a new value onto the key.
```

Section placement: substrate default section `:live-tile` at
priority 28 — present-tense self-knowledge, after the catalogs
(functions-catalog 27), before `:namespace-context` (30) and the
dynamic tail. It sits in the per-agent dynamic zone (its content
changes with the world), like `:warnings`/`:open-todos`.

### Per-turn semantics — stated explicitly, correct by design

The human's tile updates per relevant tx (SSE morph, already live).
The agent's twin is **as-of the turn's db value** — the snapshot the
composer rendered the prompt from. Between turns the human may see
fresher data than the agent's last twin. This is CORRECT BY DESIGN —
do not "fix" it with stored presentation state or mid-turn refreshes;
the next turn's twin re-derives from the then-current db.

### Errors are legible — never silently vanish

A tile fn that THROWS must remain distinguishable from an unwired
tile on BOTH sides:

- **agent's section** shows the error envelope (the standard
  `:seon.error/*` shape) in place of the twin — the agent sees its
  own renderer is broken and what the exception said.
- **human's tile** shows a fallback ("tile error — the agent has been
  shown the failure"), NOT a blank. Today `render-agent-tile`
  swallows throws into `{:seon.render/hiccup nil}` (render.cljs:497)
  — that catch changes to produce the fallback + carry the error to
  the twin path. Vanish = indistinguishable from unwired; banned.

### No `set-tile!` sugar — DECIDED(user 2026-06-11)

The taught path is the raw transact of the attr — one pattern,
matching the `:seon.render/html` semantics agents already know:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id (seon.db/current-agent-id)
     :seon.render.live-tile/content 'my.workouts/chart-tile}]})
```

Sugar only if friction shows later. The ctx.cljs "Your live tile"
capabilities block updates to teach this key + the twin contract +
the tag-blocks convention (it already teaches the raw-transact shape).

## 3. Namespaces — public faces feed agent context

DECIDED(user 2026-06-11): two new PUBLIC namespaces, internals in
`.internal` siblings (the standard `*.internal` convention —
context-v3: `*.internal` is never rendered to agents; the ns name IS
the filter):

- **`seon.render.live-tile`** — the tile mechanism: key registration,
  content resolution (wraps the existing `html-render` value-or-fn
  dispatch — no parallel resolver), the twin contract, the substrate
  welcome fn (§4), the error-envelope fallback.
- **`seon.render.chat`** — the conversation surface: the derived
  bubble query (builds on `recent-messages`' from-kind labels), the
  bubble hiccup (human / agent / inline agent-to-agent styles).

Non-internal namespaces auto-feed agent context — **the public fns
ARE the teaching**. Keep them exemplary: full `:malli/schema` on
every public fn, map-in/map-out (or `:catn` named positional),
namespaced keys, docstrings that teach (an agent reading
`seon.render.live-tile`'s source learns the whole tile vocabulary
without a prose section).

## 4. The default/welcome experience — show, don't tell

### The welcome fn

A substrate welcome fn (lives in `seon.render.live-tile`; takes the
agent's entity/context — the standard render input) renders an
elegant, simple, **TIME-AWARE** default:

- current date/time for the user, and a time-aware greeting ("Good
  evening" at 21:40);
- the agent's purpose line (from the seeded `:purpose` — "created to
  track your workouts");
- a line like *"I'll update this panel as I work — charts, statuses,
  whatever you ask for."*

The copy is double-duty by design: it tells the HUMAN what the panel
is, and — because the agent reads this fn's twin and source every
turn — it reinforces to the AGENT that writing more hiccup-returning
fns is normal and easy.

DECIDED(user 2026-06-11) — welcome personalization: greet the human
by name when a user attr exists in the store (`:seon.user/name` on
the user entity) — "Good evening, Sean." — gracefully generic
otherwise. U1 implements the read side (installed-schema-gated query
in `seon.render.live-tile/user-name`); registering `:seon.user/name`
itself belongs with the `:seon.user` entity schema in
`seon.agent.message` (follow-up one-liner — until it lands, the
generic branch is the live behavior).

### Wired by a REAL EVAL at agent creation — honest provenance

DECIDED(user 2026-06-11): NOT a faked log entry. Agent creation
actually EVALS the wiring form(s) AS the agent (inside its scope,
through the normal eval path) — multiple forms in one turn is fine —
so the eval log honestly shows "the agent set up its tile", and to
the agent it reads like a turn already taken in the conversation
(imitation over obedience, same trick as V3-E's demonstrated evals).
The log never lies: the eval really ran, the datoms really landed,
replay/resume reconstructs it like any other eval.

### The steer: tile updates should be RENDERED DATABASE QUERIES

DECIDED(user 2026-06-11), stated as doctrine in the teaching and the
welcome fn's own implementation: most subsequent tile updates should
be **queries over properly-transacted data** — transact important
findings as linked entities, render by reference. Not hardcoded
hiccup snapshots of computed values. This is what makes session
resume work (the tile re-derives from the store on a fresh pod) and
it is the reactive-context principle applied to the human surface.

## 5. Styling — Phosphor, consumer-tuned

The chat/tile surface keeps the Phosphor Terminal soul — warm blacks,
cream text, amber accents (`bg-base-*`, `text-text-*`, amber
emphasis) — but RELAXES the debug-view density for the consumer
surface:

- real chat bubbles (rounded, padded, sender-distinct), not log rows;
- more breathing room (`p-3`/`p-4` where the debug view uses `p-1.5`);
- larger primary text than the debug views (`text-sm` body where
  debug uses `text-xs`);
- agent-to-agent inline messages: dimmer (`text-text-400`), smaller
  (`text-xs`), labeled `agent-<id>`, visually subordinate to the
  human↔agent stream;
- monospace stays for ids/code; prose bubbles may use the sans stack.

The bar (user): "you know how to make elegant chat systems and make
them beautiful." The debug view's density rules are untouched.

## 6. Unit ladder — demo-floor first, ≤7 files each

All units are independently landable with explicit file fences and
live proofs. **Registry-stomp caution:** U1 and U3 introduce brand-new
namespaces — NEVER hot-require a new ns into the live pod; both
require a planned pod restart (`bin/seon restart pod`, or fold into
one restart after U3 if landing same-day). U2/U4/U5/U6 edit existing
namespaces (hot-reload-safe via the watcher, restart still the clean
path on demo day).

### U1 — `seon.render.live-tile`: key, twin, welcome, container CSS

**LANDED 2026-06-11 (this unit).** The mechanism. New ns with:
`register!` of the tile key (DECIDED `:seon.render.live-tile/content`,
§2) and the optional `:seon.render/ai` entry on
`:seon.render/html-response`; `render-tile` resolution (delegating to
`seon.render/html-render` — value-or-fn, one dispatch); the error
envelope + human fallback (replacing the silent catch in
`render-agent-tile`); the `welcome` fn (time-aware, purpose-aware);
the `.seon-tile` container-query CSS + wrapper-class convention.

- Files (≤7): `src/seon/render/live_tile.cljs` (new),
  `src/seon/render.cljs` (twin entry on the response schema; tile
  resolution reads the new key first), a CSS asset under
  `resources/public/css/` (container rules; loaded by the pages),
  `test/seon/render/live_tile_test.cljs` (new).
- Touches live pod surface: YES — new ns, pod restart required.
- Live proofs: (1) transact the key with literal hiccup onto a live
  agent → root grid tile morphs to it; (2) wire a fn returning the
  twin map → `render-tile` response carries both keys; (3) wire a
  throwing fn → human tile shows the fallback text, response carries
  the error envelope; (4) unwired agent → welcome renders with
  today's date and a correct time-of-day greeting (read the actual
  HTML, compare wall clock).

### U2 — agent view + route swap (`seon.render.chat`)

**LANDED 2026-06-11.** All five live proofs observed on the running
pod (agent kXQ-2606101814): consumer split at `/agent/<id>`, real
DeepSeek reply bubble via SSE, peer message inline/dim/labeled,
debug view at `/agent/<id>/debug` (SSE at `/agent/<id>/debug/sse`),
grid tap → consumer view. Note: landing exposed and fixed a live
substrate bug — datahike-cljs `get-else` defaults never fire, so
`seon.render.default/recent-messages` dropped every agent-from
message (only `user` rows survived); the label resolution now joins
eid→id maps in Clojure.

The consumer split screen at `/agent/<id>`: left bubbles + input
(reuses the `/chat` endpoint and the chat-form submit pattern), right
the expanded tile (`.seon-tile` at expanded container size). Debug
view moves verbatim to `/agent/<id>/debug`; grid tap target and
cross-links updated; the per-agent SSE listener pushes BOTH views
(same tx-listener, one more morph fragment).

- Files (≤7): `src/seon/render/chat.cljs` (new),
  `src/seon/web/inspector.cljs` (routes, consumer shell, debug route
  move, grid href), `test/seon/render/chat_test.cljs` (new),
  optionally `src/seon/web/serve.cljs` if routing needs a touch
  (likely not — `inspector/route?` owns `/agent/*`).
- Touches live pod surface: YES — new ns, pod restart required (plan
  ONE restart covering U1+U2 when landing together).
- Live proofs: (1) `/agent/<id>` shows bubbles + expanded tile;
  (2) send a message from the input → reply bubble appears via SSE
  without reload; (3) a peer agent `message!`s this agent → the
  exchange appears INLINE, dimmer, labeled `agent-<peer>`;
  (4) `/agent/<id>/debug` is byte-identical in behavior to today's
  inspector; (5) grid tap lands on the consumer view.

### U2b — focus mode (OPTIONAL, after T1/T2 prove out)

Surface 2b: a toggle on the agent view → full-viewport tile + chat
bar (input + last reply); history panel toggles back. Pure layout +
one container size — no new namespace, no agent-facing change,
hot-reload-safe. Build only if the demo floor is solid and time
allows; the toggle state is client-side (signal), nothing stored.

- Files (≤4): `src/seon/render/chat.cljs`,
  `src/seon/web/inspector.cljs`, the CSS asset, tests.
- Live proofs: toggle → tile fills viewport at expanded container
  size, chat collapses to bar with last reply; toggle back restores
  the split; agent's `::ai` twin unchanged in both modes.

### U3 — default root tile

Uncustomized agents' root tile = purpose + ID + last reply nicely
formatted (the agent's last assistant message rendered as text, not
raw message data). Implemented as the welcome fn's compact block plus
a `last-reply` derived read in `seon.render.chat` — the default tile
IS a wired substrate fn, eating the same dogfood.

- Files (≤7): `src/seon/render/live_tile.cljs`,
  `src/seon/render/chat.cljs`, `src/seon/web/inspector.cljs` (grid
  cell sizing if needed), tests.
- Touches live pod surface: edits existing+U1 nses (no new ns).
- Live proofs: root grid shows, per agent: purpose line, id, the
  agent's actual last reply text (compare against the message log);
  compact block only (no expanded chart bleed) at grid size.

### U4 — startup evals at creation: tile wiring + todo registration

`start-agent!` (the ONE boot path) evals the wiring form(s) as the
new agent: define-or-reference the welcome (substrate fn — the form
just transacts the key pointing at it), transact the key. Eval log
carries the real eval(s); resume replays them like any agent eval.

**EXPANDED (user, 2026-06-11): the startup eval block is the home for
ALL agent-specific instance wiring, not just the tile.** Same turn,
multiple forms: (1) the tile wiring above; (2) **todo-system
registration** — `add-section!` of the open-todos section moves from
`substrate-default-ctx`'s static defaults to a real startup eval, so
the agent SEES itself wire its own todo view (and learns
remove-section!/add-section! by example). Anything agent-instance-
specific added later (new default sections, future per-agent wiring)
joins this block — one place, one mechanism, all visible in the eval
log. Context-v4's `<your-entity>` then shows the resulting datoms.

- Files (≤7): `src/seon/client.cljs` (creation path),
  `src/seon/render/live_tile.cljs` (the canonical wiring form as
  data/fn so client + tests share it), `test/` additions.
- Touches live pod surface: edits `seon.client` (hot-reload risky on
  the boot path — restart before demoing).
- Live proofs: (1) create an agent via `/agents/new` → its eval log's
  first entries include the tile wiring eval with real result;
  (2) the new agent's first context shows the `:live-tile` section
  quoting the welcome twin; (3) pod restart → replay keeps the wiring
  (tile still welcome-rendered, no re-seed needed).

### U5 — awareness section + teaching update

The `:live-tile` section in `seon.ctx/substrate-default-ctx`
(priority 28): invokes the wired value against the turn's db,
renders header (wired identity) + body (twin text / verbatim hiccup /
error envelope), renders NOTHING when unwired… except every agent is
wired from creation (U4), so in practice it's always present — the
unwired branch is the correctness floor. Update the capabilities
"Your live tile" block: new key, twin contract, tag-blocks
convention, the rendered-queries steer, no sugar.

- Files (≤7): `src/seon/ctx.cljs`, `src/seon/render/live_tile.cljs`
  (section fn lives with the mechanism; ctx just wires it),
  `test/seon/ctx_*` additions.
- Touches live pod surface: edits `seon.ctx` (the prompt path) —
  re-verify a live turn after landing.
- Live proofs: (1) ctx-preview of a welcome-wired agent contains the
  section with the welcome twin text and the wired fn's name;
  (2) agent transacts literal hiccup onto the key → NEXT turn's
  context shows that hiccup verbatim; (3) throwing fn → section shows
  the error envelope; (4) between-turns check: transact data the tile
  queries AFTER a turn renders → human tile updates now, agent twin
  updates next turn (assert both, in that order).

### U6 — debug overlay

The `⚙ debug` button + keybinding (DECIDE trigger, §1) opening the
debug view as an overlay over `/agent/<id>` without a URL change.
Iframe floor; Esc/button closes.

- Files (≤7): `src/seon/web/inspector.cljs`, tests.
- Touches live pod surface: edits existing ns.
- Live proofs: open `/agent/<id>`, hit the trigger → debug two-pane
  appears live (SSE updating inside it), URL unchanged; Esc returns.

### U7 — measurement (post-demo OK; encode the predicates now)

The gym angle (ties to `gym-upgrade-prd-2026-06-11.md` U1 prompt-blob
predicates):

- **Tile correctness is judgeable via the twin landing in prompt
  blobs**: a scenario predicate asserts the `:live-tile` section is
  present in the captured turn-0/turn-N prompt and that its body
  matches the seeded data (mechanical regex + LLM-judge axis "does
  the twin's description match the seeded counts/weights?").
- **The welcome wiring eval is assertable mechanically**: datalog for
  the creation-time eval entity whose source matches the tile key —
  no judge needed.
- **S-TILE "chart my workouts"** (carried from the original draft,
  updated): human asks "put a chart of my workouts on your tile."
  Predicates: (1) agent entity carries the tile key pointing at an
  agent-authored fn; (2) the rendered tile contains `[:svg`/`<svg`;
  (3) the agent's NEXT context contains the `:live-tile` section
  describing the chart; (4) the reply directs the human to the tile
  rather than pasting an ASCII chart. The falsifiable claim: an agent
  that SEES its surface stops re-describing it in chat.
- Files (≤7): one scenario EDN under `test/seon/gym/scenarios/`,
  driver predicate additions if the prompt-blob harness needs them.

## 7. Out of scope (not contradicted — parked)

- **Interactivity components** (`tile-action` buttons POSTing back
  through `/chat`, forms) — the loop is nearly free on this
  architecture but is NOT in the demo cut.
- **Hiccup sanitization gate** — agent-authored hiccup rendered in
  the human's browser is an XSS surface (`on*` attrs,
  `javascript:` URLs survive `html/->string`'s string escaping).
  MUST land before any untrusted/multi-user deployment; flagged as
  the first post-demo follow-up unit. (The CLJS sandbox is not a
  security boundary; the render boundary is where this belongs.)
- **Chart helpers** (`seon.ui.chart` bar/sparkline) — inline SVG
  hiccup already works with zero new code; helpers are polish.
- **Presence / "when did my human last look"** — would need
  append-only view events; explicitly not in the floor. Revisit only
  when the notify-vs-update judgment is wanted.
- **`:seon.render/label` short-label attr** (#34/P20) — still parked;
  the purpose-derived headline is the no-new-attr default.

## 8. Uniformity / drift findings (follow-up units)

Per the standing uniformity directive — the tile value DELIBERATELY
matches `:seon.render/html` semantics (value-or-fn, late-resolved).
Drift this work exposes, each a candidate follow-up unit:

1. **`:seon.render/html` double duty.** Today the SAME key is both
   the generic per-entity-card render slot AND the agent-tile slot
   (render.cljs `entity-html-sym` → `render-agent-tile`). The
   dedicated live-tile key separates the roles; after U1 the agent
   entity's tile use of `:seon.render/html` should be migrated/
   retired so one key means one thing. Until then `render-agent-tile`
   reads the new key first, old key as fallback — delete the fallback
   in the follow-up (no legacy).
2. **Twin key naming drift**: `ai-render` responses use
   `:seon.render/text`; section twins and the new tile twin use
   `:seon.render/ai`. Two names for "the text twin of a render".
   Converge on one (`:seon.render/ai` is the more-used) — follow-up
   sweep.
3. **The teaching block in `seon.ctx` is prose** describing the tile;
   after U5 it should shrink toward "read `seon.render.live-tile`"
   plus the worked transact — context-v3's code-first direction
   (docstrings on real fns are the durable teaching).
4. **`agent-grid-tile`'s inline fallback** (inspector.cljs:891-896)
   duplicates a mini default tile in hiccup — after U3 the welcome fn
   is the only default; delete the inline fallback.

## 9. Superseded from the previous draft (loss audit)

- **`:seon.render/level` enum + level-aware fns** — replaced by
  container queries over ONE render (DECIDED). No level key, no
  branching renderers.
- **Automatic hiccup→text flatten** as the twin floor — replaced by
  "you see exactly what's wired": fns return the `::ai` twin; raw
  hiccup (and twin-less fn output) shows verbatim. No flatten
  machinery.
- **`set-tile!` sugar** — rejected (raw transact is the one taught
  path).
- **"Friday freeze / U1-teaching-only" timing** — superseded: the
  user calls the full build achievable for the 06-12 demo.
- **Charts/images/actions/sanitization as in-scope content
  vocabulary** — moved to §7 parked/follow-up (sanitization flagged
  as mandatory pre-multi-user).
- **Presence ("watching now" / view events)** — moved to §7 parked.
- Carried forward intact: tile-is-derived-awareness (one wired value,
  two twins — the old "tile is a section" insight, now via the
  composer invoking the wired value), the gym S-TILE scenario, the
  route swap, the purpose-derived default headline, the teaching
  bones (capabilities block + raw transact + the "tile, don't paste"
  doctrine, now expressed as the rendered-queries steer).
