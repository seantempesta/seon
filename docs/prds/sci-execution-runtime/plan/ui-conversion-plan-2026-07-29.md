---
type: prd
status: active
tags: [prd, ui, datastar, render, quarry]
---

# UI conversion — the sliced plan (2026-07-29)

The conversion of the old UI onto the ruled render model, sliced in
shippable order. It consumes
[../research/old-ui-quarry-2026-07-29.md](../research/old-ui-quarry-2026-07-29.md)
(83 pieces, dispositions, and the three highest-value conversions),
the owner rulings of 2026-07-28/29 in [README.md](README.md), the target
in [../../../seon/architecture/ui.md](../../../seon/architecture/ui.md),
and the landed fresh owners (`src/seon/render.clj`,
`src/seon/render/block.clj`, `src/seon/render/web.clj`,
`src/seon/render/walk.clj`, `src/seon/render/root.clj`,
`src/seon/render/agent.clj`, `src/seon/cluster/message.cljc`,
`src/seon/cluster/wake.cljc`, `src/seon/cluster/agent.clj`).

This document sequences only its own slices. `plan/README.md` remains
the one ordering authority for the program; where a ruling and this plan
disagree, the ruling wins.

## The law

> "We can convert it to this ping renderer but I don't want to lose the
> design language and the functions" — and — "DO NOT PORT THINGS
> EXACTLY." (owner, 2026-07-29)

The quarry supplies inventory and design language. Every piece below is
re-derived from the ruled model: namespace-and-distance context is the
organizing principle, kind is the boundary, delivery is a render,
distance is a query, presence is state, and the bar is *write a function
to change it*. A conversion that keeps a piece's old SHAPE while moving
its address is a ported defect, not a conversion.

## 0. The four reconceptions this plan makes

Stated up front, because they are what distinguishes this from a port.

1. **An inbound message is not a domain call, it is tx-data.** The
   quarry's `/chat` handler called an effectful `agent/message!`
   (`48b89dd7d^:src/seon/web/serve.cljs:1602-1648`). Here the fact's own
   family gains a PURE function over a database value — the same shape
   `seon.cluster.message/delivery` already is — and the web boundary is
   the one place that commits. The boundary parses and commits; the
   family decides. Nothing effectful moves to the web tier.
2. **The message bar is a BLOCK, not shell chrome.** The quarry's
   answer was "put the input outside the morph target so a morph cannot
   clobber it" (`48b89dd7d^:src/seon/web/datastar.cljs:625-650`). The
   fresh system reaches the same safety by a stronger route: a bar whose
   render is a pure function of the agent id produces identical bytes on
   every pass, so equality suppression (`web/changed`) never patches it,
   and the caret is never disturbed. All transient state — typed text,
   refusal, pending — lives in Datastar signals, never in the render.
   The prize is the bar: an agent can replace its own input by writing
   one defn, which the shell-chrome answer forecloses. (Owner decision
   D3; the falsifier is named in §1.7.)
3. **The echo is already built.** No transcript is needed to see a sent
   message appear: `seon.render.walk` follows REVERSE refs
   (`walk.clj:203-241,292-315`), a message points at its recipient
   through `:seon.cluster.message/to`, and `agent/namespace-html` at
   distance 1 renders it through `seon.cluster.message/render-html`. The
   commit's render wake is unconditional (`wake.cljc:137-145`). Slice 1
   therefore ships a working conversation with zero new render code.
4. **Focus and rail are blocks at distance, not a surface registry.**
   The quarry's `surface.cljc` materialized compact/expanded faces with
   touch-history selection (C06, U02). That machinery is retired whole:
   the focal panel is one block rendered at distance N, the rail is the
   same units at distance 0-1, and selection is tab-local presentation
   state in a signal. Nothing stored, nothing pinned in the database
   until an owner asks for a durable pin.

---

## Slice 1 — THE MESSAGE BAR

**Priority one: the owner cannot talk to his agents from the browser.**
Today `src/seon/render/web.clj:531-614` dispatches five GETs and has no
state-changing route at all.

### 1.1 The claim this slice settles

Typed text in a browser becomes one message fact with no `from`; its
`to` datom wakes the named agent through the existing routing listener;
the agent's reply and the message itself appear on the page through the
ordinary per-block morph; reconnect repaints the same facts; typed but
unsubmitted text and caret position survive unrelated morphs.

### 1.2 Owned files

| File | Change |
|---|---|
| `deps.edn` | reitit coordinate (`:local/root reference-code/reitit/modules/reitit-core` + `…/reitit-ring`) — owner decision D4 |
| `src/seon/render/web.clj` | the reitit route tree replaces `handler`'s `cond`; the inbound POST handler; the bar's render fn and its block seed entry |
| `src/seon/cluster/message.cljc` | `inbound-tx` — pure tx-data for a message from outside the agent population |
| `src/seon/schema/message.edn` | `:seon.cluster.message/inbound-request` |
| `src/seon/schema/web.edn` | `:seon.render.web/routes`, `:seon.render.web/inbound` |
| `resources/public/css/input.css` | `.seon-bar*` semantic classes |
| `test/seon/render/web_test.clj` | the sealed suite of §1.8 |
| `test/seon/cluster/message_test.clj` | `inbound-tx` properties |

Protected, not to be touched by this slice: `src/seon/render/block.clj`,
`src/seon/render/walk.clj`, `src/seon/cluster/wake.cljc`,
`src/seon/cluster/agent.clj`. The delivery chain is finished; slice 1
adds a producer, not a path.

### 1.3 The route tree

Slice 1 lands the ONE reitit tree, because slice 1 is the first request
where the `cond` is actually wrong: it discriminates on URI prefix only,
so `GET /agent/bob` and `POST /agent/bob/message` are the same branch and
a path parameter is `subs`. Method dispatch, path params, and build-time
conflict detection are exactly what the tree is for
(`reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:121-151,360-404`).

```
["" {:middleware [::same-origin]}
 ["/"                         {:get  ::root-page}]
 ["/data"                     {:get  ::data-drill}]
 ["/feed/:id"                 {:get  ::feed}]
 ["/agent/:id"                {:get  ::agent-page}]
 ["/agent/:id/message"        {:post ::inbound}]      ; slice 1
 ["/agent/:id/call"           {:post ::call}]         ; slice 3
 ["/agents"                   {:post ::birth}]        ; slice 4
 ["/css/*path"                {:get  ::asset}]
 ["/js/*path"                 {:get  ::asset}]]
```

`POST /agent/{id}/message` and not the historical `/chat?agent=`
(owner decision D1). Three reasons: "chat" is not this system's
vocabulary — the fact is a message and the family that owns it is
`seon.cluster.message`; the target is a RESOURCE, and a resource
identified by a query parameter is the shape reitit exists to retire;
and the agent-scoped tree is where `/call` and `/app/{x}` already belong
per `ui.md:551-559`, so one nesting carries route-data (`:seon.route/
owner`, middleware) down to all three.

Same-origin is ONE middleware on the tree root rather than a check per
handler (P14's lesson, kept). The no-match default handler 302s to `/`
(P15, `ui.md:528-535`) — the redirect is route data, not a `cond` arm.
Route DATOMS (`:seon.route/*`) are deliberately NOT in this slice: the
tree is seeded from code today and accretes to facts when an agent first
needs to add `/agent/{id}/app/{x}`. One dispatcher either way.

### 1.4 The fact

`seon.cluster.message/inbound-tx` — pure over a database value,
returning tx-data or a flat error value, and committing nothing:

- resolves `:seon.cluster.agent/id` against the current database value;
  an unknown agent is `::unknown-recipient` — the SAME error kind
  `delivery` already produces for the same condition, not a second one;
- refuses blank content and content over the configured bound as flat
  error values;
- emits exactly one row: `id`, `to` (a lookup ref), `content`, `at` —
  **and no `from`.** Absence is the whole contract
  (`src/seon/schema/message.edn:14-25`): a message with no sender came
  from outside the agent population. No origin enum, no `:human` stamp,
  no hops integer. The chain walk resets here for free
  (`message.cljc:18-42`);
- provenance is transaction metadata only — `:seon.db/process` for this
  process, `:seon.db/user` when a user entity exists. Never copied onto
  the message row.

**Identity is the one genuinely new problem** and the riskiest part of
this slice. Every id in the fresh system is derived from a run
(`message.cljc:193-199`: `<run>-<ordinal>-message-<index>`). A browser
POST has no run: it IS the origin. The recommendation (owner decision
D2) is to derive it from the basis the commit lands at, inside a
`:db.fn/call` transaction function owned by `seon.cluster.message`:
the transaction function runs inside Datahike's serial writer loop, so
its database value is the immediate predecessor basis and no two commits
can observe the same one. The id is then `(str "inbound-" t "-" index)`
— derived, no allocator, no registry, no uuid, and stated in the
dependency's own vocabulary (basis transaction). The falsifying probe is
named in §1.8; if `:max-tx` is not readable inside a `:db.fn/call` the
fallback is to allocate at the boundary and the decision returns to the
owner rather than being invented in the handler (the quarry's own
instruction, `old-ui-quarry-2026-07-29.md:270-272`).

The handler's whole body: parse, call `inbound-tx`, `d/transact` on
success, `204` with no body; on a refusal value, `422` with the refusal
message as text. A 204 emits NO competing morph deliberately (P07's
lesson, kept verbatim in behavior): the ordinary feed paints the result,
so there is exactly one painter.

### 1.5 Delivery — nothing new in the middle

Stated so no implementer adds a step:

```
d/transact  →  wake/route! (the cluster's ONE listener, wake.cljc:132-162)
            →  render wake, unconditional, once per report      → the page
            →  the :seon.cluster.message/to datom's VALUE is the recipient's
               entity id → offer! into THAT agent's mailbox channel
            →  agent/mailbox-step → ::episode → agent/turn-step → the turn
```

No queue, no inbox flag, no acknowledgement, no second channel. A
recipient with no routing entry falls to the armer, which covers the
created-and-messaged-in-one-commit window (`wake.cljc:106-110,151-158`).

### 1.6 The echo

The sent message appears on `/agent/{id}` with no new render code, per
reconception 3. The order of events is worth writing down because it is
the proof that no optimistic client state is needed: the POST returns
204 having committed; the commit's report offers the render wake; the
render proc derives the page at the new basis; suppression finds the
namespace block's bytes changed; the tab's writer patches that one
block. The typed text clears from the input because `$text=''` runs in
the submit expression, not because anything echoed it.

If the owner finds the walk's rendering of a message too flat to read as
a conversation, that is slice 2's problem and not a reason to delay
slice 1.

### 1.7 The bar

One html-only block in the ordinary agent's seed set (`render/agent.clj`
`blocks`), `:seon.render.block/band :anchor` so it sorts above the
dynamic content, and a render that is a pure function of the agent id.

Design language carried from `48b89dd7d^:src/seon/web/datastar.cljs:625-667`,
reconceived:

- one dense terminal line: dark field, amber send action, and **the
  target named in the placeholder** ("message agent bob …") — the
  quarry's best small idea, because it makes the destination legible
  without a label;
- Datastar form-mode POST: `data-on:submit` runs
  `@post('/agent/<id>/message', {contentType:'form'}); $text=''`, the
  field is `data-bind`'d, `required` blocks a blank send client-side,
  `autocomplete="off"`, `autofocus`;
- the utility-class strings are DROPPED. `input.css` gains `.seon-bar`,
  `.seon-bar-field`, `.seon-bar-send` built from the landed tokens
  (base-950 field on base-900 bar, `--color-signal` action, `text-xs`
  mono) — the fresh direction stated at D10: Clojure says what a thing
  is, CSS says how it looks;
- refusal is a SIGNAL, never a render and never a fact: the
  `data-on:datastar-fetch` lifecycle idiom quarried from L11 sets
  `$refusal` on an error response, and a `data-show`/`data-text` span
  displays it. This is what keeps the bar's bytes constant — the moment
  refusal text entered the render, the bar would morph and the caret
  would move.

**The falsifier for reconception 2** (and the reason it is a decision
and not an assumption): across a burst of morphs driven by unrelated
commits, the bar's surface id must never appear in the patch set. If it
does, the equality argument is wrong and the bar moves to the shell as
the quarry had it — a one-line change, since the shell already places a
non-morphed sibling for the feed opener (`web.clj:161-168`).

### 1.8 Sealed suite — seeds 2026072901-2026072906

Wire-level where the claim is wire-level; the seeds continue the
series established by the F2 suite in `test/seon/render/web_test.clj:407`.

1. `inbound-tx-omits-from-test` — the committed row has `to`, `content`,
   `at`, `id` and NO `:seon.cluster.message/from`; asserted on the datoms,
   because presence is the contract.
2. `inbound-identity-is-unique-under-burst-test` (seed 2026072901) — N
   inbound commits in sequence yield N distinct ids; the probe that
   falsifies D2's basis derivation before any handler is written.
3. `inbound-wakes-the-named-agent-test` (seed 2026072902) — commit an
   inbound row against a routed cluster; the recipient's mailbox channel
   receives a wake and no other agent's does. Falsifies "delivery needs
   something in the middle".
4. `blank-and-unknown-are-values-not-throws-test` — three refusals
   (blank, oversized, unknown agent) come back as flat error values with
   their kinds; `inbound-tx` never throws and never returns rows.
5. `the-message-appears-on-the-page-wire-test` (seed 2026072903) — WIRE
   LEVEL: open a feed for the agent, commit an inbound message, and
   assert the patch set contains the namespace block carrying the
   message text. The echo claim, measured on the socket.
6. `the-bar-is-never-patched-test` (seed 2026072904) — WIRE LEVEL: drive
   ten unrelated commits against an open feed; the bar's surface id is
   absent from every patch set. §1.7's falsifier.
7. `route-conflicts-are-build-time-test` — the tree's method/path
   conflict detection refuses a duplicate at construction, which is the
   class the `cond` shadowed silently.
8. `a-refusal-emits-no-morph-test` — a 422 response carries no SSE
   patch; only the feed paints.

Deleted in the same commit as they are superseded: the `cond`-shaped
assertions in `web_test.clj:300-315` that pin URI-prefix dispatch.

### 1.9 The live proof — THIS IS THE ACCEPTANCE

The owner sends a message from his own browser and the agent answers.
Concretely: start the cluster (`clojure -M:dev`, the view logs its
derived URL — `cluster.clj:566,590`), open `/agent/{id}`, type into the
bar, press send, and watch the message and then the reply arrive without
a reload. Then: reload mid-conversation and see the same page (reconnect
= repaint); type text, let an unrelated commit land, and see the text
and caret survive; send to a stale agent id and read the refusal at the
input.

No slice-1 test result substitutes for this. A green suite with a bar
the owner cannot type into is a failed slice.

---

## Slice 2 — THE AGENT TRANSCRIPT

`/agent/{id}` becomes the conversation view. This subsumes the old N4
package-5 intent; there is no separate transcript package.

### 2.1 The shape

The page is three blocks and nothing else:

- `:agent-header` — identity and derived state. Presence is the state:
  an agent with a `:seon.cluster.agent/run` is running, and there is no
  status attribute to read (`render/agent.clj:74-78`). Dot plus text,
  never a pill (C02, and the landed root state-dot CSS at D14).
- `:transcript` — the conversation, ordered. Messages, runs, receipts,
  and errors as the FAMILY renderers already produce them
  (`message/render-html`, and each family's own twin as it lands). The
  block's job is SELECTION AND ORDER, not presentation: it queries the
  units addressed to and authored by this agent, orders them, and hands
  each to the router. No transcript-specific renderer, no second
  registry — the quarry's C09-C12 hierarchy survives as CSS on the
  family entries, not as a bubble component.
- `:focus` — the focal surface, a layout emitting `(slot …)` /
  `(entity-slot …)` holes. The rail is the SAME units at a shorter
  distance, in a second slot.

### 2.2 Focal surface and rail, reconceived as distance

The quarry's three-column layout (C03-C05) is kept as LAYOUT and dropped
as MECHANISM. The focal panel renders one unit at the page's distance;
each rail card renders a neighbour at distance 0-1 — which is precisely
"distance 0 = name only, 1 = signatures and docstrings" from the
resolution ruling. Compact-versus-expanded stops being two faces a
surface materializer chooses between (C06, C13) and becomes ONE renderer
called with different hop budgets. That is the whole conversion: the old
system stored two projections of one unit; the new one spends a number.

Selection is tab-local: a signal names the selected unit, the rail card
sets it, the focal slot reads it. Nothing stored. A durable pin is
explicitly OUT of this slice — `ui.md:618-625` describes one, and it
needs the web-session facts that do not exist in fresh `src/`; naming it
here would be inventing a contract a consumer must not invent.

### 2.3 Order

Order is derived from committed facts, ascending entity id within one
basis — which IS commit order for facts committed in sequence and is
already the root list's rule (`render/root.clj:143-145`). No stored
sequence number, no `:at` sort that ties on a coarse clock.

### 2.4 Owned files, accretions, suite

Owned: `src/seon/render/agent.clj` (three renders and the seed vector),
`resources/public/css/input.css` (`.seon-transcript*`, `.seon-rail*`,
`.seon-focus*`), `test/seon/render/agent_test.clj`.

Schema accretions: NONE. Every fact this slice renders already exists,
and the presence doctrine means it stores nothing to remember what is
selected. If this slice finds itself wanting an attribute, that is the
signal that it has drifted back into the surface-registry shape.

Sealed suite — seeds 2026072907-2026072909:

1. `transcript-orders-by-commit-test` — one database value, two
   derivations, one order.
2. `transcript-renders-through-family-lenses-test` — a message, a run,
   and an error in one transcript are each projected by their OWNER's
   declared html symbol; re-evaluating one family's defn changes the
   next render and no other entry.
3. `rail-and-focus-are-one-renderer-at-two-distances-test` (seed
   2026072907) — the same unit rendered at distance 0 and distance N
   comes from one symbol; the compact form is a prefix of the expanded
   form's facts, not a different function.
4. `selection-survives-a-morph-test` (seed 2026072908) — WIRE LEVEL: a
   commit that repaints the transcript does not change the selected
   focal unit, because selection is a signal.
5. `an-agent-with-nothing-to-say-renders-an-empty-transcript-test` —
   omission keeps the identified wrapper so a later message morphs into
   it (ruling 1, 2026-07-28).

Live proof: a two-turn conversation reads as a conversation — the
owner's message, the agent's reply, the evals it ran as quiet activity
rows, and one failure disclosed calmly rather than as a core-fault card.
Selecting a rail card moves the focal panel without disturbing the bar.

---

## Slice 3 — AGENT-AUTHORED CONTROLS

An agent's html render may contain controls; clicking one crosses ONE
guarded boundary and becomes a durable fact. This is the capability-door
discipline applied to the browser: the control is not an authority, the
POST boundary is (L12's lesson, and the reason the old gate is the one
piece of `reactive/` worth keeping conceptually).

### 3.1 The shape

- **`my.canvas` constructors survive as PURE FUNCTIONS.** `button`,
  `input`, `select`, `toggle`, `form` (`src-old/my/canvas.cljc:214-303`)
  are already values-in/hiccup-out and already carry qualified field
  keywords. They convert nearly unchanged in SHAPE and entirely changed
  in CLASSES: utility strings out, `.seon-control*` semantic classes in.
- **The effectful half of the old `my.canvas` does NOT convert.**
  `show!`, `clear!`, `pinned`, `state`, `save!`
  (`src-old/my/canvas.cljc:73-212`) are in-eval runtime mutation — the
  old-engine residue the standing goal names explicitly. An agent
  changes what the human sees by writing a render fn and installing a
  block, which is the bar restated: write a function to change it.
- **One render-time postwalk** rewrites `:on-click`/`:on-submit` slots
  carrying a fn-call or fn-ref into a Datastar `@post` at
  `/agent/{id}/call` (L10). Agent code authors ordinary Clojure values
  and never a URL. Bare symbols resolve in the authoring namespace;
  qualified symbols pass through.
- **One POST handler** validates before it invokes anything: the route
  agent is live, the named function is public, its committed schema
  admits the argument map, and its source transaction was authored
  through the REPL process (`ui.md:564-577`). Refusal precedes invoke.
  Args stay data.
- **The admitted action is a FACT**, and the outcome is an ordinary
  block query. The HTTP response is never an execution channel
  (`ui.md:44-57`): the handler commits a pending interaction and returns
  204; the agent's own graph executes it; the result commits; the page
  re-derives. Reconnect shows the same outcome because it was always
  derived from facts.
- **Lifecycle is visible at the control** (L11): stable pending/error
  signal names, `data-indicator`, disabled, `aria-busy`, and a visible
  failure line with retry guidance. Carried nearly verbatim in behavior,
  restyled.

### 3.2 What this slice depends on and must not invent

It needs a fact shape for a pending action and its outcome, and it needs
the agent graph to acquire one. Neither exists in fresh `src/` today. If
that owner has not landed when this slice is dispatched, the slice
BLOCKS on it and names it — it does not define an interaction attribute
at the web boundary. That is the consumer-invents-the-contract failure
the program ledger exists to prevent.

### 3.3 Owned files, accretions, suite

Owned: a new `src/seon/render/control.clj` (constructors and the
postwalk — the confinement guardrail says machinery lives in the render
family), `src/seon/render/web.clj` (the `::call` handler),
`src/seon/schema/control.edn`, `resources/public/css/input.css`,
`test/seon/render/control_test.clj`.

Schema accretions: the interaction request/outcome shapes, owned by
whichever namespace owns the fact — NOT by the render family. Presence
doctrine: a pending action has no terminal facts; an outcome is the
presence of a result or an error, never a stored status enum.

Sealed suite — seeds 2026072910-2026072912:

1. `a-control-is-a-value-test` — the constructors are pure; the same
   request yields the same hiccup.
2. `the-postwalk-rewrites-only-handler-slots-test` — ordinary attributes
   pass through untouched; a fn-call becomes exactly one `@post`.
3. `the-gate-refuses-before-it-invokes-test` (seed 2026072910) — a
   private fn, a schema-violating argument map, and a fn whose source
   was not authored through the REPL process are each refused, and the
   invocation counter is zero in all three.
4. `the-response-is-not-the-result-test` — the POST returns 204 with the
   action still pending; the outcome arrives on the feed.
5. `the-outcome-survives-reconnect-test` (seed 2026072911) — WIRE LEVEL:
   a tab that disconnects before the outcome commits sees it on
   reconnect, because it was derived.

Live proof: an agent writes a render fn containing a button, installs it
as a block, and the owner clicks it in his browser; the action's outcome
appears without a reload, and a deliberately broken handler shows its
failure at the control rather than blanking the page.

---

## Later slices, and what is retired

### Slice 4 — agent birth from root

`POST /agents` in the same tree, and a creation surface on `/`. Quarry:
P03, P06, P08, plus C01's persistent header (which becomes an ordinary
shared block, not a fixed utility string — root's landed masthead is not
replaced). Blocked on the fresh agent-birth transition: `creation-tx`
exists (`cluster/agent.clj:81-93`) but atomic birth with an optional
purpose and first message is not proven. Do not call the old
`start!`/`delegate!`.

### Slice 5 — debug as an ordinary page

Quarry P10. A seeded route selecting a debug block page on the same
block/page/feed path: exact prompt, per-block token breakdown, receipts,
errors, timings — composed from family renderers. No debug feed, no page
cache, no provenance-routed interest.

### Slice 6 — the family rendering library

Quarry R10-R15, C17, U04. Server-side Markdown, Clojure source
highlighting, and function/namespace/schema/test family defaults, ONE
family at a time, as demand reaches them, each through its schema's
`:seon.render/ai` and `:seon.render/html`. Never batch-ported. C17's
rounded badge is replaced by the landed dot/text language.

### Slice 7 — CSS and asset closure

Quarry D06 (prune motion to what fresh pages can emit) and D15 (keep the
drill rules and the font; delete the client highlight, debug, Scittle,
and reactive-demo scripts once reference searches show no consumer).

### Retired, with reasons

Beyond the quarry's own DEAD column, this plan retires the following
outright rather than scheduling them:

| Piece | Why it does not convert |
|---|---|
| C06, R17 (surface materializer, view-unit ids) | Compact/expanded is now one renderer called at two distances; `surface-id` is the one derivation. |
| U02 (touch-history canvas selection) | Implicit machinery choosing what the human sees. Selection is a slot and a signal; a durable pin is an owner decision, not a heuristic. |
| U03 (auto-run render-fn blocks) | Hidden discovery. A block is installed, or it does not exist. |
| L04, L14 (heartbeat, JVM feed mailboxes) | The mult/sliding-1 pipeline owns loss semantics; a second mailbox is a second truth. |
| L06, L07 (subscription registry, feed identity) | One registration per block; a tab is a tap and a virtual thread. |
| C15 (canvas greeting machinery) | The RULE survives as a design token — an empty state teaches the next action — and it is already landed in root's empty lists. The pinned/default resolution machinery does not. |
| `my.canvas` `show!`/`clear!`/`pinned`/`state`/`save!` | In-eval runtime mutation: old-engine residue by the standing goal's own definition. |
| P05's `/chat?agent=` wire | Retired with its name in slice 1; "chat" is not this system's vocabulary and a resource is not a query parameter. |

---

## The design language carried over

Ten tokens. Everything else in the quarry's D-series has already landed
and is extended, never re-themed.

1. **The dense one-line bar**: dark field, amber action, target named in
   the placeholder. (P05)
2. **Direction hierarchy in the transcript**: the human right/amber, the
   agent left/cream, a peer subordinate, a system line calm and centered
   — as CSS on family entries. The oversized rounded bubble is dropped.
   (C09, C10)
3. **State is a dot plus text**, never a pill or a badge. (C02, C17,
   D14)
4. **One focal panel, subordinate previews**: bordered warm-black,
   contained scrolling, a small label strip over a clipped preview that
   cannot capture events. (C03-C05)
5. **Compact activity, deliberate disclosure**: one quiet technical row
   per eval; `details`/`summary` is the density tool; an ordinary
   failure is calm prose, not a core-fault card. (C11, C12)
6. **The empty state teaches the next action.** (C15, and root's landed
   empty lists)
7. **Lifecycle is visible at the control**: pending indicator, disabled,
   `aria-busy`, a failure line with retry guidance. (L11)
8. **Qualified field signals**: the agent writes `::field` keywords; the
   adapter derives stable signal names. (C16, L10)
9. **The morph-safety rules**: transient state in signals and never in
   DOM attributes; the feed opener a hidden sibling of the morph
   targets; and — new here — a constant render is never patched. (L08,
   L09)
10. **Motion identifies change**, it does not decorate a transition.
    (D06)

---

## Owner decisions

| # | Decision | Recommendation | Cost of the alternative |
|---|---|---|---|
| D1 | The inbound route's name | `POST /agent/{id}/message` | `/chat?agent=` keeps a vocabulary we do not use and puts the resource in a query parameter; a bare `/message` loses the nesting that `/call` and `/app/{x}` need. |
| D2 | How an inbound message gets its id | Derive from the basis inside a `:db.fn/call` owned by `seon.cluster.message`: `inbound-<t>-<index>` | A uuid is honest but underived and unrelated to anything; a boundary allocator is a new mechanism for one row. Falsified by the §1.8 probe BEFORE the handler is written. |
| D3 | Is the message bar a block or shell chrome? | A block, with the constant-render argument and its falsifier | Shell chrome is provably safe but forecloses "write a function to change it" for the single most-used surface in the system. |
| D4 | Land reitit in slice 1? | Yes — one coordinate from the vendored `reference-code/reitit` | Deferring means the first method-discriminated route lives in the `cond`, which is the dispatcher the quarry told us not to grow twice. |
| D5 | Durable focal pin (slice 2) | Defer; selection stays tab-local | A stored pin needs the web-session facts that do not exist in fresh `src/`; inventing them at the render tier is the consumer-invents-the-contract failure. |

## Name table

| Name | Meaning | Grounded against |
|---|---|---|
| `POST /agent/{id}/message` | the one inbound human-message boundary | `ui.md:551-559` route family; `:seon.cluster.message/*` |
| `seon.cluster.message/inbound-tx` | pure tx-data for a message from outside the agent population | sibling of the landed `delivery` (`message.cljc:201`) |
| `:seon.cluster.message/inbound-request` | what that function is asked | `message.edn`'s `delivery-request` shape |
| `:seon.render.web/routes` | the reitit route tree value | reitit's own `router`/route-data vocabulary |
| `:message-bar` | the bar block's `:seon.render.block/name` | `block/surface-id`, one keyword in three roles |
| `:transcript`, `:agent-header`, `:focus` | slice 2's three block names | `render/block.clj` seed vectors |
| `seon.render.control` | slice 3's constructors and the handler-slot postwalk | confinement guardrail (2026-07-28 close) |
| `inbound` | a message whose origin is outside the agent population | the schema's own words at `message.edn:14-25` |

Deliberately NOT introduced: "chat", "bubble", "surface registry",
"mailbox" (as a UI noun), "optimistic update", "compact face".

## Orchestrator review points

1. **Before slice 1 is dispatched** — D1-D4 answered. D2 in particular:
   the identity probe is cheap and falsifies a design, so it runs first.
2. **After the reitit tree, before the POST handler** — review the diff
   for exactly one dispatcher and no surviving `cond` arm.
3. **After slice 1's live proof** — the owner types into the bar. This
   is the gate for everything after it; a slice-2 dispatch before it is
   out of order.
4. **Before slice 2 touches `render/agent.clj`** — that file is also the
   pilot's owner (`namespace-ai`). Confirm no other lane holds it.
5. **Before slice 3** — confirm the interaction fact owner exists. If it
   does not, slice 3 blocks and says so rather than defining attributes
   at the web boundary.
6. **At every slice's end** — the reconciliation the repository requires:
   compare against the program ledger, update this file's state, and
   name the next dependency-ready refill.
