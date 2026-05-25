---
type: prd
status: draft
tags: [prd, agent, runtime]
---

# Unified agent runtime loop — handlers, render-on-entity, request-turn events

Supersedes the dispatcher/handler/effects/sections sections of
[loop-design.md](loop-design.md) (§2 sections half, §2 handler half, §3
schemas, §4 dispatcher, §5 effect catalogue, §6 cycle guard, §8 substrate
handlers, §9 per-agent customization). Resumability (§7) and migration
(§12) carry over with the changes noted below. Companion files:
[loop-walkthrough-2026-05-25.md](loop-walkthrough-2026-05-25.md) (literal
data flow for four scenarios) and
[loop-testing-strategy-2026-05-25.md](loop-testing-strategy-2026-05-25.md).

The collapse: **rendering is a per-entity symbol on the entity itself;
handlers transact those entities directly; there is no separate `:seon.section`,
`:seon.ctx/slot`, or `:effect/type :tx` mechanism.** One verb
(`seon.handler/register!`), one entity (`:seon.handler`), one bus
(`d/listen!` on tx-report). Effects-as-data stays for genuine
side-effects; everything else is just a transact.

## 1. The three locked decisions

### D1. Sections fold into handlers + render-on-entity

There is **no `:seon.section` primitive and no `:seon.ctx/slot`
mechanism**. Handlers emit `{:tx [...]}` containing entities that carry
their own `:seon.render/ai` and `:seon.render/html` symbols. The
renderer queries all `:seon.ctx/*`-tagged entities for an agent, resolves
each entity's symbol via `seon.eval/lookup-value`, and calls it on the
entity. The dispatcher knows nothing about rendering; the renderer
knows nothing about handlers.

This means: a warnings-section that used to be a fn-on-`:seon.ctx`-entity
is now a **set of `:seon.ctx.warning` entities** the warnings handler
transacts. When the underlying problem clears, the handler retracts the
entity (or transacts a newer state that supersedes it via identity
upsert). The render reflects current truth because the entity it pulls
from is current truth.

### D2. Caching order is part of the rendering contract

LLM providers cache prefixes. The agent's AI context is assembled by
**`seon.render/assemble-ai-context`**: query all `:seon.ctx/*` entities
for the agent, **sort oldest-first by `:seon.ctx/updated-at`** (the
tx-time of the entity's most recent assertion), and call each entity's
`:seon.render/ai` symbol. The prefix is stable; the tail is dynamic.

Consequence: stable ctx (program graph, conventions, schema reference)
sits at the prefix because nothing re-asserts it every turn. Volatile
ctx (recent eval, recent message, latest async result) sits at the
tail because each new tx bumps its `:seon.ctx/updated-at`. When a
handler updates an existing entity (error count +1), that entity moves
to the tail naturally — no manual ordering, no cache-busting metadata.

`:seon.ctx/updated-at` is asserted by the handler that writes the
entity. Identity-attr upsert means re-asserting the entity with a
later timestamp moves it.

### D3. `seon.handler/register!` is the one verb

Fully-spec'd map argument, registered via the same `register!` pattern
schemas use:

```clojure
(schema/register! :seon.handler/name     :keyword)             ; ns'd kw
(schema/register! :seon.handler/agent    [:maybe :seon.db/ref]) ; nil ⇒ substrate
(schema/register! :seon.handler.match/attr   :keyword)
(schema/register! :seon.handler.match/value? [:or :string :keyword :int :inst
                                                  :uuid :boolean :seon.db/ref]
                  {:optional true})
(schema/register! :seon.handler/match
  [:map [:seon.handler.match/attr   :seon.handler.match/attr]
        [:seon.handler.match/value? {:optional true} :seon.handler.match/value?]])
(schema/register! :seon.handler/fn        :symbol)
(schema/register! :seon.handler/on-origin
  [:set [:enum :user :agent :system :handler :replay]])
(schema/register! :seon.handler/priority  :int)
(schema/register! :seon.handler/updated-at :inst)

(schema/register! :seon.handler/register!-request
  [:map
   [:seon.handler/name     :seon.handler/name]
   [:seon.handler/agent    {:optional true} :seon.handler/agent]
   [:seon.handler/match    :seon.handler/match]
   [:seon.handler/fn       :seon.handler/fn]
   [:seon.handler/on-origin {:optional true} :seon.handler/on-origin]
   [:seon.handler/priority {:optional true} :seon.handler/priority]])

(schema/register! :seon.handler/register!-response
  [:map [:seon.handler/registered? :boolean]])
```

Defaults: `:on-origin` omitted ⇒ `#{:user :agent :system}` (skip
`:handler` origin to break trivial loops). `:priority` omitted ⇒ `0`.

Composite identity is `[:seon.handler/name :seon.handler/agent]` via
Datahike `:db/tupleAttrs`. The first wave of impl work probes this — if
the pinned `datahike-cljs` version lacks composite-tuple identity, the
fallback is a derived scalar `:seon.handler/key` formed as
`"<name>@<agent-or-substrate>"` carrying `{:seon.db/identity true}`,
auto-asserted by `register!`. Either way, re-registering replaces.

Agent-authored handlers are identical except `:seon.handler/agent` is
set. The verb is the same; the entity is the same; only the scope
differs.

## 2. The dispatcher

Single `d/listen!` per pod, keyed `[::seon.runtime/dispatch]`. Per
committed tx:

1. Read `(:seon.db/origin tx-meta)`. For each candidate handler, if
   origin is `:handler` AND that handler's `:on-origin` doesn't include
   `:handler`, skip it. (Default cycle-guard.)
2. Walk added datoms. For each, look up handlers by
   `:seon.handler.match/attr`; if `:value?` is set, filter by `=`;
   scope by `:seon.handler/agent` (nil = substrate-wide; otherwise the
   handler's agent must match the agent the dispatcher is currently
   reasoning about — derived from the matched datom's entity or
   value).
3. Invoke matched handlers in priority desc. Each receives
   `{:seon.db/db <db-after> :seon.db/tx-report <report> :seon.agent/id <id>}`
   and returns `{:tx [...] :effects [...]}` (either may be absent).
4. Apply `:tx` as one `db/transact!` with `:tx-meta {:seon.db/origin :handler}`.
5. Apply `:effects` by handing each map to `seon.runtime/run-effect!`
   (multimethod on `:effect/type`). Effects execute on the next
   event-loop tick.

Handler-index cache keyed on `:seon.handler.match/attr`, invalidated
when any tx adds/retracts a `:seon.handler` datom. ALS-scoped
fiber-local depth counter capped at 16 as defense in depth behind the
origin-skip default.

## 3. The agentic loop is a request-turn event chain

Drop "tick" — there is no clock. Use **`:seon.turn-request`** entities.

A turn = one LLM call = one batch of evals. After an agent completes a
turn that emitted at least one form (i.e., did real work), the
`ask-and-eval!` body of `run-turn!` transacts:

```clojure
{:seon.turn-request/id    "<id>"
 :seon.turn-request/agent [:seon.agent/id <id>]
 :seon.turn-request/at    (js/Date.)}
```

A substrate handler `process-turn-request` matches
`:seon.turn-request/agent`. It reads `:seon.agent/step-count` and
`:seon.agent/max-steps`. If under cap, it emits
`{:effect/type :wake :agent <id>}` and transacts `:seon.agent/step-count
(inc current)`. If at cap, it transacts `:seon.agent/state :stopped`
plus a `:seon.ctx.cap-hit` entity (carrying `:seon.render/ai 'seon.runtime/render-cap-hit`)
that surfaces in the agent's next render — so the cap-hit becomes
visible if the user resumes.

**Stop conditions:**

- **Stop-naturally**: turn produced only narration (zero forms) — no
  `:seon.turn-request` transacted. Dispatcher flips `:seon.agent/state
  :stopped` directly inside `run-turn!`'s close path.
- **Stop-by-cap**: `process-turn-request` hits `:max-steps`.
- **Stop-on-error**: catastrophic error in turn machinery; agent flips
  to `:stopped` and a `:seon.system/error` entity is transacted (see
  D5 below).

**Proposed collapse (open):** drop the `:seon.turn-request` entity
entirely and derive "wants another turn" from "the most-recent
`:seon.message` for this agent is `:assistant` AND has at least one
linked `:seon.eval` (i.e., emitted forms)". The substrate handler then
matches `:seon.message/role` and checks the derived condition. This
removes a schema. We adopt it if the derivation is cheap (one query per
matching tx); the explicit-entity form lands first because it's
trivially auditable.

## 4. Effects, async results, and errors are all DB events

### Effects = data (`run-effect!` multimethod)

Three kinds in v1:

| `:effect/type` | Required keys | Behavior |
|---|---|---|
| `:wake` | `:agent` | If `:stopped`, flip `:running` + `(run-agent-loop! agent)`. Else no-op. |
| `:run-llm` | `:agent` `:request` `:corr` | LLM call; on settle, transacts `:seon.async-result` (see below). |
| `:spawn-agent` | `:parent` `:kind` `:initial-message` `:refs` | Mint child + initial-message + handler entities at creation. |

**No `:effect/type :tx`.** Handlers return `{:tx [...]}` directly.

### Async results = data

When an effect's interpreter settles (LLM resolves, fetch returns, tool
finishes), it transacts:

```clojure
{:seon.async-result/id     "<id>"
 :seon.async-result/agent  [:seon.agent/id <id>]
 :seon.async-result/of     [:seon.effect/corr <corr>]   ; optional
 :seon.async-result/ok?    true|false
 :seon.async-result/value  <data>      ; only when ok?
 :seon.async-result/error  <envelope>  ; only when not ok?
 :seon.async-result/at     #inst ...
 :seon.render/ai           'seon.async-result/render-ai
 :seon.render/html         'seon.async-result/render-html
 :seon.ctx/updated-at      #inst ...}
```

A substrate handler `route-async-result` matches
`:seon.async-result/agent` and emits `{:effect/type :wake :agent X}`.

### Errors are also data — never thrown into substrate code

Substrate-wide rule: **no effect interpreter ever lets an exception
escape**. The interpreter wraps the work in `try/catch`; on throw, it
transacts an `:seon.async-result` with `:ok? false` and a structured
error envelope. The agent always sees failure as data.

For errors that aren't tied to a specific effect (handler-fn missing,
dispatcher depth-overflow, schema-load misordering), the runtime
transacts `:seon.system/error` entities. A default substrate handler
`surface-system-error` matches `:seon.system/error/agent`; if the
entity carries an agent ref, it routes to that agent (renders the
error in its ctx). If not, it routes to a designated supervisor agent
(configurable; nil ⇒ the entity remains in the DB unconsumed but
inspectable).

## 5. Event sourcing — explicit correspondence

This loop IS event sourcing. The mapping:

| Event-sourcing term | Seon realization |
|---|---|
| Event log | The Datahike tx log (bitemporal, durable) |
| Aggregate state | The DB itself (`db-after` is the fold over all prior tx) |
| Projections | Render fns + Datalog queries against the current DB |
| Replay | Re-applying the tx log against a fresh conn reproduces state |
| Snapshots | Standard Datahike checkpoints (or pure replay if absent) |
| Compensation | Transacting a retraction or corrective assertion |
| Subscription | `d/listen!` callback |
| Command | A transact (with `:seon.db/origin :user|:agent|:system|:handler`) |

We don't have to build any of this. Datahike + handlers gives it for
free. Replay-as-test-strategy (Layer 5 in the testing doc) is the
direct consequence.

## 6. Substrate handlers (boot-time registration)

```clojure
(handler/register!
  {:seon.handler/name  :seon.handler/wake-on-message-to
   :seon.handler/match {:seon.handler.match/attr :seon.message/to}
   :seon.handler/fn    'seon.runtime/wake-on-message-to})

(handler/register!
  {:seon.handler/name  :seon.handler/route-async-result
   :seon.handler/match {:seon.handler.match/attr :seon.async-result/agent}
   :seon.handler/fn    'seon.runtime/route-async-result})

(handler/register!
  {:seon.handler/name  :seon.handler/process-turn-request
   :seon.handler/match {:seon.handler.match/attr :seon.turn-request/agent}
   :seon.handler/fn    'seon.runtime/process-turn-request})

(handler/register!
  {:seon.handler/name  :seon.handler/surface-system-error
   :seon.handler/match {:seon.handler.match/attr :seon.system/error}
   :seon.handler/fn    'seon.runtime/surface-system-error})
```

No per-agent copies — substrate handlers carry `:agent nil` and the
dispatcher scopes from the matched datom's value (e.g. for
`:seon.message/to`, the ref IS the agent; for
`:seon.async-result/agent`, same).

## 7. What we killed

| Killed | Reason |
|---|---|
| `:seon.section` / `:seon.ctx/slot` | Entities carry their own renderer symbol; the renderer queries them; no slot. |
| `:effect/type :tx` | Handlers return `{:tx ...}` directly. |
| `:seon.handler/id` separate from `:name` | Composite identity is `[name agent]`. |
| Per-agent copies of substrate handlers | Substrate handler with `:agent nil` scopes by reading the tx's relevant agent. |
| Match-spec DSL beyond attr+value | Three substrate handlers fit; richer match waits for a real consumer. |
| Throwing exceptions out of effect interpreters | All failure becomes `:seon.async-result/ok? false` or `:seon.system/error`. |
| "Tick" terminology | There's no clock. `:seon.turn-request` is an explicit event. |
| Separate event queue | The tx log IS the queue. |

## 8. Resumability — minimal delta

`replay-program-graph!` (v1.md §7.4) brings handler fns back live.
`install-dispatcher!` runs once; the per-attr handler index is built
from `:seon.handler` rows. Any agent left `:running` from before
restart gets a system-message + `:seon.system/error` describing the
interruption transacted; `wake-on-message-to` fires; the agent's next
render shows what happened.

Boot order:

1. `replay-program-graph!` — handler fns are alive in `js/globalThis`.
2. `install-dispatcher!` — single `d/listen!`, build handler-index.
3. Query `:seon.agent/state :running` rows; for each, transact a
   system message + a `:seon.ctx.interrupt` entity.
4. Future txs fire the dispatcher normally; wake handlers re-enter
   `run-agentic-loop!` per agent.

## 9. Migration touch-points (from current `feature/agent-runtime`)

Concrete file changes versus HEAD:

- **`src/seon/agent.cljs:336-358` `user-message-handler`** → becomes
  `seon.runtime/wake-on-message-to`, a handler fn that takes the
  dispatcher-supplied `{:seon.db/db :seon.db/tx-report :seon.agent/id}`
  map and returns `{:effects [{:effect/type :wake :agent id}]}`. The
  current `setTimeout` + `with-agent` re-entry moves into the `:wake`
  effect interpreter.
- **`src/seon/agent.cljs:360-375` `install-user-trigger!`** → becomes
  `seon.runtime/install-dispatcher!`, called ONCE at pod boot, not
  per-agent.
- **`src/seon/agent.cljs:489-575` session/turn machinery** → keep
  `with-turn!` + `ensure-session!` for now; the open question is
  whether `:seon.session` survives the collapse (likely yes — it's the
  unit of "messages-since-user", which is still useful).
- **`src/seon/agent.cljs:597-661` `run-turn!`** → ends with either
  `{:tx [{:seon.turn-request/...}]}` (if `n-forms > 0`) or transact
  `:seon.agent/state :stopped` (if zero forms).
- **`src/seon/agent.cljs:663-706` `run-agentic-loop!`** → shrinks to
  one turn. The "loop" becomes the `:wake` → `run-turn!` →
  `process-turn-request` → `:wake` chain.
- **New: `src/seon/runtime.cljs`** — holds `install-dispatcher!`, the
  multimethod `run-effect!`, the substrate handler fns, and
  `surface-system-error`.
- **New: `src/seon/handler.cljs`** — holds `register!` and the
  handler-index cache.
- **`src/seon/render.cljs`** — gains `assemble-ai-context` (queries
  `:seon.ctx/*`, sorts by `:seon.ctx/updated-at`, calls each entity's
  `:seon.render/ai`).

The eval log, the program graph, the renderer's symbol-on-entity
dispatch are unchanged shape-wise.

## 10. Acceptance criteria

1. **One `register!` call site.** `grep -rn 'seon.handler/register!'`
   shows every handler in the system.
2. **Substrate vs agent handlers differ in one key.** Diff a substrate
   row vs an agent row — only `:seon.handler/agent` is set.
3. **Re-registering replaces.** Two `register!` calls under the same
   `[name agent]` → one entity, latest `:fn`.
4. **Four walkthrough scenarios pass.** See
   `loop-walkthrough-2026-05-25.md`. Layer-3 tests in the strategy doc
   are the automation.
5. **No `:effect/type :tx`.** `grep` confirms.
6. **Origin-skip default works.** A handler that emits `{:tx ...}`
   doesn't re-fire itself.
7. **Depth-guard caps at 16.** An `:on-origin #{:handler}` handler
   that chains halts with `:seon.system/error/kind :depth-exceeded`.
8. **Resume.** Boot → handlers reinstalled → interrupted agent gets a
   system msg + wake within 200ms for 10 agents.
9. **No exception escapes an effect interpreter.** Property test:
   inject a throw inside each effect kind; assert
   `:seon.async-result/ok? false` lands.
10. **AI context is prefix-stable.** Property test: after N turns,
    bytewise compare the first K chars (cache-hash) of
    `assemble-ai-context`; expect stability when no `:seon.ctx/*`
    entity older than K was re-asserted.

## 11. Open questions (kept short)

1. **Composite tuple identity in our datahike-cljs build.** Probe at
   spike time. Fallback documented in §1 D3.
2. **Drop `:seon.turn-request`?** Proposed collapse §3. Lands second
   if the explicit form ships cleanly.
3. **Where does the supervisor-agent setting live?** A `:seon.system`
   singleton entity referenced by `surface-system-error`? Likely yes;
   defer until first user-facing crash.

## 12. Cross-references

- `loop-walkthrough-2026-05-25.md` — four scenarios as literal data.
- `loop-testing-strategy-2026-05-25.md` — five-layer test plan.
- `docs/prds/agent-runtime/loop-design.md` — parent PRD (this supersedes §§2-9).
- `docs/prds/agent-runtime/research/agent-loop-pattern-survey-2026-05-25.md`
- `docs/prds/agent-runtime/research/gemini-clojure-pattern-survey-2026-05-25.md`
- `docs/prds/agent-runtime/research/re-frame-vs-roll-own-2026-05-25.md`
- `docs/seon/concepts/reactive-context.md` — derive-not-store principle, applied here per-entity.
- `docs/seon/concepts/code-as-data-runtime.md` — handlers ARE `:seon.fn` rows.
- `src/seon/render.cljs` + `src/seon/render/default.cljs` — symbol-on-entity dispatch.
- `src/seon/schema.cljc` — the `register!` pattern this mirrors.
- `src/seon/eval.cljs` — `eval-batch!`, `record-eval!`, `lookup-value`.
- `src/seon/agent.cljs:336-706` — current loop, file/line targets for migration.
