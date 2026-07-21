---
type: research
status: active
tags: [research, agent, cljs]
---

# Eval-render dedup audit (2026-06-17)

## TL;DR

There are TWO renderers for the same `:seon.eval` data:

1. `seon.ctx/format-eval-row` — the LIVE agent-transcript path. Hand-rolled
   string composer invoked from the `session-evals` loop inside
   `assemble-context` (`src/seon/ctx.cljs:1561`). This is what the LLM reads
   of its own eval history each turn.
2. `seon.handlers.eval/render-ai` + `render-html` — the schema-dispatched
   render, wired on the `:seon.eval` entity's `:seon.render/ai` /
   `:seon.render/html` props (`src/seon/agent.cljs:402-403`). `render-ai`
   feeds nothing in the agent's prompt today; `render-html` is the inspector
   card.

They overlap (both turn one `:seon.eval` row into text the LLM could read)
but are NOT interchangeable: `format-eval-row` carries render-time context
(`prior?`, `:seon.ctx/core-authored?`, per-eval char budget, result-claim
neutralization, the result-var `⇒ (result :id)` handle, the derived
persistence note added in this batch) that the entity-only handler lacks.
**Recommendation: a single-renderer merge is feasible and desirable, but
DEFERRED** — it requires threading the transcript-only context into the
schema-dispatch call, which is a non-trivial render-API change. Concrete
plan + blockers below.

## 1. Where each is invoked, which surface consumes it

### `seon.ctx/format-eval-row` (`src/seon/ctx.cljs:461-541`)

- Invoked from `transcript-section` / `assemble-context` at
  `ctx.cljs:1561` inside the `session-evals` loop (`ctx.cljs:1539`,
  `session-evals` defined at `ctx.cljs:643-663`).
- Consumes: the AGENT CONTEXT — the prompt text the LLM receives every turn
  (the one composer `seon.agent/assemble-context`). It is private (`defn-`)
  and used only inside `ctx.cljs`.
- It is NOT used by the inspector and NOT a schema-dispatched render — it is
  invoked directly with the eval map plus a `prior?` flag, not through
  `:seon.render/ai`.

### `seon.handlers.eval/render-ai` + `render-html` (`src/seon/handlers/eval.cljs:71-154`)

- Both are stamped on the `:seon.eval` entity schema (`agent.cljs:400-403`):
  `:seon.render/ai 'seon.handlers.eval/render-ai`,
  `:seon.render/html 'seon.handlers.eval/render-html`.
- Invocation is schema-dispatch: `seon.render/visible-entities` walks every
  entity carrying `:seon.render/ai`, the inspector resolves the symbol and
  calls it via `seon.eval/lookup-value` (per the handler ns docstring,
  `handlers/eval.cljs:9-14`).
- Consumers: `render-html` → the inspector's HTML pane (the per-eval card,
  web UI). `render-ai` → the standalone-entity AI render path; it is the
  "what the LLM sees of one entity in isolation" shape, but the LIVE agent
  transcript does NOT route through it today (that is `format-eval-row`).

## 2. Which `:seon.eval/*` attrs each reads, and output shape

### `format-eval-row` reads

`:seon.eval/source`, `:seon.eval/ok?`, `:seon.eval/result-edn`,
`:seon.eval/output`, `:seon.eval/error`, `:seon.eval/error-data`,
`:seon.eval/id`, `:seon.eval/duration-ms`, `:seon.eval/narration`,
`:seon.eval/ns`, plus the synthesized `:seon.ctx/core-authored?` (added by
`session-evals`) and the positional `prior?` arg.

Output shape (a single multi-line string):

```
;; <narration, result-claims neutralized>      ; if non-blank
<ns>=> <source, neutralized, capped>
<output, capped>                               ; if non-blank
<body: capped result | ;; ERROR … | rendered malli error | ;; <no result>>  ; suffix
;; won't persist across reboots: …             ; NEW (Part B) — on :ok, derived
```

`suffix` is `  ; ⇒ (result :<id>) · <ms>` on :ok (omitted when `prior?`),
`  ; # <id> · <ms>` on error.

### `render-ai` reads

`:seon.eval/id`, `:seon.eval/narration`, `:seon.eval/source`,
`:seon.eval/ok?`, `:seon.eval/result-edn`, `:seon.eval/error`,
`:seon.eval/duration-ms`. Returns `{:seon.render/ai <string>}`:

```
;; <narration, trimmed>          ; if non-blank
[eval <id> <ms> :ok|:error]
<source, trimmed, truncated 800>
=> <short-result, 1 line, ≤80>   ; :ok
:error <short-error, ≤120>       ; :error
```

### `render-html` reads

Same attrs as `render-ai`. Returns `{:seon.render/hiccup …}` — a card with
markdown narration, an id/duration/status-pill header, a
`language-clojure` `<pre>`, and on error a collapsible `<details>` holding
the full `:seon.eval/error` string.

Note: neither handler reads `:seon.eval/output`, `:seon.eval/error-data`,
or `:seon.eval/ns`. `format-eval-row` reads all three.

## 3. How they DIFFER (concrete)

- **Caps.** `format-eval-row` selects `core-eval-render-cap` (50000,
  `ctx.cljs:339`) for core-authored rows vs `eval-render-cap` (1500,
  `ctx.cljs:330`) for agent rows, and applies the loud-truncation
  `cap-result`/`cap-result-body` (with the "narrow your query" guide on
  oversized results). The handlers use FIXED truncations: `source-truncate`
  800, `result-summary-truncate` 80, `error-summary-truncate` 120
  (`handlers/eval.cljs:35-37`) — much tighter, one-line summaries.
- **Result-var handle.** `format-eval-row` appends
  `  ; ⇒ (result :<id>) · <ms>` (the retrieval call, show-don't-tell).
  `render-ai` shows only `=> <short>` with no dereference handle.
- **Prior-session handling.** `format-eval-row` takes a `prior?` flag and
  DROPS the result-var handle for prior-session evals (their live values
  did not survive restart). The handlers have no concept of prior-session.
- **Error envelope.** `format-eval-row` branches on a Malli instrumentation
  envelope (`:seon.eval/error-data`) and renders via
  `einstrument/render-malli-error`. The handlers ignore `error-data` and
  show only the first-line short summary (`short-error`).
- **`neutralize-result-claims`.** `format-eval-row` rewrites
  model-authored `;; =>` result-claim comments in narration AND source to
  the unverified marker (anti-fabrication gate). The handlers do NOT —
  they trust narration/source verbatim.
- **Core-authored cap selection.** Only `format-eval-row` reads
  `:seon.ctx/core-authored?` to render OUR scripted output in full.
- **Output line.** Only `format-eval-row` shows captured
  `:seon.eval/output` (println/prn during the eval span). The handlers
  drop it entirely.
- **Ending ns.** Only `format-eval-row` prefixes the prompt line with
  `<ns>=> `; the handlers use a `[eval … ]` header with no ns.

## 4. Can they unify behind one schema-dispatch (`:seon.render/ai`)?

**Yes, feasible — DEFERRED.** The transcript path could route through the
single `:seon.render/ai` handler if the handler accepted the render-time
context that `format-eval-row` currently receives implicitly.

What the entity-only handler LACKS that the transcript needs:

- `prior?` — whether the eval is from a prior (dead) session, to drop the
  result-var handle and not promise a live `(result :id)`.
- `:seon.ctx/core-authored?` — to pick `core-eval-render-cap` vs
  `eval-render-cap`. Today injected by `session-evals` onto the eval map;
  it could ride INTO the handler as part of the `:seon.render/entity` map
  (it already does — `session-evals` `assoc`s `::core-authored?`), so this
  one is nearly free.
- The char budget itself — handlers hard-code tiny truncations; the
  transcript needs the loud-clip `cap-result` family and the
  context-SAFETY cap.

### Merge plan (recommend, do not implement)

1. Extend the `:seon.render/ai` call convention so the dispatcher MAY pass
   a render-context map alongside `:seon.render/entity` (e.g.
   `:seon.render/prior?`, `:seon.render/cap`). Entity-only callers
   (inspector) pass nothing and get sensible defaults; the transcript
   composer passes the full context.
2. Move the `format-eval-row` body into `seon.handlers.eval/render-ai`,
   keyed on the optional render-context: when absent, behave like today's
   compact entity render; when present, produce the rich transcript row
   (ns prompt line, result-var handle, output line, malli-error branch,
   neutralization, derived persistence note).
3. Replace the `ctx.cljs:1561` call with a dispatch through
   `:seon.render/ai` (carrying the context map). Delete the private
   `format-eval-row`. Keep `neutralize-result-claims`,
   `cap-result*`, the caps, and `session-evals` in `ctx` (or move the
   neutralizer to a shared ns the handler can require).

### Risks / blockers

- **Require direction.** `seon.handlers.eval` is a leaf today (requires
  only `clojure.string`). To host the transcript logic it would need
  `seon.eval` (for `scratch-def-note`, `render-malli-error` lives in
  `seon.error.instrument`), the caps, and `neutralize-result-claims`.
  `seon.ctx` already requires `seon.eval`; the merged handler must not
  create a cycle (`seon.eval` must not require `seon.handlers.eval` —
  verify; today it does not). The neutralizer + caps currently live in
  `ctx`; they would have to move to a shared ns or to the handler ns.
- **Render-API change is cross-cutting.** Adding an optional context arg to
  `:seon.render/ai` touches `seon.render`/`visible-entities` and every
  `:seon.render/ai` handler's contract — a wider blast radius than this
  batch. This is the main reason to defer.
- **Behavior parity is load-bearing.** The transcript carries
  anti-fabrication (neutralize), context-SAFETY (caps), and the
  result-var contract. Any merge must preserve all three exactly; a
  regression here corrupts agent context. Needs the full cljs suite +
  a live-agent transcript spot-check as the merge gate.

## 5. The new derived persistence note (Part B)

The reactive "won't persist" note (#7) added in this batch is
`seon.eval/scratch-def-note` (pure, source-derived, no stored attr),
appended in `seon.ctx/format-eval-row` on `:ok`. It lives in the
transcript path ONLY (the inspector handlers do not show it). Post-merge,
it would live in the single renderer and be emitted whenever the rich
transcript context is requested — the inspector card could opt in or stay
without it. Because it is DERIVED (not a stored attr), the merge inherits
it for free: any renderer with the source string can recompute it.
