---
type: research
status: active
tags: [research, web, agent, flow, ui]
---

# Interactive tiles — how an agent authors forms/buttons that call back (#22)

## TL;DR

- **The interactive BACKBONE already exists and is WIRED — #22's "UNBUILT" is
  stale by half.** The render path rewrites agent-authored event handlers into
  standard Datastar `@post`, the `/agent/{id}/call` POST route is a seeded
  `:seon.route/*` datom, and a capability gate authorizes the call before any
  invocation. I proved all three live (read-only) on the default pod. There is
  no bespoke client macro — agents write a normal Clojure fn-call in a handler
  slot and the server emits standard Datastar.
- **What's actually missing is four things, none of them the hard part:**
  1. **The transform is gated on `agent-authored-sym?` — it fires ONLY for
     fn-SYMBOL-wired tiles, NOT for literal-hiccup tiles.** Since the live-tile
     guidance pushes literal hiccup as the *easiest* path, an agent's first
     interactive instinct (`[:button {:on-click ...}]` in a literal-hiccup tile)
     **silently produces a dead button.** This is the #1 correctness gap and a
     2-line Core fix (`render.cljs:678`).
  2. **No `my.canvas` interactive primitives** — agents must hand-author hiccup
     handler slots + `data-bind` signal wiring + remember the safelist. There is
     no `button` / `form` / `input` / `select` / `toggle` helper that emits
     correct, safelisted, dual-render hiccup. `my.ui` (the static helpers, owned
     by the concurrent lane) has `status-line`/`kv-table`/`section` but nothing
     interactive.
  3. **The CSS safelist has ZERO interactive-control classes** — no
     `cursor-pointer`, `hover:*`, `focus:*`, `active:*`, `disabled:*`,
     `select-none`. An agent-authored button/input is unstyled and gives no
     affordance. Small additive fix to `input.css`.
  4. **No reactive RESULT contract** — the invoked fn transacts and the existing
     feed re-renders, which is correct and sufficient, but nothing tells the
     agent's *context* that a human acted. The clean shape (derive-don't-store)
     is a section that reads the agent's own interaction-effect datoms.
- **Recommendation:** build `my.canvas` as a thin set of dual-render interactive
  primitives on top of the existing transform+gate (no engine rewrite), fix the
  literal-hiccup transform gate in Core, extend the safelist, and prefer the
  **fn-REF + signals** shape (form fields → one map argument) as the canonical
  way data lands in the agent. The interaction is "just an eval authored as
  hiccup and routed by namespace" — that framing is already the implementation;
  we are adding ergonomics + one correctness fix, not a new mechanism.

---

## Part A — What EXISTS today (grounded, file:line)

The full path agent-hiccup → human-click → agent already exists across four
files. Read these before designing anything.

### A.1 The render-time rewrite — `seon.web.reactive.transform`

`src/seon/web/reactive/transform.cljs` is a pure server-side postwalk over
agent-authored hiccup. Two authoring shapes (`transform.cljs:10-46`):

- **fn-CALL** — a seq whose head is a symbol, args bound at RENDER time:
  `[:button {:on-click (list 'inc-counter! "x-1")} "+1"]`. The args are the live
  values captured when the tile rendered; rewritten to a POST carrying them
  transit-serialized in `?args=`.
- **fn-REF** — a bare/qualified symbol, args from CLICK-time signals:
  `[:button {:on-click 'submit-order!}]`. No render-time args; Datastar posts the
  current signals as the JSON body and `/call` passes them to the fn as **one map
  argument**.

`event-attr?` matches `^on[:-].+` (`transform.cljs:149-155`), so `:on-click`,
`:on:submit`, `:on:change` all work; the captured event becomes the Datastar
`data-on:<event>` key. Bare handler symbols qualify to the authoring ns
(`qualify`, `transform.cljs:160-166`) — Clojure semantics, a bare name means the
current ns. The descriptor rides the **URL query** (`call-action`,
`transform.cljs:127-143`), not Datastar's `@post` 2nd arg (which is fetch
options in v1, with signals in the body). `fn` + the transit-encoded `args`
vector are URL-encoded and the apostrophe is `%27`-escaped so it can't break the
single-quoted `@post('…')` string.

**LIVE PROOF (default pod, read-only):**

```clojure
(require '[seon.web.reactive.transform :as xf])
(xf/transform-hiccup 'my.agent.demo
  [:button {:on-click (list 'inc-counter! "x-1")} "+1"])
;; => [:button {:data-on:click
;;     "@post('/agent/demo/call?fn=my.agent.demo%2Finc-counter!&args=%5B%22x-1%22%5D')"} "+1"]

(xf/transform-hiccup 'my.agent.demo
  [:form {:on-submit 'save-note!}
   [:input {:data-bind "note" :placeholder "note..."}]
   [:button {:type "submit"} "Save"]])
;; => [:form {:data-on:submit "@post('/agent/demo/call?fn=my.agent.demo%2Fsave-note!')"}
;;     [:input {:data-bind "note", :placeholder "note..."}]   ;; data-bind PASSES THROUGH
;;     [:button {:type "submit"} "Save"]]
```

Note the form case: `data-bind` is a non-event attr, so it passes through
untouched, and the submit becomes a no-args `@post` whose body will carry the
`note` signal. That is exactly the fn-REF/signals contract.

### A.2 The capability gate + invoke — `seon.web.reactive.call`

`src/seon/web/reactive/call.cljs` is "the THIRD door of the one
sandboxed-execution service (eval + render are the other two): an interaction is
just an eval authored as hiccup and routed by its namespace" (`call.cljs:2-12`).

- **`resolve-owning-agent`** (`call.cljs:63-80`) — namespace-as-route: the fn's
  ns must be `my.agent.<id>` AND a live `:seon.agent/id` row must exist. `fs`,
  `seon.*`, a domain ns, a dead agent → nil → refused. This is the "namespace IS
  the route" replacement for the old JVM `seon.*`-prefix whitelist
  (`call.cljs:14-20`), which was "exactly wrong for agent code."
- **`granted-fn?`** (`call.cljs:82-96`) — the fn must be a registered `:seon.fn`
  whose owning ns is that agent's home ns (a fn the agent itself defined). A
  symbol with no matching `:seon.fn` row → refused.
- **`capability-check`** (`call.cljs:98-115`) — pure fn of a db value; returns
  `{::agent-id <id>}` or `{::refused <reason>}`. **Never invokes — the refusal
  is the security boundary, evaluated before any execution** (`call.cljs:24-36`).
- **`invoke!`** (`call.cljs:126-167`) — resolve-and-apply: resolves the granted
  symbol to its COMPILED runtime value (`seval/lookup-value`) and `(apply f
  args)` with **args as VALUES, never printed into source / re-read as code**
  (`call.cljs:38-50`). Runs inside `db/with-agent` + `with-tx-context` so the
  transact is agent-tagged and the reactive feed updates. Errors are values
  (catches throws, incl. the fn's own Malli arg-validation, → 422), async
  Promises (a `db/transact!`) are awaited.
- **`handle!`** (`call.cljs:203-274`) — POST handler. 200 ok / 403 refused / 422
  bad-args-or-invoke-failure / 400 missing-fn. fn-CALL decodes `?args=`
  **DATA-ONLY** (`transform/decode-args` rejects symbols/lists/tagged-values,
  `transform.cljs:90-107`); fn-REF reads the POST body's Datastar signals
  (`parse-signals`, `call.cljs:193-201`) and passes them as a single map arg.

**LIVE PROOF (default pod, read-only) — the refusal boundary works both ways:**

```clojure
(require '[seon.web.reactive.call :as call] '[seon.db :as db])
(call/capability-check @db/*conn* 'fs/readFileSync)
;; => {::refused "no agent owns the namespace of `fs/readFileSync` …"}
(call/capability-check @db/*conn* 'my.agent.root/bogus-fn)
;; => {::refused "`my.agent.root/bogus-fn` is not a granted :seon.fn of agent root …"}
```

(The positive granted path is the exact query inversion of `granted-fn?` — it
returns `{::agent-id …}` once a `:seon.fn` row exists in the agent's home ns. No
agent had authored a home-ns fn at observation time, so the positive case was
confirmed by reading the symmetric query, not invoked — invoking would require a
tx on the shared store, which I avoided per the lane boundary.)

### A.3 The route — a seeded `:seon.route/*` datom

`src/seon/route.cljs:106-108` seeds the action door as a DATOM, not a hardcoded
route:

```clojure
{::pattern "/agent/{id}/call" ::method :post ::name ::agent-call
 ::handler    'seon.web.reactive.call/handle!
 ::middleware [::same-origin]}
```

`seon.web.router/db->routes` (`router.cljs:200-224`) projects all `:seon.route/*`
datoms into the reitit vector, resolving the handler symbol LATE via
`eval/lookup-value` (`router.cljs:184-198`). The `:seon.route/same-origin`
middleware (`router.cljs:151-161`) refuses cross-origin POSTs (loopback binding
is not CSRF protection). There is also a back-compat flat `/call` in the static
supplement (`router.cljs:274-275`). **Adding a new interactive endpoint = adding
a `:seon.route/*` datom**, nothing else.

### A.4 The wiring into the live tile — `seon.render/render-agent-tile`

`src/seon/render.cljs:619-722` is the ONE tile entry point. After it renders the
wired value to hiccup, it applies the transform (`render.cljs:672-685`):

```clojure
resp (if (render-sci/agent-authored-sym? value)
       (update resp :seon.render/hiccup
               (fn [h] (if h (transform/transform-hiccup
                               (symbol (namespace value)) h) h)))
       resp)
```

The result comes back via the EXISTING reactive feed: the invoked fn transacts →
`listen!` → render → SSE morph. No second update mechanism (the datastar skill's
standing rule).

---

## Part B — The gaps (what's actually missing for #22)

### B.1 CRITICAL — the transform gate excludes literal-hiccup tiles

`render.cljs:678` gates the transform on `(render-sci/agent-authored-sym?
value)`. `agent-authored-sym?` (`render/sci.cljs:93-106`) requires a
**qualified symbol**. But `wired-content` (`render/live_tile.cljs:361-380`)
binds `::value` to the literal hiccup vector when the agent transacts literal
hiccup — NOT a symbol. So:

> **An agent that transacts literal hiccup with an `:on-click` handler gets NO
> transform → the raw `{:on-click (list 'foo …)}` slot is emitted verbatim →
> invalid Datastar → a dead button.**

This is the trap: `live_tile.cljs:158-165` advertises literal hiccup as "(a)
literal hiccup — instant, no fn needed" — the *easiest* path — and that is
exactly the path where interactivity silently fails. The fix is to gate on "is
this an agent tile" (we always know the owning `id` in `render-agent-tile`), and
qualify bare handler symbols to the agent's home ns:

```clojure
;; render.cljs — replace the agent-authored-sym? gate around line 678
resp (let [h (:seon.render/hiccup resp)]
       (if (and h (or (render-sci/agent-authored-sym? value)
                      (vector? value)))          ; literal hiccup is agent-authored too
         (assoc resp :seon.render/hiccup
                (transform/transform-hiccup
                  (if (symbol? value) (symbol (namespace value))
                      (symbol (str "my.agent." id)))   ; bare handlers → agent's home ns
                  h))
         resp))
```

This is a **Core fix** (`seon.render` is Core's). Flagged below.

### B.2 No `my.canvas` interactive primitive set

The transform mechanism is there but agents must:
- remember the `:on-click (list 'fn args)` vs `:on-click 'fn` distinction,
- hand-wire `data-bind "field"` on every input (and know it's a Datastar attr
  the safelist doesn't validate),
- author a `<form>`/`<button type=submit>` shell correctly,
- emit only safelisted classes for a control that has no safelisted classes (B.3).

That is exactly the kind of friction `my.ui` removed for the *static* canvas. We
need the interactive sibling. Design in Part C.

### B.3 The CSS safelist has no interactive-control classes

`resources/public/css/input.css` `@source inline(…)` (lines 29-43) safelists
layout/space/text/color/border — but **zero** of `cursor-pointer`, `hover:*`,
`focus:*`, `focus-visible:*`, `active:*`, `disabled:*`, `select-none`,
`ring-*`. An agent-authored button is unstyled and gives no click affordance; an
input has no focus ring. The `my.canvas` primitives should emit a small fixed set
of control classes, and those classes must be added to the safelist (additive,
~1 line). **UI-lane fix** (the safelist is owned here / shared with the canvas
lane — coordinate so the static + interactive additions land together).

### B.4 No reactive "the human acted" context surface

Today the invoked fn transacts and the human's tile re-renders — correct. But
the AGENT only learns a human acted if its fn wrote a datom the agent later
reads. There is no general "your human just clicked X" context section. Per
derive-don't-store, the clean shape is: the interaction-handler fn transacts a
typed effect onto the agent's own entity (e.g. a counter, a submitted note
entity with `:my.note/from :human`), and a context block renders the recent ones
by querying. We should NOT add a notification queue or a stored "pending
interaction" flag. This is optional polish, not blocking — `B.1`+`B.2` are the
must-haves.

---

## Part C — Design: the `my.canvas` interactive primitives

`my.canvas` is the **interactive** sibling of `my.ui` (static). Same contract:
DATA in, the `:seon.render/html-response` dual-render envelope out
(`{:seon.render/hiccup … :seon.render/ai …}`), so the human's styled control and
the agent's text description can't drift. It lives in a NEW file `src/my/tile.cljs`
(the concurrent lane owns `my.ui*` + `live_tile.cljs` + `ui-canvas`; `my.canvas`
is this lane's interactive tier per the prompt).

### C.1 The primitives

Each emits agent-authored hiccup with a handler slot the existing transform
rewrites. The `:seon.render/ai` twin describes the control AND its wired action,
so the agent's live-tile section reads "a button labelled X that calls my fn Y",
never raw HTML.

```clojure
;; my.canvas/button — label + an action (a fn-CALL list or a fn-REF symbol).
(button {:my.canvas/label  "Approve"
         :my.canvas/action (list 'approve! order-id)})  ; fn-CALL: render-time arg
;; human: a styled clickable button (data-on:click @post → /call)
;; ai:    "[button] Approve → calls approve! with \"order-7\""

;; my.canvas/button — fn-REF (bare symbol): args come from click-time signals.
(button {:my.canvas/label "Submit" :my.canvas/action 'submit! :my.canvas/submit? true})

;; my.canvas/input — a labelled field BOUND to a signal (data-bind).
(input {:my.canvas/field "note" :my.canvas/label "Note" :my.canvas/placeholder "…"})

;; my.canvas/select / my.canvas/toggle — same field→signal binding.
(select {:my.canvas/field "tier" :my.canvas/options [["free" "Free"] ["pro" "Pro"]]})
(toggle {:my.canvas/field "live" :my.canvas/label "Live updates"})

;; my.canvas/form — fields → a submit action. The fields data-bind to signals; on
;; submit the signals POST as the body and land as ONE map arg to the handler.
(form {:my.canvas/submit 'save-note!            ; fn-REF — gets {:note "…" :tier "…"}
       :my.canvas/label  "Save"
       :my.canvas/fields [(input  {:my.canvas/field "note"  :my.canvas/label "Note"})
                        (select {:my.canvas/field "tier" :my.canvas/options […]})]})
```

`form` composes child field-envelopes exactly the way `my.ui/section` composes
blocks (`my/ui.cljs:125-142`) — it stacks their hiccup, joins their `:ai`, wraps
in a `<form>` with the `data-on:submit` handler, and appends the submit button.
The dual render holds through nesting.

### C.2 The two data-flow shapes (when each)

| Shape | Authoring | Data source | Lands as | Use when |
|---|---|---|---|---|
| **fn-CALL** | `(list 'f arg…)` | render-time values captured in the tile | `?args=` transit, decoded DATA-ONLY → positional args | the action's inputs are KNOWN when the tile renders (an id on a row's button) |
| **fn-REF + signals** | `'f` + `data-bind` fields | click-time signals (form/input) → POST JSON body | ONE map arg `{:field val …}` | the human TYPES/PICKS the input (forms) |

`form` always uses fn-REF (the fields are the signals). `button` on a row uses
fn-CALL (the row id). This mirrors `call.cljs:236-249` exactly — the design adds
no new wire shape.

### C.3 The handler the agent writes

The action is a `:seon.fn` the agent defined in its home ns (that's what
`granted-fn?` authorizes). The handler snippets below are **elided for the
data-flow** — a real agent fn carries a `:malli/schema` (instrumentation is
always-on) and any new attribute (`:my.agent/counter`, `:my.note/*`) is
`schema/register!`-ed first, else `db/transact!` refuses it. Both are exactly
what the always-on `:namespaces` context teaches via the worked
`register!→transact!→query` example; the `my.canvas` primitives don't change that.
For the counter example:

```clojure
;; the agent evals this once — it becomes a granted :seon.fn of my.agent.<id>
(defn ^:async inc-counter! [arg]
  (seon.db/transact!
    {:seon.db/tx-data
     [{:seon.agent/id (seon.db/current-agent-id)
       :my.agent/counter (inc (or (some-> (seon.db/pull
                                            {:seon.db/db @seon.db/*conn*
                                             :seon.db/pull-pattern '[:my.agent/counter]
                                             :seon.db/ref [:seon.agent/id (seon.db/current-agent-id)]})
                                           :my.agent/counter) 0))}]}))
```

For the note form, the handler takes the signals map:

```clojure
(defn ^:async save-note! [{:keys [note]}]    ; signals → one map arg
  (seon.db/transact!
    {:seon.db/tx-data [{:my.note/text note
                        :my.note/from :human
                        :my.note/agent [:seon.agent/id (seon.db/current-agent-id)]}]}))
```

The agent then makes its tile a FN that reads `:my.agent/counter` /
`:my.note/*` and re-derives every render — so the click's effect shows up with no
extra wiring (the feed re-renders on the transact).

---

## Part D — End-to-end flow + worked example

### D.1 Flow diagram (counter button)

```
AGENT (one eval)                          RENDER (every feed tick)
  defn inc-counter!  ──► :seon.fn row       render-agent-tile
  transact tile-fn symbol onto                resolve wired value (the tile fn)
    :seon.render.live-canvas/content            html-render → hiccup w/ [:button {:on-click (list 'inc-counter!)}]
                                              transform-hiccup → [:button {:data-on:click "@post('/agent/<id>/call?fn=…inc-counter!')"}]
                                              SSE morph → human sees the button
HUMAN clicks ──────────────────────────►  POST /agent/<id>/call?fn=my.agent.<id>%2Finc-counter!
                                              same-origin mw ✓
                                              capability-check @conn 'my.agent.<id>/inc-counter!
                                                resolve-owning-agent → "<id>"   (ns = home ns, live agent ✓)
                                                granted-fn? → true              (:seon.fn row in home ns ✓)
                                              invoke!  with-agent <id> → (apply inc-counter! [])
                                                → db/transact! :my.agent/counter (inc …)
                                              200 {ok? true}
  tx fires listen! ◄──────────────────────  the transact's tx-listener
  render-agent-tile re-derives counter
  SSE morph ──► human sees the new count
  next turn: the live-tile context section re-derives → AGENT sees the new count too
```

The security claim is the single `capability-check` step: a click can only ever
invoke a fn the agent itself defined in its own home ns. `fs/*`, `seon.*`,
another agent's fn, or an un-defined symbol are all refused 403 BEFORE invoke.

### D.2 Worked example — a note form that messages the agent

`save-note!` (D.3 below) could instead drop the note as a MESSAGE to the agent
(waking it), rather than a plain datom, when the human's input should pull the
agent into a turn:

```clojure
(defn ^:async save-note! [{:keys [note]}]
  (seon.message/user {:seon.message/to   (seon.db/current-agent-id)
                      :seon.message/text (str "Human submitted via tile: " note)}))
```

So the same `/call` door supports both reactive shapes the prompt asks about:
**a TX** (entity update → reactive re-render, the counter) and **a MESSAGE**
(lands in the transcript + wakes the agent, the note). The agent CHOOSES by what
its handler fn does — the mechanism doesn't fork. (NB: `message/user` has a known
install-timing/discoverability wart, CLAUDE.md "Open" — Core's, orthogonal.)

---

## Part E — The action-allowlist security contract

This is already the design; stating it as the contract for #22:

1. **The allowlist is the agent's own `:seon.fn` set, by namespace.** A handler
   is invocable iff it is a registered `:seon.fn` whose owning ns is the calling
   agent's home ns (`granted-fn?`, `call.cljs:82-96`). There is no separate
   "exposed actions" registry to maintain — defining the fn IS granting it; a
   `forget!`/redefine changes the surface. This is the same code-as-data corpus
   the eval + render doors read.
2. **Refusal precedes invocation, always** (`call.cljs:24-36, 104-115`). The gate
   is a pure fn of a db value; it never executes the symbol to decide.
3. **Args stay data, never code** (`call.cljs:38-50`): fn-CALL args are
   transit-decoded DATA-ONLY (`decode-args` rejects symbol/list/tagged-value,
   `transform.cljs:90-107`); fn-REF signals are `js->clj` of a JSON body. An
   attacker-controlled arg can never become an eval'd form.
4. **The resolved fn is the always-on-instrumented var** — its own
   `:malli/schema` validates the click's args; a bad arg surfaces as a 422 value,
   not a crash. No second validator to drift.
5. **Same-origin middleware** on the route (loopback ≠ CSRF protection).
6. **The sandbox is NOT the boundary — this gate is.** Consistent with CLAUDE.md:
   isolation comes from the capability surface (which fns resolve + are granted),
   not from the SCI tile sandbox (that catches LLM hallucinations / non-terminating
   tiles, `render.cljs:649-670`).

The one thing to ADD for `my.canvas` is nothing on the security side — the
primitives produce handler slots that ride the SAME gate. The danger to avoid is
a primitive that lets the agent specify a *raw Datastar action string* (e.g. a
free-form `@post(url)`), which would bypass namespace-routing. `my.canvas` must
only accept a fn-symbol/fn-call as the action, never a string — the transform's
`call-or-ref` already ignores non-symbol/seq handler values
(`transform.cljs:168-181`), so keeping the primitive's `:my.canvas/action` typed as
`[:or :symbol [:sequential :any]]` enforces it.

---

## Part F — Prototype result

Per the lane boundary (a concurrent agent holds the live-agent-drive lane; the
shared default pod must not be disrupted), I ran **read-only / pure-fn proofs on
the live pod** rather than minting an agent or transacting. Proven:

1. **The transform produces correct Datastar for both shapes** incl. a form with
   `data-bind` passthrough (Part A.1 live output). ✓
2. **The capability gate refuses correctly** for `fs/*` (no owning agent) and a
   non-granted agent symbol (Part A.2 live output). ✓
3. **The seeded route + late-resolving handler exist** (`route.cljs:106`,
   `router.cljs:200-224`). ✓ (read, not re-derived)

**Deferred (needs a tx → not on the shared pod):** the full click→invoke→tx→morph
round trip, which requires (a) the Core literal-hiccup gate fix (B.1) for the
easy path, or a fn-wired tile, plus (b) an agent with a granted home-ns fn. The
clean way to prove it is in the **isolated `bin/acme` cluster (7980)**: mint an
agent, eval `inc-counter!`, wire a fn-tile with a `[:button {:on-click 'inc-counter!}]`,
then drive `/agent/<id>/call` with a node client and observe the 200 + the
counter datom + the feed morph. I recommend the implementing agent do this E2E in
acme as the first proof after B.1 lands. The pure-fn proofs above establish that
every PIECE works; only the composed round-trip is unproven, and only because it
needs a write.

---

## Part G — Recommendation (how to build it well)

**Order of work (smallest correctness fix first):**

1. **Core: fix the literal-hiccup transform gate (B.1)** — `render.cljs:678`.
   2 lines. Without this, the *easiest* authoring path silently yields dead
   buttons; with it, both literal-hiccup AND fn-tiles are interactive. This is
   the single highest-leverage change and it's Core's.
2. **UI: extend the CSS safelist (B.3)** — add `cursor-pointer select-none
   hover:bg-base-{800,850} hover:text-text-100 focus:outline-none
   focus-visible:border-amber-400 disabled:opacity-50` (and the input border
   classes the controls use) to `input.css @source inline`. Coordinate with the
   canvas lane so static + interactive safelist additions land in one edit.
3. **UI: build `src/my/tile.cljs`** — `button` / `input` / `select` / `toggle` /
   `form`, dual-render, emitting safelisted controls + typed actions (Part C).
   Mirror `my.ui`'s shape exactly (registered shared field shapes, `:=>`
   `:seon.render/html-response` returns). Add a short worked example to the
   ns-docstring (the namespaces block renders it).
4. **Prove E2E in acme (Part F)** — the click→invoke→tx→morph round trip, server-
   side (node `/call` client + a store query + a gunzip feed frame). Then, once
   the live-drive lane is free, a real DeepSeek agent authors an interactive tile
   un-coached and we observe whether the primitives are discoverable.
5. **(Optional) the "human acted" context surface (B.4)** — a derive-only section
   reading recent `:my.note/from :human` (or counter deltas) on the agent's
   entity. Only if drives show agents lose track of human interactions.

**Keep the framing:** an interaction is an eval authored as hiccup, routed by
namespace, gated by the agent's own fn set. We are adding ergonomics
(`my.canvas`), one correctness fix (literal-hiccup gate), and presentation (safelist)
— NOT a new mechanism, NOT a new security model, NOT a new update path. The feed
is the result channel; the agent's own fns are the allowlist; the namespace is the
route.

---

## Flags (needs Core / cross-lane)

- **[CORE] `render.cljs:678` literal-hiccup transform gate (B.1)** — agent-authored
  literal-hiccup tiles with handlers emit dead buttons. The gate should be "agent
  tile" (always known via `id`) not "value is a symbol". 2-line fix; design in B.1.
  This is the only ENGINE change #22 needs.
- **[UI/shared] CSS safelist has no control classes (B.3)** — coordinate with the
  canvas lane (owner of `my.ui*`/`input.css` additions) so interactive + static
  safelist entries land together.
- **[CORE, pre-existing] `message/user` install-timing + discoverability** — if
  `my.canvas` handlers message the agent (D.2), they hit the known wart (CLAUDE.md
  Open list). Orthogonal to #22 but will surface in any "form → message" demo.
- **[doc] `live_tile.cljs` guidance** — once B.1 lands, the live-tile docstring's
  "literal hiccup — instant" example should gain an interactive variant so agents
  learn the `:on-click 'my-fn` shape from the always-on context (the place agents
  actually read, per the "succeed from always-on context" finding).
