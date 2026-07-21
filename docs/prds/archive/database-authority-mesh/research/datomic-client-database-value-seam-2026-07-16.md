---
type: research
status: complete
tags: [research, prd, database, decision, flow]
---

# Datomic Client database-value seam

## Decision

Delete Seon's `coordinate` abstraction and make the Bun-facing value of a
database an ordinary map with the same observable keys as a Datomic Client
database value:

```clojure
{:db-name "default"
 :t 536870916
 :as-of nil
 :since nil
 :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

```

The first five keys are the established Datomic Client vocabulary. The single
Datahike extension is necessary because this maintained Datahike supports
branches, retained commits, restore, and batched commits. In those cases
`[:db-name :t]` does not uniquely identify the immutable Datahike value that
must answer a retry. Use Datahike's existing persisted metadata key
`:datahike/commit-id`; do not rename it or expose Datahike's process-local
connection/generation cache identity.

This is a semantic replacement, not an aliasing migration:

- `:db-name` replaces the repeated logical database name plus attachment as the
  public route to one database;
- `:t` is the basis transaction of the containing committed database value;
- `:as-of` and `:since` describe temporal filters without overloading `:t`;
- `:history` says whether assertions and retractions are visible; and
- `:datahike/commit-id` identifies the containing retained Datahike commit when
  the Datahike authority needs exact lineage.

The JVM continues to hold native Datahike values. Bun holds only the ordinary
map and passes it back to `seon.db` operations. A future authority can implement
the same Datomic-shaped behavior without constructing a Datahike record.

This decision supersedes the keep-coordinate conclusions in
[[strict-temporal-coordinate-seam-2026-07-16]] and
[[remote-seon-db-contract-freeze-2026-07-16]]. Those reports remain useful for
their source probes, batching evidence, and consumer inventories, but their
public database-value shape is no longer the target.

## Dependency ledger

| Owner | Exact selected source | Relevant fact |
|---|---|---|
| Datomic Client | `com.datomic/client` 1.0.146, official `datomic.client.api` documentation | `d/db` returns a database value supporting lookup of `:db-name`, `:t`, `:as-of`, `:since`, and `:history`; database values are passed to query, pull, and index APIs. |
| Datomic filters | Official Database Filters reference | A filter accepts and returns a database value. The same query and index traversal works against current and filtered values. |
| Maintained Datahike | `org.replikativ/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f`, selected in root `deps.edn` and mirrored at the same SHA under `reference-code/datahike` | `db`, `as-of`, `since`, `history`, `commit-id`, `parent-commit-ids`, `commit-as-db`, `branch-as-db`, query evidence/cache, pull, entity, and native index access are the implementation seam. |
| Datahike database records | `reference-code/datahike/src/datahike/db.cljc:307-695` | Raw `DB` and the `AsOfDB`, `SinceDB`, and `HistoricalDB` wrappers already express the meanings that the wire map describes. Wrappers delegate index/search to their origin with a temporal search context. |
| Datahike versioning | `reference-code/datahike/src/datahike/versioning.cljc:403-443` | `commit-id` and `parent-commit-ids` read persisted commit metadata; `commit-as-db` resolves a retained commit and preserves attached cache ownership. |
| Datahike serialization | `reference-code/datahike/src/datahike/transit.cljc:34-86`, `json.cljc:22-94`, `remote.cljc:56-107` | Datahike's own remote representation already uses store ID, maximum transaction, maximum entity ID, commit ID, and explicit temporal wrappers. It does not need Seon's database ID/branch/commit/t wrapper. |
| Datahike reports/listeners | `reference-code/datahike/src/datahike/db.cljc:130`, `core.cljc:202-225` | A transaction report is `{:db-before ... :db-after ... :tx-data ... :tempids ... :tx-meta ...}`; keyed `listen!` callbacks receive that report and `unlisten!` removes the key. |
| Datahike query sharing | `reference-code/datahike/src/datahike/query.cljc:2427-2604` | The result cache is already keyed by committed connection, generation, and commit. It structurally propagates unaffected results, admits numeric strict `as-of` values, and evicts a generation on connection release. |
| Datahike API description | `reference-code/datahike/src/datahike/api/specification.cljc` | The maintained dependency already records public arities, schemas, capability operations, remote support, host-value returns, and laziness. Derive Seon's compatibility matrix and fixtures from this catalog; do not create another API registry. |
| Seon public facade | `src/seon/db.cljs` | The facade currently resolves and repeats a coordinate for every operation, derives `:seon.db/tx` from its overloaded `t`, and exposes head/transaction-coordinate helpers. |
| Seon protocol | `src/seon/db/protocol.cljc` | Version 8 repeats database name, attachment, and coordinate through read, write, listen, index cursor, lifecycle, KNN, and error shapes. |
| Seon authority | `src/seon/db/writer.clj`, `registry.clj`, `executor.clj` | The registry already owns routing and native connections; the writer already resolves commits, applies temporal wrappers, serializes eager values, and receives native reports. |

Official references used for the public contract:

- [Datomic Client API](https://docs.datomic.com/client-api/datomic.client.api.html)
- [Datomic database filters](https://docs.datomic.com/reference/filters.html)
- [Datomic index APIs](https://docs.datomic.com/indexes/index-apis.html)
- [Datomic index pull](https://docs.datomic.com/indexes/index-pull.html)

No Datomic internals were inferred. Datomic establishes the public shape;
maintained Datahike source establishes the implementation and lineage rules.

## Why `coordinate` is redundant

### It combines three established concepts

The current closed value contains database ID, branch, commit ID, and `t`.
Those fields combine:

1. database routing, which the registry and `:db-name` already own;
2. immutable Datahike commit identity, which `d/commit-id` already owns; and
3. temporal filtering, which Datahike and Datomic already call `as-of`,
   `since`, and `history`.

That combination creates a new noun without adding information. It also forces
the public protocol to repeat `database-name`, `attachment`, and `coordinate`
even though the client is naming one database value.

### Its `t` has two meanings

For a head value, current `t` means the committed basis transaction. For a
strict historical read, the same field means an `as-of` cut inside a later
containing commit. This is not the Datomic model. Datomic keeps the containing
basis `:t` and the temporal filter separately observable as `:as-of`.

The distinction matters to caching and batching. Datahike's cache identifies
the containing commit, while a strict numeric `as-of` adds the selected time to
the cache key. A four-field coordinate hides that distinction and makes callers
reconstruct it.

### Attachment is authority state, not database-value data

Datahike's `[:config :store :id]` and `[:config :branch]` are useful inside the
registry for validating physical ownership. They do not need to cross every
read boundary. A Bun process already acquires a database by `:db-name`, and the
physical session owns cleanup. A branch exposed to clients is another database
name, not a branch keyword that every operation must repeat.

### It duplicates Datahike lineage

Datahike persists `:datahike/commit-id` and `:datahike/parents`, and exposes
`commit-id`, `parent-commit-ids`, and `commit-as-db`. Seon should use those
facts directly when proving a retained value is reachable from the acquired
database head. Copying the commit ID into a new namespaced map does not make the
lineage safer.

### It leaks into unrelated application code

The current abstraction is not isolated to the authority. As of this audit,
the deletion search finds 602 targeted production references and 1,497 test
references, with 891 occurrences of the word in the five central database
owners alone. Agent loops, rendering, routes, embedding, errors, browser state,
and lifecycle code all know the invented wrapper. Replacing it with the value
of a database makes those callers speak the same API as query, pull, and index
operations.

## Exact Bun-side database value

The wire schema is a closed ordinary map:

```clojure
[:map {:closed true}
 [:db-name [:string {:min 1}]]
 [:t [:int {:min 0}]]
 [:as-of [:or nil? :int :inst]]
 [:since [:or nil? :int :inst]]
 [:history :boolean]
 [:datahike/commit-id {:optional true} :uuid]]

```

The nil values here mirror third-party lookup behavior at this protocol
boundary; they are not durable Seon entity data. All five Datomic keys are
present on values produced by `seon.db`. The Datahike extension is present for
a value produced by the JVM/Datahike authority and absent only for an
implementation that can prove exact retry and lineage semantics without it.

Examples:

```clojure
;; Current committed value.
{:db-name "default" :t 536870916
 :as-of nil :since nil :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

;; Strict value at an earlier transaction within the same retained commit.
{:db-name "default" :t 536870916
 :as-of 536870914 :since nil :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

;; History through that strict cut.
{:db-name "default" :t 536870916
 :as-of 536870914 :since nil :history true
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

;; Facts added after a point, against the same containing value.
{:db-name "default" :t 536870916
 :as-of nil :since 536870914 :history false
 :datahike/commit-id #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}

```

Do not add database ID, branch, attachment, selected transaction, basis,
revision, snapshot, or handle fields. `:t`, `:as-of`, and `:since` already name
those facts precisely.

## Resolution inside the Datahike authority

One function at the JVM boundary converts the ordinary map to a host-local
Datahike value:

1. Resolve the registry entry by `:db-name` and acquire its existing Datahike
   connection.
2. When `:datahike/commit-id` equals the current head commit, use `d/db`.
   Otherwise load exactly that retained value with `d/commit-as-db`.
3. Prove the selected commit is reachable from the current head of that
   database name by walking `d/parent-commit-ids`. Never fall back to head.
4. Require the raw value's maximum transaction to equal `:t`.
5. Apply `d/as-of` when `:as-of` is non-nil, `d/since` when `:since` is
   non-nil, and `d/history` when `:history` is true, in the one composition
   order proven by the maintained Datahike tests.
6. Run all requested work against that host-local value and release the raw
   materialized commit in `finally` when it was loaded by commit.

Reject invalid combinations explicitly. The first contract should allow
current, as-of, since, history, and history-of-as-of. It should reject
simultaneous `:as-of` and `:since` until a direct Datahike probe establishes a
useful supported meaning. No caller currently requires that composition.

The current registry's physical database ID and branch checks remain internal
connection-opening invariants. They are deleted from read requests, read
responses, errors, and client state.

## Strict lineage rules

1. The request's `:db-name` must be acquired on the physical session. Another
   database name is another database, even when it currently addresses the same
   commit.
2. A Datahike database value must carry `:datahike/commit-id`. `:t` alone is
   insufficient after branch creation, restore, or head replacement.
3. The commit must exist in the selected database's store and be reachable from
   that database name's current head. A retained orphan or sibling-only commit
   is not readable through this name.
4. The raw retained value's maximum transaction must exactly equal `:t`.
   `:t` never means an as-of point.
5. Numeric `:as-of` and `:since` must lie between Datahike's transaction origin
   and `:t`. For a non-origin numeric point, require the transaction entity's
   exact `:db/txInstant` datom; do not silently round a hole.
6. A date filter is passed to Datahike unchanged after the containing commit is
   proven. The returned ordinary database value retains the requested date in
   `:as-of` or `:since`.
7. A normal transaction's `:db-before` and `:db-after` share `:db-name`.
   `:db-after` must be the new reachable commit. Its normal single-parent case
   includes the prior head; merge operations may have several parents.
8. An expected write value must be a current, unfiltered database value:
   `:as-of nil`, `:since nil`, and `:history false`. Compare database name,
   basis `:t`, and Datahike commit ID. Do not compare only `t`.
9. A restore or forced branch transition is not disguised as a transaction.
   Its lifecycle operation proves the selected retained commit and publishes a
   new current database value after the native branch operation succeeds.
10. Missing, released, unreachable, or mismatched values return one existing
    ordinary database/stale error. Never substitute the current value.

These rules keep routing, lineage, and temporal semantics separate while using
only existing Datomic/Datahike names.

## Transaction and listener shapes

### Transaction result

Return the established transaction-report shape, with host values replaced by
ordinary database-value maps:

```clojure
{:db-before {:db-name "default"
             :t 536870916
             :as-of 536870914
             :since nil
             :history false
             :datahike/commit-id
             #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}
 :db-after {:db-name "default"
            :t 536870916
            :as-of nil
            :since nil
            :history false
            :datahike/commit-id
            #uuid "72482707-c5c3-52b5-b803-8fcd3d89df2f"}
 :tx-data [{:seon.db/e 536870914
            :seon.db/a :example/value
            :seon.db/v "new"
            :seon.db/tx 536870915
            :seon.db/added? true}]
 :tempids {-1 101}
 :tx-meta {:seon.db.protocol/request-id "request-1"}}

```

Datahike batches ready logical transactions into one persisted commit and
replaces every callback's `:db-after` with that final committed value. A later
logical transaction's native `:db-before` may therefore be an uncommitted
intermediate value. Encode that logical pre-transaction state as `:db-before`
using the final containing commit plus `:as-of` equal to the native
`db-before` maximum transaction. This is the direct Datomic-shaped expression
of the behavior that current `previous-coordinate` tries to represent.

Keep request recovery and generated-ID evidence as additional namespaced keys
only when required. Delete the parallel compact fields `:seon.db/coordinate`,
`:seon.db/tx`, `:seon.db/tx-count`, `:seon.db/added`, and
`:seon.db/retracted`; they duplicate the report and force consumers to learn a
second result model. Counts are cheap pure derivations from `:tx-data` when a
caller needs them.

### Listener callback

Keep Datahike's keyed listener model and callback shape. A selective listener
receives:

```clojure
{:db-before <ordinary database value>
 :db-after <ordinary database value>
 :tx-data <only the matching eager datom maps>
 :tempids {}
 :tx-meta <ordinary transaction metadata>}

```

The listener key remains the existing request ID. There is no second
subscription identity. `unlisten!` names that key, and physical disconnect
removes all keys owned by the session.

Selective delivery still uses Datahike query attribute dependencies or exact
datom patterns. The report shape does not imply broadcast: the JVM's existing
attribute-indexed interest table chooses callbacks before serialization. A
transport pressure gap emits the existing resynchronization event with only
the latest `:db-after`; the consumer recomputes its derived view there.

## Query, pull, entity, and index semantics

### Public `seon.db` calling conventions

Preserve the established Datahike/Datomic positional API. Protocol request maps
are private construction data inside `seon.db`; application and agent callers
must not learn wire keys merely because the implementation is remote.

The explicit database-value arities are:

```clojure
(await (db/query query-form database & inputs))
(await (db/pull database selector entity-id))
(await (db/pull-many database selector entity-ids))
(await (db/entity database entity-id))
(await (db/listen! database key callback))
```

The existing ambient conveniences remain for the common single-database
fiber: query without an explicit database argument, two-argument pull,
two-argument pull-many, one-argument entity, and keyed `listen!` with the
ambient database. The facade resolves the ambient database value once and then
uses the same explicit implementation. `entity` remains eager `pull '[*]`, not
a remote Datahike Entity.

Preserve map arities beside the positional arities. Public functions and their
request schemas stay colocated in `seon.db`; there is no `seon.data` facade or
generic public request namespace. Every Seon-owned map key is fully namespaced,
for example `:seon.db/db`, `:seon.db/query`, and `:seon.db/args`. An absent
`:seon.db/db` means the latest database value. The bare `:db-name`, `:t`,
`:as-of`, `:since`, and `:history` keys remain only because they are the
established Datomic database-value observables.

Selective interest options may remain one namespaced `seon.db` options value,
but key and callback retain positional arities. Do not make
`{:seon.db/handler ... :seon.db/key ...}` the only listener interface, and do
not expose `:seon.db.protocol/*` request maps. Asynchrony is the one unavoidable
difference from an in-process Datahike call: operations that cross the authority
return Promises and are awaited by the existing agent-facing boundary.

### Query

Use the Datomic Client argument model: query form plus arguments. Every Bun-side
database descriptor anywhere in a multi-source query's ordinary arguments is
resolved to its native JVM database value before Datahike receives the argument
vector. The common single-database facade also accepts `:seon.db/db` directly;
callers need not construct a one-source argument vector.

Return eager ordinary query results. Keep maintained Datahike's native
attribute-dependency, cache, and resource evidence on the evidence-returning
facade; do not make Bun parse Datalog or maintain another query cache.

Bun compatibility preserves semantic values and collection shapes, not host
object identity or laziness. Database descriptors are rehydrated only on the
JVM. Entities are eager maps; Datoms are ordinary five-field maps; transaction
and listener reports are ordinary maps. No Datahike record, lazy sequence,
connection, entity object, or listener function crosses the transport.

### Pull

Keep `pull` and `pull-many` as first-class eager operations. They return maps or
nil and an ordered vector of maps/nils respectively. Resolve selectors and
lookup refs in the JVM against the same native database value.

Datomic Client deliberately has no lazy entity API. Delete a remote `entity`
operation and any attempt to serialize Datahike `Entity`. If source-level
convenience remains valuable, `seon.db/entity` is only a local name for
`pull '[*]`; it returns an ordinary map and has no navigation behavior.

History database values may be used with query and index operations, not pull.
Reject pull and pull-many when `:history true`, matching Datomic Client. A
`since` database can omit an entity's identity datom even when later attribute
facts exist; callers needing a known entity resolve its ID against a current
value and then use the numeric ID against the since value.

### Native indexes

Expose Datahike's maintained eager `index-page` as the bounded transport form
of Datomic raw index access. Keep the established fields:

```clojure
{:db <ordinary database value>
 :index :avet
 :components [:example/value]
 :direction :forward
 :limit 100
 :cursor nil}

```

The native Datahike cursor is the five Datom fields. The protocol cursor adds
the complete database value, index, direction, and history check needed to
prevent resuming against a different value. Replace the embedded coordinate;
do not rename the cursor or datom components.

Retain Datahike `datoms`, `seek-datoms`, `rseek-datoms`, and `index-range`
inside the authority. `index-page` is the default Bun interface because it is
eager, bounded, resumable, and already implemented in the maintained fork.
Add `index-range` or `index-pull` only when a measured caller benefits from the
stronger native operation; do not synthesize either from repeated Bun calls.

## Cache and lifetime consequences

No new shared cache is needed.

The maintained Datahike query cache already uses the facts that belong inside
the JVM: connection ID, open generation, commit ID, query, and non-database
arguments. It propagates unaffected results from parent to child commits using
attribute dependencies. It also single-flights equal in-progress queries.

The current fork's source admits a strict numeric `AsOfDB` into the cache as
`[connection-id generation commit-id as-of-t]`. Raw committed values use
`[connection-id generation commit-id]`. `SinceDB` and `HistoricalDB` do not
currently produce cache keys. The implementation should preserve that exact
behavior and measure those less common cases before extending it.

`d/release` already closes the query-cache generation and evicts all its
snapshots while detaching active single-flight callers. This is the Clojure
answer to “evict when nobody cares about that database”: registry acquisition
reference counts determine when the connection is released, and Datahike owns
the corresponding cache eviction. Bun does not count cache readers or send a
separate cache-release command.

Replacing the coordinate wrapper improves the cache seam because the JVM
receives the exact database meaning directly. It no longer has to infer whether
`t` is the commit basis or an earlier cut.

## Exhaustive production rename and deletion inventory

The following is exhaustive for the targeted source search at the selected
checkout. Re-run this exact search after each cut:

```bash
rg -l 'seon\.db\.coordinate|::coordinate/|:seon\.db\.coordinate/|::protocol/(coordinate|previous-coordinate|head-coordinate|expected-coordinate|current-coordinate|source-coordinate|expected-source-head|expected-target-head|source-head|main-coordinate)|:seon\.db\.protocol/(coordinate|previous-coordinate|head-coordinate|expected-coordinate|current-coordinate|source-coordinate|expected-source-head|expected-target-head|source-head|main-coordinate)|resolve-transaction-coordinate' src test docs

```

### Delete outright

- `src/seon/db/coordinate.cljc`
- `test/seon/db/coordinate_test.cljc`
- `test/seon/db/transaction_coordinate_test.clj`
- protocol operation `resolve-transaction-coordinate` and its facade helper;
  use `as-of` on a known containing database value or a bounded transaction-log
  lookup that returns a database value;
- all attachment and coordinate public schemas, response fields, cursor fields,
  error fields, and facade state;
- `head-coordinate`; the established operation is `db` and it returns the
  current database value;
- `previous-coordinate`; transaction results already have `:db-before`;
- compact transaction `tx` and datom-count summaries that duplicate `tx-data`;
  and
- any remote lazy entity or serialized Datahike database/entity representation.

### Central database owners to rewrite in place

- `src/seon/db.cljs`
- `src/seon/db/backend.clj`
- `src/seon/db/browser.cljs`
- `src/seon/db/executor.clj`
- `src/seon/db/protocol.cljc`
- `src/seon/db/registry.clj`
- `src/seon/db/restore.cljc`
- `src/seon/db/restore/schema.cljc`
- `src/seon/db/restore_admin.clj`
- `src/seon/db/restore_admin/schema.cljc`
- `src/seon/db/transport/uds.cljs`
- `src/seon/db/writer.clj`

The registry retains internal physical store ID and branch validation but stops
returning attachment maps. Protocol requests take one `:db` database value.
Lifecycle operations take database names and current/selected database values.
The executor accepts the resolved native value and does not inspect public
identity fields.

### Production consumers to rename from coordinate to database value

- `src/my/blob/schema.cljc`
- `src/seon/agent.cljs`
- `src/seon/agent/debug.cljs`
- `src/seon/agent/loop.cljs`
- `src/seon/agent/run.cljs`
- `src/seon/agent/turn.cljs`
- `src/seon/client.cljs`
- `src/seon/dev/restore.clj`
- `src/seon/embed.cljs`
- `src/seon/error.cljs`
- `src/seon/execution.cljs`
- `src/seon/execution/host.cljs`
- `src/seon/launch.cljc`
- `src/seon/repl/autocomplete.cljs`
- `src/seon/runtime/lifecycle.cljc`
- `src/seon/state.cljs`
- `src/seon/web/datastar.cljs`
- `src/seon/web/debug.cljs`
- `src/seon/web/router.cljs`
- `src/seon/web/serve.cljs`

These consumers must not learn commit ancestry. They retain or compare the
ordinary `:db` map, pass it to `seon.db`, and derive views from returned data.

## Exhaustive affected test inventory

Delete the two dedicated coordinate suites named above. Rewrite the remaining
affected tests around ordinary database values and native reports:

- `test/my/blob_test.cljs`
- `test/my/plan_test.cljs`
- `test/seon/agent/ctx/canvas_test.cljs`
- `test/seon/agent/ctx/menu_test.cljs`
- `test/seon/agent/ctx/namespaces_test.cljs`
- `test/seon/agent/ctx/subagents_test.cljs`
- `test/seon/agent/ctx/typeahead_steps_test.cljs`
- `test/seon/agent/ctx/warnings_test.cljs`
- `test/seon/agent/debug_test.cljs`
- `test/seon/agent/message_test.cljs`
- `test/seon/agent/run_test.cljs`
- `test/seon/agent/turn_capture_test.cljs`
- `test/seon/agent/turn_test.cljs`
- `test/seon/agent_debug_errors_test.cljs`
- `test/seon/agent_lifecycle_test.cljs`
- `test/seon/agent_retry_test.cljs`
- `test/seon/ai_test.cljs`
- `test/seon/db/backend_test.clj`
- `test/seon/db/executor_test.clj`
- `test/seon/db/protocol_test.clj`
- `test/seon/db/protocol_test.cljs`
- `test/seon/db/registry_routing_test.clj`
- `test/seon/db/registry_test.clj`
- `test/seon/db/request_receipt_test.clj`
- `test/seon/db/restore_admin_test.clj`
- `test/seon/db/restore_test.cljs`
- `test/seon/db/server_test.clj`
- `test/seon/db/transport_uds_test.clj`
- `test/seon/db/transport_uds_test.cljs`
- `test/seon/db/writer_integration_test.clj`
- `test/seon/db/writer_interest_test.clj`
- `test/seon/db/writer_query_admission_test.clj`
- `test/seon/db_session_test.cljs`
- `test/seon/dev/branch_test.clj`
- `test/seon/dev/cli_test.clj`
- `test/seon/dev/process_test.clj`
- `test/seon/dev/restore_test.clj`
- `test/seon/embed_test.cljs`
- `test/seon/error_record_test.cljs`
- `test/seon/execution/host_test.cljs`
- `test/seon/execution/runtime_test.cljs`
- `test/seon/execution_test.cljs`
- `test/seon/launch_test.cljs`
- `test/seon/repl/autocomplete_test.cljs`
- `test/seon/runtime/lifecycle_test.cljc`
- `test/seon/runtime/recovery_test.cljs`
- `test/seon/state_test.cljs`
- `test/seon/web/datastar_test.cljs`
- `test/seon/web/reactive/call_test.cljs`
- `test/seon/web/router_test.cljs`
- `test/seon/web/serve_test.cljs`

Tests that only compare values should change fixtures, not add adapters.

## Documentation replacement inventory

Current architecture and roadmap language that presents coordinate as the
target must be replaced when implementation begins:

- `docs/seon/architecture/data-model.md`
- `docs/prds/database-lifecycle-recovery/roadmap.md`
- `docs/prds/reactive-render-units/roadmap.md`
- `docs/prds/database-browser/research/coordinate-bound-cursor-contract-2026-07-15.md`
- all database-lifecycle research reports returned by the exhaustive search;
- `docs/prds/reactive-render-units/research/reactive-unit-database-browser-reconciliation-2026-07-14.md`;
- `docs/seon/issues/database-protocol-coordinate-is-incomplete.md`; and
- downstream agent-runtime and agentic-tool reports returned by the search.

Historical research need not be rewritten line by line. Add a visible
supersession note to still-linked reports, update current architecture/roadmaps,
and ensure no active instruction calls coordinate the intended interface.

## Compatibility-free implementation order

1. **Settle fixtures first.** Add the closed database-value schema and pure
   constructors in `seon.db.protocol`; add fixtures for current, as-of, since,
   history, transaction report, listener report, and index cursor. Do not keep
   the coordinate schemas beside them. Generate the compatibility matrix and
   relevant fixture inventory from Datahike's existing API specification;
   do not maintain a second handwritten API catalog.
2. **Replace the authority resolver.** In `writer.clj`, resolve one database
   value map to one native Datahike value with exact commit reachability,
   `:t` equality, temporal composition, and materialized-value release.
3. **Replace read operations atomically.** Query, pull, pull-many, schema,
   execute-many, index-page, KNN preflight, and their responses all take/return
   `:db`. Delete attachment and coordinate from those frames in the same cut.
4. **Return native-shaped transaction reports.** Convert Datahike
   `db-before/db-after` to ordinary maps, preserving batched logical-before
   semantics with `:as-of`. Delete compact duplicate response fields and the
   transaction-coordinate resolver.
5. **Replace selective events.** Deliver the same ordinary report shape with
   matching `tx-data`; retain request ID as the keyed listener identity and
   retain current pressure/resynchronization behavior.
6. **Simplify registry and lifecycle.** Keep physical ID, branch, ref-count,
   release, and restore facts internal. Public lifecycle responses use database
   names and database values. Branch creation returns the target database's
   current value.
7. **Rewrite `src/seon/db.cljs` once.** `db` returns the current database value;
   every operation preserves its explicit positional database-value arity and
   ambient convenience; transaction accepts an optional expected database
   value; keyed listen callbacks receive ordinary reports. Keep protocol maps
   private. Delete head, coordinate, attachment, and transaction-coordinate
   functions.
8. **Replace consumers by dependency order.** Startup/session, state and
   execution context, agent loop, route/render acquisition, browser/debug,
   embeddings, then lifecycle UI. Consumers pass database values and never
   inspect Datahike commit ancestry.
9. **Delete the old namespace and tests.** Remove `coordinate.cljc` and the two
   dedicated suites, then require the exhaustive search to return only
   explicitly superseded historical research.
10. **Run one integrated checkpoint.** Focused protocol/writer/session tests,
    complete writer and CLJS suites, then one live Bun cluster proving query,
    transaction, selective refresh, reconnect, retained as-of read, and clean
    release/cache eviction.

Do not introduce aliases such as snapshot, revision, handle, point, or
database-coordinate during the cut. The public nouns are database value,
transaction report, listener, Datom, query, pull, and index.

## Shortest decisive tests

### Protocol data

1. A current database value round-trips through JVM and Bun Transit with
   exactly the six allowed keys and remains an ordinary map.
2. Current, as-of, since, and history values validate; simultaneous as-of and
   since is rejected; a record, connection, Datahike DB, or Entity is rejected.
3. Every read request carries one `:db` and no database name, attachment, or
   coordinate sibling field.
4. Public facade tests prove `query q db & inputs`, `pull db pattern eid`,
   `pull-many db pattern eids`, `entity db eid`, and `listen! db key callback`,
   every corresponding namespaced map arity, and every ambient convenience.
   An omitted `:seon.db/db` selects latest. In particular, three-argument pull
   and the explicit-database entity arity must not disappear, and listen must
   not regress to a map-only API. The captured transport frame remains private.
5. A multi-source query rehydrates every database descriptor in ordinary
   `:seon.db/args` on the JVM while leaving non-database arguments unchanged.
   Compatibility fixtures are selected from Datahike's existing API
   specification and assert ordinary eager results for every remote-supported
   operation; no second API catalog exists.

### Datahike resolution and lineage

6. Head commit resolves to the identical `d/db`; a retained ancestor resolves
   through `commit-as-db`; a sibling-only or orphan commit is rejected.
7. Wrong `:db-name`, commit ID, or basis `:t` is rejected without falling back
   to head.
8. Strict `:as-of` returns the earlier facts while retaining the containing
   `:t` and commit ID; invalid numeric holes and future points are rejected.
9. History query/index includes retractions; history pull is rejected; since
   demonstrates the documented identity-datom caveat.

### Transactions and listeners

10. A normal transaction returns ordinary `db-before`, `db-after`, `tx-data`,
   `tempids`, and `tx-meta`, and the after commit has the before commit as a
   parent.
11. Force two logical transactions into one Datahike commit batch. Both reports
   share the final `db-after`; the later report's `db-before` uses that commit
   with the exact earlier `:as-of` and reads the correct state.
12. A compare-and-set write accepts only the exact current unfiltered database
    value and rejects same-`t` wrong-commit and filtered values.
13. Two selective listeners on different attributes receive only their matching
    reports; unlisten acknowledgment prevents later delivery; disconnect clears
    both.

### Index and cache

14. An index cursor resumes only with an equal database value, index, direction,
    and history setting.
15. Concurrent equal queries at one current database value single-flight and
    then hit the Datahike result cache from several Bun child processes.
16. An unrelated transaction propagates the cached result; a relevant
    transaction recomputes it.
17. Releasing the last acquisition closes the Datahike generation, evicts its
    cached snapshots, and detaches active query callers. Bun sends no cache
    release message.

### Deletion gate

18. Production `rg` finds no `seon.db.coordinate`, coordinate schema, attachment
    field, head-coordinate, previous-coordinate, or
    resolve-transaction-coordinate.
19. The Bun artifact contains no `datahike.api`, Datahike DB record, Entity,
    index object, connection, listener function, or JVM implementation value.

## Remaining measured choices

The public seam is settled. Three implementation choices should be measured,
not guessed:

- whether returning full matching `tx-data` on every listener report costs
  materially more than the current matching datom event after Transit and Bun
  decoding; both keep the same selective authority path;
- whether numeric strict `as-of` cache admission remains beneficial at the
  expected number of simultaneous agent database values; the maintained fork
  already exposes hit/miss and occupancy evidence; and
- whether a real consumer warrants native `index-pull` or `index-range` on the
  wire after bounded `index-page` is proven.

None requires retaining coordinate or adding another public identity.
