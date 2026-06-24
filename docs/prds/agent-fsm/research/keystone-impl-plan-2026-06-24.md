---
type: research
status: active
tags: [research, agent, schema, flow]
---

# Keystone implementation plan — collapse 3 render systems into 1

Read-only design pass for the keystone unit (context-render.md Sequence step 2).
Build to this. Live checks marked "verify-live-before-building" are for the build
agent on a FRESH cluster / private REPL — never the shared pod mid-flight.

## 1. The 3 render systems (current state)

- **A — section composer (`seon.ctx`).** `assemble-context` (ctx.cljs:1717-1822)
  pulls the agent, `merge-sections` (1604-1618) unions `core-default-ctx`
  (1497-1551) with the agent's `:seon.agent/ctx` (override-by-name), renders via
  `render-section` (1625-1649) / `render-section-html` (1651-1672),
  `apply-agent-budget` (1674-1715), computes the stable/volatile split by
  `(.indexOf names :namespaces)` (1780), joins with the in-band `stable-boundary`
  line (1463-1480). Callers: the prompt path `seon.agent.turn/render-prompt`
  (turn.cljs:191-204, default slot `'seon.agent/assemble-context`,
  agent.cljs:135) and the inspector `seon.agent.inspect/ctx-preview`
  (inspect.cljs:59-97). Section string-builders wired by symbol:
  `seon.ctx.{namespaces,transcript,inventory,live_tile,warnings,relevant,
  your_entity}` + `seon.agent.todo/open-todos-section`; each emits its own
  `;; ── x ──` header.
- **B — entity renderer (`seon.render`).** Symbol dispatch over `:seon.schema`
  rows: `entity-primary-kind` → `entity-render-slot` (render.cljs:300-318,
  per-entity override wins) → `render-entity-ai` (468-490) / `render-entity-html`
  (320-348). `ai-render`/`html-render` (158-183) = slot-resolution primitive.
  `render-agent-tile` (377-466) = the SCI-bounded seed.
- **C — six per-kind handlers (`seon.handlers.*`).** `eval, fn, ns, schema, test,
  message` export `render-ai`/`render-html`, shape
  `(fn [{:seon.db/db :seon.render/entity :seon.agent/id}] {…})`; they're the
  kind-default symbols in schema props, resolved by System B.

**Deletions the keystone makes (same patch):** `assemble-context`,
`merge-sections`, `render-section`, `render-section-html` (ctx.cljs); the
`:seon.render/text` second arm of `:seon.render/ai-response` (render.cljs:106-113)
+ the `:text` tolerance read in `render-entity-ai` (482-484); the per-section
`;; ── x ──` headers in converted sections. `apply-agent-budget` must be CARRIED
into `context-root` (not orphaned) — see §6.

## 2. Sub-step sequence (compiles + renders after each; no-parallel-system enforced at the COMMIT)

- **(a) additive render-core.** Add OPTIONAL render-control attrs beside
  `:seon.ctx/section` (ctx.cljs:96-109): `:seon.render/clip` (namespaced-key map),
  `:seon.render/hidden?`, `:seon.render/children`; reuse `:seon.render/ai`/`/html`/
  `:seon.ctx/priority`; make `:seon.ctx/section` a superset of the renderable. Add
  `renderable-id`, `renderable-inst`, `resolve-slot`, `generic-default-renderer`,
  `schema-default-renderer` (wraps existing `entity-render-slot` dispatch — reuse),
  recursive `render` (view-bound `:seon.render/render` handle) in `seon.render`.
  Generalize `render-sci/invoke-bounded` to 3-arity `(sym input view)` →
  value | `{:interrupt}`/`{:fallthrough}` (Decision 3); keep the tile call site
  working. Purely additive, zero callers change.
- **(b) converters.** Rewrite the 6 handlers' destructure key
  `:seon.render/entity` → `:seon.render/node` and return the BARE value (String /
  hiccup), not the envelope — these ARE the message/eval/ns converters. Update
  `entity-render-slot`'s callers to assoc `:seon.render/node` (one key; System B is
  absorbed). Eval converter carries forward the fabrication-guard + caps.
- **(c) context-root + repoint both consumers.** Add `context-root` (children =
  core converters ∪ agent `:seon.agent/ctx` override-by-id ∪ derived rows, sorted
  by static `:seon.ctx/priority`); its `:ai` is the section renderer (render each
  child via the handle, join with the fixed delimiter, each child emits its
  `;;; ┌─/└─` bracket). Repoint `render-prompt` (turn.cljs:204) →
  `(render :ai ctx (context-root ctx))` (returns a bare String — adjust the
  `:seon.render/text` extraction). Repoint `ctx-preview` (inspect.cljs:80) →
  `(render :ai …)` + `(render :html …)`. ONE render, two consumers. Soul block-1
  stays (P3 moves it).
- **(d) delete the parallel systems.** Delete `assemble-context`/`merge-sections`/
  `render-section`/`render-section-html`, the `:seon.render/text` ai-response arms,
  the per-section headers. Repoint or keep-resolvable the
  `'seon.agent/assemble-context` fallback symbol. ENFORCES no-parallel-system →
  same commit as (c).

## 3. The transcript seam with P1

Keystone makes the transcript a renderable CHILD of `context-root` NOW but does
NOT touch its internals (P1 owns `inbound-by-turn`/`render-turn`/no-turns branch).
Wrap the existing `transcript-section` (already returns a String) as a
section-renderable child `{:seon.ctx/name :transcript :seon.ctx/priority 80
:seon.render/ai 'seon.ctx.transcript/transcript-section :seon.render/clip :none}`.
P1 later replaces the fn body with the flat event query (per-event message/eval
converters via the handle). Keystone does NOT add per-event handle-recursion
inside the transcript — that's P1's "one model".

## 4. Keystone converters vs interim wrapped sections

- **Converters now:** message, eval (handler rewrite), ns (`render-one-ns-ai/html`
  reuse). **todo** → wrapped section-renderable now; per-todo `todo->renderable` is
  P4. **doc** → NOT created in keystone (`seon.ctx.doc` is P3); soul stays block-1.
- **P2-deferred (inventory, live_tile, warnings, relevant, your_entity):** ride as
  wrapped, header-stripped, body-unchanged String-returning section-renderables.
  Keystone removes their `;; ── x ──` header (bracket replaces it); P2 rewrites the
  body to a renderable-returning converter + HTML twin (+ inventory data-only,
  your_entity removal).

## 5. Cache wiring (no second mechanism)

`context-root`'s `:ai` section renderer joins children by `:seon.ctx/priority`. The
breakpoint is a thin POST-step on the joined String: insert the EXISTING
`stable-boundary` delimiter (same byte string) at the static stable→volatile
priority transition (replacing the `.indexOf :namespaces` heuristic). `split-context`
finds it unchanged; `anthropic.cljs:139-163` is UNCHANGED (2 breakpoints; 3rd
deferred, Decision 5). Verify `cache_read_input_tokens > 0` on call 2 (Haiku) +
as-of diff diverges only at the append point.

## 6. Risks & spec ambiguities

- **Require cycle (central decision).** `seon.render` is the LOW ns (requires db/
  eval/schema, NOT ctx/handlers). Converters CANNOT live in `seon.render` (they
  need agent/ctx) — they STAY in their domain namespaces, resolved by SYMBOL via
  `eval/lookup-value`. **`context-root` lives in `seon.ctx`, NOT `seon.render`** —
  it needs `core-default-ctx` + the agent pull. THIS CONTRADICTS the spec's
  Functions list ("seon.render: … context-root"); build to `seon.ctx`. (Spec
  correction needed.)
- **`render-prompt`/inspector return-shape seam.** `(render …)` returns a bare
  String; `render-prompt` + `ctx-preview` currently extract `:seon.render/text`
  from a map. Missing this silently breaks the prompt.
- **`:seon.render/text` key survives PARTIALLY.** `:seon.ctx/section-text`,
  `:seon.render/assemble-response`, `:seon.agent.inspect/section-text` still use it
  as the section-text carrier. Only the AI-RESPONSE `:text` arm dies.
- **`apply-agent-budget`** (ctx.cljs:1674-1715) is called only by
  `assemble-context`; deleting it orphans the budget. Carry it into `context-root`
  as a post-step (P7 retokenizes). Do not silently drop agent-section budgeting.
- **HTML twin per-item (P5-verifier finding):** the section renderer must render
  each child (incl. each eval) via its `:html` slot per-item, NOT a section-level
  `pretty-html` dump of the raw entity.

## 7. Sizing & commits

- **Commit 0 (precedes, done):** P5 de-stub + lifecycle split.
- **Commit 1 (THE keystone):** sub-steps (a)+(b)+(c)+(d) as ONE atomic patch — the
  swap (c) and delete (d) cannot be separated without a parallel/broken system.
  `apply-agent-budget` carried into `context-root`; `anthropic.cljs` unchanged.
  Gate: green `bin/test-cljs` + DeepSeek drive no-worse than baseline +
  `cache_read > 0`.

## Top 3 risks (summary)

1. **Require cycle** → converters stay in domain nses (symbol-resolved);
   `context-root` in `seon.ctx` not `seon.render` (spec correction).
2. **Return-shape seam** → `render` returns a bare String; rewire `render-prompt` +
   `ctx-preview` off `:seon.render/text`.
3. **Cache boundary as a 2nd mechanism** → must FEED the existing in-band
   `stable-boundary`/`split-context` (same byte string at the priority transition);
   carry `apply-agent-budget` into `context-root`.
