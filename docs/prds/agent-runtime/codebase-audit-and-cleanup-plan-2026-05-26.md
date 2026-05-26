---
type: research
status: draft
tags: [research, agent, runtime, web, cleanup]
---

# Agent-runtime + inspector + render: codebase audit and cleanup plan (2026-05-26)

## TL;DR — honest assessment

The "empty inspector + dead chat box" bug is **mostly an empty-state bug, not a wiring bug**. The pipeline (POST → wake → LLM → eval → tx → SSE → morph) actually does fire end-to-end and was just exercised live during this audit: DeepSeek returned a real reply, evals were recorded with `:seon.render/ai` and `:seon.render/html` symbols stamped, the inspector tx-listener pushed three patches per commit, and the per-agent SSE stream delivered them. What Sean saw was a **fresh agent with zero entities scoped to it** (the per-agent `d/filter` correctly returned nothing) plus a chat box that gives **zero visual feedback when 204s land** — no spinner, no optimistic echo, no error toast — so a slow LLM round-trip looks identical to a dead endpoint.

But: the audit also surfaced real problems. Two parallel SSE / broadcast subsystems run side-by-side. The render pipeline is a single naïve "all `:seon.render/ai` entities sorted by tx-time" query — none of the section structure Sean's vision describes exists. There is no register-and-override mechanism for renderers; everything is hardcoded symbol-on-entity. Retro-stamp DID run (verified, 0 entries stamped because no pre-existing renderable entities matched the marker attrs) but its purpose is undercut by the fact that the substrate doesn't HAVE specs/fns/ns entities seeded yet — those handlers exist (`seon.handlers.fn`, `seon.handlers.schema`, `seon.handlers.ns`) but **nothing writes those entity types into the DB at boot**, so the cards never appear. We built renderers for entities that don't exist.

Of the seven recent feature commits, my assessment is: **3 fully deliver what their messages claim, 2 partially deliver (machinery is wired but the data shape never appears in production), and 2 deliver less than the message implies** (see §2).

---

## §1 — Sean's vision, encoded

These are the principles the cleanup plan must converge to. They are quoted-in-spirit from the brief and become the acceptance criteria for §6.

### V1 — Single understandable system for reads, writes, and rendering

There is one mechanism that decides what the agent sees, what the user sees, and what gets persisted. Not three parallel projections (broadcast tile / inspector pane / AI ctx). The browser view a developer opens at `/agent/<id>` is **the same data, rendered the same way**, that the LLM gets in its prompt — they differ only in surface format (HTML hiccup vs plain text). When the developer queries the system from the REPL with `(ctx-preview {:seon.agent/id …})`, the result equals what the LLM would receive on its next turn, byte-for-byte. No more "I think the agent has these entities."

### V2 — Per-agent customization via registration override

The substrate registers default rendering in `seon.agent` namespace (or a stable substrate ns like `seon.render.default`). Each agent's home ns (`seon.agent.<id>`) is a long-lived REPL-defined namespace; an agent customizes its own context simply by **calling the same registration function with the same name from its own ns**. This is the exact pattern `schema/register!` already uses for schemas — one verb, idempotent upsert by name. The mechanism is data-driven (entities in the DB), not code-driven (no protocol dispatch, no multimethods).

### V3 — Context-caching-aware ordering

LLM providers (Anthropic, OpenAI, DeepSeek) cache **prefixes**. The render output must be structured so the stable parts come first and the dynamic parts last:

1. Schema reference (specs the agent can transact) — never changes
2. Functions in related namespaces — compact, brief signatures
3. Functions in the current agent's namespace — full detail, including the tests that exercise them
4. Eval / message history — most volatile, lives at the tail
5. Most-recently-changed entities sit at the very end

When the agent defines a new fn, the entity for that fn lands with a fresh tx-time, naturally surfacing at the tail. When older entries scroll past the budget, they are **truncated**, not promoted. The render is conceptually a stable-prefix + dynamic-tail composition.

### V4 — Section-with-detail-levels

The same entity can render `:full`, `:compact`, or `:hidden` depending on the section that owns it. A fn defined in a *related* ns renders as one line (signature + first line of doc). The same fn entity referenced from the *current* ns renders as full source + tests. The detail level is **decided by the section that places the entity in the context**, not by the entity itself.

### V5 — Substrate defaults, agent overrides

Substrate registers a set of named sections in `seon.agent` namespace: `:specs`, `:related-ns-fns`, `:current-ns-fns`, `:eval-history`, plus optionally `:messages`, `:errors`. Each section is a fn `(db, agent-id) → seq-of-entity-render-instructions`. An agent overrides any section by **calling the same registration verb from its own ns**, supplying its own fn. No protocol indirection. Just data.

### V6 — Code IS data IS render

A `:seon.fn` entity is the source-of-truth for both "what the agent can call" AND "what the agent sees in its context as a callable." A `:seon.schema` entity is both "what the agent can transact" and "what appears in the schema-reference section." The renderer walks the same entities the analyzer wrote at eval-time. We do not maintain a separate `:seon.ctx/*` projection.

---

## §2 — Live state vs claimed state

Pod is alive (`pid=24295`, port 7890). Live verification done at 2026-05-26 01:57Z against agent `DEy-2605251730`.

### Live probe results

| Probe | Result |
|---|---|
| `bin/seon status` | pod and cljs-watch alive |
| `GET /agents` | One agent listed: `DEy-2605251730`, state `idle`, 0 turns |
| `GET /agent/<id>` initial HTML | "(empty context)" in AI pane, "no renderable entities" in HTML pane, `1 handlers` |
| `inspect/ctx-preview` (pre-POST) | `{:seon.render/text "" :seon.render/entities [] :seon.render/token-estimate 0}` |
| `:seon.render/ai` entity count (pre-POST) | 0 |
| Total entity count (pre-POST) | 88 (schema entities + agent + session + handler) |
| `POST /chat?agent=…` with `text=hello+test` | 204, LLM round-trip succeeded, two assistant messages + one eval recorded, `:seon.render/ai`/`html` stamped on all three |
| `:seon.render/ai` entity count (post-POST) | 4 |
| `assemble-ai-context` (post-POST) | Returns 4 entities, real text, 162 token estimate |
| Inspector SSE push log | `:conns 1, :chars 588` then `649` — pushes did happen |

### Commit-by-commit reality check

| Commit | Claim | Reality | Verdict |
|---|---|---|---|
| 51b8d27 multi-world isolation (sidecar-poc) | N parallel JVM writers | sidecar-poc/ workspace, not exercised by the live pod | Out of scope for this audit |
| 14d4ef4 entity rendering for fn/schema/ns | Consistent + useful cards | Renderers exist (`handlers/fn.cljs`, `schema.cljs`, `ns.cljs`) but **no path writes these entity types into the DB at runtime**. The renderers are wired through `retro-stamp/kind->symbols` but the marker attrs (`:seon.fn/sym`, `:seon.schema/key`, `:seon.ns/name`) have zero rows. | PARTIAL — renderers exist, entities don't |
| 542948e guest-side transact-batch! | wire op + overlay | sidecar-poc — not exercised by main pod | Out of scope |
| 88e7bcc test-runner Phase 1 | universal test entrypoints | Exists, separate workstream | Not in scope |
| dff6651 parse fence-stripping | tolerate markdown code fences | `seon.parse` exists; the live log shows the agent emitting bare `(defn …)` (no fences) so the fence-strip path didn't trigger this run. Code is present and unit-testable. | UNVERIFIED in production |
| d0d7327 retro-stamp + markdown narration | retro-stamp at boot, marked.js in HTML pane | retro-stamp IS called in `start-agent!` (line 629) and runs successfully — but it stamps 0 rows on a fresh DB because no `:seon.fn` / `:seon.schema` / `:seon.ns` / pre-existing message rows exist. marked.js IS loaded (verified in served HTML). | PARTIAL — runs, does nothing useful yet |
| b00463e tx batcher | sidecar — out of scope | n/a | Out of scope |
| e649cea inspector polish (chat input, hljs) | Source-first eval render, hljs, chat input | Chat input form rendered correctly. hljs script tag present. Source-first eval rendering verified in `handlers/eval.cljs/render-html`. | PASS |
| 29372b9 POST→eval→inspector round-trip | full round-trip | POST 204, LLM call succeeds, eval lands, render symbols stamped on the user message, assistant message, and eval entity. SSE push fires. | PASS (the round-trip works) |
| 0734a8a multi-agent wasm-guest + JVM sidecar | sidecar PoC | Out of scope | Out of scope |
| e8cff05 inspector — see what the agent sees | AI + HTML, SSE-live | Inspector pane infrastructure works end-to-end. The "empty" state was confused for "broken" because there was no first-run seed content. | PASS for the infrastructure |
| 7a5ae04 v0 shell — handler/register! + wake + agent-view + inspect | First runtime shell | Working — wake handler registered, agent-view filters correctly, `inspect/ctx-preview` returns correct empty result on a fresh agent | PASS |

**Summary:** The inspector + chat + LLM + render pipeline DO work. The failures the user observed are:

1. **Cold-start emptiness:** A fresh agent has nothing scoped to its id, so the per-agent filter correctly returns 0 entities, which the inspector correctly displays as "(empty context)". This is correct behavior — but the *display* gives no hint that submitting a message will populate it.
2. **Chat-box silence:** Submitting POSTs the message and the LLM round-trip takes ~2-3 seconds. During those seconds the UI offers no feedback whatsoever — no spinner, no optimistic user-message echo. To Sean it looks dead.
3. **Renderers for ghost entity types:** The eval/fn/schema/ns cards never appear because we built renderers ahead of the writers that would create those entities. The agent's `(defn greeting …)` form runs but no detect-and-tee converts it into a `:seon.fn` entity.

---

## §3 — The architecture today, traced in code

Path of a user message from browser to render, file-by-file:

| Step | File | Verb |
|---|---|---|
| 1. User opens `/agent/<id>` | `seon.web.inspector/handle!` → `inspector-shell` | Returns full HTML including chat form |
| 2. SSE opens via `data-init="@get('/agent/<id>/sse')"` | `inspector/open-agent-sse!` | Registers conn in `!sse-by-agent`, sends initial snapshot |
| 3. User submits chat | inline JS `fetch('/chat?agent=<id>', POST)` | Form intercept; clears input on 200/204 |
| 4. Server receives POST | `seon.web.serve/handle-chat!` | Calls `agent/chat agent-id text` |
| 5. `agent/chat` | `seon.agent/chat` | Wraps in `db/with-agent`, transacts `{:seon.message/role :user …}` with `:seon.render/ai`/`:html` symbols stamped, tx-meta carries agent-id |
| 6. tx-listener fires | `agent/user-message-handler` | Detects `:seon.message/role :user` datom on agent's listener; if agent state ≠ `:running`, schedules `(run-agentic-loop! input)` on next tick |
| 7. Agent loop | `agent/run-agentic-loop!` → `run-turn!` → `ask-and-eval!` | Renders prompt via `render-prompt` → calls `llm-fn` → `eval-batch!` |
| 8. LLM | `seon.ai.deepseek/agent-adapter` | DeepSeek API call (active in live pod) |
| 9. Eval | `seon.eval/eval-batch!` → `record-eval!` | Parses LLM text (stripping fences via `seon.parse`), evals each form against bootstrap compile-state, records each eval as `:seon.eval` entity with render symbols stamped |
| 10. Detect-and-tee | (intended in eval batch) | **Currently does NOT write `:seon.fn` / `:seon.schema` / `:seon.ns` entities** when the agent evals `(defn …)` / `(s/register! …)` / `(ns …)`. The handlers/renderers exist; the writers do not. |
| 11. Inspector tx-listener | `seon.web.inspector/on-tx` | Reads tx-meta agent-id, coalesces 100ms, pushes morphs to that agent's SSE conns only |
| 12. Snapshot | `inspector/snapshot` → `render/assemble-ai-context` | Walks `:seon.render/ai` entities in agent's filtered view, oldest-first by `:last-tx`, render-one per entity, joins with blank-line |
| 13. HTML pane | `inspector/render-entity-hiccup` | Per entity, calls `render/html-render` (resolves symbol → fn) and emits hiccup |
| 14. SSE push | `inspector/push-agent!` | Writes three `datastar-patch-elements` events (header, ai pane, html pane) |

Parallel path that **also fires on every tx** but writes to a different SSE stream:

| Step | File | What it does |
|---|---|---|
| 1. tx-listener fires (separately) | `seon.web.broadcast/broadcast-on-tx` | Iterates ALL running agents, computes the `default/view` hiccup per agent, diffs against `!last-rendered`, emits a `datastar-patch-elements` event to **the legacy `/sse` stream** (`seon.web.sse/emit-patch!`) |
| 2. Live log evidence | `[seon.web.sse] emit-patch! {:conns 0, :wrote 0}` | The legacy /sse stream has **zero consumers** in the live pod — broadcast renders and writes to nothing |

So `seon.web.broadcast` is doing real work — computing per-agent hiccup on every tx — and shipping it into the void. This is pure waste plus a maintenance hazard.

---

## §4 — The renderer specifically

Read of `seon.render` against Sean's vision:

| Vision principle | Today's reality | Gap |
|---|---|---|
| Sectioned: specs / related-ns / current-ns / eval-history | Flat: one query for `[?e :seon.render/ai _]`, sorted by tx-time | Total miss — no section concept exists |
| Detail levels per entity | Single render symbol per entity, no per-call variation | Total miss — renderer has no notion of `:compact` vs `:full` |
| Per-agent override | An agent can transact a fn on its own entity with `:seon.render/ai` pointing at its own ns's fn — that works at the entity level. But there is no notion of a per-section override registered in the agent's ns. | The substrate hardcodes "every entity carrying `:seon.render/ai`" — the structure is entity-driven, not section-driven |
| Stable prefix vs dynamic tail | Partially present via `:seon.sticky/position :prefix` — but no entities use it today | Implemented but unused; needs to be the default ordering primitive, not an opt-in |
| Token budget | Coarse `(quot (count text) 4)` after the fact, no truncation | The renderer concatenates everything, then reports the size. No section is told to truncate. |
| `ctx-preview = browser view` | TRUE — both go through `assemble-ai-context`. This is good. | Keep. |

The closest the substrate gets to Sean's vision is `seon.render.default/ctx`, which composes named blocks (`repl-state-header`, `how-you-respond`, `what-you-can-do`, `conventions`, `recent-conversation`, `recent-evals-block`, `recent-errors-block`, `schema-reference`). But: **this fn is no longer wired into the live renderer.** `assemble-ai-context` never calls it. The default-ctx blocks are dead code in the new tx-log-as-context regime. The agent's prompt is now whatever happens to carry `:seon.render/ai`, which is currently only `:seon.message` and `:seon.eval` entities — so the agent gets no schema reference, no convention reminder, no "how you respond" preamble in the new path.

This is the single biggest "we built the wrong thing" finding in §6.

---

## §5 — Spaghetti map

Each item: location, what's wrong, severity.

### Red (production-broken or wasteful)

- **R1. Two parallel SSE/broadcast subsystems.** `seon.web.broadcast` (legacy `/sse`, 0 consumers) and `seon.web.inspector` (per-agent SSE, 1+ consumer). Broadcast computes hiccup per tx per agent, diffs, emits to nothing. Both register independent tx-listeners on the same conn. Files: `src/seon/web/broadcast.cljs` (194 LOC), `src/seon/web/sse.cljs` (59 LOC), `src/seon/web/inspector.cljs` (528 LOC).
- **R2. `seon.render.default/ctx` orphaned.** The substrate's most thoughtfully composed renderer (the named-section pattern Sean's vision wants) is unreferenced by the new `assemble-ai-context`. New agents get no schema reference, no conventions block — only their own message log. File: `src/seon/render/default.cljs`.
- **R3. Renderers without writers.** `seon.handlers.fn`, `seon.handlers.schema`, `seon.handlers.ns` plus their entries in `retro-stamp/kind->symbols` are wired, but the agent's `(defn …)`, `(schema/register! …)`, and `(ns …)` forms never produce these entities. Detect-and-tee in `eval-batch!` is the missing piece (referenced in `client.cljs:99` comment as Phase B item 10, supposedly done in commit `e425f79`). Verify in `seon.eval/eval-batch!` whether `detect-and-tee` actually writes these.
- **R4. Empty-context UX trap.** `inspector/ai-pane-fragment` displays the literal string `"(empty context)"` when text is blank. No call-to-action, no hint that submitting a chat will populate it. Looks like a broken page.
- **R5. Dead chat box UX.** `inspector/chat-bar-fragment` POSTs and waits silently for SSE. Round-trip is 1-3s. No spinner, no optimistic echo, no error toast. User concludes "send did nothing." (The send actually works — log proves it.)

### Yellow (works but wrong shape)

- **Y1. `retro-stamp` runs against marker attrs that have no rows.** `kind->symbols` lists `:seon.eval/source :seon.message/content :seon.fn/sym :seon.schema/key :seon.ns/name`. The first two ARE populated by the live pipeline; the latter three never get written. retro-stamp is silently a no-op for 3 of 5 kinds. Not wrong per se, but obscures that the pipeline is incomplete.
- **Y2. `:seon.render/ai` and `:seon.render/html` are duplicated per entity.** Every message, eval, fn, schema, ns row carries both symbols inline. This is fine for v0 but is essentially N copies of a fixed lookup; an indirection (e.g., `:seon.entity/kind :message` → `(:render/ai (kind-config :message))`) would be more honest about what's actually varying.
- **Y3. `pod-host/sidecar-poc/` is rapidly diverging from `src/seon/`.** Five recent commits (51b8d27, 542948e, 88e7bcc, b00463e, 0734a8a) are sidecar work. The live pod is the CLJS pod; the sidecar PoC is exploratory. Right now they share branch but no shared code. Not a bug, but unclear which is the primary line of investment.
- **Y4. `seon.handler/register!` exists; `seon.render/register-section!` does not.** The substrate has one half of the register-and-override pattern but not the half Sean's vision actually needs.

### Green (works, just inconsistent naming)

- **G1. `ai-render` / `html-render` in `seon.render` are NOT used by `assemble-ai-context`.** That fn inlines its own resolution (`render-one`). The two-function API is dead duplication of the same resolve-symbol-and-call dance. Same in `inspector/render-entity-hiccup` which inlines a third copy.
- **G2. Comment in `seon.handler` says "Today, the dispatcher does not auto-fire (no `d/listen!` bus yet); the user-message trigger lives in `seon.agent`."** The per-agent `install-user-trigger!` IS a `d/listen!`. It just isn't reading the handler registry. So we have a handler entity for `wake/on-message` that nobody dispatches against, and a per-agent listener that hardcodes the wake logic. Two implementations of the same idea.
- **G3. `inspect/ctx-preview` vs `render/assemble-ai-context`.** `inspect` is a paper-thin wrapper. Either fold inspect into render, or make render the private impl and inspect the public verb.

---

## §6 — Cleanup plan

Sequenced. Each action has acceptance criterion.

### Phase A — Critical-fix (Sean can use it tomorrow)

**A1. Chat-box optimistic echo + send-state indicator.** When the user submits, immediately render the user message into the HTML pane locally (or via an instant SSE patch on the user-message tx that the inspector already produces). Add a spinner or "…" indicator while the agent's state is `:running`. Files: `seon.web.inspector` (chat-bar-fragment + inline JS). ~30 LOC. Acceptance: typing "hello" and hitting send shows the message in the pane within 100ms, regardless of LLM latency.

**A2. Cold-start prompts the user.** Replace `"(empty context)"` with a one-screen onboarding: "Send a message to wake this agent. The full context will appear here once the first turn completes." Files: `seon.web.inspector/ai-pane-fragment`. ~10 LOC. Acceptance: a fresh inspector shows guidance, not the appearance of brokenness.

**A3. Verify detect-and-tee actually writes `:seon.fn` / `:seon.schema` / `:seon.ns` entities.** Read `seon.eval/eval-batch!` and trace whether `(defn greeting …)` produces a `:seon.fn` row. If yes, the renderer cards should appear after the next chat — verify in browser. If no, that's the gap, fix at the write site. Files: `seon.eval`, possibly `seon.analyzer-info`. ~50 LOC. Acceptance: after one chat that defines a fn, the HTML pane shows an fn card.

**A4. Delete `seon.web.broadcast` and `seon.web.sse`.** They have zero consumers and the inspector path replaces them entirely. Files: delete `broadcast.cljs`, `sse.cljs`, drop the requires from `client.cljs`. ~250 LOC removed. Acceptance: pod boots, inspector still updates on every tx.

### Phase B — Alignment (one mechanism, not two)

**B1. Single render entry point: `seon.render/render-ai` and `seon.render/render-html` are the only resolve-and-call fns.** Delete `assemble-ai-context`'s inline `render-one`, the inspector's inline `render-entity-hiccup` per-entity dance. All three call sites converge on the two named fns. ~40 LOC. Acceptance: grep shows exactly one place each per surface that resolves a `:seon.render/*` symbol.

**B2. Fold `seon.inspect` into `seon.render`.** `ctx-preview` is `assemble-ai-context` with `:seon.render/text` extracted. `visible-entities` is the same call's `:entities` key. `handlers` belongs in `seon.handler`. Delete `seon.inspect`. ~70 LOC. Acceptance: REPL still has `(seon.render/ctx-preview …)`; `seon.inspect` ns no longer exists.

**B3. Replace per-agent user-message trigger with handler-registry dispatch.** Today `agent/install-user-trigger!` hardcodes the wake logic per agent. The handler registry has a `:wake/on-message` entry that nothing reads. Install a single `seon.runtime/dispatch` `d/listen!` that reads the registry. Per-agent boot no longer installs its own listener. Files: `seon.agent`, new `seon.runtime`. ~150 LOC. Acceptance: removing the registry entry stops the agent from waking; adding a new handler entity wakes it on its trigger attr.

**B4. Decide sidecar-poc fate.** Either promote some of its patterns into the main pod or move it under `pod-host/` clearly marked exploratory. Not blocking but the parallel-branch drift will compound. Acceptance: a developer reading `feature/agent-runtime` knows which subdir is the production target.

### Phase C — Vision (the section-with-override system)

**C1. Define `seon.render/register-section!`.** Mirror of `schema/register!`. Body persists a `:seon.render/section` entity:

```clojure
(schema/register! :seon.render.section/name      :keyword)            ; e.g. :specs, :current-ns-fns
(schema/register! :seon.render.section/agent     {:optional true} :seon.db/ref)  ; nil ⇒ substrate
(schema/register! :seon.render.section/fn        :symbol)             ; (db, agent-id, opts) → seq-of-entries
(schema/register! :seon.render.section/order     :int)                ; stable position in the prefix
(schema/register! :seon.render.section/detail    [:enum :full :compact :hidden])
(schema/register! :seon.render.section/budget    {:optional true} :int)
```

Composite identity on `[:seon.render.section/name :seon.render.section/agent]`. ~80 LOC plus the schema bridge.

**C2. Implement substrate default sections in `seon.agent` ns** (or `seon.render.default`, but Sean's wording says `seon.agent`):

- `:specs` — pulls all registered `:seon.schema` entities. Detail `:compact` (one line per schema name+shape).
- `:related-ns-fns` — pulls `:seon.fn` entities whose `:seon.fn/ns` is in the agent's known-related set. Detail `:compact` (signature + first doc line).
- `:current-ns-fns` — pulls `:seon.fn` entities whose `:seon.fn/ns = (agent/home-ns id)`. Detail `:full` (source + tests).
- `:eval-history` — last N evals chronologically. Detail `:full` for the last 5, `:compact` for older. Truncates oldest first.
- `:messages` — conversation log. Detail `:full` recent, `:compact` older.

Each section returns a vec of `{:seon.render/entity <eid-or-map> :seon.render/detail <kw>}`. ~250 LOC across the section fns.

**C3. New `assemble-ai-context` walks sections in order.** For each section, call the fn, pass the entries to a renderer that respects detail level. Concatenate. Older sections are stable prefix (substrate sections at low orders); eval-history sits last with dynamic tail. ~80 LOC rewrite of the existing fn. Acceptance: `(ctx-preview …)` returns text in [specs, related-ns, current-ns, evals, messages] order; appending an eval moves only the tail; a new fn def lands in current-ns-fns without disturbing prefix.

**C4. Detail-level dispatch.** Per surface, per kind, pick the right render shape. Probably a small map per entity kind:

```clojure
(def message-render
  {:full    'seon.handlers.message/render-ai-full
   :compact 'seon.handlers.message/render-ai-compact
   :hidden  nil})
```

Resolution: section's chosen detail → kind's map → symbol → call. ~50 LOC plus updates to the 4 handler files.

**C5. Agent override demonstration.** From the agent's home ns:

```clojure
(seon.render/register-section!
  {:seon.render.section/name  :related-ns-fns
   :seon.render.section/agent [:seon.agent/id (seon.db/current-agent-id)]
   :seon.render.section/fn    'seon.agent.DEy-2605251730/my-related-ns-fns
   :seon.render.section/order 20
   :seon.render.section/detail :full})
```

This is the same verb the substrate calls. Composite identity upserts: re-registering with new fn replaces. ~0 LOC (already supported by B/C1 if implemented right). Acceptance: an agent evals one form and its next render uses the new section.

### Recommended order

A1 → A2 → A3 → A4 → B1 → B2 → C1 → C3 → C2 → C4 → C5 → B3 → B4.

(B3 deferred behind Phase C because it's the most invasive and the most likely to break things, and Phase C delivers the user-facing wins.)

---

## §7 — Proposed unified render API (sketch)

```clojure
;; --- Substrate registers defaults in seon.agent (or seon.render.default) ---

(seon.render/register-section!
  {:seon.render.section/name  :specs
   :seon.render.section/fn    'seon.render.sections/specs
   :seon.render.section/order 10
   :seon.render.section/detail :compact})

(seon.render/register-section!
  {:seon.render.section/name  :related-ns-fns
   :seon.render.section/fn    'seon.render.sections/related-ns-fns
   :seon.render.section/order 20
   :seon.render.section/detail :compact
   :seon.render.section/budget 4000})        ; char budget; truncate-oldest if over

(seon.render/register-section!
  {:seon.render.section/name  :current-ns-fns
   :seon.render.section/fn    'seon.render.sections/current-ns-fns
   :seon.render.section/order 30
   :seon.render.section/detail :full})

(seon.render/register-section!
  {:seon.render.section/name  :eval-history
   :seon.render.section/fn    'seon.render.sections/eval-history
   :seon.render.section/order 90
   :seon.render.section/detail :full
   :seon.render.section/budget 8000})

;; --- Section fn shape ---
;; (fn [{:seon.db/db    db
;;       :seon.agent/id id
;;       :seon.render.section/detail detail
;;       :seon.render.section/budget budget}]
;;   {:seon.render/entries [{:seon.render/entity <map>
;;                           :seon.render/detail :full|:compact}]
;;    :seon.render/section-text "..."  ; pre-rendered text, optional
;;    :seon.render/cache-key <opaque>}) ; for prefix-cache awareness

;; --- Agent override (from seon.agent.DEy-2605251730) ---

(seon.render/register-section!
  {:seon.render.section/name  :current-ns-fns
   :seon.render.section/agent [:seon.agent/id (seon.db/current-agent-id)]
   :seon.render.section/fn    'seon.agent.DEy-2605251730/my-current-ns-fns
   :seon.render.section/order 30
   :seon.render.section/detail :full})

;; --- assemble-ai-context walks sections ---
;; 1. Query all :seon.render/section entities; agent's own wins per name via composite identity.
;; 2. Sort by :order asc.
;; 3. Call each section's fn with the agent-scoped input map.
;; 4. Render each entry per its detail level (resolved through kind-config).
;; 5. Concatenate with section dividers.
;; 6. Sections with :budget enforce char limits before returning, oldest-first truncation.
```

---

## §8 — What should be DELETED

Honest list of code that becomes obsolete after the cleanup lands:

- `src/seon/web/broadcast.cljs` (194 LOC) — replaced entirely by `seon.web.inspector`.
- `src/seon/web/sse.cljs` (59 LOC) — only consumer is broadcast.
- `src/seon/inspect.cljs` (73 LOC) — folds into `seon.render` + `seon.handler`.
- The `:seon.sticky/*` schemas in `seon.render` — replaced by `:seon.render.section/order`. (~5 LOC)
- `seon.render/ai-render` and `seon.render/html-render` if their callers can use the same internal resolve-and-call as `assemble-ai-context`. (~25 LOC)
- The `seon.handlers.retro-stamp` namespace once detect-and-tee writes the entities at the source. (~85 LOC) — retroactive stamping is a workaround for missing forward stamping.
- `seon.web.broadcast/!last-rendered` atom + diffing — once we trust the section render to be cheap, no per-agent diff cache needed.
- The legacy `default/view` (the agent-tile dashboard) if the inspector becomes the only UI — but probably keep it for the `/agents` index page enhancement later.

Net: cleanup should *delete more than it adds* in Phase A+B. Phase C adds new section infrastructure but pays for it by retiring three parallel rendering paths.

---

## Appendix: Files audited

- `src/seon/web/inspector.cljs`, `src/seon/web/serve.cljs`, `src/seon/web/broadcast.cljs`, `src/seon/web/sse.cljs`
- `src/seon/agent.cljs` (chat, run-agentic-loop!, install-user-trigger!)
- `src/seon/render.cljs`, `src/seon/render/default.cljs`
- `src/seon/inspect.cljs`, `src/seon/handler.cljs`
- `src/seon/handlers/{eval,message,fn,schema,ns,wake,retro_stamp}.cljs`
- `src/seon/client.cljs` (boot chain, 539-639)
- `docs/prds/agent-runtime/unified-loop-v1.md` (D1/D2/D3 decisions)

Live-pod probes via MCP CLJS REPL session `default` against agent `DEy-2605251730`.
