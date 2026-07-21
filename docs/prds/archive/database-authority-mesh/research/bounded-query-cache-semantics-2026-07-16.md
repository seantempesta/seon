---
type: research
status: complete
tags: [database, capability, research]
---

# Bounded query cache and single-flight semantics

## Decision

Resource-bounded queries should share Datahike's existing completed-result
cache with unbounded queries, because a completed immutable result is the same
semantic value. Each bounded caller must still certify that result against its
own `max-results` and `max-result-weight`; a completed-cache hit performs no
query work, so its observed work is zero and `max-work` is satisfied.

Cold bounded computations should use the existing single-flight coordinator,
but only callers with the same three resource limits may join one computation.
The completed cache key remains the semantic query key. The in-flight key adds
`max-work`, `max-results`, and `max-result-weight`. A successful bounded owner
stores only the ordinary result under the semantic completed-cache key. Failed,
canceled, or uncertifiable work is not cached, exactly as today.

This is the smallest safe seam. It changes only `datahike.query/raw-q`'s cache
policy and reuses `datahike.resource` plus the existing coordinator. It does
not create a Seon cache, a bounded-query cache, another worker pool, or another
request identity.

## Dependency ledger

- Datahike `092f5b0580c892c32b1dc65bf9acdbe37db90c4f`:
  - `reference-code/datahike/src/datahike/query.cljc`
  - `reference-code/datahike/src/datahike/query/single_flight.cljc`
  - `reference-code/datahike/src/datahike/resource.cljc`
  - `reference-code/datahike/src/datahike/api/specification.cljc`
  - `reference-code/datahike/src/datahike/api/types.cljc`
- Existing retained tests:
  - `reference-code/datahike/test/datahike/test/query_cache_test.cljc`
  - `reference-code/datahike/test/datahike/test/query_cancel_test.clj`
- First-party caller:
  - `src/seon/db/writer.clj`
- Governing contracts:
  - [[roadmap]]
  - [[read-materialization-contract-2026-07-16]]

The audited query, resource, and single-flight files were clean at that exact
SHA. Concurrent shared-tree edits existed in other Datahike files and were not
read as evidence or modified by this audit.

## Current exact behavior

`normalize-q-input` admits the three resource options and request ID as query
options (`query.cljc:95-118`). `q-with-evidence` binds one caller-local evidence
sink and delegates to `raw-q` (`query.cljc:123-144`).

`raw-q` currently puts any query carrying `max-work`, `max-results`, or
`max-result-weight` on the uncacheable branch before it constructs a database
cache identity or semantic cache key (`query.cljc:4179-4205`). Consequently a
bounded query:

- never reads an already completed identical result;
- never becomes or joins an identical in-flight computation;
- never stores a successful certified result; and
- always reports `:datahike.cache.outcome/uncacheable`.

For an eligible unbounded query, the completed key is the exact committed
database identity plus the scale-sensitive semantic key
`[query non-db-args offset limit order-by planner-mode]`
(`query.cljc:4206-4224`). A completed hit returns directly. A miss enters the
single-flight coordinator with `[db-key cache-key]`, rechecks after admission,
computes once, and stores only a successful weight-certified result
(`query.cljc:4225-4246`). This is already the right mechanism.

The public capability catalog says `q-with-evidence` is cacheable and lists all
three resource options (`api/specification.cljc:380-394`). The unconditional
bounded-query bypass therefore contradicts the advertised composition even
though each individual bounded result remains correct.

## Shortest falsifier

One attached in-memory database contained `{:probe/id "one"}`. The query used
an input predicate that increments an atom, with all three generous limits:

```clojure
{:query '[:find ?v
          :in $ ?pred
          :where [_ :probe/id ?v]
                 [(?pred ?v)]]
 :args [database pred]
 :max-work 1000
 :max-results 10
 :max-result-weight 1000}

```

Two sequential `q-with-evidence` calls returned the same `#{["one"]}` result,
but the predicate ran twice. Both outcomes were `uncacheable`, both reported
six units of work, the completed snapshot count remained zero, and
single-flight owners remained zero:

```clojure
{:calls 2
 :outcomes [:datahike.cache.outcome/uncacheable
            :datahike.cache.outcome/uncacheable]
 :work [6 6]
 :snapshot-count 0
 :single-flight-owners 0}

```

The same source already retains the positive control: sixteen concurrent
unbounded identical queries invoke a sleeping predicate once
(`query_cache_test.cljc:210-235`), and evidence calls report owner then hit with
positive work then zero work (`query_cache_test.cljc:237-263`). The defect is
therefore isolated to resource-option admission, not cache identity or
single-flight coordination.

## Why limits have two identities

The completed result depends on the database value, query, arguments, semantic
pagination/order options, and planner mode. Resource limits do not change a
successful result; they decide whether computation may reach it. Putting
limits in the completed-cache key would retain duplicate equal values and make
a result computed with one generous limit unavailable to every other caller.

In-flight work is different. Two cold callers with different limits cannot
safely share one outcome:

- if the stricter caller owns, its budget failure must not fail a more generous
  caller that could have completed;
- if the generous caller owns, its success must not bypass the stricter
  caller's work limit; and
- cancellation remains per request ID, while the final joined caller alone
  sets the cooperative computation signal.

Therefore the existing semantic cache key stays unchanged, while the existing
flight key becomes:

```clojure
[db-key cache-key max-work max-results max-result-weight]

```

Nil values naturally identify the existing unbounded case. No new public name
or protocol field is required.

## Per-caller certification and evidence

Datahike already has the necessary checks. `certify-result!` checks top-level
cardinality in O(1) when counted and performs a bounded, non-serializing shallow
weight walk (`resource.cljc:120-168`). A cache hit should create the caller's
ordinary budget evidence, certify the cached result against that caller's
result limits, publish the evidence, and return the identical persistent root.
It must not copy or traverse the result twice.

`max-work` is a computation limit. A completed-cache hit observes zero query
work. Reporting zero is both true and consistent with the existing unbounded
hit proof (`query_cache_test.cljc:256-259`). The returned evidence must still
include the caller's configured limits; the current initial nil-budget evidence
would otherwise incorrectly return an empty limits map
(`resource.cljc:22-54`).

For an exact-limit joined cold computation, the owner currently publishes work
only into its thread-bound evidence sink. A waiter reports shared scope but has
no owner counters. The implementation should deliver the owner's bounded
resource evidence beside the internal single-flight completion, publish that
same immutable evidence into each exact-limit waiter sink, and still expose
only the ordinary query result from `q`. This is internal completion data, not
a new public result wrapper. Joined callers then truthfully report the shared
computation they were bounded by.

## Rejected alternatives

- Keep bounded queries uncacheable: simplest code, but defeats compute-once for
  the safest remote queries and contradicts the capability catalog.
- Put limits in both cache and flight keys: safe but duplicates equal completed
  values and reduces propagation reuse.
- Omit limits from both keys: completed hits are safe after per-caller
  certification, but cold callers can incorrectly share a stricter failure or
  a more generous success.
- Cache only unbounded results: bounded work that completes first still cannot
  help later callers, and identical bounded misses still duplicate work.
- Add a Seon cache or executor coalescing: duplicates immutable-value identity,
  cancellation, cache propagation, and release fencing already owned by
  Datahike.

## Acceptance tests

Add focused Datahike tests beside the existing query-cache and resource tests:

1. An unbounded successful owner followed by bounded calls returns a completed
   hit, identical result root, zero work, requested limits, and no predicate
   re-execution.
2. A bounded successful owner is stored once and a later unbounded call hits
   the same completed entry.
3. Eight or thirty-two concurrent identical queries with identical limits
   compute once, retain one completed entry, and report one owner plus joined
   callers with the same shared resource counters.
4. Concurrent identical queries with different `max-work` do not join. A strict
   caller fails without failing a generous caller; the generous success is then
   available as a certified completed hit.
5. A completed result over `max-results` or `max-result-weight` throws the same
   structured budget error for the strict caller, remains cached, and succeeds
   for a generous caller.
6. A bounded owner failure, cancellation, final-caller cancellation, cache
   clear, generation release, and retry retain no flight/request identity and
   never cache a partial or failed result.
7. Cache propagation across an unrelated transaction preserves the bounded
   result exactly when its attribute dependencies remain valid.
8. Existing BigDecimal scale separation, reentrancy, overflow, weighted cache,
   CLJS synchronous semantics, and normal `q` return shapes remain green.

Measure predicate executions, cache outcomes, owner/join counts, work counters,
completed weight, retained flights/waiters, allocations, and warm-hit latency.
The performance exit is one computation for identical exact-limit cold calls,
zero computation for any certifiable completed hit, and no additional retained
result copy.

## Implementation boundary

Change Datahike first, in place. The shortest implementation order is:

1. separate semantic cache eligibility from cold-computation resource limits
   in `raw-q`;
2. certify completed hits per caller while publishing zero-work evidence with
   the requested limits;
3. add the three existing limit values only to the single-flight key;
4. carry owner resource evidence in the existing internal completion so joined
   exact-limit callers report it; and
5. retain current success-only cache admission and generation/release fences.

Seon's authority then needs no compatibility path. It continues passing the
existing request ID and optional Datahike resource limits through
`q-with-evidence`; improved reuse is automatic.
