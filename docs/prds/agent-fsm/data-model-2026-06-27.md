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
page · tile · slot · layout · canvas · world · dashboard · app · route ·
warnings block**. The error value is **one general base shape**, not
render-specific, with NO `:kind`/`:type` discriminator — specialized only where a
shape truly diverges.

## 1. TL;DR — the entity graph in one paragraph

The **agent** (`:seon.agent/id`, identity) is the root. It OWNS, by
cascade-retract component vectors, its **blocks** (`:seon.agent/ctx` →
`:seon.ctx/block` children — the context units, each up to two renders) and its
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
`:seon.route/owner` is a ref to the owning agent. The **state** is never stored —
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
constraint follows. Note `:seon.ctx/name` is NOT in this table (see §4 block):
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

### 3.2 block — `:seon.ctx/block` (ctx.cljs:100-113, RENAME of `:seon.ctx/section`)

| attribute | malli | datahike facet | reuse/NEW | notes |
|---|---|---|---|---|
| `:seon.ctx/name` | `:keyword` | keyword / one | reuse | per-agent logical name; prompt header + DOM `#tile-<name>` — **NOT a datahike identity** (see below) |
| `:seon.ctx/priority` | `:int` | long / one | reuse | prompt order AND default scroll order |
| `:seon.render/ai` | `:seon.render/ai` (ref to the registered `[:or :string :symbol]`) | string (EDN) / one | reuse | **make `{:optional true}`** (ctx.cljs:112) — html-only blocks |
| `:seon.render/html` | `:seon.render/html` (ref to registered shape) | string (EDN) / one | reuse | optional; present ⇒ a tile |

The block map schema (rename `:seon.ctx/section` → `:seon.ctx/block`, make ai
optional):

```clojure
(schema/register! :seon.ctx/block
  [:map
   [:seon.ctx/name     :seon.ctx/name]
   [:seon.ctx/priority :seon.ctx/priority]
   [:seon.render/ai    {:optional true} :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]])
```

**Decision — blocks are component children, name is NOT a datahike identity.**
Each block is its own entity, owned via `:seon.agent/ctx`
(`[:vector {:seon.db/component true} :seon.db/ref]`) so it cascade-retracts with
the agent. `:seon.ctx/name` stays a plain `:keyword`: if it were
`{:seon.db/identity true}` the uniqueness would be GLOBAL, and two agents could not
both own a `:transcript` block (the second upsert would steal the first's eid).
The "upsert by name" the design docs describe is an APPLICATION-level merge in
`gather-blocks` (override-by-name within one agent's set merged over
`default-blocks`), not a datahike identity upsert. The block schema REFERENCES the
already-registered `:seon.render/ai` / `:seon.render/html` shapes — it does NOT
re-inline `[:or :symbol :string]` (see §5 reinvention finding).

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
| `:seon.route/handler` | `:symbol` | **symbol** / one | NEW (recommended) | the layout symbol → `{:handler …}` via reitit `Expand` (core.cljc:29) |
| `:seon.route/middleware` | `[:vector :keyword]` | keyword / **many** | NEW (optional) | `:middleware` keywords resolved through reitit's `::registry` (middleware.cljc:15-33) |

`seon.route` entity-map (`:map {:seon.db/entity true}` with `pattern`/`method`/
`name`/`handler` required, `owner`/`middleware` optional). Datahike tolerates the
extra `:seon.route/owner` key because reitit route-data is an OPEN map (`Expand`
on a map returns it unchanged, core.cljc:21). reitit gives build-time path + name
**conflict detection** (`path-conflicting-routes` → `:conflicts` throws
`:path-conflicts`, core.cljc:292,329) that the hand-rolled `cond` dispatch lacks.

Handler-attr decision: the design docs say "the handler reuses `:seon.render/html`"
(unifying "a route handler IS a layout IS a block's html render"). That WORKS (the
symbol arm of the mixed `:or`, stored EDN-encoded) but a route handler is never
literal hiccup, so a dedicated `:seon.route/handler :symbol` (native
`:db.type/symbol`, cleaner storage) is recommended. Either resolves via
`lookup-value`. Flagged in §6.

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

```clojure
;; Shared FIELD shapes (registered once; both the base and any specialization
;; reference these — never re-inline [:string] across error maps).
(schema/register! :seon.error/message :string)   ; promote from the inline use in :seon.db/error
(schema/register! :seon.error/where   :keyword)   ; NEW — the site: a block/route/fn name
(schema/register! :seon.error/symbol  :symbol)    ; NEW — the offending fn
(schema/register! :seon.error/hint    :string)    ; NEW — the actionable fix
(schema/register! :seon.error/data    :map)       ; NEW — opaque per-site payload

;; THE base error — every error IS one of these unless it genuinely diverges.
(schema/register! :seon/error
  [:map
   [:seon.error/message :seon.error/message]
   [:seon.error/where   {:optional true} :seon.error/where]
   [:seon.error/symbol  {:optional true} :seon.error/symbol]
   [:seon.error/hint    {:optional true} :seon.error/hint]
   [:seon.error/data    {:optional true} :seon.error/data]])
```

**Per-error DECISION — diverge or fold?** Apply the rule "mint a specialized
keyword only if the shape truly differs" to each error shape that exists today:

| error keyword | diverges from `:seon/error`? | decision |
|---|---|---|
| **render error** | NO — message + where (block/route name) + symbol (offending fn) + hint is exactly the base | **FOLD.** Do NOT register a distinct `:seon.render/error` schema (it would be identical). The render guard PRODUCES a `:seon/error`; the html-response carries it under the `:seon.render/error` KEY (the structural discriminator — see below). Replaces the current bare alias `(register! :seon.render/error :seon.db/error)` (render.cljs:116). |
| **transact error** `:seon.db/error` (db.cljs:143-152) | YES — adds the serialized JS exception (`:seon.error/ex-data?`, `:stack?`, `:cause?`, `:raw?`, `:truncated?`), built by `seon.error/->map` from a thrown error | **KEEP as a specialization** referencing the base's shared fields, adding the exception-capture fields. It is the only error that carries a serialized exception, so it genuinely diverges. |
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

## 5. Reuse audit — what already exists, do NOT reinvent

Already-registered shapes to REFERENCE, never re-create:

- **`:seon.db/ref`** (schema.cljc:88) — the ONE ref shape; every ref/component-ref
  references it (`:seon.route/owner`, `:seon.agent/ctx`, …). ✓ used correctly by
  the design for `:seon.route/owner` and the component vectors.
- **`:seon.db/id`** (schema.cljc:104) — the shared 14-char id shape behind every
  identity attr.
- **`:seon.ctx/name`** (ctx.cljs:100) + **`:seon.ctx/priority`** (ctx.cljs:101) —
  the block's name/sort; reuse verbatim.
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
  envelope; KEEP as the one specialization of `:seon/error` (it carries the captured
  exception); reference the base's shared field shapes, don't duplicate the message.
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

4. **`:seon.ctx/name` called "the single identity".** The design §1 says
   `:seon.ctx/name` is "THE id: upsert key". It is NOT a datahike identity (must
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

## 6. Open data-model decisions (for the owner)

1. **Route handler attr** — dedicated `:seon.route/handler :symbol` (recommended,
   native symbol storage) vs reuse `:seon.render/html` (the design's "handler IS a
   layout" unification, EDN-encoded). Pick one before seeding routes.
2. **`:seon.route/name` uniqueness scope** — it is a GLOBAL datahike identity (good
   for `match-by-name`), so per-agent app routes (`/agent/{id}/app/{x}`) must mint
   globally-unique names (e.g. `:agent.abc/app-x`). Confirm the naming convention,
   or scope names per-agent in app code (and drop the global identity).
3. **Are routes seeded-only or agent-extendable?** Core routes (`/`, `/agent/{id}`,
   `/agent/{id}/feed`, `/call`, `/eval`) seed at boot. Do agents transact their own
   `:seon.route/*` rows for apps (`/agent/{id}/app/{x}`), and if so, is route
   creation a capability-gated verb? (Affects the conflict-detection blast radius.)
4. **Confirm `:seon/error` as the base + `:seon.db/error` as the lone divergence.**
   §3.9 folds render/eval errors into the base (no distinct schema, discriminate by
   carrier) and keeps only `:seon.db/error` (carries the exception) as a
   specialization. Confirm no other error genuinely diverges (e.g. an LLM/provider
   error — does it carry provider-specific fields, or is it just a `:seon/error` under
   an `:seon.ai/error` carrier key? Lean: just a base value, no new schema).
5. **Field-shape sharing + the `where` overlap.** Promote `:seon.error/message`,
   `:seon.error/where`, `:seon.error/hint`, `:seon.error/symbol`, `:seon.error/data`
   to registered shared FIELD shapes referenced by both error maps (recommended).
   Note the overlap with `:seon.warn/where :string` (warn.cljs:52) — two `where`s,
   two namespaces, similar meaning; keep separate (different ns) or unify.
6. **Retire the two pre-existing `:kind` attrs?** `:seon.error/kind`
   (error/instrument.cljc:62) and `:seon.warn/kind` (warn.cljs:50) both use the now-
   banned `:kind` pattern. The new error model uses neither. Decide whether to
   replace them (out of this redesign's scope — flagged, separate task).
7. **Block name as datahike identity — confirmed NO** (finding 4); calling it out so
   it is not "fixed" into an identity by a later patch.
8. **`:seon.agent.turn/llm-meta`** — write-only, never read (turn.cljs:76); drop or
   keep as audit. (Carried from agent-runtime-spec open decision 3.)

## Detail docs

- [[agent-runtime-spec]] — the run / turn / FSM baseline this extends.
- [[layout-context-unification-design-2026-06-27]] — the block/render/tile/slot/
  layout/route + override-seam design (the surface this model makes concrete).
- [[layout-context-migration-2026-06-27]] — the file:line migration (rename map).
- [[architecture]] — §Glossary (locked vocabulary), §The data model, §The render
  engine, §Routing is data.
- [[datahike-primer]] — the "work in datahike's grain" mindset for the bridge.
