---
type: research
status: completed
tags: [research, database, agent]
---

# Local allocation writer configuration audit

## TL;DR

The private generated-id transaction operation belongs in Datahike's existing
single-writer loop. That is the one mechanism that makes candidate preflight,
cross-attribute uniqueness, tempid assignment, and commit atomic on both JVM
and CLJS self-writer connections.

Install it by applying `seon.db.id/allocation-connect-config` to the
configuration passed to `d/connect` only:

```clojure
(d/create-database bare-config)
(d/connect (id/allocation-connect-config bare-config))
```

Do **not** put the decorator in `seon.server.store/config-for`, and do not pass
the decorated config to `d/create-database`. Datahike persists the database's
runtime config on every commit, not only at creation. A direct
`:writer/:write-fn-map` decorator therefore still fails on the first real file
commit because Fressian cannot serialize a live Clojure function. The working
design stores only the namespaced backend keyword
`:seon.db.id.writer/serialized`; its Datahike multimethod injects the private
function into the runtime self writer without changing the DB config.

The first `d/connect` for a `[store-id branch]` must be decorated. Datahike
caches connections under that coordinate and excludes `:writer` from the
existing-connection comparison. A later decorated connect therefore returns
the already-open bare writer rather than upgrading it.

The implementation is colocated in `src/seon/db/id.cljc`: one private write
operation, one namespaced writer backend, one connect-config decorator, and one
fail-fast allocation guard. The JVM wire handler now forwards the manifest
through the ordinary transaction operation; the registry connection and every
allocation-bearing local fixture use the same backend.

## Scope

This audit covers:

- the active CLJS pod;
- the JVM wire-server that is the production write authority;
- local/in-memory connections that execute production functions now being
  migrated to `seon.db.id/allocate!`; and
- the corresponding JVM and CLJS behavioral fixtures.

It does not cover ACME, the paused embedded-JVM flow track, or unrelated tests
whose local Datahike connections never execute allocation code.

## What Datahike actually does

| Source | Observed behavior | Design implication |
|---|---|---|
| `reference-code/datahike/src/datahike/api/impl.cljc:29-41` | A transaction arg-map is forwarded whole to the writer. Unknown namespaced allocation keys are not stripped. | The allocation manifest can ride the normal Datahike transaction arg-map. |
| `reference-code/datahike/src/datahike/writer.cljc:260-267` | `transact!` dispatches the entire arg-map as the argument to writer op `'transact!`. | No parallel allocation transport is needed. |
| `reference-code/datahike/src/datahike/writer.cljc:43-71` | One transaction loop reads an invocation, resolves the operation from `write-fn-map`, and invokes it with the loop's current immutable DB value. | Candidate checks and transaction rewriting are serialized against all ordinary writes only when they run here. |
| `reference-code/datahike/src/datahike/writer.cljc:104-110` | The loop advances from `old` to the returned report's `:db-after` before processing the next invocation. | A second allocation observes the first allocation's result without another lock or registry. |
| `reference-code/datahike/src/datahike/writer.cljc:170-203` | A self writer merges caller-provided operations over `default-write-fn-map`. | Replacing only `'transact!` preserves every other Datahike writer operation. The custom operation must delegate manifest-less transactions to `datahike.writing/transact!`. |
| `reference-code/datahike/src/datahike/writing.cljc:48-180,400-484` | `db->stored` includes the runtime DB `:config`, and every commit writes that stored form to the branch head. | A live function cannot appear in the connected DB config at all. A serializable backend keyword must recreate runtime behavior. |
| `reference-code/datahike/src/datahike/connector.cljc:221-248` | Connections are cached by `[store-id branch]`; the existing-connection comparison normalizes away `:writer`. | First connect wins. There is no supported in-place writer upgrade. |
| `reference-code/datahike/src/datahike/connector.cljc:249-323` | A fresh connection constructs its writer from the caller config, and connect dispatch itself is a writer-backend multimethod. | A namespaced backend can delegate to the stock connector and install a runtime writer without forking Datahike. |

### Live probes

The probes used the pinned fork through `clojure -M:simd:fork-deps` and a
temporary file store outside the repository.

Putting a live function in the connected config failed on the first real
file-backed commit:

```text
Cannot write seon.db.id$transact_with_generated_ids_STAR_... as tag null
```

Bare creation followed by a namespaced-backend connect, allocation, release,
reconnect, and a second allocation succeeded:

```text
:persisted-writer {:backend :seon.db.id.writer/serialized}
:runtime-write-fn-map nil
:allocated-after-reconnect true
```

A bare self-writer allocation against the current fail-fast guard returned
`:seon.db.id.error/unconfigured-allocation-writer` and left the target
attribute empty. This is the required failure posture: no silent commit followed
by a missing-eid error.

A configured self writer was also driven concurrently with the same candidate
under two different managed identity attributes. One transaction committed and
one conflicted; only one AVET contained the value. Same-attribute concurrency
alone is not sufficient evidence because Datahike's ordinary uniqueness already
protects that case.

## Why the writer operation is the only correct boundary

Preflight in a request handler followed by an ordinary `d/transact` is two
operations, even if both are enclosed in `(locking conn)`. Ordinary writes do
not acquire that monitor; they enqueue directly on Datahike's writer. One can
therefore land after the handler's preflight and before its generated
transaction is processed.

Datahike uniqueness only applies within one identity attribute. Seon's policy
is stronger: one generated value must be unused across every generator-managed
identity attribute in the logical DB/branch. An intervening write under a
different managed attribute can violate that policy without triggering a
Datahike `:db.unique` error.

Running `prepare-transaction` from the backend's private `'transact!` operation fixes
the authority mismatch. The operation receives the writer loop's current DB,
performs the cross-attribute AVET checks and rewrite, calls
`datahike.writing/transact!`, and returns the report before the loop advances.
There is no second monitor, queue, or connection registry.

## Smallest one-mechanism design

### Configuration

Keep these responsibilities in `seon.db.id`:

- the private generated-id transaction function is the sole Datahike write
  operation for
  allocation-aware transactions;
- `:seon.db.id.writer/serialized` is the durable backend identity and delegates
  at runtime to Datahike's self writer with that private operation;
- `allocation-connect-config` is the sole pure config decorator, preserves
  ordinary self-writer queue settings, rejects live `:write-fn-map` values, and
  is idempotent; and
- applying it to another writer backend fails loudly. The pod's `:seon-wire`
  peer is not decorated.

Creation uses the serializable bare config. Every Seon-owned self-writer connect
uses the decorated runtime config. Do not add a second `connect` abstraction or
an atom-backed writer registry.

### Transaction flow

Both local and remote allocation use the existing transaction pipeline:

```text
allocate!
  -> seon.db.internal/transact!*
  -> Datahike arg-map with generated manifest
  -> local self writer OR :seon-wire transport
  -> JVM self writer
  -> transact-with-generated-ids!
  -> datahike.writing/transact!
```

The CLJS local path and the JVM wire handler must not call
`prepare-transaction` before enqueueing. They only attach/forward the manifest
and normalize the resulting report or exact conflict.

The current remote wire protocol already carries the manifest in
`src/seon/store/wire.cljs:350-430`; this change does not require a new operation
or envelope.

### Fail-fast proof at runtime

Every real Datahike connection is derefable, so `allocate!` can validate the
runtime writer config before it builds or submits a transaction:

- `:seon-wire` is valid because the authoritative check happens after routing
  to the JVM writer;
- a local connection must name `:seon.db.id.writer/serialized`; and
- any other shape returns a structured core-bug envelope before commit.

The keyword is durable configuration, not a domain fact or security claim. The
operation stays private and uninstrumented because a live writer closes over
that exact function value; instrumentation must not replace the public var and
make a raw identity guard lie. A cold reset remains the supported way to pick
up writer implementation changes.

The JVM wire handler must perform the same shared check before accepting a
generated manifest on a directly supplied connection. This prevents tests or a
future non-registry caller from sending allocation fields through Datahike's
default `'transact!`, which would otherwise ignore the fields and commit an
unprepared transaction.

## Production connection inventory

### Must change

| File and lines | Connection | Required change |
|---|---|---|
| `src/seon/server/registry.clj:223-243` | `create-entry!`, the sole production constructor for ambient and per-cluster JVM connections | Keep `d/create-database cfg` bare. Change only `d/connect` to use `(id/allocation-connect-config cfg)`. This is the production authority. |
| `src/seon/server/registry.clj:401-422` | `fork-verify!` temporary self-writer connection | Decorate the connect too. It is read-only today, but a failed release could otherwise leave a bare first connection cached for the target coordinate. One invariant for every active registry connection is cheaper than a special exception. |
| `src/seon/client.cljs:317-335` | Public `mem-db` REPL/diagnostic constructor | Bare create, decorated connect. A caller can bind the returned conn and execute production allocation verbs. |
| `src/seon/client.cljs:615-630` | `open-agent-conn!`, the canonical isolated test/diagnostic constructor | Bare create, decorated connect. One edit fixes its 33 current test consumers. |
| `src/seon/repl.cljs:117-130` | `ensure-conn!`, the persistent local dev-REPL connection | Bare create, decorated first connect. Without this, allocation verbs in `dev-init!` fail fast. |

### Transaction boundary to simplify

| File and lines | Current role | Required change |
|---|---|---|
| `src/seon/server/wire.clj:479-552` | Manually locks, prepares, commits, and resolves generated transactions in the request handler | Delete the second preparation/locking path. Pass the manifest through the ordinary `d/transact` arg-map on the configured registry conn, then normalize `:seon.db.id/generated-eids` or the exact structured conflict. |
| `src/seon/db/id.cljc:583-667` | Canonical private operation, writer backend, and config decorator | Retain as the one owner. Ensure manifest-less transactions delegate unchanged and durable config carries no function. |
| `src/seon/db/id.cljc:933-955` | Writer-path guard | Retain the early guard; validate the exact namespaced local backend or the remote `:seon-wire` backend. |
| `src/seon/db/internal.cljs:1433-1480` | Normal CLJS transaction path carries allocation fields | Retain. Do not add a separate local allocation commit function. |

### Deliberately unchanged

- `src/seon/client.cljs:632-660` opens the pod's `:seon-wire` DIS peer. Its
  writer is remote and must remain unchanged; the JVM registry connection is
  decorated instead.
- `src/seon/server/store.clj:117-148` must keep returning a serializable bare
  store config because the same value is used for create/delete/fork lifecycle.
- `src/seon/embed/preflight.clj:127-165` is a self-contained embedding probe and
  does not execute allocation.
- `src/seon/db/datahike/conn_process.clj:88-95` belongs to the paused JVM flow
  track and is outside this active cutover.

## Test fixture inventory

### Direct allocation fixtures that must connect with the decorator

These fixtures bypass `client/open-agent-conn!` and therefore need an explicit
`seon.db.id` require plus a decorated `d/connect`. Their `d/create-database`
calls stay bare.

| File and lines | Allocation behavior exercised |
|---|---|
| `test/my/plan_test.cljs:59-80` | `plan/step!`, `plan!`, and `reconcile!` mint compact plan ids. |
| `test/seon/agent/message_test.cljs:30-55` | `agent/message!` mints message ids and, for inbound human messages, linked plan-step ids in the same commit. |
| `test/seon/agent/run_test.cljs:30-50` | `run/open-run!` mints run ids and fences the agent pointer in the same commit. |
| `test/seon/agent/multiagent_test.cljs:38-58` | Uses `run/open-run!` against a direct local conn. |
| `test/seon/agent/ticker_test.cljs:36-56` | Uses `run/open-run!` for ticker/deadline behaviors. |
| `test/seon/agent/ctx/subagents_test.cljs:28-40` | Uses `run/open-run!` while deriving the subagent context block. |
| `test/seon/ctx_test.cljs:66-88` | Uses `run/open-run!` in context derivation coverage. |
| `test/seon/render/chat_test.cljs:152-173` | Uses `agent/message!` to build the real transcript. |
| `test/seon/eval/record_eval_tee_test.cljs:58-73` | Directly exercises `record-eval!`; it must be decorated before eval-id allocation moves into the record commit. |

### Allocation implementation fixtures

| File and lines | Required change |
|---|---|
| `test/seon/db/id_test.cljc:112-133` | The JVM fixture already decorates, but currently passes the decorated config to creation. Split it into bare create plus decorated connect so it proves the persistent-safe lifecycle. |
| `test/seon/db/id_test.cljc:215-221` | Apply the same bare-create/decorated-connect pattern to the CLJS fixture. |
| `test/seon/server/generated_id_transaction_test.clj:53-61` | Decorate the direct server test connection. After the wire refactor, this fixture must exercise the real custom writer rather than the deleted handler-local preparation path. |

### Fixtures already covered by one constructor edit

Thirty-three current test files call `client/open-agent-conn!`. No per-file
writer change is needed after `src/seon/client.cljs:615-630` is fixed. This set
includes the allocation-heavy agent loop/lifecycle/turn tests and the eval
integration tests. The homegrown gym consumers are included in the count but
are retirement targets; do not refactor the gym itself for this change.

### Direct local fixtures that do not need eager migration

Many DB, query, renderer, provider, and schema tests open bare local stores but
never call an allocation verb. They can remain bare. The runtime allocation
guard is the mechanical backstop: if future production code introduces an
allocation into one of them, it fails before any commit and names the missing
writer configuration. A source-text allowlist test for every `d/connect` would
be brittle and would incorrectly outlaw legitimate bare Datahike tests.

## Migration order

1. Keep the private transaction operation, namespaced writer backend,
   `allocation-connect-config`, and allocation-path guard together in
   `seon.db.id`. Make the guard validate the backend and add the
   unconfigured/no-commit behavioral test.
2. Change the JVM registry connects. Keep creation configs bare. Assert the
   configured shape immediately after a newly opened production connection so
   cold boot fails at the constructor, not on the first agent action.
3. Replace `seon.server.wire`'s handler-local lock/preflight with one ordinary
   Datahike transaction carrying the manifest. Preserve only response
   normalization and exact conflict classification.
4. Change the three CLJS local constructors (`mem-db`, `open-agent-conn!`, and
   REPL `ensure-conn!`) to bare-create/decorated-connect.
5. Change the direct allocation fixtures in the tables above. Do not touch
   unrelated direct-Datahike tests.
6. Run the cross-platform allocator tests, affected subsystem tests, full CLJS
   suite, cold restart, and one live remote allocation. Remove any superseded
   local-preparation or handler-lock code in the same commit series; do not
   leave a legacy path.

## Behavioral proof matrix

No assertion below depends on response prose.

| Proof | Failure it falsifies |
|---|---|
| Pure config test: the decorator is idempotent, preserves queue settings, installs the exact namespaced backend, and rejects a live `:write-fn-map`. | The decorator silently destroys writer configuration or permits an unserializable runtime value. |
| Persistent lifecycle test: create a temporary file DB with the bare config, connect with the namespaced backend, allocate, release, reconnect, and allocate again; inspect both committed configs for absence of `:write-fn-map`. | A live function leaked into durable config, or reconnect loses the writer operation. |
| First-connect test: bare first connect is rejected by allocation; after release, decorated first connect succeeds. | A later decorator is incorrectly assumed to upgrade a cached writer. |
| Unconfigured local test on CLJ and CLJS: `allocate!` returns the structured unconfigured-writer error, basis-t is unchanged, and no candidate datom exists. | Default Datahike `'transact!` ignores the manifest and commits before Seon notices missing generated eids. |
| Configured local test on CLJ and CLJS: allocation returns every requested id/eid and refs resolve to those exact eids. | The custom writer is installed but does not preserve the allocation protocol. |
| Cross-attribute concurrency test: force the same candidate into two simultaneous allocations targeting two different managed identity attrs; exactly one commits. | Preflight still occurs outside the serialized writer. |
| Manifest-less transaction test through a configured writer. | Replacing `'transact!` broke ordinary Datahike writes. |
| Direct JVM wire-handler test on a configured conn, plus an unconfigured-conn rejection test. | The server still relies on handler-local preparation or silently accepts a manifest through the default writer. |
| Constructor tests for `client/open-agent-conn!`, `client/mem-db`, REPL dev conn, and registry memory conn report the configured runtime shape. | A production/local constructor was missed. |
| Live cold proof: reset/restart the default cluster, mint an agent, create a plan step, send a message, and open a run; query all four generated identity attrs and the returned eids. | Unit fixtures pass while the real wire-server registry remains bare. |

The full CLJS suite then becomes a broad behavioral tripwire. Any missed
allocation-bearing direct fixture fails before commit with the structured
writer-configuration error, making the omission local and diagnosable rather
than corrupting state.

## Conclusion

Do not build a local allocator, a second lock, or a test-only transaction
adapter. Datahike already owns serialization. Seon only needs to install its one
custom operation at self-writer connect time, carry the manifest through the
normal transaction path, and fail before commit when that runtime operation is
absent.
