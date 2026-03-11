# Research: Worktree Code Reloading in Shared JVM

**Date**: 2026-01-04
**Status**: Complete
**Researcher**: Claude Opus 4.5

## Executive Summary

**YES, we can reload code from agent worktrees into the shared JVM**, but with important constraints. The `clj-reload` library bypasses the JVM classpath and loads files directly from specified directories using `Compiler/load`. This enables worktree-based development, but **all agents share a single namespace registry**, meaning only one version of a namespace can exist at a time.

**Recommended Approach**: Option B (Limited Support with Constraints)

---

## Research Questions & Answers

### 1. Can clj-reload load from multiple source directories?

**YES.** Verified via REPL testing.

```clojure
;; Reinitialize clj-reload with worktree path FIRST (priority order)
(reload/init {:dirs ["/path/to/worktree/src"  ; Worktree takes priority
                     "src"                     ; Main repo fallback
                     "env/dev/clj"
                     "test"]
              :no-reload '#{user}})

;; Reload detects and loads files from worktree
(reload/reload {:only :all})
;; => {:loaded [... seon.test.worktree-namespace ...]}

```

**Key Mechanism**: clj-reload uses `Compiler/load` with file content (`slurp`), bypassing the classpath entirely:

```clojure
;; From clj-reload/src/clj_reload/util.clj line 121
(defn ns-load-file [content ns ^File file]
  (let [[_ ext] (re-matches #".*\.([^.]+)" (.getName file))
        path    (-> ns str (str/replace #"\-" "_") (str/replace #"\." "/") (str "." ext))]
    (Compiler/load (StringReader. content) path (.getName file))))

```

### 2. Can we call reload/init with different dirs per agent?

**YES, but with a critical constraint.** clj-reload uses a global `*config*` atom:

```clojure
;; From clj-reload/src/clj_reload/core.clj line 26
(def ^:private ^:dynamic *config*)

;; And line 54
(def ^:private *state
  (atom {}))

```

Each call to `(reload/init ...)` **replaces** the global config. This means:
- Only ONE set of source directories is active at a time
- Switching between agent contexts requires re-initializing
- No concurrent agents with different source paths

### 3. Can we dynamically change the classpath at runtime?

**Partially.** Clojure 1.12+ provides `clojure.repl.deps/add-lib` for adding dependencies, but:
- It adds JARs/directories to the `DynamicClassLoader`
- It does NOT help with `require` finding files (that uses classpath search)
- For clj-reload, classpath modification is irrelevant since it reads files directly

**Conclusion**: Classpath manipulation is a red herring. clj-reload's direct file loading is the solution.

### 4. Can different agents have different versions of the same namespace loaded?

**NO.** This is a fundamental JVM/Clojure constraint.

Clojure's namespace registry is a **static global map**:

```java
// From Clojure source: clojure.lang.Namespace
static ConcurrentHashMap<Symbol, Namespace> namespaces = new ConcurrentHashMap<>();

```

When you load a namespace, it replaces any previous definition. There is no per-thread or per-classloader isolation without loading a completely separate Clojure runtime.

**True isolation would require**:
- Separate ClassLoaders loading their own Clojure JAR
- Communication via reflection or serialization
- Massive memory overhead (~500MB+ per isolated runtime)

### 5. What happens if Agent A and Agent B both modify `seon.trading.signals`?

**Last-write-wins.** When clj-reload reinitializes with a different worktree:
- The namespace gets unloaded (`remove-ns`)
- The new version from the active worktree gets loaded
- Any code holding references to old vars sees the new definitions

**Tested scenario**: When the same namespace exists in both worktree and main repo, clj-reload tracks BOTH files in `:ns-files`:

```clojure
seon.dev.hook {:ns-files #{"/tmp/test-worktree/src/seon/dev/hook.clj"
                          "src/seon/dev/hook.clj"}}

```

On reload, the behavior depends on modification timestamps. This is a potential source of confusion.

---

## REPL Test Results

### Test 1: Loading from Non-Classpath Directory

```bash
# Create test worktree
mkdir -p /tmp/test-worktree/src/seon/test
echo "(ns seon.test.worktree-reload) (def test-value :from-worktree)" > \
     /tmp/test-worktree/src/seon/test/worktree_reload.clj

```

```clojure
;; Initialize with worktree
(reload/init {:dirs ["/tmp/test-worktree/src" "src" "env/dev/clj" "test"]})

;; Force load all (including unloaded namespaces)
(reload/reload {:only :all})
;; => {:loaded [... seon.test.worktree-reload ...]}

;; Verify it loaded
seon.test.worktree-reload/test-value
;; => :from-worktree

```

### Test 2: Modifying and Reloading

```bash
# Update the file
echo "(ns seon.test.worktree-reload) (def test-value :updated) (def new-var 42)" > \
     /tmp/test-worktree/src/seon/test/worktree_reload.clj

```

```clojure
(reload/reload)
;; => {:unloaded [seon.test.worktree-reload], :loaded [seon.test.worktree-reload]}

[seon.test.worktree-reload/test-value seon.test.worktree-reload/new-var]
;; => [:updated 42]

```

### Test 3: Direct require FAILS (Classpath Not Modified)

```clojure
;; After reinitializing clj-reload but BEFORE running reload
(require 'seon.test.worktree-reload)
;; => FileNotFoundException: Could not locate seon/test/worktree_reload...

```

**Conclusion**: clj-reload must explicitly load the namespace; `require` cannot find it.

---

## Proposed Options

### Option A: Full Worktree Reload Support

**Description**: Each agent gets its own clj-reload config pointing to its worktree.

**Implementation**:

```clojure
(defn activate-agent-worktree! [agent-worktree-path]
  (reload/init {:dirs [(str agent-worktree-path "/src")
                       "src"  ; Fallback to main repo
                       "env/dev/clj"
                       "test"]
                :no-reload '#{user}}))

(defn reload-agent-code! []
  (reload/reload))

```

**Pros**:
- Simple implementation
- Agents can test their changes in the shared JVM
- Changes are hot-reloaded instantly

**Cons**:
- Only ONE agent's worktree can be active at a time
- Switching agents requires re-initialization (slow)
- Global state means context switching overhead
- Risk of namespace pollution across agent switches

### Option B: Limited Support with Constraints (RECOMMENDED)

**Description**: Worktree reload works, but with documented constraints:
1. One active agent worktree at a time (enforced by orchestrator)
2. Agents work on isolated namespaces (no overlap)
3. Switch requires explicit handoff

**Implementation**:

```clojure
(defn switch-agent-context! [namespace-sym worktree-path]
  ;; 1. Unload any namespaces the previous agent was working on
  (reload/unload)

  ;; 2. Reinitialize with new worktree
  (reload/init {:dirs [(str worktree-path "/src") "src" "env/dev/clj" "test"]
                :no-reload '#{user}})

  ;; 3. Force reload the agent's namespaces
  (reload/reload {:only (re-pattern (str "^" namespace-sym "\\..*"))}))

```

**Pros**:
- Clear mental model
- Orchestrator controls context
- Works with existing architecture
- Aligns with "one agent per namespace" constraint

**Cons**:
- Cannot have two agents testing simultaneously
- Context switch has ~1-2 second overhead

### Option C: Accept Limitation, Agents Test via Shell

**Description**: Agents cannot use the shared JVM for testing. They run tests via shell commands in their worktree.

**Implementation**:

```clojure
;; Agent's test workflow
(defn run-tests-in-worktree [worktree-path ns-pattern]
  (clojure.java.shell/sh
    "clj" "-M:test" "-m" "kaocha.runner"
    "--focus" ns-pattern
    :dir worktree-path))

```

**Pros**:
- Complete isolation
- No interference between agents
- Simple to implement

**Cons**:
- Slow test feedback (JVM startup per test run)
- No REPL-driven development for agents
- Loses key benefit of shared JVM

---

## Recommendation

**Choose Option B: Limited Support with Constraints**

### Rationale

1. **Aligns with existing constraint**: We already have "one agent per namespace at a time" - this naturally extends to "one agent worktree active at a time"

2. **Preserves REPL workflow**: Agents can still use REPL-driven development, which is a core Clojure advantage

3. **Manageable overhead**: Context switching is rare (agents work for extended periods), so the ~1-2s switch cost is acceptable

4. **Clear semantics**: The orchestrator explicitly manages which agent's code is loaded

### Implementation Plan

Add to **Phase 5: Git Worktree Integration**:

```clojure
;; In seon.agent.reload namespace

(defn activate-agent!
  "Switch shared JVM to load code from agent's worktree.
   Returns function to deactivate."
  [{:seon.agent/keys [namespace worktree-path]}]
  (let [original-dirs (:dirs @(var clj-reload.core/*config*))]
    ;; Reinitialize with agent's worktree
    (reload/init {:dirs [(str worktree-path "/src")
                         "src" "env/dev/clj" "test"]
                  :no-reload '#{user}})
    ;; Return deactivation function
    (fn deactivate! []
      (reload/init {:dirs original-dirs
                    :no-reload '#{user}}))))

(defn reload-agent-namespaces!
  "Reload namespaces for the currently active agent."
  [namespace-sym]
  (reload/reload {:only (re-pattern (str "^" (str namespace-sym) "(\\..*)?$"))}))

```

### Constraints to Document

1. **One active worktree**: Only one agent's code can be loaded at a time
2. **Namespace exclusivity**: Agents must work on non-overlapping namespaces
3. **Orchestrator controls**: Context switching is managed by orchestrator, not agents
4. **Test isolation**: Agents should still run full test suite via shell before merging

---

## Related Files

- `reference-code/clj-reload/src/clj_reload/core.clj` - Main clj-reload implementation
- `reference-code/clj-reload/src/clj_reload/util.clj` - File loading utilities
- `env/dev/clj/user.clj` - Current reload configuration

## Libraries Investigated

| Library | Purpose | Relevant? |
|---------|---------|-----------|
| **clj-reload** | Hot code reloading | YES - core solution |
| **tools.namespace** | Namespace dependency tracking | NO - classpath-based |
| **pomegranate** | Dynamic classpath | NO - deprecated for JDK 17+ |
| **dynapath** | Classpath manipulation | NO - superseded by Clojure 1.12 |
| **clojure.repl.deps** | Runtime dependency adding | NO - for JARs, not source dirs |
