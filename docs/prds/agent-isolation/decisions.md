# Agent Isolation - Architectural Decisions

## Decision 1: Namespace is the Unique Identifier

**Date**: 2026-01-04

**Decision**: The Clojure namespace (e.g., `seon.trading`) is THE identifier for everything:
- Database name derived from namespace
- nREPL port derived from namespace
- Web routing derived from namespace
- Git worktree derived from namespace
- One agent per namespace at a time

**Rationale**:
- No artificial naming schemes to maintain
- Clear 1:1 mapping: namespace = isolated environment
- Simple mental model for agents

---

## Decision 2: Separate nREPL Per Namespace

**Date**: 2026-01-04

**Decision**: Each active namespace gets its own nREPL server within the shared JVM.

**Rationale**:
- **Concurrent evals**: One agent's long computation doesn't block others
- **Namespace binding**: nREPL starts with `(in-ns 'seon.trading)` already done
- **Memory efficient**: Still shared JVM, just separate nREPL threads
- **Port-based routing**: Easy to direct Claude Code to correct nREPL

**Trade-off accepted**: Shared heap means one OOM crashes all, but that's rare.

**RESEARCH NEEDED**: Can nREPL start multiple servers in one JVM?

---

## Decision 3: One Agent Per Namespace (Shared Infrastructure)

**Date**: 2026-01-04

**Decision**: With shared infrastructure, only one agent can work on a namespace at a time.

**Rationale**:
- Shared code in worktree = two agents editing same files = conflict
- Simplifies orchestrator logic (no merge conflicts)
- Namespace is "locked" while agent is active
- Can queue agents if namespace is busy

---

## Decision 4: Injected Context Atom (ctx)

**Date**: 2026-01-04

**Decision**: When an agent is assigned a namespace, inject a `ctx` atom with fully spec'd keys.

**Proposed ctx structure** (`:seon.agent/` namespace):
```clojure
{:seon.agent/namespace     'seon.trading
 :seon.agent/session-id    "trading-20260104-abc123"
 :seon.agent/db            <xtdb-connection>       ; Namespace's isolated DB
 :seon.agent/render-fn     (fn [hiccup] ...)       ; Render to web UI
 :seon.agent/sse-push-fn   (fn [fragment] ...)     ; Push SSE update
 :seon.agent/worktree-path "/path/to/worktree"
 :seon.agent/nrepl-port    7889
 :seon.agent/started-at    #inst "..."}
```

**RESEARCH NEEDED**: How to inject? Options:
1. Dynamic var in nREPL session
2. Well-known atom (e.g., `seon.agent/*ctx*`)
3. MCP tool that returns context

---

## Decision 5: Agent-Provided Hiccup

**Date**: 2026-01-04

**Decision**: Agents provide Hiccup EDN, orchestrator handles rendering and SSE delivery.

**Example**:
```clojure
(let [{:seon.agent/keys [db render-fn]} @ctx]
  (let [trades (xt/q db "SELECT * FROM trades")]
    (render-fn
      [:div#trades-list
       (for [t trades]
         [:div.trade (:symbol t)])])))
```

**RESEARCH NEEDED**: Does this work with Datastar SSE?
