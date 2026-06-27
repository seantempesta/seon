---
type: prd
status: active
tags: [prd, schema, database, agent]
---

# The Seon data model — entities, schema, errors

> **Target design** (present tense — the system as it is when built). Current code state + the migration path live in [[roadmap]].

The complete, concrete schema layer: every entity, attribute, type, and ref;
the three relationship kinds; how an entity's kind is identified; the
`my.*` domain schemas; and the one general `:seon/error` value. Vocabulary is
locked in [[architecture]] (the glossary). This doc owns the **schema**; it
points at [[ui]] for the render/route/slot machinery, at [[agent-runtime]] for
the loop/lifecycle that mutates these rows, and at [[toolkit]] for the verbs an
agent calls over them.

## 1. TL;DR — the entity graph in one paragraph

The **agent** (`:seon.agent/id`, identity) is the root. It OWNS, by
cascade-retract component vectors, its **blocks** (`:seon.agent/ctx` →
`:seon.agent.ctx/block` children — the context units, each up to two renders)
and its **schedules** (`:seon.agent/schedules` → `:seon.agent.schedule` cron
maps). It POINTS (plain ref) at its current **run** (`:seon.agent/run`) and at
the agent that started it (`:seon.agent/parent`); a run points back
(`:seon.agent.run/agent`); **turns** point up to their run
(`:seon.agent.turn/run`) and own their **evals**
(`:seon.agent.turn/evals`). **Messages** (`:seon.agent.message`) and the one
**user** (`:seon.user/id`) are independent entities joined by refs. The
**program graph** (`:seon.ns` source, `:seon.fn`, `:seon.schema`, `:seon.test`,
`:seon.eval`) is the agent's code-and-history as data; blocks, routes, and
schedules reference its members by **symbol value** (late-resolved, NOT a
datahike ref). **Routes** (`:seon.route`) are datoms a `db->routes` fn projects
into reitit. The **root agent** (`:seon.agent/id "root"`) is the seeded base
agent whose world is the `/` overview and who holds the lifecycle grant (see
[[agent-runtime]]). The agent's **state is never stored** — it is derived
(`seon.derive/derive-state`) from its primitives. The **`my.*` domain schemas**
carry the agent's actual data: `my.kb` (a global knowledge base — rows carry no
agent ref), `my.todo` (a per-agent plan TREE — rows carry `:my.todo/agent` and
`:my.todo/parent`), and `my.agent` (the agent's `:my.agent/purpose`). **Global
vs per-agent is a property of the DATA's agent-ref**, never of the block and
never of a stored `:kind`. The **error value** is ONE base shape, `:seon/error`,
specialized only where the shape genuinely diverges; failures never crash, they
surface.

## 2. Three relationship kinds

Seon expresses relationships three ways. Conflating them is the single biggest
data-model error, so each is pinned against the bridge (`seon.db.internal`) and
the vendored datahike source ([[datahike-primer]]).

### 2.1 datahike ref (`:seon.db/ref`) and component refs

`:seon.db/ref` is the ONE canonical ref shape, registered once in `seon.schema`
as `[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]` — the set of
forms datahike resolves to an eid at transact time. The bridge special-cases it:
`resolve-malli-form` returns `:seon.db/ref` unchanged and
`form->datahike-value-type` maps it directly to `:db.type/ref`, NOT by following
its `[:or …]` body. Every ref attribute REFERENCES this shape — never inline an
`[:or :int :string …]`.

A **plain ref** is a single pointer (`:db.cardinality/one :db.type/ref`):
`:seon.agent/run`, `:seon.agent/parent`, `:seon.agent.run/agent`,
`:seon.agent.run/cause`, `:seon.agent.turn/run`, `:seon.fn/ns`, `:seon.schema/ns`,
`:seon.test/ns`, `:seon.agent.message/from`, `:seon.route/owner`,
`:my.todo/agent`, `:my.todo/parent`. Use a plain ref when the entity does NOT own
the referent's lifecycle — a fn does not own its ns; a turn does not own its run;
a todo does not own its parent.

A **component ref vector** `[:vector {:seon.db/component true} :seon.db/ref]` is
an OWNED-children list: `{:seon.db/component true}` → `:db/isComponent true` and
the vector wrapper → `:db.cardinality/many`. Datahike requires a component attr
to also be `:db.type/ref`, which `:seon.db/ref` provides. **Component = cascade
retract:** on `[:db.fn/retractEntity parent]`, datahike's `retract-components`
(in `db/transaction.cljc`) maps every component-attr datom to a child
`retractEntity`, so retracting the agent retracts every owned block and schedule,
and retracting a turn retracts its evals. The owned-children attrs:
`:seon.agent/ctx`, `:seon.agent/schedules`, `:seon.agent.turn/evals`,
`:seon.render/children`.

Lookup-by-identity rides on a ref's `[:attr val]` form: `[:seon.agent/id "abc"]`
is a valid `:seon.db/ref` value (the `[:tuple :keyword …]` arm) that datahike
resolves to the agent's eid, so a write can reference an entity by its natural
key without first querying its eid.

### 2.2 identity / lookup attrs (`{:seon.db/identity true}`)

`{:seon.db/identity true}` → `:db/unique :db.unique/identity`. An identity attr
makes the entity upsertable by that value: transacting a map carrying the
identity attr MERGES into the existing entity rather than creating a new one —
this is how "redefine = upsert" works for the program graph and how a fresh
agent re-seeds idempotently.

| identity attr | malli shape | datahike valueType | role |
|---|---|---|---|
| `:seon.agent/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | agent natural key |
| `:seon.agent.run/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | the **fencing token** |
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | turn key |
| `:seon.agent.message/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | message key |
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | schedule key |
| `:seon.eval/id` | `[:and {:seon.db/identity true} :seon.db/id]` | `:db.type/string` | eval key |
| `:seon.user/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | the one human |
| `:seon.fn/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | fn qualified-sym key |
| `:seon.test/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | test key |
| `:seon.ns/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | ns key |
| `:seon.schema/key` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | schema-attr key |
| `:seon.route/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | reverse-routing key |
| `:my.kb.shared/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | global KB entry key |
| `:my.todo/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | todo key |

`:seon.db/id` is itself a shared shape (`[:string {:min 14 :max 14}]`) referenced
by every string id-attr — bump it once, every length constraint follows.
`:seon.agent.ctx/name` is NOT an identity (see §4.2): it is a plain `:keyword`, a
per-agent upsert key, not a global identity.

### 2.3 symbol-as-value — late binding to the program graph (NOT a ref)

A render fn, a route handler, and a schedule fn are stored as **symbol values**,
resolved late at use time via `seon.eval/lookup-value`. They are NOT datahike
refs — there is no entity to point at; the symbol names a var in the running
program, which the program-graph entities (`:seon.fn`, `:seon.ns`) also describe,
but the binding is by NAME at call time, not by eid at write time. This is what
lets an agent transact `:seon.render/html 'my.agent.abc/status-tile` before (or
after) the fn exists, and lets a redefine take effect with no re-transact.

Two storage encodings, both VALUES:

- **Pure `:symbol`** → `:db.type/symbol` (datahike has a native symbol type).
  Used by `:seon.agent.schedule/fn` and `:seon.route/handler`.
- **Mixed `:or` (symbol OR data)** → stored as a **pr-str'd EDN string**
  (`:db.type/string`), because datahike's typed schema cannot hold a scalar
  union; the bridge encodes on write and `seon.db/decode-edn-value` decodes on
  read. Used by `:seon.render/ai` (`[:or :string :symbol]`) and
  `:seon.render/html` (references `:seon.render.live-tile/content`,
  `[:or :symbol ::hiccup]`).

The render path resolves these through ONE engine: `ai-render` / `html-render`
call `eval/lookup-value` on a qualified symbol, falling through to a
pretty-printer on a miss. Agent-authored symbols run SCI-bounded; core symbols
run compiled. The render engine itself is owned by [[ui]].

## 3. Identifying an entity's kind — presence, not a stored field

Datahike has no entity type or class: an entity IS its attributes; schema is
per-attribute; entities are enumerated by walking AEVT *for an attribute*. So
Seon never stores a field whose job is to select which schema a row obeys. This
is the dual of "how refs work" and the rule the whole schema honors.

**The rule.** An entity's kind is the set of attributes it carries — primarily
its identity attr. You IDENTIFY kind two ways:

- **Stored rows** → the required-attr subset test against the registered
  `{:seon.db/entity true}` schemas: the most-specific kind (the most required
  attrs, alphabetical tie-break) whose `:seon.schema/required-attrs` are all
  present on the entity. This is what `:seon.schema/id-attr` →
  `entity-primary-kind` already computes.
- **In-flight values** → malli `:orn` + `m/parse`: a tagged-or over the
  registered kinds, branches ordered most-specific-first, returns a `Tag` whose
  `:key` IS the identified kind in one structural pass.

```clojure
;; Stored: the most-specific registered kind whose required-attrs are present.
;; A pulled :seon.fn row carries no :kind field; it is identified as :seon.fn
;; because #{:seon.fn/ns :seon.fn/source :seon.fn/sym} are all present.

;; In-flight: the matching Tag's :key is the kind (read (:key tag), not (first)).
(m/parse [:orn [:my.kb.shared :my.kb.shared] [:my.todo :my.todo]] a-value)
;; => #malli.core.Tag{:key :my.kb.shared, :value {…}}
```

**Entity-kind discriminator (BANNED) vs value enum (FINE) — the distinction the
whole audit turns on.** Two things wear the keyword `kind`/`type`:

- **Entity-kind discriminator (BANNED):** a STORED field whose VALUE selects
  *which schema a row obeys* — "is this row a session or a message or a
  tool-call?". Datahike has no concept of this; identify by attribute presence
  instead. The active runtime has **zero** of these.
- **Value enum (FINE, even when literally named "kind"):** a *flavor of an
  already-identified single kind* — a derived label, a fault tag, a library
  shape. These are correct and KEPT:
  - `:seon.error/kind` — the fault tag on an error VALUE (`:user-input` vs
    `:core-bug` vs `:compile` …); it is read to retag whether a failure is
    caller-fixable. Not an entity selector.
  - `:seon.warn/kind` — the source-check identifier on a DERIVED (never-stored)
    warning map; exactly malli's "registry key into messages" pattern.
  - `:seon.agent.message/origin`, `:seon.agent.run/trigger`,
    `:seon.agent.run/closed-reason`, `:my.todo/status` — value enums that flavor
    one already-identified entity kind.
  - Library / third-party shapes (a `cljs.test` report `:type`, an Anthropic
    content block `{:type "text"}`, a rewrite-clj node `:type`, datahike's own
    `:db.secondary/type`) and derived labels (a `store-inventory` `:kind` =
    `(keyword (namespace a))`).

The audit question for any `kind`/`type` field is therefore: does it SELECT the
entity's schema (BANNED → make it presence-based) or is it a value-flavor /
derived label / library shape (FINE → keep the enum)?

## 4. Per-entity schema tables

Facet column reads `valueType / cardinality / unique|component`. Mixed-`:or`
attrs note the EDN-string storage. Every attribute below is named, typed, and
registered via `schema/register!`; the bridge derives the datahike facet.

### 4.1 agent — `:seon.agent/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | the root identity |
| `:seon.agent/parent` | `:seon.db/ref` | ref / one | optional; → the agent that started this one (absent on the root agent — the base case) |
| `:seon.agent/run` | `:seon.db/ref` | ref / one | optional; → current run; the fencing pointer + derived-state spine |
| `:seon.agent/terminated-at` | `:inst` | instant / one | optional; presence ⇒ derived `:terminated` |
| `:seon.agent/default-turn-limit` | `:int` | long / one | optional; seeds a run's work bound |
| `:seon.agent/default-deadline-ms` | `:int` | long / one | optional; seeds a run's clock bound |
| `:seon.agent/schedules` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned cron maps (cascade-retract) |
| `:seon.agent/ctx` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned **blocks** (cascade-retract), seeded at creation, sorted by `:seon.agent.ctx/priority` at render |
| `:seon.render/ai` | `:seon.render/ai` | string (EDN) / one | optional; the agent record's own ai render (absent by default) |
| `:seon.render/html` | `:seon.render.live-tile/content` | string (EDN) / one | optional; per-entity tile-render override |

The `:seon.agent` entity map (`{:seon.db/entity true}`) lists `id` required,
everything else optional. **State is derived, never stored** — there is no
`:seon.agent/state` datom; `seon.derive/derive-state` is the one projection rule
([[agent-runtime]]). The agent map is open, so an agent entity also carries
`:my.agent/purpose` (§5.4) seeded into it at creation. The lifecycle that writes
`:seon.agent/run` / `:seon.agent/parent` / `:seon.agent/terminated-at` lives in
[[agent-runtime]].

### 4.2 block — `:seon.agent.ctx/block`

A block is one context unit: a function-of-the-DB map with up to two renders.
Blocks live in the `seon.agent.ctx` namespace and are owned by the agent via
`:seon.agent/ctx`.

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.ctx/name` | `:keyword` | keyword / one | per-agent upsert key; prompt header + DOM `#tile-<name>` — **NOT a datahike identity** |
| `:seon.agent.ctx/priority` | `:int` | long / one | prompt order AND default scroll order |
| `:seon.render/ai` | `:seon.render/ai` | string (EDN) / one | optional; the prompt-text render |
| `:seon.render/html` | `:seon.render/html` | string (EDN) / one | optional; present ⇒ a tile |

```clojure
;; ns seon.agent.ctx
(schema/register! :seon.agent.ctx/block
  [:map
   [:seon.agent.ctx/name     :seon.agent.ctx/name]
   [:seon.agent.ctx/priority :seon.agent.ctx/priority]
   [:seon.render/ai          {:optional true} :seon.render/ai]
   [:seon.render/html        {:optional true} :seon.render/html]])
```

**Seed-copy, one collection.** ALL blocks are seeded into the agent's own
`:seon.agent/ctx` at creation; render reads the agent's COMPLETE `:seon.agent/ctx`
sorted by `:seon.agent.ctx/priority`. There is no render-time merge over a
separate default set — each agent owns its complete block set. Each block is its
own component entity, so it cascade-retracts with the agent. The install/remove
+ variadic seed mechanism
and the priority-sort render are owned by [[ui]].

**Name is a plain `:keyword`, not a datahike identity.** If it were
`{:seon.db/identity true}` the uniqueness would be GLOBAL, and two agents could
not both own a `:transcript` block (the second upsert would steal the first's
eid). "Upsert by name" is an application-level merge WITHIN one agent's set, not
a datahike identity upsert. The block schema REFERENCES the registered
`:seon.render/ai` / `:seon.render/html` shapes — it never re-inlines
`[:or :symbol :string]` (that would drift from the bridge's EDN-encoding
detection, which keys off the registered form). The block carries no `:kind`:
render presence (`:seon.render/ai` vs `:seon.render/html`) selects which render
is produced (§3).

### 4.3 run — `:seon.agent.run/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.run/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | the fencing token |
| `:seon.agent.run/agent` | `:seon.db/ref` | ref / one | back-ref → agent |
| `:seon.agent.run/started-at` | `:inst` | instant / one | wake time |
| `:seon.agent.run/trigger` | `[:enum :message :schedule]` | keyword / one | value enum |
| `:seon.agent.run/cause` | `:seon.db/ref` | ref / one | → the waking message (when `:message`) |
| `:seon.agent.run/turn-limit` | `:int` | long / one | work bound (bumpable) |
| `:seon.agent.run/deadline` | `:inst` | instant / one | absolute clock bound |
| `:seon.agent.run/last-beat-at` | `:inst` | instant / one | heartbeat |
| `:seon.agent.run/paused-at` | `:inst` | instant / one | presence ⇒ derived `:paused` |
| `:seon.agent.run/remaining-ms` | `:int` | long / one | banked at pause, re-extends deadline at resume |
| `:seon.agent.run/status` | `[:enum :open :closed]` | keyword / one | value enum |
| `:seon.agent.run/closed-reason` | `[:enum :completed :waited :turn-limit :deadline-exceeded :terminated :superseded :error :crashed]` | keyword / one | present iff `:closed` |

`turn-count` / `now` / `snapshot` are derived-read scalars, not stored datoms.
The run model + FSM live in [[agent-runtime]].

### 4.4 turn — `:seon.agent.turn/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | |
| `:seon.agent.turn/at` | `:inst` | instant / one | |
| `:seon.agent.turn/status` | `[:enum :running :done :error]` | keyword / one | value enum |
| `:seon.agent.turn/run` | `:seon.db/ref` | ref / one | turn → its run |
| `:seon.agent.turn/prompt-chars` | `:int` | long / one | |
| `:seon.agent.turn/prompt-file` | `:string` | string / one | |
| `:seon.agent.turn/llm-retries` | `:int` | long / one | |
| `:seon.agent.turn/llm-usage` | `:string` | string / one | |
| `:seon.agent.turn/evals` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned evals (cascade-retract) |

### 4.5 message + user — `:seon.agent.message/*`, `:seon.user/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.message/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | |
| `:seon.agent.message/content` | `:string` | string / one | |
| `:seon.agent.message/from` | `:seon.db/ref` | ref / one | → user or agent |
| `:seon.agent.message/to` | `[:vector :seon.db/ref]` | ref / **many** | recipients (NOT component — not owned) |
| `:seon.agent.message/at` | `:inst` | instant / one | |
| `:seon.agent.message/hops` | `:int` | long / one | hop-cap guard |
| `:seon.agent.message/origin` | `[:enum :human :agent :core]` | keyword / one | value enum; `:core` marks quiet bootstrap forms (see [[agent-runtime]]) |

`:seon.user/id` (`[:string {:seon.db/identity true}]`) + the `:seon.user`
entity-map are the one human; `user-ref` = `[:seon.user/id "user"]`. An inbound
human message is the first trigger of a run and auto-mints a `my.todo` (§5.3); a
hop-exhausted message becomes a dead-letter.

### 4.6 schedule — `:seon.agent.schedule/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | |
| `:seon.agent.schedule/cron` | `:string` | string / one | 5-field cron |
| `:seon.agent.schedule/fn` | `:symbol` | **symbol** / one | qualified fn to invoke — symbol-as-value (§2.3) |
| `:seon.agent.schedule/timezone` | `:string` | string / one | IANA tz |
| `:seon.agent.schedule/concurrency-policy` | `[:enum :forbid :allow]` | keyword / one | value enum |

### 4.7 eval — `:seon.eval/*`

Every form an agent (or the boot/quiet-`:core` bootstrap) evaluates is recorded
as a `:seon.eval` row — the durable history the warn-checks query and the
transcript renders.

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.eval/id` | `[:and {:seon.db/identity true} :seon.db/id]` | string / one / identity | |
| `:seon.eval/origin` | `[:enum :human :agent :core]` | keyword / one | value enum; `:core` ⇒ quiet (no wake, no turn-count) — the bootstrap origin |
| `:seon.eval/source` | `:string` | string / one | the form's source |
| `:seon.eval/ok?` | `:boolean` | boolean / one | false ⇒ a failed eval |
| `:seon.eval/error` | `:string` | string / one | optional; the rendered error headline |
| `:seon.eval/error-data` | `:string` | string / one | optional; EDN of the structured error payload (§6) |

The `:core`-origin quietness (bootstrap forms run synchronously before any
trigger, recorded but not waking a run) is owned by [[agent-runtime]].

### 4.8 route — `:seon.route/*`

Each row is a datom a `db->routes` fn projects into a reitit route vector. The
schema lives here; reitit, `db->routes`, the capability gate, and the root-world
(`/`) are owned by [[ui]].

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.route/pattern` | `:string` | string / one | the path string `"/agent/{id}"` |
| `:seon.route/method` | `:keyword` | keyword / one | `:get`/`:post`/… → the method endpoint |
| `:seon.route/name` | `[:keyword {:seon.db/identity true}]` | keyword / one / identity | reverse-routing key; agent app routes namespace per-agent (e.g. `:agent.abc/app-x`) so identities never collide |
| `:seon.route/owner` | `:seon.db/ref` | ref / one | → owning agent; rides as opaque route-data, meta-merges parent→child for auth |
| `:seon.route/handler` | `:symbol` | **symbol** / one | the layout symbol → resolved via `lookup-value`; symbol-as-value (§2.3) |
| `:seon.route/middleware` | `[:vector :keyword]` | keyword / **many** | optional; middleware keywords resolved through reitit's registry |

The `seon.route` entity map (`{:seon.db/entity true}`) requires
`pattern`/`method`/`name`/`handler`, with `owner`/`middleware` optional.
`:seon.route/handler` is a native `:db.type/symbol` (a route handler is always a
layout symbol, never literal hiccup) — "a route handler IS a layout" holds at the
value level; it simply stores as a pure symbol rather than the EDN-encoded
mixed-`:or` of `:seon.render/html`.

### 4.9 program graph — `:seon.fn` / `:seon.ns` / `:seon.schema` / `:seon.test`

Blocks, routes, and schedules reference these members BY SYMBOL VALUE (§2.3), so
only the identities and core refs matter here.

| entity | identity attr | valueType | other refs |
|---|---|---|---|
| `:seon.ns` | `:seon.ns/name` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.ns/requires [:vector :keyword]` (cardinality-many), `:seon.ns/source :string` |
| `:seon.fn` | `:seon.fn/sym` `[:string {:seon.db/identity true}]` | string | `:seon.fn/ns :seon.db/ref`, + source/spec/arglists/doc strings |
| `:seon.schema` | `:seon.schema/key` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.schema/ns :seon.db/ref`, source |
| `:seon.test` | `:seon.test/sym` `[:string {:seon.db/identity true}]` | string | `:seon.test/ns :seon.db/ref`, last-passed-at/last-failed-at insts |

Each `:seon.schema` row also carries `:seon.schema/id-attr`,
`:seon.schema/required-attrs [:vector :keyword]`, `:seon.schema/render-fn
:symbol`, and `:seon.schema/render-html-fn :symbol` — the data the presence-based
kind identification (§3) and the render engine read.

**Index everything, show `my.*` in full.** The boot analyzer indexes EVERY
namespace's valid forms into the program graph (`:seon.ns` / `:seon.fn` /
`:seon.schema` / `:seon.test`), so the whole code corpus is queryable as data
(the runtime IS the database). The agent's context renders only `my.*` members in
FULL source — the agent's own code, the thing it edits — while the rest of the
graph is indexed-but-summarized: discoverable by query, not expanded into the
prompt. The render policy (what expands in context) is owned by [[ui]]; the index
is the data fact here.

## 5. Domain schemas — `my.*` (the agent's data)

The `my.*` namespaces carry the agent's actual domain data and are seeded as
worked-examples at creation (the seed registers the schema, defines the verbs,
and installs the rendering block — see [[agent-runtime]] for the bootstrap and
[[toolkit]] for the verbs). They demonstrate the two scoping patterns.

### 5.1 Global vs per-agent = the DATA's agent-ref

Whether data is shared by all agents or private to one is a property of the
ROW's agent-ref, never of the block and never of a stored `:kind`:

- **No agent ref ⇒ global.** `my.kb` rows carry no agent ref, so one knowledge
  base serves every agent. The render fn that surfaces it queries the whole KB.
- **An agent ref ⇒ per-agent.** `my.todo` rows carry `:my.todo/agent`, so each
  agent sees only its own. The render fn scopes by `[?t :my.todo/agent
  agent-eid]`.

Same block registration in both cases; the render fn scopes by WHAT it queries.

### 5.2 my.kb — the global knowledge base (no agent ref)

`my.kb` is the shared, queryable manual every agent reads — the DB's own
self-documentation. Rows carry no agent ref, so the base is global.

```clojure
;; ns my.kb — global: no agent-ref attribute exists on the entity.
(schema/register! :my.kb.shared/id    [:string {:seon.db/identity true}])
(schema/register! :my.kb.shared/title :string)
(schema/register! :my.kb.shared/body  :string)            ; markdown
(schema/register! :my.kb.shared
  [:map {:seon.db/entity true}
   [:my.kb.shared/id    :my.kb.shared/id]
   [:my.kb.shared/title :my.kb.shared/title]
   [:my.kb.shared/body  :my.kb.shared/body]])
```

### 5.3 my.todo — the per-agent plan TREE

Planning IS the todo list: a todo gains a `:my.todo/parent` ref, so the work-list
becomes a plan tree (top = plans/milestones, leaves = actions). There is no
separate plan system. Rows carry `:my.todo/agent`, so the tree is per-agent.

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:my.todo/id` | `[:string {:seon.db/identity true}]` | string / one / identity | |
| `:my.todo/title` | `[:string {:min 1}]` | string / one | |
| `:my.todo/status` | `[:enum :open :done]` | keyword / one | value enum (a flavor, not an entity-kind) |
| `:my.todo/agent` | `:seon.db/ref` | ref / one | → owning agent (the scoping ref) |
| `:my.todo/parent` | `:seon.db/ref` | ref / one | optional; → parent todo (the TREE edge) |
| `:my.todo/created-at` | `:inst` | instant / one | |
| `:my.todo/completed-at` | `:inst` | instant / one | optional |
| `:my.todo/from` | `:seon.db/ref` | ref / one | optional; → who asked |
| `:my.todo/message` | `:seon.db/ref` | ref / one | optional; → the inbound message it tracks |

```clojure
;; ns my.todo — per-agent tree.
(schema/register! :my.todo
  [:map {:seon.db/entity true}
   [:my.todo/id     :my.todo/id]
   [:my.todo/title  :my.todo/title]
   [:my.todo/status :my.todo/status]
   [:my.todo/agent  :my.todo/agent]
   [:my.todo/parent {:optional true} :my.todo/parent]])
```

**Roll-up is DERIVED, never stored.** A parent's progress is a pure query over
its subtree: a parent is `:done` when every descendant is `:done`; partial
progress is the done-fraction of the descendants reached via `:my.todo/_parent`.
Nothing rolls up a stored counter — store the leaf facts, derive the window
(self-healing: complete a child and the parent's progress recomputes).
`:my.todo/parent` is a plain ref (a parent does not own its children's
lifecycle), so the tree is navigated by the reverse `:my.todo/_parent` lookup.

### 5.4 my.agent — `:my.agent/purpose`

`:my.agent/purpose` is a markdown goal string carried on the agent entity — the
agent's stated objective. It is the first per-agent seed worked-example: the seed
registers the schema, defines a `refine` verb, and installs a self-refining block
into the agent's `:seon.agent/ctx` so the agent owns and SEES its own purpose and
can revise it.

```clojure
;; ns my.agent — the purpose attr rides on the agent entity (open map).
(schema/register! :my.agent/purpose :string)              ; a markdown goal string
```

The bootstrap that seeds the schema, the refine fn, and the block is owned by
[[agent-runtime]]; the refine verb is owned by [[toolkit]].

## 6. The error value — base `:seon/error`, specialized only where the shape diverges

Per the never-crash-always-surface principle ([[architecture]]), every failure
is caught at its site and surfaced as a structured `:seon/error` VALUE — never a
process crash, never a discarded exception. This section owns the error VALUE's
shape.

The model is ONE base shape registered at the root namespace, `:seon/error`
(precedent: `:seon/embedding`), and a specialized error keyword is minted ONLY
where the shape genuinely diverges, each referencing the base's shared FIELD
shapes (the shared-shape rule — sharing is at field-shape granularity). There is
NO `:kind`/`:type` discriminator; consumers tell errors apart by WHICH attribute
carries the value (§3, §6.2).

**The shared core — what every error guarantees.** A generic surfacer relies
ONLY on the shared core; variant attrs are bonus.

```clojure
;; Shared FIELD shapes — registered once; the base and every specialization
;; reference these (never re-inline [:string] across error maps).
(schema/register! :seon.error/message :string)   ; the humanized headline
(schema/register! :seon.error/where   :keyword)   ; the site: a block/route/fn name
(schema/register! :seon.error/symbol  :symbol)    ; the offending fn
(schema/register! :seon.error/hint    :string)    ; the actionable fix
(schema/register! :seon.error/data    :map)       ; the structured payload (e.g. the malli explain)

;; THE base error — every error IS one of these unless it genuinely diverges.
(schema/register! :seon/error
  [:map
   [:seon.error/message :seon.error/message]               ; the one required field
   [:seon.error/where   {:optional true} :seon.error/where]
   [:seon.error/symbol  {:optional true} :seon.error/symbol]
   [:seon.error/hint    {:optional true} :seon.error/hint]
   [:seon.error/data    {:optional true} :seon.error/data]])
```

A handler written against the base — `(defn surface [{:seon.error/keys [message
where hint]}] …)` — works on ANY error, base or specialized, because every
specialization references these same field shapes. That is the whole point of one
base + variant attrs over N unrelated error maps.

**Grounded in malli's own error model — humanize is a VIEW, data is the source.**
`malli.core/explain` returns `{:schema :value :errors}`, each error
`{:path :in :schema :value :type}`; `malli.error/humanize` is a pure transform
over that map (the error `:type` keyword is a registry key into messages, not a
branch — malli too has no `:kind`). So `:seon.error/data` keeps the explain map
(the precise `:in` path + offending `:value` + violated `:schema` an AI agent
reasons over), and `:seon.error/message` is the humanized headline. The value
carries BOTH at once — humanize never replaces the data. A schema /
instrumentation rejection therefore needs no new shape: it is a `:seon/error`
whose `:seon.error/data` is the malli explain projection. The error's two renders
split the emphasis: its **ai render** prints the data-as-Clojure (the explain map,
for the prompt); its **html render** leads with the headline and offers a
drill-down into `:seon.error/data` (the human tile).

**The two specializations.** Render, eval, transact, capability, and schema
errors all ARE a plain `:seon/error` (no distinct schema). Only two shapes
genuinely diverge:

```clojure
;; :seon.db/error — the serialized-exception / transact-failure envelope.
;; Diverges by carrying a captured JS exception; references the shared fields.
(schema/register! :seon.db/error
  [:map
   [:seon.error/message   :seon.error/message]
   [:seon.error/data      {:optional true} :seon.error/data]
   [:seon.error/where     {:optional true} :seon.error/where]
   [:seon.error/ex-data   {:optional true} :map]
   [:seon.error/stack     {:optional true} :string]
   [:seon.error/cause     {:optional true} :map]
   [:seon.error/raw       {:optional true} :any]
   [:seon.error/truncated {:optional true} :boolean]])

;; :seon.ai/error — the LLM/provider envelope. Diverges by carrying the
;; provider/transport fields that drive the retry decision.
(schema/register! :seon.ai/error
  [:map
   [:seon.error/message :seon.error/message]            ; shared core
   [:seon.ai/status     {:optional true} :int]           ; HTTP status
   [:seon.ai/timeout?   {:optional true} :boolean]       ; wall-clock abort
   [:seon.ai/transport? {:optional true} :boolean]       ; the retryable flag
   [:seon.ai/raw-body   {:optional true} :string]])      ; raw provider body
```

The LLM adapters return errors as VALUES, never a rejected Promise: a
`:seon.ai/transport?` error gets one bounded retry; a persistent failure closes
the turn `:seon.agent.turn/status :error`, and the render derives a system line
from the turn status so the agent SEES the failure in its transcript. The turn
loop is owned by [[agent-runtime]].

### 6.1 Persistence per carrier

- **Render / transact / capability errors** are TRANSIENT in-memory `:seon/error`
  values — constructed at the failure site, surfaced by a render or a check,
  gone next render (self-healing).
- **The eval error is PERSISTED** as `:seon.eval/error` (the rendered headline) +
  `:seon.eval/error-data` (EDN of the structured payload) on the `:seon.eval`
  row (§4.7) — the durable log the runtime warn-checks query.

`:seon/error`, `:seon.db/error`, and `:seon.ai/error` are malli value shapes used
in-memory; none is itself a transacted entity — only the eval-row string
projections are stored.

### 6.2 How consumers tell errors apart — structurally, never by a field

The carrier attribute IS the identification:

- A render failure arrives under the `:seon.render/error` KEY of the
  `:seon.render/html-response` map — known to be a render failure by the slot it
  came from, no `:kind` needed.
- A transact failure arrives under the `::error` (=`:seon.db/error`) KEY of
  `:seon.db/transact!`'s response.
- An eval failure is read from `:seon.eval/error` on a `:seon.eval` row where
  `:seon.eval/ok?` is false.

## 7. The warnings / checks surface

The warnings block ([[architecture]] glossary) is NOT an error list — it is the
rendered union of every non-clean **check** in the `seon.warn/checks` registry, a
vector of pure `(db) → ::check-response` fns. Each response:

```clojure
(schema/register! :seon.warn/check-response
  [:map
   [:seon.warn/kind      :keyword]        ; the source-check id (a value enum — §3)
   [:seon.warn/affected  [:vector [:map [:seon.warn/sym :string]
                                        [:seon.warn/where? :string]]]]
   [:seon.warn/explain   :string]
   [:seon.warn/example   :string]
   [:seon.warn/urgent??  :boolean]
   [:seon.warn/dev-only?? :boolean]])
```

`:seon.warn/affected` is EMPTY when clean ⇒ the check renders nothing ⇒ the
surface vanishes when the problem is fixed (self-healing; never stored; a pure fn
of the db). A check that THROWS becomes its own `:warn-check-error` cluster so one
broken check can't blank the block. **Errors are just a handful of checks** among
many — the runtime-error checks read the persisted eval-log error datoms
(`:seon.eval/ok? false` + `:seon.eval/error`); a render-health check aggregates
the transient render `:seon/error` values; the rest surface non-errors (perf,
lint, test status, message routing, unresolved tiles).

**Failure-site → surface (by carrier).** Every site catches, names a carrier, and
reaches at least one agent-visible AND one human-visible surface.

| failure site | carrier | agent-visible | human-visible |
|---|---|---|---|
| **render** (block ai/html throws, missing symbol, SCI deadline) | transient `:seon/error` under the `:seon.render/error` key | warnings block (render-health check) | the in-place error tile (siblings untouched) |
| **eval** (a form throws) | PERSISTED `:seon.eval/error` + `:seon.eval/error-data` | the eval's render in the transcript + the failed-eval checks | the eval tile / transcript line |
| **transact** (a tx is rejected) | transient `:seon.db/error` under `::error` | the eval that called `transact!` records it | the eval tile |
| **capability denial** (fs / `/call` refuses) | the denial string in the eval result | the fs-denied check | the eval tile |
| **schema / instrumentation rejection** | `:seon.error/data` = the malli explain, under `:seon.eval/error-data` | the failed-eval check renders the structured error | the eval tile |
| **LLM / provider error** | `:seon.ai/error` → turn `:seon.agent.turn/status :error` | the transcript system line derived from the turn status | the same transcript line |
| **throwing warn-check** | synthetic `:warn-check-error` cluster | the warnings block (that check degrades loudly) | the warnings tile |
| **throwing layout / route handler** | transient `:seon/error` (same as render) | warnings block if the agent owns the route | a human error page / error tile |
| **runaway / hung eval** | run `:seon.agent.run/closed-reason :deadline-exceeded` | derived run-status surfaces "deadline exceeded" | the run-status tile |

Two invariants: no agent code ever touches an SSE connection or throws into the
event loop (a failure becomes a value the UI host renders — [[ui]]), and every
error reaches both the agent (who can fix it) and the human (who is watching),
from one source, because every surface is a derived fn of the same db plus
transient render values.

## Detail docs

- [[architecture]] — the glossary (locked vocabulary), the cross-cutting
  principles (DB-as-bus, derive-everything, never-crash-always-surface,
  roles-as-capabilities, seed-copy-not-merge, code-as-data), deployment topology.
- [[agent-runtime]] — loop / run / turn / FSM / derived-state, creation-as-idle,
  bootstrap-as-seeded-forms, orchestrator-root lifecycle, the `:core`-origin
  quietness, isolation tiers.
- [[ui]] — block / render / tile / slot / layout, world / root-world / app,
  reitit routing + the capability gate, the SSE / `!last-tree` channel, the
  seed-copy + variadic `install!`/`remove!` override model.
- [[toolkit]] — the `my.*` verb catalog (the agent's action surface over these
  schemas).
- [[roadmap]] — current code state, the gap, and the dependency-ordered
  migration to this target.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset
  for the bridge (db is a value, only values cross the wire, CAS-as-assertion).
