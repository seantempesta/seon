---
type: issue
status: open
tags: [issue, agent, flow, database, architecture]
severity: blocker
---

# An agent can OOM the pod via unbounded query and eval values

## Problem

The agent's eval results are stored verbatim and materialized unbounded, so a
single eval can exhaust Node's heap and kill the pod (taking the in-RAM
`:memory` datahike DB with it).

Observed 2026-06-08 while fixing [[context-derived-not-stored]]:

- An agent eval `(seon.db/pull {:seon.db/pull-pattern '[*] :seon.db/ref 111})`
  returned a **9.7M-char** result, stored verbatim as `:seon.eval/result-edn`.
- Later, an agent eval `(seon.db/query '[:find ?e ?a ?v :where [?e ?a ?v]])` (a
  whole-DB scan) **materialized** all the bloated `:seon.eval/result-edn` and
  `:seon.turn/prompt-text` datoms at once → `FATAL: Reached heap limit` → Node
  OOM → the `:memory` DB was lost with the process.

The context-render cap (`5f2a564`) bounds what reaches the LLM, but does NOT stop
the DB from holding multi-MB blobs nor stop a query/pull from materializing them.

## Root cause

The persisted strings are now bounded, but two in-heap surfaces remain:

1. **Query-time:** an agent eval like `pull [*]` on a richly-connected entity, or
   a whole-DB `[?e ?a ?v]` scan, materializes arbitrarily large results in heap
   with no guard.
2. **Retained values:** `result/<id>` slots are capped by count, but each slot
   retains its full value on `globalThis`. One huge value, or the bounded number
   of individually large values, can still retain an unsafe amount of heap.

The prior store-time surfaces are closed: `record-eval!` persists a bounded
render of the result/error, and turn prompts are append-only blob content with
bounded database projections rather than an uncapped `:seon.turn/prompt-text`
datom.

## Acceptance criteria

- [DONE 2026-06-08] `:seon.eval/result-edn` capped at store time (sane limit,
  elision marker) so the DB never holds multi-MB result blobs. (`render` cap
  already exists; this is the store-time complement.) Also capped
  `:seon.eval/error`.
- [DONE 2026-06-09] The retired `:seon.turn/prompt-text` datom was replaced by
  whole prompt blobs plus bounded database projections.
- [OPEN — see "In-heap guard" finding below] A guard against an agent eval
  materializing an unbounded result. The bound must apply before or during
  Datahike query/pull realization; truncating or serializing the already
  materialized value is not a memory guard. At minimum, one bad query must not
  kill the pod.
- [DONE 2026-06-08] A regression test: a huge value is stored/capped bounded and
  the bounded-count live result store still returns the full value
  (`test/seon/eval/memory_safety_test.cljs`).

## Current source check — 2026-07-14

- `seon.db/query` still delegates to `raw-query` and captures the already
  materialized result; it has no row or byte bound.
- `seon.db/pull` still delegates to Datahike `d/pull` after attribute/ref guards;
  wildcard and recursive pull materialization has no size bound.
- `seon.eval/bind-result-var!` prunes the oldest runtime slots to
  `result-vars-cap`, but stores each successful value verbatim before pruning.
- Maintained Datahike's process-global query-result cache stores the complete
  query value and weighs it only by top-level tuple count. A scalar or one-row
  result containing a huge string weighs one, so fixing result slots alone
  would leave another unbounded-value retention surface.
- `seon.eval.memory-safety-test/one-live-result-slot-retains-the-full-value`
  explicitly proves that a 5 MB value remains live in one slot. The existing
  collection render tests prove bounded presentation only, not bounded
  materialization or retained heap.

The source-grounded implementation design and falsification matrix are
[[docs/prds/runtime-reliability/research/eval-query-memory-safety-audit-2026-07-14]].

## Owner

The one `seon.db/query` / `seon.db/pull` boundary, including the JVM
database/heavy-compute seam when required for a true realization-time bound,
plus `seon.eval`'s existing `result/<id>` retention owner.

## Refs

- `src/seon/eval.cljs` (`record-eval!`), `src/seon/agent.cljs`
  (`format-eval-row` render cap, already landed in `5f2a564`)
- Surfaced by [[context-derived-not-stored]]
- T2b flagged `eval.cljs` as the store-time cap site (out of that task's scope).

## Store-time caps — DONE (2026-06-08)

The store-time complement to the render cap landed:

- New shared const + fn in `seon.eval`: `store-edn-cap` = **16384** and
  `cap-edn` (mirrors `seon.agent/cap-result` but at the larger store-time
  bound). 16k = ~10x the render cap (1500, never exceeded toward the LLM) and
  ~600x below the 9.7M blob that OOM'd — headroom for direct datom inspection
  while keeping any single persisted string bounded.
- `record-eval!` now wraps the `pr-str` of both `:seon.eval/result-edn` AND
  `:seon.eval/error` in `cap-edn`. (Capped `:seon.eval/error` too — it is the
  same uncapped-`pr-str` surface and an error can carry a huge data payload.)
- `seon.agent/with-turn!` now wraps `:seon.turn/prompt-text` in
  `seval/cap-edn`.
- The FULL value is unaffected: `eval-batch!` stashes the raw value on
  globalThis (`stash-result-raw!`) BEFORE `record-eval!` runs, and `(result
  <id>)` reads that stash — capping the persisted datom does not touch it.
- Live before/after (live pod DB at fix time): max stored
  `:seon.eval/result-edn` was **34,617 chars** (n=11), max
  `:seon.turn/prompt-text` **21,005** (n=6) — both above 16k, so the cap is
  load-bearing, not theoretical. New writes cap at 16384 + a ~24-char marker
  (verified: a 2M-char prompt → 16,408 stored).
- Tests: `test/seon/eval/memory_safety_test.cljs` — 7 tests / 20 assertions,
  green. Covers: huge value capped + elision marker present, live stash still
  returns the FULL value, normal small result stored verbatim (no spurious
  truncation), nil-safety, explicit-limit, huge-prompt capped.

## In-heap guard — INVESTIGATE finding + recommendation (NOT implemented)

The store-time caps do **not** close the OOM that started this issue. The OOM
happens before `record-eval!`: the agent's form is evaluated and its result is
fully materialized, then `bind-result-var!` stores that value verbatim in a
bounded-count `globalThis.result` slot. Two transient-heap surfaces remain:

1. A single eval whose RESULT is huge — `(seon.db/pull {... '[*] ... 111})` or
   `(seon.db/query '[:find ?e ?a ?v :where [?e ?a ?v]])` (whole-DB scan).
   `d/q`/`d/pull` build the entire result vector in heap inside the eval; if it
   is multi-hundred-MB the pod OOMs at `d/q` time, before any cap can run.
2. The live-result store retains full values on `globalThis`. Slot count is
   bounded, but retained bytes are not; the cap therefore limits cardinality,
   not memory.

Why a guard is NOT trivially cheap here:

- You cannot measure a CLJS value's heap size without walking/serializing it,
  which itself materializes the cost you are trying to avoid. `pr-str` then
  checking length defeats the purpose (the 9.7M string is the expensive part).
- The maintained Datahike fork accepts query-map `:limit`, but the planned
  relation and legacy paths collect and deduplicate before applying it. A safe
  20-row predicate-counter probe returned two rows for `:limit 2` while still
  invoking the predicate 20 times. The direct executor has a `max-results`
  seam, but its caller currently passes `nil`. The existing limit is therefore
  result semantics, not a realization or work bound.
- Pull applies a per-attribute default limit of 1,000, but wildcard component
  expansion and recursive/subpattern frames have no global entity/datom/result
  budget. An attribute-level limit, including an explicit `nil`, cannot be the
  safety boundary.

Recommendation (defer to a focused follow-up task, sized ~M):

1. **Guard Datahike during execution.** Add synchronous work/output/weight
   budgets to the maintained query executor and a global budget to the existing
   pull frame machine, then set hard defaults at `seon.db`. Applying `take`,
   `bounded-count`, `pr-str`, or a size check after `d/q` or `d/pull` returns is
   too late. Do not move unbounded work into the sole JVM writer; that only
   moves the crash into the database authority.
2. **Repair query-cache admission.** Reuse bounded shallow weight accounting or
   skip an entry that cannot be certified without crossing the inspection
   budget. Tuple count is not memory weight.
3. **Bound retained value weight.** Before installing a result slot, perform a
   bounded non-serializing structural inspection that stops after `N + 1`
   nodes and charges O(1) string/byte lengths. Preserve small values exactly;
   represent rejected values with a bounded descriptor or an existing blob
   handle. Never serialize an arbitrary oversized compound value merely to
   decide whether to keep it.

These repairs are the current runtime-reliability blocker; store-time caps were
necessary but did not close the OOM root cause. The
store-time caps guarantee the *DB* never holds a multi-MB blob (so a later
whole-DB scan over `result-edn`/`prompt-text` datoms — the exact second OOM
observed — is now bounded); the *transient eval-time* heap blow-up from one bad
query is still open.

## Notes

- `seon.db/with-agent` takes a bare **string** agent-id, NOT a map — passing a
  map stamps it as tx-meta `:seon.db/agent-id`, fails the `string?` schema, and
  the whole tx is silently rejected (the error envelope looks ok-ish). Cost two
  debugging detours this session; worth a docstring/precondition fix.
