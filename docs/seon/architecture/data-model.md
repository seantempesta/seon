---
type: architecture
status: active
tags: [architecture, schema, database, agent]
---

# The Seon data model — attributes, connections, and receipts

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Seon's durable model is the admitted EDN population in
`resources/seon/schema.edn`. A database entity is identified by the attributes it
carries and the connections it follows; there are no entity kinds, route rows,
turn rows, or compatibility identities. This document names the durable
families that architecture prose may rely on. Function request/response maps
and process-local host objects remain Malli contracts, not database entities.

[[agent-runtime]] owns the transitions over these facts. [[context]] and [[ui]]
own their projections. [[observability]] owns forensic use of the receipts.

## Modeling laws

- Every stored attribute is declared once in `resources/seon/schema.edn`.
  `seon.schema.datahike/malli->datahike-schema` derives Datahike value type,
  cardinality, uniqueness, indexing, component ownership, and history facets.
- An entity is found by attribute presence and identified by a unique identity
  attribute. No stored `:type`, `:kind`, or status restates its shape or the
  presence of terminal facts.
- A plain `:seon.db/ref` connects independently lived entities. A component ref
  owns its child and cascades retraction. Cardinality-many is an unordered set;
  ordered children carry their own ordinal.
- Stored optional values are absent, never nil. Clearing an attribute is an
  explicit retraction; omitting it from an upsert leaves the current value.
- Transaction provenance is `:seon.db/user` and `:seon.db/process` on the
  transaction entity, plus Datahike's `:db/txInstant`. Domain rows do not copy
  `created-by`, `created-at`, turn, or eval provenance.
- A Clojure symbol stored as a scalar is a value, not a database ref. Program
  relationships use refs to the canonical `:seon.fn`, `:seon.ns`,
  `:seon.schema`, and `:seon.test` entities.
- The cluster JVM reads an immutable database value and calls co-located
  Datahike directly. There is no remote database protocol, replica, or wire on
  the database path.

## The durable graph

One `:seon.cluster/name` entity is the branch root. It connects to the config
singleton, shared instruction rows, and toolkit namespace rows. Every agent is
identified by `:seon.cluster.agent/id`, points to that cluster, may point to one
owned namespace, and points to an open run only while it has work. A message's
`:seon.cluster.message/to` connection wakes that agent's graph. A run points
back to its agent and owns neither its forms nor receipts through a stored
collection: forms and receipts point to the run and are queried by those refs.

A run's form plan is a set of `:seon.cluster.run.form` entities whose ordinals
provide order. Each attempted form has one `:seon.cluster.eval` receipt joined
to the same run and ordinal. The receipt's optional result, error, and
interruption attributes are its state. Context captures and provider attempts
point to the run as independent evidence. Program rows and durable session
definitions belong to the cluster-wide program graph.

The root agent is the ordinary agent with `:seon.cluster.agent/id "root"`.
Root has no parent attribute, lifecycle kind, grant row, or second identity.

## Relationship forms

### Identity attributes

`{:seon.db/identity true}` derives `:db/unique :db.unique/identity`, so a lookup
ref such as `[:seon.cluster.agent/id "root"]` identifies and upserts one
entity. The architecture's durable natural keys are:

| Family | Identity |
|---|---|
| cluster | `:seon.cluster/name` |
| instruction | `:seon.cluster.instruction/id` |
| agent | `:seon.cluster.agent/id` |
| message | `:seon.cluster.message/id` |
| run | `:seon.cluster.run/id` |
| planned form | `:seon.cluster.run.form/id` |
| eval receipt | `:seon.cluster.eval/id` |
| context capture | `:seon.context.capture/id` |
| context contribution | `:seon.context.contribution/id` |
| provider attempt | `:seon.ai.attempt/id` |
| error fact | `:seon.error/id` |
| function | `:seon.fn/sym` |
| namespace | `:seon.ns/name` |
| schema | `:seon.schema/key` |
| test | `:seon.test/sym` |
| durable REPL definition | `:seon.code.def/id` |

The test-observation families add `:seon.test.run/id`,
`:seon.test.result/id`, and `:seon.test.failure/id`. No durable identity exists
for a turn, route, schedule, browser session, interaction, restore record, or
any compatibility agent/eval family.

### Plain and component refs

The central plain connections are:

- `:seon.cluster/config` → config singleton;
- `:seon.cluster/instructions` and `:seon.cluster/toolkit` → shared rows;
- `:seon.cluster.agent/cluster` → cluster;
- `:seon.cluster.agent/namespace` → `:seon.ns`, unique so one namespace has at
  most one owner agent;
- `:seon.cluster.agent/instructions` → additive instruction rows;
- `:seon.cluster.agent/run` → the current open run;
- `:seon.cluster.run/agent` → owning agent;
- `:seon.cluster.run.form/run` and `:seon.cluster.eval/run` → their run;
- `:seon.cluster.run.form/ns` and `:seon.cluster.eval/ns` → canonical namespace;
- `:seon.cluster.message/to` and optional `/from` → recipient and sender agents;
- `:seon.cluster.message/about` → the fact a message assigns or explains;
- `:seon.context.capture/run` and `:seon.ai.attempt/run` → their run; and
- program-graph refs such as `:seon.fn/ns`, `:seon.fn/calls`,
  `:seon.fn.arity/input`, and `:seon.code.def/ns` → canonical program rows.

Component relationships are reserved for owned bounded children. The current
families use them for context contributions, function arities and Malli AST
nodes, namespace alias/import/refer bindings, and test failure detail. A run
does not own forms or eval receipts through a component collection; their
forward run refs are the query and recovery authority.

### Transaction provenance

Every datom names its transaction. Normal Seon transactions add:

| Transaction attribute | Target | Meaning |
|---|---|---|
| `:seon.db/user` | an existing agent or root ref | who submitted the facts |
| `:seon.db/process` | a `:seon.db.process/id` entity | boot, config, or REPL path |

Datahike also supplies `:db/txInstant`. These facts answer who, through which
path, and when without duplicating provenance on domain entities.

## Persistent entity census

The tables below are the architecture-to-schema census. Every attribute named
as a current database fact appears in the cited admitted entity schema.

### Cluster, instructions, and agents

| Entity schema | Persisted attributes | Meaning |
|---|---|---|
| `:seon.cluster/cluster` | `:seon.cluster/name`, `/config`, `/instructions`, `/toolkit` | branch root and shared connections |
| `:seon.cluster.instruction/instruction` | `:seon.cluster.instruction/id`, `/text` | shared rendered instruction |
| `:seon.cluster.agent/context-links` | `:seon.cluster.agent/id`, `/cluster`, optional `/instructions` | cluster and additive context connections |
| `:seon.cluster.agent/agent` | `:seon.cluster.agent/id`, optional `/namespace`, optional `/run` | agent identity, namespace ownership, and current work pointer |

The two agent entity schemas describe attributes on the same open Datahike
entity; they are not competing kinds. Formal creation writes the namespace and
agent rows in one transaction, including `:seon.cluster.agent/cluster`.
Absence of `:seon.cluster.agent/run` means idle. Namespace reassignment is an
ordinary cardinality-one transaction. There are no parent, termination,
default-turn-limit, home-requires, schedule, or agent-status attributes.

AI configuration follows ruling #34 without a second override schema. Every AI
leaf registration marked `:seon.config/per-agent true` contributes the same
attribute ident to the derived agent overlay. Presence on the agent overrides
the cluster value; absence inherits. Effective settings are resolved per model
call and recorded on the attempt as `:seon.ai.attempt/settings-edn`.

### Messages

| Attribute | Shape | Meaning |
|---|---|---|
| `:seon.cluster.message/id` | string identity | stable message identity |
| `:seon.cluster.message/to` | ref | recipient and wake attribute |
| `:seon.cluster.message/content` | non-empty string | message text |
| `:seon.cluster.message/at` | instant | observed send time |
| `:seon.cluster.message/from` | optional indexed ref | sender agent; absence means outside the agent population |
| `:seon.cluster.message/about` | optional indexed ref | assigned or explained fact |
| `:my.message/reason` | optional string | a declination's reader-facing reason |

`to` is cardinality one. Sending to several agents produces several message
rows. Origin, hop count, delivery acknowledgement, browser session, and
read/unread state are not stored. Message-chain bounds derive from transaction
metadata and message relations.

### Runs and forms

| Entity schema | Persisted attributes |
|---|---|
| `:seon.cluster.run/run` | `:seon.cluster.run/id`, `/agent`, `/opened-at`, optional `/closed-at`, optional `/process`, optional `/plan-digest`, optional `/error` |
| `:seon.cluster.run.form/form` | `:seon.cluster.run.form/id`, `/run`, `/ordinal`, `/source`, optional `/ns` |

Run state is presence:

- no `:seon.cluster.run/closed-at` means open;
- `:seon.cluster.run/process` present means held;
- `:seon.cluster.run/plan-digest` present means the form plan is frozen; and
- `:seon.cluster.run/error` means the run closed without a usable plan.

`process` is the non-empty process-identity string used by the cluster run
owner, not a process-record ref. There is no epoch, lease, heartbeat, phase,
turn limit, status, closed-reason, result, or result-ref attribute. A completed
or waiting disposition remains the last eval result and the run closes or
releases from that value.

Forms preserve the reader's order with `/ordinal`; cardinality-many never
pretends to be ordered. The optional namespace ref records the reader namespace
in effect for that form.

### Eval receipts and blobs

| Attribute | Shape | Meaning |
|---|---|---|
| `:seon.cluster.eval/id` | string identity | stable receipt identity |
| `:seon.cluster.eval/run` | ref | owning run |
| `:seon.cluster.eval/ordinal` | nonnegative int | joins the planned form |
| `:seon.cluster.eval/at` | instant | receipt opening time |
| `:seon.cluster.eval/ns` | optional ref | namespace after evaluation |
| `:seon.cluster.eval/result-edn` | optional string | bounded result projection |
| `:seon.cluster.eval/result-blob` | optional SHA-256 digest | full result address |
| `:seon.cluster.eval/result-size` | optional nonnegative int | full serialized size |
| `:seon.cluster.eval/error` | optional string | failure headline |
| `:seon.cluster.eval/interrupted-at` | optional instant | cut by time limit or recovery |
| `:seon.cluster.eval/output` | optional non-empty string | what the form printed |
| `:seon.problems/id`, `:seon.error/kind` | optional values | problem routing evidence |

A receipt carrying none of result, error, or interruption is running. Terminal
settlement asserts one of those facts once; it never stores `ok?`, status, or
error-data mirrors. `:seon.blob/digest` and `:seon.blob/content` are the blob
family used by eval results and durable definitions. The digest is the content
address; capped presentation is derived from `/result-size` and configuration.

The `:seon.eval/*` keys remain only the in-memory SCI admission diagnostic
record: `/fn-entries`, `/host-interop-count`, `/duration-ms`,
`/allocated-bytes`, and `/outcome`. They are not durable eval identities or
receipts.

### Context captures

| Entity schema | Persisted attributes |
|---|---|
| `:seon.context.capture/capture` | `:seon.context.capture/id`, `/run`, `/basis-t`, `/prompt`, `/contributions`, optional `:seon.cluster.run/live-processes` |
| `:seon.context.contribution/contribution` | `:seon.context.contribution/id`, `/position`, `/hash`, `/tokens`, `:seon.render.block/name`, optional `:seon.render/projection`, optional `:seon.error/kind`, optional `/error` |

The capture is committed before the external model call. `/prompt` is the exact
text sent; `/basis-t` identifies the database basis rendered; contribution rows
explain its ordered pieces without copying their text. A failed contribution is
identified by error-attribute presence. No durable turn row is required.

### Provider attempts

One `:seon.ai/attempt` row records each observed model call. Its persisted
attributes are:

- identity and ordering: `:seon.ai.attempt/id`, `/run`, `/ordinal`, `/at`;
- resolved request observations: `:seon.ai/endpoint`, `:seon.ai/model`,
  `:seon.ai.attempt/settings-edn`;
- provider observations: optional `/usage-edn`, `/reasoning`,
  `/reasoning-blob`, `/reasoning-size`, and `/finish-reason`;
- wire-phase evidence: optional `:seon.ai/http-status`,
  `:seon.ai/request-transmitted?`, `:seon.ai/response-started?`, and
  `:seon.ai/output-observed?`; and
- failure/failover evidence: optional `:seon.ai.attempt/error`,
  `/failover-from`, and `/delay-ms`.

Error-ref presence means failure; `failover-from` connects a replacement to the
attempt whose evidence justified it. There is no attempt outcome, provider
role, config digest, outer-timeout, or transport-status mirror. Usage
normalization and retry disposition derive at read.

### Program graph and durable session image

| Entity schema | Persisted attributes |
|---|---|
| `:seon.fn/fn` | `:seon.fn/sym`, `/ns`, `/source`, optional `/arglists`, `/doc`, `/private?`, `/spec`, `/calls`, `/arities`, `/ast`, `/workload` |
| `:seon.schema/schema` | `:seon.schema/key`, `/form`, optional `/created-at`, optional `:seon.db.id/generator` |
| `:seon.ns/ns` | `:seon.ns/name`, optional `/source`, `/doc`, `/requires`, `/aliases`, `/imports`, `/refers` |
| `:seon.test/test` | `:seon.test/sym`, `/ns`, `/source` |
| `:seon.code.def/def` | `:seon.code.def/id`, `/ns`, `/name`, optional `/value-edn`, `/blob`, `/size`, `/source`, `/unrestorable`, plus `/ordinal` |

Function contracts persist twice through one producer: `/spec` retains the
canonical Malli form, while `/arities` and `/ast` point to ordered component
rows shaped from Malli's own parser. Arity rows carry order, arity, min/max,
input/output/guard refs, and their transitive schema refs. AST nodes and entries
carry Malli's parsed keys and explicit ordinals. This makes contract queries
ordinary graph queries without a hand-written parser or second writer.

Namespace alias, import, and refer bindings are owned component rows. They
preserve SCI's effective resolver inputs. `:seon.code.def` stores one current
REPL definition per namespace/name: a replay-safe source, a faithful inline or
blob value, or an honest unrestorable reason. Restore order derives from
`/ordinal`; namespace identity, not an agent-private context, owns the image.

The program graph is live per cluster. Every agent in one cluster calls the
same graph; another cluster has another database branch and SCI context.
Callability is never stored as an allowlist or grant.

### Durable errors

`:seon.error/value` is the flat in-memory error shape:

```clojure
{:seon.error/kind :qualified/rule
 :seon.error/message "What failed."
 :seon.error/data {:qualified/evidence "..."}}
```

`:seon.error/fact` is the durable family. It requires `:seon.error/id`, `/at`,
`/process`, `/kind`, `/message`, `/signature`, `/data-edn`, and `/capped?`.
Optional evidence includes `/class`, `/proc`, `/op`, `/cid`, `/basis-t`, `/run`,
`/agent`, and instrumentation attributes. Error kind names the producer's rule;
it is not an entity discriminator. Recurrence is a query over `/signature`, not
a mutable counter on a singleton.

## Routes are code, not database entities

The one route authority is `seon.render.route/routes`, compiled by
reitit. It declares `/`, namespace and agent pages plus their debug variants,
agent message submission, feeds, `/data`, and static assets. Reverse routing
uses the route names in that table. There is no route schema, route entity,
database route projection, dynamic route owner, or remote route
protocol. Adding a namespace page is adding one route-table line and using the
generic namespace renderer; it does not create a new database family.

## Ruled durable facts

Two rulings require durable facts without authorizing architecture to invent
their attribute identities:

- **Agent prompt fact — ruling #24.** The authentic REPL-session design gives
  each agent one durable prompt fact set by an ordinary function.
- **Per-eval host-interop observation — ruling #32.** Each eval receipt records
  whether its evaluation touched host interop so session-image restore can
  distinguish pure replay from a required cold evaluation.

Their schema owners assign globally identified attributes before transaction
or query code names them.

Ruling #25's blob-backed eval results, rulings #28–#32's namespace-owned
session image, ruling #33's parsed function contracts, and ruling #34's
per-agent AI overlay plus attempt settings use the families above.

## Source authority

- The named family sections in `resources/seon/schema.edn` own the entity,
  cluster, configuration, transaction-provenance, and test-observation facts
  in this census. Sections are editorial; every identity is global.
- `src/seon/cluster/{agent,run,message,loop}.clj*` owns agent creation, run
  transitions, message derivation, receipt settlement, and session-image
  persistence.
- `src/seon/render/{route,transcript,agent,web}.clj` owns the current route
  table and the queries that join agents, messages, runs, forms, and receipts.
- `src/seon/program.cljc` and `src/seon/sci/eval.clj` own program-row identity,
  parsed contract persistence, cluster acquisition, and session restoration.

## See also

- [[architecture]] — system map and vocabulary.
- [[agent-runtime]] — run transitions, per-agent flow graphs, and recovery.
- [[context]] — prompt derivation and context capture.
- [[ui]] — namespace pages and the current route table.
- [[observability]] — receipts, captures, attempts, and errors as evidence.
