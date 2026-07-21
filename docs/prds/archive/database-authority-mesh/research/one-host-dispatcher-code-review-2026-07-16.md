---
type: research
status: completed
tags: [research, prd, database, flow]
---

# One-host dispatcher code review — 2026-07-16

## Review boundary

This is a commit-specific review of `d684c0f8` (`Unify JVM database work
capacity`). It does not review later working-tree edits. The review falsified
the implementation against [[roadmap]],
[[single-jvm-host-capacity-2026-07-16]],
[[one-host-dispatcher-replacement-design-2026-07-16]], the pinned Datahike and
Proximum sources, and the OpenJDK 26.0.1 source installed on the development
host.

The commit correctly replaces the separate Seon read and embedding admission
queues with one `seon.db.executor` value. Read work uses bounded platform CPU
workers; provider work uses separately bounded virtual threads; admission and
completion share one job registry; exact-scope read release still fences,
cancels, and drains. These are useful foundations.

It does **not** yet satisfy the one-host-capacity contract. No P0 corruption or
cross-generation execution was reproduced, but the P1 findings below block
graduation and any claim that host work is bounded by one capacity owner.

## Evidence run

- `bin/test-writer seon.db.executor-test`: 10 tests, 60 assertions, zero
  failures and zero errors.
- A direct pure-state probe interleaved CPU and provider selection over class
  order `[:read :knn :provider]`. The selected request IDs were
  `["r1" "p1" "r2"]`; without provider selection the next CPU class would
  have been `:knn`. The provider dispatcher therefore changes CPU class
  rotation.
- OpenJDK 26 `Executors.java:255-266` confirms that
  `newVirtualThreadPerTaskExecutor` creates a new virtual thread for each
  accepted task. `ThreadPerTaskExecutor.java:163-190` confirms that `close()`
  waits until every task terminates, interrupting tasks only when the thread
  calling `close()` is itself interrupted.
- Datahike's selected writer still defaults both its transaction and commit
  buffers to 120,000 entries in
  `reference-code/datahike/src/datahike/writer.cljc:78,281-299`.
- Production call-site search found dispatcher submissions only for `:read`
  and `:provider`. Transaction, KNN, replay, and response encoding remain
  direct calls in `seon.db.writer`.

## P1 findings

### P1 — the capacity map describes work it does not control

`d684c0f8:src/seon/db/executor.clj:105-123` declares limits for read, KNN,
encode, provider, mutation, and HNSW work, but
`d684c0f8:src/seon/db/writer.clj:1586-1591` installs execute functions only for
read and provider. The request dispatcher still calls `handle-transact`
directly, which locks the connection and synchronously calls `d/transact`
(`writer.clj:886-944,1362-1370`). KNN likewise calls the configured search
function inline (`writer.clj:1430-1441`). Replay and encoding also remain
outside the dispatcher.

Consequences:

- the advertised mutation global/per-database bounds are inert;
- every database retains Datahike's generic 120,000 + 120,000 writer buffers;
- KNN can consume caller threads and native CPU without the shared CPU ceiling;
- no encode or HNSW construction reservation exists; and
- the Proximum construction pool remains an independent hidden capacity owner.

The commit is therefore a shared read/provider dispatcher, not yet one host
dispatcher.

Acceptance gate: route mutation admission, KNN, response encoding, and HNSW
construction through the one capacity decision, or remove their limits from
the map until the atomic replacement does so. Configure Datahike's internal
writer bounds no larger than the approved admission envelope. Prove mixed
read/KNN/encode/mutation/provider load never exceeds the selected CPU,
provider, native-worker, or writer bounds.

### P1 — request-byte accounting is disconnected from real requests

Admission defaults `request-bytes` to zero
(`d684c0f8:src/seon/db/executor.clj:319-338`). Neither production read nor
provider submissions supply it (`d684c0f8:src/seon/db/writer.clj:604-617,
707-721`). The UDS framing owner does not pass decoded frame length into
`handle-request`, so the 4 MiB per-request and 8/16/32 MiB queued-byte limits
accept arbitrarily large real request values while evidence reports zero.

The focused byte test proves only manually supplied accounting. It cannot
prove transport-to-dispatcher accounting.

Acceptance gate: the framing/session owner supplies the exact decoded frame
bytes once; every admitted outer request carries that value; joined requests
do not double count it; select, queued cancel, fence, rejection, and stop each
subtract exactly once. Add an integration test that sends real framed requests
and observes rejection and zero retained bytes after completion.

### P1 — provider selection perturbs CPU fairness

`take-ready` stores one shared `class-cursor` in dispatcher state
(`d684c0f8:src/seon/db/executor.clj:175-196`). Both the CPU workers and the
provider dispatcher call it with different allowed-class sets
(`executor.clj:273-309`). A provider selection advances the same cursor used
for CPU rotation.

The direct probe selected read, then provider, then read instead of read, then
provider, then KNN. Under continuous provider completions this creates
scheduler-timing-dependent CPU preference and falsifies the roadmap law that
the CPU dispatcher rotates ready CPU classes before databases. The existing
fairness test omits provider work, so it cannot detect the interference.

Acceptance gate: provider admission must not mutate CPU class rotation. Keep
one queue/capacity owner while maintaining resource-specific selection state,
then prove bounded wait for every continuously ready CPU class while provider
work starts and completes concurrently. Preserve database round robin inside
each class.

### P1 — release and dispatcher state retain closed generations forever

Fencing adds every exact scope to `closed-scopes` and never removes it
(`d684c0f8:src/seon/db/executor.clj:369-394`). Each class's `database-order`
and empty per-database queue likewise remain forever
(`executor.clj:137-164,213-229`). Releasing and reconnecting databases thus
grows generation maps and database scans for the lifetime of the authority.

This is a density and resilience failure for the intended many-cluster host:
completed release reports zero retained job identities, but the dispatcher
still retains every closed attachment/generation and every database name.

Acceptance gate: after the final running job relinquishes an exact scope,
remove that scope and remove empty database queue entries without breaking ABA
protection. A newly attached generation must remain admissible, an old
generation must remain inadmissible through the release boundary, and repeated
create/release cycles must return retained scope/database counts to baseline.

### P1 — provider release is not cancellation and can consume capacity after release

Writer release passes `#{:provider}` as abandonable work
(`d684c0f8:src/seon/db/writer.clj:1293-1301`). `fence-scope!` immediately
removes a running provider job and delivers its failure, but does not retain or
interrupt the submitted virtual-thread task
(`d684c0f8:src/seon/db/executor.clj:369-394`). The work continues until the SDK
call and retry loop finish. `execute-embedding!` correctly re-resolves the exact
generation before the derived transaction, so stale publication was not found,
but released work can still hold a provider permit, input/assertion data, SDK
resources, and a continuation for an unbounded interval.

Acceptance gate: retain the provider task handle internally, interrupt it on
exact-scope release, and prove the SDK deadline plus interruption returns the
permit and retained values. Stale repair remains the recovery mechanism; exact
release must not wait for or publish derived work.

### P1 — shutdown can wait forever on a provider task

`stop!` sets stopped, lets the provider dispatcher drain all queued work, joins
the dispatcher threads, then closes the virtual-thread executor
(`d684c0f8:src/seon/db/executor.clj:473-483`). OpenJDK's `close()` waits for all
submitted tasks. The Google GenAI client is built without an explicit request
deadline or matched connection limit (`src/seon/embed.clj:514-528`), and the
retry loop can add multiple backoff intervals. A hung SDK call therefore makes
authority shutdown unbounded.

The virtual-thread choice is otherwise sound: the dispatcher increments
provider running count before submission, so no more than `maximum-active`
provider tasks start. The missing pieces are termination control and the
second resource bound: the SDK's connection pool is not configured to match
provider admission.

Acceptance gate: configure a finite provider request deadline and SDK
connection limits equal to provider active capacity; stop admission; reject
queued repair work; interrupt running repair work; close the SDK; and prove
shutdown returns within a fixed bound with zero running jobs, retained job
identities, queued bytes, and live provider tasks.

### P1 — capacity selection is not an operator-controlled startup contract

The pure `capacity` function accepts a selected processor count, but production
writer startup always calls its zero-argument form
(`d684c0f8:src/seon/db/writer.clj:1586-1591`). There is no operator/config input
for repeatable 2/4/8-core or shared-host limits. Startup validation checks only
that execute-map classes occur in the capacity map; the capacity schema itself
is merely `:map`, so inconsistent or impossible class limits are accepted.

Acceptance gate: read one explicit startup processor override through the
existing operator/config authority, validate the complete immutable capacity
shape and relationships, and expose selected versus JVM-available processors
in evidence. Prove the same release artifact at selected 2, 4, and 8 processors.

## Claims that survived review

- A single `seon.db.executor` value now owns read/provider queue state and
  admission; no second Seon embedding executor remains in writer startup.
- CPU jobs are bounded by the number of platform workers and the shared CPU
  active count.
- Provider jobs admitted through the dispatcher are bounded by
  `provider/maximum-active`; virtual-thread parking does not occupy a Seon CPU
  worker.
- Per-class and per-database **queued job** limits work for the two integrated
  classes.
- Exact-scope queued read cancellation and running read drain remain coherent;
  the focused release test passes.
- `stop!` drains finite, terminating accepted work and OpenJDK `close()` waits
  for submitted provider tasks rather than silently discarding them.

## Graduation order

1. Fix resource-specific fairness state and bounded state cleanup first; these
   are local dispatcher invariants.
2. Connect exact transport request bytes and add lifecycle accounting proof.
3. Add interruptible, deadline-bounded provider tasks with matched SDK
   connections and bounded shutdown.
4. Route mutation and KNN through the dispatcher and reduce Datahike internal
   writer bounds.
5. Patch Proximum construction to consume authority-supplied workers, then add
   encode and HNSW reservations without nested pools.
6. Run the approved 2/4/8-core mixed-load matrix. Graduate only when CPU,
   native-worker, provider-connection, writer-depth, job, scope, and byte
   evidence all return to baseline after release and stop.
