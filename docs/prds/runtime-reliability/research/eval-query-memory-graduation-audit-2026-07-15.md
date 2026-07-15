---
type: research
status: completed
tags: [research, agent, database, flow, architecture]
---

# Eval/query memory graduation audit — 2026-07-15

## Decision

The explicit unit-0 eval/query materialization and retained-result memory
hazards are implemented in the current branch and have enough integrated
evidence to remain graduated. They do **not** block unit 1's ordered clean-
restart proof. The maintained Datahike execution owner, Seon's one database
boundary, and Seon's one result-slot owner all remain present at the exact
pinned source revision, and the retained live proof exercises the failure and
recovery behavior through the running default pod.

The original design audit's exhaustive falsification matrix is broader than
the evidence retained for unit 0. Explicit path-level proofs for a broad join,
disconnected Cartesian components, budgeted recursive pull, live pod pull
exhaustion, and before/after heap/RSS stabilization are missing or indirect.
Those gaps do not reveal a new implementation root cause and do not justify
reopening the settled owners during lifecycle work. They are the shortest
post-restart regression gate and final unit-9 performance evidence.

The architecture's blob wording is also aspirational beyond the safety fix:
an overweight arbitrary eval result becomes a compact recovery descriptor; it
is not automatically copied into `my.blob`. The memory invariant is true, but
the broader target sentence that a large eval value remains addressable by a
result symbol **or blob hash** is only true when the producer explicitly uses
the blob capability. This is a later blob/observability capability delta, not a
reason to retain the unsafe raw object.

No production source, roadmap, process, live cluster, or ACME state was
changed for this audit. No new issue was opened because the only missing facts
are proof breadth and an already-explicit aspirational capability; the known
hard arbitrary-allocation residual remains owned by
[[docs/seon/issues/eval-process-isolation-memory-containment]].

## Dependency ledger

| Dependency or owner | Selected identity | Exact source read | First-party consumer/evidence |
|---|---|---|---|
| Maintained Datahike | `417649383c65e13f15ea41d394fb1ed742477965`, selected by both root `:writer` and `:cljs` aliases | `reference-code/datahike/src/datahike/resource.cljc`, `query.cljc`, `query/execute.cljc`, and `pull_api.cljc` | `src/seon/db.cljs`; Datahike query, pull, and weighted-LRU tests |
| ClojureScript self-host/runtime | `1.12.145` | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` and async/compiler sources named by the `clojurescript` skill | `src/seon/eval.cljs`; eval memory/result tests |
| Seon read boundary | current branch | `src/seon/db.cljs` and `src/seon/db/internal.cljs` | `test/seon/db_test.cljs`, `test/seon/db/read_observer_test.cljs` |
| Seon retained-value owner | current branch | `src/seon/eval.cljs` and the shared opaque-value predicate in `src/seon/render/value.cljs` | `test/seon/eval/memory_safety_test.cljs`, `result_var_test.cljs`, `record_eval_tee_test.cljs`, and transcript tests |
| Existing design/evidence | commits `9843e318` and `ddecf018`; Datahike commits included in pinned `417649` | [[docs/prds/agent-runtime-correctness/research/eval-query-memory-safety-audit-2026-07-14]] | [[docs/seon/issues/archive/eval-memory-safety]] and this PRD's unit-0 checkpoint |

`git -C reference-code/datahike rev-parse HEAD` equals the SHA in both
dependency aliases. The current checkout therefore supplies the actual code
selected by the pod and writer rather than a nearby library version.

## Requirement-to-evidence matrix

### Database query and pull materialization

| Concrete requirement | Current authoritative evidence | Verdict |
|---|---|---|
| Query execution has independent work, result-count, and shallow-weight ceilings; exhaustion is structured and never a silent prefix | `datahike.resource` owns the per-operation counters, O(1) scalar weights, bounded structural certification, and `:datahike/budget-exceeded` data. `datahike.query/raw-q*` binds that budget around the maintained executor and final certification. | **Implemented.** The gate is inside library execution, not after Seon has materialized the value. |
| Query work is charged across planned and legacy execution, including scalar aggregates and find-pull | The pinned source threads the budget through the executor's existing cancellation checkpoints. `synchronous-resource-budgets` proves semantic `:limit` plus ordering cannot bypass work, relation and legacy collectors fail before returning a prefix, scalar aggregate work fails, find-pull inherits result weight, and a later query succeeds. | **Implemented and focused-proven.** Explicit disconnected-component and broad-join budget fixtures are not retained. |
| Pull has one global budget across wildcard, attributes, components, recursion, and `:limit nil` | `pull_api.cljc` charges frame-loop work, wildcard datoms, values, and result nodes under one dynamically inherited budget; nested find-pull inherits the active query budget. `global-pull-budget` proves wildcard component exhaustion, unlimited-selector weight exhaustion, and later recovery. | **Implemented and focused-proven.** A wide recursive-pull exhaustion fixture is missing, although the same frame loop owns recursion. |
| Datahike query-cache admission cannot pin a one-row huge or uncertifiable value | `query/result-cache-put!` calls one bounded `resource/shallow-weight-within`; nil skips admission. Weighted LRU stores the certified weight. Library tests prove lazy/non-certifiable inspection returns nil, a one-row huge string is not cached, scalar cache hits remain correct, and total cache weight stays bounded. | **Implemented and focused-proven.** |
| Seon's public database surface always clamps hard defaults and lets callers lower but never raise them | `src/seon/db.cljs` registers positive request options, defines query ceilings `2,000,000 / 50,000 / 8 MiB` and pull ceilings `250,000 / 25,000 / 4 MiB`, clamps both namespaced and Datahike-shaped request keys, and routes every public query/pull arity through those helpers. | **Implemented.** `query-and-pull-resource-budgets-are-clamped-and-recover` covers lower/raise behavior and structured query/pull failures. |
| Captured reads replay the identical normalized budgets without retaining a database handle or using an uncapped path | Query/pull observation schemas require all three normalized bounds. `execute-query`/`execute-pull` record the clamped request; `replay-read-result` calls private `raw-query`/`guarded-pull` with that request. Read-observer tests prove exact replay and no retained runtime handle. | **Implemented.** The tests prove the closed request shape indirectly; no focused assertion prints each captured numeric ceiling. |
| Exhaustion leaves the running pod usable | The unit-0 roadmap and archived resolved issue retain the default-cluster MCP proof: `max-results 1` failed after observed two, 100 repeated exhausted queries returned control, a later normal query returned all three rows, and writer/pod stayed ready. | **Live-proven for query.** There is no corresponding retained live pull-exhaustion transcript or heap/RSS series. |

### Retained eval results and persisted projections

| Concrete requirement | Current authoritative evidence | Verdict |
|---|---|---|
| Admission is bounded, iterative, non-serializing, cycle-aware for object identity, and rejects oversized, lazy, or opaque values | `seon.eval/admit-result-value` caps 4,096 distinct nodes and 256 KiB shallow weight; string and buffer length is O(1); a `WeakSet` skips shared/cyclic objects; uncounted collections and the shared `seon.render.value/opaque?` predicate fail closed. | **Implemented.** Small immutable values preserve identity. |
| The same admitted value reaches persistence/transcript and `result/<id>` | `record-eval!` admits before preparing/storing the result projection and returns only `::retained-value`; all bind sites use that returned admitted value. Allocation retry reuses the frozen descriptor rather than touching the raw lazy value. | **Implemented and focused-proven.** The lazy allocation-retry test demonstrates no realization and byte-stable candidate formatting. |
| Pending Promises may occupy one capped handle, but late settlement passes admission and cannot resurrect an evicted slot | `replace-live-result!` checks exact live membership, runs `admit-result-value`, and preserves insertion age. Slot eviction deletes both the JavaScript property and analyzer handle. | **Implemented and focused-proven.** Tests cover overweight settlement and an evicted id's failed late replacement. |
| Slot count and per-slot weight jointly give a finite retention envelope | The runtime object is the live-key/age authority; `bind-admitted-result-var!` prunes oldest keys over `result-vars-cap`. Per-slot caps precede installation. | **Implemented and focused-proven.** Cap tests show a plateau and exact analyzer/runtime co-eviction. |
| Persisted result/error text remains bounded without promising a dead raw handle | `record-eval!` formats the admitted value and passes it through `cap-edn`; the memory-safety tests prove the 16,384-character storage cap and descriptor storage. Transcript tests suppress a handle when exact runtime membership is absent. | **Implemented and focused-proven.** |
| Live oversized-result recovery is honest | Retained default proof: a 300 KB string became the 256 KiB weight-cap descriptor and the next eval returned `42`. | **Live-proven.** The descriptor points to narrower reads or explicit `my.blob/put!`; it does not claim the raw value remains retrievable. |

## Contradictions and evidence limits

### No contradiction in the safety owners

Current source still matches the resolved issue and the unit-0 claim:

- Datahike budgets execute before a result crosses into Seon;
- Seon's request boundary always supplies hard ceilings;
- read replay reuses those captured ceilings;
- cache admission performs a bounded certification walk;
- eval admission happens before persisted rendering and live binding; and
- pending settlement and oldest-first eviction use the same one runtime slot
  authority.

Later refactors did not reintroduce a second result atom, an uncapped replay
helper, a post-materialization-only guard, or a raw settled-Promise write.

### The original exhaustive matrix is not fully evidenced

The 2026-07-14 audit named twenty exact probes. Current code/tests/live records
directly cover the central owners, but these matrix rows remain indirect or
missing:

- broad intermediate join under a tiny work budget;
- disconnected-component Cartesian product under a tiny work budget;
- wide acyclic and cyclic recursive pull under a global budget;
- a live pod pull exhaustion followed by a normal pull;
- an explicit read-observer assertion that the three exact clamped numeric
  budgets survive capture and replay;
- a direct opaque-handle admission assertion (rendering has an opaque test;
  admission is source-proven through the same predicate); and
- repeated query **and pull** exhaustion with before/after heap and RSS showing
  return to a stable band.

These are proof gaps, not evidence that the owners are absent. The first three
are cheap dependency tests; the last is deliberately a source-frozen live or
unit-9 performance checkpoint.

### Blob-addressability is a later target delta

[[docs/seon/architecture/toolkit]] says a large value remains addressable by a
result symbol or blob hash, and [[docs/seon/architecture/observability]] names
oversized eval results as blob producers. Current safe behavior does not
automatically blob an arbitrary rejected value. That is intentional in the
source-grounded audit: serializing the rejected compound value would recreate
the memory failure, and even a plain-string automatic blob needs measured
copying/hash behavior. The architecture remains an aspirational target; the
current implementation honestly returns a descriptor with an explicit blob
recovery path.

## Shortest dependency-safe completion gate

Do not interrupt unit 1's clean restart/crash/restore spine. At the first
source-frozen post-restart checkpoint:

1. run one focused maintained-Datahike gate that adds or selects tiny fixtures
   for broad join, disconnected Cartesian, recursive pull, and exact cache
   admission, on both CLJ and CLJS where the shared implementation runs;
2. run the focused Seon database, read-observer, eval-memory, result-slot,
   record/retry, and transcript namespaces, adding only an exact captured-
   budget assertion if it is still absent;
3. through cluster-qualified MCP, issue one bounded failing query and one
   bounded failing pull, then one normal query/pull and `42`, proving the same
   pod and writer remain ready;
4. during that bounded live loop, record pod heap/RSS before, peak, and after
   explicit GC/settling in the unit-9 measurement format; and
5. keep arbitrary hostile allocation/process death in the already-open
   execution-containment owner. Do not use a query-budget result to claim that
   arbitrary JavaScript allocation is contained.

If those probes pass, no new implementation work belongs in the eval/query
memory owners. The final program gate then consumes their evidence as one row
of unit 9 rather than reopening unit 0.

## Evidence index

- `deps.edn:25-33,152-165` — exact maintained Datahike/Konserve selection.
- `reference-code/datahike/src/datahike/resource.cljc:7-157` — structured
  counters, bounded shallow certification, and work signal.
- `reference-code/datahike/src/datahike/query.cljc:2398-2427,2574-2585,4006-4064`
  — weighted cache admission and query budget binding.
- `reference-code/datahike/src/datahike/pull_api.cljc:128-175,264-345` — pull
  work/value/node charges and one inherited global budget.
- `src/seon/db.cljs:202-236,433-446,832-885,1312-1364,1793-1818` — request
  schemas, clamped ceilings, captured budgets, and bounded replay.
- `src/seon/eval.cljs:1189-1330,1674-1788,3218-3250,3280-3335,4447-4464` —
  structural admission, slot settlement/eviction, record-before-bind order.
- `test/seon/db_test.cljs:818-864` and
  `test/seon/db/read_observer_test.cljs:241-326` — Seon boundary/replay proof.
- `test/seon/eval/memory_safety_test.cljs:96-149`,
  `test/seon/eval/result_var_test.cljs:158-252`, and
  `test/seon/eval/record_eval_tee_test.cljs:257-310` — result admission,
  eviction, late settlement, and retry proof.
- `reference-code/datahike/test/datahike/test/query_cancel_test.clj:62-120`,
  `pull_api_test.cljc:68-97`, and `lru_weighted_test.cljc:11-130` — maintained
  library behavior.
- `docs/prds/runtime-reliability/roadmap.md:254-304` and
  [[docs/seon/issues/archive/eval-memory-safety]] — complete/focused counts and
  bounded default-cluster live evidence.
