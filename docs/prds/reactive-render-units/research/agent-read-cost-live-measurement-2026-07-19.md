---
type: research
status: active
tags: [database, agent, research]
---

# Agent read-cost live measurement

## Question

Measure one representative database-heavy agent run on the current default
artifact, then identify the existing source boundary that can attribute
database, context, model, evaluation, and reactive-render cost to an agent
without adding an always-on trace or a second observability system.

## Artifact and method

The default cluster was ready at Seon `15acdaf9` with maintained Datahike
`6611de27`. No source, configuration, or lifecycle mutation occurred during the
run. Another admitted Inspect task used an isolated retained branch, so shared
provider or writer contention cannot be excluded from wall-clock observations.

`POST /agents/run` admitted `flat-kiwis-start` with a bounded request to inspect
the root agent, active agents, reactive timing configuration, and `my.ns`, then
complete. Before and after the run, the existing `seon.reactive/measurements`
and `seon.web.datastar/performance-snapshot` projections were captured. The
door's ordinary turn, eval, and model-transport evidence was retained in
`tmp/reactive-real-turn.json` for the local investigation only.

## Result

The run timed out after 211,126 ms with 37 turns and 36 successful evals. Each
turn rendered about 27,984 context tokens plus 609 system tokens. Twenty-seven
of the 36 evals repeated the same `my.plan/active!` call. Only three eval source
forms were explicit database reads; that count is not the actual read count
because context construction, plan functions, and selected calls perform reads
inside the execution child.

The result therefore falsifies two tempting claims:

- 211 seconds is not database latency. The model completed approximately one
  turn every four to seven seconds while the agent repeatedly selected the same
  plan action and never completed the task.
- Counting eval forms is not database instrumentation. The current persisted
  turn/eval graph cannot attribute child-internal query, pull, entity, schema,
  index, or `execute-many` work.

Reactive and Datastar counters did not move during the run because no feed was
attached. That is correct and prevents unrelated process counters from being
misattributed to the agent.

## Existing source seam

Datahike already returns bounded evidence with eager query execution:

- `:datahike.query/cache-evidence` reports cache outcome and whether
  computation was saved;
- `:datahike.query/resource-evidence` reports charged work, result count, and
  result weight; and
- `:datahike.read/dependency-plan` reports the source-scoped invalidation plan.

The maintained implementation is in
`reference-code/datahike/src/datahike/query.cljc` and
`reference-code/datahike/src/datahike/resource.cljc`. Seon's typed protocol
already transports all three query fields. `seon.db/query-with-evidence`
exposes them explicitly, while ordinary `query` records only the dependency
plan in the fiber-local scope. Pull, pull-many, schema, index, entity, and
`execute-many` already cross the same typed request boundary and return their
dependency plans.

`seon.db.internal/run-with-read-evidence` is the one execution-child scope.
Every compiled invocation already runs inside it, and its ordinary result
message already returns the collected read evidence to the parent. This is the
correct place to add an optional bounded aggregate alongside—not inside—the
dependency plan. Instrumenting individual public functions or parsing eval
source would miss composed and selected calls.

The durable consumer is the existing turn record and
`seon.agent.debug/turn`; `/agent/{id}/debug` is already a reactive derived view
of that evidence. No profiler registry, log scraper, or debug-only database is
needed.

## Progress-accounting prerequisite

Commit `0879d756` removed the unbounded reset but its first live rule was too
coarse. A second rebuilt request closed after three different read-only plan
queries, before acting. The replacement uses the stable ordered source/result/
error/namespace fields already persisted on eval rows: a different observation
is newly acquired knowledge, while an identical observation repeated across
turns advances the existing bound. Attribution work remains paused until that
replacement passes current-artifact live proof.

## Required contract

The selected configuration owns one mode with a launch-time environment
override:

- `:off` performs no timer, counter, retained-data, or persistence work;
- `:aggregate` retains one bounded map per completed turn with operation counts,
  cumulative request duration, cache outcomes, Datahike work/result counts and
  weights, context render duration/tokens, model duration, eval duration, and
  reactive render/notification deltas attributable to that turn; and
- `:trace` may retain a separately bounded diagnostic blob of individual spans,
  but is not required for the aggregate graduation unit.

The aggregate uses Datahike's producer vocabulary. It does not call resource
evidence a query cost when it came from another operation, and it does not
infer child reads from source text. Millisecond timings describe Seon's typed
request boundary; Datahike resource counters describe engine work. Cache
outcomes remain Datahike's keywords.

The turn stores a small projection because it is historical evidence, not
derived current state. The debug page derives its cards from that projection.
An absent projection means instrumentation was off or the turn predates the
contract; it must never display zeros that imply measured work.

## Acceptance

- With mode `:off`, a characterization proves the request path never reads the
  clock, mutates an aggregate, expands an IPC result, or writes measurement
  attributes.
- With `:aggregate`, one execution-child invocation containing query, pull,
  pull-many, entity, schema, index, and `execute-many` reads produces one
  bounded aggregate and preserves its existing dependency evidence unchanged.
- Query cache hit/miss and Datahike resource totals equal the transported
  producer evidence; composed reads are counted once at the typed request
  boundary.
- The parent stores the aggregate on the exact turn and
  `seon.agent.debug/turn` plus `/agent/{id}/debug` show it without rereading
  process-local counters.
- A failed or retired child either returns the bounded evidence already
  completed or reports it absent; instrumentation never changes failure,
  timeout, retry, or cleanup behavior.
- Focused overhead measurement compares `:off` and `:aggregate` over a cached
  read loop. The mode remains configurable rather than justified by an
  unmeasured claim that aggregation is cheap.
