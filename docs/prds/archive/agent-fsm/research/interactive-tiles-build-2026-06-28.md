---
type: research
status: completed
tags: [research, web, agent, ui, flow]
---

# Interactive tiles — BUILD (#22 shipped)

## TL;DR

The interactive-tile layer is built and live-proven. Three changes close the
gaps the research mapped, plus an E2E round-trip proof:

1. **Core render fix (the critical one)** — `seon.render/render-agent-tile`
   now applies the handler→Datastar transform to **literal-hiccup** tiles, not
   just fn-symbol tiles. Before, an agent's easiest path
   (`[:button {:on-click …}]` transacted as literal hiccup) silently produced a
   dead button. Now both paths wire.
2. **`src/my/tile.cljs`** — the interactive dual-render sibling of `my.ui`:
   `button` / `input` / `select` / `toggle` / `form`. DATA in, the
   `:seon.render/html-response` envelope out (styled control for the human, a
   `[button: … → fn]` line for the agent). Each emits a safelisted control whose
   action is a fn the agent defined; the existing `/agent/{id}/call` gate
   authorizes it. No new mechanism, no new wire shape, no weakened gate.
3. **CSS safelist** — added the interactive-control classes (`cursor-pointer`,
   `hover:*`, `focus-visible:*`, `disabled:*`, `placeholder:*`, `accent-*`) so
   the controls actually render styled.
4. **E2E proof** — click→invoke→tx→morph proven live with ZERO LLM spend on the
   reachable pod, plus the gate's allow/refuse boundary.

Suite: `bin/test-cljs` green — **754 tests / 3423 assertions / 0 failures /
0 errors** (includes the new `my.canvas-test` + the new render interactive test;
no pre-existing `ctx_test` failures were present at run time).

## What I fixed/built (file:line)

### 1. `seon.render/render-agent-tile` — literal-hiccup transform gate

`src/seon/render.cljs` (~line 678). The transform was gated on
`agent-authored-sym?` (a SYMBOL), so literal-hiccup tiles fell through
untouched. Replaced the gate with: pick the authoring ns from the value —
`(namespace value)` for a fn-symbol tile, else `my.agent.<id>` for literal
hiccup (the same id `/call` routes by, always known in `render-agent-tile`) —
and transform when an authoring ns exists. Core renders (welcome/section
symbols) are neither `agent-authored-sym?` nor a vector → no-op, unchanged.

Test: `test/seon/render/live_tile_test.cljs`
`render-agent-tile-literal-hiccup-interactive-gets-transform` — a literal
`[:button {:on-click (list 'bump! "row-1")} …]` tile becomes
`{:data-on:click "@post('/agent/<id>/call?fn=my.agent.<id>%2Fbump!&args=…')"}`,
and the raw `:on-click` slot is gone.

### 2. `my.canvas` — interactive primitives

`src/my/tile.cljs`. Same dual-render contract as `my.ui`; fully-namespaced
`:my.canvas/*` schemas; `:malli/schema` on every public fn. The action type is
`[:or :symbol [:sequential :any]]` — a fn-REF or fn-CALL, never a raw string
(so an agent can't author a free-form `@post` that bypasses namespace routing).

API (all return `{:seon.render/hiccup … :seon.render/ai …}`):

| fn | request keys | data flow |
|---|---|---|
| `button` | `::label`, `::action` | fn-CALL `(list 'f a)` → `?args=` positional; fn-REF `'f` → body signals as one map arg |
| `input` | `::field`, `::label?`, `::placeholder?` | `data-bind` to the signal `::field` |
| `select` | `::field`, `::options [[val label]…]`, `::label?` | `data-bind` to `::field` |
| `toggle` | `::field`, `::label?` | checkbox `data-bind` to `::field` (boolean) |
| `form` | `::submit` (fn-REF), `::label`, `::fields [envelopes]` | stacks the field hiccup; on submit every bound signal POSTs as ONE map arg to `::submit` |

Worked example (ai + html twins), from a live self-host eval of `my.canvas/form`:

```clojure
(my.canvas/form
  {:my.canvas/submit 'save-note!
   :my.canvas/label  "Save"
   :my.canvas/fields [(my.canvas/input  {:my.canvas/field "note" :my.canvas/label "Note"})
                    (my.canvas/select {:my.canvas/field "tier"
                                     :my.canvas/options [["free" "Free"] ["pro" "Pro"]]})]})
;; :seon.render/hiccup
[:form {:on-submit save-note! :class "flex flex-col gap-2"}
 [:label {:class "flex flex-col gap-1"}
  [:span {:class "text-2xs text-text-400 uppercase tracking-wider"} "Note"]
  [:input {:type "text" :data-bind "note" :class "w-full px-2 py-1 rounded border …"}]]
 [:label … [:select {:data-bind "tier" …} [:option {:value "free"} "Free"] …]]
 [:button {:type "submit" :class "px-2 py-1 rounded border … cursor-pointer …"} "Save"]]
;; :seon.render/ai
"[form → save-note!]
[input: Note → signal \"note\"]
[select: tier → signal \"tier\" | options: Free, Pro]
[submit: \"Save\"]"
```

After the render-time transform, the form's `:on-submit` becomes
`{:data-on:submit "@post('/agent/<id>/call?fn=my.agent.<id>%2Fsave-note!')"}`
and the `data-bind`s pass through untouched — the body signals land as the
handler's one map arg.

Tests: `test/my/tile_test.cljs` — pins the dual-render mirror per primitive,
that the handler slot carries the fn VERBATIM (never a string), that bound
fields carry `data-bind`, and that the whole thing survives the transform into
a wired `@post`.

### 3. CSS safelist

`resources/public/css/input.css` — added four `@source inline(…)` lines under
the existing safelist: `cursor-pointer select-none accent-amber-400`,
`hover:bg-base-{800,850} hover:text-text-{50,100} hover:border-base-700`,
`focus:outline-none focus-visible:border-amber-400 border-amber-{300,400}`,
`disabled:opacity-50 placeholder:text-text-{400,500}`. Kept tight and in sync
with `my.canvas`'s class strings. (Each variant needs its own entry — the base
class alone doesn't emit the variant.)

## E2E proof — click→invoke→tx→morph, zero LLM

Lane note: the acme pod is NOT reachable via the MCP CLJS REPL (documented in
`acme-harness.md` — MCP sees only the live `:client` shadow runtime, not the
`compile`-built `:acme-client`; standing a parallel shadow server for it was
disproportionate). The acme bundle DOES carry the fix + seeds `my.canvas`, so it
works there too — but the executable, observable round trip was run on the
reachable default pod via the GENUINE self-host agent path, with a fully
**ephemeral** agent that was retracted afterward (the shared store is clean —
`CLEANUP-AGENT-GONE true`). The code exercised is byte-identical core `seon.*`.

Setup (genuine path): minted an ephemeral agent `IPc-2606281946`, defined
`my.agent.IPc-2606281946/bump!` via `seon.eval/eval` into the live compile-state
(`lookup-value` resolves it), recorded its `:seon.fn`/`:seon.ns` rows, and wired
a **literal-hiccup** tile `[:button {:on-click (list 'bump! "click")} "+1"]`.

Observed (live):

- **Gate ALLOW** — `capability-check @conn 'my.agent.IPc-2606281946/bump!`
  → `{::agent-id "IPc-2606281946"}`.
- **Gate REFUSE** — a non-granted `…/nope` → refused; `fs/readFileSync` →
  refused.
- **Render fix** — `render-agent-tile` on the literal-hiccup tile produced
  `:data-on:click "@post('/agent/IPc-2606281946/call?fn=my.agent.IPc-2606281946%2Fbump!&args=%5B%22click%22%5D')"`
  and the raw `:on-click` was gone. (This is the fix working end to end.)
- **HTTP round trip** (curl, same-origin):
  - `POST /agent/IPc-2606281946/call?fn=…%2Fbump!&args=%5B%22click%22%5D`
    → `200 {"ok?":true}` (×2)
  - non-granted fn → `403`; `fs/readFileSync` → `403` with the refusal reason.
- **TX landed** — after two clicks, `:my.agent.IPc-2606281946/counter` = `2`
  (the invoked fn's transact committed under the agent's tx-context).
- **Feed morph** — a node gunzip SSE client on `/agent/<id>/feed` received
  frames carrying the agent's rendered content after a click
  (`{"contentEncoding":"gzip","framesReceived":2,"sawAgentIdInFeed":true}`).

So: human click (POST) → capability gate → invoke (apply, args as DATA) → tx →
the existing reactive feed morph. The whole #22 loop, no LLM.

## Security contract (unchanged — `my.canvas` rides the existing gate)

The allowlist is the agent's own `:seon.fn` set by namespace; refusal precedes
invocation; fn-CALL args are decoded DATA-ONLY; fn-REF signals are `js->clj`
of the JSON body; same-origin middleware on the route. `my.canvas` adds nothing
on the security side — `::action` is typed `[:or :symbol [:sequential :any]]`,
so a raw action string is rejected at the schema boundary before it could
bypass namespace routing.

## What Core / other lanes may want next (non-blocking)

- **Agent-facing guidance** — the `seon.render.live-tile` docstring and the
  `ui-canvas` skill still say tile interactivity is "UNBUILT / no
  interactive buttons yet". With #22 shipped they should gain an interactive
  variant (the `:on-click 'my-fn` shape + a `my.canvas/button` example) so agents
  learn it from the always-on context. (Core owns the live_tile docstring; the
  skill content is UI-lane — follow-up.)
- **(Optional) "the human acted" context surface** (research B.4) — a
  derive-only section reading recent interaction-effect datoms on the agent's
  entity. Only if live drives show agents lose track of human clicks.
- The `message/user` install-timing wart (CLAUDE.md Open) will surface in any
  `form → message-the-agent` demo — orthogonal to #22, Core's.

## Files

- `src/seon/render.cljs` — literal-hiccup transform gate fix.
- `src/my/tile.cljs` — NEW, the interactive primitives.
- `resources/public/css/input.css` — control-class safelist.
- `test/my/tile_test.cljs` — NEW.
- `test/seon/render/live_tile_test.cljs` — the literal-hiccup interactive test.
