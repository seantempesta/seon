---
type: issue
status: open
tags: [issue, agent, flow, database]
severity: architectural
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

- `:seon.eval/result-edn` capped at store time (sane limit, elision marker) so
  the DB never holds multi-MB result blobs. (`render` cap already exists; this is
  the store-time complement.)
- `:seon.turn/prompt-text` capped at store time similarly.
- A guard against an agent eval materializing an unbounded result (bound result
  size / heap guard in the eval path, or a documented limit) — at minimum, the
  pod must not die from one bad query.
- A regression test: a pull/query returning a huge structure does NOT OOM and is
  stored/rendered bounded.

## Refs

- `src/seon/eval.cljs` (`record-eval!`), `src/seon/agent.cljs`
  (`format-eval-row` render cap, already landed in `5f2a564`)
- Surfaced by [[context-derived-not-stored]]
- T2b flagged `eval.cljs` as the store-time cap site (out of that task's scope).

## Notes

- `seon.db/with-agent` takes a bare **string** agent-id, NOT a map — passing a
  map stamps it as tx-meta `:seon.db/agent-id`, fails the `string?` schema, and
  the whole tx is silently rejected (the error envelope looks ok-ish). Cost two
  debugging detours this session; worth a docstring/precondition fix.
