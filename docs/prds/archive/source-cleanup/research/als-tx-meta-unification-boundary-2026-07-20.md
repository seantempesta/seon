---
type: research
status: active
tags: [prd, database, architecture]
---

# ALS transaction-metadata unification boundary

## Decision

The FOLD row is real, but its original rename is no longer semantically
correct. `seon.db.internal` still has two overlapping ambient carriers:
`tx-context` and `agent-context`. They must become one operation-context
`AsyncLocalStorage`. The third construction, `read-evidence-context`, is not a
duplicate carrier: it owns a fresh mutable collector whose lifetime and return
value are deliberately narrower than the operation context. Keep it separate.

Do **not** mechanically rename `with-tx-context` to `with-tx-meta`. The current
map is demonstrably not transaction metadata. It carries an immutable database
value, the full decoded configuration singleton, eval namespace, current turn,
branch head, a process-local commit callback, and a test-runner flag in addition
to the two durable provenance refs. Datahike transaction metadata is the much
smaller registered projection that crosses the writer boundary. Calling the
whole carrier `tx-meta` would erase that boundary and invite process-local
values onto transaction entities.

The corrected in-place cut is:

- one `operation-context` ALS in `seon.db.internal`;
- `current-operation-context` and `run-with-operation-context` as its one
  generic map face;
- `with-agent` associates `:seon.agent/id` in that same map;
- `without-agent` runs with only `:seon.agent/id` dissociated, preserving the
  pinned database value, configuration, provenance override, and other outer
  operation facts;
- `current-agent-id` reads `:seon.agent/id` from the map; and
- transaction submission derives only registered transaction metadata from
  the operation map, with `:seon.db/user` and `:seon.db/process` remaining the
  durable provenance vocabulary.

This is one mechanism, not a compatibility layer. Rename every
`current-tx-context`/`with-tx-context` caller atomically and delete the old
symbols in the same commit. Amend and close
[[../../../seon/issues/als-unify-tx-meta]] against this corrected vocabulary;
its intended carrier collapse is preserved, while its obsolete assertion that
the ambient map "IS the tx-meta" is rejected by current source evidence.

## Snapshot and dependency ledger

Audit snapshot: `f797a8ef76258b96a571abc8be6512591c097bc7` on 2026-07-20.
The checkout also contains active, uncommitted U4 host/context work; those paths
were read but not edited.

| Dependency or mechanism | Selected source | Contract used here |
|---|---|---|
| Bun async context | Bun 1.3.14, `node:async_hooks` compatibility surface | `.run` scopes descendants without mutating sibling fibers; the executable proof is [[als-config-probe-2026-07-20]] |
| Node comparison runtime | Node 26.4.0 | The same probe observed the same `.run`/`.enterWith` distinction |
| Datahike | `reference-code/datahike` at `6f2569087ed3` | `db/transaction.cljc:830-849` turns every tx-meta entry into a datom and rejects an attribute absent from system/installed schema |
| ClojureScript | 1.12.145; vendored read source at `946d75f3483c0c8e784e6668bff2c71a25619a77` | Native Promise/await descendants are the operation fibers that ALS must isolate |
| Seon ambient owner | `src/seon/db/internal.cljs:16-78` | Three ALS instances exist; only `tx-context` and `agent-context` overlap |
| Seon transaction boundary | `src/seon/db.cljs:896-932` and `src/seon/db/internal.cljs:493-500` | `transact!` validates all tx-data and tx-meta attributes and values before transport, then returns failures as ordinary error data |
| Existing operation precedent | `src/seon/execution/runtime.cljs:583-605` | One operation already scopes agent, configuration, and database-derived inputs around descendants |
| U4 JVM host | active `src/seon/host/context.clj`, `src/seon/host/record.clj`, and `src/seon/host.clj` edits | JVM sci invocations bind agent identity and stamp the same user/process refs directly on writer requests; reread after U4 commits |

The Datahike source is decisive: tx-meta is persisted data, not a synonym for
an ambient execution map. `flush-tx-meta` emits one `:db/add` for every entry.
Seon's current `validate-attrs!` plus `validate-values!` call at
`db.cljs:923-927` is therefore the safety boundary that must remain before the
wire request.

## Surviving duplication and single owner

At the snapshot, `src/seon/db/internal.cljs` constructs:

1. `tx-context`, whose map is read by database selection, read policy,
   instrumentation injection, turn/eval state, and transaction provenance;
2. `agent-context`, whose string is read by authorization, instrumentation,
   read attribution, and transaction provenance; and
3. `read-evidence-context`, whose atom collects read dependencies and is
   returned alongside one computation's value.

The duplication is exactly items 1 and 2. They represent one operation but can
currently be entered independently and are recombined by
`selected-provenance`/`merge-tx-context-into-opts`. Item 3 has different data,
lifetime, nesting, and output semantics. Folding it would couple an
invocation-local collector to every operation and would break the proven
concurrent-evidence isolation in `db_remote_contract_test.cljs`.

`seon.db.internal` owns the sole carrier and the pure projection from operation
context to transaction metadata. `seon.db` owns only the public scoped
functions and transaction error-value boundary. No host, eval, turn, web, or
instrumentation namespace may construct another ALS or project provenance for
the CLJS client.

The JVM host is a separate process/runtime boundary, not a second CLJS ambient
mechanism. U4 currently binds `seon.host.context/*agent-id*` around sci eval and
stamps `:seon.db/user`/`:seon.db/process` onto direct writer protocol requests.
That code must converge on the same **data contract**, but it must not import a
JavaScript ALS abstraction.

## Operation-context and transaction-metadata contracts

The operation carrier is process-local immutable data. Its registered schema
must be a closed map of the actually supported optional keys after the Stage 4
inventory, including at least:

- `:seon.agent/id` — current agent identity string;
- `:seon.db/user` and `:seon.db/process` — optional provenance overrides;
- `:seon.db/db` — the operation's pinned ordinary database value;
- `:seon.config/configuration` — the full decoded singleton at that same
  database value;
- `:seon.eval/ns`, `:seon.agent.turn/current-id`, and
  `:seon.db/branch-head` — existing operation facts; and
- `:seon.db/on-commit!` and the test-runner flag — explicitly registered
  process-local values if they still survive the post-U4 inventory.

Do not use a bare `:map` to conceal unknown keys. Each surviving key is
namespaced and registered in its real owner; optionality is absence, never nil.
`run-with-operation-context` validates the entered delta and merges it with the
outer immutable map before `.run`. Validation failure at an internal/core
operation boundary is a core bug; an agent-facing database call still catches
it at `transact!` and returns the canonical `:seon.error` value.

The transaction-metadata projection has a distinct contract:

- explicit `:seon.db/tx-meta` remains a heterogeneous registered-attribute map;
- every key must be qualified and registered, and every value must satisfy that
  key's registered Malli schema before transport;
- explicit non-Seon metadata may survive, while callers cannot spoof Seon's
  selected provenance;
- `:seon.db/user` is selected from an explicit operation override, otherwise
  from `:seon.agent/id`, otherwise the existing human fallback;
- `:seon.db/process` is selected from an explicit operation override,
  otherwise the existing REPL fallback; and
- no database value, configuration, namespace, callback, branch head, turn ID,
  or evidence collector can enter the protocol transaction-meta map.

The merge/projection function should be named for its result, for example
`operation-context->tx-meta`, and remain pure. `transact!` then merges the
explicit request metadata under that selected provenance and performs the
existing attribute/value validation. Do not add a second validator or depend
on the writer's later Datahike rejection as ordinary control flow.

## Required post-U4 reread

This unit is not source-ready until U4 commits and releases its host/database
paths. At the implementation HEAD, reread and re-search:

1. `src/seon/host/context.clj`, `src/seon/host/record.clj`, and
   `src/seon/host.clj` for every read/write provenance field and direct
   transaction request;
2. `src/seon/db/id.cljc` and its callers for any new metadata or idempotency
   receipt attached to transaction requests;
3. all `with-tx-context`, `current-tx-context`, `with-agent`, `without-agent`,
   and `current-agent-id` call sites in `src/` and `test/`;
4. all `::db/db`, configuration, eval namespace, turn ID, branch-head,
   on-commit, and test-runner keys read from the ambient map; and
5. every `AsyncLocalStorage` construction in `src/seon/db/internal.cljs`.

U4's current diff adds first-class eval/corpus transactions in the JVM host and
directly stamps provenance. A stale audit could miss a new metadata key or
misstate who owns recording. The post-U4 reread is an implementation
precondition, not optional verification.

## Dependency order

1. U4 commits/releases the active host/context/database paths and closes its
   retained `u15` branch in its own integration pass. This audit does not touch
   or close that state.
2. Stage 2's atomic process-term rename completes under its own freeze; this
   Stage 5 fold must not expand that rename boundary.
3. Stage 4 lands per-operation pinned database-value plus full-configuration
   propagation and deletes boot `enterWith` only after all operation boundaries
   are covered. This establishes the final carrier population.
4. Reinventory the post-U4/post-Stage-4 carrier and host provenance contract.
5. In one source-atomic Stage 5 commit, introduce the one operation ALS, move
   agent scope into it, preserve `without-agent`, rename every carrier API and
   caller, delete the old ALS/symbols, update localized authority, and close the
   issue note.
6. Run focused async/database/operation tests, then include the result in the
   program's frozen full-suite and live-cluster gates.

If the same Stage 4 owner elects to collapse the carrier while populating every
boundary, the changes may land together only with the combined proof below.
Otherwise configuration propagation lands first. There is no safe intermediate
state where some callers use a new carrier while others depend on the old ALS.

## Focused falsifiers

The implementation is rejected unless the smallest focused gate proves all of
these behaviors:

1. Two `Promise.all` operations with different agent IDs, pinned database
   values, configurations, and namespaces retain their own values across
   awaited, `.then`, nested async, and timer descendants, with no cross-talk.
2. A nested operation delta inherits outer keys; nested `with-agent` changes
   only identity; nested `without-agent` observes no agent but retains the
   outer database/configuration; returning from either restores the exact outer
   map.
3. A transaction under each concurrent scope emits exactly its selected
   `:seon.db/user` and `:seon.db/process`. The wire request contains no other
   operation-context key.
4. A registered custom tx-meta attribute survives and is stored. An
   unregistered key or invalid value returns the canonical user-input error
   value and sends zero transaction requests. Nothing throws or rejects into
   the agent loop.
5. Read attribution and write provenance select identical user/process refs for
   one operation.
6. Existing `with-read-evidence` concurrent scopes remain isolated and retain
   their exact returned values; the evidence collector is still a distinct
   ALS.
7. Instrumentation injection obtains agent ID, full configuration, and eval
   namespace from one operation map, while an explicit ordinary function
   argument retains the existing precedence rules.
8. A scoped commit observer fires only for its own raw-form transaction and is
   never serialized as tx-meta.
9. A source sweep finds one operation ALS and one read-evidence ALS in
   `seon.db.internal`, no `agent-context`, no `tx-context`, and no old public
   carrier symbols or compatibility aliases.

The existing focused owners are
`test/seon/db_remote_contract_test.cljs`,
`test/seon/instrument_inject_test.cljs`,
`test/seon/execution/runtime_test.cljs`, and
`test/seon/eval/receipt_test.cljs`. Add assertions to those mechanisms rather
than creating another runner.

## Live falsifiers and graduation evidence

At a frozen source HEAD and ready default cluster:

- run two real overlapping operations for distinct agents and observe their
  database/configuration/identity values remain distinct after an await;
- transact one fact from each, then query the datoms' transaction entities and
  prove the exact user/process refs;
- apply a changed database query ceiling and prove the next operation observes
  it without restart while an already-entered operation retains its pinned
  configuration;
- drive one JVM-host sci invocation after U4 and prove its recorded eval/corpus
  transaction carries the same provenance vocabulary as the CLJS operation;
- verify an invalid tx-meta key returns an ordinary error value and the client,
  host, and writer remain ready; and
- inspect current-generation logs for no unhandled rejection or ambient-context
  leakage.

Focused greens are necessary but cannot close the row: the live overlap and
datom query are what prove async inheritance plus durable projection end to
end. These proofs then ride the program's final twice-frozen three-suite and
live-cluster graduation checkpoint.
