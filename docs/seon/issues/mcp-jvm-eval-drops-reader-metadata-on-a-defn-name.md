---
type: issue
status: open
severity: friction
tags: [issue, mcp, repl, metadata, contracts, agent-experience]
---

# MCP JVM evaluation drops reader metadata on a `defn` name

## Problem

An agent that writes idiomatic Clojure metadata on the name,
`(defn ^{:malli/schema [...]} f "doc" [x] ...)`, silently loses it when the form is
evaluated through the MCP `eval_clj` tool in JVM mode: the Var has its docstring but no
`:malli/schema`. The same keys in `defn`'s attribute map survive. Plain Clojure keeps both.
A contract written this way is never armed, and nothing tells the agent.

Found by a dogfood agent on 2026-09-24 (toy `weekly-summary` task, Codex Sol): its first
redefinition carried the contract as name metadata and the task check still reported
`:malli/schema` missing. Reproduced by the orchestrator in a throwaway namespace:

```clojure
;; mcp eval_clj, cluster default, mode jvm, namespace scratch.probe-meta
(do (defn ^{:malli/schema [:=> [:cat :int] :int] :probe/x 1} probe-meta-a "doc" [x] x)
    (defn probe-meta-b "doc" {:malli/schema [:=> [:cat :int] :int] :probe/x 1} [x] x)
    {:name-meta (select-keys (meta #'probe-meta-a) [:malli/schema :probe/x :doc])
     :attr-map  (select-keys (meta #'probe-meta-b) [:malli/schema :probe/x :doc])})
;; => {:name-meta {:doc "doc"}
;;     :attr-map  {:doc "doc", :malli/schema [:=> [:cat :int] :int], :probe/x 1}}
```

## Cause

UNVERIFIED. Likely the form is re-serialized between the MCP server and the prepl
(`pr-str` does not print metadata unless `*print-meta*` is bound), so reader metadata is lost
before evaluation. Verify at `bin/mcp-server` / `seon.operator.prepl` before fixing.

## Success test

The probe above returns `:malli/schema` and `:probe/x` for both spellings through the MCP
tool, in JVM and SCI modes.
