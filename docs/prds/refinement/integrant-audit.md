# Integrant Deep Audit

## 1. Version Analysis

**We are running:** `integrant/integrant {:mvn/version "0.10.0"}` (deps.edn:14)

**Latest available:** `1.0.1` (2025-10-02, per reference-code/integrant/project.clj)

**We are 7 minor versions behind.** Here is what we are missing:

| Version | Key Changes |
|---------|-------------|
| 0.11.0 | BREAKING: removed `^:override` metadata |
| 0.12.0 | Added `profile`/`deprofile`, `annotate`/`describe`, `load-annotations`, inner fn arg to `expand` |
| 0.12.1 | Fixed bug with `expand` merging profiles |
| 0.13.0 | Added `var` and `bind` |
| 0.13.1 | Fixed bug expanding configs with records |
| 1.0.0-RC1 | BREAKING: **Removed `prep` and `prep-key` entirely** |
| 1.0.0 | Same as RC2 |
| 1.0.1 | Fixed composite keys with default `init-key` |

### Upgrade Risk Assessment

**Low risk.** We already use `ig/expand` instead of `ig/prep` (core.clj:57, user.clj:37). We do not use `^:override` metadata. The upgrade path is clean.

**Recommendation:** Upgrade to `1.0.1`. Our code already follows the modern patterns. The only change needed is bumping the version in deps.edn.

---

## 2. Features We Are and Are Not Using

### ig/expand vs ig/prep -- CORRECT

We correctly use `ig/expand` everywhere:
- `seon.core/start-app` (core.clj:63): `(ig/expand cfg)`
- `user/dev-prep!` (user.clj:38): `(ig/expand cfg)`
- `user/test-prep!` (user.clj:46): `(ig/expand cfg)`

No references to `ig/prep` remain. This is correct and future-proof. When we upgrade to 1.0.x, `prep` will not exist and our code will work unchanged.

### ig/pre-init-spec -- REMOVED upstream, replaced by assert-key

`pre-init-spec` was removed in 0.9.0-alpha1 and replaced by `ig/assert-key`. We do not use either.

### init-key default method -- available since 0.10.0

Since 0.10.0, the default `init-key` method looks for a function with the same fully-qualified name as the key. For example, `:seon.web.server/http-server` would look for `seon.web.server/http-server` (a function, not a defmethod).

We do NOT use this pattern -- we use traditional `defmethod ig/init-key` everywhere. This is fine. The function-based approach is an alternative style, not a replacement.

### suspend/resume -- partially used

See Section 5 for per-component analysis.

### derive for component hierarchies -- NOT USED

See Section 8.

### ig/ref vs ig/refset -- ref only

We use `#ig/ref` throughout system.edn. We do not use `#ig/refset`.

### ig/load-namespaces -- USED

Used correctly in `start-app` (core.clj:62) and `dev-prep!`/`test-prep!` (user.clj). We also explicitly require component namespaces in `seon.system` (system.clj:17-22), which is belt-and-suspenders but harmless.

### resolve-key -- NOT USED

`resolve-key` controls what value dependents see when they reference a component. Potentially useful for hiding internals from dependents (e.g., datalevin-server returns `{:server server :port port :root root}` but dependents only need port).

### annotate/describe -- NOT USED (available since 0.12.0)

Allows attaching metadata to component keys for documentation/tooling. Not available in our current version (0.10.0). Would become available on upgrade.

---

## 3. Malli Config Validation via assert-key

`ig/assert-key` runs immediately before `init-key` with the fully-resolved config value (all `#ig/ref` replaced with actual component values). This is the right place to validate component configuration with Malli.

### The Opportunity

Right now our component configs are implicitly validated -- each `init-key` destructures what it expects and fails with confusing errors if something is wrong. There is no schema that says "this component expects a map with these keys of these types." This means:

1. **Typos in system.edn are silent.** Misspell `:port` as `:prot` and the component gets nil, falls through to a default, or throws a cryptic error deep in init.
2. **No programmatic introspection.** An agent cannot ask "what config does this component accept?" -- they have to read the source.
3. **No centralized config documentation.** The shape of each component's config is scattered across init-key destructuring patterns.

### Proposed Pattern

Define a Malli schema per component key, then use `assert-key` to validate:

```clojure
;; In each component namespace, register the config schema
(def config-schema
  [:map
   [:port [:int {:min 1 :max 65535}]]
   [:root :string]
   [:opts {:optional true}
    [:map
     [:idle-timeout {:optional true} [:int {:min 0}]]]]])

;; Single assert-key implementation that validates against registered schemas
(defmethod ig/assert-key :seon.db.datalevin/server [_ value]
  (when-let [schema (component-config-schema :seon.db.datalevin/server)]
    (when-not (m/validate schema value)
      (throw (ex-info (str "Invalid config for :seon.db.datalevin/server\n"
                           (me/humanize (m/explain schema value)))
                       {:key :seon.db.datalevin/server
                        :value value
                        :errors (m/explain schema value)})))))
```

### Centralized Config Registry

Better yet, a single registry that all components participate in:

```clojure
;; seon.system.config (new namespace)
(def component-configs
  "Registry of component key -> config schema.
   Used by assert-key for validation and by agents for introspection."
  {:seon.db.datalevin/server
   [:map
    [:port [:int {:min 1 :max 65535}]]
    [:root :string]
    [:opts {:optional true} [:map [:idle-timeout {:optional true} [:int {:min 0}]]]]]

   :seon.db.datalevin/connections
   [:map
    [:server :any]  ;; resolved ref
    [:ttl-ms [:int {:min 0}]]
    [:cleanup-interval-ms [:int {:min 1000}]]]

   ;; ...etc
   })

;; Generic assert-key using derive hierarchy (see Section 8)
(defmethod ig/assert-key :seon/component [_ value]
  ;; All seon components get validated if they have a registered schema
  ...)

;; Agent introspection
(defn describe-component [key]
  "What does this component accept?"
  {:config-schema (get component-configs key)
   :current-value (get @integrant.repl.state/system key)
   :status (if (get @integrant.repl.state/system key) :running :stopped)})
```

### Priority: Medium-High

This is infrastructure that pays for itself. Config typos are currently silent failures, and agents cannot programmatically discover what a component needs.

---

## 4. Component Naming Audit

### Current Names vs Proposed Names

The current naming is inconsistent. Some keys use the `:seon/` namespace with a generic name, some use the component's actual namespace. The principle should be: **the component key should match the namespace that defines it**, so you can find the code by reading the key.

| Current Key | Defined In | Intuitive? | Proposed Key |
|-------------|-----------|------------|--------------|
| `:seon/datalevin-server` | `seon.db.datalevin.server` | No -- "seon" is not the namespace | `:seon.db.datalevin/server` |
| `:seon/connection-manager` | `seon.db.datalevin.conn` | No -- generic name, wrong ns | `:seon.db.datalevin/connections` |
| `:seon/graph-db` | `seon.system` (inline) | **No -- misleading name.** This is a Datalevin connection to the runtime DB, not a "graph database" | `:seon/runtime` or `:seon.runtime/db` |
| `:seon/schema-registry` | `seon.system` (inline) | Acceptable | `:seon.schema/registry` |
| `:seon/nrepl-server` | `seon.system` (inline) | Acceptable | `:seon.dev/nrepl` |
| `:seon.web.server/http-server` | `seon.web.server` | **Good -- matches namespace** | Keep as-is, or simplify to `:seon.web/server` |
| `:seon.web/tailwind-watcher` | `seon.web.tailwind` | Close enough | `:seon.web/tailwind` |
| `:seon/caddy-proxy` | `seon.web.caddy` | No -- wrong ns | `:seon.web/caddy` |
| `:seon/agent-pool` | `seon.flow.pool` | No -- wrong ns | `:seon.flow/pool` |
| `:seon/primer-ctx` | `seon.system` (inline, delegates to `seon.primer.ctx`) | OK | `:seon.primer/ctx` |
| `:seon/orchestrator-sessions` | `seon.system` (inline, delegates to `seon.orchestrator.session`) | OK | `:seon.orchestrator/sessions` |
| `:seon/code-scanner` | `seon.system` (inline) | OK | `:seon.graph/scanner` |
| `:seon/claude-code` | `seon.system` (inline) | OK | `:seon.ai.claude/sdk` |

### The `:seon/graph-db` Name Problem

This is the most misleading name. It is NOT a graph database. It is a Datalevin connection that stores:
- Runtime registry data (what namespaces are running)
- Code graph entities (from the code scanner)
- Render system lookups

The DB name on disk is `seon.runtime`. The component initializes `seon.runtime/init!`. It should be called `:seon/runtime` or `:seon.runtime/db`.

### Naming Principle

**Component key = namespace that owns it.** If `:seon.db.datalevin/server` is the key, then `seon.db.datalevin.server` is where `init-key` lives. An agent sees the key in system.edn and immediately knows where to look.

The current pattern has a split where some components define their `init-key` in their own namespace (good: `seon.web.server`, `seon.web.tailwind`, `seon.web.caddy`, `seon.flow.pool`, `seon.db.datalevin.server`, `seon.db.datalevin.conn`) but others are defined inline in `seon.system` (bad: graph-db, schema-registry, nrepl-server, primer-ctx, orchestrator-sessions, code-scanner, claude-code).

The inline ones should either:
1. Move to their own namespaces (if complex enough), or
2. Keep the key consistent with what they delegate to

### Impact of Renaming

This is a mechanical refactor but it touches:
- `resources/system.edn` -- all key names and `#ig/ref` values
- `defmethod ig/init-key` / `halt-key!` / etc in each component ns
- Every `(:seon/foo state/system)` access in production code (grep found ~15 occurrences)
- Tests that reference component keys

It is safe to do as a single coordinated change. Integrant does not care about key names -- they are just keywords.

---

## 5. Component Lifecycle Audit

### Legend
- **init**: Has `ig/init-key` method
- **halt**: Has `ig/halt-key!` method
- **suspend**: Has `ig/suspend-key!` method (survives reset)
- **resume**: Has `ig/resume-key` method

### 5.1 :seon/datalevin-server (server.clj:191-222)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (config-aware) | Yes |

**Verdict: CORRECT.** Expensive to restart, no stale state on reload.

### 5.2 :seon/connection-manager (conn.clj:415-447)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (config-aware) | Yes |

**Verdict: CORRECT.**

### 5.3 :seon/graph-db (system.clj:123-176) -- should be :seon/runtime

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (config-aware) | Yes |

**Verdict: CORRECT lifecycle, wrong name.** This is the runtime Datalevin connection. It initializes `seon.runtime`, wires the render system, marks crashed instances, hydrates the cache. All of those are runtime concerns, not "graph" concerns.

### 5.4 :seon/schema-registry (system.clj:28-38)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | No | Minor issue |
| resume | No | Minor issue |

**Issue:** Re-initializes on every reset with log noise. Pure value, no resources. Should survive.

### 5.5 :seon/nrepl-server (system.clj:44-81)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (config-aware) | Yes |

**Verdict: CORRECT.**

### 5.6 :seon.web.server/http-server (server.clj:67-112)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | No | **ISSUE** |
| resume | No | **ISSUE** |

**Issue:** Restarts on every reset, dropping SSE connections. The handler uses `requiring-resolve` for late binding (server.clj:93-94), so code changes are already picked up without restart. **Should survive reset.**

### 5.7 :seon.web/tailwind-watcher (tailwind.clj:92-110)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | No | Minor |
| resume | No | Minor |

**Issue:** Restarts on reset. Tailwind `--watch` is stateless -- no reason to restart.

### 5.8 :seon/caddy-proxy (caddy.clj:77-101)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (process-alive check) | Yes |

**Verdict: CORRECT.** Minor: resume-key signature drops the `key` parameter and hardcodes `:seon/caddy-proxy` on line 100-101.

### 5.9 :seon/agent-pool (pool.clj:741-772)

| Lifecycle | Present | Correct |
|-----------|---------|---------|
| init | Yes | Yes |
| halt | Yes | Yes |
| suspend | Yes | Yes |
| resume | Yes (config-aware) | Yes |

**Verdict: CORRECT.**

### 5.10 :seon/primer-ctx (system.clj:88-92)

Missing halt, suspend, resume. Calls `seon.primer.ctx/init!` -- if idempotent, this is fine but sloppy.

### 5.11 :seon/orchestrator-sessions (system.clj:99-103)

Missing halt, suspend, resume. Calls `seon.orchestrator.session/init!` -- same issue.

### 5.12 :seon/code-scanner (system.clj:185-234)

Missing suspend/resume. **Full project re-analysis on every reset.** This is slow and unnecessary since the graph-db it writes to survives reset.

### 5.13 :seon/claude-code (system.clj:242-245)

Pure config map. Missing halt is fine. Re-init is instant.

---

## 6. Datalevin Pre-Reload and Recovery

### Current Flow

1. `:seon/datalevin-server` starts the server process
2. `:seon/connection-manager` creates a connection cache with TTL cleanup
3. `:seon/graph-db` opens a connection to the `seon.runtime` database, applies merged schema, initializes runtime, marks crashed instances, hydrates cache

### What Happens on Reset

Datalevin server and connection-manager both survive reset (suspend/resume). Graph-db also survives. So connections are preserved across reset. This is correct.

### What Happens on Crash (pkill -9)

LMDB corruption. The init-key for datalevin-server (server.clj:196-205) has crash recovery:

```clojure
(try
  (start-fn)
  (catch Throwable e
    (log/warn e "LMDB corruption detected, attempting recovery from backup")
    (attempt-recovery! root)
    (start-fn)))
```

This tries to restore from backup, or clears the data directory. Then `graph-db` init calls `runtime/mark-crashed!` to mark instances from the previous run. This is correct.

### What About Agent Databases?

Each agent gets its own Datalevin database via the connection-manager. These are lazily created on first connection and cleaned up by TTL. If an agent's DB gets corrupted, there is no per-namespace recovery -- the entire data directory gets nuked by the server's recovery path.

**Gap:** No per-namespace backup/restore. If one agent's DB corrupts, all DBs are lost in recovery. This is acceptable for now since agent DBs are ephemeral (rebuilt from source), but worth noting.

---

## 7. Agent Restartability -- The "Already Running" Problem

You asked about the case where an agent wants to restart a component but gets told "already running on port X, ignoring." This touches a fundamental question: **what should agents be able to control?**

### Current State

Agents interact with the system through `integrant.repl.state/system`:
```clojure
(:seon/connection-manager state/system)  ;; get a component
```

They cannot:
- Restart individual components
- See component health
- Know what config a component was started with
- Request a component restart without doing a full `(reset)`

### What Agents Need

An agent with REPL access should be able to:

1. **Inspect:** "What is running? What config does it have? Is it healthy?"
2. **Restart:** "This component is in a bad state, restart just this one"
3. **Discover:** "What components exist? What do they accept?"

### Proposed: Component Control API

```clojure
(ns seon.system.control)

(defn components
  "List all components with their status."
  []
  ;; => [{:key :seon.db.datalevin/server :status :running :config {:port 8898 ...}}
  ;;      {:key :seon.web/server :status :running :config {:port 8080}}
  ;;      ...]

(defn component
  "Get detailed info about a single component."
  [key]
  ;; => {:key :seon.db.datalevin/server
  ;;     :status :running
  ;;     :config {:port 8898 :root "data/datalevin"}
  ;;     :config-schema [:map [:port [:int ...]] ...]
  ;;     :value <the init-key return value>
  ;;     :depends-on [:nothing]
  ;;     :depended-on-by [:seon.db.datalevin/connections]}

(defn restart!
  "Restart a single component. Halts it, then re-inits with current config.
   Also restarts any components that depend on it (reverse dependency order)."
  [key]
  ;; Uses ig/halt-key! then ig/init-key on the component and its dependents

(defn healthy?
  "Check if a component is healthy."
  [key]
  ;; Component-specific health check -- delegates to the component's own check fn
```

### The Balance

The question is: should agents restart individual components, or always go through `(reset)`?

**Individual component restart is better** because:
- `(reset)` restarts everything that does not have suspend/resume
- An agent working on trading signals should not restart the HTTP server
- Failed components should be individually recoverable
- It matches how agents think: "this thing is broken, fix this thing"

**But it needs guardrails:**
- Restarting a component must also restart its dependents (Integrant's dependency graph handles this)
- The agent should see what will be affected before confirming
- Some components (datalevin-server) should require elevated confirmation since restarting them cascades to everything

### Implementation Path

This does not require new Integrant features. Integrant already supports partial init/halt via key filtering:

```clojure
(ig/halt! system [:seon.web/server])  ;; halts just this key + dependents
(ig/init config [:seon.web/server])   ;; re-inits just this key + dependencies
```

The control API would wrap this with introspection and safety checks.

---

## 8. Component Hierarchies via derive

Integrant supports `derive` to create parent-child relationships between component keys. This lets you:

1. **Group components** -- `(ig/halt! system [:seon/database])` halts all database components
2. **Share behavior** -- A single `assert-key` for `:seon/component` validates all components
3. **Selective reset** -- Restart just the web tier, or just the data tier

### Proposed Hierarchy

```clojure
;; All seon components derive from :seon/component
(derive :seon.db.datalevin/server :seon/component)
(derive :seon.db.datalevin/connections :seon/component)
(derive :seon.runtime/db :seon/component)
;; ...

;; Tier groupings
(derive :seon.db.datalevin/server :seon/data)
(derive :seon.db.datalevin/connections :seon/data)
(derive :seon.runtime/db :seon/data)

(derive :seon.web/server :seon/web)
(derive :seon.web/caddy :seon/web)
(derive :seon.web/tailwind :seon/web)

(derive :seon.flow/pool :seon/agents)
(derive :seon.ai.claude/sdk :seon/agents)

;; Now agents can do:
;; (ig/halt! system [:seon/web])  ;; restart just web tier
;; (ig/halt! system [:seon/data]) ;; restart just data tier
```

### Where to Put derive Calls

Integrant provides `load-hierarchy` which reads `integrant/hierarchy.edn` from the classpath. This is cleaner than scattering `derive` calls across namespaces:

```edn
;; resources/integrant/hierarchy.edn
{:seon.db.datalevin/server     [:seon/component :seon/data]
 :seon.db.datalevin/connections [:seon/component :seon/data]
 :seon.runtime/db              [:seon/component :seon/data]
 :seon.web/server              [:seon/component :seon/web]
 :seon.web/caddy               [:seon/component :seon/web]
 :seon.web/tailwind            [:seon/component :seon/web]
 :seon.flow/pool               [:seon/component :seon/agents]
 :seon.ai.claude/sdk           [:seon/component :seon/agents]}
```

Then in config loading: `(ig/load-hierarchy)` before `(ig/expand cfg)`.

### Value for Agents

With hierarchies, an agent can:
- `(ig/halt! system [:seon/web])` -- restart the web tier without touching databases
- Ask "what components are in the :seon/data group?" via `(filter #(isa? % :seon/data) (keys system))`
- Have a single `assert-key` for `:seon/component` that validates all component configs

---

## 9. Recommendations (Prioritized)

### Phase 1: Naming and Upgrade (1 agent, mechanical)

1. **Upgrade Integrant to 1.0.1** in deps.edn
2. **Rename component keys** to match their namespaces per the table in Section 4
3. **Rename `:seon/graph-db` to `:seon/runtime`** (or `:seon.runtime/db`) -- this is Datalevin, not a graph DB
4. **Move inline component definitions** from `seon.system` to their own namespaces where it makes sense

### Phase 2: Lifecycle Fixes (1 agent, straightforward)

5. **Add suspend/resume to HTTP server** -- prevent SSE drops on reset
6. **Add suspend/resume to code-scanner** -- prevent wasteful re-analysis
7. **Add suspend/resume to tailwind-watcher** -- minor
8. **Add halt methods to primer-ctx and orchestrator-sessions**
9. **Fix caddy-proxy resume-key signature**

### Phase 3: Config Validation (1 agent, new infrastructure) -- DONE

10. **Create Malli config schemas** per component -- DONE (`src/seon/system/config.clj`)
11. **Implement assert-key** validation using registered schemas -- DONE (`:seon/component` hierarchy + generic assert-key)
12. **Create component introspection API** -- DONE (`seon.system.config/describe`)

### Phase 4: Agent Control (1 agent, significant)

13. **Add component hierarchy** via `integrant/hierarchy.edn` -- DONE (`resources/integrant/hierarchy.edn`)
14. **Create `seon.system.control`** namespace with `restart!`, `healthy?`, `components`
15. **Wire into agent REPL** so agents can discover and control components programmatically

---

## 10. Summary

The Integrant usage in Seon is fundamentally sound but has grown organically. The main issues are:

1. **Naming inconsistency** -- Component keys do not match their namespaces, making discovery harder for agents and humans. The worst offender is `:seon/graph-db` which is a Datalevin runtime connection, not a graph database.

2. **No config validation** -- Component configs are a free-for-all. `assert-key` with Malli schemas would catch errors early and enable programmatic introspection.

3. **Missing lifecycle methods** -- HTTP server and code-scanner restart unnecessarily on reset. Several components lack halt methods.

4. **No agent control surface** -- Agents can read components from `state/system` but cannot restart individual components, check health, or discover what components accept. A control API with component hierarchies would make the system self-describing and controllable.

The path forward is incremental: rename first (mechanical, low risk), then add lifecycle methods (straightforward), then build config validation and agent control (new infrastructure).
