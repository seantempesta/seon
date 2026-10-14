---
title: "Card RF-1 Write a branch's declarations back without a merge"
type: reference
status: done (24e8cc2df)
created: 2026-10-08
---

# RF-1 Write a branch's declarations back without a merge

Design: `docs/research/agent-platform/2026-10-08/repl-first/repl-first.md` §2 B2. Lane: Sonnet. Depends on the
landed B1 fix (`seon.cluster.source/proven-file` analyzes against the merged database).

`seon.cluster.source/write-back!` needs only `{:seon.program/identities :seon.source/base-commit-id
:seon.source/commit-id}`; today its one agent entrance, `my.program/write-back!`, takes `accept-merge!`'s answer.
Measured from the agent `repl-first` (2026-10-08): the owner call with main's head as base and the branch head as
commit runs in 458 ms and, after B1, writes the branch's definition.

## Functions

- ADD `my.program/write-branch!` `[:=> [:cat [:map [:my.program/context :my.program/context] [:seon.fn/root :seon.fn/root]
  [:seon.program/identities {:optional true} :seon.program/identity-set]]] [:or :seon.source/written
  :seon.source/write-back-error :seon.error/value]]`: identities default to the changed and added declarations of
  `my.program/diff` (removed ones too); base = the cluster head commit `head-comparison` already reads; commit =
  my branch head. Calls `source/write-back!` on my branch connection. Lives in `src/my/program.clj` (claimed by
  led-nk1 today: wait or patch).
- No new schema: `:seon.source/accepted` already admits this value (`:seon.test.run/id` is optional).

## Proof

`my.program-test/write-branch!-writes-my-defn-into-its-file` (new): fixture file on H, agent defn on its branch,
`write-branch!` answers `:seon.source/files` with the identity replaced, and the file reindexed from disk declares
the branch digest. Reaching: `seon.cluster.write-back-test`.

## Cost

One merge-base walk (`diff`, about 35-50 ms measured) plus one write-back (one file analysis, 35-170 ms).
