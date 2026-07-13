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
`:seon.agent.run/cause`, `:seon.agent.turn/run`,
`:seon.agent.turn/cause-message`, `:seon.fn/ns`, `:seon.schema/ns`,
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
and our bridge `src/seon/db/internal.cljs:344-350` (the component/identity facet) —
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
this is how "redefine = upsert" works for program definitions and how exact
reconciliation applies known identities. Generated creation deliberately
guards that path: inside the serialized writer, `seon.db.id/allocate!` checks
the exact old database and incoming transaction for every identity that could
resolve the candidate entity, injects an allocator-owned tempid, and returns
the final eid only from Datahike's committed transaction report. A generated
collision therefore retries without mutating an existing agent/entity.

The broad protocol schema `:seon.db/id` accepts reserved root, readable-word,
and compact syntax for generic envelopes. Persisted identity attributes use
narrower named value schemas: `:seon.db.id/agent-value` accepts only `root` or
readable-word syntax; `:seon.db.id/compact-value` accepts only compact syntax.
Generator metadata selects output. There is no compatibility grammar arm or migration
path; current test databases reset at this cutover.

| identity attr | malli shape | datahike valueType | role |
|---|---|---|---|
| `:seon.agent/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/human-readable} :seon.db.id/agent-value]` | `:db.type/string` | readable agent natural key; `root` is reserved, not generated |
| `:seon.agent.run/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact **fencing token** |
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact turn key |
| `:seon.agent.message/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact message key |
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact schedule key |
| `:seon.eval/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact eval key |
| `:seon.runtime.recovery/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact crash-recovery anchor key |
| `:seon.web.session/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact browser-tab session key allocated by the writer |
| `:seon.db.restore/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact completed-restore fact key |
| `:seon.user/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | the one human |
| `:seon.db.process/id` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | boot/config/REPL provenance path |
| `:seon.fn/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | fn qualified-sym key |
| `:seon.test/sym` | `[:string {:seon.db/identity true}]` | `:db.type/string` | test key |
| `:seon.ns/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | ns key |
| `:seon.schema/key` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | schema-attr key |
| `:seon.route/name` | `[:keyword {:seon.db/identity true}]` | `:db.type/keyword` | reverse-routing key |
| `:my.kb.shared/id` | `[:string {:seon.db/identity true}]` | `:db.type/string` | global KB entry key |
| `:my.plan/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | `:db.type/string` | compact plan step key |

`:seon.db/id` is the broad protocol union; generated identity
attributes reference one of the two narrow value schemas above.
`seon.db.id/allocate!` is the only allocator. The compiled Malli property is
startup/declaration input: the schema index persists it as an ordinary
`:seon.db.id/generator` datom on the corresponding `:seon.schema/key` entity.
The serialized writer queries those database facts; no request carries a
client-authored list of managed attributes. Only `:seon.agent/id` may hold the
human-readable policy; all other generated persistent identities hold compact.

Allocation privately adapts the platform package, then gives the caller's pure
builder a candidate map for the complete domain transaction. The writer checks
the candidate against the old database, injects allocator-owned tempids, runs
Datahike's normal transaction function, and validates the complete
**uncommitted TxReport** before returning it to Datahike's commit queue. That
postflight sees assertions created by nested maps, transaction functions, and
transaction metadata as well as literal input. A current-grammar value
must match the allocator manifest; an identical attr/value already present in
`db-before` remains a normal exact upsert. The explicit reserved root remains
readable without pretending to be generated. Policy changes full-audit the
resulting managed population, so a
removal with live values, invalid grammar, or cross-attribute value collision
cannot commit.

A matching structured Datahike `:transact/unique` or `:transact/upsert`
conflict rebuilds and retries the whole candidate-dependent transaction within
the fixed bound; unrelated failures do not retry. Final eids come only from the
accepted report. Local connections name the durable
`:seon.db.id.writer/serialized` backend; its runtime multimethod delegates to
Datahike's self writer with the private transaction operation in memory. No
function enters the persisted database config. The JVM database registry uses that
same backend, so local and remote writes share one policy boundary without a
second lock or transaction path. This supplies database-wide generated-value
uniqueness without a global identity entity; lookup refs remain
attribute-qualified. The protocol request UUID is a separate idempotency
receipt, not a domain identity.

That receipt is one transaction-metadata identity plus its content hash and
protocol version. Caller tempids that must survive a lost reply receive
same-transaction marker refs. These are durable recovery facts, but they are
private to `seon.db.protocol`: public transaction reports, replay events,
changed-attribute routing, and domain datom counts omit every reserved receipt
attribute. The request ID travels separately on the protocol event solely to
correlate a replica's own commit with its response.

`:seon.agent.ctx/name` is NOT an identity (see §4.2): it is a plain `:keyword`, a
per-agent upsert key, not a global identity.

### 2.3 symbol-as-value — late binding to the program graph (NOT a ref)

A render fn, a route handler, and a schedule fn are stored as **symbol values**,
resolved late at use time via `seon.eval/lookup-value`. They are NOT datahike
refs — there is no entity to point at; the symbol names a var in the running
program, which the program-graph entities (`:seon.fn`, `:seon.ns`) also describe,
but the binding is by NAME at call time, not by eid at write time. This is what
lets an agent transact `:seon.render/html 'my.agent.abc/status-surface` before (or
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

### 2.4 tx provenance — datom → transaction → user/process

The connection nobody models but everybody gets: a datom's 4th field is its
**transaction id**, and the transaction is a real entity carrying real datoms.
Datahike reifies `:tx-meta` onto the tx entity
(`reference-code/datahike/src/datahike/db/transaction.cljc:802`
`flush-tx-meta`) and auto-stamps a monotonic `:db/txInstant`. Seon adds two refs
to every normal post-genesis production transaction:

| transaction attribute | target | meaning |
|---|---|---|
| `:seon.db/user` | existing `:seon.agent/id` or `:seon.user/id` entity | root, human, or agent whose operation submitted the facts |
| `:seon.db/process` | `:seon.db.process/id` entity | boot, config, or REPL execution path |

Root is the existing root agent, not a second database-user row. Boot/config
are processes running as root; an agent's eval is the agent user through REPL.
One explicit un-attributed genesis transaction installs these ref attributes,
root, and the three process identities before normal provenance can refer to
them.

An asynchronous ownership boundary must select these refs again rather than
inherit the notifier's fiber. In particular, inbound-message wake, renew, and
re-drive callbacks explicitly select the receiving agent plus REPL after their
timer hop. Explicit transaction context intentionally outranks the ambient
agent scope, so a callback that restored only the latter could otherwise record
the sender as the user of the receiver's work.

The durable provenance primitive is therefore a join from datom to transaction
to user/process/time. Provenance belongs on the transaction entity, not as an
owner, creator, kind, or status attribute copied onto domain entities. It does
not grant authorization and it does not determine config/reconciliation
authority.

Consequence for modeling: who/when/writing-path is a **join** through the
transaction, never a domain attribute — a `created-by`/`created-at`/`source-turn`
attribute duplicates the transaction/domain record. Turn and eval remain
ordinary linked domain entities and runtime scopes; they are not copied onto
every transaction and do not promise complete effect replay. The one
exception is a PRE-event snapshot coordinate: a fact about a db value observed
*before* the entity's own tx (the turn's rendered
database/branch/commit/t—other
agents' txs interleave on the shared conn, so the turn's creation-tx is not that
coordinate). Those are genuinely underivable and ARE stored as domain attrs.
Worked recipe: the `datahike` skill, "Transaction metadata".

### 2.5 one database coordinate

Every registry, protocol, feed, UI, lifecycle, turn, and error boundary uses one
map schema:

```clojure
{:seon.db.coordinate/database-id #uuid "..."
 :seon.db.coordinate/branch      :db
 :seon.db.coordinate/commit-id   #uuid "..."
 :seon.db.coordinate/t           536870914}
```

`{database-id, branch}` is the stable attachment; `commit-id` and `t` are an
all-or-none resolved point. Commit id is canonical and t is a display/range
aid. A logical database name travels beside this map as routing data and is never
treated as storage identity. A convenience `{branch, t}` selector must resolve
to exactly one commit in that branch's retained ancestry; zero/multiple matches
are typed errors. Cache keys and durable bookmarks contain the resolved commit,
never bare t.

## 3. Identifying an entity's kind — presence, not a stored field

Datahike has no entity type or class: an entity IS its attributes; schema is
per-attribute; entities are enumerated by walking AEVT *for an attribute*. So
Seon never stores a field whose job is to select which schema a row obeys. This
is the dual of "how refs work" and the rule the whole schema honors.

**The rule.** An entity's kind is the set of attributes it carries — primarily
its identity attr. You IDENTIFY kind two ways:

- **Stored rows** → the required-attr subset test against the runtime catalog
  derived once from the complete canonical Malli registry: the most-specific
  shape (the most required attrs, alphabetical tie-break) whose required attrs
  are all present on the entity. No `:seon.schema/required-attrs` or
  `:seon.schema/id-attr` projection is persisted.
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
    content block `{:type "text"}`, a rewrite-clj node `:type`, and datahike's
    own `:db.secondary/type`).

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
| `:seon.agent/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/human-readable} :seon.db.id/agent-value]` | string / one / identity | readable agent identity; reserved `root` is reconciled |
| `:seon.agent/parent` | `:seon.db/ref` | ref / one | optional; → the agent that started this one (absent on the root agent — the base case) |
| `:seon.agent/run` | `:seon.db/ref` | ref / one | optional; → current run; the fencing pointer + derived-state spine |
| `:seon.agent/terminated-at` | `:inst` | instant / one | optional; presence ⇒ derived `:terminated` |
| `:seon.agent/default-turn-limit` | `:int` | long / one | optional; seeds a run's work bound |
| `:seon.agent/default-deadline-ms` | `:int` | long / one | optional; seeds a run's clock bound |
| `:seon.agent.runtime/wake?` | `:boolean` | boolean / one | optional; false suppresses the process-local inbound listener while preserving manual hosting; absence means true |
| `:seon.eval/home-requires` | serialized require-spec vector | string (EDN) / one | optional; exact per-agent home namespace declaration selected at birth and read during runtime reconstruction |
| `:seon.agent/schedules` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned cron maps (cascade-retract) |
| `:seon.agent/ctx` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned **blocks** (cascade-retract), seeded at creation, sorted by `:seon.agent.ctx/priority` at render |
| `:seon.render/ai` | `:seon.render/ai` | string (EDN) / one | optional; the agent record's own ai render (absent by default) |
| `:seon.render/html` | `:seon.render/html` | string (EDN) / one | optional; generic entity-render override, not the focal canvas pin |
| `:seon.render.canvas/content` | `:seon.render.canvas/content` | string (EDN) / one | optional; literal hiccup or qualified renderer symbol explicitly pinning the focal canvas; absence derives focus |

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
| `:seon.agent.ctx/name` | `:keyword` | keyword / one | per-agent upsert key; prompt header + DOM `#surface-<name>` — **NOT a datahike identity** |
| `:seon.agent.ctx/priority` | `:int` | long / one | prompt order AND default scroll order |
| `:seon.render/ai` | `:seon.render/ai` | string (EDN) / one | optional; the prompt-text render |
| `:seon.render/html` | `:seon.render/html` | string (EDN) / one | optional; present ⇒ a surface |

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
and variadic seed mechanism
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
| `:seon.agent.run/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact fencing token |
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
| `:seon.agent.turn/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact |
| `:seon.agent.turn/at` | `:inst` | instant / one | |
| `:seon.agent.turn/status` | `[:enum :running :done :error :interrupted]` | keyword / one | value enum; `:interrupted` is asserted only by crash recovery when no runtime remains to close the committed turn normally |
| `:seon.agent.turn/run` | `:seon.db/ref` | ref / one | turn → its run |
| `:seon.agent.turn/cause-message` | `:seon.db/ref` | ref / one | optional; exact inbound human message this turn is assigned to answer |
| `:seon.agent.turn/rendered-database-id` | `:uuid` | uuid / one | frozen prompt database identity |
| `:seon.agent.turn/rendered-branch` | `:keyword` | keyword / one | frozen prompt branch |
| `:seon.agent.turn/rendered-commit-id` | `:uuid` | uuid / one | canonical frozen prompt commit |
| `:seon.agent.turn/rendered-t` | `:int` | long / one | frozen prompt t; display/range aid |
| `:seon.agent.turn/prompt-chars` | `:int` | long / one | |
| `:seon.agent.turn/prompt-file` | `:string` | string / one | |
| `:seon.agent.turn/llm-retries` | `:int` | long / one | |
| `:seon.agent.turn/llm-usage` | `:string` | string / one | |
| `:seon.agent.turn/evals` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / **component** | owned evals (cascade-retract) |

The run's `:seon.agent.run/cause` is only the message that opened the run; later
human messages can renew that same run. At each turn boundary the runtime selects
the exact inbound address task the turn is assigned to answer and records its
message ref as `:seon.agent.turn/cause-message`. Continuation turns may retain
the same message; a later queued message may become the next turn's cause;
schedule/internal turns omit it. This is a fact about the runtime's assignment,
not provenance copied onto arbitrary transactions and not safely inferable from
the run opener.

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
`(ai/resolved-config {:seon.db/db (db/at-coordinate rendered-coordinate)
                      :seon.agent/id id})`.
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

### 4.5 crash recovery — `:seon.runtime.recovery/*`

Crash recovery writes one small anchor in the same deterministic transaction as
the run/turn/pointer repairs. It stores the fact that an unexpected recovery
happened, not a materialized incident report:

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.runtime.recovery/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | frozen operation identity; retry upserts the same anchor |
| `:seon.runtime.recovery/reason` | `[:enum :unexpected-exit]` | keyword / one | the observed durable cause |
| `:seon.runtime.recovery/detail` | `[:string {:max 2048}]` | string / one | optional bounded diagnostic only when it is not derivable from transaction facts |

The anchor does **not** copy agent/run/turn refs, timestamps, prior/current
coordinates, acknowledgement state, or a rendered notice. Query the
`:seon.runtime.recovery/id` datom's transaction and join it to the run close,
turn status, and pointer-retraction datoms in that transaction; transaction
metadata supplies user/process/time, and the commit graph supplies prior/current
coordinates. Root's recovery notice and “still needs a decision” prominence are
projections of that join and whether each affected agent has opened a later run.
An interrupted eval that never committed has no durable result, so recovery does
not invent one; already committed eval rows remain facts as recorded.

### 4.6 message + user + web session

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.message/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact |
| `:seon.agent.message/content` | `:string` | string / one | |
| `:seon.agent.message/from` | `:seon.db/ref` | ref / one | → user or agent |
| `:seon.agent.message/to` | `[:vector :seon.db/ref]` | ref / **many** | recipients (NOT component — not owned) |
| `:seon.agent.message/at` | `:inst` | instant / one | |
| `:seon.agent.message/hops` | `:int` | long / one | hop-cap guard |
| `:seon.agent.message/origin` | `[:enum :human :agent :core]` | keyword / one | value enum used by the wake gate; `:core` is a non-waking substrate nudge |
| `:seon.agent.message/web-session` | `:seon.db/ref` | ref / one | optional; originating browser-tab session for a human message; not component-owned |

`:seon.user/id` (`[:string {:seon.db/identity true}]`) + the `:seon.user`
entity-map are the one human; `user-ref` = `[:seon.user/id "user"]`. An inbound
human message is the first trigger of a run and auto-creates a `my.plan` (§5.3); a
hop-exhausted message becomes a dead-letter.

The web UI records one exact location per browser tab without turning presence
into a parallel service:

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.web.session/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | tab-local natural key |
| `:seon.web.session/user` | `:seon.db/ref` | ref / one | → human identity |
| `:seon.web.session/location` | `[:string {:min 1}]` | string / one | normalized same-origin path + query, including an optional manual surface selector; observed navigation, surface choice, and root selection update this fact |

Route/agent selection is derived by matching `location` through the database-
derived reitit router. There is deliberately no duplicate selected-agent ref,
route-name attr, `updated-at`, `active?`, or acknowledgement flag; transaction
metadata supplies who/when and the browser renders the current cardinality-one
fact. Scroll, disclosure, and form-signal state are intentionally absent. An
inbound human message's optional `web-session` ref lets root target the
originating tab without storing ambient session state on root.

Session identity is scoped by the database value that contains it. The browser's
transient reconnect tuple carries `{database-id, branch, session-id}`; bootstrap
reuses it only when that attachment matches and the session lookup ref resolves
to the current human. No database-id or branch projection is copied onto the
session entity.

### 4.7 schedule — `:seon.agent.schedule/*`

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.agent.schedule/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact |
| `:seon.agent.schedule/cron` | `:string` | string / one | 5-field cron |
| `:seon.agent.schedule/fn` | `:symbol` | **symbol** / one | qualified fn to invoke — symbol-as-value (§2.3) |
| `:seon.agent.schedule/timezone` | `:string` | string / one | IANA tz |
| `:seon.agent.schedule/concurrency-policy` | `[:enum :forbid :allow]` | keyword / one | value enum |

### 4.8 eval — `:seon.eval/*`

Every form an agent evaluates is recorded as a `:seon.eval` row — the durable
history the warn-checks query and the transcript renders. Initial safe
declarations are born as program facts and loaded after commit; they are not
fabricated eval rows.

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.eval/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact |
| `:seon.eval/source` | `:string` | string / one | the form's source |
| `:seon.eval/ok?` | `:boolean` | boolean / one | false ⇒ a failed eval |
| `:seon.eval/error` | `:string` | string / one | optional; the rendered error headline |
| `:seon.eval/error-data` | `:string` | string / one | optional; EDN of the structured error payload (§6) |

### 4.9 route — `:seon.route/*`

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
| `:seon.route/middleware` | `:keyword` | keyword / one | optional; one middleware key resolved through reitit's registry and wrapped as reitit middleware data at projection time |

The `seon.route` entity map (`{:seon.db/entity true}`) requires
`pattern`/`method`/`name`/`handler`, with `owner`/`middleware` optional.
`:seon.route/handler` is a native `:db.type/symbol` (a route handler is always a
layout symbol, never literal hiccup) — "a route handler IS a layout" holds at the
value level; it simply stores as a pure symbol rather than the EDN-encoded
mixed-`:or` of `:seon.render/html`. A middleware chain is not stored as a
cardinality-many value because Datahike sets cannot preserve order; the current
model stores the one required gate as one fact. **Ground in** reitit `trie.cljc:60`
(`split-path` accepts both `{id}` and `:id`) + `reitit-ring/.../ring.cljc:14-16`
(`http-methods` + `Endpoint`): one `{pattern, method, handler, middleware}` row
maps to `["/agent/{id}" {:get {:handler <sym> :middleware […]}}]`. `db->routes`
(UI lane) groups rows by `pattern`, nests by `method`. See [[library-grounding]].

### 4.10 program graph — `:seon.fn` / `:seon.ns` / `:seon.schema` / `:seon.test`

Blocks, routes, and schedules reference these members BY SYMBOL VALUE (§2.3), so
only the identities and core refs matter here.

| entity | identity attr | valueType | other refs |
|---|---|---|---|
| `:seon.ns` | `:seon.ns/name` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.ns/requires [:vector :keyword]` (cardinality-many), `:seon.ns/require-edges` (component rows `{:seon.ns.require/target :keyword, alias :symbol, refers [:set :symbol]}` — the reified `:as`/`:refer` facts the SCI cage env is built from AND boot replay's synthesized `(ns …)` head for a sourceless member-bearing row — the agent HOME ns, whose requires are wired at runtime; teed from the analyzer, boot-indexed for full-source nses), `:seon.ns/source :string` |
| `:seon.fn` | `:seon.fn/sym` `[:string {:seon.db/identity true}]` | string | `:seon.fn/ns :seon.db/ref`, plus source/spec/arglists/doc strings; renderer reads are runtime observations, not stored keyword literals |
| `:seon.schema` | `:seon.schema/key` `[:keyword {:seon.db/identity true}]` | keyword | `:seon.schema/ns :seon.db/ref`, full canonical untruncated EDN form |
| `:seon.test` | `:seon.test/sym` `[:string {:seon.db/identity true}]` | string | `:seon.test/ns :seon.db/ref`, last-passed-at/last-failed-at insts |

The entity identity/required/render catalog is derived once per validated Malli
registry generation from those canonical forms. It is process-local projection
data, not a second append-only schema decomposition in Datahike.

**Index everything, show `my.*` in full.** The boot analyzer indexes EVERY
namespace's valid forms into the program graph (`:seon.ns` / `:seon.fn` /
`:seon.schema` / `:seon.test`), so the whole code corpus is queryable as data
(the runtime IS the database). The agent's context renders only `my.*` members in
FULL source — the agent's own code, the thing it edits — while the rest of the
graph is indexed-but-summarized: discoverable by query, not expanded into the
prompt. The render policy (what expands in context) is owned by [[ui]]; the index
is the data fact here.

### 4.11 completed restore — `:seon.db.restore/*`

A restore has no persisted phase/status checklist. After the guarded root move,
selected overlays, and runtime reconstruction succeed—but before admission—one
fact records what actually happened:

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.db.restore/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | string / one / identity | compact completion key |
| `:seon.db.restore/db-name` | `:keyword` | keyword / one | logical routing label |
| `:seon.db.restore/database-id` | `:uuid` | uuid / one | stable database identity |
| `:seon.db.restore/from-branch` | `:keyword` | keyword / one | source branch |
| `:seon.db.restore/from-commit-id` | `:uuid` | uuid / one | source commit |
| `:seon.db.restore/from-t` | `:int` | long / one | source branch-local t |
| `:seon.db.restore/to-branch` | `:keyword` | keyword / one | selected target branch |
| `:seon.db.restore/to-commit-id` | `:uuid` | uuid / one | selected target commit |
| `:seon.db.restore/to-t` | `:int` | long / one | target branch-local t |
| `:seon.db.restore/forced-commit-id` | `:uuid` | uuid / one | commit created on live main by force |
| `:seon.db.restore/undo-branch` | `:keyword` | keyword / one | retained undo branch |
| `:seon.db.restore/target-branch` | `:keyword` | keyword / one | prepared target branch |
| `:seon.db.restore/core-overlay-digest` | `:string` | string / one | optional; present only when committed |
| `:seon.db.restore/config-overlay-digest` | `:string` | string / one | optional; present only when committed |

Attribute presence identifies the fact; there is no kind, phase, progress,
generation, or status attr. The recording transaction supplies time,
`:seon.db/user`, and `:seon.db/process`. Optional digest absence means that
population was preserved. External lifecycle intent is deleted after this fact
and admission; it is crash-recovery input, not a second history log.

## 5. Domain schemas — `my.*` (the agent's data)

The `my.*` namespaces carry the agent's actual domain data and are installed as
worked examples through canonical schema/function/context facts (see
[[agent-runtime]] for fact-first initialization and [[toolkit]] for the
functions). They demonstrate the two scoping patterns.

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
| `:my.plan/id` | `[:and {:seon.db/identity true :seon.db.id/generator :seon.db.id.generator/compact} :seon.db.id/compact-value]` | compact step/plan key |
| `:my.plan/title` | `[:string {:min 1}]` | the step, one line |
| `:my.plan/status` | `[:enum :open :active :done :blocked]` | value enum; **`:active` = where the agent IS** |
| `:my.plan/agent` | `:seon.db/ref` | → owning agent (the scoping ref) |
| `:my.plan/parent` | `:seon.db/ref` | optional; → parent step (the nesting edge) |
| `:my.plan/needs` | `[:vector :seon.db/ref]` | optional; → prerequisite steps (dependency edges — a step is READY only when all `needs` are `:done`) |
| `:my.plan/goal` | `[:string]` | optional, root-level; the WHY narrative that outlives the transcript window |
| `:my.plan/expect` | `[:string]` | optional; the falsifiable expected outcome — "how I'd know this step failed" — so the render prompts VERIFY-before-`done!` (stops blind re-issue) |
| `:my.plan/pace` | `[:enum :one-shot :multi-session]` | optional, root-level; explicit scope so "spans sessions" can't collapse to a sprint |
| `:my.plan/completed-at` | `:inst` | optional; drives the recently-completed window |
| `:my.plan/from` | `:seon.db/ref` | optional; → who asked |
| `:my.plan/message` | `:seon.db/ref` | optional; → the inbound message it tracks |

```clojure
;; ns my.plan — per-agent dependency graph. A step entity is identified by
;; :my.plan/id; required = what step!/plan! write unconditionally.
(schema/register! ::step
  [:map {:seon.db/entity true}
   [::id           ::id]
   [::title        ::title]
   [::status       ::status]
   [::agent        ::agent]          ; the DATA→AGENT scoping ref
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
counter.** Creation time is the `:db/txInstant` of the step identity datom; it
is not duplicated on the entity. A parent is `:done` when every descendant is
`:done`; a step is
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
agent's stated objective. It is the first per-agent worked example: agent birth
relies on the already validated shared `:my.agent/purpose` schema, then includes
the purpose value, an agent-home `refine` function declaration, and a
self-refining context component so the agent owns and sees its purpose and can
revise it. A later agent birth never reasserts or reattributes the shared schema row.

```clojure
;; ns my.agent — the purpose attr rides on the agent entity (open map).
(schema/register! :my.agent/purpose :string)              ; a markdown goal string
```

Root/boot owns the shared schema fact. The atomic agent-specific birth facts and
post-commit declaration load are owned by [[agent-runtime]]; the refine function
is owned by [[toolkit]].

### 5.5 knowledge-on-demand and importable skills

An agent needs domain knowledge where it is working. The normal context surface
has three primary pieces, none of which requires a standing skills block:

- **Compact cards** — a home-required namespace renders as function heads +
  docstring line 1 + schema (§4.2, the `:namespaces` block). The card IS the
  discoverable expertise: the agent reads the fn contract and calls it. (Proven
  at the `repl`/`namespaces` milestones — cards suffice for correct first calls.)
- **State-gated block teaching** — each block carries its OWN teaching, rendered
  exactly when its state holds (colocation; the reactive rule). Knowledge about a
  thing lives with the block that surfaces that thing, not in a separate skill.
- **Pull references** — deeper worked manuals (`my.kb`) are PULLED on demand — the
  db is self-describing; the agent reads them when it needs them, never pushed.

Skills remain supported as **importable source data** because users bring
existing `SKILL.md` corpora. The existing `my.skills` identity and load/unload
mechanism are refined in place rather than replaced:

- one parser/validator accepts the shipped `seon-skills` tree, an explicitly
  selected directory, or uploaded `SKILL.md` bytes;
- import transacts exact canonical name/description/body facts (or a content-
  addressed body ref behind the same shape), so later config-free restart never
  requires the original file path;
- tx metadata records who/process imported it; no provenance attributes are
  duplicated on the skill entity; and
- `load`/`unload` remain explicit overrides over the one
  `install!`/`remove!` block collection. Loaded state is derived from block
  presence, never stored as a flag.

The default and test context trees contain no skills context block. Importing a
corpus therefore consumes no standing prompt tokens. Dynamic context, namespace
cards/current source, and state-gated blocks remain responsible for normal
capability discovery; explicit loading is available when a user or agent truly
wants the source in context. `seon-skills` is the shipped corpus authority, while
`.agents/skills` and `.claude/skills` are generated or mechanically validated
adapter views.

### 5.6 config manifest — an optional desired-state input

The customization seam is ONE consolidated manifest. A startup/apply request
either explicitly supplies an input (normally `config/system.edn`, or a path
explicitly selected by `SEON_CONFIG`) or selects no overlay. The path/env values
are compiled immediately into one canonical desired payload and digest; the
crash-recovery intent stores that immutable payload, never a promise to reread
the path. The manifest is an operation input, never a runtime dependency:
`resolve-config-singleton` resolves every knob (environment overrides manifest,
which overrides defaults) and the exact population reconciler restores them as
one `:seon.config` singleton entity (`[:seon.config/id "cluster"]`)—every dial a
datom. No selection means no config transaction; it is not an empty manifest and
does not fall back to `config/system.edn`. After a successful apply, later
config-free boots use the committed database facts.

From attachment onward every runtime read is a database query via
`config/config-view` (the accessors keep their names + arities; one projection is
threaded per snapshot or cached by branch-qualified commit coordinate, never by
a db value). A tiny compiled kernel fallback may serve only the pre-connection
database-connect/error path and is never exposed as attached runtime config. A
required missing DB fact after attachment is a typed readiness error, not a
silent current default. Every manifest section is `{:optional true}`, so `{}` is
a valid explicitly selected desired value; an unknown key fails loud. The full boot/read
mechanics — the require-direction db→error→config, `seon.db` injecting the
reader, replay-visible + live-tunable dials — live in [[context]]
§"Config-through-DB"; this section is the schema of record.

Config owns only its declared populations/attributes. A selected startup or explicit apply
retracts omitted managed values and stale exclusive rows, repairs partial state,
and submits nothing when equal; agents/messages/plans/knowledge and every other
outside fact survive untouched. The resulting transaction is `{user root,
process config}`. Live database edits are visible until that next apply.

The real manifest carries a dial per config concern (not just seeds) — a new
concern = ONE `:seon.config/<section>` schema + one resolver fn + one key here:

```clojure
;; ns seon.config — the registry of known sections (config.cljs)
(schema/register! :seon.config/manifest
  [:map
   [:seon.config/repl-mode        {:optional true} :seon.config/repl-mode]         ; :batch | :stream (per-model default when absent)
   [:seon.config/namespaces       {:optional true} :seon.config/namespaces-spec]   ; which nses render full
   [:seon.config/routes           {:optional true} [:vector :seon.config/route-spec]]
   [:seon.config/render           {:optional true} :seon.config/render]            ; the render caps (EDN/eval/message/value…)
   [:seon.config/system-text      {:optional true} :seon.config/system-text]       ; the system-prompt string → the datom
   [:seon.config/on-core-error    {:optional true} :seon.config/on-core-error]     ; :crash | :gate | :log (the ONE fault dial)
   [:seon.config/web              {:optional true} :seon.config/web-spec]
   [:seon.config/repair           {:optional true} :seon.config/repair]
   [:seon.config/spawn-depth-cap  {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/watchdog         {:optional true} :seon.config/watchdog]
   [:seon.config/schedule-breaker {:optional true} :seon.config/schedule-breaker]
   [:seon.config/agent-context    {:optional true} :seon.config/agent-context]     ; the per-agent block tree + dials
   [:seon.config/skills           {:optional true} :seon.config/skills]            ; importable SKILL.md corpus input
   [:seon.config/root-context     {:optional true} :seon.config/root-context]])    ; a sparse override merged for root
```

Each key resolves onto the flat `:seon.config/singleton` entity (`config.cljs`,
every knob `{:optional true}`, `:seon.config/id` the only required key), so the
render caps, `repl-mode`, `system-text`, `on-core-error`, and the multi-agent
dials are all datoms an agent's turn reads through `config-view`. `resolve-routes`
produces canonical desired maps reconciled exactly when selected; absence from the
final route population means removal. The optional skills section is an
**import input**, not a loadout: it freezes and validates selected `SKILL.md`
bytes into canonical database facts during apply, while the agent's skills
context block remains absent unless explicitly installed. Later config-free
boots use those facts and do not reread the directory. A selected manifest is
the single env surface (its `#env` tags let a var override a manifest default).
`SEON_PROFILE` / aero `#profile` is INERT — variants are separate manifest FILES
selected by `SEON_CONFIG`, never in-file profiles.

**Per-test / per-cluster recipe** — name your own manifest, zero src edits:

- `SEON_CONFIG=config/test.edn bin/test-cljs` — a test run loads its own
  context / routes / render caps (pins `:batch`).
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
(precedent: `:seon/embedding`), and a specialized error keyword is registered ONLY
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
drill-down into `:seon.error/data` (the human error card).

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

- **Returned render / transact / capability errors** are TRANSIENT in-memory
  `:seon/error` values—constructed at the failure site, surfaced by a render or
  a check, gone next render (self-healing). When a catch site calls
  `seon.error/record!`, it also persists the bounded forensic projection below;
  the transient envelope itself is never stored.
- **The eval error is PERSISTED** as `:seon.eval/error` (the rendered headline) +
  `:seon.eval/error-data` (EDN of the structured payload) on the `:seon.eval`
  row (§4.8) — the durable log the runtime warn-checks query.

`:seon/error`, `:seon.db/error`, and `:seon.ai/error` remain Malli value shapes;
none is transacted directly. `record!` creates an anonymous entity identified by
the presence of `:seon.error/fault` and carrying only EDN-safe projections:

| attribute | malli | datahike facet | notes |
|---|---|---|---|
| `:seon.error/fault` | `[:enum :agent :core]` | keyword / one | fault population |
| `:seon.error/message` | `:string` | string / one | bounded deepest-cause headline |
| `:seon.error/kind` | `:keyword` | keyword / one | optional diagnostic value enum |
| `:seon.error/database-id` | `:uuid` | uuid / one | catch-site database identity |
| `:seon.error/branch` | `:keyword` | keyword / one | catch-site branch |
| `:seon.error/commit-id` | `:uuid` | uuid / one | canonical catch-site commit |
| `:seon.error/t` | `:int` | long / one | catch-site t; display/range aid |
| `:seon.error/stack` | `:string` | string / one | optional bounded raw stack |
| `:seon.error/frames` | `[:vector {:seon.db/component true} :seon.db/ref]` | ref / many / component | optional parsed frames |
| `:seon.error/args-edn` | `:string` | string / one | optional bounded arguments |
| `:seon.error/data-edn` | `:string` | string / one | optional bounded structured data |

The four coordinate attrs are captured together. A partial coordinate is
invalid; structured reproduction never guesses a lineage from a bare t.

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
lint, test status, message routing, unresolved surfaces).

**Failure-site → surface (by carrier).** Every site catches, names a carrier, and
reaches at least one agent-visible AND one human-visible surface.

| failure site | carrier | agent-visible | human-visible |
|---|---|---|---|
| **render** (block ai/html throws, missing symbol, SCI deadline) | transient `:seon/error` under the `:seon.render/error` key | warnings block (render-health check) | the in-place error card (siblings untouched) |
| **eval** (a form throws) | PERSISTED `:seon.eval/error` + `:seon.eval/error-data` | the eval's render in the transcript + the failed-eval checks | the transcript activity/error row |
| **transact** (a tx is rejected) | transient `:seon.db/error` under `::error` | the eval that called `transact!` records it | the transcript error row |
| **capability denial** (fs / `/call` refuses) | the denial string in the eval result | the fs-denied check | the transcript error row |
| **schema / instrumentation rejection** | `:seon.error/data` = the malli explain, under `:seon.eval/error-data` | the failed-eval check renders the structured error | the transcript error row |
| **LLM / provider error** | `:seon.ai/error` → turn `:seon.agent.turn/status :error` | the transcript system line derived from the turn status | the same transcript line |
| **throwing warn-check** | synthetic `:warn-check-error` cluster | the warnings block (that check degrades loudly) | the warnings surface |
| **throwing layout / route handler** | transient `:seon/error` (same as render) | warnings block if the agent owns the route | a human error page / error card |
| **runaway / hung eval** | run `:seon.agent.run/closed-reason :deadline-exceeded` | derived run-status surfaces “deadline exceeded” | the run-status surface |

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
  fact-first initialization, orchestrator-root lifecycle, substrate-message
  quietness, isolation tiers.
- [[ui]] — block / render / surface / slot / layout, view / root-agent-view / app,
  reitit routing + the capability gate, the gzip-morph SSE channel, the
  seed-copy + variadic `install!`/`remove!` override model.
- [[toolkit]] — the `my.*` function catalog (the agent's action surface over these
  schemas).
- [[roadmap]] — current code state, the gap, and the dependency-ordered
  migration to this target.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset
  for the bridge (db is a value, only values cross the protocol,
  CAS-as-assertion).
