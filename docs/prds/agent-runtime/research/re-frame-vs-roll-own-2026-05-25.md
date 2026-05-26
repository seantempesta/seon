---
type: research
status: draft
tags: [research, agent, runtime]
---

> **SUPERSEDED for design decisions** by `architecture/ctx-render-strategies-prd.md` (2026-05-26 revision). Retained for history; do not use as the current spec.

# re-frame vs. roll-own — dispatch for the agent runtime

## TL;DR

**Roll our own.** A re-frame-style `(register-handler! kw shape)` API gives us
the abstraction Sean is reaching for — but re-frame's own implementation is
the wrong vehicle for Seon: its load-bearing assumption is **`app-db` as a
process-local atom**, and *its* central act is event-queue dispatch from
user-driven UI events, not tx-report fan-out from a shared bitemporal DB
holding N concurrent agents. Adopting re-frame would mean rebuilding the
parts that matter (DB-resident handlers, multi-agent scoping, restart-safe
identity, tx-meta provenance) on top of an atom + event-queue model we don't
need and would have to fight. The minimum-powerful version is small: **one
verb (`register-handler!`), one entity (`:seon.handler`), one bus
(`d/listen!`)**. That fits in ~120 lines of CLJS.

## What re-frame actually gives you

Re-frame's six concepts:

1. **`app-db`** — single Reagent atom holding all state.
2. **Events** — vectors `[:event-id arg1 arg2]` dispatched onto a queue.
3. **Event handlers** — `(reg-event-fx :event-id (fn [coeffects event] {:db ... :fx [...]}))`.
4. **Effects** — declarative descriptors `{:http {...}}` consumed by registered effect handlers.
5. **Co-effects** — declarative inputs (current time, localStorage) injected into the handler.
6. **Subscriptions** — `(reg-sub :id (fn [db query] ...))` cached Reagent reactions.
7. **Interceptors** — middleware chain wrapping every handler.

The genuinely good ideas, ranked by relevance to Seon:

- **Effects-as-data with a separate interpreter.** ✅ Already adopted in `loop-design.md` §5.
- **Handler is a pure(-ish) fn returning `{:db ... :fx [...]}`.** ✅ Adopted (we return `{:tx :effects}`).
- **Registration as the single API surface.** ✅ This is what Sean is pointing at — `register-handler!` should parallel `schema/register!`.
- **Subscriptions = cached derivations.** ✅ Already adopted under a different name — sections + the renderer dispatch. We do not need re-frame's subscription graph; we have Datalog.
- **Co-effects.** ✗ The DB itself is the co-effect — every handler receives `:seon.db/db` (the post-tx db value) and queries freely. No injection needed.
- **Interceptors.** ✗ Speculative ergonomics. No use case in our five scenarios.
- **Event queue.** ✗ Datahike's tx-report queue IS our event queue. We don't add a second one.

## Why re-frame's *implementation* doesn't fit

### 1. `app-db` is a Reagent atom; ours is Datahike

Re-frame's whole machine is structured around dispatching events that read/write a single atom. Our state lives in a bitemporal datom store that is:

- shared across N agents in the same process,
- queryable historically (`d/as-of`, `d/history`),
- persisted to LMDB,
- already broadcasting changes via `d/listen!`.

Wrapping Datahike as if it were `app-db` would require:

- A shim that re-shapes `d/listen!` callbacks into re-frame events.
- A custom effect handler for "transact this" (re-frame's `:db` effect doesn't speak Datahike).
- Discarding the queue (because the tx-report IS the queue) — at which point what's left of re-frame?

What's left after the cuts: `reg-event-fx` (an atom of handlers keyed by id), `reg-fx` (an atom of effect interpreters). That's ~30 lines of CLJS we'd write directly.

### 2. Re-frame events are vector-keyed; our events are committed datoms

Re-frame: `(dispatch [:user-clicked-save "foo"])`. The event id is the dispatch key.

Seon: the "event" is `{:e 42 :a :seon.message/to :v [:seon.agent/id "..."] :tx 1234 :added true}` — a datom that landed in a tx. We don't choose an event id; the **schema attr IS the event id**. `:seon.handler/match` is `{:attr :seon.message/to ...}` because the attr is the discriminator.

Re-frame doesn't have a notion of "match on a property of the event payload". We do, by necessity (handler scoping by agent, value-equality matching). This isn't impossible to bolt onto re-frame — but at that point the bolt is bigger than the host.

### 3. Restart-safety + DB-resident handlers

In re-frame, handlers register at boot via `(reg-event-fx ...)`. They are process-local fns, period. There is no notion of "the registry of handlers is a queryable, persistent thing."

Seon needs DB-resident handlers because:

- An agent transacts a handler at REPL-time. It must survive pod restart.
- Cross-agent visibility — agent A should be able to *see* what handlers agent B has installed (single Datalog query).
- The publish gate (substrate vs agent-scoped) is a property of the handler entity.

Re-frame can't help here. We'd add a `:seon.handler` entity layer anyway, with re-frame being a thin facade over it.

### 4. `refx` / `rfx` are worse fits, not better

The Gemini survey (§A.4) confirms: refx is hook-based, tied to React 18. rfx is Factor House's modern fork — still browser-targeted. Neither solves our problem (multi-agent on shared DB, tx-report bus, restart-safety).

### 5. Server-side re-frame is conceptual, not real

Gemini survey §A.2: no production-ready backend re-frame exists. The community has tried; the global single-atom model defeats them at the multi-tenant boundary. Seon has multiple agents in one process — the exact failure mode.

## What we *do* take from re-frame

| Idea | Adopted as |
|---|---|
| One verb to register | `register-handler!` (parallels `schema/register!`) |
| Handler returns `{db, effects}` | Handler returns `{:tx :effects}` |
| Effects are data + multimethod interpreter | `:effect/type` multimethod (already in `loop-design.md`) |
| Subscriptions = cached derivations | Sections + the renderer's data-shape dispatch (already shipped) |
| **Not** taken: app-db, event queue, co-effects, interceptors, vector-keyed events | — |

## The minimum-powerful API

Mirroring `schema/register!`:

```clojure
(schema/register! ::msg-content :string)                 ; existing
(handler/register! ::wake-on-message {:attr :seon.message/to}
                   #'my.ns/wake-on-message-fn)           ; new
```

Three params:

1. **Keyword** — `:seon.handler/name`. Namespaced; identity attr. Re-register replaces.
2. **Match shape** — `{:attr ...}` or `{:attr ... :value ...}`. **Dumb-match v1 is correct** — the three substrate handlers all express their predicate with attr+optional value. If we ever need conjunctions or Datalog `:where`, the match map's schema grows; the registration API doesn't.
3. **Fn (symbol or var)** — resolved through `seon.eval/lookup-value` at dispatch time.

Optional 4th arg `opts` map: `{:seon.handler/agent <ref> :seon.handler/priority N :seon.handler/on-origin #{...}}`. Substrate-shipped handlers omit `:agent`; agent-scoped handlers include it.

That's it. Everything else (effect interpreter, dispatcher, depth guard, origin-skip rule) is internal to `seon.runtime`, not user-facing API.

## Whether sections and handlers are the same thing

**No, but they share a registration verb.** The Elm/reactive-stream distinction (derivation vs effect) is load-bearing. Sections are pure queries that re-run every render — they have no match predicate, they have no effects, they cannot transact. Handlers fire on tx-report match — they return `{:tx :effects}`, they are scheduled, they cycle-guard.

But **the renderer's data-shape dispatch already chooses sections by querying the program graph for fns with appropriate `:malli/schema` metadata** (see `src/seon/render.cljs:30-32` and `seon.eval`'s pull queries). A section is a registered fn whose output schema is `:seon.render/ai-response`. A handler is a registered fn whose output schema is `:seon.handler/result` (a `{:tx :effects}` map).

So the unification at the implementation layer is: **both are registered fns with shape-typed return values**, distinguished by what their output schema is. The user-facing verbs stay distinct (`register-handler!` vs writing a section fn with `:malli/schema [:=> ... :seon.render/ai-response]`) because the time bases are different (every-render vs every-matching-tx) and conflating them was the original bug. See unified-loop-v1.md §3 for the proposal.

## Multi-agent semantics

`:seon.handler/name` is the identity attr. `:seon.handler/agent` is optional.

- **Substrate handler:** `:seon.handler/name :seon.handler/wake-on-message`, no `:agent`. One entity for the whole pod. All agents see it.
- **Agent-scoped handler:** `:seon.handler/name :my.handlers/rerun-failed-test` + `:seon.handler/agent [:seon.agent/id "A"]`. One entity per (name, agent). Identity becomes the composite — see open question §6.

The dispatcher walks all matched handlers; for each, checks `(or (nil? agent) (= agent dispatching-agent-id))`. Substrate handlers fire for every agent; agent-scoped handlers fire for one.

**Two agents registering the same name:** the simplest answer is that
`:seon.handler/name` alone is the identity attr ⇒ second registration
replaces the first. If we want per-agent copies, identity must be the
composite `[:seon.handler/name :seon.handler/agent]` — Datahike supports
tuple identity (`:db.unique :db.unique/identity` on a `:db.type/tuple`
attr). We propose composite identity for v1; this gives substrate handlers
(with `:agent nil`) one row, and per-agent overrides one row each, with no
ambiguity. (Open question — see §6 of the unified PRD.)

## Decision

**Roll-own.** Stay close to the data we already have. The minimum-powerful API
is `register-handler!`, parallel to `schema/register!`, backed by a
`:seon.handler` entity. We borrow re-frame's *registration shape*, its
*effects-as-data* discipline, and its *pure-handler return contract*. We
reject its event queue, its co-effect injection, its interceptors, and its
`app-db` model.

## Cross-references

- `docs/prds/agent-runtime/loop-design.md` — current PRD, superseded by `unified-loop-v1.md`
- `docs/prds/agent-runtime/unified-loop-v1.md` — the unified system proposal
- `docs/prds/agent-runtime/research/agent-loop-pattern-survey-2026-05-25.md` — prior survey
- `docs/prds/agent-runtime/research/gemini-clojure-pattern-survey-2026-05-25.md` — confirms no production backend re-frame
- `src/seon/schema.cljc` — the `register!` pattern we mirror
- `src/seon/render.cljs` — the existing renderer dispatch we leverage
- re-frame docs — https://github.com/day8/re-frame (Sections 1-7 of the docs/)
