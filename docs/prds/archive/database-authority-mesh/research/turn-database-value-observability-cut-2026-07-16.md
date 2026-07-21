---
type: research
status: complete
tags: [research, database, agent]
---

# Turn transaction-reference observability cut — 2026-07-16

## Corrected decision

Ordinary `:seon.db/db` maps are request-scoped execution context. Do not store
them as turn, provider-attempt, render, function, schema, or test facts.

Persist one native Datahike transaction-entity ref on the turn:

```clojure
(schema/register! :seon.agent.turn/rendered-tx :seon.db/ref)
```

Its value is the basis transaction id (`:t`) of the ordinary database value
captured before prompt rendering. Delete the four current turn coordinate
attributes. Provider attempts already belong to the turn through the component
ref `:seon.agent.turn/llm-attempts`; they use the turn's `rendered-tx` and do
not repeat database identity. Delete the four attempt coordinate attributes
without replacing them.

This corrects the earlier version of this report, which proposed persisting an
EDN-encoded database descriptor. That would have made request context into a
domain fact and hidden its fields from Datahike. The transaction ref is
smaller, queryable, uses Datahike's own name and identity, and needs no storage
codec or parallel representation.

Functions, schemas, tests, renders, turns, and attempts remain ordinary
entities connected by refs. The child receives a `:seon.db/db` map only while
executing a request.

## Why one transaction ref is sufficient

Every committed Datahike transaction is an entity whose entity id is the
transaction id and which carries `:db/txInstant`. Datahike `as-of` accepts a
numeric transaction id and includes datoms through that transaction
(`reference-code/datahike/src/datahike/db.cljc:143-147` and
`api/impl.cljc:153-182`). Seon's strict temporal validation already proves an
exact transaction with an EAVT seek on `[t :db/txInstant]`, with only the empty
origin `tx0` exempt (`src/seon/db/coordinate.cljc:90-118`).

A turn is committed after its rendered transaction. Therefore any database
lineage that contains the turn also contains the transaction it references.
A native branch copied after the turn preserves those transaction entities and
ids. If a restore removes that lineage, it also removes the turn; there is no
surviving turn fact whose descriptor must point into an unrelated lineage.

Exact prompt reconstruction is consequently:

1. pull the turn and its `rendered-tx` ref from the selected cluster database;
2. capture the current ordinary database value for that database;
3. issue prompt reads with that value's `:as-of` set to the referenced
   transaction id; and
4. run the same child prompt owner with that request-scoped value.

Append-only transaction semantics make the facts at that cut identical even
though the current containing commit is newer. The prompt blob remains the byte
ground truth when render code changes.

## When commit identity is needed

Commit identity is authority/lifecycle data, not a field every turn must
duplicate.

Datahike records `:max-tx`, commit UUID, parents, and index roots in each
immutable commit. Seon's existing transaction-origin walk finds the earliest
retained commit that introduced a transaction; branch/force metadata commits
may repeat `max-tx`, so numeric equality alone is intentionally insufficient
(`src/seon/db/registry.clj:970-1035`). The authority can use that mechanism
when a forensic workflow needs to fork exactly at the transaction's original
commit.

Do not persist a commit UUID on the turn merely to avoid this on-demand lookup:

- normal reconstruction only needs `as-of rendered-tx` on a lineage that
  already contains the turn;
- turn diff needs two transaction ids, not two commit descriptors;
- provider evidence uses the same turn transaction ref;
- debug association can compare refs directly in Datalog; and
- forking is rare lifecycle work where the authority's retained commit graph
  is the correct owner.

If the transaction-origin operation is removed during the ordinary database
protocol cleanup, retain its graph walk as an internal authority function used
by branch creation. Do not replace it with a persisted turn descriptor.

## Dependency ledger

| Owner | Selected revision | Source-grounded constraint |
|---|---|---|
| Seon | `ab98d70f28705386caa82ae7c4eb25b1e74a0299` plus active execution work | `:seon.db/db` is the closed request map in `src/seon/db/protocol.cljc:213-229`; execution messages are migrating to it. |
| Seon schema | same revision | `:seon.db/ref` is the one application ref schema. No map encoding or new entity is required. |
| Datahike | `a464cd887458d2572414a6ea951c477b0981fdae` | Numeric `as-of` filters datoms by transaction id; every ordinary transaction has a `:db/txInstant` entity datom. |
| Datahike commit graph | same revision | Immutable commits retain parents and `max-tx`; origin resolution must distinguish ordinary transaction commits from branch metadata commits that repeat `max-tx`. |
| ClojureScript | `1.12.145`; checkout `946d75f3483c0c8e784e6668bff2c71a25619a77` | Ordinary database maps cross Transit only for the duration of prompt/eval requests. |

## Current namespace and REPL mode

The storage correction does not change the execution finding: the child prompt
owner must return the exact current namespace and REPL mode acquired at the
same request-scoped database value.

`run-turn-body!` currently calls local-value-era `ctx/repl-mode` and
`ctx/current-ns` after prompt rendering. Strengthen the existing
`seon.execution.runtime/render-prompt!` acquisition instead:

1. add `:seon.config/repl-mode` to its cluster-config pull member;
2. add/reuse the latest-successful-eval query already present in
   `src/seon/agent/ctx/transcript.cljs:774-786`;
3. return `:seon.config/repl-mode` and `:seon.eval/starting-ns` with the
   rendered prompt; and
4. pass them to provider/eval without another database read.

This uses the current program graph and namespace ordering. It does not replay
forms, create cursor state, or persist execution context.

## Exact source inventory

### Primary cut

| Path | Required change |
|---|---|
| `src/seon/agent/turn.cljs` | Register `rendered-tx`; delete eight turn/attempt coordinate schemas and conversion helpers; thread request `:seon.db/db`; store only its `:t` ref on the turn; derive attempts through their parent turn. |
| `src/seon/execution/runtime.cljs` | Return current namespace and REPL mode from the existing prompt acquisition. |
| `src/seon/execution.cljs` | Finish exact request-scoped database-value invocation and grouped-member handling. |
| `src/seon/execution/host.cljs` | Finish byte-equal invocation/result database-value validation. |
| `src/seon/client.cljs` | Bootstrap `:seon.agent.turn/rendered-tx`; remove the four attempt coordinate attrs. |

### Downstream consumers

| Path | Required change |
|---|---|
| `src/seon/agent/debug.cljs` | Pull `rendered-tx`; construct request database values with `as-of`; turn diff uses transaction ids; error/turn association joins and orders by `rendered-tx`. |
| `src/seon/repl/autocomplete.cljs` | Resolve the turn's ref to an as-of request value and invoke the existing prompt owner twice; do not replay evals. |
| `src/seon/web/serve.cljs` | Pull turn `rendered-tx`; derive attempt database evidence through the parent turn; project transaction id rather than reconstructing coordinate maps. |
| `src/seon/agent/AGENTS.md` | State one request-scoped database value per turn and one persisted rendered transaction ref. |
| `docs/seon/architecture/observability.md` | Replace persisted `rendered-db` language with `rendered-tx`; describe request-time as-of resolution and on-demand commit-origin resolution for forks. |

Program entities in `:seon.fn`, `:seon.ns`, `:seon.schema`, and `:seon.test`
do not change in this cut. Rendering continues to receive entity/ref-based
domain inputs plus a request-scoped database value only at its execution
boundary.

### Focused tests

- `test/seon/agent/turn_test.cljs`: exact request map reaches prompt and eval;
  the response echoes that map, but it is not persisted.
- `test/seon/agent/turn_capture_test.cljs`: the turn stores one valid ref to
  the pre-render transaction; an interleaving write does not change it;
  current namespace persists without form replay.
- `test/seon/agent_retry_test.cljs`: attempts contain no database/coordinate
  fields and all derive the parent turn's `rendered-tx`.
- `test/seon/agent/debug_test.cljs`: turn reconstruction uses the ref as an
  as-of cut and turn diff compares transaction ids.
- `test/seon/repl/autocomplete_test.cljs`: export performs two byte-stability
  child calls at the referenced transaction.
- `test/seon/web/serve_test.cljs`: provider config and stream mode are
  rederived at the parent turn's transaction; evidence contains no attempt
  coordinate copy.
- `test/seon/execution_test.cljs` and
  `test/seon/execution/host_test.cljs`: request-scoped ordinary database values
  remain exact through child IPC.

## Shortest falsifiers

1. Transact a turn with `rendered-tx = (:t database)`. Pull the ref and its
   `:db/txInstant`; both must exist and no coordinate/database-map attribute may
   be installed.
2. Advance the database, query prompt inputs through an ordinary value with
   `:as-of rendered-tx`, and compare them with inputs captured before the
   advance.
3. Fork after the turn. The copied turn's `rendered-tx` must resolve to the
   same prompt inputs on both branches.
4. Restore before the turn. The turn must be absent; no surviving domain fact
   may point into the discarded lineage.
5. Resolve the transaction's origin through the retained commit graph and fork
   there. Branch metadata commits that repeat `max-tx` must not be selected as
   the transaction origin.
6. Retry twice across intervening commits. Attempts must carry no database
   identity and historical provider validation must use their parent turn's
   one ref.
7. Source/schema reachability must find none of the eight retired coordinate
   attrs and no persisted `:seon.agent.turn/rendered-db` or
   `:seon.ai.attempt/db`.

## Ordered no-compatibility implementation cut

1. Finish execution IPC with request-scoped `:seon.db/db` and focused proof.
2. Extend the existing prompt acquisition with current namespace and REPL
   mode.
3. Replace turn coordinate storage with `rendered-tx`; remove attempt database
   identity entirely; update turn/capture/retry tests.
4. Migrate debug and autocomplete to transaction-ref as-of resolution.
5. Migrate web provider/turn evidence to the parent turn ref.
6. Update schema bootstrap, localized instructions, and observability
   architecture; delete all retired attrs/helpers without old-reader fallback.
7. Run focused suites, branch/restore falsifiers, source/schema reachability,
   and the relevant complete CLJS checkpoint.

## Exit condition

One turn executes against one ordinary database value and persists one ref to
that value's basis transaction. Attempts derive it through their parent turn.
Prompt reconstruction uses Datahike `as-of`; an exact forensic fork resolves
the transaction's origin through the authority's retained commit graph. No
database descriptor, coordinate decomposition, compatibility path, or duplicate
attempt identity is stored, and functions, schemas, tests, renders, turns, and
attempts remain entities connected by refs.
