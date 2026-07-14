---
type: research
status: completed
tags: [research, database, agent, flow, architecture]
---

# Eval and query memory-safety audit — 2026-07-14

## Decision

The blocker is real, and clipping the value after `seon.db/query` or
`seon.db/pull` returns cannot close it. The smallest correct owner is a bounded
execution contract in the maintained Datahike query and pull implementations,
enabled with hard defaults by `seon.db`. The existing JVM writer protocol is
not the right first owner: moving an unbounded read into the sole writer would
move the crash from the pod to the database authority without bounding it.

The retained-result problem is separate. `seon.eval` should admit a value to a
`result/<id>` slot only after a bounded, non-serializing structural inspection.
That inspection limits retention; it does not pretend to make an already-run
arbitrary computation safe. Oversized text can be made durable through the
existing blob archive without EDN serialization. An oversized arbitrary
compound value must be produced incrementally into a bounded database/blob
representation; automatically `pr-str`ing it would recreate the failure.

One additional retention surface belongs to the same blocker: maintained
Datahike's query-result cache retains complete query values and weighs them by
top-level tuple count. A one-row result containing a huge string weighs one.
The implementation must either use the same bounded structural weight or skip
caching a result that cannot be certified within the cache budget.

This design closes the concrete database-query/pull blocker. It cannot make
arbitrary synchronous ClojureScript allocation safe inside the same Node
process: `(vec (range ...))` can exhaust the heap before any return-value hook
runs, just as a nonterminating synchronous form can monopolize the event loop.
Hard containment of arbitrary eval requires a disposable process/worker with a
heap limit and an explicit database capability surface. That is a broader
runtime-isolation decision, not something a result serializer can supply.

## Scope and current runtime

This audit read the active roadmap, issue, target architecture, localized
instructions, `seon.db`, `seon.eval`, the result-slot and memory-safety tests,
the replica/protocol/writer boundary, and the maintained Datahike source under
`reference-code/datahike/`. No production source was changed. No unbounded live
pull, OOM-scale fixture, or full ClojureScript suite was run. One live
whole-database query-map probe requested `:limit 2` against the current default
database and returned two rows; the source audit then established that it had
not bounded underlying work. No larger live probe was attempted.

The active runtime is a file-backed local Datahike replica in the Node pod.
Synchronous reads execute in that single process. The JVM process is the sole
writer and selected heavy-work boundary; its typed protocol currently owns
ping, database open, transaction, bounded transaction replay, and bounded KNN
search. It has no general query or pull operation.

At audit time `bin/seon status` reported the default cluster ready with watcher
PID 69409, writer PID 69581, and pod PID 69663. The latest retained focused
ClojureScript report was complete and passing (4 tests, 16 assertions); no
test process was active, so the two small source experiments below did not
overlap a suite.

## Current Seon path

### Query and pull materialize before Seon can intervene

`src/seon/db.cljs` has one public read boundary, but its guards are semantic,
not resource bounds:

- `raw-query` calls `(apply d/q q db inputs)` and returns its complete result.
- `execute-query` records that already-complete result when read capture is
  active.
- `guarded-pull` filters never-installed attributes, then calls `d/pull` and
  returns its complete map.
- `execute-pull` likewise observes the already-complete map.

The bounded index APIs are materially different: `raw-index-datoms` applies
`take` before `mapv`. They are already the right mechanism for database
browsing, but they cannot replace Datalog aggregates, joins, or shaped pulls.

The query documentation says relation results are clipped at roughly 50 rows.
That is presentation behavior in `seon.eval`/`seon.render.value`, not execution
behavior in `seon.db`. The full query value has already existed and can already
have exhausted the heap.

### Result slots retain values verbatim

`seon.eval/bind-result-var!` writes the successful value directly to
`globalThis.result.<munged-id>`, then prunes old keys only when the configured
slot count is exceeded. `replace-live-result!` similarly swaps a settled
Promise's complete value into an existing slot. There is no value-weight
admission check.

The test `one-live-result-slot-retains-the-full-value` deliberately binds and
retrieves a 5 MiB string. It proves persisted EDN clipping is independent from
live addressability, but it now pins the unsafe retention policy and must be
replaced when the blocker is implemented.

Persisted eval output is no longer the immediate problem. `record-eval!`
renders through the bounded value renderer and applies `cap-edn` before the
database write. That closes database amplification through new eval-result
datoms, not transient execution or process retention.

## Maintained Datahike findings

### `:limit` exists but is usually post-materialization

The earlier issue note says Datahike has no built-in query limit. That is stale
for the maintained fork. `normalize-q-input` extracts `:offset`, `:limit`, and
`:order-by` from a query map. However, the major execution paths collect and
deduplicate first:

- the planned relation path calls `collect`, then `into #{}`, then applies
  offset/limit in `post-process-result`;
- the legacy path calls `-q`, `collect`, and `into #{}` before the same
  post-processing;
- the direct path supports a `max-results` parameter in
  `execute-plan-direct`, but `execute-planned-direct` currently passes `nil`;
- order-by necessarily sorts the complete result before taking a limit;
- disconnected components are independently completed and Cartesian-merged
  before the top-level limit; and
- aggregate and find-pull processing happens before the ordinary limit.

A safe isolated JVM experiment made this observable without a large result. A
20-row database ran a predicate that increments an atom, with query-map
`:limit 2`. The returned count was 2 and the predicate count was 20:

```clojure
{:result-count 2, :predicate-calls 20}

```

This proves `:limit` currently bounds the returned collection but not query
work or intermediate realization. It cannot be relabeled as the memory guard.
The preceding live query-map probe also returned two rows, including one large
source string, but it was not used as memory evidence because the current
database's complete work was unknown.

### Cancellation is not a same-thread heap bound

The maintained query context already carries `:cancel`, and the planned
executor checks it in hot loops. In the synchronous ClojureScript pod, an
asynchronous timer cannot flip cancellation while one query monopolizes the
event loop. Cancellation remains useful for JVM/other-thread callers, but the
pod requires a synchronous work/output budget checked by the executor itself.

### Pull has only per-attribute limits

`datahike.pull-api` is an iterative frame machine. `pull-attr-datoms` applies a
per-attribute limit, defaulting to 1,000. The pull grammar permits an explicit
limit and even `nil` for unlimited values. There is no global budget across
frames, component expansion, recursive subpatterns, entities, datoms, or
strings.

Wildcard pull groups every datom on an entity and automatically expands
component refs. Explicit recursive/subpattern pulls can branch through many
entities. Cycle detection prevents one repeated path from looping forever, but
does not bound a large acyclic component graph. The frame loop is the natural
place to decrement a global pull budget before adding datoms, child eids, or
result nodes.

### The query-result cache is another unbounded-value retention surface

`datahike.query/query-result-cache` is bounded by database-snapshot count and a
nominal tuple-weight limit. `result-weight` uses the top-level collection count
or weight one for a scalar. It does not account for tuple width, nested pull
maps, string/byte length, or compound values. Consequently a scalar or one-row
result can retain an arbitrarily heavy value while consuming one cache unit.

This cache is consulted by ordinary `d/q` calls before Seon's result slot is
bound. Fixing only `globalThis.result` would still leave the same query value
reachable from Datahike's process-global cache.

## Required implementation contract

### 1. Add library-owned query budgets

Extend the maintained Datahike query map with explicit, library-general
execution limits. Names are a source-design choice, but the contract needs
three independent facts:

- maximum emitted result tuples/nodes;
- maximum execution work, decremented in every scan/join/function/rule loop;
  and
- maximum shallow result weight, counting tuple slots plus O(1) string and
  byte lengths without serializing values.

The output limit and work limit are not interchangeable. A scalar aggregate
has one output and may scan millions of rows. A join may do substantial work
before producing its first row. Conversely a simple index scan can cheaply
produce too many output tuples.

For unordered, non-aggregate relation queries, thread the requested result
limit into the existing direct executor's `max-results` and equivalent bounded
collectors. Deduplication means the executor may inspect more raw tuples than
the result cap; the separate work counter prevents that from becoming
unbounded. Ordered queries must still complete their sort input, so the work
and weight budgets remain authoritative.

Exhausting a hard budget must throw structured `ex-info` data such as
`:datahike/budget-exceeded` plus the budget name and observed/allowed counts.
It must never silently return a prefix as if the query were complete.
Callers that intentionally request a partial relation use an explicit positive
result limit; the returned shape then has its ordinary documented partial-query
semantics.

### 2. Add a library-owned global pull budget

Thread one immutable/mutable budget handle through Datahike's existing pull
frames. Charge before enqueuing or retaining:

- visited entity/frame nodes;
- datoms consumed across all attributes;
- result map/vector entries; and
- O(1) string/byte lengths.

The global ceiling remains active even when an attribute pull specifies a
higher limit or `nil`. Per-attribute limits remain query semantics; the global
budget is resource safety. Budget exhaustion returns the same structured
library error class as query exhaustion.

### 3. Make `seon.db` set hard defaults

`seon.db/query` and `seon.db/pull` remain the one application boundary. Extend
their namespaced request schemas with bounded query/pull options and translate
them into the maintained Datahike options. Positional calls receive the same
hard defaults. A caller may lower a bound but may not exceed the configured
hard ceiling through the agent surface.

Do not statically ban `[?e ?a ?v]` or wildcard pulls as the primary mechanism.
Those forms can be safe on a small database and dangerous on a large one;
syntax heuristics both reject useful work and miss expensive joins,
aggregates, functions, recursive rules, and branching pulls. The execution
budget is the general invariant. The bounded index/cursor APIs remain the
recommended mechanism when the intent is exploration rather than a finite
relational answer.

Read observation must record the normalized budgets with the request so replay
uses the identical contract. It must not retain an oversized result merely to
compare it later; a budget error is itself the observed result.

### 4. Repair Datahike query-cache admission

The query cache must reuse the same bounded shallow weight accounting. Cache a
result only when it can be certified within the remaining cache budget by a
bounded inspection. If certification crosses the inspection limit, skip the
cache entry; do not finish walking the result to compute a weight. A one-row
huge string or nested pull must no longer weigh one.

Disabling the cache only around agent eval would hide the duplicate retention
surface while leaving it active for the same public query elsewhere. Fix the
maintained cache owner.

### 5. Admit retained eval values with a bounded structural inspection

Before `bind-result-var!` or `replace-live-result!` stores a value, run a
non-serializing inspector with fixed limits. It uses an iterative worklist and
stops after `N + 1` visited nodes. It charges string length, typed-array/
buffer byte length, and collection entries; handles cycles by object identity;
never calls `pr-str`; never fully counts a lazy sequence; and rejects opaque
database/entity/compiler handles conservatively.

This is safe even for an already-large compound value because the inspector's
own traversal is bounded. It cannot undo the transient allocation that
produced the value, but it prevents the bounded slot count from pinning that
allocation indefinitely. A fixed per-slot admission ceiling plus the existing
slot-count ceiling provides a simple total upper bound without a second mutable
weight registry.

For an admitted value, `result/<id>` keeps returning the identical value. For a
rejected value, bind a small namespaced descriptor instead of the raw value.
The descriptor states that the value was not retained and tells the agent to
rerun the operation through bounded query/pull/index functions or to write
large text incrementally to `my.blob`. Automatically serializing an arbitrary
compound value is forbidden. An oversized plain string may be streamed to the
existing blob archive if profiling proves that hashing/writing does not make a
second whole-string copy; the current `my.blob/put!` can be reused, not
duplicated.

The current 5 MiB full-retention test should become two tests: a small value
round-trips identically, and an overweight value leaves only the bounded
descriptor/blob handle while the raw slot is absent.

## Why the JVM writer is not the guard

Adding general query/pull messages to `seon.db.protocol` would require shipping
query data, inputs, temporal coordinates, and results across Transit. More
importantly, an unbounded operation would execute in the sole writer process
that owns commits, receipts, feed, and replay. A heap failure there is at least
as damaging as a pod failure.

The JVM boundary remains appropriate for work whose implementation is already
bounded and genuinely JVM-owned, as KNN is. If later requirements demand hard
isolation for arbitrary user computations, the safe topology is a disposable
worker process with an explicit heap limit reading an immutable database
coordinate. It is not the writer and not a substitute for Datahike budgets.

## Exact falsification matrix

| Layer | Probe | Required observation |
|---|---|---|
| Datahike query output | 20 unique rows, result limit 2 | Two rows returned; executor emits no more than the documented bounded allowance. |
| Datahike query work | Same predicate-counter experiment | Predicate/work counter stops at the work/result allowance, not 20 as today. |
| Aggregate | Scalar count over more rows than a tiny work budget | Structured budget error before completing; one scalar cannot bypass work accounting. |
| Join | Selective result after a broad intermediate join | Structured work error at the configured ceiling; RSS remains in the test band. |
| Ordering | Ordered query with small output limit and tiny work budget | Structured work error; limit is not misrepresented as an early-sort guard. |
| Cartesian components | Disconnected query whose product exceeds work budget | Structured work error before product materialization. |
| Find-pull | Query containing pull over branching refs | Query and pull budgets both apply; no nested map bypass. |
| Pull wildcard | Component root with more children than global node budget | Structured pull-budget error; no complete component tree is built. |
| Pull recursion | Wide acyclic graph and a cyclic graph | Wide graph stops at budget; cycle handling remains correct within budget. |
| Pull override | Attribute selector with `:limit nil` | Global pull budget still stops it. |
| Query cache | One-row result containing a string over cache weight | Entry is skipped/evicted; cached weight is not one. |
| Cache hit | Safe bounded result queried twice | Equal result; second call may hit cache without rewalking the database value. |
| Seon query | Agent whole-database relation under tiny defaults | Error-as-value at eval boundary; pod remains ready and later `(+ 1 1)` succeeds. |
| Seon pull | Agent wildcard/recursive pull over budget | Error-as-value; pod remains ready and later bounded pull succeeds. |
| Read replay | Captured bounded query replayed on unchanged/changed db | Same normalized budgets and same result/error; no uncapped replay path. |
| Result admission | Small nested value | Identical `result/<id>` value is live. |
| Result rejection | 5 MiB string and over-node nested value | Raw value is not live; bounded descriptor/blob handle is; slot count remains bounded. |
| Pending Promise | Promise settles after its slot is evicted or overweight | It neither resurrects an evicted slot nor installs an overweight raw value. |
| Opaque handles | Datahike db/entity/compiler state returned from eval | Conservative bounded descriptor; no old snapshot/cache is pinned by a result slot. |
| Persistence | Overweight result records an eval row | Stored result/error stays under `database-edn-cap`; no raw content datom appears. |
| Process proof | Repeated over-budget query/pull attempts | Writer and pod stay ready; heap returns to a stable band; a normal eval/query succeeds afterward. |

The final process proof should use a generated, bounded fixture sized to cross
the configured limits by a small factor. It must not recreate the historical
OOM payload. Capture before/after pod heap/RSS, structured errors, readiness,
and a subsequent successful eval.

## Implementation order

1. Add Datahike query work/output accounting and repair direct/legacy/planned
   coverage with CLJ and CLJS library tests.
2. Add the global pull-frame budget with wildcard, recursion, component, and
   unlimited-attribute tests.
3. Repair query-cache admission with shallow bounded weight tests.
4. Add `seon.db` hard defaults, request schemas, read-observer replay data, and
   error normalization.
5. Add `seon.eval` retained-value admission and replace the unsafe 5 MiB
   round-trip assertion.
6. Run focused Seon tests, then the bounded live process matrix. Only after
   this blocker passes should broad autonomous agent/browser drives resume.

## Source map

- `src/seon/db.cljs:775-903` — unbounded query call and public dispatch.
- `src/seon/db.cljs:1215-1313` — guarded but globally unbounded pull.
- `src/seon/eval.cljs:1390-1573` — verbatim result slots and count-only
  eviction.
- `src/seon/eval.cljs:4200-4237` — normal and pending result binding.
- `test/seon/eval/memory_safety_test.cljs` — persisted cap plus unsafe full
  5 MiB retention assertion.
- `reference-code/datahike/src/datahike/query.cljc:92-114` — query-map options.
- `reference-code/datahike/src/datahike/query.cljc:2348-2445` — result cache and
  tuple-count weight.
- `reference-code/datahike/src/datahike/query.cljc:3625-3750` — collection,
  deduplication, and late limit.
- `reference-code/datahike/src/datahike/query.cljc:3767-3964` — dispatch,
  Cartesian merge, and execution paths.
- `reference-code/datahike/src/datahike/query/execute.cljc:2396-2405` — existing
  direct-executor `max-results` seam.
- `reference-code/datahike/src/datahike/pull_api.cljc:15-314` — per-attribute
  limit and iterative pull frames.
- `src/seon/db/protocol.cljc:19-157` — current writer operations; no general
  query/pull request.
- `src/seon/db/replica.cljs:1-233` — local read topology and bounded JVM KNN
  exception.
