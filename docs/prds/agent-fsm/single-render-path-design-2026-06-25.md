---
type: prd
status: draft
tags: [prd, agent, render, flow, web]
---

# Single Context Render Path — Design (2026-06-25)

One render path producing the twin (ai + html) in a single pass, stored on
the turn record, and viewed reactively by the inspector (no recompute).
Resolves the F2 divergence (prompt path lacked `:seon.agent/entity`; the
inspector path injected it). Source: a read-only design pass against the live
code. Owner directives folded in: DB-backed + reactive for everything; the
inspector is a live view, not a recompute; tests use the same mechanism;
agents extend context by transacting sections.

## The divergence today (file:line)

- `seon.ctx/context-root` (`ctx.cljs:1824-1847`) builds the root node: pulls
  the agent entity once (`pull-agent-entity` `:1806`/`:1841`), merges
  `core-default-ctx ∪ agent-sections` (`gather-sections` `:1739`), attaches
  `:seon.agent/entity` onto the **root node** (`:1844`) — NOT onto `ctx`.
- `seon.render/render` (`render.cljs:628-648`) threads child input at
  `:640-642`: `in = (assoc ctx :seon.render/node node :seon.render/render …)`
  — passes the **original ctx** (no entity) to children.
- Prompt path: `render-prompt` (`turn.cljs:172-196`) →
  `render/render :ai ctx (context-root ctx)`, `ctx = {:seon.db/db :seon.agent/id}`
  — **no entity**.
- Inspector path: `ctx-sections` (`ctx.cljs:1913-1937`) **recomputes**
  `context-root`, then `:1922` `ctx* (assoc ctx :seon.agent/entity (:seon.agent/entity root))`
  and renders BOTH views against `ctx*`. **This injection is F2.** Three
  renders per inspector view (`render-context-ai :1870`, `render-context-html
  :1900`, `ctx-sections :1923-1924`); html fallback for an ai-only section is
  a map dump (`generic-default-renderer` `render.cljs:561-564`).
- Inspector liveness: `db/listen!` (`inspector.cljs:1749`) → `on-tx`
  (`:1712`) → `schedule-push!` (100ms coalesce `:1690`) → `push-agent!`
  (`:1621`) → `snapshot` (`:247`) → `inspect/ctx-preview` (`inspect.cljs:59`)
  → `ctx-sections` **recompute on every commit** — the recompute to remove.
- The prompt string is **already** transacted per turn:
  `:seon.agent.turn/prompt-chars` datom + `:seon.agent.turn/prompt-file`
  blob (`turn.cljs:71-72`, `:269-274`, full text `:417`). Only the structured
  per-section twin is missing.

## 1. The one render fn + return schema

Add `seon.ctx/render-context` — the single producer. `[{:seon.db/db
:seon.agent/id :as ctx}] → :seon.render/context`. The ONLY place sections
render; `render-prompt`, the inspector, and tests read fields off its result.

One pass:

1. `root (context-root ctx)`.
2. **Thread entity ONCE**: `child-ctx (assoc ctx :seon.agent/entity
   (:seon.agent/entity root))`. Every child (core AND agent) renders against
   `child-ctx` for both views — the structural cure for F2.
3. Render both views once per child: `ai (render/render :ai child-ctx child)`,
   `html (render/render :html child-ctx child)`.
4. Classify `:seon.render/status` → `:failed` / `:empty` / `:ok` (prefer the
   engine emitting an explicit failure flag over string-matching the guard
   markers — see Risks).
5. `apply-agent-budget` (`ctx.cljs:1763`) over the `:ok` agent sections.
6. Bracket (`section-bracket-ai :1754`); stable/volatile by priority ≤
   `stable-priority-max` (`:1732`); join byte-for-byte identical to
   `render-context-ai` (`:1895-1898`).
7. **HTML fallback from the ai text**: when a section's html is nil/blank,
   synthesize `[:pre.themed ai-text]` from the same-pass ai — right pane never
   empty, always mirrors the left, covers agent sections too.

Register near `ctx.cljs:1557` (reuse inspector keys `:seon.render/text` /
`:seon.render/hiccup`):

```
:seon.render/context-section
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.ctx/priority :int]
   [:seon.ctx/agent? :boolean]
   [:seon.render/stable? :boolean]
   [:seon.render/status [:enum :ok :empty :failed]]
   [:seon.render/text :string]        ; post-budget section ai (unbracketed)
   [:seon.render/bracketed :string]   ; exact bytes joined into the prompt
   [:seon.render/hiccup [:or :nil :seon.render.live-tile/hiccup]]]

:seon.render/context
  [:map
   [:seon.render/sections [:vector :seon.render/context-section]]
   [:seon.render/ai :string]          ; THE joined prompt (brackets + boundary)
   [:seon.render/stable-text :string]
   [:seon.render/volatile-text :string]
   [:seon.render/token-estimate :int]]
```

(`:seon.render/ai` is already `[:or :string :symbol]` `render.cljs:78` — do
NOT reuse as a map key; section ai uses `:seon.render/text`.)

Root slots become thin delegates: `render-context-ai` (`:1870`) →
`(:seon.render/ai (render-context …))`; `render-context-html` (`:1900`) →
cards from `(:seon.render/sections …)` `:seon.render/hiccup` preserving the
`[:section {:data-section name} …]` wrapper.

Engine belt-and-suspenders (`render.cljs:640-642`): when `node` carries
`:seon.agent/entity`, bind the recursion handle to a `child-ctx` so authored
nesting inherits it.

**Deleted**: `ctx-sections` (`:1913-1937`, incl the `:1922` injection);
`rendered-section-texts` (`:1858-1868`) folds into `render-context`. No
prompt-path injection is added — the one fn provides the entity.

## 2. Entity threaded from one source

`context-root` already attaches the once-pulled entity to the root node
(`:1844`). Move it from "on the node" to "in the ctx children render against,"
in one place — `render-context`'s `child-ctx`. Delete the inspector's
compensating injection (`:1922`). Both views + all children (core + agent)
see the entity from one source on every path.

## 3. Render once → store → reactive live view

Insight: the inspector left pane must be byte-identical to the prompt AND a
live view that doesn't recompute. Only jointly satisfiable by reading the
**stored render of the turn that ran** — a recompute against a moved-on DB
can't be byte-identical to a past prompt. Storing the render is the
explicitly-allowed category (the eval/turn/message log): a bitemporal record
of *what the agent saw at turn N*. Sections still derive from the DB at render
time; we persist the *result*.

Formulation:

- `render-prompt` (`turn.cljs:172-196`): call `render-context` once; keep
  `(:seon.render/ai result)` as the prompt (byte-identical). Hold the result.
- Persist on the **turn** record (bitemporal; NOT the agent record, which
  overwrites + loses history):
  - tier-1 datom `:seon.agent.turn/token-estimate :int` (new, near
    `turn.cljs:71`).
  - tier-3 blob `:seon.agent.turn/render-file :string` (new, mirror of
    `prompt-file`) — EDN of the full `:seon.render/context` (sections incl.
    html hiccup), written at `open-turn!` (`:269-274`). Big joined ai stays in
    `prompt-file` (rule at `turn.cljs:70` — no 49k text in a datom).
- Reactive trigger, no new mechanism: the existing `open-turn!` tx (already
  asserts prompt-chars/prompt-file) is what the inspector's `on-tx`
  (`inspector.cljs:1712`) sees. Add the two new attrs to that tx → one commit
  → `schedule-push!` → `push-agent!`.
- Between-turns / non-acting agent: inspector shows the **last stored render**
  (the bytes the agent last saw) — correct for byte-identity. Mid-turn
  freshness is the live **tile's** job, already separate (`render-agent-tile`
  `inspector.cljs:293`).

### THE open choice (owner decides) — live-view source

- **DB-durable source of truth** (recommended; matches "DB-backed for
  everything" + survives pod restart): `render-file` is written as turn
  history **un-gated** (not behind debug-capture); the inspector reads it on
  the (coalesced 100ms) push. Optional in-memory read-cache
  (`defonce !last-render-by-agent (atom {})`, keyed by agent-id) for the hot
  path. Cost: a blob read on push.
- **In-memory payload + datom trigger only**: the tx triggers the push; the
  push reads a cross-fiber `globalThis` atom (sub-ms, no file IO,
  capture-independent); `render-file` optional/gated. Cost: live view empty
  after a pod restart until the next turn.

Either is recompute-free and byte-exact. Recommendation: DB-durable source
(owner's stated principle), globalThis as a mere cache, `render-file` un-gated
as first-class turn history. **Do NOT** store the live payload in
`seon.embed.stash` (AsyncLocalStorage is fiber-local `embed/stash.cljs:8-16`;
the inspector fiber can't read it).

### Inspector edits

- `snapshot` (`inspector.cljs:247`): replace the `inspect/ctx-preview` call
  (`:269-270`) with a read of the stored render (cache → blob; first-paint
  fallback to `render-context`). Derive `:ai-text`, `:section-texts`,
  `:html-cards` off that one value. `expand-namespaces-section` (`:188`) +
  `context-bar-data` (`:216`) unchanged.
- `inspect/ctx-preview` (`inspect.cljs:59-106`): re-implement on
  `render-context`/the stored slot; keeps prepending `:system`
  (`effective-system-prompt` `:94`) + `debug-full-prompt` (`:97`); drop its
  separate `render` + `ctx-sections` calls (`:85,89`).

## 4. HTML-twin contract + fallback + follow-up

Contract: every section yields ai + an html twin in the one pass. Custom twin
via `:seon.render/html` (symbol or hiccup literal — already in
`:seon.ctx/section` `ctx.cljs:108-113`). Absent → default `[:pre.themed
ai-text]` from the same-pass ai. Never empty; mirrors the left; covers
agent-added sections (runs over the merged child set).

Sections needing a nice custom twin (follow-up wave; fallback covers them now):
soul + agents (markdown), shared-instructions, namespaces, your-entity
(**in progress**), live-tile (**in progress**; lift the tile hiccup twin),
warnings, open-todos, relevant-source, inventory. `:transcript` already has
`transcript-section-html`.

Failed/loading explicit, not vanishing: keep every section + its
`:seon.render/status`. Prompt drops `:empty` (reactive "warnings vanish");
inspector renders `:failed` as a legible card, may dim `:empty`. Closes the
"section returns '' → silent vanish" gap (`ctx.cljs:1866`) while keeping
reactive emptiness intentional.

## 5. Test mechanism (structural cure)

Canonical helper = `render-context`. Tests build a real-path ctx
`{:seon.db/db @db/*conn* :seon.agent/id <id>}` — **never** hand-inject
`:seon.agent/entity` — and assert on the structured result. No divergent ctx
exists for a test to validate a fiction.

- Migrate `live-tile-section-stable-on-composer-input` (working tree) to one
  assertion over `render-context` (`:live-tile` section `:status :ok`, no
  `⚠`/malli).
- Add: appear/vanish (warning `:empty→:ok`/back, in/out of joined ai);
  byte-stable-between-renders (two calls, no tx → identical ai); twin-coverage
  (every section non-nil hiccup incl. an agent ai-only section);
  **prompt==inspector** (the join equals the inspector's left-pane derive).
- Replace `ctx-sections` tests (fn deleted); search test/seon for
  `ctx-sections` / `:seon.agent/entity` / `context-root` / `render-prompt`.

## 6. Agent-authored sections — one uniform path

No new architecture. `gather-sections` (`ctx.cljs:1739`) already merges
core ∪ agent by priority with override-by-name; `render-context` renders the
merged children → identical twin resolution, budget (`apply-agent-budget`
charges only `:seon.ctx/agent?` `:1771`), bracket, stable/volatile, store,
reactive push. The html fallback covers ai-only agent sections. The stored
result is the merged render, so the reactive inspector shows a new agent
section on its next turn. Discoverable: `seon.agent/add-section!` /
`remove-section!` / `update-ctx!` (`agent.cljs:24,541-582`); shape
`:seon.ctx/section` (`ctx.cljs:108-113`). Add one catalog line: "add a section
= transact a `:seon.ctx/section` (or `add-section!`); ai-only is fine, the
inspector renders a default twin."

## Step-ordered sequence (each hot-reloadable, no restart)

1. Schemas (`ctx.cljs:~1557`): register `:seon.render/context-section` +
   `:seon.render/context`. REPL round-trip.
2. `render-context` (new, beside `ctx-sections`): one pass, `child-ctx`
   threading, status, budget, bracket/boundary join (mirror `:1878-1898`
   exactly), html fallback. Verify `(:seon.render/ai (render-context ctx))`
   byte-equals the old `render/render :ai ctx (context-root ctx)`.
3. Engine belt-and-suspenders (`render.cljs:640-642`).
4. Thin root slots (`ctx.cljs:1870,1900`) → delegate.
5. Switch consumers (`turn.cljs:191-195`, `inspect.cljs:85-102`) →
   `render-context`; **delete `ctx-sections`**; fold `rendered-section-texts`.
   Verify prompt bytes unchanged; inspector unchanged.
6. Store + live slot: write the slot + tier-1/tier-3
   (`token-estimate`/`render-file`) in `render-prompt`/`open-turn!`
   (`turn.cljs:269-274`); add turn schema attrs (`turn.cljs:71,91-103`).
7. Inspector reads the store (`inspector.cljs:269-270`); first-paint fallback.
   Verify SSE push live on turn boundary, no per-tx recompute.
8. Html fallback + status surfacing in the card path
   (`inspector.cljs:280-284,450`).
9. Tests: migrate + add canonical structural/byte-stable/appear-vanish/
   twin-coverage/prompt==inspector; remove `ctx-sections` tests.
10. Follow-up wave (separate): author nice custom twins for the 10 ai-only
    sections (your-entity + live-tile already in progress).

## Risks

- **Byte-identity**: step 2's join must reproduce `ctx.cljs:1878-1898` exactly
  (brackets, `\n\n`, boundary `stable-boundary-delim :1586`). Guard with the
  byte-stable test BEFORE deleting old code.
- **Fiber visibility**: live render in a `globalThis` atom (cross-fiber), NOT
  `embed.stash` (AsyncLocalStorage, fiber-local).
- **Concurrent agents**: key the slot by agent-id; never one global slot.
- **Capture-off liveness**: pure-datom requires `render-file` written un-gated.
- **Status false-positives**: classify `:failed` via an explicit engine flag,
  not by matching guard marker strings.

## Critical files

`src/seon/ctx.cljs`, `src/seon/render.cljs`, `src/seon/agent/turn.cljs`,
`src/seon/web/inspector.cljs`, `src/seon/agent/inspect.cljs`.
