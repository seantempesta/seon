---
type: issue
status: open
severity: friction
tags: [issue, operator, wave/dev-mcp]
---

# MCP runtime_status lists no clusters for an explicit live root

## Problem

`runtime_status` accepts a `root` argument, but for the live development root
it returns an empty cluster list, while `eval_clj` with the same `root` and
`cluster` discovers the advertisement and evaluates. Discovery and evaluation
disagree about the same root, so the sanctioned "what is live?" question
answers "nothing" for the one cluster the edit hook publishes to.

## Evidence

2026-09-06, `tmp/juniper-context-live` with `juniper-context` alive
(pid 18934, later pid of the 03:19Z restart), `bin/seon --root
tmp/juniper-context-live status` reporting `1/1 clusters alive`:

```clojure
;; runtime_status {:root "/Users/sean/src/seon/tmp/juniper-context-live"}
{:seon.dev.mcp/root "/Users/sean/src/seon/tmp/juniper-context-live"
 :seon.dev.mcp/clusters []
 :seon.dev.mcp/selected-cluster "default"}
;; eval_clj {:root <same> :cluster "juniper-context" :code "(+ 1 2)"} ⟹ 3
```

The empty list reads absence as health — exactly the check class the shared
instructions call worse than nothing.

## Owner

`script/seon/dev/mcp.clj`, `execute-runtime-status`: its discovery must use
the same root-scoped advertisement census `eval_clj` uses, and an explicit
root with zero advertisements should say so as a typed value rather than an
empty vector.

## Acceptance

`runtime_status` with the hook's `:current-source` root lists that cluster
with its advertisement; a root with no advertisements returns a typed
"no advertisements under ROOT" value; one regression covers both.
