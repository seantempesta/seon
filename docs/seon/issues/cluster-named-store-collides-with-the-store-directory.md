---
type: issue
status: open
severity: friction
tags: [issue, cluster, operator, correctness]
---

# A cluster named `store` collides with the store directory

## Problem

`:seon.boot/cluster-name` is `[:string {:min 1}]`
(`resources/seon/schemas/seon.boot.edn:14`) with no reserved names, and
`cluster-paths` (`src/seon/cluster.clj:541`) places a cluster at
`<root>/<name>` — the same directory level where the process root's store
lives (`data/clusters/store`). Creating a cluster named `store` would
write cluster state into the store directory. Nothing refuses it today.
The symptom has already surfaced once: a past enumeration reported `store`
as a cluster
([mcp-toolset-audit-2026-08-01.md:184](../../prds/sci-execution-runtime/research/mcp-toolset-audit-2026-08-01.md));
the current enumerator hides it only by filtering on `prepl.edn` presence
(`resources/seon/operator/state.clj:652-660`) — a filter, not a guard.

## Owner

The cluster-name admission seam. The fix is a loud typed refusal of the
reserved sibling names (`store`, `store.lock`, `blob-staging`) at cluster
creation — or the R3 layout change that moves the non-cluster children
out of `data/clusters/` entirely, which dissolves the collision
structurally
([options doc](../../prds/sci-execution-runtime/research/store-path-rename-options-2026-08-13.md),
option 2 + addendum).

## Acceptance

Creating a cluster with a reserved name returns a flat `:seon.error`
refusal naming the collision; one regression asserts it.
