---
type: research
status: complete
tags: [research, prd, database, web, flow]
---

# Datahike reactive-read and page protocol — 2026-07-19

## Decision

Datahike is Seon's reactive engine. Every eager immutable database read returns
its ordinary value plus one Datahike-owned, execution-aware dependency plan.
The plan is scoped by parsed database source when a query has several sources.
A registered reactive read sends the plan back as its committed-report
interest. Matching transactions make the registration dirty and advance its
one pending target; they never update cached rows. Evaluation happens lazily
at settle time or configured maximum latency against the newest database value.
The completed Clojure value is compared with the last delivered value using
`=`; equality suppresses notification to an established consumer, while a new
consumer always receives its first current value.

A page render is one consumer of this general mechanism. It unions the plans
returned by the reads that actually ran, then Datastar suppresses equal
serialized events and sends a complete outer morph only when needed.

Seon does not parse Datalog, pull selectors, entity refs, index ordering, or
transaction datoms. It composes closed dependency data with one operation:
union, with `:all` absorbing. Declared `:seon.fn/read-attrs` remains useful as a
cold-start or focus hint but never excludes an actual Datahike dependency.

The first page implementation stays at whole-page granularity. It does not restore
the removed local database-read replay engine or the per-surface unit cache.
Those mechanisms are reconsidered only if profiling after correctness shows
that a complete page rerender is the dominant remaining cost.

## Dependency ledger

| Owner | Selected source | Contract used |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` based on `4c55791be1fb8bb8d9332f21c576f5c20b85b760`; first dependency fix `b9a487f6` | parsed query and pull forms, exact immutable database identities, result cache, single-flight, committed reports |
| Datalog parser | `reference-code/datalog-parser` at `08a32d8f2facde9986e257e3df2807104402bf59` (release `0.2.37`) | typed query clauses, database source bindings, and recursive canonical PullSpec data |
| Datastar | `reference-code/datastar` at `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | stable-id outer morph, streaming fetch, request cancellation and retry |
| Datastar Clojure | `reference-code/datastar-clojure` at `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | canonical `datastar-patch-elements` SSE framing |
| Database protocol | `src/seon/db/protocol.cljc`, `src/seon/db.cljs` | coordinate-pinned query, pull, pull-many, schema, index-page, execute-many, listen, and unlisten values |
| JVM authority | `src/seon/db/writer.clj`, `src/seon/db/executor.clj` | bounded independent reads, attribute-indexed interests, ordered committed delivery |
| Page acquisition | `src/seon/execution/runtime.cljs`, `src/seon/execution.cljs` | grouped base reads and independently awaited selected render functions |
| Web subscription | `src/seon/web/datastar.cljs` | equivalent-view normalization, one active plus newest pending render, last complete event, coalescing, fanout, and backpressure |

The selected Datahike source differs from several earlier reactive reports. The
source and tests named here are current checkout truth.

## Shortest falsifiers

The current design fails before any performance question matters:

1. A query containing `(missing? $ ?e :person/deleted?)` parses successfully,
   but `query-attribute-dependencies` returns an empty set. A query interest is
   then rejected as dependency-free, and a cached result can be propagated
   across a transaction of `:person/deleted?`.
2. A find pull containing `{:person/friend [:person/email]}` records the root
   relationship but omits the nested `:person/email` dependency.
3. A reverse pull `:person/_friend` records the printed reverse key rather than
   the canonical stored datom attribute `:person/friend`.
4. A failed page render produces an error event without dependency evidence.
   The subscription retains its prior narrow set, so a transaction that repairs
   a newly relevant input can be filtered indefinitely.

These are false negatives, not conservative over-selection. Each is a blocker
for selective reactivity and cache inheritance.

## Existing test fidelity and the missing contract

The maintained fork has substantial faithful coverage, but the pieces are not
joined into a reactive-read law:

- `reference-code/datahike/test/datahike/test.cljc` aggregates the full JVM
  entity, listen, pull, query, and cache suites. The CLJS Node runner is an
  intentional subset and omits the full entity, listen, query-cache,
  query-pull, and query suites.
- `query_cache_test.cljc` strongly covers database source identity, temporal
  isolation, bounded lifetime, release/reconnect fencing, late publication,
  multi-source identity, single-flight ownership/waiters, and cancellation.
- `pull_api_test.cljc` and `entity_test.cljc` strongly cover returned pull and
  entity values, including nested refs, reverse refs, wildcard, components,
  recursion, and touch. They do not assert dependency evidence or result-cache
  behavior for those reads.
- Seon's writer-interest tests cover replacement, cleanup, report-gap
  resynchronization, query versus pattern delivery, and reverse-index selection
  of one candidate among 1,000 interests.
- Datastar tests cover attribute intersection, fail-open selection, reconnect
  bytes, and per-socket latest-only backpressure. They do not cover an active
  slow evaluation under a transaction burst, configured maximum latency, or
  Clojure-value equality suppression.

The narrow dependency tests prove simple data-pattern attributes, flat
find-pull attributes, cache inheritance, wildcard widening, and variable
attribute widening. No test states the universal contract that each supported
eager read returns its value and a sound dependency plan. The suites can
therefore remain green while `missing?`, nested and reverse pulls, direct pull,
pull-many, dynamic entity access, and schema-changing transactions are unsound.

The Datahike fork suite remains its own dependency gate; Seon's writer runner
does not silently substitute for it. Implementation begins by adding red cases
to the owning Datahike suites, followed by Seon protocol and scheduler tests.

## What Datahike already does well

### Exact query cache and single-flight

The query result cache keys work by exact committed database source identity,
query, non-database arguments, pagination/order options, and planner mode.
Multi-source queries retain the identity and argument position of each source.
A completed hit avoids flight allocation. On a miss, one caller computes and
identical waiters join; canceling a waiter does not cancel the owner until the
last caller leaves.

After durable commit, Datahike derives modified attributes, propagates safe
entries from the parent immutable value to the child value, resets the
connection, and only then publishes committed reports. Old database values and
their cache entries remain immutable and queryable.

This machinery is the correct owner for reusable database reads. Seon must not
add a query cache, result-flight registry, or database-value-keyed memoization.

### Attribute-indexed transaction interests

The JVM writer already indexes interests by database scope and attribute. A
transaction unions candidates from only the changed datom attributes plus the
`:all` bucket, applies an exact second filter, and sends matching reports. Bun
does not need to inspect every database transaction.

The existing listener also accepts explicit datom patterns. The Datastar path's
synthetic conjunction query exists only to make Datahike rediscover an already
known attribute set. A normalized dependency plan should become the direct
listener input instead.

### Bounded independent execution

`execute-many` reserves and submits independent members through the database-
fair read executor. Capacity is bounded near `processors - 1`; independent
databases and read keys can progress concurrently, while mutation and delivery
retain their required per-database serialization. Identical cacheable queries
still collapse through Datahike single-flight.

This is the correct meaning of parallelism: execute every dependency-ready
read independently, preserve order only where one result chooses the next read,
and never split one Datalog query or pull traversal into application-managed
fragments.

## Where the current dependency projection is unsound

`datahike.query/query-attribute-dependencies` calls Datahike query
normalization, but it hand-walks the raw normalized `:where` forms. Only the
`:find` side consumes the parsed query value. Consequently:

- typed database predicates such as `missing?` are not handled semantically;
- rule calls and variable attributes widen, which is safe but imprecise;
- new parser-supported clause forms require a second hand-maintained grammar;
- find-pull extraction traverses only the root PullSpec keys;
- nested subpatterns are omitted;
- reverse pulls use the display key instead of each parsed option's canonical
  `:attr`; and
- nested wildcards are not discovered recursively.

Datahike's pull parser already produces the necessary structure. Each PullSpec
has `:wildcard?`, an `:attrs` map, canonical `:attr` values, and nested
`:subpattern` values. Dependency extraction should recursively fold that parsed
value. A wildcard at any level returns `:all`.

The query side should fold the typed `:qwhere` representation. Parsed shape
alone is insufficient for database functions whose attribute is an argument,
so Datahike also owns a small explicit semantic table for its database-reading
functions, including `missing?`, `get-else`, and `get-some`. Unknown functions,
rules that cannot be closed over their supplied rule data, and variable
attribute positions widen to `:all`.

## One dependency-plan value

The initial public shape should stay deliberately small:

```clojure
;; every change may affect the read
:all

;; only datoms carrying one of these canonical stored attributes may affect it
#{:seon.agent/id :seon.agent/purpose}

```

This is already enough to remove all Seon-side query construction and parsing.
If profiling later proves attribute over-selection material, Datahike may extend
the closed plan with exact datom patterns. The extension belongs in Datahike
and must preserve one `dependency-plan-affected?` interpretation used by cache
inheritance and interests.

Datahike exposes one operation-semantic family:

- `query-with-evidence` returns the plan derived from the typed query and its
  supplied inputs;
- `pull-with-evidence` and `pull-many-with-evidence` recursively fold the
  parsed PullSpec;
- wildcard entity reads return `:all`;
- exact AEVT/AVET index prefixes may return their canonical attribute;
- schema reads and unknown/temporal/foreign operations return `:all`; and
- malformed or incomplete evidence always returns `:all`, never an empty plan.

The result and plan are referentially transparent ordinary data. Datahike does
not retain a page-render capture session: a render spans conditional application
work and several RPCs, so server-side begin/end state would introduce cleanup,
cancellation, and web-lifecycle coupling. The caller merely unions returned
plans.

## Generalized immutable-read reuse

Today only Datalog `q` has result caching and single-flight. Direct `pull`,
`pull-many`, schema, and index-page reads parse and execute on every call.
Therefore “rerunning a page is cheap” is true only to the degree that its work
is expressed as cacheable queries.

The preferred trajectory is one bounded immutable-read cache behind Datahike's
public read operations:

```text
[operation, exact source identity, normalized arguments, resource options]
  -> {value, dependency plan, certified weight}

```

It reuses the existing query cache's generation admission, weighted LRU,
single-flight, cancellation, source inheritance, and release fence. It is not a
second pull cache beside the query cache. Generalization follows measurement:
first return correct evidence for every operation and profile direct pull cost;
then move pull/pull-many into the same owner if the repeated work is material.

Schema-changing transactions must conservatively prevent inheritance. Their
transaction datoms name schema-definition attributes rather than every domain
attribute whose interpretation changed. Treating them as ordinary selective
changes can propagate results across changed cardinality, uniqueness, ref, or
component semantics.

The present cache-propagation implementation also scans candidate source
buckets and rebuilds survivor maps, despite comments claiming work
proportional only to invalidated entries and structural sharing. Correct the
comments and use `reduce-kv` plus `dissoc` first. Add a reverse cache dependency
index only if commit profiling demonstrates a bottleneck, especially because
multi-source keys require additional indexing.

## Page render protocol

### First paint

1. Normalize the route and browser state into one semantic subscription key.
2. Acquire the current immutable database value once.
3. Establish one async-local dependency accumulator in the execution child.
4. Issue all dependency-ready core reads through `execute-many`. Every response
   includes Datahike's plan; the accumulator unions it.
5. Invoke independent selected surface functions through the existing
   `Promise.all` boundary. Reads made by the branch that actually runs add their
   returned plans to the same accumulator.
6. Deep-force and validate the completed page projection.
7. Serialize the complete stable-ID `#app-view` once.
8. Atomically install the serialized event and dependency union only if the
   same subscription still owns that render.
9. Reconcile the database interest from the union of all live subscription
   plans.

Any failed, canceled, incomplete, or unrecognized capture installs `:all`.
Repairability is more important than selective performance.

### Committed change

1. Datahike commits and derives the canonical changed datoms.
2. The dependency plan selects candidate interests in the JVM.
3. Bun coalesces the delivered report for 16 ms normally, 300 ms for structural
   changes, with a hard 500 ms deadline.
4. Every affected normalized subscription is enqueued in the same event-loop
   pass. Different subscriptions proceed independently.
5. Each subscription retains at most one active render and the newest pending
   database value. Work inside one subscription remains serialized so an older
   completion cannot replace newer state.
6. The page reruns at exact `db-after`. Datahike cache hits and single-flight
   suppress reusable database computation.
7. Equal serialized event bytes suppress transport. Unequal bytes fan to every
   equivalent socket.
8. Each socket independently applies latest-wins backpressure.

The scheduler drops obsolete projection requests, never committed database
truth. During a burst the coalescer unions changed attributes while advancing
to the newest `db-after`. If reports continue past the hard coalescing deadline,
the system may render periodic snapshots, but it does not build an unbounded
transaction-sized render queue. While a render is active, newer relevant
commits replace its single pending target.

Dependency plans are conditional and can change when the page reruns. Plan
replacement therefore needs a race-closing handshake: register the completed
render's new plan and replay or test committed reports from that render's basis
transaction through the current branch head. If one matches, retain the newest
database value as pending and rerun. Without this step, a transaction for a
newly discovered dependency could commit during the render, be rejected by the
old plan, and leave the completed page stale.

### Reconnect

Datastar supports event IDs and retry replay, but Seon should continue to send
a fresh full projection. Complete-snapshot semantics make latest-wins safe and
avoid a transaction-replay protocol in the browser.

The current HTML event is retained only while a normalized subscription has a
consumer. A sole socket disconnect normally releases it, so reconnect is not
generally an HTML-cache hit. This is a useful freshness and memory property.
Add bounded zero-consumer retention only after measuring a meaningful reopen
hit rate; correctness must never depend on it.

## Datastar source conclusions

The existing wire format is canonical. `datastar-patch-elements` defaults to
`outer`; with no selector, the client resolves every top-level child by stable
ID. It parses the fragment once and morphs only unequal attributes, children,
and text while preserving persistent IDs. No server-side tree diff is needed.

One event may contain several top-level stable-ID elements, so smaller units
remain available as a measured later optimization. They would require their
own complete dependency/output ownership, however. Full-page snapshots retain
the strongest simple laws:

- conditional elements disappear naturally;
- reconnect is one repaint;
- latest-wins backpressure cannot lose required deltas; and
- dependency-plan replacement closes the render-basis-to-branch-head race; and
- server and browser converge from any prior DOM state.

Keep the full-page protocol until client morph cost, event bytes, or page
serialization—not query work—proves to be the bottleneck.

## Concurrency audit

| Boundary | Current behavior | Decision |
|---|---|---|
| Independent `execute-many` members | bounded JVM read workers, database-fair | retain |
| Identical cold queries | one Datahike single-flight owner, joined waiters | retain and generalize only inside Datahike |
| Selected render functions in one execution child | `Promise.all`; remote reads can overlap | retain |
| Data-dependent page reads | ordered by the branch that chooses the next request | retain; do not manufacture concurrency |
| Different normalized subscriptions | enqueued without awaiting one another | retain |
| One normalized subscription | one active plus newest pending | retain coherence boundary |
| Different agent children | separate processes | retain |
| Same-agent child invocations | serialized | measure UI delay behind long turns before changing process ownership |
| Equivalent browser sockets | one render and event, independent writes/backpressure | retain |
| Hiccup serialization and registry transitions | synchronous Bun event loop | profile; do not add workers speculatively |

The main open concurrency risk is same-agent page rendering waiting behind a
long turn in that agent's retained execution child. A separate pure projection
lane is considered only after live latency evidence; concurrently mutating one
retained compiler/runtime is not acceptable.

## Rejected alternatives

- A Seon Datalog or pull parser: duplicates the database's language.
- Analyzer-declared attributes as correctness authority: non-transitive.
- Synthetic Datalog queries that encode a known attribute union.
- Replaying every captured read in Bun before deciding to render.
- Restoring per-surface units before whole-page profiling.
- A JVM page capture session spanning several RPCs.
- Database values, entities, functions, or Promises in retained cache state.
- Per-browser listeners or a second event bus.
- Transaction replay to the browser.
- Delta/append patches with latest-wins backpressure.
- Parallelizing reads whose inputs depend on prior results.
- A standalone pull cache beside the query cache.

## Proof matrix

### Datahike dependency soundness

- Every query accepted by `q` returns a sound concrete plan or `:all` for
  vector, list, map, string, and quoted forms.
- Typed patterns, boolean clauses, joins, predicates, database functions,
  rules, variable attributes, and multiple sources cannot produce a false
  negative.
- Literal, nested, reverse, alias, default, limit, recursion, wildcard,
  dynamic, and malformed pull selectors use canonical stored attributes or
  widen to `:all`.
- Query `:find` pulls and direct pull/pull-many use the same recursive PullSpec
  fold.
- Schema-changing transactions propagate no potentially stale read result.
- Generated query/pull plus transaction cases compare selected plans with
  actual result change; over-selection is allowed, a false negative fails.

### Page correctness

- A helper-indirected read updates an already-open page.
- Conditional branch dependencies are replaced after a rerender.
- Renderer source, configuration, and selected canvas changes are observed
  even when declared read attributes remain unchanged.
- Missing or failed dependency capture widens to `:all` and a repair
  transaction rerenders the error page.
- Interest replacement has no commit gap: acknowledgement database value plus
  later reports cover every commit.

### Reuse and scheduling

- An unrelated commit produces no page acquisition, renderer, serialization,
  or SSE work.
- An affected rerender reports Datahike cache hits for unaffected queries and
  recomputation for affected reads.
- Independent read members overlap within bounded capacity; dependent reads
  remain ordered.
- One slow subscription does not prevent another from completing.
- One subscription never exceeds one active render and retains only the newest
  pending value.
- Equivalent sockets receive byte-identical output from one render.
- Equal output emits no morph; a slow socket retains only the newest complete
  event.

### Live profile

Capture writer candidate count, read operation and cache outcomes, pull/query
time, execution-child wait/body time, page serialization, event bytes, drain
time, and browser morph time. Only those measurements may graduate fragment
units, zero-consumer output retention, cancellation, listener partitioning, or
generic pull-result caching.
