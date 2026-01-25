# Seon: Personal Runtime OS

Transform ml-options-trading into **Seon** - a personal "OS for life" with modular domains (trading, health, finance, tasks, knowledge).

---

## CURRENT STATUS

**Stage 4 COMPLETE**: Core infrastructure added
- `seon.db.factory` - Create domain-specific XTDB nodes
- `seon.trading.core` - Trading domain public API
- Domain registry in `seon.core` (register/unregister/list)
- 185 tests passing, backward compatible

**Next step**: Stage 5 - Instrumentation & Agent Interface (Malli, capabilities)

---

## TECH STACK (from source project)

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

## ORIGINAL PROJECT STRUCTURE

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

## KEY PATTERNS FROM SOURCE

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

## APPLE HEALTH DATA RESEARCH

**Data Access Options:**
1. **Manual XML Export** - Settings > Health > Export (one-time, comprehensive)
2. **Health Auto Export app** - JSON/CSV, incremental sync to iCloud
3. **HealthKit API** - iOS only, no macOS direct access
4. **Direct SQLite** - Complex, undocumented schema

**Recommended approach:**
- Initial bulk: Manual XML export + healthkit-to-sqlite
- Ongoing: Health Auto Export JSON sync

**Health metrics available:**
- HRV (SDNN, RMSSD), heart rate, resting HR
- Blood pressure, SpO2, VO2 max
- Sleep (duration, stages, efficiency)
- Activity (steps, calories, workouts)
- Body (weight, body fat)

**User's health focus:**
- Track HRV (can't easily view in normal apps)
- Correlate with tirzepatide and testosterone
- Generate insights and recommendations

---

## CONTEXT FOR NEXT SESSION

Start fresh session with:

```
Read PLAN.md in ~/src/seon for full context.

Current status: Stage 1 copy complete. Need to:
1. Verify system runs: `clj -M:dev:nrepl` then `(go)` and `(status)`
2. Git init and commit
3. Then proceed to Stage 2 (namespace rename)

Original project at ~/src/ml-options-trading for reference if needed.
We're transforming it into "Seon" - a personal OS with domains (trading, health, etc).
```

## Vision

- **Protocol-based architecture**: Core defines what domains implement
- **Separate XTDB databases per domain**: Each domain gets its own DB node (passed in, not managed)
- **Standard module pattern**: `specs.clj`, `core.clj`, `signals.clj`, `queries.clj`, `tests.clj` (colocated)
- **Domains receive db**: Functions take `db` as parameter - dependency injection, no coupling
- **LLM-agent friendly**: Query functionality, view Malli specs, see examples via tests
- **Malli for all specs**: Data schemas, function contracts, runtime validation with instrumentation

## Key Docs Reference

Agents should read these for context:

| Doc | Path | Purpose |
|-----|------|---------|
| XTDB v2 Reference | `docs/reference/xtdb-v2-reference.md` | XTQL patterns, gotchas |
| XTDB Bulk Loading | `docs/reference/xtdb-bulk-loading.md` | Import/export, compaction |
| Integrant REPL | `docs/reference/integrant-repl-workflow.md` | System lifecycle |
| Malli Instrumentation | `docs/prds/test-coverage-audit/research/malli-instrumentation.md` | Function specs |
| XTDB Multi-DB (Future) | `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` | For later: cross-DB queries |

## Immediate Goal

Copy ml-options-trading, get it running, then incrementally transform via git checkpoints.

---

## Stage 1: Copy & Verify (Commit 1)

**Objective**: Get an exact working copy of ml-options-trading in the new location. This is the foundation - everything must work before we change anything.

**Success criteria**: `(go)` starts system, `(status)` shows healthy XTDB node.

### 1.1 Deep Copy ✓ DONE

Files copied to `~/src/seon/` from `~/src/ml-options-trading`.

**Original commands (for reference):**
```bash
mkdir -p ~/src/seon
cd ~/src/ml-options-trading

# Source and config
cp -R src resources test env bin docs dev deps.edn build.clj tests.edn .gitignore ~/src/seon/

# Editor/tooling configs
cp -R .claude .clj-kondo .lsp .calva ~/src/seon/ 2>/dev/null || true

# Create empty data dirs
mkdir -p ~/src/seon/data/xtdb
```

**NOT copied**: `data/xtdb/` (73GB), `data/*.backup*`, `.cpcache/`, `classes/`, `logs/`, `.nrepl-port`

### 1.2 Verify It Runs

```bash
cd ~/src/seon
clj -M:dev:nrepl
```

```clojure
(go)      ; System starts
(status)  ; XTDB node healthy
```

### 1.3 Git Init & Commit

```bash
git init
git add .
git commit -m "Initial copy of ml-options-trading (unchanged)"
```

**Checkpoint**: Working system, can always `git reset --hard HEAD` to return here.

---

## Stage 2: Rename to Seon (Commit 2)

**Objective**: Rename all namespaces from `ml-options` to `seon`. Trading code becomes `seon.trading.*`. No functional changes - just naming.

**Success criteria**: `(reset)` works, all tests pass with new namespaces.

**Key docs to read**: `docs/reference/integrant-repl-workflow.md`

### 2.1 Namespace Rename

| From | To |
|------|-----|
| `ml-options` | `seon` |
| `ml_options/` (dirs) | `seon/` |
| `:ml-options/*` (integrant) | `:seon/*` |

**Files to change:**
- `src/ml_options/` → `src/seon/`
- All `.clj` files: namespace declarations, requires
- `deps.edn`: paths
- `resources/system.edn`: component keys
- `dev/user.clj`, `env/*/clj/user.clj`

### 2.2 Keep Trading as seon.trading

The trading code becomes `seon.trading.*`:
- `seon.trading.signals` (was `ml-options.dsl.primitives`)
- `seon.trading.analysis` (was `ml-options.agent.analysis`)
- `seon.trading.thetadata` (was `ml-options.data.thetadata`)

### 2.3 Verify & Commit

```clojure
(reset)   ; Reload with new namespaces
(status)  ; Still works
```

```bash
git add .
git commit -m "Rename ml-options → seon"
```

---

## Stage 3: Refactor to Standard Pattern (Commit 3)

**Objective**: Reorganize files into the standard pattern: `specs.clj`, `core.clj`, `signals.clj`, `queries.clj`. This establishes conventions for all future domains.

**Success criteria**: All tests pass, REPL works, file organization follows new pattern.

### 3.1 Target Structure

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

### 3.2 Standard Files Per Domain

| File | Purpose |
|------|---------|
| `specs.clj` | Malli schemas for domain data |
| `core.clj` | Public API, entry points, exposes domain capabilities |
| `signals.clj` | Derived metrics/indicators (composable functions) |
| `queries.clj` | XTDB read/append operations |
| `tests.clj` | Colocated tests - serve as examples for LLM agents |

Optional per domain: `analysis.clj`, `ingest.clj`, data source clients

### 3.3 All Functions Receive `db`

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

### 3.4 Verify & Commit

```bash
git add .
git commit -m "Refactor to standard module pattern (specs/core/signals/queries/tests)"
```

---

## Stage 4: Seon Core & DB Management (Commit 4)

**Objective**: Create seon.core that manages separate XTDB nodes per domain and passes them to domain functions.

**Success criteria**: Each domain can be initialized with its own DB, domains work independently.

### 4.1 DB Node Factory

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

### 4.2 Domain Registry

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

### 4.3 Update system.edn

```clojure
;; Each domain gets its own DB component
:seon.trading/db {:path "data/trading"}
;; Future: :seon.health/db {:path "data/health"}
```

### 4.4 Verify & Commit

```bash
git add .
git commit -m "Add seon.db.factory, domain registry, separate DBs per domain"
```

---

## Stage 5: Instrumentation & Agent Interface (Commit 5)

**Objective**: Enable Malli instrumentation for rapid REPL feedback during development. Create agent-queryable interface so LLMs can discover domain capabilities.

**Success criteria**: `(instrument!)` enables spec validation, `(capabilities)` returns domain info.

**Key docs to read**: `docs/prds/test-coverage-audit/research/malli-instrumentation.md`

### 5.1 Malli Instrumentation

Enable function instrumentation for rapid REPL feedback:

```clojure
;; In dev/user.clj
(require '[malli.instrument :as mi])

(defn instrument! []
  (mi/instrument!))

(defn unstrument! []
  (mi/unstrument!))
```

### 5.2 Agent Query Interface

Each domain's `core.clj` exposes:

```clojure
(defn capabilities []
  "Returns description of domain capabilities for LLM agents."
  {:domain :trading
   :signals (keys trading.signals/registry)
   :specs (keys trading.specs/registry)
   :examples "See seon.trading.tests namespace"})
```

### 5.3 Verify & Commit

```bash
git add .
git commit -m "Add Malli instrumentation, agent query interface"
```

---

## Future: Health Module (After Research)

**Wait until Apple Health data research is complete.**

When ready, create:
```
seon/health/
  core.clj      ; Public API
  specs.clj     ; Health data schemas (HRV, sleep, etc.)
  signals.clj   ; Recovery score, HRV trend, etc.
  queries.clj   ; Health XTDB queries
  ingest.clj    ; Apple Health import
  analysis.clj  ; Health insights for agents
```

Separate database: `data/health/`

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
