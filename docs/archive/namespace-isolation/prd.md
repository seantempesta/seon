> **Status: ARCHIVED** — Dead — depended on deleted reactive.instance

> **Status: ARCHIVED** — Dead — depended on deleted reactive.instance

# PRD: Namespace Instance Isolation

**Status:** Research
**Priority:** High
**Branch:** feature/namespace-isolation

---

## Goals

1. **Code Isolation** - Multiple instances from a single base namespace, each with independent function definitions
2. **Code Inheritance** - Instances inherit base namespace functions but can override independently
3. **Ephemeral by Design** - Instances disappear on restart; base code remains unchanged
4. **Graduation Path** - Sandbox (restricted) → Trusted (full JVM) → Production (separate process)

---

## Connection to Vision

This PRD advances **VISION.md Layer 2: Agent Isolation**:

> **What's next:**
> - **Namespace ownership model** - Declare which agent owns which namespace
> - **Cross-agent communication** - Via schemas and database, not shared state
> - **Conflict detection** - Alert when agents touch the same code

---

## What We've Already Built

### State Isolation (Done)

`seon.web.reactive.instance` provides **state isolation**:
- Each browser tab / session gets its own `*ctx*` atom
- State changes in one instance don't affect others
- SSE pushes updates to the right clients

### Code Isolation (This PRD)

State isolation doesn't help when two agents both `(defn foo [] ...)` - they overwrite each other's definitions because they share the same JVM namespace.

**Gap:** We need instances where agents can modify functions independently.

---

## Problem Statement

Currently, all nREPL sessions share the same JVM and code definitions. When two agents both `(defn foo [] ...)`, they overwrite each other. The `*ctx*` atom isolation we built for reactive UI doesn't help with **code isolation**.

We need:
- Agents to modify functions without affecting each other
- Safe sandboxing for untrusted/experimental code
- A path to "graduate" proven code to full access

**Impact:** Without this, agents can't safely experiment with code changes in parallel.

---

## Prior Research

### Context Injection Findings

From `docs/prds/dynamic-context/research-findings.md`:
- Claude Code processes APPEND context, they don't REPLACE
- System message injection doesn't work
- Turn limit continuation DOES work

This informs how we might communicate with isolated instances - we can't dynamically update agent context, but we can use MCP tools to query instance state.

---

## Current Architecture

| Component | Isolation Level | Same JVM? |
|-----------|-----------------|-----------|
| Claude Code process | Full (separate OS process) | No |
| nREPL servers | Separate servers, same JVM | Yes |
| `*ctx*` atoms | Per-server via middleware | Yes |
| Database connections | Per-namespace XTDB connection | Yes |
| **Code (`defn`, `def`)** | **SHARED across all nREPLs** | Yes |

---

## Three Approaches to Research

| Approach | Isolation | Java Interop | Memory | Use Case |
|----------|-----------|--------------|--------|----------|
| **Sci** | Strong (context) | Limited (extensible) | ~10MB/ctx | Sandboxed prototyping |
| **Dynamic NS** | Weak (same JVM) | Full | ~1MB/ns | Trusted code, fast iteration |
| **Lightweight JVM** | Full (process) | Full | ~200-500MB | Untrusted, production |

**Graduation path:** Sci sandbox → Dynamic NS (trusted) → Separate JVM (production)

---

## Phase 1: Sci Deep Dive

### Objective

Understand Sci's isolation model, Java interop capabilities, and performance characteristics.

### Tasks

1. Add Sci as git submodule: `reference-code/sci`
2. Read key source files to understand architecture
3. Create `src/seon/experimental/sci_exploration.clj`
4. Test context forking and isolation
5. Test Java interop limits and extensibility
6. Measure performance vs native eval
7. Document findings in `research/sci-findings.md`

### Key Questions

- How does `sci/fork` work? Is it copy-on-write?
- Can we register arbitrary Java classes per-context?
- Can we add capabilities to an existing context dynamically?
- What's the performance overhead vs native Clojure eval?

### Key Files to Study

```
reference-code/sci/src/sci/
├── core.cljc          ; Public API (init, fork, eval-string*)
├── impl/vars.cljc     ; How Sci vars work (different from Clojure)
├── impl/interop.cljc  ; Java interop implementation
└── impl/namespaces.cljc ; Namespace handling

```

### Experiment Code

```clojure
(ns seon.experimental.sci-exploration
  (:require [sci.core :as sci]))

;; 1. Create base context
(def base-ctx
  (sci/init {:namespaces {'app {'greet (fn [n] (str "Hello, " n))
                                'process (fn [x] (* x 2))}}}))

;; 2. Fork for two instances
(def ctx-1 (sci/fork base-ctx))
(def ctx-2 (sci/fork base-ctx))

;; 3. Test isolation
(sci/eval-string* ctx-1 "(def counter 100)")
(sci/eval-string* ctx-1 "(defn greet [n] (str \"Hi \" n))")

;; Does ctx-2 see counter? (should be no)
(sci/eval-string* ctx-2 "counter") ; => should error

;; Does ctx-2 have original greet? (should be yes)
(sci/eval-string* ctx-2 "(greet \"world\")") ; => "Hello, world"

;; 4. Test Java interop limits
(sci/eval-string* ctx-1 "(java.util.Date.)") ; works if in :classes
(sci/eval-string* ctx-1 "(System/exit 0)") ; should fail

```

### Deliverables

- [ ] `reference-code/sci` submodule added
- [ ] `src/seon/experimental/sci_exploration.clj` with working experiments
- [ ] `docs/prds/namespace-isolation/research/sci-findings.md`

---

## Phase 2: Dynamic Namespace Instances

### Objective

Test Clojure's native namespace machinery for instance isolation.

### Tasks

1. Create `src/seon/experimental/ns_instance.clj`
2. Implement `create-instance-ns` with inheritance
3. Test var isolation between instances
4. Test macro inheritance behavior
5. Handle the `::keyword` problem
6. Document findings in `research/dynamic-ns-findings.md`

### The `::keyword` Problem

When in `seon.email.a1b2`, writing `::foo` expands to `:seon.email.a1b2/foo`.
But schemas expect `:seon.email/foo` (the canonical namespace).

**Solutions:**
1. Convention - always use explicit keywords `:seon.email/foo`
2. Namespace alias - `(alias 'email 'seon.email)` then `::email/foo`
3. Code rewriting - transform `::foo` before eval (complex, last resort)

### Core Functions

```clojure
(defn create-instance-ns
  "Create an instance namespace that inherits from base."
  [base-ns instance-id]
  (let [instance-sym (symbol (str base-ns "." instance-id))
        instance-ns (create-ns instance-sym)]
    ;; Copy public vars from base
    (doseq [[sym var] (ns-publics base-ns)]
      (intern instance-ns sym @var))
    ;; Set up alias for :: keywords
    (binding [*ns* instance-ns]
      (alias (symbol (name base-ns)) base-ns))
    instance-ns))

(defn instance-eval
  "Eval code in an instance namespace."
  [instance-ns code]
  (binding [*ns* instance-ns]
    (eval (read-string code))))

(defn destroy-instance-ns
  "Remove an instance namespace."
  [instance-ns]
  (remove-ns (ns-name instance-ns)))

```

### Verification Tests

1. **Isolation test:** Two instances, define same var differently, verify no cross-pollution
2. **Inheritance test:** Base functions available in both instances
3. **Override test:** Override in one instance, other keeps original
4. **Base unchanged:** Modifications don't affect base namespace
5. **Cleanup test:** `destroy-instance-ns` removes all vars
6. **Macro test:** Inherited macros expand correctly

### Deliverables

- [ ] `src/seon/experimental/ns_instance.clj`
- [ ] `test/seon/experimental/ns_instance_test.clj`
- [ ] `docs/prds/namespace-isolation/research/dynamic-ns-findings.md`

---

## Phase 3: Lightweight JVM Measurement

### Objective

Measure actual memory footprint for minimal Seon instances.

### Tasks

1. Create minimal deps.edn for seon-lite
2. Measure memory: bare Clojure, +Datalevin, +http-kit, +Malli
3. Test startup time with various JVM flags
4. Document findings in `research/jvm-footprint-findings.md`

### Memory Targets

| Component | Target | Notes |
|-----------|--------|-------|
| JVM base | 50-100MB | With `-Xms64m -Xmx256m` |
| Clojure core | ~30MB | Loaded lazily |
| Datalevin | ~20MB | Much lighter than XTDB |
| http-kit | ~5MB | Minimal HTTP server |
| Malli | ~5MB | Schema validation |
| **Total** | **~150-200MB** | Before data |

### JVM Tuning Flags

```bash
java -Xms64m -Xmx256m \
     -XX:+UseSerialGC \
     -XX:MaxMetaspaceSize=128m \
     -Dclojure.spec.skip-macros=true \
     -jar seon-lite.jar

```

### Measurement Script

```bash
# Start minimal Clojure REPL, measure RSS
clojure -J-Xms64m -J-Xmx256m -M -e "(println \"started\")" &
PID=$!
sleep 5
ps -o rss= -p $PID  # RSS in KB

```

### Deliverables

- [ ] `src/seon/experimental/jvm_footprint.clj` - measurement code
- [ ] `docs/prds/namespace-isolation/research/jvm-footprint-findings.md`

---

## Phase 4: Integration Design

### Objective

Design how instance isolation integrates with existing systems.

### Integration Points

1. **MCP Sessions** - Each session gets an instance namespace
2. **Reactive UI** - Each browser tab's instance ID maps to a namespace
3. **Agent System** - Agents work in isolated instances
4. **Graduation** - Criteria and mechanism for promoting code

### Graduation Criteria (Draft)

- All tests pass for N consecutive runs
- No security violations detected
- Code review approved (human or AI)
- Performance within acceptable bounds

### Deliverables

- [ ] `docs/prds/namespace-isolation/research/integration-design.md`

---

## Dependencies

```clojure
;; Add to deps.edn for Sci experiments:
org.babashka/sci {:mvn/version "0.8.43"}

```

No other new dependencies - uses Clojure core functions.

---

## Constraints

- Must be REPL-friendly (test everything interactively)
- Must not break existing functionality
- Ephemeral instances must not persist across restarts
- Base namespace code must never be modified by instances

---

## Success Criteria

1. **Sci:** Understand isolation model, document Java interop limits
2. **Dynamic NS:** Two instances can modify same function independently
3. **JVM Footprint:** Know actual memory cost per isolated instance
4. **Integration:** Clear design for connecting to existing systems

---

## Risks

| Risk | Mitigation |
|------|------------|
| Shared JVM dangers | Document clearly; use process isolation for untrusted code |
| `::keyword` confusion | Establish convention early; document in findings |
| Sci performance | Measure; may be acceptable for sandboxing |
| Scope creep | Strict phase boundaries; research only, no production code |

---

---

## Files Agents Must Study

Before starting any phase, agents should read these files to understand existing work:

### Core Context Files

| File | Why |
|------|-----|
| `VISION.md` | Overall architecture vision, especially Layer 2 (Agent Isolation) |
| `docs/prds/namespace-ui/prd.md` | Session model and introspection vision |
| `docs/prds/dynamic-context/research-findings.md` | Prior research on context injection |

### Existing Instance Implementation

| File | Why |
|------|-----|
| `src/seon/web/reactive/instance.clj` | Current STATE isolation - study patterns |
| `src/seon/web/reactive/demo.clj` | Example namespace using instances |
| `src/seon/web/reactive/transform.clj` | How hiccup is transformed with instance context |

### Experimental Code

| File | Why |
|------|-----|
| `src/seon/experimental/context_injection.clj` | Prior experiments (can be deleted) |

---

## Work Phases for Agents

### Agent 1: Sci Research

**Files to include in prompt:**
- `docs/prds/namespace-isolation/prd.md` (this PRD)
- `src/seon/web/reactive/instance.clj` (study existing patterns)

**Tasks:**
- Add sci submodule: `git submodule add https://github.com/babashka/sci reference-code/sci`
- Read key source files in sci to understand architecture
- Create `src/seon/experimental/sci_exploration.clj`
- Document findings in `docs/prds/namespace-isolation/research/sci-findings.md`

### Agent 2: Dynamic NS Research

**Files to include in prompt:**
- `docs/prds/namespace-isolation/prd.md` (this PRD)
- `src/seon/web/reactive/instance.clj` (study patterns for registry, lifecycle)
- `src/seon/web/reactive/demo.clj` (example of base namespace)

**Tasks:**
- Create `src/seon/experimental/ns_instance.clj`
- Write isolation tests in `test/seon/experimental/ns_instance_test.clj`
- Investigate macro behavior and `::keyword` problem
- Document findings in `docs/prds/namespace-isolation/research/dynamic-ns-findings.md`

### Agent 3: JVM Measurement

**Files to include in prompt:**
- `docs/prds/namespace-isolation/prd.md` (this PRD)

**Tasks:**
- Create measurement scripts
- Test memory with: bare Clojure, +Datalevin, +http-kit, +Malli
- Measure startup times with various JVM flags
- Document in `docs/prds/namespace-isolation/research/jvm-footprint-findings.md`

### Agent 4: Integration Design (after phases 1-3)

**Files to include in prompt:**
- All three research findings documents
- `src/seon/web/reactive/instance.clj`
- `VISION.md`

**Tasks:**
- Synthesize findings from all phases
- Design how CODE isolation integrates with existing STATE isolation
- Design graduation criteria and mechanism
- Write `docs/prds/namespace-isolation/research/integration-design.md`
