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
the loop/lifecycle that mutates these rows, and at [[toolkit]] for the functions an
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
agent whose view is the `/` overview and who holds the lifecycle grant (see
[[agent-runtime]]). The agent's **state is never stored** — it is derived
(`seon.derive/derive-state`) from its primitives. The **`my.*` domain schemas**
carry the agent's actual data: `my.kb` (a global knowledge base — rows carry no
agent ref), `my.plan` (a per-agent plan TREE — rows carry `:my.plan/agent` and
`:my.plan/parent`), and `my.agent` (the agent's `:my.agent/purpose`). **Global
vs per-agent is a property of the DATA's agent-ref**, never of the block and
never of a stored `:kind`. The **error value** is ONE base shape, `:seon/error`,
specialized only where the shape genuinely diverges; failures never crash, they
surface.

## 2. Three relationship kinds

Seon expresses relationships three ways — plus a fourth every datom carries for
free (§2.4). Conflating them is the single biggest data-model error, so each is
pinned against the bridge (`seon.db.internal`) and the vendored datahike source
([[datahike-primer]]).

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
`:my.plan/agent`, `:my.plan/parent`. Use a plain ref when the entity does NOT own
the referent's lifecycle — a fn does not own its ns; a turn does not own its run;
a plan step does not own its parent.

A **component ref vector** `[:vector {:seon.db/component true} :seon.db/ref]` is
an OWNED-children list: `{:seon.db/component true}` → `:db/isComponent true` and
the vector wrapper → `:db.cardinality/many`. Datahike requires a component attr
to also be `:db.type/ref`, which `:seon.db/ref` provides. **Component = cascade
retract:** on `[:db.fn/retractEntity parent]`, datahike's `retract-components`
(in `db/transaction.cljc`) maps every component-attr datom to a child
`retractEntity`, so retracting the agent retracts every owned block and schedule,
and retracting a turn retracts its evals. The owned-children attrs:
`:seon.agent/ctx`, `:seon.agent/schedules`, `:seon.agent.turn/evals`,
`:seon.render/children`. **Ground in** `transaction.cljc:730` (`retract-components`)
+ our bridge `src/seon/db/internal.cljs:344-350` (the component/identity facet) —
[[library-grounding]]. (Contrast `:my.plan/parent`, a PLAIN ref: no cascade.)

To REPLACE a whole component vector (reconcile, `install!`/`remove!`) so its owned
children match a desired set, retract the attribute with **`:db.fn/retractAttribute`**
— only `retractAttribute` and `retractEntity` run `retract-components`; a plain
`:db/retract` on the attribute severs the parent→child edges but leaves the child
entities ORPHANED (`transaction.cljc:959-977`).

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
| `:my.plan/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | plan step key |

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
  `:seon.render/html` (references `:seon.render.canvas/content`,
  `[:or :symbol ::hiccup]`).

The render path resolves these through ONE engine: `ai-render` / `html-render`
call `eval/lookup-value` on a qualified symbol, falling through to a
pretty-printer on a miss. Agent-authored symbols run SCI-bounded; core symbols
run compiled. The render engine itself is owned by [[ui]].

### 2.4 tx provenance — every datom names its transaction (free, auto-stamped)

The connection nobody models but everybody gets: a datom's 4th field is its
**transaction id**, and the transaction is a real entity carrying real datoms.
Datahike reifies `:tx-meta` onto the tx entity
(`reference-code/datahike/src/datahike/db/transaction.cljc:802`
`flush-tx-meta`) and auto-stamps a monotonic `:db/txInstant`. Seon's
`transact!` auto-merges the active `with-agent`/`with-tx-context` scope into
`:tx-meta` (`src/seon/db/internal.cljs` `merge-tx-context-into-opts`; the seven
attrs: `:seon.db/agent-id`, `session-id`, `turn-id`, `eval-id`, `origin`,
`replay?`, `resume-marker?`), and the stamps survive the wire to the JVM
writer. `:seon.db/origin` is DERIVED at that boundary
(`seon.db.internal/derive-origin`) — callers never pass it: an agent scope
stamps `:agent` (a tx-context claim of a non-managed origin like `:system` /
`:test-run` / `:replay` is trusted), and the managed origins
`:core-seed`/`:config` are only reachable from an UNSCOPED `with-tx-context`,
so managed-core provenance cannot be forged from inside an agent scope.

Consequence for modeling: WHO/WHEN-wrote-this is a **join**
(`[?e attr _ ?tx] [?tx :seon.db/turn-id ?turn]`), never a domain attribute — a
`created-by`/`created-at`/`source-turn` attr duplicates the tx record. The one
exception is a PRE-event snapshot coordinate: a fact about a db value observed
*before* the entity's own tx (`:seon.agent.turn/rendered-as-of` — other agents'
txs interleave on the shared conn, so the turn's creation-tx is not that
coordinate). Those are genuinely underivable and ARE stored as domain attrs.
Worked recipe: the `datahike` skill, "Transaction metadata".

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
(m/parse [:orn [:my.kb.shared :my.kb.shared] [:my.plan :my.plan]] a-value)
;; => #malli.core.Tag{:key :my.kb.shared, :value {…}}
```

**Ground in** malli `core.cljc:164` (`Tag`) + `:1073-1078` (the `:orn` parser).
The parser returns `(reduced (tag k %))` on the FIRST branch that validates — so
order `:orn` branches **most-specific-first** (most required attrs first), or a
loose branch matches prematurely. See [[library-grounding]].

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
    `:seon.agent.run/closed-reason`, `:my.plan/status` — value enums that flavor
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

**`register!` ≠ bridge-to-datahike.** `schema/register!` is in-memory (the malli
registry) only; the datahike bridge runs lazily at transact time, on the attrs
that actually appear in a tx (`ensure-datahike-attrs!`, `internal.cljs:1359/1370`).
So an IN-MEMORY-ONLY value shape — the `:seon/error` family (§6), the derived
`:seon.warn/check-response` (§7), `:seon.derive/status` — registers fine even
though it is a `:map` the bridge cannot store: it is never transacted as an entity
attr, so it never hits the bridge. Only attrs you `transact!` must be
bridge-storable. **Ground in** `src/seon/db/internal.cljs:286-360,1211` —
[[library-grounding]].

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
| `:seon.render/html` | `:seon.render.canvas/content` | string (EDN) / one | optional; per-entity tile-render override |

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

**Model config is DERIVED, never stored.** The config an agent runs under is
a pure function of the db: `seon.ai/resolved-config` takes
`{:seon.db/db db :seon.agent/id id}` and resolves each of the five keys
(provider/model/temperature/max-tokens/thinking) through the ONE chain — the
agent's own override datom → the global `{:seon.ai/id "config"}` row →
`seon.ai/shipped-defaults` — returning the `:seon.ai/resolved-config` value
PLUS `:seon.ai/provenance` (per-key `:agent-override`/`:config-row`/
`:default`, derived by re-walking the chain, not stored). No per-turn
config datoms exist; datahike is bitemporal, so the config any PAST turn ran
under is the same fn over that turn's frozen basis:
`(ai/resolved-config {:seon.db/db (db/as-of db (:seon.agent.turn/rendered-as-of turn)) :seon.agent/id id})`.
The `POST /agents/run` door computes `model_config` this way at response
time; the bench ledger consumes that runtime-derived value.

**Per-agent config = intent, one chain.** The agent entity carries optional
`:seon.ai/agent-provider`/`-model`/`-temperature`/`-max-tokens`/`-thinking`
override attrs (absent/`:inherit` = inherit); `seon.ai/current` lays the
CALLING agent's overrides over the global row per call: **explicit call opts
→ the agent's own attrs → the cluster config row → shipped defaults**. This
resolution shape is the general pattern for every agent-related config
family (skills, render caps, ctx blocks, capability sets): agent attrs are
the override point, one chain, absent = inherit — new families add an attr
pair to the resolver's data map, never a second mechanism.

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
human message is the first trigger of a run and auto-mints a `my.plan` (§5.3); a
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
schema lives here; reitit, `db->routes`, the capability gate, and the root-agent-view
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
mixed-`:or` of `:seon.render/html`. **Ground in** reitit `trie.cljc:60`
(`split-path` accepts both `{id}` and `:id`) + `reitit-ring/.../ring.cljc:14-16`
(`http-methods` + `Endpoint`): one `{pattern, method, handler, middleware}` row
maps to `["/agent/{id}" {:get {:handler <sym> :middleware […]}}]`. `db->routes`
(UI lane) groups rows by `pattern`, nests by `method`. See [[library-grounding]].

### 4.9 program graph — `:seon.fn` / `:seon.ns` / `:seon.schema` / `:seon.test`

Blocks, routes, and schedules reference these members BY SYMBOL VALUE (§2.3), so
only the identities and core refs matter here.

| entity | identity attr | valueType | other refs |
|---|---|---|---|
| `:seon.ns` | `:seon.ns/name` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.ns/requires [:vector :keyword]` (cardinality-many), `:seon.ns/require-edges` (component rows `{:seon.ns.require/target :keyword, alias :symbol, refers [:set :symbol]}` — the reified `:as`/`:refer` facts the SCI cage env is built from AND boot replay's synthesized `(ns …)` head for a sourceless member-bearing row — the agent HOME ns, whose requires are wired at runtime; teed from the analyzer, boot-indexed for full-source nses), `:seon.ns/source :string` |
| `:seon.fn` | `:seon.fn/sym` `[:string {:seon.db/identity true}]` | string | `:seon.fn/ns :seon.db/ref`, + source/spec/arglists/doc strings, `:seon.fn/read-attrs [:vector :qualified-keyword]` (the declared read-set — keyword literals walked off the read form at tee time; the canvas derivation's watch set) |
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
worked-examples at creation (the seed registers the schema, defines the functions,
and installs the rendering block — see [[agent-runtime]] for the bootstrap and
[[toolkit]] for the functions). They demonstrate the two scoping patterns.

### 5.1 Global vs per-agent = the DATA's agent-ref

Whether data is shared by all agents or private to one is a property of the
ROW's agent-ref, never of the block and never of a stored `:kind`:

- **No agent ref ⇒ global.** `my.kb` rows carry no agent ref, so one knowledge
  base serves every agent. The render fn that surfaces it queries the whole KB.
- **An agent ref ⇒ per-agent.** `my.plan` rows carry `:my.plan/agent`, so each
  agent sees only its own. The render fn scopes by `[?t :my.plan/agent
  agent-eid]`.

Same block registration in both cases; the render fn scopes by WHAT it queries.

**The ref direction is settled: per-agent data points DATA→AGENT** (the row
carries the scoping ref, e.g. `:my.plan/agent`; there is no `:seon.agent/plan`).
Grounds, in force order:

1. **Schema authority.** The scoping ref registers in the OWNING `my.*` ns — the
   ns IS the schema authority. An agent-side attr would force the core
   `seon.agent` schema to learn every `my.*` domain (inverted dependency); with
   data→agent, adding a per-agent domain = one new ns, the core stays closed.
2. **Write locality.** Many rows → one agent. An agent-side
   cardinality-many vector would rewrite the HOT agent entity (the one the
   run/turn FSM fences on) on every domain write; data→agent writes only the new
   rows — `my.plan/plan!` stays one flat tempid tx that never touches the agent.
3. **Query parity.** One VAET-indexed ref reads both ways: forward
   `[?t :my.plan/agent ?a]`, reverse pull `:my.plan/_agent`. An owner-side
   vector adds no query power.
4. **Scope-by-signature** ([[context]]) falls out: the function that declares
   `:seon.agent/id` stamps and filters the data-side ref.

**Agent-retract semantics (no cascade, by design):** agents are terminated
(`:seon.agent/terminated-at`), not retracted. If an agent entity IS retracted,
datahike's `retract-entity` (`transaction.cljc:897`) also retracts every
incoming v-datom — the scoping edges vanish and the rows are ORPHANED: their own
datoms survive, they match no agent-scoped read, and history keeps everything
(`db/as-of` recovers). Component cascade is reserved for OWNED bounded sets
(`:seon.agent/ctx`, `:seon.agent/schedules`), never for open-ended domain data.

### 5.2 my.kb — the global knowledge base (no agent ref)

`my.kb` is the shared, queryable manual every agent reads — the DB's own
self-documentation. No attribute in the ns carries an agent ref, so the base is
global: every fn signature omits `:seon.agent/id` (scope-by-signature, read the
arglist). The worked shape is claim + graded provenance:

```clojure
;; ns my.kb — global: no agent-ref attribute exists on any row.
(schema/register! ::source-path :string)     ; file the fact was read from
(schema/register! ::source-line :int)        ; 1-based first line of a range
(schema/register! ::verified-at :inst)
(schema/register! ::confidence  [:enum :verified :inferred])
(schema/register! ::claim [:string {:seon.db/identity true}])  ; upsert key
```

(`my.kb.shared` — the append-only shared-instructions singleton — is the one
declared entity kind there: `::shared` with an `::instructions` component
vector.)

### 5.3 my.plan — the per-agent planning graph

**Planning, not a todo list.** A plan is a per-agent **dependency graph** built
for long-range work that survives interruption: nested steps (a `:my.plan/parent`
tree) with explicit **dependency edges** (`:my.plan/needs`), a durable goal
narrative, a falsifiable expected outcome per step, and — the load-bearing part —
a render that always makes clear **where the agent is**. There is no separate
todo system; this IS the work model. Rows carry `:my.plan/agent`, so the graph
is per-agent. (Design driven by the 2026-07-02 long-horizon drive: the old
open-items-only todo tree was "a queue, not a journal" — it went silent on
success exactly at resume time, offered no position anchor, and let a 3× blind
retry through. Each attribute below targets one of those measured failures.)

| attribute | malli | notes |
|---|---|---|
| `:my.plan/id` | `[:string {:seon.db/identity true}]` | step/plan key |
| `:my.plan/title` | `[:string {:min 1}]` | the step, one line |
| `:my.plan/status` | `[:enum :open :active :done :blocked]` | value enum; **`:active` = where the agent IS** |
| `:my.plan/agent` | `:seon.db/ref` | → owning agent (the scoping ref) |
| `:my.plan/parent` | `:seon.db/ref` | optional; → parent step (the nesting edge) |
| `:my.plan/needs` | `[:vector :seon.db/ref]` | optional; → prerequisite steps (dependency edges — a step is READY only when all `needs` are `:done`) |
| `:my.plan/goal` | `[:string]` | optional, root-level; the WHY narrative that outlives the transcript window |
| `:my.plan/expect` | `[:string]` | optional; the falsifiable expected outcome — "how I'd know this step failed" — so the render prompts VERIFY-before-`done!` (stops blind re-issue) |
| `:my.plan/pace` | `[:enum :one-shot :multi-session]` | optional, root-level; explicit scope so "spans sessions" can't collapse to a sprint |
| `:my.plan/created-at` | `:inst` | |
| `:my.plan/completed-at` | `:inst` | optional; drives the recently-completed window |
| `:my.plan/from` | `:seon.db/ref` | optional; → who asked |
| `:my.plan/message` | `:seon.db/ref` | optional; → the inbound message it tracks |

```clojure
;; ns my.plan — per-agent dependency graph. The entity kind is
;; :my.plan/step; required = what step!/plan! write unconditionally.
(schema/register! ::step
  [:map {:seon.db/entity true}
   [::id           ::id]
   [::title        ::title]
   [::status       ::status]
   [::agent        ::agent]          ; the DATA→AGENT scoping ref
   [::created-at   ::created-at]
   [::description  {:optional true} ::description]
   [::goal         {:optional true} ::goal]
   [::expect       {:optional true} ::expect]
   [::pace         {:optional true} ::pace]
   [::from         {:optional true} ::from]
   [::message      {:optional true} ::message]
   [::parent       {:optional true} ::parent]
   [::needs        {:optional true} ::needs]
   [::completed-at {:optional true} ::completed-at]])
```

**Everything about progress and position is DERIVED — nothing rolls up a stored
counter.** A parent is `:done` when every descendant is `:done`; a step is
**ready** when every `:my.plan/needs` target is `:done` (blocked otherwise); the
**position anchor** is the `:active` step (or the first ready leaf) plus its
ancestor chain to the goal — "executing step N of goal G, M of K done". Complete
a step and the whole picture recomputes (self-healing). `:my.plan/parent` and
`:my.plan/needs` are plain refs (a parent/prerequisite does not own the other's
lifecycle); the graph is walked by reverse lookups (`:my.plan/_parent`,
`:my.plan/_needs`).

**The render is WINDOWED — never mostly-completed history.** The plan block
([[context]] band 1) leads with the position anchor, then shows the open frontier
(the ready + active steps and their immediate context), then a **small
recently-completed tail** (the last few `:my.plan/completed-at` steps — proof of
progress and resume-grounding), and DROPS the long completed interior (it stays
in the DB, queryable, but out of the prompt). So a plan a thousand steps deep
renders at constant size, the agent always sees where it is and what's next, and
a resumed agent re-grounds from plan-state, not transcript archaeology. Evidence:
`research/long-horizon-plan-drive-2026-07-02.md`.

### 5.4 my.agent — `:my.agent/purpose`

`:my.agent/purpose` is a markdown goal string carried on the agent entity — the
agent's stated objective. It is the first per-agent seed worked-example: the seed
registers the schema, defines a `refine` function, and installs a self-refining block
into the agent's `:seon.agent/ctx` so the agent owns and SEES its own purpose and
can revise it.

```clojure
;; ns my.agent — the purpose attr rides on the agent entity (open map).
(schema/register! :my.agent/purpose :string)              ; a markdown goal string
```

The bootstrap that seeds the schema, the refine fn, and the block is owned by
[[agent-runtime]]; the refine function is owned by [[toolkit]].

### 5.5 knowledge-on-demand — cards, state-gated teaching, pull references

An agent needs domain knowledge WHERE it is working, without a curated catalog.
The target surface has three pieces, none of them a loadable-skill row:

- **Compact cards** — a home-required namespace renders as function heads +
  docstring line 1 + schema (§4.2, the `:namespaces` block). The card IS the
  discoverable expertise: the agent reads the fn contract and calls it. (Proven
  at the `repl`/`namespaces` milestones — cards suffice for correct first calls.)
- **State-gated block teaching** — each block carries its OWN teaching, rendered
  exactly when its state holds (colocation; the reactive rule). Knowledge about a
  thing lives with the block that surfaces that thing, not in a separate skill.
- **Pull references** — deeper worked manuals (`my.kb`) are PULLED on demand — the
  db is self-describing; the agent reads them when it needs them, never pushed.

> **DEPRECATED — the loadable-skills SYSTEM is retiring.** The former `my.skills`
> catalog + loadable bodies (`:my.skills/name`/`description`/`body`, the
> `SEON_SKILLS_DIR` corpus scan, `load`/`unload` = `install!`/`remove!` of a
> `:skill/<name>` block) still exists in code and its render fns carry
> `DEPRECATED` docstrings. Its job dissolves into the three pieces above — see
> [[context-rebuild]] ("The idea inventory" + "Deliberately NOT blocks"). Do not
> build against it as the target. The `install!`/`remove!` block mechanism it
> rode on is unaffected — that stays the sole seed/override path ([[ui]]).

### 5.6 config manifest — `:seon.config/*` resolves at boot into the DB singleton

The startup-load customization seam is ONE consolidated manifest
(`config/system.edn`, path overridable by `SEON_CONFIG`), read by `seon.config`.
It is a SEED FILE, not a runtime dependency: at boot `resolve-config-singleton`
resolves EVERY knob to its effective value (env → manifest → default) and
`state/reconcile!` (scope `#{:config}`) transacts them as ONE `:seon.config`
singleton entity (`[:seon.config/id "cluster"]`) — every dial a datom. **From
there every RUNTIME read is a db query** via `config/config-view` (the accessors
keep their names + arities; a db-value-keyed memo collapses a turn's reads to
one entity pull), falling back to the boot manifest resolve only for the pre-conn
sliver. Absent or `{}` ⇒ byte-identical to a no-config boot; every section key is
`{:optional true}`, so the empty manifest validates; an UNKNOWN key fails LOUD at
validation (a config typo is a crash, never a silent ignore). The full boot/read
mechanics — the require-direction db→error→config, `seon.db` injecting the
reader, replay-visible + live-tunable dials — live in [[context]]
§"Config-through-DB"; this section is the schema of record.

The real manifest carries a dial per config concern (not just seeds) — a new
concern = ONE `:seon.config/<section>` schema + one resolver fn + one key here:

```clojure
;; ns seon.config — the registry of known sections (config.cljs)
(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills          {:optional true} :seon.config/skills-spec]
   [:seon.config/repl-mode        {:optional true} :seon.config/repl-mode]         ; :batch | :stream (per-model default when absent)
   [:seon.config/namespaces       {:optional true} :seon.config/namespaces-spec]   ; which nses render full
   [:seon.config/routes           {:optional true} [:vector :seon.config/route-spec]]
   [:seon.config/render           {:optional true} :seon.config/render]            ; the render caps (store-edn/eval/message/value…)
   [:seon.config/system-text      {:optional true} :seon.config/system-text]       ; the system-prompt string → the datom
   [:seon.config/on-core-error    {:optional true} :seon.config/on-core-error]     ; :crash | :gate | :log (the ONE fault dial)
   [:seon.config/web              {:optional true} :seon.config/web-spec]
   [:seon.config/repair           {:optional true} :seon.config/repair]
   [:seon.config/spawn-depth-cap  {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/watchdog         {:optional true} :seon.config/watchdog]
   [:seon.config/schedule-breaker {:optional true} :seon.config/schedule-breaker]
   [:seon.config/agent-context    {:optional true} :seon.config/agent-context]     ; the per-agent block tree + dials
   [:seon.config/root-context     {:optional true} :seon.config/root-context]])    ; a sparse override merged for root
```

Each key resolves onto the flat `:seon.config/singleton` entity (`config.cljs`,
every knob `{:optional true}`, `:seon.config/id` the only required key), so the
render caps, `repl-mode`, `system-text`, `on-core-error`, and the multi-agent
dials are all datoms an agent's turn reads through `config-view`. `resolve-routes`
+ `resolve-skill-rows` produce the DECLARATIVE desired set reconciled at boot
(origin `:config`, §4.8 / the seeding model in [[agent-runtime]]); the manifest is
the single env surface (its `#env` tags let a var override a manifest default).
`SEON_PROFILE` / aero `#profile` is INERT — variants are separate manifest FILES
selected by `SEON_CONFIG`, never in-file profiles.

**Per-test / per-cluster recipe** — name your own manifest, zero src edits:

- `SEON_CONFIG=config/test.edn bin/test-cljs` — a test run loads its own
  loadout / routes / render caps (pins `:batch`).
- `SEON_CONFIG=config/minimal.edn bin/seon restart pod` — the minimal-tree
  variant (`#include`s `system.edn`, inherits the graduated system-text).
- `bin/acme` exports `SEON_CONFIG=config/acme.edn` — the isolated cluster
  curates independently.

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
`malli.core/explain` returns `{:schema :value :errors}` (and **`nil` on a valid
value** — construct the error only on a non-nil explanation), each error
`{:path :in :schema :value :type}`; `malli.error/humanize` is a pure transform
over that map (the error `:type` keyword is a registry key into messages, not a
branch — malli too has no `:kind`). **Ground in** malli `core.cljc:2660`
(`explain`), `error.cljc:374` (`humanize`), `impl/util.cljc:19` (`-error`) —
[[library-grounding]]. So `:seon.error/data` keeps the explain map
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
- [[ui]] — block / render / tile / slot / layout, view / root-agent-view / app,
  reitit routing + the capability gate, the gzip-morph SSE channel, the
  seed-copy + variadic `install!`/`remove!` override model.
- [[toolkit]] — the `my.*` function catalog (the agent's action surface over these
  schemas).
- [[roadmap]] — current code state, the gap, and the dependency-ordered
  migration to this target.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset
  for the bridge (db is a value, only values cross the wire, CAS-as-assertion).
