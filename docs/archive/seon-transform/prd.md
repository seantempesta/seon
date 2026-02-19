> **Status: ARCHIVED** — Complete — rename done long ago

> **Status: ARCHIVED** — Complete — rename done long ago

# Seon Transformation PRD

## Goal

Transform `ml-options-trading` into **Seon** - a personal operating system with modular domains (trading, health, finance, tasks, knowledge).

## Background

The existing ml-options-trading codebase is a well-structured Clojure/XTDB application for options trading analysis. We're expanding it into a multi-domain personal OS while preserving the trading functionality as the first domain.

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

---

## Tech Stack

- **Clojure 1.12.0** on Java 21+
- **XTDB v2.1.0-rc0** - Bitemporal database
- **Integrant 0.10.0** - Component lifecycle (go/halt/reset)
- **Malli 0.17.0** - Schema validation
- **HTTP Kit 2.9.0-beta2** - HTTP server with SSE
- **Aero 1.1.6** - Config with profiles (dev/test/prod)
- **Timbre 6.5.0** - Logging

**Key aliases in deps.edn:**
- `:dev` - Development with integrant/repl, nREPL
- `:nrepl` - JVM flags for XTDB + nREPL main
- `:test` - Kaocha test runner
- `:run` - Standalone runner

**Start REPL:** `clj -M:dev:nrepl`

---

## Original Project Structure

```
~/src/ml-options-trading/
├── src/ml_options/
│   ├── core.clj              # System entry, -main
│   ├── system.clj            # Integrant component definitions
│   ├── config.clj            # Aero config loader
│   ├── runner.clj            # Standalone runner
│   ├── db/
│   │   ├── node.clj          # XTDB wrapper (query, execute-tx!)
│   │   ├── schema.clj        # Malli schemas (OptionQuote, Greeks)
│   │   ├── queries.clj       # Domain queries
│   │   └── transactions.clj  # TX builders
│   ├── dsl/
│   │   ├── primitives.clj    # IV rank, skew, term structure → signals.clj
│   │   └── executor.clj      # DSL execution
│   ├── agent/
│   │   └── analysis.clj      # analyze-ticker for LLM agents
│   ├── data/
│   │   ├── thetadata.clj     # ThetaData REST client
│   │   ├── ingest.clj        # Data ingestion pipeline
│   │   ├── bulk_load.clj     # Parallel bulk loader
│   │   ├── validation.clj    # Greeks validation
│   │   └── date_utils.clj    # Date handling
│   └── web/
│       ├── server.clj        # HTTP Kit setup
│       ├── routes.clj        # Ring routes
│       ├── handlers.clj      # Request handlers
│       └── sse.clj           # Server-sent events
├── resources/
│   └── system.edn            # Integrant config
├── test/                     # Test files (→ colocated tests.clj)
├── dev/user.clj              # REPL helpers
├── env/{dev,prod,test}/      # Profile-specific configs
└── data/xtdb/                # XTDB storage (73GB, NOT copied)
```

---

## Key Patterns From Source

### 1. XTDB Query Wrapper
```clojure
;; Use ml-options.db.node/query, NOT xt/q
(node/query db '(from :option-greeks [ticker strike iv]))

;; Dynamic values with xt/template
(node/query db (xt/template
  (from :option-greeks [{:ticker ~ticker} strike iv])))
```

### 2. Integrant Lifecycle
```clojure
(go)      ; Start system
(halt)    ; Stop system
(reset)   ; Reload code + restart
(status)  ; Show system status
```

### 3. Data Ingestion Pattern
```
fetch (thetadata.clj) → transform → validate → batch ingest → checkpoint
```

### 4. DSL Primitives (→ signals.clj)
- `iv-rank` - IV percentile vs historical
- `skew-index` - Put/call IV spread
- `term-structure-slope` - Near vs far term IV
- `gamma-rent` - Gamma/theta ratio

### 5. Agent Analysis
```clojure
(analyze-ticker node "SPY" {:as-of #inst "2025-07-15"})
;; Returns: {:signals {...} :recommendation :no-trade :reasoning "..."}
```

---

## Transformation Stages

### Stage 1: Copy & Verify (Commit 1) - DONE

**Objective**: Get an exact working copy of ml-options-trading in the new location.

**Success criteria**: `(go)` starts system, `(status)` shows healthy XTDB node.

- [x] Copy files to `~/src/seon`
- [ ] Verify system starts: `(go)` and `(status)`
- [ ] Git init and initial commit

**NOT copied**: `data/xtdb/` (73GB), `data/*.backup*`, `.cpcache/`, `classes/`, `logs/`, `.nrepl-port`

---

### Stage 2: Rename to Seon (Commit 2)

**Objective**: Rename all namespaces from `ml-options` to `seon`. No functional changes - just naming.

**Success criteria**: `(reset)` works, all tests pass with new namespaces.

**Key docs to read**: `docs/reference/integrant-repl-workflow.md`

#### Namespace Rename Map

| From | To |
|------|-----|
| `ml-options` | `seon` |
| `ml_options/` (dirs) | `seon/` |
| `:ml-options/*` (integrant) | `:seon/*` |

#### Files to Change
- `src/ml_options/` → `src/seon/`
- All `.clj` files: namespace declarations, requires
- `deps.edn`: paths, main-opts
- `resources/system.edn`: component keys
- `dev/user.clj`, `env/*/clj/user.clj`
- `tests.edn`: test paths

#### Keep Trading as seon.trading (Stage 3 prep)
During rename, also rename trading-specific modules:
- `seon.dsl.primitives` → keep as is (will become `seon.trading.signals` in Stage 3)
- `seon.agent.analysis` → keep as is (will become `seon.trading.analysis` in Stage 3)
- `seon.data.thetadata` → keep as is (will become `seon.trading.thetadata` in Stage 3)

---

### Stage 3: Refactor to Standard Pattern (Commit 3)

**Objective**: Reorganize files into the standard pattern: `specs.clj`, `core.clj`, `signals.clj`, `queries.clj`. This establishes conventions for all future domains.

**Success criteria**: All tests pass, REPL works, file organization follows new pattern.

#### Target Structure

```
src/seon/
  core.clj              ; System entry, integrant lifecycle
  system.clj            ; Component definitions
  config.clj            ; Aero config loading

  db/
    core.clj            ; XTDB wrapper, public query API
    specs.clj           ; DB-related Malli specs

  trading/
    core.clj            ; Public API for trading domain
    specs.clj           ; Trading data specs (OptionQuote, Greeks, etc.)
    signals.clj         ; IV rank, skew, term structure (was dsl/primitives)
    queries.clj         ; Trading-specific XTDB queries
    tests.clj           ; Colocated tests (examples for agents)
    analysis.clj        ; Agent-level analysis
    thetadata.clj       ; ThetaData API client
    ingest.clj          ; Data ingestion pipeline

data/
  xtdb/                 ; Current XTDB storage (git-ignored)
  trading/              ; Trading domain XTDB (future, git-ignored)
  health/               ; Health domain XTDB (future, git-ignored)
```

#### Standard Files Per Domain

| File | Purpose |
|------|---------|
| `specs.clj` | Malli schemas for domain data |
| `core.clj` | Public API, entry points, exposes domain capabilities |
| `signals.clj` | Derived metrics/indicators (composable functions) |
| `queries.clj` | XTDB read/append operations |
| `tests.clj` | Colocated tests - serve as examples for LLM agents |

Optional per domain: `analysis.clj`, `ingest.clj`, data source clients

#### All Functions Receive `db`

Domain functions don't manage their own database - they receive it:

```clojure
;; In seon.trading.queries
(defn options-for-ticker [db ticker]
  (node/query db ...))

;; In seon.trading.signals
(defn iv-rank [db ticker opts]
  (let [historical (queries/historical-ivs db ticker (:lookback opts))]
    ...))
```

Seon core is responsible for creating/managing DB nodes and passing them to domains.

---

### Stage 4: Seon Core & DB Management (Commit 4)

**Objective**: Create seon.core that manages separate XTDB nodes per domain and passes them to domain functions.

**Success criteria**: Each domain can be initialized with its own DB, domains work independently.

#### DB Node Factory

```clojure
;; seon/db/factory.clj
(ns seon.db.factory
  "Creates XTDB nodes for domains.")

(defn create-node
  "Create an XTDB node for a domain.

  Args:
    domain-id - keyword like :trading, :health
    opts - {:path \"data/trading\"} or {:in-memory? true}"
  [domain-id opts]
  (if (:in-memory? opts)
    (xtn/start-node)
    (xtn/start-node {:log-dir (str (:path opts) "/log")
                     :storage-dir (str (:path opts) "/storage")})))
```

#### Domain Registry

```clojure
;; seon/core.clj
(defonce domains (atom {}))

(defn register-domain!
  "Register a domain with its DB node."
  [domain-id db-node]
  (swap! domains assoc domain-id {:db db-node}))

(defn domain-db
  "Get the DB node for a domain."
  [domain-id]
  (get-in @domains [domain-id :db]))
```

#### Update system.edn

```clojure
;; Each domain gets its own DB component
:seon.trading/db {:path "data/trading"}
;; Future: :seon.health/db {:path "data/health"}
```

---

### Stage 5: Instrumentation & Agent Interface (Commit 5)

**Objective**: Enable Malli instrumentation for rapid REPL feedback during development. Create agent-queryable interface so LLMs can discover domain capabilities.

**Success criteria**: `(instrument!)` enables spec validation, `(capabilities)` returns domain info.

**Key docs to read**: `docs/prds/test-coverage-audit/research/malli-instrumentation.md`

#### Malli Instrumentation

```clojure
;; In dev/user.clj
(require '[malli.instrument :as mi])

(defn instrument! []
  (mi/instrument!))

(defn unstrument! []
  (mi/unstrument!))
```

#### Agent Query Interface

Each domain's `core.clj` exposes:

```clojure
(defn capabilities []
  "Returns description of domain capabilities for LLM agents."
  {:domain :trading
   :signals (keys trading.signals/registry)
   :specs (keys trading.specs/registry)
   :examples "See seon.trading.tests namespace"})
```

---

## Key Files Reference

| Current Path | Target Path | Notes |
|--------------|-------------|-------|
| `src/ml_options/core.clj` | `src/seon/core.clj` | System entry |
| `src/ml_options/dsl/primitives.clj` | `src/seon/trading/signals.clj` | Renamed |
| `src/ml_options/agent/analysis.clj` | `src/seon/trading/analysis.clj` | Moved |
| `src/ml_options/db/schema.clj` | `src/seon/trading/specs.clj` | Renamed |
| `src/ml_options/db/queries.clj` | `src/seon/trading/queries.clj` | Moved |
| `src/ml_options/data/thetadata.clj` | `src/seon/trading/thetadata.clj` | Moved |
| `test/ml_options/**_test.clj` | `src/seon/trading/tests.clj` | Colocated |
| (new) | `src/seon/db/factory.clj` | DB node creation |

---

## Verification Commands

After each stage:

```bash
# Compile check
clj -M:dev -e "(require 'seon.core)"

# REPL check
clj -M:dev:nrepl
(go)
(status)

# Run tests
clj -M:test
```

---

## Rollback Points

| Commit | State | Rollback Command |
|--------|-------|------------------|
| 1 | Exact copy, ml-options namespaces | `git reset --hard HEAD~4` |
| 2 | Renamed to seon | `git reset --hard HEAD~3` |
| 3 | Standard file pattern | `git reset --hard HEAD~2` |
| 4 | Domain protocol, separate DBs | `git reset --hard HEAD~1` |
| 5 | Instrumentation, agent interface | Current |

---

## Success Criteria

1. System starts and all existing tests pass after each stage
2. Trading functionality preserved
3. Clear separation between core and domains
4. Ready for new domains (health, finance, etc.)

## Constraints

- Preserve all existing functionality
- One commit per stage (rollback points)
- No parallel implementations
- Tests must pass at each stage

## Reference

- Original project: `~/src/ml-options-trading`
- Transformation plan: `PLAN.md`
- XTDB v2 Reference: `docs/reference/xtdb-v2-reference.md`
- Integrant REPL: `docs/reference/integrant-repl-workflow.md`
- Malli Instrumentation: `docs/prds/test-coverage-audit/research/malli-instrumentation.md`
