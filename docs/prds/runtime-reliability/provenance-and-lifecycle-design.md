---
type: decision
status: draft
tags: [decision, database, flow, agent]
---

# Minimal transaction provenance and explicit runtime lifecycles

## Design objective

Use Datahike as intended: state is the accumulating set of attributes on linked
entities, and every datom already points to the transaction that asserted it.
Persist only facts that make necessary queries possible. Derive state, status,
operation meaning, and summaries from the graph whenever their processing cost
is acceptable.

This design is intentionally not an authentication or authorization system. It
does not assign owners or permissions to domain entities. Future security may
validate authenticated actors before submission, but provenance only records
what asserted committed facts.

## The minimum missing fact

Datahike supplies a transaction entity and `:db/txInstant`. Domain entities
supply their normal links to agents, turns, evals, messages, and runs. The
missing fact needed for provenance and scoped reconciliation is:

```text
datom → transaction → actor
```

The fresh schema starts with only two proposed attributes:

| Attribute | Shape | Purpose |
|---|---|---|
| `:seon.actor/id` | unique keyword identity | Lookup stable system actors |
| `:seon.tx/actor` | cardinality-one ref | State which entity asserted the transaction's datoms |

Initial candidate system actors:

- `:seon.actor/root` — explicit administrative and root seed changes;
- `:seon.actor/boot` — facts derived from compiled core code and required core
  seed data;
- `:seon.actor/config` — facts derived from configuration.

Config is distinct from boot because its desired set changes independently and
must be reconciled independently. Agent-originated transactions should point to
the agent entity directly if the common ref schema permits it; do not create a
duplicate actor row merely to restate agent identity.

The exact actor list remains draft until the transaction inventory proves the
queries. A migration actor or human actor is added only when a real operation
cannot be represented by root or an existing durable entity.

## Facts deliberately not stored

Do not initially add:

- entity owner or manager;
- entity kind;
- transaction operation/type/status;
- generation or projection labels;
- transaction summaries or counts;
- duplicate timestamps;
- runtime-instance identity;
- request, turn, eval, message, or agent ids repeated on the transaction when
  ordinary asserted entities already link them;
- causal prose;
- authorization roles, permissions, credentials, or principals.

An additional transaction attribute requires a concrete query, evidence that
the existing graph cannot answer it at acceptable cost, and a clear reason it
is not a derived classification.

## Provenance queries we know we need

### Assertions derived from core code

Find current datoms whose transaction references the boot actor. This bounds
the current core reconciliation candidates without treating every program
entity as core data.

### Assertions derived from configuration

Find current datoms whose transaction references the config actor. Config can
then add, change, or remove its facts without scanning agent-authored data.

### Assertions made by an agent

Join a datom's transaction through `:seon.tx/actor` to `:seon.agent/id`. This is
useful for debugging, UI recency, and protecting agent-authored program data
from boot reconciliation.

### Previously asserted facts no longer current

For deleted or replaced identities, run the same actor-constrained query over
Datahike history. It must also constrain known identity and relevant attribute
sets. A generic scan over all live datoms and their earliest visible
transactions is neither required nor correct.

These queries must be proven manually against `reference-code/datahike` and a
fresh in-memory database before the schema is ratified.

## Provenance is per datom

An entity can accumulate facts asserted by different actors. That is a feature,
not a modeling failure. Reconciliation therefore compares and retracts specific
attributes/datoms rather than declaring that one process owns an entire entity.

For a mixed-origin entity:

- boot reconciliation changes boot-derived attributes;
- config reconciliation changes config-derived attributes;
- agent/root assertions survive unless an explicit domain operation removes
  them;
- `retractEntity` is used only when the remaining graph and component semantics
  prove the complete entity may disappear.

Cardinality-one conflicts require an explicit domain rule because the current
datom records the last assertion, not permanent ownership. The transaction
inventory must identify whether such conflicts occur before a generic policy is
written.

## Exact reconciliation is data processing

Each reconciliation process knows:

- its actor;
- the identity attributes it may produce;
- the attributes calculated by its input;
- its complete desired value;
- the relevant current/historical datoms asserted by that actor.

Pure functions calculate:

```clojure
{:seon.state/additions [...]
 :seon.state/changes [...]
 :seon.state/retractions [...]
 :seon.state/tx-data [...]}
```

The production path does not transact against an immutable copy merely to diff
the before and after databases. `datahike.api/with` remains a valuable oracle in
tests: apply the compiled transaction to a database value and verify that the
resulting facts equal the intended state.

When `:seon.state/tx-data` is empty, do not call `db/transact!`. Datahike still
creates transaction metadata and `txInstant` datoms for a metadata-bearing
empty transaction, advances the transaction log, and wakes listeners.

## Lifecycle responsibilities

### Cold cluster boot

Open the database; load configuration; build one program snapshot; install
missing attribute schema; calculate and transact nonempty core/config deltas;
replay durable agent-authored code into the fresh runtime; instrument; install
global services once; resume persisted runnable agents.

### Warm pod restart

Perform the same process-local reconstruction. When source and config are
converged, core/config reconciliation emits no transactions. Replay and
instrumentation remain necessary because the JavaScript runtime is new.

### Configuration change

Calculate and transact only the exact config delta. Normal database listeners
react. Do not rebuild core source, replay code, reinstrument, or restart global
services.

### Core hot reload

Build one new source snapshot, reconcile changed/deleted core facts, and
instrument changed or unwrapped live function objects. Do not mint or resume
agents.

### Agent mint

Create the agent and initial agent-local context, establish its namespace and
wake mechanism, and start one host. Do not seed, prune, replay all code,
instrument globally, or reinstall services.

### Agent resume

Pull and validate an existing agent, reconstruct its transient host, and
continue from durable run/FSM facts. Do not mint it or overwrite initial state.

### Agent eval

Evaluate in the agent namespace, persist the eval/result and any newly authored
program facts with the agent as transaction actor, and instrument only new or
redefined functions.

## Current system to audit, not preserve by default

The existing transaction context carries agent, session, turn, eval, origin,
replay, and resume-marker metadata and derives a broad origin classification at
the database boundary. Some fields may support irreplaceable forensic queries;
others may duplicate links already present in ordinary entities. Treat the
whole shape as evidence to inventory, not a compatibility contract.

For every current field, record:

1. where it is written;
2. where it is queried;
3. whether the same answer follows from normal datoms and refs;
4. the measured cost of deriving that answer;
5. whether deletion would lose a necessary historical distinction.

Keep only proven facts, migrate callers atomically, and delete the old origin
classification rather than maintaining two provenance systems.

## Graduation criteria

This design becomes an architecture decision only after:

- all current transaction writers and readers are inventoried;
- actor lookup and current/history queries are proven in Datahike;
- mixed-origin attribute behavior is specified from real cases;
- the minimal schema supports core, config, root, and agent transactions;
- a converged reconciliation produces no transaction;
- stale core/config facts are removed without a separate pruning/healing path;
- agent-authored facts survive those reconciliations;
- no retained metadata field lacks a named consumer query.
