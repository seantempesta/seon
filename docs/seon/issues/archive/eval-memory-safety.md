---
type: issue
status: resolved
tags: [issue, agent, flow, database, architecture]
severity: blocker
---

# An agent can OOM the pod via unbounded query and eval values

## Problem

An agent could materialize an unbounded Datahike query or pull, then retain the
complete result in `result/<id>`. In the original 2026-06-08 incident, a 9.7M-
character pull was persisted and a later whole-database scan exhausted Node's
heap, killing the in-memory pod database used by the retired architecture.

Store-time result/error caps and blob-backed prompt projections closed the
persisted-string half first, but query/pull realization, the query cache, and
live result slots remained unbounded until the runtime-reliability refactor.

## Resolution

Maintained Datahike commit `1e78cb9c` adds synchronous work, result-node, and
shallow-weight budgets to query and pull execution, including nested find-pull
inheritance and bounded cache admission. `seon.db/query` and `seon.db/pull`
apply hard ceilings that callers may lower but never raise, and read replay
captures the normalized budgets.

`seon.eval/admit-result-value` now performs iterative, non-serializing bounded
inspection before transcript recording or `result/<id>` retention. Small
immutable values retain identity; strings and buffers use O(1) weights; shared
references are counted once; and overweight, lazy, or opaque values become a
compact recovery descriptor. Late Promise settlement uses the same gate.

Hard containment of arbitrary JavaScript or dependency allocation is a
separate process-boundary concern tracked by
[[eval-process-isolation-memory-containment]].

## Evidence

- Datahike JVM: 117 tests/309 assertions.
- Datahike CLJS: 104 tests/821 assertions.
- Nested find-pull budget matrix: three tests/21 assertions.
- Seon focused: database 50/346, read observation 8/76, eval memory 13/40,
  result slots 8/29, record/retry 28/130.
- Complete CLJS: 1,305 tests/6,175 assertions, zero failures and errors.
- Writer: 50 tests/308 assertions; operator: 81/532.
- Live default cluster: a query with `:seon.db/max-results 1` returned
  `:datahike/budget-exceeded` after observing two rows, 100 repeated exhausted
  queries completed with `:recovered`, and a later normal query returned all
  three rows. A 300 KB string became the weight-cap descriptor and the next
  eval returned `42`. The writer and pod remained ready.

The source-grounded design and falsification matrix are retained in
[[docs/prds/agent-runtime-correctness/research/eval-query-memory-safety-audit-2026-07-14]].

## Owner

The one maintained Datahike query/pull executor, `seon.db` read boundary, and
`seon.eval` retained-result owner.

## Acceptance

Completed: query and pull stop during execution with structured budget data;
cache and live-result retention cannot admit an uncertified large value; small
values retain their behavior; exhaustion does not wedge the pod; and focused,
complete, writer, operator, and live default-cluster proof is green.
