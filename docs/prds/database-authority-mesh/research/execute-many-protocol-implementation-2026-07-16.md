---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Execute-many protocol implementation — 2026-07-16

## Decision

Add one `execute-many` protocol operation whose outer request owns the existing
request ID, database name, attachment, and exact coordinate. Its bounded member
vector contains only existing query, pull, or pull-many inputs. Resolve the
Datahike value once, submit members as flat `:read` jobs to the one authority
dispatcher, preserve member-vector order, and release the materialized value
after every member has actually stopped.

There is no second executor, database-value cache, response cache, connection,
transport operation per member, or member ID on the wire. Vector position is
the member correlation. Temporary executor and Datahike caller strings derived
from `[outer-request-id position]` are host implementation details.

The historical secondary-index blocker found in
[[execute-many-value-reuse-2026-07-16]] is now settled by Datahike
`release-materialized-db`. Historical values own only the secondary resources
created by their materialization; head values and primary-only values are
no-ops. Execute-many must invoke that function exactly once in `finally` after
all member calls have returned, including after cancellation or failure.

## Dependency ledger

- Seon `d684c0f8`: `src/seon/db/protocol.cljc`, `db/writer.clj`, and the one
  authority-wide `db/executor.clj` dispatcher.
- Datahike `7eb1b849`: `versioning.cljc:414-445`,
  `writing.cljc:226-325`, `query.cljc:4179-4246`, and
  `api/impl.cljc:368-376`.
- Existing contracts: [[authority-protocol-contract-2026-07-16]],
  [[read-materialization-contract-2026-07-16]], and
  [[execute-many-value-reuse-2026-07-16]].

The current executor already rotates work class and database, applies one CPU
ceiling, bounds queued request bytes, rejects closed scopes, cancels queued
jobs, exposes running requests for native cancellation, and makes
`fence-and-drain!` wait until matching jobs disappear. The missing interval is
between the resolution job completing and member jobs being admitted: no job
then retains the exact connection generation.

## Minimal wire schema

Advance the protocol version from 2 to 3 and add only these public shapes. The
names below reuse current protocol names wherever their meaning is identical.

```clojure
(def execute-many-operation :seon.db.protocol.operation/execute-many)

(schema/register!
 ::query-member
 [:map {:closed true}
  [::operation [:= query-operation]]
  [::query-form ::query-form]
  [::arguments ::arguments]
  [:datahike.resource/max-work {:optional true}
   :datahike.resource/max-work]
  [:datahike.resource/max-results {:optional true}
   :datahike.resource/max-results]
  [:datahike.resource/max-result-weight {:optional true}
   :datahike.resource/max-result-weight]])

(schema/register!
 ::pull-member
 [:map {:closed true}
  [::operation [:= pull-operation]]
  [::selector ::selector]
  [::entity-id ::entity-id]
  ;; the same three optional Datahike resource attributes
  ])

(schema/register!
 ::pull-many-member
 [:map {:closed true}
  [::operation [:= pull-many-operation]]
  [::selector ::selector]
  [::entity-ids ::entity-ids]
  ;; the same three optional Datahike resource attributes
  ])

(schema/register!
 ::member
 [:multi {:dispatch ::operation}
  [query-operation ::query-member]
  [pull-operation ::pull-member]
  [pull-many-operation ::pull-many-member]])

(schema/register! ::members [:vector {:min 1 :max 64} ::member])

(schema/register!
 ::execute-many-request
 [:map {:closed true}
  [::operation [:= execute-many-operation]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::members ::members]])
```

Sixty-four is the first conservative semantic bound, not a capacity promise.
Queue and CPU bounds still govern actual concurrency. Keep resource bounds on
each member because different queries have different work and result costs;
adding outer duplicates would create ambiguous precedence. Aggregate safety is
already supplied by the maximum member count, maximum request bytes, shared CPU
ceiling, and per-database queue limits. Measurements may later lower the member
bound without changing the shape.

The response owns the same outer metadata and one ordered `::results` vector.
Each position is one of the existing successful result bodies or the existing
failed response:

```clojure
(schema/register!
 ::query-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [:datahike.query/result :datahike.query/result]
  [:datahike.query/cache-evidence :datahike.query/cache-evidence]
  [:datahike.query/resource-evidence :datahike.query/resource-evidence]])

(schema/register!
 ::read-member-response
 [:map {:closed true}
  [::success? [:= true]]
  [::result ::result]])

(schema/register!
 ::member-response
 [:or ::failed-response ::query-member-response ::read-member-response])
(schema/register! ::results [:vector ::member-response])

(schema/register!
 ::execute-many-response
 [:map {:closed true}
  [::success? [:= true]]
  [::request-id ::request-id]
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::results ::results]])
```

An outer failure means the request could not acquire its exact database value
or could not be admitted at all. Once acquisition succeeds, ordinary member
errors remain at their positions and the outer response succeeds. This avoids
fail-fast nondeterminism and lets independent members finish usefully.
Completed member results are not returned by a canceled request because the
existing cancel response is the terminal response for that request; they are
simply released. A future resumable/page protocol can make partial delivery an
explicit contract rather than smuggling it into cancellation.

## Minimal executor strengthening

Strengthen `seon.db.executor` in place with exact-scope retain/release; do not
add another namespace or Java executor.

- Add `::retained-scopes` to state as a map from the existing `::scope` to a
  positive count.
- `retain-scope!` locks the executor, rejects stopped or closed scopes, and
  increments that count atomically.
- `release-scope!` locks, decrements/removes the count, and notifies all.
- `fence-scope!` continues to close admission before cancellation.
- `fence-and-drain!` waits until both matching jobs and the retained count are
  zero.
- `evidence` reports the sum of counts as its existing
  `::retained-identities`; do not expose a new protocol concept.

The execute-many coordinator runs on the existing request-handling caller, not
on a read worker and not on a new pool. Its order is:

1. derive and retain the current exact scope;
2. submit one ordinary fair `:read` job that calls `pinned-database` once;
3. submit all members as flat `:read` jobs carrying the identical internal DB
   value and their vector position;
4. await every result promise outside the worker pool and place its ordinary
   response at that position;
5. call `d/release-materialized-db` once; and
6. release the exact scope in the outermost `finally`.

The resolution and member executor requests are internal maps, not protocol
requests. They may contain the DB value after resolution because the dispatcher
is its host owner; neither `protocol.cljc`, Transit, database facts, nor agent
functions can observe it. A queued pointer does not copy the immutable value or
its indexes. The existing input validation runs over member wire data before
retention, and the existing result materialization walk runs independently on
every successful result.

Do not implement the coordinator as a read job which recursively submits and
waits: all CPU workers could then wait for work queued behind themselves. Do
not resolve outside the dispatcher: historical restoration can perform storage
and secondary-index work and therefore belongs under fair bounded admission.

## Identity, cancellation, and release

The outer `::request-id` remains the only public identity. Add the same outer
request ID to each internal executor work map while retaining the existing
unique `::job-id` for dispatcher bookkeeping. Derive unique job IDs and query
caller IDs from the outer string and the numeric vector position. They never
cross the wire and are removed when work finishes.

Extend cancellation by the existing outer request ID, not by string-prefix
search:

- queued resolution/member jobs are removed and settled;
- no new members are admitted after cancellation;
- running query members receive `d/cancel-query!` with their temporary caller
  IDs;
- running pull and pull-many calls are truthfully uncancelable and must return
  before the DB value and exact scope are released; and
- cancellation of one member must not cancel a joined identical Datahike query
  still needed by another caller.

The coordinator needs one process-local cancellation flag because cancellation
can race between resolution and member admission. Store it only in the
request-lifetime coordinator, not in the database, protocol, or a completed
request registry. The existing request server already retains the active call;
the flag disappears in `finally`.

Final database release closes the exact scope first, cancels every running
query caller under it, waits for all jobs and the retained coordinator, then
releases the registry/Datahike connection generation. The coordinator releases
historical secondary resources before dropping the scope. For a head DB,
`release-materialized-db` is a proven no-op and cannot close the
connection-owned secondary index.

## Writer refactor boundary

Avoid duplicating the three operation cases. Extract the current body of
`execute-read!` into a private function accepting `[db-value request
datahike-request-id]` and returning a member response body. One-member reads
call it with their normal request ID and add the existing outer metadata.
Execute-many calls it with the shared DB value and temporary caller ID.

`pinned-database` remains the sole resolver. Add an option to load historical
primary-only values only after KNN dispatch is separated: query/pull/pull-many
can call `commit-as-db` with `{:secondary-indices? false}`, while a KNN member
must explicitly acquire the matching secondary generation. Until that split is
implemented, the safe general path restores and releases secondary resources
once per outer request.

Do not add KNN to the first execute-many schema. KNN uses the `:knn` work class
and secondary-index lifetime, while the selected first operation promises flat
members in the `:read` class over one DB value. Add it only after the Proximum
API accepts the authority dispatcher and its exact historical-index behavior
is proven.

## Implementation order and proof

1. Add protocol schemas, constructor, version fixture, canonical request and
   response validation tests. Prove members cannot carry request ID, database,
   attachment, or coordinate.
2. Add executor retain/release and tests for retain-after-close rejection,
   nested counts, cancellation, and fence waiting between resolution and
   member admission.
3. Extract one DB-value read function and prove existing query, pull, and
   pull-many responses remain byte-for-byte shape-compatible.
4. Add the coordinator and deterministic tests with barriers: one historical
   `commit-as-db`, parallel member starts, completion-order inversion with
   vector-order response, mixed success/failure, and all-failure response.
5. Prove outer cancellation removes queued members, detaches running identical
   queries independently, waits for running pull, admits no late member, and
   leaves zero executor/Datahike retained state.
6. With a Closeable secondary fixture, prove exactly one restore and close for
   an old coordinate; prove head execute-many closes nothing; repeat failures
   and cancellation until resource evidence returns to baseline.
7. Prove release/reconnect generation isolation by pausing after DB resolution,
   starting final release, confirming it waits, finishing members, and proving
   stale cleanup cannot close the replacement generation.
8. Benchmark 1/8/32/64 mixed members against repeated one-member requests:
   resolution count, allocated bytes, wall time, worker utilization, cache
   joins/hits, cancellation latency, and retained secondary resources.

The focused gate should cover protocol tests, executor tests, writer integration,
UDS canonical framing, and Datahike versioning/resource tests. The graduation
gate additionally needs the complete writer suite and one live persistent
session proof once the transport replacement reaches this operation.

## Expected deletion and performance effect

At 32 members, the prior source probe measured one materialization at about
10.9 microseconds and 38 KB versus repeated materialization at 384.2
microseconds and 1.23 MB. Eight independent scan-heavy queries over one value
completed about 5.47 times faster in parallel than sequentially. Execute-many
therefore removes repeated coordinate framing, Transit envelopes, registry
resolution, Konserve cache touches, DB/index wrapper allocation, and secondary
restoration while preserving bounded cross-database fairness.

After execute-many is proven, internal one-member reads should use the same
coordinator with one member. That deletion leaves one read implementation and
one database-value lifetime rule rather than a batch path beside a legacy
single-read path.
