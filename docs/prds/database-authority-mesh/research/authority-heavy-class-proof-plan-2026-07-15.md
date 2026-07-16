---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Authority heavy-class proof plan — 2026-07-15

## Decision boundary

This plan defines the retained adversarial fixture for Unit 4. It does not pick
production permit counts. It proves whether six separately bounded work classes
and database-first fairness are real before those policy values are approved.

The first implementation uses ordinary work-conserving round robin over
per-database ready queues. With equal weights, quantum one, and unit request
cost, weighted deficit round robin produces exactly the same selection trace
while adding counters and normalization rules. Deficits remain an explicit
later option only if measured request shapes or product policy require unequal
weights. Queue limits, deadlines, and background shares remain explicit policy
decisions.

## Exact current owners

- Query CPU: Datahike `reference-code/datahike/src/datahike/query.cljc` owns
  Datalog execution and completed result caching; pull/index/history owners are
  `pull_api.cljc`, `db.cljc`, and `api/specification.cljc`. Seon has no remote
  query scheduler yet.
- Provider/embedding: `src/seon/embed.clj:557-727` batches Gemini calls, retries
  with sleeping backoff, and creates a fresh fixed executor of at most six
  threads per `embed-texts` call. `augment-tx-with-embeddings` at lines 866-921
  performs provider work before returning augmented transaction data.
- KNN/native: `src/seon/embed.clj:1037-1132` owns Proximum/HNSW lookup and query
  embedding. `src/seon/db/writer.clj:1072-1082` dispatches KNN over the current
  immutable Datahike value. The durable HNSW is restored during connection
  initialization by `initialize-database!` at `src/seon/embed.clj:1134`.
- Mutation: `src/seon/db/writer.clj:543-617` owns receipt recovery, transaction
  transform, and `d/transact` under `(locking connection)`. Because the
  embedding transform is called inside that region, provider delay can
  currently retain the per-connection mutation lock. This is the first
  falsifier, not an assumed acceptable seam.
- Encoding/delivery: `src/seon/db/transport/uds.clj:62-135` encodes Transit JSON
  into fresh byte arrays and length-prefixed frames. Each current request
  connection processes one request synchronously at lines 160-229. Publication
  uses one encoded frame plus duplicated buffers and bounded subscriber queues
  at lines 267-377. Datastar gzip is a separate pod UI owner and is not this
  authority fixture.
- Lifecycle/control: `src/seon/db/registry.clj:497-573` owns ensure/open;
  `release-attachment!` and `release-database!` own release. Writer request
  dispatch is `src/seon/db/writer.clj:1107-1180`; server admission/shutdown is
  `src/seon/db/transport/uds.clj:181-265`; writer start/stop is
  `src/seon/db/writer.clj:1184-1245`.

Existing proof homes are `test/seon/embed_writer_test.clj`,
`test/seon/db/writer_integration_test.clj`, `test/seon/db/registry_test.clj`,
`test/seon/db/registry_routing_test.clj`, and
`test/seon/db/transport_uds_test.clj`.

## Fixture shape

One retained JVM test namespace should create 2, 4, and 8 real memory-backed
Datahike databases through the authority registry. Each database has a stable
identity, an equal scheduler weight, deterministic seeded rows, and instrumented
class operations controlled by latches. No external Gemini call is permitted:
the provider dependency is a blocking deterministic function with the same
success, retry-delay, cancellation, and failure shapes. KNN uses either a small
real Proximum index when the maintained test classpath supports it or the
existing injected `::writer/knn-search` seam; both variants must retain a
separate native-class permit.

Every submitted job is immutable namespaced data containing database identity,
work class, job/request identity, enqueue time, deadline, cost estimate, and an
invocation function held only inside the authority. The scheduler selects a
database before a class permit. A database with many ready jobs therefore gets
turns, not a private executor or a fixed thread.

The fixture exposes barriers for admitted, started, provider-entered,
mutation-entered, encoding-entered, completed, canceled, and released. Tests
must assert transitions and metrics, never wall-clock ordering alone.

## Workload matrix

| Load | Saturated class | Required concurrent evidence |
|---|---|---|
| 32 expensive Datalog jobs on A | query | B query and B mutation start within bounded scheduler turns |
| blocked provider calls on A | provider | B query, mutation, KNN, encode, and control progress |
| blocked KNN/index builds on A | KNN/native | B query/provider/mutation/control progress |
| 4 MiB encoded results to slow A consumers | encode/delivery | query permits release before delivery drains; B small result completes |
| continuous A writes | mutation | A remains ordered; B writes commit independently; control remains available |
| acquire/release/cancel storm | lifecycle/control | control reserve remains bounded and query/mutation floors survive |
| all classes saturated on A | all | one ready job from B in each protected class starts within its bound |
| 2/4/8 equal hot databases | mixed | service counts converge within the configured deficit/aging tolerance |
| one weighted database | mixed | observed service ratio follows configured weight without starving peers |
| unique cold keys plus duplicates | query | admission remains bounded; single-flight savings do not alter fairness accounting |

Run each row with a quiet baseline, then adversarial saturation. Repeat with one
database canceling all waiters, one database releasing while queued, injected
provider/KNN/encode failure, and authority shutdown.

## Metrics and invariants

Record gauges by database and class: queued, running, permits, borrowed permits,
queued bytes, oldest age, and ready-queue count. Record counters: submitted,
admitted, started, completed, rejected, timed out, canceled-before-start,
canceled-running, failed, borrowed, and scheduler selections. Histograms cover
queue delay, execution time, delivery time, end-to-end latency, and cancellation
latency. Mutation additionally records commit order and lock-hold duration;
provider records attempts/backoff; query records single-flight owner/waiter;
encoding records input/result/framed bytes and shared-body reuse.

Required invariants:

- running and queued never exceed global or per-database bounds;
- a job consumes exactly one class permit, except an explicitly recorded phase
  transition that releases the first before acquiring the second;
- database selection precedes permit acquisition;
- cancellation/control never waits behind provider, KNN, encoding, or query;
- one database cannot consume another's protected query, mutation, delivery, or
  control progress;
- writes remain ordered per connection and concurrent across connections;
- provider backoff sleeps consume provider capacity only, never a mutation lock;
- encoding/delivery never retains a query/KNN permit;
- release removes queued jobs and no completion resurrects released state; and
- shutdown returns every queue, permit, connection, worker, byte counter, and
  in-flight registry to zero.

## Shortest falsifiers

1. Block the injected embedding provider during one transaction and submit a
   second ordinary transaction to the same database. If the second cannot reach
   the ordered mutation boundary, current provider work is holding the
   connection lock and must be phased before Unit 4 can graduate.
2. Fill provider permits with A, then submit B ping/cancel and B query. Either
   waiting behind provider disproves class isolation.
3. Fill A's query queue, enqueue one B query, and count scheduler selections
   rather than milliseconds. Missing B's configured turn disproves database
   fairness.
4. Pause a 4 MiB A socket after encoding begins, then submit B query plus small
   encode. If A retains query capacity or all encode capacity, phase ownership
   or delivery bounds are wrong.
5. Hold A's mutation, commit B's mutation, then release A. B must commit and A's
   queued work must disappear without corrupting its connection.
6. Saturate every ordinary class and issue cancel, health, and release. Any
   inability to start within the reserved control bound disproves the reserve.
7. Inject failure at every phase and assert all class/database/global gauges
   return to their pre-test values. Any retained permit is a stop failure.

## Expected implementation and test owners

The scheduler belongs in one new internal JVM authority namespace consumed by
`seon.db.writer`; it must not enter Datahike or the transport. Registry entries
provide database lifetime and generation identity. Writer retains ordered
mutation semantics. `seon.embed` supplies provider and KNN functions but stops
creating uncoordinated per-call pools once authority admission owns capacity.
UDS owns byte accounting and delivery completion, not job selection. Protocol
adds only observable namespaced queue/cancel/error evidence after the in-process
fixture settles its shape.

The retained adversarial fixture belongs beside writer integration tests, with
focused primitive tests beside the scheduler. Existing embedding, registry,
writer, and UDS tests remain their owners; do not create a second harness.

## Stop rules and approval points

Stop implementation and return to design when:

- provider work cannot be moved outside the connection lock without changing
  transaction/hash/receipt correctness;
- Datahike or Proximum requires undocumented global serialization;
- database identity or release generation is unavailable to queued jobs;
- fairness requires a global FIFO or fixed executor/thread per database;
- cancellation claims rollback after Datahike accepted a mutation;
- borrowing can consume a protected floor or cannot be accounted exactly;
- a test needs production sleeps or an external provider to establish order;
- metrics retain DB values, query arguments, results, or errors; or
- any class remains unbounded in jobs, bytes, retries, or executor growth.

Owner approval is required before freezing: equal-weight quantum/cost units,
aging curve, each global and per-database queue/permit/byte bound, protected
floors, borrow/reclaim behavior, provider-backoff permit policy, abandoned
result policy, overload response, and weighted-database capability. The fixture
should make those alternatives measurable rather than bury them in constants.

## Graduation

Unit 4 graduates only when the retained fixture passes real 2/4/8-database
mixed loads, every shortest falsifier, deterministic failure/cancel/release and
shutdown cleanup, and a measured modest-hardware profile. Report p50/p95/p99
latency, throughput, allocation/retained memory, peak threads, queue/byte peaks,
fairness ratios, maximum starvation turns, and lock-hold time for every policy
candidate. A green average throughput number without protected progress and
zero-retention evidence is not graduation.
