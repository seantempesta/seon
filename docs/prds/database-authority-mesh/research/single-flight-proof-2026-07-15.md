---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Datahike single-flight proof — 2026-07-15

## Result

An isolated Clojure coordinator proved the required miss-coalescing behavior:

- 2, 8, and 32 simultaneous requests for one key computed exactly once;
- eight distinct keys reached eight simultaneous computations;
- one failure was delivered to all eight callers, removed, and retried cleanly;
- cancelling one waiter did not cancel or corrupt the shared computation;
- the last-waiter transition invoked a policy hook without forcibly interrupting
  arbitrary Datalog code;
- owner-thread same-key reentrancy bypassed coalescing instead of self-deadlocking;
- the in-flight map returned to zero after every success and failure;
- a hard entry bound produced an explicit overflow path; and
- only completed values were eligible for Datahike result caching or transaction
  propagation.

The recommended first implementation is a small JVM-only coordinator immediately
beside Datahike's existing completed-result cache. It uses Clojure promises as
one-shot tagged completion cells, atomically owned entries, and explicit waiter
identities. It does not store a Future, Promise, thread, cancellation token, or
socket in `query-result-cache`.

No Seon lifecycle, production source, or tests ran. The disposable prototype was
under `tmp/` and was removed after the proof.

## Existing source owner

The maintained Datahike query result owner is
`reference-code/datahike/src/datahike/query.cljc:2358-2620`:

- `query-result-cache` is a weighted LRU of completed result entries;
- database buckets use `db-cache-key`;
- entries contain result, attribute dependencies, and structural weight;
- `result-cache-get` touches the completed bucket;
- `result-cache-put!` stores only weight-certified completed values; and
- `propagate-query-cache` copies safe completed entries from a parent database
  value to its child after a transaction.

Transaction completion invokes propagation from
`reference-code/datahike/src/datahike/core.cljc:130-151`. Query execution and
completed-cache lookup occur later in `datahike.query`; the implementation cut
must wrap only the cache-miss compute boundary, not parsing, successful cache
hits, or transaction propagation.

The exact single-flight key cannot be frozen until database-value identity task
1 closes. Conceptually it is the completed-cache database identity plus the
existing normalized query/non-database-input cache key. It must distinguish
physical databases, branches, speculative values, history/as-of/since values,
rules, and all non-database inputs exactly as the completed result does.

## Disposable proof

The prototype exposed four operations:

- `acquire!` atomically returns owner, waiter, reentrant, or overflow;
- `finish!` delivers one tagged result and compare-removes the matching entry;
- `cancel!` removes only one waiter and reports the last-waiter transition; and
- `run!` gives synchronous Datahike-style compute-or-await behavior.

Command:

```bash
clj -M tmp/single-flight-proof.clj
```

Raw decisive output:

```text
:identical {:n 2,  :calls 1, :values {:value 2},  :state-size 0}
:identical {:n 8,  :calls 1, :values {:value 8},  :state-size 0}
:identical {:n 32, :calls 1, :values {:value 32}, :state-size 0}
:different-keys {:values [0 1 2 3 4 5 6 7], :peak 8, :state-size 0}
:failure {:values {:failed 8}, :calls 1, :state-size 0,
          :retry :recovered, :calls-after-retry 2}
:waiter-cancel {:result {:status :ok, :value :done}, :state-size 0,
                :cancellations 1, :last-waiter 0}
:last-waiter {:hooks [:last], :cancellations 1, :last-waiter 1}
:reentrant :inner
:reentrant-state {:calls 2, :state-size 0, :reentrant 1}
:bounded {:overflow? true, :state-size 0, :overflow 1}
:owner-overhead-ns 639.7
```

The overhead number is one 100,000-iteration warmed process, not JMH evidence.
It measures owner acquire plus completion/removal with UUID waiter creation and
metrics, about 0.64 microseconds per miss on this machine. Completed-cache hits
must bypass the coordinator entirely and pay none of this cost.

## Exact state machine

Each in-flight entry is process-local data:

```clojure
{cache-key
 {:completion one-shot-promise
  :owner-thread thread-identity
  :waiters #{waiter-id ...}
  :started-at monotonic-time
  :database-key exact-database-value-key}}
```

The thread and promise never cross the protocol or enter durable/cacheable data.

```text
ABSENT
  acquire within bound -> OWNER-RUNNING(waiters={owner})
  acquire over bound   -> OVERFLOW-BYPASS

OWNER-RUNNING
  another thread, same key -> add waiter -> OWNER-RUNNING
  owner thread, same key   -> REENTRANT-BYPASS
  cancel waiter            -> remove only waiter
  final waiter removed     -> LAST-WAITER-HOOK, owner still running
  owner succeeds           -> cache completed result, deliver OK, remove entry
  owner fails              -> deliver ERROR, remove entry

DELIVERED/REMOVED
  new acquire -> completed-cache hit, or a new owner if failure/not cached
```

Compare-removal uses the entry's identical completion token. A stale owner can
never remove a newer computation for the same key.

The owner must follow this order:

1. caller misses the completed-result cache;
2. acquire single-flight key;
3. if owner, recheck the completed cache to close the miss/acquire race;
4. compute through the existing query path;
5. put the successful weight-certified result in the completed cache;
6. deliver tagged success to waiters;
7. compare-remove the in-flight entry in `finally`;
8. on failure, deliver tagged failure and remove without caching.

Delivering success after the completed cache put ensures a caller arriving at
the removal boundary observes either the in-flight completion or the completed
cache, never a forced duplicate window.

## Cancellation law

Cancellation belongs to a waiter, not automatically to the computation. One
remote request timing out removes its waiter and returns a cancelled result; all
other waiters remain attached and receive the shared result.

When the last waiter leaves, the coordinator invokes a policy hook with the key
and owner cancellation signal. The first implementation should only record the
transition and set a cooperative signal. It must not use `Thread.stop`, blindly
interrupt a shared executor thread, or cancel a Java Future that other code may
own. Datahike query execution can honor the signal later at measured safe points.

If the owner completes successfully after all waiters leave, normal
weight-certified completed caching remains useful unless policy evidence says
otherwise. The result is immutable and a later caller may use it. A resource
budget may instead choose not to cache abandoned expensive results, but that is
a separate measured policy.

## Reentrancy law

If the owner thread requests the exact key while computing it, waiting on its
own promise deadlocks. The prototype detects identical owner thread plus key and
runs the nested computation outside single-flight. This restores the behavior
the unwrapped synchronous function would have had: successful recursion,
ordinary stack overflow, or application failure, but never a coordination
deadlock.

This bypass can duplicate work and should increment a metric. A real occurrence
is evidence to inspect query functions/rules for recursion. Throwing a special
coordination error would change local `q` semantics; silently waiting is worse.

## Failure law

Completion is always tagged data internally:

```clojure
{:status :ok :value result}
{:status :error :throwable cause}
```

Promises can deliver `nil` and Throwable values, so a tag is required. Every
waiter observes the same success value or cause. Failure is never written to the
completed query-result cache. Removal happens even if delivery or metrics fail,
and a later request becomes a new owner. The authority converts the cause to the
ordinary protocol error envelope at its existing boundary.

## Propagation law

`propagate-query-cache` reads only `query-result-cache`, whose entries remain
`{:result ... :attrs ... :weight ...}`. The in-flight registry is a separate,
private, process-local atom and has no propagation function. Therefore a child
database value can inherit only completed immutable results; it can never
inherit a Promise, Future, owner thread, waiter, cancellation signal, or failed
computation.

This separation is structural, not a conditional check. Do not put a pending
variant into the weighted LRU and teach propagation to skip it.

## Bounds and metrics

Unique cold keys can still create unbounded work. Single-flight removes
duplicates; it is not admission control.

The coordinator needs a global in-flight-entry bound and preferably a
per-database bound supplied by the authority scheduler. On bound overflow:

- local synchronous Datahike `q` may compute without coalescing to preserve its
  existing API semantics; and
- the remote authority should normally reject before execution with a bounded
  busy/error value rather than amplify work.

Required counters/gauges:

- owner computations, attached waiters, hits, successes, shared failures;
- active entries and waiters globally/per database;
- overflow/bypass, owner reentrancy, waiter cancellation, last waiter;
- compute duration, waiter duration, saved-computation count;
- completed-cache outcome after owner success; and
- stale-completion compare-remove failures, which should remain zero.

Metrics use counters outside durable database data and never retain query args,
database values, results, or errors after completion.

## Primitive comparison

### Clojure promise

Best first primitive for JVM synchronous `q`: one-shot, idiomatic, minimal,
supports many deref waiters, and does not silently choose an executor. Tagged
success/failure preserves arbitrary values. Cancellation remains explicit at
the waiter registry, which is the desired law.

### Clojure future

Reject as the state cell. It combines scheduling with completion, defaults can
leak onto a shared pool, and Future cancellation incorrectly suggests one
waiter owns the shared computation. The authority scheduler should choose where
owners run; single-flight should only coordinate them.

### `CompletableFuture`

Technically capable and useful if the JVM authority becomes callback-driven,
but Java-specific, more surface than required, and its cancellation/completion
methods make ownership easier to misuse. It does not solve CLJC portability.
Measure it only if promise parking appears in profiles.

### CLJC constraint

Datahike `q` is synchronous in CLJ and CLJS. JVM threads can overlap and wait;
one CLJS event loop cannot synchronously block on a JavaScript Promise. The
coordinator should therefore have a CLJC-shaped internal API with:

- the full promise/atom implementation in CLJ; and
- direct computation/no-op coordination in CLJS unless a future asynchronous
  Datahike API supplies honest Promise semantics.

Do not make synchronous CLJS `q` return a Promise or add a spin wait. The target
benefit is the multi-threaded JVM authority where all Bun processes converge.

## Recommended first implementation

1. Settle and test exact database/cache identity first.
2. Add one private Datahike single-flight coordinator beside the completed
   query-result cache, with CLJ implementation and semantics-preserving CLJS
   direct path.
3. Wrap only cacheable completed-result misses. Completed hits bypass it.
4. Reuse the existing normalized query/non-database-input cache key.
5. Recheck completed cache after ownership, then execute the existing function
   unchanged.
6. Cache only successful weight-certified results before delivery.
7. Expose internal waiter registration/cancellation to the JVM authority without
   exposing Promise or thread values through `seon.db.protocol`.
8. Add exact concurrency, failure, cancellation, reentrancy, overflow, and
   propagation tests based on this proof.
9. Benchmark cold identical queries at 2/8/32 callers and adversarial unique-key
   load before changing default bounds.

The implementation should be inside the maintained Datahike mechanism because
that is where exact immutable database values, normalized query keys, completed
cache insertion, and transaction propagation meet. The authority owns admission
and remote cancellation policy; Datahike owns compute-once correctness.

## Graduation evidence

- 2/8/32 identical cacheable misses execute once and return identical values.
- Different keys and different database values make bounded parallel progress.
- Failure reaches every current waiter, retains nothing, and retries once.
- Cancelling one waiter never affects another; last-waiter policy is observable.
- Same-owner same-key reentrancy cannot deadlock.
- Overflow is bounded and follows distinct local/remote policies.
- Transaction propagation contains completed result maps only.
- Active entry/waiter counts return to zero after success, failure, cancellation,
  overflow, database release, and authority shutdown.
- Existing synchronous CLJ and CLJS query behavior remains unchanged.
