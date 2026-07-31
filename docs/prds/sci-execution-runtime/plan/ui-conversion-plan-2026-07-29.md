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

[Partially superseded by the 2026-07-31 rulings: this plan's block SEED
SETS and `:seon.render.block/band` ordering are the retired stored-membership
contract. Membership is now the derived entity walk and order is the naive
last-change transaction basis. The slices' page structure, morph targeting,
grammar refusal, and route work are unaffected.]

**Revised 2026-07-29 after falsification**
([../research/ui-plan-falsification-2026-07-29.md](../research/ui-plan-falsification-2026-07-29.md),
`8a08a211f`). Slice 1's four plan-changing claims — the reverse-ref echo,
the constant-bar suppression, the basis-derived identity, and the outside
episode reset — were each attacked directly and survived; slice 1 is
SEALED and implementing, and nothing in it changes here except the
reitit alignment of §1.2, §1.3, and §1.8 required by R-1. Slice 3 was
REJECTED FOR SEAL (SB-1) and is redesigned in place below. R-1 (D4) and
R-2 (the refusal surface) are folded.

**Seal (orchestrator, 2026-07-29): SLICE 1 SEALED with two falsification
corrections — the single POST route is hand-rolled in web.clj's existing
routing (reitit deferred; D4 answered: not for one route), and the
identity burst probe runs first exactly as planned. Slices 4-7 and the
retirement table are accepted. SLICE 3 IS BLOCKED pending revision: the
falsifier proved agent-authored hiccup can emit raw form/Datastar action
attributes that bypass the callback gate and POST directly — the render
pipeline must make ungated actions unrepresentable (sanitize/refuse at
the one place agent hiccup is admitted), not merely gate the blessed
path. Slice 2 may proceed after slice 1's live proof.**

**Revision landed (2026-07-29, this lane): slice 3 redesigned in §3.0-3.5
— the unrepresentability mechanism, the provenance seam, the two
smuggling paths the probe did not reach (`raw` and the literal
declaration arm), the property, and the 16 adversarial cases. Awaiting
re-seal. ONE DELIBERATE DIVERGENCE from the seal note's wording, flagged
for veto: the redesign REFUSES and does not SANITIZE. A stripped
attribute silently renders something other than what the agent asked
for and teaches it nothing, which is the symptom-side containment layer
the repository forbids; refusal reuses the landed error-card path
(`block.clj:412-426`) and the agent reads the refusal in its own
context. If the owner wants stripping, it is a one-line change at the
same seam.**

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

## 0. The five reconceptions this plan makes

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
4. **An ungated action is unrepresentable, not merely refused.** (Added
   2026-07-29 by SB-1.) The quarry gated a route and let every other
   attribute through; that gates the door of a building with no walls.
   The conversion puts the boundary where authored hiccup is ADMITTED,
   as a closed grammar, and orders it before the one rewrite that may
   produce an action — so the gate protects the only expressible path
   rather than the most likely one. See §3.0.
5. **Focus and rail are blocks at distance, not a surface registry.**
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
| `src/seon/render/web.clj` | one exact method-discriminated branch in the existing `handler`; the inbound POST handler; the bar's render fn and its block seed entry |
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

### 1.3 The route — in the existing dispatcher (revised, R-1)

**Reitit is DEFERRED.** The first draft made the route tree a slice-1
dependency; the falsifier showed that overstated
(`ui-plan-falsification-2026-07-29.md:146-174`). `seon.render.web/handler`
is already the one Ring dispatcher and already receives `:request-method`
alongside `:uri` (`web.clj:531-614`) — it ignores the method only because
every live route is a GET. One exact branch ahead of the `/agent/` GET
prefix expresses this route correctly:

```clojure
(and (= :post (:request-method request))
     (= (str "/agent/" target-id "/message") (:uri request)))
```

That strengthens the existing dispatcher in place; it does not add a
second one. Reitit remains the target router and lands when `/call`,
nested route data, and capability middleware make the tree pay for
itself — slice 3 is where that case is real. The target shape, unchanged
as a target:

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
identified by a query parameter is the shape the route tree exists to
retire; and the agent-scoped path is where `/call` and `/app/{x}`
already belong per `ui.md:551-559`, so one nesting will carry route-data
(`:seon.route/owner`, middleware) down to all three when the tree lands.

Same-origin is ONE check applied to every state-changing branch, written
once (P14's lesson, kept), and it becomes tree middleware unchanged in
meaning when reitit arrives. **Same-origin is a necessary and grossly
insufficient control** — SB-1 below is precisely an attack that arrives
same-origin. The no-match redirect to `/` (P15, `ui.md:528-535`) is
unchanged. Route DATOMS (`:seon.route/*`) are not in this slice.

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
7. `the-inbound-route-is-method-discriminated-test` (revised, R-1) — a
   `GET /agent/bob/message` does not reach the inbound handler and a
   `POST /agent/bob` does not reach the page handler; the exact-URI
   branch does not match `/agent/bob/message/extra` or
   `/agent/bob/messages`. This is the class the prefix `cond` shadowed,
   asserted directly rather than delegated to a router's conflict
   detection.
8. `a-refusal-emits-no-morph-test` — a 422 response carries no SSE
   patch; only the feed paints.
9. `a-cross-origin-inbound-is-refused-test` — the same-origin check
   applies to the state-changing branch. It proves the check runs; it
   proves nothing about SB-1, which arrives same-origin by construction.

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

> **REDESIGNED 2026-07-29 after SB-1.** The first draft gated the blessed
> path — a postwalk that rewrites recognized Clojure handler slots into a
> `@post` at `/agent/{id}/call`, and a capability gate on that route —
> and explicitly required "ordinary attributes to pass through
> untouched". The falsifier showed that is not a boundary at all: the
> fresh Hiccup grammar accepts every attribute map without classifying
> browser behavior (`hiccup.clj:89-147`) and the serializer emits them
> (`hiccup.clj:371-391`), so an authored render can simply emit
> `[:form {:method "post" :action "/agent/victim/message"} …]` or a raw
> `data-on:submit` and POST straight at slice 1's inbound route. The
> human clicks; the browser supplies a same-origin request; the row
> commits with no `from` and is indistinguishable from a human message.
> **The callback gate cannot refuse a request it never receives.**
> Slice 3 is therefore no longer "add a gated route". It is: make an
> ungated action UNREPRESENTABLE in authored output, and the gated route
> is what remains once nothing else can be expressed.

### 3.0 The unrepresentability mechanism

Agent-authored hiccup is admitted against a CLOSED grammar — an
allowlist of presentational tags and attributes carrying no browser
execution or network authority — so no action-bearing attribute can be
present in authored output at all; anything outside the allowlist is
refused loudly as that block's error card, never stripped. The trusted
handler-slot rewrite runs strictly AFTER that refusal, and it is the
only code in the system that emits an action attribute — so every action
attribute reaching a browser was produced by the rewrite, targets
`/agent/{id}/call`, and passes the gate, as an ordering invariant rather
than an inspection.

That two-phase ordering is the whole argument, and it is worth stating
as a sequence because its soundness is entirely in the order:

1. **Refuse.** The authored value is checked against the closed grammar.
   Any tag or attribute outside the allowlist refuses the block. At this
   instant the tree provably contains ZERO action-bearing attributes.
2. **Rewrite.** The trusted postwalk converts declarative Clojure
   handler slots — VALUES like `:on-click (list handler data)`, never
   strings — into Datastar `@post` attributes at `/agent/{id}/call`.
3. **Therefore.** Since (1) left none and (2) is the only producer,
   every action attribute in the output came from (2). No inspection of
   the rewritten tree is needed to know its provenance; a revalidation
   pass is still specified in §3.4 as a cheap standing assertion that
   the invariant holds, not as the thing that establishes it.

This is the same shape as the landed path-traversal refusal
(`web.clj:521-522`): the path must MATCH a conservative pattern, so `..`
never reaches `io/resource` at all. Refuse by construction, do not
sanitize. An allowlist is not the forbidden hand-list — a hand list
enumerates the BAD and drifts as attackers find the next attribute; a
closed grammar enumerates the GOOD and fails closed on everything
nobody thought of, which is exactly the property SB-1 needs.

**Refuse, do not strip.** A stripped attribute silently renders
something other than what the agent asked for, teaches the agent
nothing, and is the symptom-side containment layer this repository
forbids. The refusal needs no new mechanism: `block/surface` already
returns a flat `:seon.error/value` for output that is not hiccup, and
the block paints its own error card and spares the page
(`block.clj:412-426`). The authored-grammar refusal is one more arm of
that same check, naming the offending tag or attribute so the agent's
next render fixes it — and the agent reads that error through its own
context, which is how it learns to write a handler slot instead.

### 3.0.1 Who is "authored", computed and not named

Provenance is COMPUTED at the one projection-invocation seam the ruling
already establishes — compiled Var or SCI Var, same result union
(README ruling 3, 2026-07-28). The router invokes the projection
(`render.clj:145-161`); the seam labels the result with which kind of
Var produced it. A namespace-prefix or symbol-list rule would be the
hand-list class (R34) and is refused.

Today every projection is a compiled Var, so no agent-authored html
reaches a page at all — **slice 3 is the slice that introduces it**,
which is precisely why the boundary must exist before the first authored
render is admitted and not after.

Two smuggling paths follow from the same seam and must be closed in the
same change, because both bypass the grammar rather than violating it:

- **the literal arm.** `render/render` treats a non-symbol declaration
  as its own output (`render.clj:137-143`), so a unit whose
  `:seon.render/html` IS a literal hiccup vector never invokes anything.
  A literal is inert but not trusted: when the unit's provenance is
  authored, the literal is authored output and takes the authored
  grammar. (The durable slot is a qualified symbol only —
  `ui.md:118-123` — so this cannot arrive from the database; it can
  arrive on a runtime unit.)
- **`raw`.** `hiccup/raw` bypasses escaping entirely
  (`hiccup.clj:80-83,139`), so one raw string is a complete escape from
  any attribute-level grammar. Authored output may not contain a `raw`
  value, full stop. Trusted core renderers keep it.

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
- **The closed authored grammar** of §3.0 runs FIRST, at
  `block/surface`'s existing html check. Presentational tags and
  attributes only; no `raw`; the literal arm included. A refusal is that
  block's error card.
- **One render-time postwalk** then rewrites `:on-click`/`:on-submit`
  slots carrying a fn-call or fn-ref into a Datastar `@post` at
  `/agent/{id}/call` (L10). Agent code authors ordinary Clojure VALUES
  and never a URL or an attribute string. Bare symbols resolve in the
  authoring namespace; qualified symbols pass through. The first draft's
  "ordinary attributes pass through untouched" is DELETED — it was the
  sentence SB-1 walked through.
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

The closed grammar of §3.0 is NOT one of those dependencies: it depends
on nothing unlanded, and it is the precondition for admitting the first
authored render rather than a consequence of it. If the interaction
owner slips, the grammar can still land alone — and should, because
SB-1's exposure begins the moment authored html reaches a page, not the
moment controls work.

### 3.3 Owned files and accretions

Owned: `src/seon/render/hiccup.clj` (the closed authored grammar,
colocated with `hiccup?` because it is the same kind's grammar and one
namespace owns it), `src/seon/render/block.clj` (the provenance-selected
arm of the existing html check), `src/seon/render.clj` (the seam's
provenance label and the literal arm), a new
`src/seon/render/control.clj` (constructors and the postwalk),
`src/seon/render/web.clj` (the `::call` handler and, by D4, the reitit
tree that now pays for itself), `src/seon/schema/control.edn`,
`resources/public/css/input.css`, `test/seon/render/control_test.clj`,
`test/seon/render/hiccup_test.clj`.

This slice touches `block.clj`, `render.clj`, and `hiccup.clj`, which
slices 1 and 2 deliberately protect. It therefore may not run
concurrently with them; see review point 5.

Schema accretions: the interaction request/outcome shapes, owned by
whichever namespace owns the fact — NOT by the render family. Presence
doctrine: a pending action has no terminal facts; an outcome is the
presence of a result or an error, never a stored status enum.

### 3.4 The property the sealed suite must prove

> **No sequence of agent-authored hiccup reaches a state-changing route
> except through the gate.**

Stated so it is mechanically checkable: for every authored value `v`,
the bytes `web/surface-html` produces for `v` contain no element or
attribute capable of issuing an HTTP request or executing script, EXCEPT
action attributes emitted by the trusted rewrite, whose target is
exactly `/agent/{id}/call` for the page's own agent. Equivalently, and
this is the form the suite asserts: **driving the serialized page — by
clicking every clickable node, submitting every form, and firing every
load-time hook — produces requests to `/agent/{id}/call` and to no other
state-changing route.**

Both rails, on every adversarial case: (a) the offending construct never
serializes into executable bytes, and (b) the request count at every
state-changing route other than `/call` is zero. Plus one positive
control, so the suite cannot pass by refusing everything: a recognized
Clojure handler slot still reaches the guarded `/call` boundary and
still executes.

#### 3.4.1 The adversarial cases, enumerated

Seeds 2026072913-2026072916. Each is a crafted authored value driven
through the real admission pipeline and the real serializer.

**Direct action authority**

1. Raw Datastar action: `{(keyword "data-on:submit") "@post('/agent/victim/message', …)"}` — the falsifier's exact probe.
2. Native form: `[:form {:method "post" :action "/agent/victim/message"} …]` — needs no Datastar at all.
3. `formaction` on a submit button, overriding an enclosing trusted form's target.
4. Inline DOM handlers: `:onclick`, and `:onerror` on an `[:img]` guaranteed to fail loading.
5. `[:a {:href "/agents"}]` and `:href "javascript:…"` — navigation and script as attribute values.

**No-human-interaction paths** (the worst class: these fire on paint)

6. `data-init` and load-time Datastar hooks — the very mechanism the shell uses for the feed opener (`web.clj:165-168`). An authored `data-init` POSTs with no click at all.
7. `[:meta {:http-equiv "refresh" …}]`, `[:iframe {:src …}]`, `[:img {:src …}]` as a request-on-paint beacon.
8. `[:script]`, `[:object]`, `[:embed]`, `[:base]`, `[:link]`, and `[:svg [:script]]` / `[:svg [:use {:href …}]]` — SVG carries its own script and fetch surface.

**Escapes from the grammar rather than violations of it**

9. `hiccup/raw` carrying a complete `<form>` or `<script>` — one string defeats any attribute-level check (§3.0.1).
10. The literal arm: a unit whose `:seon.render/html` is a literal hiccup vector rather than a symbol (§3.0.1).
11. Smuggling through the other kind: an authored `:seon.render/ai` string that a later renderer embeds as html.

**Evasion of the checker itself**

12. Tag and attribute casing and type: `["FORM" …]`, `:ONCLICK`, string attribute keys `"data-on:submit"`, namespaced keys `:data/on-click`.
13. Tag shorthand: `:form#x.y`, so the allowlist must apply to the PARSED tag name and not the literal keyword.
14. Placement: the offending node deep in a tree, inside a `for` fragment, or as a child of a trusted wrapper — the check is over the whole tree, not the root.
15. `:style` as a string rather than a map, carrying `url(…)`. Authored `:style` is admitted as a MAP of scalars only; the string form is refused. A residual `url()` inside a map value is a NOTE for the owner, not a blocker.

**Injection through the blessed path**

16. A handler slot whose argument DATA, once encoded into the action attribute, closes the quoting and appends a second expression — the rewrite's output must be unforgeable by its input.

#### 3.4.2 The gate's refusal surface (R-2)

For a request that does reach `/agent/{id}/call`, the suite asserts every
refusal class in the falsifier's table
(`ui-plan-falsification-2026-07-29.md:185-197`), each with BOTH rails —
invocation count zero AND no pending interaction fact committed:

| boundary | refused condition | expected |
|---|---|---|
| origin | cross-origin state-changing request | refused before handler |
| descriptor parse | missing route agent id or function symbol | 400 |
| route agent | missing, terminated, or not live | 403 |
| function row | unknown, private, or not registered | 403 |
| source provenance | no agent author, or not from the REPL process | 403 |
| source identity | missing committed fingerprint or incomplete schema | core-unavailable, never invoke |
| argument codec | malformed, non-vector, or code-shaped values | 422 |
| exact contract | args violate the committed schema, incl. stale source/schema mismatch | 422 |
| durable admission | the pending interaction cannot be committed | unavailable, no invoke |

**An ownership-equality check must NOT be added.** Public agent-authored
functions are deliberately shared cluster capabilities, so caller and
original author may differ (`ui.md:564-577`, and R-2 says so
explicitly). The gate proves capability, never authorship-equality.

#### 3.4.3 The remaining suite

1. `a-control-is-a-value-test` — the constructors are pure; the same request yields the same hiccup.
2. `the-postwalk-rewrites-only-handler-slots-test` — a fn-call becomes exactly one `@post`; presentational attributes survive; no attribute outside the allowlist ever reaches the rewrite, because §3.0 already refused it.
3. `the-response-is-not-the-result-test` — the POST returns 204 with the action still pending; the outcome arrives on the feed.
4. `the-outcome-survives-reconnect-test` (seed 2026072917) — WIRE LEVEL: a tab that disconnects before the outcome commits sees it on reconnect, because it was derived.
5. `trusted-renderers-keep-the-full-grammar-test` — the shell's own `data-init`, the bar's `data-on:submit`, and core `raw` still serialize. The boundary must not cost the system its own mechanisms; a suite that passes by breaking the feed opener has proved nothing.

### 3.5 Live proof

An agent writes a render fn containing a button, installs it as a block,
and the owner clicks it in his browser; the action's outcome appears
without a reload, and a deliberately broken handler shows its failure at
the control rather than blanking the page.

Then the adversarial half, run by hand once: an agent is asked to author
a render containing a native form posting to another agent's message
route. The page shows that block's error card naming the refused
attribute, the network panel shows no request to
`/agent/{victim}/message`, and the victim's fact count is unchanged.

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
| D4 | Land reitit in slice 1? | **SETTLED: no — deferred to slice 3** (revised by R-1) | Reitit is the target router and architectural prepayment, not a slice-1 correctness dependency: the existing `handler` already receives `:request-method` and can express one exact POST without a second dispatcher (`web.clj:531-614`). It lands when `/call`, nested route data, and capability middleware make the tree pay for itself. |
| D5 | Durable focal pin (slice 2) | Defer; selection stays tab-local | A stored pin needs the web-session facts that do not exist in fresh `src/`; inventing them at the render tier is the consumer-invents-the-contract failure. |

## Name table

| Name | Meaning | Grounded against |
|---|---|---|
| `POST /agent/{id}/message` | the one inbound human-message boundary | `ui.md:551-559` route family; `:seon.cluster.message/*` |
| `seon.cluster.message/inbound-tx` | pure tx-data for a message from outside the agent population | sibling of the landed `delivery` (`message.cljc:201`) |
| `:seon.cluster.message/inbound-request` | what that function is asked | `message.edn`'s `delivery-request` shape |
| `:seon.render.web/routes` | the reitit route tree value (slice 3, by D4) | reitit's own `router`/route-data vocabulary |
| `:message-bar` | the bar block's `:seon.render.block/name` | `block/surface-id`, one keyword in three roles |
| `:transcript`, `:agent-header`, `:focus` | slice 2's three block names | `render/block.clj` seed vectors |
| `seon.render.control` | slice 3's constructors and the handler-slot postwalk | confinement guardrail (2026-07-28 close) |
| `seon.render.hiccup/authored?` | the CLOSED grammar for agent-authored output | sibling of the landed `hiccup?` (`hiccup.clj:89`), one namespace owning one kind's grammar |
| authored / trusted | whether a projection's output came from an SCI Var or a compiled Var | the seam's own union (README ruling 3, 2026-07-28); COMPUTED, never a name list |
| `inbound` | a message whose origin is outside the agent population | the schema's own words at `message.edn:14-25` |

Deliberately NOT introduced: "chat", "bubble", "surface registry",
"mailbox" (as a UI noun), "optimistic update", "compact face",
"sanitize" (authored output is REFUSED, never cleaned), "trusted
attribute list" (the grammar is closed, so there is no list of bad
things to maintain).

## Orchestrator review points

1. **Before slice 1 is dispatched** — D1-D4 answered. SETTLED: D2 was
   probed and survived, D4 is deferred, and slice 1 is sealed and
   implementing.
2. **In slice 1's diff** — one dispatcher, strengthened in place: an
   exact method-discriminated branch, no reitit coordinate, and no
   prefix arm that could also match the POST.
3. **After slice 1's live proof** — the owner types into the bar. This
   is the gate for everything after it; a slice-2 dispatch before it is
   out of order.
4. **Before slice 2 touches `render/agent.clj`** — that file is also the
   pilot's owner (`namespace-ai`). Confirm no other lane holds it.
5. **Before slice 3 is dispatched** — three gates, all of them hard:
   (a) the interaction fact owner exists, or slice 3 blocks and says so
   rather than defining attributes at the web boundary; (b) the closed
   authored grammar and its §3.4 adversarial suite are written and green
   BEFORE the first authored render is admitted to a page — the boundary
   is not a follow-up to the feature, it is the precondition for it; and
   (c) slices 1 and 2 are done, because slice 3 edits `block.clj`,
   `render.clj`, and `hiccup.clj`, which they protect.
6. **In slice 3's diff specifically** — that the refusal is a refusal
   and not a strip, that the allowlist enumerates the permitted rather
   than the forbidden, and that no trusted mechanism (the feed opener's
   `data-init`, the bar's submit, core `raw`) was broken to achieve it.
7. **At every slice's end** — the reconciliation the repository requires:
   compare against the program ledger, update this file's state, and
   name the next dependency-ready refill.
