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

**RESOLVED**: Yes, nREPL fully supports multiple servers in one JVM. Each `nrepl.server/start-server` creates an independent server with its own socket and handler. See `docs/prds/agent-isolation/research/nrepl-multi-server.md`.

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
**Updated**: 2026-01-06

**Decision**: When an agent is assigned a namespace, inject a `*ctx*` atom via custom nREPL middleware.

**Final ctx structure** (`:seon.agent/` reserved keys):

```clojure
{:seon.agent/namespace   'seon.trading      ; Read-only identity
 :seon.agent/db          <xtdb-connection>  ; Direct SQL access (Level 3)
 :seon.agent/render      (fn [hiccup] ...)  ; Push UI updates (Phase 5)
 :seon.agent/worktree    "/path/to/worktree"} ; Git worktree (Phase 6)

```

Agent state uses namespaced keys:

```clojure
{:seon.trading/signals [{:symbol "AAPL" ...}]
 :seon.trading/notes "analysis in progress"}

```

**RESOLVED**: Use custom nREPL middleware that injects `*ctx*` dynamic var into sessions:

```clojure
(def ^:dynamic *ctx* nil)

(defn make-context-middleware [ctx-atom target-ns]
  (fn wrap-context [handler]
    (fn [{:keys [session] :as msg}]
      (when (and session (not (contains? @session #'*ctx*)))
        (swap! session assoc
               #'*ns* (find-ns target-ns)
               #'*ctx* ctx-atom))
      (handler msg))))

```

Middleware descriptor uses **var references** (not strings):

```clojure
(set-descriptor! #'wrap-context
  {:requires #{#'nrepl.middleware.session/session}
   :expects #{#'nrepl.middleware.interruptible-eval/interruptible-eval}})

```

See `src/seon/orchestrator/nrepl.clj` for implementation.

---

## Decision 5: Agent-Provided Hiccup

**Date**: 2026-01-04
**Updated**: 2026-01-06

**Decision**: Agents provide Hiccup EDN, orchestrator handles rendering and SSE delivery.

**Example**:

```clojure
(let [{:seon.agent/keys [db render]} @*ctx*]
  (let [trades (xt/q db "SELECT * FROM trades")]
    (render
      [:div#trades-list
       (for [t trades]
         [:div.trade (:symbol t)])])))

```

**RESEARCH NEEDED**: Does this work with Datastar SSE? Need to scope SSE sessions per namespace.
