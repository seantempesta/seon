---
type: research
status: complete
tags: [research, database, program-graph]
---

# Lookup-ref rejection census (2026-08-29)

## Result

At `d3b93f6249dd0ebe7312ee2d9ed8a2b243aa70b1`, the production census finds
**five live latent producer families**: four outside the call-edge producer
repaired by `cba625790`, plus one residual race in that repaired producer.
The other transaction builders either establish the target earlier in the same
transaction, resolve it to an eid inside a transaction function, or depend on a
boot/runtime ordering that makes the identity durable before the producer can
run.

The central source finding slightly narrows the phrase “lookup refs resolve
against the before-db.” Datahike reduces tx-data in order against the evolving
transient `:db-after`: a lookup ref can see an identity asserted by an **earlier**
operation in the same transaction, but it cannot resolve forward to a companion
row that appears later. A ref-valued string tempid has that forward-allocation
case; an ordinary lookup ref does not.

## Scope and method

This is a source census, not a live-cluster experiment. No cluster was started,
stopped, or mutated. I read `AGENTS.md` sections 2.2 and 3 first, then followed
the repository's `data-oriented-clojure` and `datahike` instructions.

The sweep enumerated production `db/transact!`, `:tx-data`, and
`:db.fn/call` sites under `src/`; searched for identity-vector construction;
then followed transaction functions and schema-declared ref attributes so that
lookup refs assembled indirectly in request or row maps were included. Pure
query/pull selectors, render requery identities, Malli forms, and arbitrary
two-element data vectors on non-ref attributes are not transaction lookup refs
and are excluded. The focused dependencies were:

- Seon's one transaction owner, `src/seon/db.clj:1857-2021`;
- Datahike transaction reduction,
  `reference-code/datahike/src/datahike/db/transaction.cljc` at vendored commit
  `cdcb5792db8bd599487f099437265d18a31164a5`;
- Datahike entity-id resolution,
  `reference-code/datahike/src/datahike/db/utils.cljc` at the same commit;
- Datahike's serial writer,
  `reference-code/datahike/src/datahike/writer.cljc` at the same commit;
- the repaired call-edge path, `src/seon/fn.clj:547-625` and
  `src/seon/cluster/run.clj:1079-1124`;
- the resolved issue
  `docs/seon/issues/archive/call-edges-to-first-party-macros-reference-unpublished-rows.md:8-60`.

## Datahike failure pipeline

`transact-tx-data` starts with `db-before`, makes the report's `:db-after`
transient, and binds each loop iteration's `db` to that current `db-after`
(`reference-code/datahike/src/datahike/db/transaction.cljc:1205-1240`). Maps are
expanded at that point (`transaction.cljc:1270-1274`), and an entity map whose
`:db/id` is itself a lookup ref is resolved strictly before upsert/tempid
allocation (`transaction.cljc:945-971`).

For vector operations, entity-position tempids are allocated/upserted first
(`transaction.cljc:1288-1298`). A string tempid in a ref-valued value position
is allocated when absent and the same operation is retried
(`transaction.cljc:1300-1303`). Otherwise the ordinary operation is applied
(`transaction.cljc:1305-1307`). `transact-add` then resolves both the entity id
and a ref-valued value through `entid-strict` against that loop's current db
(`transaction.cljc:785-794`).

`entid` recognizes a lookup ref, checks its unique attribute, and seeks AVET;
no datom means nil (`reference-code/datahike/src/datahike/db/utils.cljc:109-129`).
`entid-strict` turns that nil into `:entity-id/missing`
(`db/utils.cljc:141-148`). There is no scan of later tx-data for a matching
identity. Thus the exact ordering law is:

1. a target in the before-db resolves;
2. a target asserted earlier in tx-data resolves;
3. a target asserted only later does **not** resolve by lookup ref;
4. a ref-valued string tempid can allocate forward and later unify/upsert.

The writer applies the entire requested operation under one `try`
(`reference-code/datahike/src/datahike/writer.cljc:136-173`). A rejected
operation never enters the commit queue, and the writer recurs with the old db
instead (`writer.cljc:201-218`). The result is atomic rejection of the whole
transaction: no receipt, disposition, message, or other sibling fact commits.
Whether that becomes “silent loss” is then determined by the Seon caller: some
callers branch on the returned flat error, while a loop that retries the same
unsettleable receipt can repeatedly lose the terminal facts.

## Production census

“Same path” below includes an identity asserted earlier in the same tx-data,
an eid derived inside the currently executing transaction function, and a boot
dependency established before any concurrent agent graph is armed. A pre-read
of a retractable program row is not an atomic guarantee.

| Producer site | Guarantee or latent | Missing-target scenario / guarantee |
|---|---|---|
| Canonical schema rows and schema-shape/contract component rows — `src/seon/schema.clj:2816-2877`, `src/seon/fn/schema_shape.clj:199-317`, `src/seon/program.cljc:261-280,353-419` | **Guaranteed.** | Canonical schemas are dependency-ordered; referenced schema identities precede dependants. Shape/component trees use their own or previously emitted tempids/rows. The non-vacuous ordering property is asserted at `test/seon/schema_reference_graph_test.clj:28-60`. |
| Source activation seal — `src/seon/cluster/source.clj:185-231` | **Guaranteed.** | Activation lookup rows get explicit string tempids, and the closure points to those tempids. Missing activation requirements refuse before the seal tx. |
| Static program publication — `src/seon/fn.clj:1716-1783,1829-1873` | **Guaranteed.** | `desired-rows` derives name-only external function and namespace identities. Indexing commits namespace bases before namespace relations, then declaration bases before subjects and calls. Every static relation target therefore predates its lookup ref. |
| Config initialization and live apply — `src/seon/cluster.clj:942-1004,1296-1330`; `src/seon/config.clj:411-445,447-496` | **LATENT 1.** | Fresh publication is safe: program rows precede initialization and `transact-initialization!` admits only rows whose external lookups already resolve. Live `apply-compiled!` does not use that readiness loop. It tempid-rewrites identities included in the desired population, but leaves external schema/function refs untouched. A manifest applied to a sovereign older branch, or a concurrently removed supplier program row, makes the outer desired rows reject before `reconcile-call`; the complete config change is lost (and currently surfaced as a config refusal). |
| Cluster entity, agent creation, bootstrap seed, and root maintenance seed — `src/seon/cluster.clj:1783-1960`, `src/seon/cluster/agent.clj:93-112`, `src/seon/bootstrap.clj:742-794`, `src/seon/schedule.clj:62-99` | **Guaranteed.** | Boot establishes the process separately because tx metadata cannot use a process introduced by its own tx. The cluster/config/instruction/toolkit rows already exist; agent namespace is a tempid row before the agent; the bootstrap message precedes the run trigger; root exists before schedule seeding; and root schedule/function rows come from the already-published program graph before agent graphs are armed. |
| Run open/claim/plan/generated/refresh/recovery — `src/seon/cluster/run.clj:395-920,934-1056`; call sites `src/seon/cluster/loop.clj:1115-1189,1311-1352,1617-1622,1737-1823` | **Guaranteed.** | Run/agent/message identities are durable and never retracted. New namespaces/forms/runs use tempids or are emitted in dependency order. Transition decisions that can race are recomputed inside `:db.fn/call`; refs produced there are eids or target rows present in that same transaction-function db. |
| Receipt settlement program relations — `src/seon/fn.clj:578-617`; `src/seon/cluster/run.clj:1079-1124,1293-1323,1568-1572,1604-1693`; `src/seon/cluster/loop.clj:196-199,242-297` | **LATENT 2 (residual at the repaired producer).** | `portable-calls` rewrites only a target absent from its pre-read db. An existing call target can be retracted by another agent's `ns-unmap` before the settlement reaches the writer, leaving the retained lookup ref missing. The same receipt path has two other pre-read relations: form `:seon.test/subject` can remain a stale lookup even though `row-tx` converts the declaration's copy to `pending-subject`, and gate-test symbols are converted to lookup refs without an in-transaction existence check. Any such concurrent program deletion rejects the whole terminal receipt. |
| Runtime namespace publication (`:seon.ns/requires`) — `src/seon/sci/eval.clj:556-570,1783-1800`; `src/seon/program.cljc:694-714`; `src/seon/cluster/run.clj:1325-1385` | **LATENT 3.** | A standalone runtime `require` or namespace change persists the complete SCI dependency set. `canonical-namespace-components` converts every required symbol to `[:seon.ns/name required]`, but `row-tx` checks only function/test namespace refs and creates no companions for namespace requirements. Requiring an SCI/host namespace with no published `:seon.ns/name` row makes the settlement transaction reject, losing the namespace declaration and receipt terminal facts. |
| Context capture, ordinary run refusal, AI attempt/failover, and curation adoption — `src/seon/context.clj:154-200`, `src/seon/cluster/loop.clj:648-680,894-1000,1425-1479`, `src/seon/cluster/curate.clj:292-339` | **Guaranteed.** | Captures/refusals point to the active durable run. Attempt error/result rows use shared tempids. A backup call is made only after the primary attempt/error transaction committed, so `failover-from` necessarily names an existing attempt. Adoption creates its new run first; superseded runs are selected durable rows and run entities are not retracted. |
| Inbound/delivered messages and error facts/messages — `src/seon/cluster/message.clj:260-309,326-431`; `src/seon/error.clj:333-410,792-811,884-939` | **Guaranteed.** | Inbound and delivery builders reject unknown recipients; `about` is resolved to an eid. Production `caused-by` comes from the active run's durable trigger message, and messages are not retracted. Error attribution refs are emitted only after existence checks; error facts and their message `about` refs share a tempid. Agent/run identities are durable. |
| Effect opening — `src/seon/effect.clj:194-208,541-638` | **LATENT 4.** | `request*` pre-pulls and validates the capability owner, then passes `[:seon.fn/sym owner]` through `open-call`; `open-call` checks only effect-id uniqueness. A concurrent `ns-unmap` of that program row between the pull and writer execution leaves a missing owner lookup and rejects the whole effect-open tx. Run and notification-agent refs are durable and are not members. |
| Notes — `src/my/note.clj:135-199` | **Guaranteed.** | The transaction function resolves the scoped agent and optional `about` entity to current eids and refuses absence. Forget resolves the current note eid inside the writer. Transaction metadata names the durable scoped agent. |
| Incremental plan verbs and whole-plan replacement — `src/my/plan.clj:161-220,440-569,593-606` | **Guaranteed under the per-agent serialization contract.** | Incremental verbs resolve agent/parent/needs/about to eids inside transaction functions. Whole-plan compilation uses tempids for all new items and lookup refs only for the calling agent's validated baseline items; one agent's graph runs one turn at a time and no other production surface owns that agent's plan rows. Retractions are ordered after additions/updates. |
| Schedule fire/settle/interrupt — `src/seon/schedule.clj:303-381,402-449,549-649` | **Guaranteed.** | `fire-call` queries the current task, owner, and handler inside the writer and uses their eids; new fire/request/receipt rows share tempids. Its one task lookup names the declaration just queried in the same transaction-function db. Settlement resolves the receipt inside the writer and new result/error rows use tempids. |
| Test result recording — `src/seon/test/runner.clj:876-915,927-946` | **LATENT 5.** | `record-tx` pre-reads whether each test exists. In the “exists” branch it emits lookup-ref `retractAttribute` operations before the result identity row. If an agent removes the test row before commit, the first retract rejects the whole result transaction. The “absent” branch is safe because it emits namespace tempids and the test identity row without those retract lookups. |
| Web/eval inbound submission and render-cost observation — `src/seon/render/web.clj:1617-1650,2069-2084`, `src/seon/eval/drive.clj:96-99`, `src/seon/render.clj:357-367,698-710` | **Guaranteed / no target lookup.** | The web service process is established before it is used as tx metadata; optional user metadata is an existing agent. Inbound message identity checks happen in `message/inbound-tx`. Render-cost rows contain scalar shape/profile facts, not database ref lookup vectors. |

Two frequently suspicious shapes are therefore not latent members. AI
`failover-from` is protected by the explicit “record primary or make no backup
call” fence (`src/seon/cluster/loop.clj:894-907,1467-1479`). Message
`caused-by` accepts a lookup vector, but its only production caller derives the
id from an active run whose trigger already points to a durable message
(`src/seon/cluster/loop.clj:463-508`).

## One reusable primitive

### Recommendation

**Generalize `portable-calls` in place into one pure, schema-aware
`seon.db` tx-preparation helper that rewrites every explicitly paired
`lookup-ref + companion identity row` to one shared string tempid without first
asking whether the row exists.**

The proposed contract is:

```clojure
;; Illustrative contract shape, not a ruled name.
(portable-lookup-refs
 {:seon.db/database-value db
  :seon.db/tx-data tx-data
  :seon.db/companion-rows companion-rows})
=> {:seon.db/tx-data prepared-tx-data
    :seon.db/portable-identities #{[identity-attribute identity-value]}}
   | :seon.error/value
```

The helper must derive unique identity attributes from the handed database
schema, walk only schema-ref positions (including row maps carried as
`:db.fn/call` arguments), index companion rows by their one unique identity,
and assign a deterministic string tempid to each paired identity. It rewrites
**all** paired occurrences and sets the companion row's `:db/id` to that same
tempid even when the identity currently exists; this removes the pre-read race
in `portable-calls`. Duplicate companion identities, a companion without
exactly one identity, or an explicitly requested portable ref without a
companion return an evidence-complete flat error naming every path. The helper
does not invent domain rows: the producer must supply the smallest valid
companion, because an identity-only function/namespace row is sanctioned by
ruling 42b while an identity-only run, message, test, or plan item may be a lie.
Unpaired refs remain the producer's documented durable/in-transaction
guarantee.

This is accretion of the existing mechanism, not a second transact path:
`portable-calls` becomes its first caller (and can disappear once call-specific
row derivation is separated from generic rewriting), while every write still
uses `seon.db/transact!`. Runtime namespace publication can supply name-only
namespace companions through the same helper. Sites whose target must not be
recreated—gate tests, effect owners, and existing test-result rows—should
resolve/validate the target in their existing transaction function and emit an
eid or typed refusal; that is ordinary use of the same transaction, not a
second write mechanism.

The alternatives are weaker:

- A generic transaction-function guard would have to wrap arbitrary existing
  `:db.fn/call` operations to inspect the operations they later return. Making
  it universal either changes the one `transact!` contract substantially or
  creates the forbidden second route; making it optional recreates the routing
  problem.
- A “program-graph census” cannot currently prove this property. The graph has
  calls between functions, not facts describing dynamically constructed
  tx-data and the ref positions returned by transaction functions. A roster,
  naming convention, or regex would violate section 2.2 and could report health
  when a new producer is absent from the census—the exact failure class under
  investigation. A future declared producer fact may check adoption, but it is
  not the safety primitive.

## First-party macro rows

Publication currently excludes macros twice: the first-party function-symbol
set filters `::analyzer/macro` at `src/seon/fn.clj:223-231`, and `var-row`
emits a function row only when the entry is not a macro at
`src/seon/fn.clj:361-363`. The result is not merely missing documentation: a
first-party macro is absent from queries until a runtime call happens to mint a
ruling-42b companion.

### Publish macro identities — recommended

Derive **name-only** `:seon.fn/sym` + `:seon.fn/ns` rows for every first-party
macro during `desired-rows`, using the same population class already used for
external call targets (`src/seon/fn.clj:1728-1768`). This is a small-to-medium
change: expose analyzer macro identities, add them to the derived identity
population, keep namespace-first phase ordering, and add publication/query
regressions. The guarantee is simple: every first-party macro definition has a
program-graph identity on every newly published source branch, independent of
whether static source happened to call it. It shrinks missing call targets and
makes the graph query-complete at the identity level without claiming that a
macro has an ordinary runtime function contract.

Do **not** emit full ordinary function rows for macros as a shortcut. That is a
larger design decision: current full rows imply ordinary source/arglist/private
and optional contract/capability semantics, feed acquisition and call
preparation, and participate in “every function is callable.” A full macro row
needs an explicit queryable macro fact plus audited consumers so compile-time
invocation is not mistaken for an ordinary runtime call. That cost is not
needed to close this rejection class.

### Keep macros unpublished

The immediate code cost is zero, and the generalized portable-ref helper still
prevents a missing macro target from rejecting settlement. The price is a
permanent hole in publication facts: macro identities appear only after a
runtime call mints a companion, program queries differ by cluster history, and
every call-edge producer must continue treating first-party macros as if they
were unindexed external vars. It also saves only the macro-indexing work; it
does not remove the need for the generic primitive because unindexed
core/library calls and runtime namespace requirements remain.

Therefore publication should add name-only first-party macro identities, while
the reusable portable-ref helper remains the class-wide transaction safety
mechanism.
