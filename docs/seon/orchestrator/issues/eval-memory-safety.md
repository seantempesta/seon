---
type: issue
status: open
tags: [issue, agent, flow, database, architecture]
severity: cleanup
---

# An agent can OOM its own pod via unbounded eval results / whole-DB queries

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

Two unbounded surfaces:

1. **Store-time:** `record-eval!` (`src/seon/eval.cljs:~834`) `pr-str`s the eval
   value into `:seon.eval/result-edn` with no size cap; `:seon.turn/prompt-text`
   likewise stored uncapped. The DB accumulates multi-MB blobs.
2. **Query-time:** an agent eval like `pull [*]` on a richly-connected entity, or
   a whole-DB `[?e ?a ?v]` scan, materializes arbitrarily large results in heap
   with no guard.

## Acceptance criteria

- [DONE 2026-06-08] `:seon.eval/result-edn` capped at store time (sane limit,
  elision marker) so the DB never holds multi-MB result blobs. (`render` cap
  already exists; this is the store-time complement.) Also capped
  `:seon.eval/error`.
- [DONE 2026-06-08] `:seon.turn/prompt-text` capped at store time similarly.
- [OPEN — see "In-heap guard" finding below] A guard against an agent eval
  materializing an unbounded result (bound result size / heap guard in the eval
  path, or a documented limit) — at minimum, the pod must not die from one bad
  query. Investigated; the cheap lever is a DB-surface row-cap, not an
  eval-path size check. Deferred to a focused follow-up.
- [DONE 2026-06-08] A regression test: a huge value is stored/capped bounded and
  the live stash still returns the full value
  (`test/seon/eval/memory_safety_test.cljs`).

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
happens *before* `record-eval!` runs: in `eval-batch!`
(`src/seon/eval.cljs` ~line 1023) the agent's form is evaluated and its result
fully materialized in heap (`raw-result` → `:value`), then **stashed verbatim
on globalThis** (`stash-result-raw!`, ~line 1045) where it stays live for the
whole session. Two transient-heap surfaces remain unbounded:

1. A single eval whose RESULT is huge — `(seon.db/pull {... '[*] ... 111})` or
   `(seon.db/query '[:find ?e ?a ?v :where [?e ?a ?v]])` (whole-DB scan).
   `d/q`/`d/pull` build the entire result vector in heap inside the eval; if it
   is multi-hundred-MB the pod OOMs at `d/q` time, before any cap can run.
2. The live-result stash itself accumulates every successful eval's full value
   on globalThis for the session lifetime — unbounded retained heap across many
   large evals (a slower leak than #1).

Why a guard is NOT trivially cheap here:

- You cannot measure a CLJS value's heap size without walking/serializing it,
  which itself materializes the cost you are trying to avoid. `pr-str` then
  checking length defeats the purpose (the 9.7M string is the expensive part).
- The real fix is upstream: bound the QUERY, not the result. Datahike has no
  built-in `:limit`, so the cheap lever is a wrapper around `seon.db/query` /
  `seon.db/pull` that (a) refuses unbounded whole-DB find patterns, or (b)
  truncates the result seq with a hard row cap via `take`/`bounded-count`
  BEFORE realizing the whole thing. `(bounded-count N coll)` realizes at most N
  elements — that is the one genuinely cheap heap guard available.

Recommendation (defer to a focused follow-up task, sized ~M):

1. **Row-cap the stash + result at realization.** In `eval-batch!`, before
   `stash-result-raw!`, if `(:value result)` is a seq/coll, realize at most a
   const `result-row-cap` (e.g. 100k) elements via `bounded-count` and stash a
   `take`'d, elided view when it exceeds. This bounds both #1's stash and #2's
   retained heap. Caveat: a single huge SCALAR (one 9.7M string from `pull
   [*]`) is not a long seq, so row-cap alone misses it.
2. **Guard the DB surface, not the eval.** Add an opt-out soft limit to
   `seon.db/query`/`pull`: reject a `:find` with no entity-binding constraint
   (pure `[?e ?a ?v]` whole-DB scan) and apply a default `take` to query
   results. This is the highest-leverage cheap guard and stops the exact two
   evals that caused the live OOM.
3. **Bound the stash lifetime.** The session-lifetime globalThis stash is a
   slow leak; an LRU/ring of the last N eval-ids would cap retained heap. Lower
   priority than 1+2.

None of these are in scope for this task (store-time caps were the must-have);
they are flagged here so the OOM root cause is not mistaken for closed. The
store-time caps guarantee the *DB* never holds a multi-MB blob (so a later
whole-DB scan over `result-edn`/`prompt-text` datoms — the exact second OOM
observed — is now bounded); the *transient eval-time* heap blow-up from one bad
query is still open.

## Notes

- `seon.db/with-agent` takes a bare **string** agent-id, NOT a map — passing a
  map stamps it as tx-meta `:seon.db/agent-id`, fails the `string?` schema, and
  the whole tx is silently rejected (the error envelope looks ok-ish). Cost two
  debugging detours this session; worth a docstring/precondition fix.
