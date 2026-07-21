---
type: research
status: completed
tags: [research, prd, database, flow]
---

# One-host dispatcher replacement design — 2026-07-16

## Decision

Strengthen `seon.db.executor` in place into the one authority-wide admission,
fairness, and capacity owner. Start it once from `seon.db.writer`; do not start
one executor per work class. Keep one fixed set of platform workers for local
CPU work, a bounded virtual-thread executor for provider I/O, and Datahike's
existing ordered writer per connection. All three consume limits from one
immutable startup capacity map and publish evidence from one dispatcher.

“One dispatcher” therefore means one capacity decision and one queue owner,
not one Java execution primitive. A Gemini retry waiting on the network must
not occupy a CPU worker. A Datahike transaction must retain its existing
per-connection ordering rather than being reordered by a generic pool. A
query, pull, KNN search, encoding phase, or HNSW construction does consume the
shared CPU ceiling. This is the smallest design that prevents
oversubscription without serializing independent resources.

Selection rotates ready work classes, then databases within the selected
class. The state transition is an ordinary pure function under the executor's
existing short lock. Queues retain ordinary request data and result promises;
they never retain a connection, DB, index, socket, stream, thread, provider
client, or encoded response body.

Lifecycle, health, cancellation, capabilities, and release admission bypass
heavy queues only while constant-time. Any heavy work they initiate—draining,
query cancellation, database close, encoding a response—runs through the
owning bounded mechanism. This keeps control responsive without creating an
unbounded “priority” executor.

## Dependency ledger

- Seon `020a1e601527c402bdde0ae7e4e9250ad888aee5`:
  `src/seon/db/executor.clj`, `src/seon/db/writer.clj`,
  `src/seon/embed.clj`, `src/seon/db/transport/uds.clj`, and their focused
  tests under `test/seon/db/`.
- Datahike `092f5b0580c892c32b1dc65bf9acdbe37db90c4f`:
  its per-connection writer, query cancellation/single-flight, immutable DB
  values, and exact generation release remain the resource owners.
- Babashka process `16a84e0af0da51b8c84e289970f6b7cc35b35d18`:
  `reference-code/babashka-process/src/babashka/process.cljc` and Seon's
  `script/seon/dev/process.clj` remain the outer process-lifecycle owner.
- OpenJDK 26.0.1 source:
  `java.util.concurrent.Executors`, `ThreadPoolExecutor`, and
  `java.lang.Runtime` from the installed `lib/src.zip`.
- Prior evidence:
  [[single-jvm-host-capacity-2026-07-16]],
  [[execute-many-value-reuse-2026-07-16]], and
  [[direct-bun-jvm-session-replacement-contract-2026-07-16]].

## Current implementation and exact problems

`seon.db.executor` already has the right core algorithm: persistent FIFO queues
per database, database round robin before work starts, one exact scope fence,
request-ID joining, queued/running cancellation distinctions, and bounded
evidence. Preserve these functions and strengthen their state transition.

The current boundary is too narrow in four concrete ways:

1. `writer/start!` constructs independent eight-worker read and six-worker
   embedding executors, each with its own 64-job bound. Neither knows what the
   other is running.
2. `seon.embed/embed-texts` constructs another process-wide six-thread
   `ThreadPoolExecutor` with a 64-batch queue. One admitted embedding job may
   therefore fan out into six more platform threads.
3. `maximum-queued` is per database but there is no global queue bound, byte
   bound, work-class active bound, or shared CPU ceiling. The number of active
   databases can grow retained work without bound.
4. `stop!` rejects new work and joins every worker with no timeout. It cannot
   distinguish cancellable reads, repairable derived work, accepted mutations,
   and non-interruptible native work.

The current queue lock does not execute user work and is not the bottleneck to
remove. The smallest safe implementation keeps the one lock and makes every
admission/selection/finalization update a short immutable-state transition.
Introducing semaphores, one queue per Java executor, or concurrent collections
would duplicate the admission truth and make exact release harder to prove.

## Java and Babashka source constraints

OpenJDK's `Executors/newFixedThreadPool` constructs a
`ThreadPoolExecutor` with an unbounded `LinkedBlockingQueue`. It is therefore
not an acceptable admission owner. The current explicit Seon queue should
remain the bound, with exactly `CPU total` long-lived platform workers pulling
selected jobs from it.

`Executors/newVirtualThreadPerTaskExecutor` explicitly creates an unbounded
number of virtual threads. Virtual threads reduce waiting-thread memory; they
do not impose provider concurrency or queue bounds. The dispatcher must admit
provider work before submitting it and use the configured provider active
limit as the maximum outstanding SDK calls, including retries.

OpenJDK documents `shutdownNow` as best-effort interruption with no guarantee
that running work terminates. Datahike query cancellation is the stronger seam
for queries; Proximum bounds/timeouts and eventual completion are the seams for
native KNN; accepted mutation receipts are the seam for writes. Do not claim
that Java interruption rolls back any of them.

`Runtime.availableProcessors()` is container-aware JVM input but its own
source documentation says the value may change. Seon's selected contract is
an immutable startup capacity value: read it once, apply an explicit operator
override if present, record both observed and selected values, and restart to
change capacity. Dynamic resizing would complicate HNSW reservations,
measurement reproducibility, and shutdown without a current product benefit.

Babashka process is deliberately outside the dispatcher. Its `process` wraps
`ProcessBuilder`, supports `:exit-fn`, and installs/removes a shutdown hook;
`destroy-tree` walks `ProcessHandle` descendants and calls `destroy`.
`script/seon/dev/process.clj` already adds stronger process containment and
terminal evidence. Use that for JVM/Bun crash recovery. Do not make the JVM
dispatcher supervise Bun children or make Babashka inspect internal jobs.

## Immutable capacity map

Create the map once in `seon.db.executor/capacity` (or a private pure helper
called by `start!`) from the observed processor count and optional explicit
override. Use existing names where they exist and qualified executor names for
the new local facts:

```clojure
{:seon.db.executor/available-processors 4
 :seon.db.executor/selected-processors 4
 :seon.db.executor/cpu-workers 3
 :seon.db.executor/classes
 {:read     {:seon.db.executor/maximum-active 3
             :seon.db.executor/maximum-queued 24
             :seon.db.executor/maximum-queued-by-database 8}
  :knn      {:seon.db.executor/maximum-active 1
             :seon.db.executor/maximum-queued 4
             :seon.db.executor/maximum-queued-by-database 2}
  :encode   {:seon.db.executor/maximum-active 1
             :seon.db.executor/maximum-queued 12
             :seon.db.executor/maximum-queued-by-database 4}
  :provider {:seon.db.executor/maximum-active 4
             :seon.db.executor/maximum-queued 8
             :seon.db.executor/maximum-queued-by-database 2}
  :mutation {:seon.db.executor/maximum-active 2
             :seon.db.executor/maximum-queued 8
             :seon.db.executor/maximum-queued-by-database 8}
  :hnsw     {:seon.db.executor/maximum-active 2
             :seon.db.executor/maximum-queued 1
             :seon.db.executor/maximum-queued-by-database 1}}
 :seon.db.executor/maximum-request-bytes 4194304
 :seon.db.executor/maximum-queued-request-bytes 16777216}
```

The example follows the selected four-processor defaults. `hnsw` active is the
number of CPU workers reserved by the one running construction, not the number
of simultaneous builds. There is one running build process-wide and one
replaceable pending build. Delivery frame/session/global byte limits remain in
the session owner, not this map, because the dispatcher must not become a
socket buffer registry.

Keep the override as an operator/startup value rather than an environment read
inside executor code. Config resolves once and passes ordinary data to
`writer/start!`, which passes it unchanged to `executor/start!`. The runtime
map retains this immutable value for evidence; no second atom or registry is
needed.

## Exact public data and function changes

Keep the namespace and current function names. Replace the plural runtime
fields `::read-executor` and `::embedding-executor` in `writer` with one
`::executor` value.

`executor/start!` changes from:

```clojure
{::name :read ::workers 8 ::maximum-queued 64 ::execute execute-read!}
```

to:

```clojure
{::capacity immutable-capacity
 ::execute {:read execute-read!
            :knn execute-knn!
            :encode execute-encode!
            :provider execute-provider!
            :hnsw execute-hnsw!}}
```

The execute map belongs to the executor runtime and is never queued. A
submission adds only facts the admission owner needs:

```clojure
{::executor executor
 ::work-class :read
 ::database-name database-name
 ::scope exact-scope
 ::job-id request-id
 ::request request-data
 ::request-bytes decoded-frame-bytes}
```

`work-class` is internal scheduling data, not a protocol field. Derive it once
from the existing protocol operation at the writer boundary; do not ask Bun to
send two names for the same operation. Internal execute-many member jobs use
`:read`, their outer request ID plus vector position only as the already
approved process-local Datahike caller address, and zero additional admitted
wire bytes because the outer request owns those bytes.

Keep `try-submit!`, `submit-async!`, `submit!`, `cancel!`,
`fence-and-drain!`, `evidence`, and `stop!`. Add no callback to a queued job.
Provider execution can use a dispatcher-owned virtual-thread executor after
admission; its completion calls the same `finish-work!` transition. Mutation
admission should use a dedicated `admit!`/`finish!` path in this namespace but
continue calling the existing Datahike transaction function on the request
thread in the first cut. Moving transaction execution is a separate measured
change and is not required to gain a global mutation bound.

`remove-database!` is only appropriate for repairable derived work. Preserve
`fence-and-drain!` for exact DB-valued reads. Make the selected class behavior
data in the runtime execute specification—cancel function and whether queued
work is abandoned on release—rather than conditionals spread through writer.

## State shape and class-then-database selection

Use this conceptual immutable state under the existing lock:

```clojure
{::class-order [:read :knn :encode]
 ::class-cursor 0
 ::ready
 {:read {::database-order ["a" "b"]
         ::database-cursor 0
         ::by-database {"a" queue-a "b" queue-b}}
  :knn  {::database-order ["a"]
         ::database-cursor 0
         ::by-database {"a" queue-knn}}}
 ::jobs {request-id {::status :queued ::work-class :read ...}}
 ::running-by-class {:read 2 :knn 0}
 ::running-by-database {"a" 1 "b" 1}
 ::queued-by-class {:read 3}
 ::queued-by-database {[:read "a"] 2 [:read "b"] 1}
 ::queued-request-bytes 4096
 ::closed-scopes #{...}}
```

The actual keys should remain qualified, and counts that are cheaply derived
need not be duplicated unless measurement shows the scan matters. The
selection transition is:

1. Starting at `class-cursor`, inspect each CPU class once.
2. A class is eligible when it has a ready job, is below its class active cap,
   and enough shared CPU workers remain. HNSW is eligible only when its whole
   declared worker reservation fits.
3. Within the first eligible class, use the existing database round robin and
   pop one FIFO job.
4. Advance both cursors past the selected class/database, mark the job running,
   and update counts before releasing the lock.
5. If no class is eligible, the platform worker waits on the existing lock.

This gives the required trace `read/a, knn/a, encode/b, read/b, ...` when all
classes are ready and caps permit. Empty classes/databases are skipped. Do not
add weights, deficit, aging, stealing, or random selection. A job completion
notifies all waiting workers; the lock decides which class/database is next.

Provider and mutation admission use the same global/per-database accounting
but are not selected by a CPU worker while waiting on I/O or Datahike ordering.
Provider admitted work is submitted to the virtual executor. Mutation
admission returns a small permit token that `writer` relinquishes in `finally`.
Large provider response parsing and embedding assertion construction re-enter
`:encode` CPU work before derived publication.

## Count and byte admission

Admission is one atomic transition. Reject when any of these is true:

- dispatcher stopped or exact scope closed;
- duplicate request ID has incompatible request data;
- class global accepted count reaches its bound;
- class/database accepted count reaches its bound;
- one request exceeds `maximum-request-bytes`; or
- total queued request bytes would exceed its bound.

Count accepted work consistently: queued plus running for class/database
limits. Count request bytes while queued; subtract them exactly when selected,
canceled, fenced, rejected before retention, or abandoned. The selected job
then owns its decoded request until completion, so evidence should separately
report running request bytes if density measurement requires it.

The transport supplies exact decoded frame bytes from framing. Internally
generated jobs supply a conservative precomputed byte charge owned by their
outer request; never serialize again merely to estimate weight. Result and
encoded-frame bytes belong to paging/session delivery and must not remain in
dispatcher state after the result promise is delivered.

Use the initial bounds from the capacity report: read global
`max(16, 8*CPU)`, read/database 8; KNN global `max(4, 2*KNN)`, KNN/database 2;
encode global `max(8, 4*encode)`, encode/database 4; provider global twice
active, provider/database 2; mutation global `max(8, 4*mutation)`,
mutation/database 8. Validate the whole map at startup and reject impossible
combinations such as class active greater than CPU workers.

## Lifecycle and shutdown

Control bypass is a constant-time state transition, not unrestricted work:

- `capabilities`, health, evidence, and cancellation inspect bounded maps;
- acquire may register database intent, but opening/restoring a DB uses bounded
  mutation/control admission before expensive storage/index work;
- final release atomically closes the exact scope, rejects and settles queued
  work, calls the class-specific cancellation seam for running work, then waits
  for every job and retained execute-many DB owner to relinquish that scope;
- only after that drain may the registry release Datahike resources; and
- response encoding enters `:encode` or the bounded session writer.

Authority shutdown should have four explicit phases:

1. atomically stop admission while keeping evidence/cancel available;
2. settle queued repair/provider/encode jobs as rejected and cancel queued or
   running reads through their real cancellation seams;
3. allow accepted mutations to reach a receipt/terminal result and wait for
   running native work to relinquish owned DB/index values; and
4. stop the virtual executor, notify CPU workers, join them, close provider
   and registry resources, and report zero retained jobs/bytes.

Do not close DB resources underneath running work and do not return
`stopped? true` while jobs remain. Because Java interruption cannot guarantee
termination of arbitrary/native work, the in-process stop may wait after
cooperative cancellation. The existing Babashka operator owns the external
deadline and may terminate the whole contained JVM if it cannot drain. This is
safer than a bounded `stop!` that lies or leaves host-owned values running
against closed resources.

A provider retry retains its provider admission through backoff, preventing
retry amplification. On shutdown it observes interruption/cancellation and
does not publish derived data. This does not affect the primary transaction;
the existing source-hash repair scan remains the recovery mechanism.

## Deletion inventory for the atomic cut

Delete in the same implementation cut that starts the shared dispatcher:

- `read-worker-count`, `::read-executor`, `::embedding-executor`, and both
  independent `executor/start!`/`stop!` calls in `seon.db.writer`;
- fixed `maximum-queued 64` literals in writer;
- `max-embed-concurrency`, `embed-queue-capacity`,
  `!embedding-executor`, `embedding-thread-sequence`,
  `embedding-thread-factory`, `new-embedding-executor`,
  `embedding-executor`, `shutdown-embedding-executor!`,
  `reset-embedding-executor!`, `cancel-embedding-futures!`, and the
  future/window executor logic in `embed-batches!`;
- imports made unreachable in `seon.embed`: `ArrayBlockingQueue`, `Callable`,
  `ExecutorService`, `Future`, `ThreadFactory`, `ThreadPoolExecutor`,
  `ThreadPoolExecutor$AbortPolicy`, and its executor-only `TimeUnit` uses;
- tests and fixtures that construct separate named executors merely to model
  production; retain focused pure queue tests but update them for class then DB;
- any writer evidence fields that expose executor instances by class; and
- later, in its owning atomic transport cut, the UDS per-connection platform
  threads and frame-count publisher queues. Do not delete them in the
  dispatcher cut before the persistent selector is ready.

Do not delete Datahike's per-connection writer, query cache/single-flight,
request cancellation, Proximum index, or Babashka lifecycle. Do not introduce
another scheduler namespace, provider queue, mutation queue, or capacity atom.

## Test design

### Pure state-transition tests

- one class/one DB preserves FIFO;
- ready classes rotate before returning to the same class;
- databases rotate inside each class;
- class caps skip an otherwise ready class without moving its queued job;
- one database cannot exceed its per-database count before global admission;
- global count and byte rejection are identity transitions except rejection
  evidence;
- cancel, select, finish, fence, and abandonment subtract bytes exactly once;
- an exact closed generation rejects while a replacement generation proceeds;
- HNSW starts only when its full worker reservation fits; and
- empty class/database removal keeps both cursors valid.

### Concurrency and lifecycle tests

- at processor overrides 2/4/8, a latch records that simultaneous CPU bodies
  never exceed 1/3/7;
- saturating read/a still lets read/b run on its next database turn;
- saturating read/a still lets ready KNN or encode take its next class turn;
- provider sleeps on virtual threads without reducing CPU progress and never
  exceeds its active/queued limits;
- provider retry retains one permit and shutdown prevents derived publication;
- mutation admission is globally and per-database bounded while independent
  Datahike connections still commit concurrently;
- queued/running read cancellation and exact release preserve current truth;
- release between execute-many resolution and member completion waits for the
  retained scope;
- non-interruptible pull/KNN makes release wait without blocking health/cancel;
- stopping rejects new work, drains accepted mutations, settles repair work,
  joins every worker, and reports zero retained jobs/bytes; and
- a forced outer JVM termination is tested through the existing operator,
  never by pretending `shutdownNow` guarantees native termination.

### Integration gates

Retain and extend `seon.db.executor-test`, `seon.db.writer-integration-test`,
`seon.embed-writer-test`, `seon.db.request-receipt-test`, and UDS tests. Add a
mixed two-database fixture rather than one test namespace per class. Assert
data, transitions, bounds, and release evidence—not timing except for generous
deadlock deadlines.

## Measurement and graduation

Measure release builds with selected processor counts 2, 4, and 8 against the
current 8+6+6 platform-thread baseline. Use 1/2/4/8 databases and mixed hot
identical reads, cold distinct reads, pull, execute-many, writes, provider
delay/retry, KNN, encode, and HNSW construction.

Record throughput, p50/p95/p99 queue and end-to-end latency, CPU time,
allocation rate, heap/RSS/direct/mapped bytes, live/peak platform and virtual
threads, per-class/database service counts, maximum scheduler turns before
progress, accepted/running/queued counts and request bytes, Datahike writer
depth, provider connections, and native HNSW workers.

Graduation requires all of the following:

- observed simultaneous CPU bodies never exceed the selected ceiling;
- database B and another eligible class progress while database A saturates;
- health, cancel, and release admission remain responsive under saturation;
- every count and byte bound rejects deterministically without retained data;
- provider waiting no longer creates six scheduler plus six provider platform
  threads;
- exact release and shutdown return jobs, scopes, queued bytes, provider
  permits, and worker threads to zero;
- throughput/latency on 2/4/8 processors is compared to the old independent
  pools, with no unexplained regression; and
- the deleted independent executors and nested embedding pool are unreachable
  by source search.

## Consequential measured choices for Sean

The design does not require a user decision before implementation. Start with
the capacity report defaults. Bring Sean measured alternatives before changing:

- leaving one processor free versus using every processor on a dedicated host;
- allowing two-core KNN/encode oversubscription for latency;
- KNN active limits and resource/patience defaults;
- provider active count and whether retry backoff should retain its permit;
- request/frame/session/global byte bounds;
- whether HNSW construction may start while interactive work is queued; and
- the later one/two/four authority-shard decision.

The implementation seam remains stable across those choices: they change the
one immutable capacity map, not callers, protocol operations, Datahike, or the
fairness algorithm.
