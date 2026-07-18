---
type: research
status: complete
tags: [database, agent, research, schema]
---

# Agent ID rename: Datahike source audit

## Conclusion

Datahike permits changing the value of a cardinality-one
`:db.unique/identity` attribute. The entity's numeric eid does not change, so
all existing ref datoms continue to point at the same entity. The old lookup
ref stops resolving and the new lookup ref resolves to the original eid.

The safest operation is one compare-and-swap transaction:

```clojure
[[:db.fn/cas [:seon.agent/id old-id]
  :seon.agent/id old-id new-id]]

```

This both asserts that `old-id` still names the intended entity and installs
`new-id` atomically. Datahike rejects an already-used `new-id`, and no observer
can see an intermediate identity-less entity.

Therefore Datahike is not a blocker to renaming Seon agents. Seon's current
coupling of the ID to the home namespace, routes, execution-child registry,
and external addresses is the blocker that needs an application-level rename
transition.

## Dependency ledger

- Selected source: `reference-code/datahike`, commit
  `4c55791be1fb8bb8d9332f21c576f5c20b85b760` (2026-07-18,
  `Avoid nil attribute probes in compiled queries`).
- Transaction implementation:
  `reference-code/datahike/src/datahike/db/transaction.cljc`.
- Lookup-ref resolution:
  `reference-code/datahike/src/datahike/db/utils.cljc`.
- Maintained behavioral tests:
  `reference-code/datahike/test/datahike/test/lookup_refs_test.cljc` and
  `reference-code/datahike/test/datahike/test/upsert_test.cljc`.
- Seon schema and identity-dependent consumers:
  `src/seon/agent/ctx/render_fns.cljs`, `src/seon/agent/home.cljs`,
  `src/seon/agent.cljs`, `src/seon/execution/host.cljs`, and
  `src/seon/route.cljs`.

## What the source establishes

### Unique identity is an indexed attribute, not the eid

Lookup-ref resolution validates that the attribute is unique, then reads AVET
for `[attribute value]` and returns the matching datom's eid
(`db/utils.cljc:120-129`). There is no separate immutable identity object.
Changing the attribute value changes the AVET key used for future lookup; it
does not rewrite the eid.

For a cardinality-one add, transaction processing chooses the upsert path
(`db/transaction.cljc:713-738`). That path finds the entity's current datom,
checks the proposed value against AVET uniqueness, and replaces the datom in
EAVT, AEVT, and AVET (`db/transaction.cljc:460-500`, especially 460-467 and
493-500). Thus adding a new value on the same eid retracts/replaces the old
cardinality-one value as one transaction operation.

### Compare-and-swap is the direct rename primitive

`compare-and-swap` first resolves the entity selector against the transaction's
current database value, reads the current `[eid attribute]` datom, verifies the
old value, and delegates the new value to the ordinary cardinality-one add path
(`db/transaction.cljc:893-915`). That add performs the unique check described
above. A stale old value fails with `:transact/cas`; a new value already owned
by another eid fails with `:transact/unique`.

Datahike's maintained lookup-ref transaction test already changes a unique
identity value with exactly this shape:
`[:db.fn/cas [:name "Ivan"] :name "Ivan" "Oleg"]`, and asserts that eid 1
now carries `"Oleg"` (`lookup_refs_test.cljc:58-60`). The same test establishes
that lookup refs resolve against the intermediate database value during an
ordered transaction (`lookup_refs_test.cljc:53-56`).

### Refs remain intact

Ref values are stored as numeric eids. Lookup refs are only selectors resolved
to eids at transaction/query boundaries (`db/utils.cljc:194-203`). Renaming an
identity datom does not touch inbound or outbound ref datoms. Only
`retract-entity` searches inbound ref attributes and retracts them
(`db/transaction.cljc:917-933`); a cardinality-one identity replacement does
not invoke that operation.

### Atomicity and ordering

The transaction reducer applies operations to its evolving `:db-after`; map
and vector operations are processed into that one report
(`db/transaction.cljc:1180-1233`). The complete transaction either returns a
new immutable database value or throws before commit. Lookup refs later in the
same transaction can therefore resolve identities asserted earlier.

CAS is preferable to a separate retract followed by add. Both ordered forms
worked in the probe, but explicit retract-first temporarily removes the lookup
ref inside the transaction and makes later operations order-sensitive. A plain
cardinality-one `:db/add` to the numeric eid also replaces the old value, but
does not assert that the caller still owns the expected old public identity.

### Upsert and conflict constraints

Entity maps containing an identity value use AVET to select an existing eid
(`db/transaction.cljc:568-642`). Multiple identity attributes in one map must
resolve to the same eid; otherwise Datahike raises `:transact/upsert`. An
explicit numeric `:db/id` that conflicts with the eid selected by the identity
also fails (`db/transaction.cljc:554-566`). The maintained tests cover explicit
eid conflicts and two identity attributes resolving to different entities
(`upsert_test.cljc:92-109`).

These rules matter during a rename transaction: do not include another unique
identity assertion that resolves to a different entity, and do not try to
"merge" two agents by assigning one agent's ID to the other. Uniqueness is
enforced by AVET for both `:db.unique/identity` and `:db.unique/value`; only
identity participates in upsert selection.

### History

With `:keep-history? true`, cardinality-one replacement updates temporal EAVT,
AEVT, and AVET as well as current indexes (`db/transaction.cljc:475-500`). The
old assertion and its retraction remain in history; the current database has
only the new identity. The maintained history test expects a changed
cardinality-one value to add a retraction and assertion
(`upsert_test.cljc:195-220`). Historical database values can consequently
resolve the old lookup ref at an old basis, while the current value resolves
only the new lookup ref.

## Shortest executable probe

The audit ran this shape directly against the selected vendored source with
an in-memory, history-enabled database:

```clojure
(let [db1 (:db-after
           (d/with (db/empty-db
                    {:agent/id {:db/unique :db.unique/identity
                                :db/cardinality :db.cardinality/one}
                     :task/owner {:db/valueType :db.type/ref}}
                    {:keep-history? true})
             [{:db/id -1 :agent/id "old"}
              {:db/id -2 :task/owner -1}]))
      eid (:db/id (d/entity db1 [:agent/id "old"]))
      db2 (:db-after
           (d/with db1
             [[:db.fn/cas [:agent/id "old"]
               :agent/id "old" "new"]]))]
  {:before eid
   :after (:db/id (d/entity db2 [:agent/id "new"]))
   :old (d/entity db2 [:agent/id "old"])})

```

Observed result: `:before` and `:after` were both eid `1`; `:old` was nil. A
separate task entity's `:task/owner` still resolved to eid `1`. Current AVET
contained `[1 :agent/id "new"]` and no old value. History contained the old
assertion, old retraction, and new assertion. Attempting to rename to another
entity's existing value raised `:transact/unique`; using a stale expected old
value raised `:transact/cas`.

## Seon-specific constraints

Seon's schema is compatible with the Datahike operation. It registers
`:seon.agent/id` with `:seon.db/identity true`, backed by the human-readable ID
shape (`src/seon/agent/ctx/render_fns.cljs:40-47`). This becomes a
cardinality-one `:db.unique/identity` attribute. Its uniqueness scope is one
cluster database, not all databases; the same display or machine ID may exist
in another isolated cluster.

Existing durable agent relationships use refs and therefore survive the value
change automatically. The application nevertheless treats the value as more
than a replaceable label:

- `home/home-ns` deterministically derives `my.agent.<id>` from it, and agent
  creation persists a separately identified `:seon.ns/name` plus source under
  that derived name (`agent/home.cljs:29-34,155-172`). Renaming only the agent
  datom would leave the old home namespace authoritative and make current
  derivations look for a different namespace.
- The execution host keys its live child map by agent-id and passes that string
  in the child's startup value (`execution/host.cljs:119-120,318-354`). A live
  rename must quiesce/retire the old child before the new ID can host work; an
  in-flight invocation cannot safely be relabeled by changing only Datahike.
- Public pages and feeds embed the ID in `/agent/{id}` routes
  (`route.cljs:100-113`). Old URLs and any external lookup refs/bookmarks stop
  resolving unless Seon deliberately provides a durable alias/redirect model.
- Agent creation, current-agent bindings, runtime advertisements, logs, and
  process supervision pass the ID as a scalar address. They will naturally use
  the new value after reacquisition/restart, but already captured values do not
  mutate.
- The reserved root ID participates in provenance/bootstrap assumptions and
  should not be user-renamable without a separate system migration.

These are application migration concerns, not Datahike limitations.

## Safe Seon transaction recipe

A complete Seon rename should be one lifecycle transition, not merely a raw
attribute edit:

1. Authorize root (or the agent itself, if policy permits), reject `root`, and
   validate the new ID against the registered `:seon.agent/id` shape.
2. Quiesce the agent and retire its execution child so no invocation continues
   under the old scalar address.
3. Capture one current database value and commit an expected-database
   transaction led by the CAS above.
4. In that same transaction, migrate the separately identified home namespace
   entity and any source/symbol facts whose names intentionally derive from the
   ID. This portion needs a dedicated program-graph audit; changing
   `:seon.ns/name` is itself another unique-identity replacement and source
   references may contain the old namespace symbol.
5. Reacquire the committed database value, publish the new runtime membership,
   and host future work under the new ID.
6. Decide explicitly whether old external addresses should fail or resolve via
   a durable alias. Datahike itself correctly makes the old lookup ref fail.

Do not model a mutable display name by repeatedly renaming the machine ID. A
non-unique `:seon.agent/name` remains useful for freely editable labels. But if
the product wants a better unique machine name, Datahike supports changing the
existing ID once Seon owns the namespace/runtime/address migration.

## Recommendation

Correct the earlier claim that a Datahike identity value cannot be changed.
It can. Preserve the distinction between numeric entity identity and a unique
identity-attribute value.

Implement agent rename only as an explicit, tested Seon lifecycle operation
that atomically changes the identity and program namespace state, retires the
old child, and defines old-URL behavior. Retain a separate mutable display name
for ordinary cosmetic renaming. Add focused Datahike/Seon tests for eid/ref
preservation, old/new lookup refs, collision and stale-CAS failure, history,
home namespace migration, restart/resume, and old-address policy before
exposing the operation.

## Follow-up: a unique namespace ref as agent identity

### Datahike supports unique identity refs

Datahike permits the same attribute to have both
`:db/valueType :db.type/ref` and `:db/unique :db.unique/identity`. Schema
validation treats value type, cardinality, and uniqueness as independent
facets and contains no prohibition on this combination
(`datahike/db.cljc:828-840`). The transaction path resolves ref values to
numeric eids before constructing the datom (`db/transaction.cljc:713-723`),
then applies the ordinary AVET unique check and cardinality-one replacement.

A normalized shape is therefore valid:

```clojure
{:seon.agent/namespace [:seon.ns/name :my.tax]}
```

with schema equivalent to:

```clojure
{:db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity}
```

The complete nested lookup ref is also valid:

```clojure
[:seon.agent/namespace [:seon.ns/name :my.tax]]
```

It first resolves `[:seon.ns/name :my.tax]` to the namespace eid and then uses
that eid as the AVET value for `:seon.agent/namespace`. CAS resolves both old
and new ref values through `entid-strict` before comparing and adding
(`db/transaction.cljc:893-915`).

### Executable unique-ref probe

The follow-up probe used a history-enabled database containing three namespace
entities, two agents, and a task ref to the first agent. The first agent held
unique ref `:agent/ns` to `:my.tax`; CAS moved it to the existing `:my.gym`
namespace:

```clojure
[[:db.fn/cas
  [:agent/ns [:ns/name :my.tax]]
  :agent/ns
  [:ns/name :my.tax]
  [:ns/name :my.gym]]]
```

Observed results:

- the agent eid remained `4`;
- the task's numeric ref target remained eid `4`;
- both `[:agent/ns gym-eid]` and the nested lookup ref
  `[:agent/ns [:ns/name :my.gym]]` resolved to eid `4`;
- both corresponding old-tax lookup refs returned nil;
- current AVET contained `[4 :agent/ns gym-eid]` only; and
- history contained the original tax-ref assertion, its retraction, and the
  gym-ref assertion.

When the second agent attempted to adopt `:my.gym`, the transaction failed
with `:transact/unique`. A map containing only the already-owned unique ref
upserted into agent eid `4` as expected. The same map with explicit eid `5`
failed with `:transact/upsert`, because the unique ref resolved to eid `4`.

This proves that the namespace ref can be the agent's Datahike identity
attribute, including ordinary entity-map upsert selection. It also proves that
two agents cannot simultaneously own the same namespace under this schema.

### Assessment of the normalized Seon model

The proposed model is materially simpler than storing a human-readable agent
ID and separately deriving a home namespace from it:

```clojure
{:seon.agent/namespace [:seon.ns/name :my.tax]}
```

The agent has one database-local numeric eid. Durable messages, runs, plans,
tasks, provenance, and parent relationships point to that eid through ref
datoms. The namespace entity has the human-meaningful unique name. Routing by
namespace performs the nested lookup above and reaches the agent without a
second public identifier.

This model has useful consequences:

- Renaming `:seon.ns/name` preserves both the namespace eid and the agent's ref
  to it. A route using the new namespace begins resolving the same agent; the
  old namespace lookup stops resolving. There is no agent-identity datom to
  change in that case.
- Moving an agent between two existing namespace entities is the unique-ref
  CAS shown above. The agent eid and all agent refs remain stable.
- Datahike enforces one agent per namespace. This is desirable if the
  connection means exclusive resident identity/stewardship.
- A separate mutable, non-unique display name remains optional UI data, not an
  identity requirement.
- The namespace keyword itself can be the stable external/process address for
  a child generation. A second generated durable process address is not needed
  merely to satisfy Datahike. The execution host must still quiesce the child
  during a namespace rename or reassignment because its process-local map and
  startup context capture the old address.

There are two product tradeoffs to settle before adopting it:

1. Cardinality-one plus uniqueness encodes exactly one namespace per agent and
   one agent per namespace. If an agent should steward several namespaces, or
   several agents should collaborate on one namespace, stewardship is a
   separate non-identity relationship and cannot replace this identity ref.
2. A tax or workout specialist needs a namespace-shaped canonical address
   (`:my.tax`, `:my.workout`). That is coherent when every resident specialist
   owns a home program namespace. It is less natural if agents may exist
   without code or if namespace names are expected to describe shared code
   independently of one resident agent.

Within the stated resident-specialist model, the normalized unique-ref design
is the stronger recommendation. Route `/agent/my.tax` (or an encoded keyword
equivalent) by `:seon.ns/name` joined through
`:seon.agent/namespace`; keep numeric eids internal; retire/restart the active
child around namespace rename; and add an alias entity only if old URLs must
continue resolving. Do not add a generated public ID solely because the old
model had one.
