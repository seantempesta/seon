---
type: issue
status: open
severity: friction
tags: [issue, rendering, program-graph]
---

# Fresh-corpus render retains a removed my.store reference

## Problem

The helper agent's namespace lens rendered `my.store/get`, but fresh `src/`
contains neither a `my.store` namespace nor any reference to that symbol.
Only `my.run` and `my.message` survive under `src/my/`. Recreating the deleted
API would violate the great deletion; the rendered row is stale database
program-graph evidence, not missing source.

## Owner

Fresh-cluster program-graph initialization and namespace render selection.

## Acceptance

A scratch cluster initialized from current source renders no `my.store`
namespace or function references. Existing clusters either reset to current
initialization pages or omit rows whose namespace no longer exists in the
current program graph.
