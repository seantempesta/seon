---
type: prd
status: draft
tags: [prd, agent, database, schema]
---

# The Seon data model — context + UI + routing + errors

The complete, concrete data model for the unified **block / render / tile / slot
/ layout / route** system plus the **run / turn** agent runtime and the **general
error + warnings** surface. Every attribute is named, typed, and mapped to its
exact datahike facet, grounded in the live bridge and the vendored library
source. This extends [[agent-runtime-spec]] and makes
[[layout-context-unification-design-2026-06-27]] concrete; it does NOT invent a
parallel model — it reuses what is already registered and flags every place a
prior doc proposed a reinvented or mistyped shape.

Vocabulary is locked ([[architecture]] §Glossary): **block · render · prompt ·
page · tile · slot · layout · canvas · world · root agent · app · route ·
warnings block**. The all-agents overview is the **root agent's world** at `/`
(there is no separate "dashboard" mechanism). The error value is **one general base
shape**, not render-specific, with NO `:kind`/`:type` discriminator — specialized
only where a shape truly diverges.

## 1. TL;DR — the entity graph in one paragraph

The **agent** (`:seon.agent/id`, identity) is the root. It OWNS, by
cascade-retract component vectors, its **blocks** (`:seon.agent/ctx` →
`:seon.agent.ctx/block` children — the context units, each up to two renders) and its
**schedules** (`:seon.agent/schedules` → `:seon.agent.schedule` cron maps). It
POINTS (plain ref) at its current **run** (`:seon.agent/run`); a run points back
(`:seon.agent.run/agent`) and OWNS no turns — **turns** point UP to their run
(`:seon.agent.turn/run`), and a turn OWNS its **evals** (`:seon.agent.turn/evals`
component vector). **Messages** (`:seon.agent.message`), **todos**
(`:seon.agent.todo`), and the one **user** (`:seon.user/id`) are independent
entities joined by refs (`from` / `to` / `owner`). The **program graph**
(`:seon.ns` source, `:seon.fn`, `:seon.schema`, `:seon.test`) is the agent's code
as data; blocks, routes, and schedules reference its members by **symbol value**
(late-resolved, NOT a datahike ref). **Routes** (`:seon.route`, NEW) are datoms a
`db->routes` fn projects into reitit; a route's handler IS a layout symbol and its
`:seon.route/owner` is a ref to the owning agent. The **root agent**
(`:seon.agent/id "root"`) is a seeded system agent whose world IS the `/` overview
(system-scoped ctx + an elevated capability grant through the same gate). The
**state** is never stored —
it is derived (`seon.derive/derive-state`) from the agent's primitives. The
**error value** is ONE base shape, top-level `:seon/error`; a specialized error is
a DIFFERENT namespaced keyword only where the shape truly diverges
(`:seon.db/error` adds the serialized exception). There is NO `:kind`/`:type`
discriminator anywhere — consumers tell errors apart by WHICH attribute/namespace
carries the value. Render/transact/capability errors are transient; the eval error
is PERSISTED as strings on the `:seon.eval` row. The **warnings block** is not an
error list — it is the rendered union of every non-clean check in the
`seon.warn/checks` registry (a pure fn of the db), of which "errors of all kinds"
is only a handful of checks.

## 2. How refs work in Seon — three distinct relationship kinds

Seon expresses relationships three different ways. Conflating them is the single
biggest data-model error, so this section pins each against the bridge
(`seon.db.internal`) and the vendored datahike source.

### 2.1 datahike ref (`:seon.db/ref`) — and component refs

`:seon.db/ref` is the ONE canonical ref shape, registered once in `seon.schema`
(`schema.cljc:88-93`) as `[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]`
— the set of forms datahike resolves to an eid at transact time. The bridge
SPECIAL-CASES it: `resolve-malli-form` returns `:seon.db/ref` unchanged and
`form->datahike-value-type` maps it directly to `:db.type/ref`
(`db/internal.cljs:169-170, 219-220`), NOT by following its `[:or …]` body. Every
ref attribute REFERENCES this shape — never inline an `[:or :int :string …]`.

A **plain ref** is a single pointer (`:db.cardinality/one :db.type/ref`):
`:seon.agent/run`, `:seon.agent/parent`, `:seon.agent.run/agent`,
`:seon.agent.run/cause`, `:seon.agent.turn/run`, `:seon.fn/ns`, `:seon.schema/ns`,
`:seon.test/ns`, `:seon.agent.message/from`, `:seon.agent.todo/owner`,
`:seon.route/owner` (NEW). Use a plain ref when the entity does NOT own the
referent's lifecycle — a fn does not own its ns; a turn does not own its run.

A **component ref vector** `[:vector {:seon.db/component true} :seon.db/ref]` is an
OWNED-children list: `{:seon.db/component true}` → `:db/isComponent true`
(`db/internal.cljs:350`) and the vector wrapper → `:db.cardinality/many`
(`db/internal.cljs:267-275`). Datahike requires a component attr to also be
`:db.type/ref` (`schema.cljc db.cljc:799-805`: "{:db/isComponent true} should also
have {:db/valueType :db.type/ref}") — which `:seon.db/ref` provides. **Component =
cascade retract:** on `[:db.fn/retractEntity parent]`, datahike's
`retract-components` maps every component-attr datom to `[:db.fn/retractEntity
child-eid]` (`reference-code/datahike/src/datahike/db/transaction.cljc:730-733`),
so retracting the agent retracts every owned block / schedule; retracting a turn
retracts its evals. The owned-children attrs: `:seon.agent/ctx` (blocks, NEW name),
`:seon.agent/schedules`, `:seon.agent.turn/evals`, `:seon.render/children`.

Lookup-by-identity rides on a ref's `[:attr val]` form: `[:seon.agent/id "abc"]`
is a valid `:seon.db/ref` value (the `[:tuple :keyword …]` arm) that datahike
resolves to the agent's eid, so a write can reference an entity by its natural key
without first querying its eid.

### 2.2 identity / lookup attrs (`{:seon.db/identity true}`)

`{:seon.db/identity true}` → `:db/unique :db.unique/identity`
(`db/internal.cljs:349`; datahike accepts `:db.unique/identity` |
`:db.unique/value`, `schema.cljc:60`). An identity attr makes the entity
upsertable by that value: transacting a map carrying the identity attr MERGES into
the existing entity rather than creating a new one — this is how "redefine =
upsert" works for the program graph and how a fresh agent re-seeds idempotently.

The identity attrs in the model, by value type:

| identity attr | malli shape | datahike valueType | role |
|---|---|---|---|
| `:seon.agent/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | agent natural key |
| `:seon.agent.run/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | the **fencing token** |
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | turn key |
| `:seon.agent.message/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | message key |
| `:seon.agent.todo/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | todo key |
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | schedule key |
| `:seon.eval/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | eval key |
| `:seon.user/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | the one human |
| `:seon.fn/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | fn qualified-sym key |
| `:seon.test/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | test key |
| `:seon.ns/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | ns key |
| `:seon.schema/key` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | schema-attr key |
| `:seon.route/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | NEW — reverse-routing key |

`:seon.db/id` is itself a shared shape (`[:string {:min 14 :max 14}]`,
`schema.cljc:104`) referenced by every id-attr — bump it once, every length
constraint follows. Note `:seon.agent.ctx/name` is NOT in this table (see §3.2 block):
it is a plain `:keyword`, a per-agent logical name, not a global identity.

### 2.3 symbol-as-value — late binding to the program graph (NOT a ref)

A render fn, a route handler, and a schedule fn are stored as **symbol values**,
resolved late at use time via `seon.eval/lookup-value`. They are NOT datahike refs
— there is no entity to point at; the symbol names a var in the running program,
which the program-graph entities (`:seon.fn`, `:seon.ns`) also describe, but the
binding is by NAME at call time, not by eid at write time. This is what lets an
agent transact `:seon.render/html 'my.agent.abc/status-tile` before (or after) the
fn exists, and lets a redefine take effect with no re-transact.

Two storage encodings, both VALUES:

- **Pure `:symbol`** → `:db.type/symbol` (`db/internal.cljs:193`; datahike has a
  native symbol type, `schema.cljc:48`). Used by `:seon.agent.schedule/fn :symbol`
  and (recommended) `:seon.route/handler :symbol`.
- **Mixed `:or` (symbol OR data)** → stored as a **pr-str'd EDN string**
  (`:db.type/string`), because datahike's typed schema cannot hold a scalar union;
  `transact!*` encodes and `seon.db/decode-edn-value` decodes
  (`db/internal.cljs:249-257, 362-380`). Used by `:seon.render/ai`
  (`[:or :string :symbol]`, `render.cljs:79`) and `:seon.render/html` (references
  `:seon.render.live-tile/content` = `[:or :symbol ::hiccup]`,
  `render.cljs:86` / `live_tile.cljs:314`).

The render path resolves these through ONE engine: `ai-render` / `html-render`
(`render.cljs:157-182`) call `eval/lookup-value` on a qualified symbol, falling
through to a pretty-printer on a miss. Agent-authored symbols run SCI-bounded; core
symbols run compiled.

## 3. Per-entity schema tables

Facet column reads `valueType / cardinality / unique|component`. "reuse" = already
registered (cited); "NEW" = added by this redesign. Mixed-`:or` attrs note the EDN
string storage.

### 3.1 agent — `:seon.agent/*` (agent.cljs:75-311)

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | the root identity |
| `:seon.agent/purpose` | `:string` | string / one | reuse | optional; renders into ctx |
| `:seon.agent/parent` | `:seon.db/ref` | ref / one | reuse | optional; aspirational (no writer until spawn) |
| `:seon.agent/run` | `:seon.db/ref` | ref / one | reuse | → current run; fencing pointer + derived-state spine |
| `:seon.agent/terminated-at` | `:inst` | instant / one | reuse | presence ⇒ derived `:terminated` |
| `:seon.agent/default-turn-limit` | `:int` | long / one | reuse | optional; seeds a run's work bound |
| `:seon.agent/default-deadline-ms` | `:int` | long / one | reuse | optional; seeds a run's clock bound |
| `:seon.agent/schedules` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | reuse | owned cron maps (cascade-retract) |
| `:seon.agent/ctx` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | **RENAME** | was `:seon.agent/sections` (agent.cljs:163) — owned **blocks** |
| `:seon.render/ai` | `[:or :string :symbol]` | string (EDN) / one | reuse | optional; agent's own ai render (absent for the record by default) |
| `:seon.render/html` | `:seon.render.live-tile/content` | string (EDN) / one | reuse | optional; per-entity tile-render override |

The `:seon.agent` entity-kind `:map` (agent.cljs:298, `{:seon.db/entity true
:seon.render/html 'seon.render.default/view}`) lists `id` required, everything else
optional. **State is derived, never stored** — there is no `:seon.agent/state`
datom (the old `:seon.agent/state` enum in agent-runtime-spec.md:59 was retired;
`seon.derive/derive-state` is the one rule). RENAME note: `:seon.agent/ctx` is the
TARGET name; it churned (`:seon.agent/ctx` → `:seon.agent/sections` in the
runtime-spec, now `:seon.agent/sections` → `:seon.agent/ctx` here). Grep-verify
zero `:seon.agent/sections` before the cluster reset (silent-empty-query risk).

### 3.2 block — `:seon.agent.ctx/block` (ns move + RENAME of `:seon.ctx/section`, ctx.cljs:100-113)

The block schema moves with its namespace: `seon.ctx` → **`seon.agent.ctx`**, and
every `:seon.ctx/*` keyword → `:seon.agent.ctx/*` (CLJS track only; the paused JVM
`.clj` side stays on `:seon.ctx/*`). Folded into the SAME atomic
`section`→`block` patch + cluster reset (see [[layout-context-migration-2026-06-27]]
item 0). **Naming coherence — three distinct, coherent things:** `:seon.agent/ctx`
is the agent's block VECTOR attr (the agent OWNS a `ctx`); `seon.agent.ctx` is the
NS that defines blocks; `:seon.agent.ctx/block` is the block schema. The agent owns
a `ctx` of blocks defined in `seon.agent.ctx`.

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.ctx/name` | `:keyword` | keyword / one | reuse | per-agent logical name; prompt header + DOM `#tile-<name>` — **NOT a datahike identity** (see below) |
| `:seon.agent.ctx/priority` | `:int` | long / one | reuse | prompt order AND default scroll order |
| `:seon.render/ai` | `:seon.render/ai` (ref to the registered `[:or :string :symbol]`) | string (EDN) / one | reuse | **make `{:optional true}`** (ctx.cljs:112) — html-only blocks |
| `:seon.render/html` | `:seon.render/html` (ref to registered shape) | string (EDN) / one | reuse | optional; present ⇒ a tile |

The block map schema (rename `:seon.ctx/section` → `:seon.agent.ctx/block`, make ai
optional):

```clojure
;; ns seon.agent.ctx (the `::` keywords expand to :seon.agent.ctx/*)
(schema/register! :seon.agent.ctx/block
  [:map
   [:seon.agent.ctx/name     :seon.agent.ctx/name]
   [:seon.agent.ctx/priority :seon.agent.ctx/priority]
   [:seon.render/ai          {:optional true} :seon.render/ai]
   [:seon.render/html        {:optional true} :seon.render/html]])
```

**Decision — blocks are component children, name is NOT a datahike identity.**
Each block is its own entity, owned via `:seon.agent/ctx`
(`[:vector {:seon.db/component true} :seon.db/ref]`) so it cascade-retracts with
the agent. `:seon.agent.ctx/name` stays a plain `:keyword`: if it were
`{:seon.db/identity true}` the uniqueness would be GLOBAL, and two agents could not
both own a `:transcript` block (the second upsert would steal the first's eid).
The "upsert by name" the design docs describe is an APPLICATION-level merge in
`gather-blocks` (override-by-name within one agent's set merged over
`default-blocks`), not a datahike identity upsert. The block schema REFERENCES the
already-registered `:seon.render/ai` / `:seon.render/html` shapes — it does NOT
re-inline `[:or :symbol :string]` (see §6 reinvention finding).

### 3.3 run — `:seon.agent.run/*` (run.cljs:40-68) — KEEP

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.run/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | the fencing token |
| `:seon.agent.run/agent` | `:seon.db/ref` | ref / one | reuse | back-ref → agent |
| `:seon.agent.run/started-at` | `:inst` | instant / one | reuse | wake time |
| `:seon.agent.run/trigger` | `[:enum :message :schedule]` | keyword / one | reuse | enum of keywords → `:db.type/keyword` |
| `:seon.agent.run/cause` | `:seon.db/ref` | ref / one | reuse | → the waking message (when `:message`) |
| `:seon.agent.run/turn-limit` | `:int` | long / one | reuse | work bound (bumpable) |
| `:seon.agent.run/deadline` | `:inst` | instant / one | reuse | absolute clock bound |
| `:seon.agent.run/last-beat-at` | `:inst` | instant / one | reuse | heartbeat |
| `:seon.agent.run/paused-at` | `:inst` | instant / one | reuse | presence ⇒ derived `:paused` |
| `:seon.agent.run/remaining-ms` | `:int` | long / one | reuse | banked at pause, re-extends deadline at resume |
| `:seon.agent.run/status` | `[:enum :open :closed]` | keyword / one | reuse | |
| `:seon.agent.run/closed-reason` | `[:enum :completed :waited :turn-limit :deadline-exceeded :terminated :superseded :error :crashed]` | keyword / one | reuse | present iff `:closed` |

`:seon.agent.run/turn-count` / `:now` / `:snapshot` (run.cljs:85-147) are
derived-read scalars, not stored datoms.

### 3.4 turn — `:seon.agent.turn/*` (turn.cljs:52-79) — KEEP

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | |
| `:seon.agent.turn/at` | `:inst` | instant / one | reuse | |
| `:seon.agent.turn/status` | `[:enum :running :done :error]` | keyword / one | reuse | |
| `:seon.agent.turn/run` | `:seon.db/ref` | ref / one | reuse | turn → its run (replaced `…/wake`) |
| `:seon.agent.turn/prompt-chars` | `:int` | long / one | reuse | |
| `:seon.agent.turn/prompt-file` | `:string` | string / one | reuse | |
| `:seon.agent.turn/llm-retries` | `:int` | long / one | reuse | |
| `:seon.agent.turn/llm-usage` | `:string` | string / one | reuse | |
| `:seon.agent.turn/llm-meta` | `:string` | string / one | reuse | audit-only; never read (candidate to drop) |
| `:seon.agent.turn/evals` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | reuse | owned evals (cascade-retract) |

### 3.5 message — `:seon.agent.message/*` (message.cljs:31-66) — KEEP

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.message/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | |
| `:seon.agent.message/content` | `:string` | string / one | reuse | |
| `:seon.agent.message/from` | `:seon.db/ref` | ref / one | reuse | → user or agent |
| `:seon.agent.message/to` | `[:vector :seon.db/ref]` | ref / **many** | reuse | cardinality-many ref (NOT component — recipients aren't owned) |
| `:seon.agent.message/at` | `:inst` | instant / one | reuse | |
| `:seon.agent.message/hops` | `:int` | long / one | reuse | hop-cap guard |
| `:seon.agent.message/origin` | `[:enum :human :agent :core]` | keyword / one | reuse | |

`:seon.user/id` (`[:string {:seon.db/identity true}]`, message.cljs:46) + the
`:seon.user` entity-map are the one human; `user-ref` = `[:seon.user/id "user"]`.

### 3.6 todo — `:seon.agent.todo/*` (todo.cljs:41-49) — KEEP

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.todo/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | |
| `:seon.agent.todo/title` | `[:string {:min 1}]` | string / one | reuse | |
| `:seon.agent.todo/description` | `:string` | string / one | reuse | |
| `:seon.agent.todo/status` | `[:enum :open :done]` | keyword / one | reuse | |
| `:seon.agent.todo/created-at` | `:inst` | instant / one | reuse | |
| `:seon.agent.todo/completed-at` | `:inst` | instant / one | reuse | |
| `:seon.agent.todo/owner` | `:seon.db/ref` | ref / one | reuse | → the agent |
| `:seon.agent.todo/from` | `:seon.db/ref` | ref / one | reuse | → who asked |
| `:seon.agent.todo/message` | `:seon.db/ref` | ref / one | reuse | → the inbound message it tracks |

### 3.7 schedule — `:seon.agent.schedule/*` (schedule.cljs:34-41) — KEEP

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | reuse | |
| `:seon.agent.schedule/cron` | `:string` | string / one | reuse | 5-field cron |
| `:seon.agent.schedule/fn` | `:symbol` | **symbol** / one | reuse | qualified fn to invoke — symbol-as-value (§2.3) |
| `:seon.agent.schedule/timezone` | `:string` | string / one | reuse | IANA tz |
| `:seon.agent.schedule/concurrency-policy` | `[:enum :forbid :allow]` | keyword / one | reuse | |

### 3.8 route — `:seon.route/*` (NEW ns `seon.route`)

The keyword-ns = code-ns rule REQUIRES a real `seon.route` namespace before any
`:seon.route/*` attr can be registered. Each row is a datom a `db->routes` fn
projects into a reitit route vector `[pattern data & children]`.

| attribute | malli | datahike facet | reuse/NEW | reitit route-data key |
|---|---|---|---|---|
| `:seon.route/pattern` | `:string` | string / one | NEW | the path string `"/agent/{id}"` (the route vector's head) |
| `:seon.route/method` | `:keyword` | keyword / one | NEW | `:get`/`:post`/… → the method endpoint key (reitit `http-methods`, ring.cljc:14) |
| `:seon.route/name` | `[:keyword {:seon.db/identity true}]` | keyword / one / identity | NEW | `:name` → `match-by-name` reverse routing (core.cljc:49) |
| `:seon.route/owner` | `:seon.db/ref` | ref / one | NEW | rides as opaque route-data; meta-merges parent→child for auth |
| `:seon.route/handler` | `:symbol` | **symbol** / one | NEW | the layout symbol → `{:handler …}` via reitit `Expand` (core.cljc:29); symbol-as-value (§2.3) |
| `:seon.route/middleware` | `[:vector :keyword]` | keyword / **many** | NEW (optional) | `:middleware` keywords resolved through reitit's `::registry` (middleware.cljc:15-33) |

`seon.route` entity-map (`:map {:seon.db/entity true}` with `pattern`/`method`/
`name`/`handler` required, `owner`/`middleware` optional). Datahike tolerates the
extra `:seon.route/owner` key because reitit route-data is an OPEN map (`Expand`
on a map returns it unchanged, core.cljc:21). reitit gives build-time path + name
**conflict detection** (`path-conflicting-routes` → `:conflicts` throws
`:path-conflicts`, core.cljc:292,329) that the hand-rolled `cond` dispatch lacks.

**DECIDED (handler) — dedicated `:seon.route/handler :symbol`.** Not a reuse of
`:seon.render/html`: a route handler is always a layout symbol, never literal
hiccup, so it stores as a native `:db.type/symbol` (not the EDN-encoded mixed-`:or`)
and resolves via `lookup-value` like every other late-bound symbol (§2.3). "A route
handler IS a layout" still holds at the VALUE level (the symbol names a layout fn);
they just don't share the storage attr.

**DECIDED (agent-extendable app routes) — YES, agents customize their own world.**
Two tiers of route rows:

- **Core base routes**, seeded at boot: `/` (the **root agent's world** — see
  below; NOT a separate dashboard), `/agent/{id}` (world), `/agent/{id}/feed` (SSE),
  `/call` (the action door), `/eval`.
- **Agent app routes** under `/agent/{id}/app/{x}`, transacted by the agent itself.
  An app route's `:seon.route/handler` is one of the agent's OWN `my.agent.<id>/…`
  layout fns — the `my.*` / `my.agent.<id>` namespaces are the grounds for app-like
  systems (the same namespace-as-route + own-ns-fn model the `/call` gate already
  enforces). Creating a route is a **capability-gated write**, exactly like the
  agent's other writes: the agent may only point a handler at a symbol in its own
  home ns, and `:seon.route/owner` is itself. This is especially relevant when the
  user is driving the agent to build its own UI.

To keep the GLOBAL `:seon.route/name` identity unique across agents (required for
reverse routing via `match-by-name`), agent app-route names are **namespaced
per-agent** — e.g. `:agent.abc/app-x` — so two agents' app routes never collide on
the identity attr; reitit's build-time name-conflict detection (core.cljc:329) is
the backstop.

**The `/` route = the root agent's world (no separate "dashboard").** The
all-agents overview is NOT a distinct page mechanism — it is the **root agent's**
world, rendered by the IDENTICAL block / layout / route machinery as every other
agent's world. `root` is a seeded agent `:seon.agent/id "root"`; the `/` route's
`:seon.route/owner` is the root agent and its `:seon.route/handler` is the root
world-layout symbol (a seeded core symbol, since root is a system agent — its layout
may be core-seeded rather than agent-authored, e.g. `seon.agent.ctx`/`seon.ui` or a
`my.agent.root/…` fn). The render + route tree is rooted here: **root world (`/`) →
per-agent worlds (`/agent/{id}`) → apps (`/agent/{id}/app/{x}`)**. Turtles: root is
just an agent with system-scoped ctx; "the local system cluster does system work"
([[architecture]]).

**Root has ELEVATED capability — a broader GRANT, not a gate bypass.** Two ways
root differs, both through the SAME mechanisms (no new schema):

- **System SCOPE:** root's `:seon.agent/ctx` blocks query ACROSS all agents (the
  all-agents overview / the agent-preview tiles); normal agents' blocks are
  self-scoped. Same render/block mechanism, wider query.
- **Elevated GRANT:** root OWNS a larger granted set of `:seon.fn`s — system-level
  fns a normal agent isn't granted (agent lifecycle spawn/terminate, cross-agent
  reads/coordination, system routes). It still routes through the SAME `/call`
  capability gate (namespace-as-route → owning agent → granted `:seon.fn`); root
  simply has a system capability scope. The security model stays uniform — root is
  an agent with more granted fns, not a hole in the gate (root = superuser, by
  grant).

### 3.9 error VALUE — base `:seon/error`, specialized only where the shape diverges

Errors are GENERAL, not render-specific, and there is **NO `:kind`/`:type`
discriminator** — anywhere, for any entity (the same hard rule as the render twins:
presence of `:seon.render/ai` vs `:seon.render/html` selects the surface, never a
`:kind` field). The discriminator is ALWAYS the namespaced keyword that carries the
value. So the model is ONE base shape registered at the ROOT namespace, `:seon/error`
(precedent: `:seon/embedding`, embed.clj:181 — a root-`seon` schema key), and a
specialized error keyword is minted ONLY where the shape genuinely diverges, each
referencing the base's shared FIELD shapes (the shared-shape rule; malli `:merge` is
not wired in the registry, so sharing is at field-shape granularity — verified:
schema.cljc registers only `m/default-schemas` + the mutable atom, no `malli.util`).

**The SHARED CORE — what every error guarantees.** A generic surfacer or handler
relies ONLY on the shared core; variant attrs are bonus. The owner's framing:
"functions accepting an error handle the SHARED parts." So the base is the minimal
contract:

- `:seon.error/message` (REQUIRED) — the HUMANIZED headline string a generic
  surfacer always prints. For the schema/coercion kind it is produced by
  `malli.error/humanize` (and reitit-malli's humanized coercion messages where
  reitit coercion is in play) — never hand-rolled; for render/eval/transact/
  capability/LLM errors it is a plain readable one-liner. It is a VIEW over the
  data, not a replacement for it (see below).
- `:seon.error/where` (optional but conventionally present) — the SITE as a keyword
  (block name / route name / fn sym). The generic "where did this happen" hook.
- `:seon.error/data` (optional, but ALWAYS present when there is structured detail)
  — the STRUCTURED payload, retained verbatim, never humanized-and-discarded. For
  the schema kind it IS the malli explain map (`:schema`/`:value`/`:in`/`:path`/
  `:type`) — the precise data an AI agent reasons over to locate and fix the
  defect. Not a reinvention: it is malli's own explain output.
- `:seon.error/symbol` / `:seon.error/hint` (optional) — the offending fn and the
  actionable fix. A handler may use them if present, never requires them.

A handler written against the base — `(defn surface [{:seon.error/keys [message
where hint]}] …)` — works on ANY error, base or specialized, because every
specialization references these same field shapes. That is the whole point of one
base + variant attrs over N unrelated error maps.

```clojure
;; Shared FIELD shapes (registered once; both the base and any specialization
;; reference these — never re-inline [:string] across error maps).
(schema/register! :seon.error/message :string)   ; promote from the inline use in :seon.db/error
(schema/register! :seon.error/where   :keyword)   ; NEW — the site: a block/route/fn name
(schema/register! :seon.error/symbol  :symbol)    ; NEW — the offending fn
(schema/register! :seon.error/hint    :string)    ; NEW — the actionable fix
(schema/register! :seon.error/data    :map)       ; NEW — opaque per-site payload (e.g. the malli explain)

;; THE base error — every error IS one of these unless it genuinely diverges.
;; A consumer that destructures only the shared core handles every variant.
(schema/register! :seon/error
  [:map
   [:seon.error/message :seon.error/message]               ; the one required field
   [:seon.error/where   {:optional true} :seon.error/where]
   [:seon.error/symbol  {:optional true} :seon.error/symbol]
   [:seon.error/hint    {:optional true} :seon.error/hint]
   [:seon.error/data    {:optional true} :seon.error/data]])
```

**Grounded in malli's OWN error model.** Malli is the precedent for "a structured
error value, humanized for display, programmatic underneath" — read from the
vendored source:

- `malli.core/explain` returns `{:schema <s>, :value <v>, :errors [<error> …]}`
  (`core.cljc:2655-2658`); each `<error>` is
  `{:path <schema-path>, :in <value-path>, :schema <sub>, :value <sub>, :type
  <error-type-kw>}` (`malli.impl.util/-error`, `util.cljc:19-21`). Note malli ALSO
  has no `:kind` discriminator — the error `:type` keyword (e.g. `::m/invalid-type`,
  `::m/missing-key`) is a registry KEY into messages, not a branch in the value.
- `malli.error/humanize` (`error.cljc:374-390`) turns an explanation into the
  human string(s) via the `default-errors` registry (`error.cljc:44`), each entry
  `{:error/message {:en "…"}}` or `{:error/fn {:en (fn [error opts] …)}}`, with
  per-schema `:error/message` / `:error/fn` property overrides.

So `:seon.error/message` mirrors malli's HUMANIZED output; `:seon.error/data`
mirrors the structured `explain` map. **Seon already captures exactly this** in
`seon.error.instrument` (`error/instrument.cljc:62-81`): `:seon.error.malli/path`
= malli's `:in` (value path), `:seon.error.malli/explain-path` = malli's `:path`
(schema path), `:seon.error.malli/leaf-type` = malli's error `:type`,
`:seon.error.malli/humanized` = `me/humanize` output, plus `expected`/`got-edn`/
`got-type`/`fn-sym`/`schema`. A **schema/instrumentation rejection** therefore needs
NO new shape: it is a `:seon/error` whose `:seon.error/data` IS the existing
`:seon.error.malli/*` projection (the malli explain). Reuse, don't reinvent.

**Humanize is a derived VIEW, never a replacement — the value carries BOTH.** We do
NOT choose humanized-string-vs-structured-data; the base shape keeps both at once.
`:seon.error/message` is the humanized headline; `:seon.error/data` is the structured
payload, ALWAYS retained (never humanized-and-thrown-away). This is malli's own
design: `malli.error/humanize` is a pure TRANSFORM over the explain map, and the
explain map is the source of truth —

- `malli.error/humanize` (`error.cljc:374-390`) takes an EXPLANATION (`{:schema
  :value :errors}` from `malli.core/explain`) and `reduce`s the `:errors` into a
  structure that MIRRORS the value, pushing each error's message in at its `:in`
  path (default `:wrap :message`, `:resolve -resolve-direct-error`). Output is a
  path-keyed MAP for nested errors, or a vector of strings for a top-level scalar
  miss (e.g. `["should be a string"]`) — exactly as our
  `:seon.error.malli/humanized` captures (`error/instrument.cljc:72-75`). The
  underlying explain map (`:schema`/`:value`/`:in`/`:path`/`:type`) is never lost.

Rationale, captured for the consumers: the **AI agent acts on the DATA** — precise
`:in` path + offending `:value` + the `:schema` it violated — with the humanized
message as a fast headline to orient; the **human UI shows the headline + a
value-explorer drill-down** into the data. Same shape as our render twins: the data
is the model, the message/render is a view. So an error value's two renders split
the emphasis — its **ai render** prints the error as data-as-Clojure (the explain
map, eval-able, for the prompt), its **html render** leads with the humanized
headline and offers a drill-down into `:seon.error/data` (the human tile). One
value, two views, data always primary.

**Per-error DECISION — diverge or fold?** Apply the rule "mint a specialized
keyword only if the shape truly differs" to each error shape that exists today:

| error keyword | diverges from `:seon/error`? | decision |
|---|---|---|
| **render error** | NO — message + where (block/route name) + symbol (offending fn) + hint is exactly the base | **FOLD.** Do NOT register a distinct `:seon.render/error` schema (it would be identical). The render guard PRODUCES a `:seon/error`; the html-response carries it under the `:seon.render/error` KEY (the structural discriminator — see below). Replaces the current bare alias `(register! :seon.render/error :seon.db/error)` (render.cljs:116). |
| **schema / instrumentation rejection** | NO new shape — base + the malli explain under `:seon.error/data` | **FOLD.** It is a `:seon/error` whose `:seon.error/data` is the existing `:seon.error.malli/*` projection (= malli's explain). No new map. |
| **capability denial** | NO — a message + where (the denied fn) + hint ("read your grants") is the base | **FOLD.** A plain `:seon/error`; evidence is also the eval-log string the `check-fs-denied` warn-check reads. |
| **transact error** `:seon.db/error` (db.cljs:143-152) | YES — adds the serialized JS exception (`:seon.error/ex-data?`, `:stack?`, `:cause?`, `:raw?`, `:truncated?`), built by `seon.error/->map` from a thrown error | **KEEP as a specialization** referencing the base's shared fields, adding the exception-capture fields. It is the only error that carries a serialized exception, so it genuinely diverges. |
| **LLM / provider error** `:seon.ai/error` (ai.cljs:96-103) | YES — carries provider/transport fields (`:seon.ai/status` HTTP, `:seon.ai/transport?` retryable, `:seon.ai/timeout?`, `:seon.ai/raw-body`) | **KEEP as a specialization** (see §3.9b) — these fields drive the retry decision, so it genuinely diverges. Reconcile its existing `:seon.ai/msg` to reference `:seon.error/message`. |
| **eval error** | NO new in-memory shape — it is the base error PROJECTED to strings for durable storage | **No new map.** Persisted on the `:seon.eval` row (below). |

```clojure
;; SPECIALIZATION — the serialized-exception / transact-failure envelope. Diverges
;; (carries a captured JS exception); references the shared FIELD shapes for the
;; common part, adds the exception capture. (If malli.util ever gets wired,
;; [:merge :seon/error [:map …extra]] is the tighter form.)
(schema/register! :seon.db/error
  [:map
   [:seon.error/message   :seon.error/message]            ; shared field shape
   [:seon.error/data      {:optional true} :seon.error/data]
   [:seon.error/where     {:optional true} :seon.error/where]
   [:seon.error/ex-data   {:optional true} :map]           ; +exception capture
   [:seon.error/stack     {:optional true} :string]
   [:seon.error/cause     {:optional true} :map]
   [:seon.error/raw       {:optional true} :any]
   [:seon.error/truncated {:optional true} :boolean]])
```

#### 3.9b The LLM / provider error — `:seon.ai/error` (DECIDED: a specialization)

The LLM envelope ALREADY EXISTS (`ai.cljs:96-103`) and ALREADY carries useful
structured fields, so it is a genuine specialization, not a plain message:

```clojure
;; TODAY (ai.cljs:96-103) — note it predates the base and uses :seon.ai/msg:
(schema/register! :seon.ai/error
  [:map
   [:seon.ai/msg        :seon.ai/msg]                 ; :string
   [:seon.ai/status     {:optional true} :int]         ; HTTP status (4xx/5xx)
   [:seon.ai/timeout?   {:optional true} :boolean]     ; wall-clock abort
   [:seon.ai/transport? {:optional true} :boolean]     ; THE retryable flag (fetch threw pre-status)
   [:seon.ai/raw-body   {:optional true} :string]])    ; raw provider body
```

Both adapters build it the same way (`anthropic.cljs/error->envelope` :255-279,
`openai_compat.cljs/error->envelope` :273-297): APIConnectionError →
`:seon.ai/transport? true` (the one retryable class); an HTTP non-2xx →
`:seon.ai/status (.-status e)`; a wall-clock abort → `:seon.ai/timeout?`. There is
**no `retry-after` or billing field captured today** — only `:status` +
`:transport?` + `:timeout?`. The retryable flag drives the agent loop's one bounded
retry (`turn.cljs:296-309`).

**DECISION:** keep `:seon.ai/error` as the LLM specialization of `:seon/error`. The
ONE reconciliation: it should reference the shared core so the generic surfacer
reads it — i.e. `:seon.ai/msg` becomes (or mirrors to) `:seon.error/message`. Target
shape:

```clojure
;; TARGET — references the base's shared field + keeps the provider variant attrs.
(schema/register! :seon.ai/error
  [:map
   [:seon.error/message :seon.error/message]            ; shared core (was :seon.ai/msg)
   [:seon.ai/status     {:optional true} :int]
   [:seon.ai/timeout?   {:optional true} :boolean]
   [:seon.ai/transport? {:optional true} :boolean]
   [:seon.ai/raw-body   {:optional true} :string]])
```

**How a failed LLM turn surfaces to the agent (so it SEES the failure).** The
adapters return errors-as-VALUES — never a rejected Promise (`ai.cljs:91-95`). In
the turn loop (`turn.cljs:332-340`): a `:seon.ai/error` in the response closes the
turn `:seon.agent.turn/status :error` (no self→self message row); the render then
DERIVES a system line from the turn status (turn.cljs:321), so the failure appears
in the TRANSCRIPT the next prompt shows the agent. A `:seon.ai/transport?` error
gets ONE bounded retry first (turn.cljs:296-309); a persistent failure closes the
run `:error`. So the carrier here is the turn-status datom + the logged
`:seon.ai/msg`, surfaced via the transcript block — no uncaught path, the agent
always sees it.

**How consumers tell errors apart — structurally, never by a field.** The carrier
attribute IS the identification:

- The **render guard** (`seon.render`) builds a `:seon/error` and places it under the
  `:seon.render/error` KEY of the `:seon.render/html-response` map (render.cljs:138-142).
  A consumer that reads `(:seon.render/error html-response)` KNOWS it is a render
  failure because of the slot it came from — no `:kind` needed.
- A **transact failure** returns the `:or` second arm of `:seon.db/transact!`'s
  `::transact-response` carrying the value under the `::error` (=`:seon.db/error`) KEY
  (db.cljs:173-178). Read it from there → it is a transact error.
- An **eval failure** is PERSISTED on the `:seon.eval` row as `:seon.eval/error`
  `:string` (rendered) + `:seon.eval/error-data` `:string` (EDN of the instrumentation
  envelope) + `:seon.eval/record-error` `:string` (agent.cljs:141-149, eval.cljs:1796).
  A warn-check that queries `[?e :seon.eval/ok? false]` and reads `:seon.eval/error`
  KNOWS it is an eval error from the attribute, never a discriminator.

**Persistence, per carrier:** render / transact / capability errors are **transient
in-memory `:seon/error` values** (constructed at the failure site, surfaced by a
render or a check, gone next render — self-healing). The **eval error is PERSISTED**
as the three strings on the `:seon.eval` row (the durable log the runtime
warn-checks query). `:seon/error` and `:seon.db/error` are malli value shapes used
in-memory; neither is itself a transacted entity — only the eval-row string
projections are stored. The instrumentation detail keys `:seon.error.malli/*`
(`error/instrument.cljc:62-71`) are the schema-rejection payload that rides under
`:seon.error/data`; reuse them as-is.

**Pre-existing `:kind` smells to flag (out of this redesign's scope).** Two
attributes already in the tree violate the no-`:kind` rule and should be revisited
separately: `:seon.error/kind :keyword` (error/instrument.cljc:62, labels the
instrumentation sub-error) and `:seon.warn/kind :keyword` (warn.cljs:50, labels which
check produced a cluster). The new error model uses NEITHER; do not reuse
`:seon.error/kind`. Changing the two existing ones is a separate task — flagged, not
silently worked around.

### 3.10 program graph — `:seon.fn` / `:seon.ns` / `:seon.schema` / `:seon.test` (brief)

Blocks, routes, and schedules reference these members BY SYMBOL VALUE (§2.3), not
by ref, so only the identities matter here:

| entity | identity attr | valueType | other refs |
|---|---|---|---|
| `:seon.ns` | `:seon.ns/name` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.ns/requires [:vector :keyword]` (card-many), `:seon.ns/source :string` |
| `:seon.fn` | `:seon.fn/sym` `[:string {:seon.db/identity true}]` | string | `:seon.fn/ns :seon.db/ref` (→ ns), + source/spec/arglists/doc strings |
| `:seon.schema` | `:seon.schema/key` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.schema/ns :seon.db/ref`, source |
| `:seon.test` | `:seon.test/sym` `[:string {:seon.db/identity true}]` | string | `:seon.test/ns :seon.db/ref`, last-passed-at/last-failed-at insts |

The `:seon.schema`-as-queryable-data rows ALSO carry `:seon.schema/id-attr`,
`:seon.schema/required-attrs [:vector :keyword]`, `:seon.schema/render-fn :symbol`,
`:seon.schema/render-html-fn :symbol` (schema.cljc:113-120) — the renderer's
kind-dispatch reads these.

## 4. The warnings / checks registry — the general "current problems" surface

The warnings block is NOT an error list. It is the rendered union of every
non-clean **check** in `seon.warn/checks` (warn.cljs:944-964) — a vector of pure
`(db) → ::check-response` fns. Each response is
`{:seon.warn/kind :keyword, :seon.warn/affected [vector of
{:seon.warn/sym :string, :seon.warn/where? :string}], :seon.warn/explain :string,
:seon.warn/example :string, :seon.warn/urgent?? :boolean, :seon.warn/dev-only??
:boolean}` (warn.cljs:49-93). `:seon.warn/affected` is EMPTY when clean →
the check renders nothing → the surface vanishes when the problem is fixed
(self-healing; never stored; a pure fn of the db). A check that THROWS becomes its
own `:warn-check-error` cluster so one broken check can't blank the block
(warn.cljs:973-987).

The registry today (warn.cljs:949-964), grouped:

| group | checks | what it surfaces | is it an "error"? |
|---|---|---|---|
| contract / lint | `check-no-malli-schema`, `check-return-is-any`, `check-arg-is-any`, `check-uses-maybe`, `check-no-return-spec`, `check-no-input-spec` | unspecced/`:any`/`:maybe` public fns | no — lint |
| schema / domain | `check-parallel-attr`, `check-unmarked-entity-kinds` | drifting attr names, un-marked entity maps | no — design hygiene |
| runtime errors | `check-failed-evals`, `check-bad-ref`, `check-record-errors`, `check-fs-denied` | failed/partially-recorded evals + denied fs calls (read `:seon.eval/ok? false` + `:seon.eval/error`) | **YES — the eval + capability errors** |
| runtime non-errors | `check-slow-evals` (perf), `check-failing-tests` (test status), `check-hop-exhausted` (message routing), `check-tile-unresolved` (render-wiring) | slow evals, red tests, dropped pings, unresolved tiles | mostly no |

So errors feed in as just a HANDFUL of checks (failed-evals, bad-ref,
record-errors, fs-denied — all reading the persisted eval-log error datoms),
alongside non-error checks (perf, lint, test status, routing). Render errors add
ONE MORE check:

- **`check-render-health`** (NEW) — aggregates the current TRANSIENT render
  `:seon/error` values into `:seon.warn/affected` entries, conj'd into `checks`.
  Pure derive, never stored, self-heals next render.

The same `:seon/error` values render as in-place **error tiles** for the human
(html side), so a failure surfaces in TWO places by carrier/site — the in-place
tile (human, where it happened) and the warnings block (agent, aggregated) — and
an eval failure ALSO surfaces in the persisted eval log. The warnings block is the
union of checks; errors are one input among many.

## 5. Error handling — never crash, always surface

**The principle: there is NO uncaught path and NO silent swallow.** Every failure
in the system is CAUGHT at its site and SURFACED in a derived, agent-visible place
(the warnings block, the transcript, an error tile) — never a process crash, never
a discarded exception. This is catch-to-SURFACE, the opposite of catch-to-hide: the
catch exists so the failure becomes a first-class `:seon/error` VALUE that flows to
a render, a check, or a row — exactly the standing "surface errors loudly, fix as
they come" rule, made structural. The pod is single-threaded, so one uncaught throw
would take down every agent + the UI host; the discipline is what keeps one bad
render or one runaway eval from blanking the world. Self-healing falls out: because
every surface is a derived fn of the db (or of a transient render value), the
moment the underlying fact is fixed the surface returns empty and the error
vanishes — no acknowledgement, no stored "last error" to clear.

**Failure-site → surface table.** Every site has a catch, a carrier, and at least
one agent-visible AND one human-visible surface:

| failure site | catch site (fn) | carrier | agent-visible surface | human-visible surface |
|---|---|---|---|---|
| **render** (block ai/html throws, missing symbol, SCI deadline) | `seon.render` guarded walker (render.cljs `render` catch :663-666, `render-entity-html`/`-ai` catches) | transient `:seon/error` under the `:seon.render/error` KEY of `:seon.render/html-response` | warnings block via `check-render-health` (NEW) | the in-place **error tile** (siblings untouched) |
| **eval** (a form in `eval-batch!` throws) | `seon.eval/record-eval!` → `seon.error/->map` | PERSISTED `:seon.eval/error` + `:seon.eval/error-data` on the `:seon.eval` row | `check-failed-evals` / `check-bad-ref` (warn) + the eval's own render in the transcript | the eval tile / transcript line |
| **transact** (a tx is rejected) | `seon.db/transact!` failure arm | transient `:seon.db/error` under `::error` of `::transact-response` | the eval that called `transact!` records it (→ `check-failed-evals`) | the eval tile |
| **capability denial** (fs / `/call` refuses) | `seon.agent.fs` / `seon.web.reactive.call` gate | the denial string in the eval result | `check-fs-denied` (warn) | the eval tile |
| **schema / instrumentation rejection** (a fn's `:malli/schema` rejects args/return) | `seon.error.instrument/report-fn` → ex-info → `eval` catch | `:seon.error.malli/*` under `:seon.eval/error-data` (= malli explain) | `check-failed-evals` renders the structured malli error | the eval tile |
| **LLM / provider error** (timeout, HTTP non-2xx, fetch throw) | adapter `error->envelope` (anthropic/openai-compat) — errors-as-values, never a rejected Promise | `:seon.ai/error` in the response → turn `:seon.agent.turn/status :error` | the **transcript** system line derived from the turn status (turn.cljs:321) | the same transcript line, human side |
| **throwing warn-check** (a check fn itself throws) | `seon.warn/run-checks` per-check catch (warn.cljs:973-987) | synthetic `:warn-check-error` cluster | the warnings block (that check degrades loudly, others render) | the warnings tile |
| **throwing layout / route handler** | a reitit error-catch middleware (the `:compile` middleware seam, middleware.cljc:58-74) | transient `:seon/error` (same as render) | warnings block if the agent owns the route | a human error page / error tile |
| **agent runaway / hung eval** | the deadline ticker → worker `terminate()` (Phase-2); today the turn-limit + run deadline close the run | run `:seon.agent.run/closed-reason :deadline-exceeded` | derived run-status surfaces "deadline exceeded"; agent resets `:idle` | the run-status tile |

Two invariants this table encodes: (1) **no agent code ever touches an SSE
connection or throws into the event loop** — a failure becomes a value the UI host
renders; (2) **every error reaches the agent** (the actor that can fix it) AND the
human (who is watching), from ONE source, because the warnings block / transcript /
tiles are all derived fns of the same db + transient render values.

> Flag for `architecture.md`: this "never crash, always surface" principle and the
> failure-site→surface table belong in the architecture doc's §The render engine
> (which already states "a throwing or hung render yields a structured error value
> for that render only") — generalize it from render to ALL sites.

## 6. Reuse audit — what already exists, do NOT reinvent

Already-registered shapes to REFERENCE, never re-create:

- **`:seon.db/ref`** (schema.cljc:88) — the ONE ref shape; every ref/component-ref
  references it (`:seon.route/owner`, `:seon.agent/ctx`, …). ✓ used correctly by
  the design for `:seon.route/owner` and the component vectors.
- **`:seon.db/id`** (schema.cljc:104) — the shared 14-char id shape behind every
  identity attr.
- **`:seon.agent.ctx/name`** + **`:seon.agent.ctx/priority`** (today `:seon.ctx/name`
  / `:seon.ctx/priority`, ctx.cljs:100-101) — the block's name/sort shapes; reuse the
  shapes, renamed with the `seon.ctx` → `seon.agent.ctx` ns move.
- **`:seon.render/ai`** (render.cljs:79) + **`:seon.render/html`** (render.cljs:86)
  — the two render shapes (mixed `:or`, EDN-encoded). The block schema REFERENCES
  these.
- **`:seon.db/identity` / `:seon.db/component`** — the bridge flags
  (db/internal.cljs:349-350); never hand-write `:db/unique` / `:db/isComponent`.
- **`:seon.error/message`** (used inline in `:seon.db/error`, db.cljs:146) — promote
  to a registered shared FIELD shape; the base `:seon/error` and `:seon.db/error`
  reference it. Do NOT re-inline `[:string]` across error maps.
- **`:seon.error.malli/*`** (error/instrument.cljc:62-71) — the schema-rejection
  payload; reuse as a `:seon.error/data` payload, unchanged.
- **`:seon.db/error`** (db.cljs:143-152) — the serialized-exception/transact
  envelope; KEEP as a specialization of `:seon/error` (it carries the captured
  exception); reference the base's shared field shapes, don't duplicate the message.
- **`:seon.ai/error`** (ai.cljs:96-103) — the LLM/provider envelope (status /
  transport? / timeout? / raw-body); KEEP as the second specialization; reconcile
  `:seon.ai/msg` → `:seon.error/message` so it references the shared core.
- **`malli.error/humanize`** (malli `error.cljc:374`) — produces the humanized
  `:seon.error/message` for the schema kind; never hand-roll schema-error prose.
  Pair with the explain map as `:seon.error/data`.
- **`seon.warn/checks`** registry (warn.cljs:944) — the general current-problems
  mechanism; add a check, don't build a parallel error feed.
- **`set-tee-fn!` idiom** (schema.cljc:183-191: `defonce ^:private` atom +
  installer + guarded read) — the template `set-blocks-provider!` / `default-blocks`
  copies for the override seam.
- **run / turn / `seon.derive`** (run.cljs, turn.cljs, derive.cljs:84 `derive-state`,
  :261 `derive-status`/`:seon.derive/status`) — KEEP unchanged.

Reinvention / mistype findings (prior docs got these wrong):

1. **Block schema re-inlines registered render shapes.** The unification design §1
   writes `[:seon.render/ai {:optional true} [:or :symbol :string]]` and
   `[:seon.render/html {:optional true} [:or :symbol :seon.render/hiccup]]` —
   re-inlining shapes that are ALREADY registered as `:seon.render/ai` /
   `:seon.render/html`. Correct form (and what the live `:seon.ctx/section` already
   does, ctx.cljs:108-113): reference the registered attrs
   `[:seon.render/ai {:optional true} :seon.render/ai]`. Inlining duplicates the
   mixed-`:or` and will drift from the bridge's EDN-encoding detection
   (`edn-encoded-attr?`, db/internal.cljs:362 keys off the REGISTERED form).

2. **Render-only error model with a discriminator.** The unification design §8 /
   migration §7 register a fresh render-specific `:seon.render/error` `[:map
   [:seon.error/message :string] [:seon.error/where :keyword] …]`; an earlier draft
   added a `:seon.error/kind` enum discriminator. BOTH are wrong. Correct form (§3.9):
   ONE base `:seon/error`; NO `:kind`/`:type` field anywhere (discriminate by the
   carrier attribute — the same structural rule as the render twins). A render error
   does not diverge from the base, so do NOT register a distinct `:seon.render/error`
   schema — the html-response just carries a `:seon/error` value under the
   `:seon.render/error` KEY. Only `:seon.db/error` diverges (it carries the captured
   exception) and stays as the one specialization.

3. **`:seon.error/message` inlined.** It appears as a literal `:string` map entry in
   `:seon.db/error`. Per the shared-shape rule, register `:seon.error/message :string`
   once and reference it from the base and the specialization.

4. **`:seon.agent.ctx/name` called "the single identity".** The design §1 says
   `:seon.agent.ctx/name` is "THE id: upsert key". It is NOT a datahike identity (must
   stay a plain `:keyword` — global uniqueness would forbid two agents sharing a
   block name); the upsert is an app-level per-agent merge. Correct framing: name is
   the per-agent logical key + DOM slot id; the entity identity is the component
   eid under `:seon.agent/ctx`.

5. **Route handler reuses `:seon.render/html`.** Works but stores EDN-encoded and a
   route handler is never hiccup. Recommend the dedicated `:seon.route/handler
   :symbol` (native `:db.type/symbol`). Flagged, not forced.

6. **`:seon.agent/state` enum still in agent-runtime-spec.** That doc registers
   `:seon.agent/state [:enum …]` "DERIVED — never transacted" (line 59). State has
   since moved fully to `seon.derive` (`:seon.derive/state`, derive.cljs:37); there
   is no `:seon.agent/state` attr to register. Treat the runtime-spec line as
   superseded.

7. **No `:kind`/`:type` discriminator — anywhere, for any entity (hard rule).** The
   discriminator is ALWAYS the namespaced keyword itself (an attr's presence /
   identity), never a `:kind`/`:type` field — exactly the render-twin rule (presence
   of `:seon.render/ai` vs `:seon.render/html` picks the surface). The model honors
   this for errors (§3.9: carrier attribute, not a field), blocks (render presence),
   and routes (method/owner attrs). Two PRE-EXISTING violations to flag for a separate
   task: `:seon.error/kind` (error/instrument.cljc:62) and `:seon.warn/kind`
   (warn.cljs:50). Do NOT add new `:kind`/`:type` attrs.

## 7. Decisions + remaining open questions

### 7.1 Locked (owner-decided)

1. **Route handler attr = dedicated `:seon.route/handler :symbol`** (native symbol
   storage), NOT a reuse of `:seon.render/html`. (§3.8.)
2. **Routes are seeded-base + agent-extendable**, agent app routes capability-gated,
   handlers in the agent's `my.agent.<id>` ns, owner = self. (§3.8.)
3. **`:seon.route/name` is per-agent namespaced** (e.g. `:agent.abc/app-x`) so the
   global identity stays unique for reverse routing. (§3.8.)
4. **Error model = one base `:seon/error` + two specializations** (`:seon.db/error`
   for the captured exception, `:seon.ai/error` for the provider/transport fields);
   render / eval / transact / capability / schema errors all FOLD into the base.
   NO `:kind`/`:type` discriminator; discriminate by carrier attr. (§3.9.)
5. **Error value carries BOTH `:seon.error/message` (humanized, via
   `malli.error/humanize` for the schema kind) AND `:seon.error/data` (the structured
   payload, = the malli explain map for schema errors), always.** Humanize is a
   derived view; data is the source of truth. (§3.9.)
6. **Promote the shared error FIELD shapes** (`:seon.error/message`/`where`/`hint`/
   `symbol`/`data`) to registered schemas referenced by the base + both
   specializations (the shared-shape rule).
7. **Block name is NOT a datahike identity** — a plain per-agent `:keyword`; merge is
   app-level (so it is not "fixed" into an identity by a later patch). (§3.2.)
8. **NS move `seon.ctx` → `seon.agent.ctx`** (every `:seon.ctx/*` → `:seon.agent.ctx/*`),
   CLJS track only, folded into the atomic `section`→`block` patch + cluster reset; the
   paused JVM `.clj` side stays on `:seon.ctx/*`. (§3.2.)
9. **The root agent** (`:seon.agent/id "root"`) — the all-agents overview IS the root
   agent's world at `/` (no separate "dashboard"); root has system SCOPE (blocks query
   across agents) + an ELEVATED capability grant (system `:seon.fn`s) through the SAME
   `/call` gate. (§3.8.)

### 7.2 Remaining open

1. **Retire the two pre-existing `:kind` attrs?** `:seon.error/kind`
   (error/instrument.cljc:62) and `:seon.warn/kind` (warn.cljs:50) both use the now-
   banned `:kind` pattern. The new error model uses neither. Decide whether to
   replace them (out of this redesign's scope — flagged, separate task).
2. **The `where` overlap** — `:seon.error/where :keyword` (new) vs the existing
   `:seon.warn/where :string` (warn.cljs:52): two `where`s, two namespaces, similar
   meaning. Keep separate (different ns, different value type) or unify.
3. **`:seon.ai/error` `:seon.ai/msg` → `:seon.error/message` reconciliation** — the
   one source delta to make the LLM error participate in the generic surfacer (§3.9b).
   Do it now or stage it.
4. **`:seon.agent.turn/llm-meta`** — write-only, never read (turn.cljs:76); drop or
   keep as audit. (Carried from agent-runtime-spec open decision 3.)

## Detail docs

- [[agent-runtime-spec]] — the run / turn / FSM baseline this extends.
- [[layout-context-unification-design-2026-06-27]] — the block/render/tile/slot/
  layout/route + override-seam design (the surface this model makes concrete).
- [[layout-context-migration-2026-06-27]] — the file:line migration (rename map).
- [[architecture]] — §Glossary (locked vocabulary), §The data model, §The render
  engine, §Routing is data.
- [[datahike-primer]] — the "work in datahike's grain" mindset for the bridge.
