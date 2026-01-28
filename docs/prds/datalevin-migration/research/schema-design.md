# Datalevin Schema Design

**Date:** 2026-01-28
**Status:** Draft

This document defines the Datalevin schema for all Seon entities, including attribute types, cardinality, uniqueness constraints, and indexes.

---

## Summary of Entities

| Entity Type | Table (XTDB) | Purpose | Temporal Strategy |
|-------------|--------------|---------|-------------------|
| AI Session | `ai_sessions` | Track AI agent sessions | Explicit `:ended-at` |
| AI Message | `ai_messages` | Store conversation messages | Explicit `:timestamp` |
| Context Snapshot | `ctx_snapshots` | Agent context persistence | Append-only with `:recorded-at` |
| Primer Session | `primer_sessions` | Multi-session context | Append-only with `:checkpointed-at` |
| Orchestrator Session | `sessions` | Session lifecycle | Explicit `:started-at`, `:stopped-at` |
| Option Greeks | `option_greeks` | Options pricing data | Explicit `:recorded-at` |
| Ingestion State | `ingestion_state` | Data load progress | Explicit `:updated-at` |
| Bulk Progress | `bulk_progress` | Day-level load tracking | Explicit `:completed-at` |
| Edit Event | `edit_event` | Dev hook: file edits | Explicit `:created-at` |
| Review Event | `review_event` | Dev hook: AI reviews | Explicit `:created-at` |
| Todo Event | `todo_event` | Agent todo tracking | Explicit `:created-at` |

---

## Datalevin Schema Definition

### Value Type Reference

| Type | Datalevin | Description |
|------|-----------|-------------|
| String | `:db.type/string` | UTF-8 string |
| Long | `:db.type/long` | 64-bit integer |
| Double | `:db.type/double` | Double-precision float |
| Boolean | `:db.type/boolean` | true/false |
| Instant | `:db.type/instant` | java.util.Date |
| UUID | `:db.type/uuid` | java.util.UUID |
| Keyword | `:db.type/keyword` | Clojure keyword |
| Symbol | `:db.type/symbol` | Clojure symbol |
| Ref | `:db.type/ref` | Entity reference |
| Bytes | `:db.type/bytes` | Byte array |

---

## 1. AI Domain

### 1.1 AI Session

Tracks AI agent sessions from start to completion.

```clojure
{;; Identity
 :ai.session/id            {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "Unique session ID (e.g., 'ses-abc123')"}

 ;; Core attributes
 :ai.session/type          {:db/valueType   :db.type/keyword
                            :db/doc         "Entity type discriminator [:session]"}

 :ai.session/status        {:db/valueType   :db.type/keyword
                            :db/index       true
                            :db/doc         "Session status [:active :completed :failed :interrupted]"}

 :ai.session/namespace     {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Clojure namespace (e.g., 'seon.trading')"}

 :ai.session/prompt        {:db/valueType   :db.type/string
                            :db/doc         "Initial prompt that started the session"}

 :ai.session/agent-session-id {:db/valueType :db.type/string
                               :db/index     true
                               :db/doc       "4-char hex Seon session ID for log files"}

 ;; Timestamps
 :ai.session/started-at    {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When session started"}

 :ai.session/ended-at      {:db/valueType   :db.type/instant
                            :db/doc         "When session ended"}

 ;; Usage tracking
 :ai.session/input-tokens  {:db/valueType   :db.type/long
                            :db/doc         "Total input tokens used"}

 :ai.session/output-tokens {:db/valueType   :db.type/long
                            :db/doc         "Total output tokens used"}

 :ai.session/cost-usd      {:db/valueType   :db.type/double
                            :db/doc         "Total cost in USD"}

 ;; Error handling
 :ai.session/error         {:db/valueType   :db.type/string
                            :db/doc         "EDN-encoded error details"}}
```

### 1.2 AI Message

Individual messages within a session.

```clojure
{;; Identity
 :ai.message/id            {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "Unique message ID (e.g., 'msg-xyz789')"}

 ;; Core attributes
 :ai.message/type          {:db/valueType   :db.type/keyword
                            :db/doc         "Entity type discriminator [:message]"}

 :ai.message/session-id    {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Parent session reference"}

 :ai.message/role          {:db/valueType   :db.type/string
                            :db/doc         "Message role ['user' 'assistant' 'system']"}

 :ai.message/content       {:db/valueType   :db.type/string
                            :db/doc         "Message content (EDN for structured)"}

 :ai.message/timestamp     {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When message was created"}

 ;; Usage tracking
 :ai.message/input-tokens  {:db/valueType   :db.type/long
                            :db/doc         "Input tokens for this message"}

 :ai.message/output-tokens {:db/valueType   :db.type/long
                            :db/doc         "Output tokens for this message"}

 ;; Claude-specific
 :ai.message/cache-read-tokens     {:db/valueType :db.type/long
                                    :db/doc       "Cache read tokens (Claude)"}

 :ai.message/cache-creation-tokens {:db/valueType :db.type/long
                                    :db/doc       "Cache creation tokens (Claude)"}

 ;; Tool interaction
 :ai.message/tool-calls    {:db/valueType   :db.type/string
                            :db/doc         "EDN-encoded tool calls"}

 :ai.message/tool-results  {:db/valueType   :db.type/string
                            :db/doc         "EDN-encoded tool results"}

 :ai.message/provider      {:db/valueType   :db.type/keyword
                            :db/doc         "AI provider [:claude :gemini :openai]"}}
```

---

## 2. Agent Context Domain

### 2.1 Context Snapshots (Append-Only)

Agent context with time-travel support via append-only snapshots.

```clojure
{;; Identity - UUID for each snapshot (NOT unique identity, append-only)
 :ctx/id                   {:db/valueType   :db.type/uuid
                            :db/doc         "Snapshot ID (UUID)"}

 ;; Lookup attributes
 :ctx/namespace            {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Agent namespace (e.g., 'seon.trading')"}

 :ctx/recorded-at          {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When this snapshot was recorded"}

 ;; State
 :ctx/state                {:db/valueType   :db.type/string
                            :db/doc         "EDN-serialized context state"}}
```

**Query pattern for point-in-time:**
```clojure
(d/q '[:find (pull ?e [*]) .
       :in $ ?ns ?as-of
       :where
       [?e :ctx/namespace ?ns]
       [?e :ctx/recorded-at ?t]
       [(<= ?t ?as-of)]
       (max ?t)]
     db "seon.trading" #inst "2026-01-28T10:00:00Z")
```

### 2.2 Primer Sessions (Append-Only)

Multi-session context with checkpointing.

```clojure
{;; Identity
 :primer.session/id        {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Session ID"}

 :primer.session/checkpointed-at {:db/valueType :db.type/instant
                                  :db/index     true
                                  :db/doc       "When checkpoint was created"}

 :primer.session/created-at {:db/valueType  :db.type/instant
                             :db/doc        "When session was first created"}

 ;; State - stored as EDN string for flexibility
 :primer.session/state     {:db/valueType   :db.type/string
                            :db/doc         "EDN-serialized session state"}}
```

---

## 3. Orchestrator Domain

### 3.1 Sessions

Orchestrator session lifecycle tracking.

```clojure
{;; Identity
 :orch.session/id          {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "4-char hex session ID"}

 ;; Core attributes
 :orch.session/namespace   {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Agent namespace symbol as string"}

 :orch.session/status      {:db/valueType   :db.type/keyword
                            :db/index       true
                            :db/doc         "Session status [:running :stopped :error]"}

 :orch.session/nrepl-port  {:db/valueType   :db.type/long
                            :db/doc         "nREPL port for this session"}

 :orch.session/db-name     {:db/valueType   :db.type/string
                            :db/doc         "Database name"}

 ;; Timestamps
 :orch.session/started-at  {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When session started"}

 :orch.session/stopped-at  {:db/valueType   :db.type/instant
                            :db/doc         "When session stopped"}}
```

---

## 4. Trading Domain

### 4.1 Option Greeks

Options pricing data with historical tracking.

```clojure
{;; Identity - deterministic for deduplication
 :quote/id                 {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "Deterministic ID: '{OCC_SYMBOL}-{ISO_TIMESTAMP}'"}

 ;; Asset identification
 :asset/ticker             {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Stock ticker symbol (e.g., 'SPY')"}

 ;; Option details
 :option/id                {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "OCC symbol (e.g., 'AAPL230616C00150000')"}

 :option/strike            {:db/valueType   :db.type/double
                            :db/doc         "Strike price"}

 :option/type              {:db/valueType   :db.type/keyword
                            :db/doc         "Option type [:call :put]"}

 :option/expiry            {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "Option expiration date"}

 ;; Quote data
 :quote/bid                {:db/valueType   :db.type/double
                            :db/doc         "Bid price"}

 :quote/ask                {:db/valueType   :db.type/double
                            :db/doc         "Ask price"}

 :quote/iv                 {:db/valueType   :db.type/double
                            :db/doc         "Implied volatility"}

 :quote/recorded-at        {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When quote was recorded (replaces _valid_from)"}

 ;; Greeks
 :greeks/delta             {:db/valueType   :db.type/double
                            :db/doc         "Delta (-1 to 1)"}

 :greeks/gamma             {:db/valueType   :db.type/double
                            :db/doc         "Gamma (non-negative)"}

 :greeks/vega              {:db/valueType   :db.type/double
                            :db/doc         "Vega (non-negative)"}

 :greeks/theta             {:db/valueType   :db.type/double
                            :db/doc         "Theta (typically negative)"}

 ;; Market data
 :market/volume            {:db/valueType   :db.type/long
                            :db/doc         "Trading volume"}

 :market/aggressor         {:db/valueType   :db.type/keyword
                            :db/doc         "Trade aggressor [:buy :sell]"}}
```

**Query pattern for backtesting (data as of time T):**
```clojure
(d/q '[:find ?iv ?recorded-at
       :in $ ?ticker ?as-of
       :where
       [?e :asset/ticker ?ticker]
       [?e :quote/iv ?iv]
       [?e :quote/recorded-at ?t]
       [(<= ?t ?as-of)]]
     db "SPY" #inst "2025-07-15T00:00:00Z")
```

### 4.2 Ingestion State

Track data loading progress for resumable imports.

```clojure
{;; Identity - deterministic by symbol
 :ingestion/id             {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "'ingestion-state-{SYMBOL}'"}

 :ingestion/symbol         {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Stock symbol"}

 :ingestion/status         {:db/valueType   :db.type/keyword
                            :db/index       true
                            :db/doc         "Status [:in-progress :complete :failed]"}

 :ingestion/start-date     {:db/valueType   :db.type/instant
                            :db/doc         "When ingestion started"}

 :ingestion/last-date      {:db/valueType   :db.type/instant
                            :db/doc         "Last successfully ingested date"}

 :ingestion/records-count  {:db/valueType   :db.type/long
                            :db/doc         "Total records ingested"}

 :ingestion/error          {:db/valueType   :db.type/string
                            :db/doc         "Error message if failed"}

 :ingestion/updated-at     {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "Last update timestamp"}}
```

### 4.3 Bulk Progress

Fine-grained progress tracking per trading day.

```clojure
{;; Identity - deterministic by symbol and date
 :progress/id              {:db/valueType   :db.type/string
                            :db/unique      :db.unique/identity
                            :db/doc         "'progress-{SYMBOL}-{DATE}'"}

 :progress/symbol          {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Stock symbol"}

 :progress/date            {:db/valueType   :db.type/instant
                            :db/doc         "Trading date (as instant)"}

 :progress/records         {:db/valueType   :db.type/long
                            :db/doc         "Records ingested for this date"}

 :progress/completed-at    {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When this date was completed"}}
```

---

## 5. Dev Hook Domain

### 5.1 Edit Event

Track file edits with test results and decisions.

```clojure
{;; Identity
 :edit/id                  {:db/valueType   :db.type/uuid
                            :db/unique      :db.unique/identity
                            :db/doc         "Edit event UUID"}

 :edit/entity-type         {:db/valueType   :db.type/keyword
                            :db/doc         "Entity type [:edit-event]"}

 :edit/file                {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Absolute file path"}

 :edit/namespace           {:db/valueType   :db.type/keyword
                            :db/doc         "Namespace keyword"}

 :edit/content-hash        {:db/valueType   :db.type/string
                            :db/doc         "SHA256 hash of file content"}

 :edit/created-at          {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When edit occurred (replaces _valid_from)"}

 ;; Test results (EDN-encoded maps)
 :edit/unit-test-result    {:db/valueType   :db.type/string
                            :db/doc         "EDN: {:success bool :test-count int ...}"}

 :edit/gen-test-result     {:db/valueType   :db.type/string
                            :db/doc         "EDN: {:success bool :test-count int ...}"}

 ;; Hook decision
 :edit/decision            {:db/valueType   :db.type/keyword
                            :db/index       true
                            :db/doc         "Decision [:continue :block]"}

 :edit/reason              {:db/valueType   :db.type/string
                            :db/doc         "Reason for decision"}

 :edit/feedback            {:db/valueType   :db.type/string
                            :db/doc         "EDN: vector of feedback messages"}}
```

### 5.2 Review Event

Track Gemini code reviews for training data.

```clojure
{;; Identity
 :review/id                {:db/valueType   :db.type/uuid
                            :db/unique      :db.unique/identity
                            :db/doc         "Review event UUID"}

 :review/entity-type       {:db/valueType   :db.type/keyword
                            :db/doc         "Entity type [:review-event]"}

 :review/files             {:db/valueType   :db.type/string
                            :db/doc         "EDN: set of reviewed file paths"}

 :review/edit-count        {:db/valueType   :db.type/long
                            :db/doc         "Number of edits in this review"}

 :review/created-at        {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When review occurred (replaces _valid_from)"}

 ;; Gemini interaction (for LLM training)
 :review/gemini-prompt     {:db/valueType   :db.type/string
                            :db/doc         "Prompt sent to Gemini"}

 :review/gemini-response   {:db/valueType   :db.type/string
                            :db/doc         "Response from Gemini"}

 :review/gemini-system-instruction {:db/valueType :db.type/string
                                    :db/doc       "System instruction used"}

 :review/gemini-code       {:db/valueType   :db.type/string
                            :db/doc         "Code that was reviewed"}

 :review/gemini-tokens     {:db/valueType   :db.type/string
                            :db/doc         "EDN: {:prompt N :response N :cached N}"}}
```

### 5.3 Todo Event

Track agent todo lists for observability.

```clojure
{;; Identity
 :todo/id                  {:db/valueType   :db.type/uuid
                            :db/unique      :db.unique/identity
                            :db/doc         "Todo event UUID"}

 :todo/entity-type         {:db/valueType   :db.type/keyword
                            :db/doc         "Entity type [:todo-event]"}

 :todo/session-id          {:db/valueType   :db.type/string
                            :db/index       true
                            :db/doc         "Agent session ID"}

 :todo/todos               {:db/valueType   :db.type/string
                            :db/doc         "EDN: [{:content str :status str :activeForm str}]"}

 :todo/created-at          {:db/valueType   :db.type/instant
                            :db/index       true
                            :db/doc         "When snapshot was taken (replaces _valid_from)"}}
```

---

## Combined Schema

```clojure
(def seon-schema
  "Complete Datalevin schema for Seon entities."
  (merge
   ;; AI Domain
   {:ai.session/id            {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :ai.session/type          {:db/valueType :db.type/keyword}
    :ai.session/status        {:db/valueType :db.type/keyword :db/index true}
    :ai.session/namespace     {:db/valueType :db.type/string :db/index true}
    :ai.session/prompt        {:db/valueType :db.type/string}
    :ai.session/agent-session-id {:db/valueType :db.type/string :db/index true}
    :ai.session/started-at    {:db/valueType :db.type/instant :db/index true}
    :ai.session/ended-at      {:db/valueType :db.type/instant}
    :ai.session/input-tokens  {:db/valueType :db.type/long}
    :ai.session/output-tokens {:db/valueType :db.type/long}
    :ai.session/cost-usd      {:db/valueType :db.type/double}
    :ai.session/error         {:db/valueType :db.type/string}

    :ai.message/id            {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :ai.message/type          {:db/valueType :db.type/keyword}
    :ai.message/session-id    {:db/valueType :db.type/string :db/index true}
    :ai.message/role          {:db/valueType :db.type/string}
    :ai.message/content       {:db/valueType :db.type/string}
    :ai.message/timestamp     {:db/valueType :db.type/instant :db/index true}
    :ai.message/input-tokens  {:db/valueType :db.type/long}
    :ai.message/output-tokens {:db/valueType :db.type/long}
    :ai.message/cache-read-tokens {:db/valueType :db.type/long}
    :ai.message/cache-creation-tokens {:db/valueType :db.type/long}
    :ai.message/tool-calls    {:db/valueType :db.type/string}
    :ai.message/tool-results  {:db/valueType :db.type/string}
    :ai.message/provider      {:db/valueType :db.type/keyword}}

   ;; Agent Context Domain
   {:ctx/id                   {:db/valueType :db.type/uuid}
    :ctx/namespace            {:db/valueType :db.type/string :db/index true}
    :ctx/recorded-at          {:db/valueType :db.type/instant :db/index true}
    :ctx/state                {:db/valueType :db.type/string}

    :primer.session/id        {:db/valueType :db.type/string :db/index true}
    :primer.session/checkpointed-at {:db/valueType :db.type/instant :db/index true}
    :primer.session/created-at {:db/valueType :db.type/instant}
    :primer.session/state     {:db/valueType :db.type/string}}

   ;; Orchestrator Domain
   {:orch.session/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :orch.session/namespace   {:db/valueType :db.type/string :db/index true}
    :orch.session/status      {:db/valueType :db.type/keyword :db/index true}
    :orch.session/nrepl-port  {:db/valueType :db.type/long}
    :orch.session/db-name     {:db/valueType :db.type/string}
    :orch.session/started-at  {:db/valueType :db.type/instant :db/index true}
    :orch.session/stopped-at  {:db/valueType :db.type/instant}}

   ;; Trading Domain
   {:quote/id                 {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :asset/ticker             {:db/valueType :db.type/string :db/index true}
    :option/id                {:db/valueType :db.type/string :db/index true}
    :option/strike            {:db/valueType :db.type/double}
    :option/type              {:db/valueType :db.type/keyword}
    :option/expiry            {:db/valueType :db.type/instant :db/index true}
    :quote/bid                {:db/valueType :db.type/double}
    :quote/ask                {:db/valueType :db.type/double}
    :quote/iv                 {:db/valueType :db.type/double}
    :quote/recorded-at        {:db/valueType :db.type/instant :db/index true}
    :greeks/delta             {:db/valueType :db.type/double}
    :greeks/gamma             {:db/valueType :db.type/double}
    :greeks/vega              {:db/valueType :db.type/double}
    :greeks/theta             {:db/valueType :db.type/double}
    :market/volume            {:db/valueType :db.type/long}
    :market/aggressor         {:db/valueType :db.type/keyword}

    :ingestion/id             {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :ingestion/symbol         {:db/valueType :db.type/string :db/index true}
    :ingestion/status         {:db/valueType :db.type/keyword :db/index true}
    :ingestion/start-date     {:db/valueType :db.type/instant}
    :ingestion/last-date      {:db/valueType :db.type/instant}
    :ingestion/records-count  {:db/valueType :db.type/long}
    :ingestion/error          {:db/valueType :db.type/string}
    :ingestion/updated-at     {:db/valueType :db.type/instant :db/index true}

    :progress/id              {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :progress/symbol          {:db/valueType :db.type/string :db/index true}
    :progress/date            {:db/valueType :db.type/instant}
    :progress/records         {:db/valueType :db.type/long}
    :progress/completed-at    {:db/valueType :db.type/instant :db/index true}}

   ;; Dev Hook Domain
   {:edit/id                  {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
    :edit/entity-type         {:db/valueType :db.type/keyword}
    :edit/file                {:db/valueType :db.type/string :db/index true}
    :edit/namespace           {:db/valueType :db.type/keyword}
    :edit/content-hash        {:db/valueType :db.type/string}
    :edit/created-at          {:db/valueType :db.type/instant :db/index true}
    :edit/unit-test-result    {:db/valueType :db.type/string}
    :edit/gen-test-result     {:db/valueType :db.type/string}
    :edit/decision            {:db/valueType :db.type/keyword :db/index true}
    :edit/reason              {:db/valueType :db.type/string}
    :edit/feedback            {:db/valueType :db.type/string}

    :review/id                {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
    :review/entity-type       {:db/valueType :db.type/keyword}
    :review/files             {:db/valueType :db.type/string}
    :review/edit-count        {:db/valueType :db.type/long}
    :review/created-at        {:db/valueType :db.type/instant :db/index true}
    :review/gemini-prompt     {:db/valueType :db.type/string}
    :review/gemini-response   {:db/valueType :db.type/string}
    :review/gemini-system-instruction {:db/valueType :db.type/string}
    :review/gemini-code       {:db/valueType :db.type/string}
    :review/gemini-tokens     {:db/valueType :db.type/string}

    :todo/id                  {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
    :todo/entity-type         {:db/valueType :db.type/keyword}
    :todo/session-id          {:db/valueType :db.type/string :db/index true}
    :todo/todos               {:db/valueType :db.type/string}
    :todo/created-at          {:db/valueType :db.type/instant :db/index true}}))
```

---

## XTDB → Datalevin Entity Mapping

### AI Session

**XTDB Document:**
```clojure
{:xt/id "ses-abc123"
 :seon.ai/type :session
 :seon.ai/status :active
 :seon.ai/namespace "seon.trading"
 :seon.ai/prompt "Analyze options data"
 :seon.ai/started-at #inst "2026-01-28T10:00:00Z"
 :seon.ai/input-tokens 1500
 :seon.ai/output-tokens 800
 :seon.ai/cost-usd 0.23}
```

**Datalevin Entity:**
```clojure
{:ai.session/id "ses-abc123"
 :ai.session/type :session
 :ai.session/status :active
 :ai.session/namespace "seon.trading"
 :ai.session/prompt "Analyze options data"
 :ai.session/started-at #inst "2026-01-28T10:00:00Z"
 :ai.session/input-tokens 1500
 :ai.session/output-tokens 800
 :ai.session/cost-usd 0.23}
```

**Key change:** Namespace prefix changes from `seon.ai/` to `ai.session/`.

---

### AI Message

**XTDB Document:**
```clojure
{:xt/id "msg-xyz789"
 :seon.ai/type :message
 :seon.ai/session-id "ses-abc123"
 :seon.ai/role "assistant"
 :seon.ai/content "I'll analyze the data..."
 :seon.ai/timestamp #inst "2026-01-28T10:01:00Z"
 :seon.ai/input-tokens 500
 :seon.ai/output-tokens 200}
```

**Datalevin Entity:**
```clojure
{:ai.message/id "msg-xyz789"
 :ai.message/type :message
 :ai.message/session-id "ses-abc123"
 :ai.message/role "assistant"
 :ai.message/content "I'll analyze the data..."
 :ai.message/timestamp #inst "2026-01-28T10:01:00Z"
 :ai.message/input-tokens 500
 :ai.message/output-tokens 200}
```

---

### Context Snapshot

**XTDB Document:**
```clojure
;; In XTDB: stored with SQL INSERT, uses _system_from for history
{:xt/id "ctx-uuid-here"
 :namespace "seon.trading"
 :state "{:seon.trading/signals [...]}"}
;; _system_from auto-set by XTDB
```

**Datalevin Entity:**
```clojure
{:ctx/id #uuid "ctx-uuid-here"
 :ctx/namespace "seon.trading"
 :ctx/recorded-at #inst "2026-01-28T10:00:00Z"  ; Explicit!
 :ctx/state "{:seon.trading/signals [...]}"}
```

**Key change:** Explicit `:ctx/recorded-at` replaces implicit `_system_from`.

---

### Option Greeks

**XTDB Document:**
```clojure
{:xt/id "AAPL231215C00185000-2024-11-01T14:00:00Z"
 :xt/valid-from #inst "2024-11-01T14:00:00Z"  ; Set for historical data
 :asset/ticker "AAPL"
 :option/id "AAPL231215C00185000"
 :option/strike 185.0
 :option/type :call
 :option/expiry #inst "2023-12-15T00:00:00Z"
 :quote/bid 5.25
 :quote/ask 5.35
 :quote/iv 0.28
 :greeks/delta 0.45
 :greeks/gamma 0.03
 :greeks/vega 0.15
 :greeks/theta -0.08}
```

**Datalevin Entity:**
```clojure
{:quote/id "AAPL231215C00185000-2024-11-01T14:00:00Z"
 :quote/recorded-at #inst "2024-11-01T14:00:00Z"  ; Explicit, was :xt/valid-from
 :asset/ticker "AAPL"
 :option/id "AAPL231215C00185000"
 :option/strike 185.0
 :option/type :call
 :option/expiry #inst "2023-12-15T00:00:00Z"
 :quote/bid 5.25
 :quote/ask 5.35
 :quote/iv 0.28
 :greeks/delta 0.45
 :greeks/gamma 0.03
 :greeks/vega 0.15
 :greeks/theta -0.08}
```

**Key changes:**
- `:xt/id` → `:quote/id`
- `:xt/valid-from` → `:quote/recorded-at`

---

### Edit Event

**XTDB Document:**
```clojure
{:xt/id #uuid "abc123..."
 :seon.dev.context/entity-type :edit-event
 :seon.dev.context/file "/path/to/file.clj"
 :seon.dev.context/namespace :seon.foo
 :seon.dev.context/decision :continue
 :seon.dev.context/unit-test-result {:success true :test-count 5}}
;; _valid_from auto-set by XTDB
```

**Datalevin Entity:**
```clojure
{:edit/id #uuid "abc123..."
 :edit/entity-type :edit-event
 :edit/file "/path/to/file.clj"
 :edit/namespace :seon.foo
 :edit/decision :continue
 :edit/unit-test-result "{:success true :test-count 5}"  ; EDN-encoded
 :edit/created-at #inst "2026-01-28T10:00:00Z"}  ; Explicit!
```

**Key changes:**
- Namespace prefix simplified from `seon.dev.context/` to `edit/`
- Complex values (maps, vectors) EDN-encoded as strings
- Explicit `:edit/created-at` replaces implicit `_valid_from`

---

## Design Decisions

### 1. Attribute Naming Convention

**Decision:** Use domain-prefixed short names instead of full Clojure namespace paths.

| XTDB | Datalevin |
|------|-----------|
| `:seon.ai/session-id` | `:ai.session/id` |
| `:seon.dev.context/file` | `:edit/file` |
| `:xt/id` | `:<entity>/id` |

**Rationale:**
- Shorter attribute names improve query readability
- Domain prefix (e.g., `ai.session/`) groups related attributes
- Matches Datomic conventions

### 2. Complex Values as EDN Strings

**Decision:** Store complex values (maps, vectors, sets) as EDN-encoded strings.

**Affected attributes:**
- `:ai.session/error`
- `:ai.message/tool-calls`, `:ai.message/tool-results`
- `:edit/unit-test-result`, `:edit/gen-test-result`, `:edit/feedback`
- `:review/files`, `:review/gemini-tokens`
- `:todo/todos`
- `:ctx/state`, `:primer.session/state`

**Rationale:**
- Datalevin doesn't support arbitrary nested data like XTDB
- EDN strings are human-readable and debuggable
- Query predicates can still work via full-text search if needed
- State atoms contain arbitrary agent data - must be flexible

### 3. Explicit Timestamps

**Decision:** All entities have explicit timestamp attributes.

| Entity Type | Timestamp Attribute |
|-------------|---------------------|
| AI Session | `:ai.session/started-at`, `:ai.session/ended-at` |
| AI Message | `:ai.message/timestamp` |
| Context Snapshot | `:ctx/recorded-at` |
| Option Greeks | `:quote/recorded-at` |
| Edit Event | `:edit/created-at` |
| Review Event | `:review/created-at` |
| Todo Event | `:todo/created-at` |

**Rationale:**
- Makes temporal behavior explicit and debuggable
- Enables point-in-time queries via explicit filtering
- Avoids dependency on database-specific temporal features

### 4. No Entity References

**Decision:** Store foreign keys as strings, not Datalevin refs.

**Example:** `:ai.message/session-id` is `:db.type/string`, not `:db.type/ref`.

**Rationale:**
- Simpler migration path (no ref resolution needed)
- Messages belong to sessions that may be in different databases (multi-db)
- Can add refs later if join performance becomes an issue

### 5. Indexes

**Decision:** Index attributes used in WHERE clauses and ORDER BY.

**Indexed attributes:**
- All `:*-at` timestamp fields (for time-range queries)
- All `/status` fields (for filtering by state)
- All `/id` and lookup fields (for joins)
- `:asset/ticker`, `:option/id` (for trading queries)

**Rationale:**
- Datalevin uses B+ trees; indexes are cheap
- Read performance matters for UI responsiveness
- Write performance impact is minimal for our volume

---

## Migration Utilities

### Entity Transformer

```clojure
(defn xtdb->datalevin
  "Transform an XTDB entity to Datalevin format."
  [entity entity-type]
  (case entity-type
    :ai-session
    (-> entity
        (dissoc :xt/id)
        (assoc :ai.session/id (:xt/id entity))
        (rename-keys {:seon.ai/type :ai.session/type
                      :seon.ai/status :ai.session/status
                      :seon.ai/namespace :ai.session/namespace
                      ;; ... etc
                      }))

    :option-quote
    (-> entity
        (dissoc :xt/id :xt/valid-from)
        (assoc :quote/id (:xt/id entity)
               :quote/recorded-at (or (:xt/valid-from entity)
                                      (java.util.Date.))))

    ;; ... other entity types
    ))
```

### Batch Migration

```clojure
(defn migrate-table!
  "Migrate an XTDB table to Datalevin."
  [xtdb-node dl-conn table-name entity-type]
  (let [entities (xt/q xtdb-node
                       (format "SELECT * FROM %s" table-name))
        transformed (map #(xtdb->datalevin % entity-type) entities)]
    (d/transact! dl-conn transformed)))
```

---

## Next Steps

1. **Implement schema in code** - Create `seon.db.datalevin/schema.clj`
2. **Create Integrant component** - Connection lifecycle with schema
3. **Migrate AI domain first** - Sessions + messages (simplest, no temporal)
4. **Add query layer** - Datalog queries matching current SQL patterns
5. **Test with existing data** - Load current XTDB data into Datalevin
6. **Performance benchmarks** - Query latency, memory usage comparison
