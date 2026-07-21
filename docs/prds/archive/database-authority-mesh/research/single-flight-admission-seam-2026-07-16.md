---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Datahike single-flight admission seam — 2026-07-16

## Recommendation

Add one host-only, two-phase query-call API to the maintained Datahike fork:
Datahike acquires the exact completed-cache/single-flight identity and returns
an opaque query-call owner; Seon admits only a call that Datahike says must run.
Joined calls attach to Datahike's existing completion without entering the
Seon read executor. The owner binds its actual execution thread only when the
read worker starts it.

Do not expose the current private `single-flight/acquire!` directly. It records
the acquiring thread as the owner, so acquiring on a socket or resolution
thread and running on a CPU worker breaks the existing same-thread reentrancy
guard. It also exposes the private flight key and leaves cache recheck,
resource evidence, completed-cache publication, and failure cleanup available
to be assembled incorrectly by the caller.

The smallest sound interface is conceptually:

```clojure
(d/acquire-q! query-map)                 ; opaque host query call
(d/q-call-state call)                    ; bounded data, see states below
(d/on-q-complete! call complete!)        ; nonblocking host handoff
(d/run-q! call)                          ; only for :run; exactly once
(d/cancel-query! request-id)             ; existing operation
```

Names are illustrative. The implementation should keep the current public
`q` and `q-with-evidence` functions as synchronous compositions of the same
mechanism. No new protocol operation or wire value is required.

The ordinary state returned by `q-call-state` needs only existing Clojure
words:

- `:completed` — a completed-cache result is already in the call;
- `:run` — this caller owns either the coordinated cold computation or an
  uncacheable direct computation;
- `:waiting` — another caller owns the exact cold computation; and
- `:rejected` — remote admission must not amplify a bounded coordinator
  overflow.

This is an internal host boundary, not a new application concept. The wire
still has only the existing request ID, database coordinate, query, result,
cache/resource evidence, and error values.

## Dependency ledger

The audit uses the exact maintained Datahike commit
`d9765276cd8d0778f39e93046c2d59b8c2fa8ff2` and these owners:

- `reference-code/datahike/src/datahike/query.cljc` — normalization,
  completed-cache identity, cache recheck, query execution, evidence, and
  public cancellation;
- `reference-code/datahike/src/datahike/query/single_flight.cljc` — exact
  in-flight identity, caller registration, completion, cancellation,
  reentrancy, ABA fencing, release, and metrics;
- `reference-code/datahike/src/datahike/resource.cljc` — per-call resource
  budgets and completed-result certification;
- `reference-code/datahike/src/datahike/connector.cljc` — generation open and
  final-release fencing;
- `reference-code/datahike/src/datahike/api/specification.cljc` — host-returning
  operation descriptions;
- `src/seon/db/executor.clj` — bounded fair work admission and physical
  completion; and
- `src/seon/db/writer.clj` — exact-coordinate resolution, request ownership,
  cancellation, release drain, and Datahike query invocation.

The observed starvation is retained in
`test/seon/db/executor_test.clj:941-1053` and
[[../../issues/datahike-single-flight-waiters-consume-seon-read-workers]].

## Confirmed cause

Seon creates one `:read` job per query before Datahike sees the query
(`writer.clj:2220-2253`). A CPU worker then resolves the exact database and
calls `q-with-evidence` (`writer.clj:634-686`). Datahike checks its completed
cache and acquires single-flight only inside that call
(`query.cljc:4221-4298`). A joined caller blocks by dereferencing its Clojure
promise (`single_flight.cljc:149-158`) while the executor continues counting
that job as running (`executor.clj:332-345`).

With three read workers, the retained proof starts one cold owner and two
identical joiners for database A. All three executor workers remain occupied,
although only one query computes. An unrelated database B cannot enter until A
finishes. Datahike correctly reports one `miss-owner`, two `miss-joined`, and
one predicate execution; the defect is the order of Seon admission relative to
Datahike acquisition, not duplicate Datahike work.

Increasing the worker count only raises the number of identical callers needed
to reproduce the same gate. A second query pool would add oversubscription and
leave the ownership inversion intact.

## Exact Datahike invariants the seam must retain

### Identity and completed-cache ordering

The cold identity is entirely Datahike-owned:

```clojure
[[connection-id generation commit-id]
 [query non-db-args offset limit order-by planner-mode]
 max-work max-results max-result-weight]
```

The actual construction is at `query.cljc:4222-4240,4265-4268`. Seon must not
receive or reconstruct it. Scale-sensitive values, all non-database inputs,
planner mode, the exact committed database generation, and resource limits are
part of correctness.

A completed hit bypasses single-flight and certifies the cached result against
this caller's resource limits (`query.cljc:4241-4256`). On a miss, the owner
must recheck the completed cache after acquisition, compute with the shared
cooperative cancellation signal, cache only a successful weight-certified
value under the captured cache epoch, publish dependency/resource evidence,
and then complete callers (`query.cljc:4257-4298` and
`single_flight.cljc:160-198`). The new API should move this code into the query
call owner, not duplicate it in Seon.

### Request cancellation

Each existing request ID owns one caller completion. `cancel!` removes only
that caller, wakes it with the existing query-canceled error, and sets the
cooperative signal only when the final caller leaves
(`single_flight.cljc:200-242`). The reverse request-ID index and the exact
flight entry change in one coordinator CAS. Seon should continue calling
`d/cancel-query!` by the existing universal request ID.

The split API needs one additional internal case: if the final caller cancels
while the owner is acquired but has not begun `run-q!`, Datahike must
compare-remove that exact unstarted owner immediately. Otherwise canceling the
queued Seon job leaves a flight that can never finish. If `run-q!` already won
the start CAS, cancellation sets the existing cooperative signal and physical
completion retains ownership until the run exits.

### Reentrancy and owner-thread transfer

The current `acquire!` captures `Thread/currentThread` at acquisition and uses
identity to bypass a nested same-key query on that thread
(`single_flight.cljc:39-49,63-74`). This is safe only because acquisition and
compute currently occur in one synchronous call.

For pre-admission acquisition, an entry must begin with no execution thread.
`run-q!` atomically changes the exact owner from `:acquired` to `:running` and
records its current worker thread. A nested same-key `q` on that worker then
retains the existing direct reentrant path. `run-q!` is exactly-once; a second
start or a start against a removed/replaced entry returns a tagged internal
failure rather than computing.

The entry's existing unique cancel volatile is sufficient ABA identity.
Compare-removal already tests that identity before an old owner can remove a
successor (`single_flight.cljc:78-93`). The new owner phase should use the same
identity rather than adding another public ID.

### Release and shutdown

Final Datahike connection release closes the exact cache generation before
writer and storage shutdown (`connector.cljc:483-498`). Generation close
removes matching flights, signals their cancellation, and completes every
caller with the scope-closed error (`single_flight.cljc:251-279`). A queued
owner whose call was removed must therefore fail its start-token check and
must never compute or publish a cache result.

Seon already fences queued/running scope jobs and waits for active requests
before releasing the database (`writer.clj:1469-1502,1817-1824`). Waiting
query calls must remain in that existing active-request map even though they do
not own executor capacity. Datahike's scope-close completion removes them
through the ordinary writer completion path; only then may Seon's release wait
finish. Authority shutdown uses the same ordering: stop socket admission,
cancel/fence calls, drain active completions, stop executor work, then release
connections. No completion token survives the active request or Datahike
flight that owns it.

## Ordinary data and host owners

The boundary should be structural:

| Value | Owner and lifetime | May cross the database protocol? |
|---|---|---|
| request ID, query form, arguments, coordinate, limits | existing request data until response | yes |
| `:completed`, `:run`, `:waiting`, `:rejected` evidence | bounded observation of a live query call | no need; may be logged/metric data |
| immutable Datahike database, normalized query, cache epoch/key | opaque Datahike query-call owner until completion | no |
| flight entry, cancel volatile, execution thread, completion cell | private Datahike coordinator until completion/cancel/release | no |
| Seon completion function | writer active request until exactly one terminal callback | no |
| final query result, attribute dependencies, cache/resource evidence | ordinary response after materialization/validation | yes |

The opaque owner may retain the immutable database because Datahike needs it to
run and to validate cache publication. It must not expose a database field or
cache key for Seon to inspect. Seon's active request may retain the owner, but
the fair executor queue should receive only that owner and existing scope/job
data, never a second Seon copy of the database or key.

## Integration sequence

1. Resolve the exact immutable database under the existing fair read boundary.
   Head resolution is cheap, while historical `commit-as-db` can own storage
   and secondary-index work and must not move onto a socket/codec thread.
2. Call `acquire-q!` after resolution but before admitting query computation.
   For `execute-many`, this is directly after its existing one-time resolution
   job and before progressive member admission.
3. Store the opaque query call in the existing writer active request. Register
   its completion handoff without waiting on the caller, codec worker, or CPU
   worker.
4. For `:completed`, deliver and release immediately. For `:waiting`, submit no
   read job. For `:run`, submit one read job whose body is only `run-q!`. For
   `:rejected`, return the existing bounded busy/database error rather than
   running an uncoordinated duplicate.
5. The terminal completion removes the active request, releases any historical
   materialization exactly once, and performs the existing admission-only UDS
   send. Cancellation remains addressed by the existing request ID.

This introduces a short, admitted resolution phase before Datahike can know
the exact database value. It does not park a worker. Historical loading is real
database work and should remain fairly bounded; pretending it is free to claim
literal zero pre-acquisition admission would move blocking I/O into the socket
path. The acceptance law is therefore precise: only the single-flight owner
holds read-computation capacity; every caller may pay the bounded exact-value
resolution necessary to identify what it is asking about. `execute-many`
already pays that resolution once for all members.

## Alternatives

### Explicit acquire/run owner — recommended

This keeps exact identity, normalization, cache publication, evidence, and
release inside Datahike while allowing Seon to choose whether a CPU job exists.
It adds one opaque host owner and a small explicit phase transition. It also
supports owner-thread transfer correctly and gives deterministic cancellation
of an owner canceled before execution.

The API should remain host-only (`supports-remote? false`,
`returns-host-value? true`) if described in Datahike's API specification. It is
not a semantic database capability and should not appear in the bounded remote
capability catalog.

### Nonblocking completion token alone — insufficient

Returning the existing promise, a `CompletableFuture`, or a core.async channel
for joiners can free the worker thread, but it does not by itself move exact
acquisition before Seon admission. If the executor continues counting the
waiting job as physically active, unrelated work is still gated. If it marks
the job complete, cancellation, scope drain, and historical database release
silently move to an unowned token unless the writer adds the same lifetime
model described above.

A `CompletableFuture` also exposes misleading per-caller cancellation on a
shared computation. A core.async promise channel schedules callbacks through
another execution mechanism and changes the hot synchronous primitive without
need. A Clojure promise preserves current behavior but requires one parked
thread per remote waiter to become callback-driven. The recommended opaque
completion cell should support one bounded host callback while synchronous
`q` continues to block through a private promise. Datahike invokes no arbitrary
query or transport work; Seon's callback only hands the terminal value to the
existing active-request completion path.

### Acquire inside the read worker, then yield joiners — useful fallback only

The smallest local patch would split current `execute!` so a joined worker
returns a completion token immediately. That removes long worker parking and
would let database B run after the A joiners yield. It does not stop duplicate
A requests from consuming fair queue positions, admission bytes, job
identities, and brief CPU entries before B. It also complicates the executor's
meaning of physical completion and leaves pre-start owner cancellation
unsettled. Keep this as a falsifier or rollback option, not the target seam.

### Scheduler callback passed into Datahike — reject

Letting Datahike submit owner work directly to a Seon executor would avoid an
opaque returned owner, but makes the transport-free database dependency retain
authority scheduler functions and policy. It couples Datahike release to
Seon's classes/queues and prevents another host from selecting a different
executor. Datahike should report ownership; Seon should admit it.

### Expose the flight key — reject

Any API returning the normalized cache/flight key invites the authority to
reproduce completed-cache lookup, resource-limit identity, or generation
fencing. It also exposes process-local connection identity. The opaque owner is
both less surface and stronger encapsulation.

## Deterministic acceptance proof

The implementation is ready only when one retained proof covers the integrated
Datahike and Seon lifetime, not two independently green unit tests:

1. Start three Seon read workers. Resolve and acquire one blocked cold query A,
   then acquire at least 32 identical A calls. Assert one A read-computation
   job, 32 Datahike active callers, and no waiting A executor jobs.
2. Submit distinct query B for another database. Require B to enter and finish
   before A releases. Then release A and assert one predicate execution, one
   owner outcome, 31 joined outcomes, and identical results/evidence.
3. Acquire an owner on one thread and run it on another. From the run thread,
   execute the same-key nested query and prove the existing reentrant bypass
   completes rather than waiting on itself.
4. Cancel one waiting request and prove only it completes canceled. Cancel the
   final queued, unstarted owner and prove its Seon job and Datahike flight both
   disappear without executing query work.
5. Race `run-q!` against final cancellation. Exactly one phase wins: either no
   compute occurs, or the running owner observes the cooperative signal and
   retains physical ownership until its terminal completion.
6. Close the exact database generation after acquire but before run. Require
   every caller to receive the scope-closed failure, reject the stale run, and
   retain zero flights, callbacks, active requests, materialized database
   owners, or executor identities.
7. Reopen the same database and acquire the same query. Release a stale old
   owner afterward and prove it cannot complete, cache into, cancel, or remove
   the successor generation.
8. Repeat success, shared failure/retry, cache hit-after-acquire, resource-limit
   failure, connection release, cache clear, and authority shutdown. Every path
   must leave zero active flights/callers and exactly one terminal callback per
   request.
9. Keep existing synchronous CLJ and CLJS `q`/`q-with-evidence` return values,
   exceptions, cache outcomes, resource evidence, and cancellation fixtures
   unchanged. CLJS keeps direct synchronous execution; the host acquisition
   benefit is JVM-only.
10. Measure 2/8/32/256 identical cold callers plus mixed unique queries. Record
    owner count, queue age, completion-handoff cost, retained bytes per waiting
    call, B latency before A release, and coordinator CAS contention. Tune no
    worker count until this trace is green.

## Decision

Implement the Datahike-owned opaque query call with explicit acquire and run,
an unstarted-to-running owner transfer, and a nonblocking host completion
handoff. Integrate it after exact immutable-value resolution and before query
computation admission. This is the narrowest seam that fixes starvation while
preserving Datahike's exact identity, synchronous API, cancellation truth,
resource evidence, release fencing, and ABA safety.
