---
type: research
status: active
created: 2026-10-13
tags: [research, ui, session, view, render-cache, layout]
---

# Sessions, views and cached render layout: what exists, what it would take

Research only, 2026-10-13. Marking: [V] = verified by a symbol, file or schema read in this session; [I] = inference. Symbols are fully qualified; file references are given only in this dated note. Nothing was run on default and no SCI was evaluated as root.

## Bottom line

1. There is no user entity, no human principal, no chat session entity and no view/layout entity anywhere in `resources/seon/schemas` or `src/seon` [V: rg for `seon.(session|view|user|owner|chat|conversation|page)/` returns no schema key; the only "human" hits are prose in `seon.cluster.message` and `seon.render.web`]. A "session" word exists only as the agent's REPL relationship (vocabulary row "session") and as page-local HTML classes `seon-session-*` [V `docs/seon/architecture/vocabulary.md`, `seon.render.transcript/session-header`].
2. The one live "tab" concept is process-local: `seon.render.web/register-tab!` and `deregister-tab!` count open tabs per page registration key in an atom `:seon.render.web/registration` [V `src/seon/render/web.clj`, `register-tab!`, `watched-registration-keys`]. It is not stored and nothing else reads it.
3. Render caching is NOT a `seon.cache` kind. It is a per-branch evidence cache, an atom under `:seon.render/cache` in the SCI projection state, holding `::calls`, `::invocations` and `::packages` [V `seon.render/shared-cache`; `seon.render.web/render-pass` `(select-keys @cache [::calls ::invocations ::packages])`]. The only `seon.cache` lookup on the render path is `::call-reach` [V `seon.render/call-reach` at `src/seon/render.clj` line 874]. This contradicts the CLAUDE.md rule "no atom holds derived state" unless the owner accepts that atom in plain words (CLAUDE.md "Prefer values to places"); flagged in section 2.
4. Per-render caching is real and keyed by content plus read evidence: `seon.render/render-call` reuses a retained call when `same-call-cache-evidence?` holds and `seon.db/read-evidence-current?` is true for its captured reads [V `src/seon/render.clj` `render-call`, `reusable-invocation`, `retain-invocation!`]. A page is NOT cached as a whole; the page is re-walked and each unit's call is reused or re-rendered [V `seon.render.web/page-result`, `seon.render.walk/neighborhood`].
5. The agent page today has no layout model: `page-result` assigns each unit its index as a rank and emits `style="order:<rank>"` [V `seon.render.web/page-result`, `surface-html`; corroborated by `docs/research/agent-platform/2026-10-13/ui-renders-actions.md` section 3]. Ordering is a sort of walked units, not a stored layout.
6. The smallest design that fits the existing owners: one `:seon.view` entity per agent-visible page (owner agent, ordered slot refs, optional pin), a `:seon.render.web` registration keyed by view id instead of only agent id, and each slot rendered by the existing `render-call` with a call id `[view-id slot-name]`. Layout changes re-order retained fragments and re-render nothing. Section 3 gives the detail. No session entity is needed until a second human exists.

## 1. Existing session, user, owner and view concepts

| Concept | What exists | Where | Stored? | Who reads |
|---|---|---|---|---|
| User / human | No entity. A human message is one with no `:seon.message/from` [V `seon.message` schema; `seon.cluster.message` prose at ~line 294: "a message with no sender came from outside the agent population — a human"]. | `resources/seon/schemas/seon.message.edn`; `src/seon/cluster/message.clj` | Absence of `from` | `seon.cluster.message` completion delivery |
| Namespace owner agent | A real agent whose `:seon.agent/id` is the namespace name, created by an explicit POST. Found via `:seon.agent/namespace` on the agent and `:seon.ns/name` on the namespace; `:seon.ns/agents` is target only [V `seon.render.web/ensure-namespace-owner!`, `create-owner-form`; vocabulary row "[TARGET] namespace agent"]. | `src/seon/render/web.clj`; `src/seon/render.clj` `namespace-owner` | Yes (agent entity) | namespace page, render walk |
| Agent (the "page subject") | `:seon.agent/id` is the URL id of `/agent/{id}`; the agent entity holds record, messages, evaluations. | route `["/agent/{id}" ...]` in `src/seon/render/route.clj` | Yes | `seon.render.web/page-response`, `seon.render.transcript` |
| Viewer versus viewed | The service carries `:seon.agent/id` beside the path's agent id and `::session?` / `::agent-header?` flags; no viewer identity is stored [V `seon.render.web/page-response`, `shell`, `feed` `:keys [:seon.agent/id]`]. | `src/seon/render/web.clj` | No | `shell`, `feed` |
| Viewer namespace (debug only) | `?viewer=` query param, debug only [V `seon.render.web/feed`]. | `src/seon/render/web.clj` | No | debug page |
| Browser tab / SSE connection | `socket`, `feed`, `register-tab!`; counted per page key. Gone on disconnect. | `src/seon/render/web.clj` | Process-local atom | render proc |
| MCP session | None. `src/seon/mcp.clj` has no session term; `seon.dev.mcp.edn` has none. | — | — | — |
| Web session / auth | None. The server binds loopback hosts and checks same origin only [V `seon.render.web` `loopback-hosts`, `same-origin?`]. | `src/seon/render/web.clj` | — | `inbound` |
| Session-level print budget | `seon.print/*budget*` per REPL session; unrelated to a view [V `src/seon/print.cljc` line 281]. | `src/seon/print.cljc` | No | value printer |

The inbound path records a tx-meta user from the route's agent id, not a human: `inbound-tx-meta` sets `:seon.db/user [:seon.agent/id user-id]` when an agent with that id exists [V `seon.render.web/inbound-tx-meta`]. The message bar form posts only `content` [V `seon.render.web/message-bar-html`, input `name "content"`]. So every web message is stamped with the addressed agent as its `:seon.db/user`. [I] That is a mislabel: the stamp names the recipient, not a user. It should be fixed or removed by the owner of `inbound`; no schema consumes it in this pass (not checked exhaustively).

## 2. Render caching: what is cached, keyed by what

**Owner.** `seon.render/render-call` [V `src/seon/render.clj`, around line 1481] is the call owner. Its helpers are `call-cache-evidence` (line 888), `reusable-invocation`, `retain-invocation!` (lines 962-978), `same-call-cache-evidence?` and `seon.db/read-evidence-current?` (`src/seon/db.clj` line 1358).

**What is cached, two levels:**

- **Call** (`::calls` entry, keyed by `:seon.render.call/id`): `:seon.render.call/output`, `:seon.render.call/read-evidence`, `:seon.render.call/static-evidence`, `:seon.render.call/basis-transaction`, `:seon.render.call/invocation-key`. Reused when the cache evidence (producer symbol, projection, program snapshot, selection input, supplied input) matches and the captured reads are still current [V render-call's `reusable?` and `fast-reusable?` branches].
- **Invocation** (`::invocations`, keyed by `invocation-cache-key`, a bucket of entries): the same, shared across calls with the same producer and input. Chosen over the call when `reusable-invocation` finds a current entry [V].

**Read evidence** is captured per read, not per value: `db/read-evidence` collects dependency plans and revisions, with index patterns collapsed to entity ids past a bound [V `seon.db/append-read-evidence!`, line 575-615]. Currency is `every?` over the captured reads of "eligible and revision equal", with a replay fallback when revisions differ [V `seon.db/read-evidence-current?`, comment at 1358-1380]. This is the mechanism the CLAUDE.md sentence "the render is cached by what it read" refers to. Lane A2 is planned to make it "one mechanism" [V `docs/prds/agent-platform/plan/lane-a2-datahike-one-answer.md` row c1]; that work is not yet in the tree.

**Page-level.** `seon.render.web/page-result` and `render-pass` re-walk the agent's units each pass and reuse calls; a per-page package is retained per registration key in `::packages`, and each tab keeps only its delivered revision [V `seon.render.web` ns docstring, `render-pass`, `next-package`, `frame-bytes`]. A package carries a delta and a keyframe of retained fragment bytes [V `next-package`].

**Scope.** Per branch: `shared-cache` allocates its own atom when the branch scope differs, and forks on the same branch share one [V `seon.render/shared-cache` docstring]. Eviction: none found for the render atom (no byte budget); the process cache dial `:seon.config.cache/max-bytes` (512 MB) covers only `seon.cache` kinds [V `resources/seon/schemas/seon.config.cache.edn`]. [I] The render atom is therefore unbounded per branch; the fork-per-branch lifetime bounds it in practice. Not measured.

**Numbers.** No measured render-page cost was found in `docs/`. The only page-timing number is cold start: root namespace page served after READY in ~19.7-20.9 s, cold, dominated by namespace load and row install, not render [V `docs/seon/reference/third-party-integration.md` lines 55-66]. The one render-cost measurement is value printing: 2.1 ms for a 2x10^5 vector window, 385 ms to print the whole thing today [V `docs/prds/agent-platform/plan/lane-b1-one-publication-path.md` line 393]. `docs/seon/architecture/ui.md` section 2 says B2 "measures tabs, history size, returned/visited work, encoding and pending bytes" before choosing shared or per-tab derivation; that measurement is not recorded in the tree.

## 3. Web page assembly today

- **Routes** [V `src/seon/render/route.clj`]: `/ns/{namespace}`, `/ns/{namespace}/debug`, `/agent/{id}` (GET), `/agent/{id}/debug`, `/agent/{id}/message` (POST, `inbound`), `/agent/{id}/context` (POST, `create-owner`), `/health`, `/feed/{id}` (SSE).
- **Shell** [V `seon.render.web/shell`, `page-response`]: the agent header (`transcript/render-agent-header` with `session-controls`), then `seon-rank-layout` containing the walked unit HTML in rank order, then the stream strip, plus `feed-url`. The message bar is added inside `shell` only when the page is not `::session?` / `::agent-header?` [V `shell` around lines 382-391].
- **Values selected** [V `seon.render.walk/neighborhood` called from `page-result`; `seon.render.transcript`]: the agent's neighborhood walk under the render profile, each unit through `render-call`, plus the transcript (last messages, evaluations in agent order, `recent-message-entities`), and the debug-only graph.
- **Update path** [V]: Datastar over SSE. `feed` paints current facts first on a virtual thread, then consumes packages from the render proc's mult; `wake/route!` is the single listener and posts one payload-free render wake per commit; the render proc (`render-step`, pinned `:io`) derives every watched page once per coalesce floor (`:seon.config.render/coalesce-ms`). Morph per block (`data-` attributes via `frame-bytes`, `datastar.elements/->patch-elements-seq`).
- **Assets** [V `resources/public`]: `css/blocks.css`, `css/input.css`, `css/output.css` (Tailwind-style output), `js/datastar.js`, `js/cytoscape.min.js` (debug graph only), `js/seon-graph.js`. Datastar is a Clojure SDK dependency (`reference-code/datastar-clojure`, `deps.edn` lines 125-133). No React, no htmx.
- **Cost per page**: not measured; see section 2 Numbers.

## 4. Message model

`:seon.message/*` attributes [V `resources/seon/schemas/seon.message.edn`, `seon.message/message` map]:

- `:seon.message/id` (identity, string), `:seon.message/to` (required ref to agent, listened, opens a turn; `:seon.render/ai`, `:seon.render/html`, `:seon.render/form` declared), `:seon.message/from` (optional ref; absent = human), `:seon.message/content` (text, bounded), `:seon.message/caused-by` (optional ref to the message that caused it), `:seon.message/about` (subject token, `:my.message/about`), `:seon.message/assignment` (evaluation id for repair), `:seon.message/received-tx` (optional).
- Pair: `:seon.render/ai seon.cluster.message/render-ai`, `:seon.render/html seon.cluster.message/render-html`, form `seon.render.transcript/message-form` [V the `:message` map annotations].
- Inbox view: `:seon.message/inbox-view` and `:seon.message/unread-entry` carry `:seon.render/operations` (acknowledgement offered) [V the same file].

**Structured value on a message:** `:seon.message/content` is a string. There is no declared attribute for a structured value with its own render. [I] A message can carry a structured value only through a ref to a function, evaluation or render entity (`:seon.message/caused-by`, `:seon.message/about`). To render a value inside a message, the message's `render-html` would have to call `seon.render/render-call` explicitly ("A render that wants a nested thing rendered calls that render explicitly", CLAUDE.md "Render output"). That path exists in principle; no message does it today.

**Reply linkage:** the message bar sends no `caused-by` [V `message-bar-html`]. A reply addressed to a question needs `caused-by` or `about` in `inbound` (not in the inbound request schema today: `:seon.message/inbound-request` has agent id, content, max-string only [V `seon.message.edn`]).

## 5. Prior designs worth reusing

- `docs/seon/architecture/ui.md` [V]: one walk with explicit output; whole-view delivery (payload-free refresh, dropping-1 tap, one patch per view); routes select subject and branch; "Per-tab rendering costs tabs x full view ... B2 measures before choosing" (section 2). Keep: whole-view delivery, payload-free refresh, dropping-1 coalesce. Reuse without change.
- `docs/research/agent-platform/2026-10-13/ui-renders-actions.md` section 3 [V]: proposes a layout render that calls each viewed-agent render explicitly, a "canvas" = most recent HTML surface by max `t` over the user agent's surfaces (derived, not stored), and an optional pin. [I] The derived-recency canvas is compatible with the cache: recency is derived from the cached call's basis `t` (`:seon.render.call/basis-transaction`), so it needs no new fact.
- `docs/research/agent-platform/2026-10-13/user-tiles.md` [V]: the owner's rule "most recent render is primary; pin overrides" was designed in commit `4a309f5ff` (2026-07-02) and deleted with the pod (`f23a2aa4b`). Its slot model was the deleted `:seon.render.live-tile/content`, a stored override. Reusable idea: an unpinned view is derived; only a pin is stored.
- `docs/seon/architecture/vocabulary.md` [V]: "[TARGET] canvas" is the primary agent surface; "block" is the render unit; "tile", "live-tile", "world" are retired. A new layout word must be recorded there before code (CLAUDE.md "Words"). The owner's "tile" wording conflicts with the vocabulary; root decision (same as in user-tiles.md section 7).
- `git show f25e34594 --stat` [V] lists the pre-rewrite `src-old/my/ui.cljc`, `src-old/seon/render/canvas.cljc`, `src-old/seon/render/chat.cljc`, `src-old/seon/render/surface.cljc`, `src-old/seon/render/handlers/message.cljc` moved to `src-old`. These are the quarry for layout and chat rendering; the chat one is the closest to the owner's "a chat coming in renders like a chat". Not read in detail here.
- `docs/seon/architecture/decisions/014-session-curation.md` [V]: superseded; context grows as stored evaluations; nothing to reuse except that compaction is a retraction, not a curation.

## 6. What a view / session entity would need to hold

Minimal, given the facts above:

- **`:seon.view/id`** (identity, string derived from the owner agent id plus a slug; no session id). **`:seon.view/owner`** (required ref to `:seon.agent/id`; the page's subject agent, as `/agent/{id}` already is). **`:seon.view/pin`** (optional ref to one unit's producer entity; absence = derived order). **`:seon.view/slots`** (component, cardinality-many, ordered by `:seon.slot/position` ints).
- **`:seon.slot/name`**, **`:seon.slot/position`**, **`:seon.slot/source`** (required ref to the entity the slot renders, or a renderer symbol plus an input entity ref). A slot holds a reference, never the rendered output: output stays in the render cache.
- Not needed: a session entity, a user entity, a per-tab layout, a stored canvas. The tab is the existing registration; the human is the absence of `:seon.message/from`.
- Writer rules (CLAUDE.md "Validate final owning values"): slots are a component of the view, so a write validates the whole view; slot sources must resolve; a deleted source removes its slot in the same transaction (optional ref sweeps, a required ref refuses).

## 7. Smallest batteries-included design: layout slots over cached renders

Goal: a layout change is a reorder of retained fragments; a content change re-renders only the slot whose read evidence changed.

1. **Layout is data, content is a render.** The view's walked units become `:seon.view/slots` (ordered refs). `page-result` replaces `ranks` from the walk index with the slot position and keeps `surface-html` for each unit. The `style order` CSS already orders by rank, so no client change.
2. **Slot call identity.** Each slot renders through `seon.render/render-call` with `:seon.render.call/id [view-id slot-name]`. Its cache lives in the existing `::calls` entry, so a reorder never touches `render-call`: it changes only the position and the retained fragment bytes are reused.
3. **Registration by view.** `:seon.render.web/registration` counts tabs per key; the key becomes `[view-id]` (the agent id is the view's owner, so existing agent pages become the default view). `watched-registration-keys` and `render-pass` need no change beyond the key shape.
4. **Derived canvas.** The focal slot is derived: the slot whose call has the greatest `:seon.render.call/basis-transaction` among the owner's surfaces, unless `:seon.view/pin` names one. Nothing stored beyond the pin.
5. **Chat slot.** The message bar becomes a slot whose source is the agent's inbox view, rendered by `seon.cluster.message/render-html`. Reply linkage needs `caused-by` added to `inbound-request` (section 4), one optional key.
6. **Proofs (red first).** (a) Reordering slots re-renders zero calls: count `render-call` misses before and after on a fixed read set (seon.profile counters; supporting evidence) and assert the emitted package has the same fragment bytes in a new order (end-to-end, per CLAUDE.md "Perf proofs"). (b) A write to one slot's source re-renders that slot only: its call id changes, others reused. (c) A deleted source removes its slot in the same transaction. (d) Two tabs on one view receive one package per commit (the existing feed test shape).
7. **Cost gate.** Measure page derivation on a branch with 1, 10 and 100 slots before and after, on the same probe (CLAUDE.md "Perf proofs end to end"). No target number is invented here; the expected cost is proportional to slots whose evidence changed.

What this does not do: no generic `/call` route, no agent-authored hiccup, no action buttons executed by the render step (ui-canvas contract). Those stay separate.

## 8. Findings for root (top)

1. **No session, user, or view entity exists.** The smallest sufficient model is one `:seon.view` entity over the existing agent. [V]
2. **Render cache is an atom in the SCI projection state, not `seon.cache`.** It is unbounded per branch and unmeasured. This needs either a `seon.cache` kind (bounded, weighed) or an owner-accepted atom rationale per CLAUDE.md. [V shared-cache; I on the rule]
3. **`:seon.db/user` is stamped with the addressed agent** on every web inbound. Fix or delete. [V inbound-tx-meta; I on intent]
4. **Layout is rank by walk index.** A stored layout only needs slot positions, and it can reuse the existing packages without new render work. [V page-result]
5. **Vocabulary conflict.** The owner says "tile"; the vocabulary retires it and calls the target "canvas". Decide before code. [V vocabulary.md]
6. **No measured page-render cost exists.** Measure on a branch before any layout change is committed. [V absence in docs/; the one measured render number is value printing]
7. **Read-evidence mechanism is in transition** (lane A2 "one mechanism"). A view design should name its currency check as `seon.db/read-evidence-current?` so A2 changes it at one owner. [V lane-a2 row c1]

## Open questions for root

1. Is the render-cache atom accepted as a justified mutable cell, or should it move to `seon.cache` with a byte weight?
2. Does a second human need an identity now? Recommended: no. The human stays the absence of `:seon.message/from`.
3. Is the focal slot "canvas" (derived by recency, pin override) as the vocabulary has it?
