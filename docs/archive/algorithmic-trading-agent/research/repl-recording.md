---
type: research
status: completed
tags: [research, archive, trading, agent]
---

# Research: REPL Recording for Training Data

**Status:** Complete
**Researcher:** Claude (Research Agent)
**Date:** 2025-12-19

---

## Research Question

How do we capture the full REPL session for training data, separating what the LLM saw (limited context) from the full values (for replay/debugging)?

---

## Executive Summary

This document designs a REPL session recording system that captures LLM agent interactions for training data. The key insight is **two-level storage**:

1. **What the agent saw** - Limited, pretty-printed output that fits in context windows
2. **Full values** - Complete data structures for replay and debugging

The recommended approach uses **explicit recording with atom-based storage**, content-addressed hashing for value IDs, and a JSONL export format compatible with standard LLM fine-tuning workflows.

---

## Goals

1. **Training Data** - Capture sessions to train future agents
2. **Replay** - Be able to replay exactly what agent saw
3. **Full Values** - Access complete data for any step
4. **Annotation** - Agent can add reasoning/explanations after the fact
5. **Low Overhead** - Recording shouldn't slow down the session

---

## Design Decisions

### Decision 1: Two-Level Storage Architecture

**Decision:** Store limited output (for context windows) separately from full values (for debugging).

**Rationale:**
- LLMs have context limits (typically 8K-200K tokens)
- Agents need to review their work with full values visible
- Training data needs to reflect actual agent experience
- Debugging requires complete data structures

**Structure:**

```clojure
;; In the ctx atom

;; Level 1: What agent saw (limited, for context window)
::repl-history
;; => [{:input "(iv-rank ctx {:ticker \"SPY\"})"
;;      :output "IV Rank: 0.73 (73rd percentile over 252 days)"
;;      :val-id "v_abc123"
;;      :timestamp #inst "2025-12-19T10:30:00Z"
;;      :fn-name "iv-rank"
;;      :duration-ms 42}]

;; Level 2: Full values (for replay/debugging)
::repl-vals
;; => {"v_abc123" {:iv-rank 0.73
;;                 :lookback 252
;;                 :historical-ivs [0.15 0.18 0.22 ... (252 values)]
;;                 :current-iv 0.28
;;                 :metadata {:query-time-ms 38}}}

```

### Decision 2: Output Truncation Strategy

**Decision:** Truncate output at **2000 characters** for history, with smart truncation based on data type.

**Rationale:**
- 2000 chars is roughly 500 tokens - reasonable for a single REPL interaction
- Still readable in context - long enough to convey meaning
- Consistent with `*print-length*` and `*print-level*` conventions

**Truncation Rules:**

| Data Type | Strategy | Example |
|-----------|----------|---------|
| Maps | Show first 5 keys, summarize rest | `{:a 1 :b 2 :c 3 :d 4 :e 5 ...+15 more}` |
| Vectors | Show first/last 3, summarize middle | `[1 2 3 ... +94 more ... 98 99 100]` |
| Strings | Head truncation with ellipsis | `"Long string content..." (2500 chars)` |
| Numbers | Full value (always small) | `0.7324567` |
| Keywords | Full value (always small) | `:high-volatility` |
| Nested | Apply `*print-level*` = 3 | `{:a {:b {:c #}}}` |

**Implementation:**

```clojure
(def ^:dynamic *output-limit* 2000)
(def ^:dynamic *print-depth* 3)
(def ^:dynamic *collection-limit* 10)

(defn truncate-for-context
  "Truncate a value for LLM context window.
   Returns [truncated-string truncated?]"
  [value]
  (let [full-str (binding [*print-length* *collection-limit*
                           *print-level* *print-depth*]
                   (with-out-str (clojure.pprint/pprint value)))
        truncated? (> (count full-str) *output-limit*)]
    [(if truncated?
       (str (subs full-str 0 (- *output-limit* 20))
            "\n... (truncated, see val-id for full value)")
       full-str)
     truncated?]))

```

### Decision 3: Content-Addressed Value IDs

**Decision:** Use truncated SHA-256 hash of serialized value, prefixed with `v_`.

**Rationale:**
- Content-addressed = same value always gets same ID
- Enables deduplication (same result stored once)
- Short enough to be readable (8 hex chars = 4 billion possibilities)
- Prefix `v_` makes it clear this is a value reference

**Implementation:**

```clojure
(import '[java.security MessageDigest])

(defn value-hash
  "Generate a short content-addressed hash for a value.
   Returns string like 'v_a1b2c3d4'"
  [value]
  (let [serialized (pr-str value)
        md (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes serialized "UTF-8"))
        hex-str (->> hash-bytes
                     (take 4)  ; First 4 bytes = 8 hex chars
                     (map #(format "%02x" (bit-and % 0xff)))
                     (apply str))]
    (str "v_" hex-str)))

;; Examples:
(value-hash {:iv-rank 0.73})
;; => "v_a1b2c3d4"

(value-hash {:iv-rank 0.73})  ; Same value = same hash
;; => "v_a1b2c3d4"

(value-hash {:iv-rank 0.74})  ; Different value = different hash
;; => "v_e5f6a7b8"

```

### Decision 4: Explicit Recording (Not Auto-Capture)

**Decision:** Use explicit `record!` wrapper, not automatic middleware or atom watch.

**Rationale:**

| Approach | Pros | Cons |
|----------|------|------|
| **Middleware (wrap all functions)** | Automatic | Noisy (records internal calls), complex |
| **Atom watch** | Catches all state changes | Can't capture function name/input |
| **Explicit `record!`** | Clear intent, full control | Requires agent discipline |

The explicit approach wins because:
1. Agent controls what gets recorded (not every internal computation)
2. Captures the full context: function name, input expression, reasoning
3. Zero overhead for non-recorded calls
4. Agent can skip recording trivial operations

**Implementation:**

```clojure
(defn record!
  "Execute an expression and record it to session history.

   Usage:
     (record! ctx 'iv-rank \"Checking IV percentile\" (iv-rank ctx opts))

   Args:
     ctx - The ctx atom
     fn-name - Symbol naming the function (for display)
     reasoning - Why agent is making this call (optional, can be nil)
     expr - The expression to evaluate (already evaluated by caller)"
  ([ctx fn-name expr]
   (record! ctx fn-name nil expr))
  ([ctx fn-name reasoning expr]
   (let [start-ms (System/currentTimeMillis)
         result expr  ; Already evaluated
         end-ms (System/currentTimeMillis)
         val-id (value-hash result)
         [truncated _] (truncate-for-context result)]
     (swap! ctx update ::repl-history conj
            {:fn-name (str fn-name)
             :output truncated
             :val-id val-id
             :timestamp (java.time.Instant/now)
             :duration-ms (- end-ms start-ms)
             :reasoning reasoning})
     (swap! ctx assoc-in [::repl-vals val-id] result)
     result)))

```

**Convenience macro for natural syntax:**

```clojure
(defmacro rec!
  "Record a REPL interaction with natural syntax.

   Usage:
     (rec! ctx (iv-rank ctx {:ticker \"SPY\"}))
     (rec! ctx \"Checking IV\" (iv-rank ctx {:ticker \"SPY\"}))"
  ([ctx expr]
   `(rec! ~ctx nil ~expr))
  ([ctx reasoning expr]
   (let [fn-name (if (seq? expr) (first expr) 'eval)]
     `(let [start# (System/currentTimeMillis)
            result# ~expr
            end# (System/currentTimeMillis)]
        (record-result! ~ctx '~fn-name ~reasoning result# (- end# start#))
        result#))))

(defn record-result!
  "Internal: record a result after evaluation."
  [ctx fn-name reasoning result duration-ms]
  (let [val-id (value-hash result)
        [truncated _] (truncate-for-context result)]
    (swap! ctx update ::repl-history conj
           {:fn-name (str fn-name)
            :output truncated
            :val-id val-id
            :timestamp (java.time.Instant/now)
            :duration-ms duration-ms
            :reasoning reasoning})
    (swap! ctx assoc-in [::repl-vals val-id] result)))

```

### Decision 5: Annotation After the Fact

**Decision:** Allow editing history entries by index, with annotation vs replacement distinction.

**Rationale:**
- Agents often realize the significance of a step later
- Training data benefits from reasoning explanations
- Need to distinguish "what I thought then" from "what I know now"

**Implementation:**

```clojure
(defn annotate!
  "Add or update annotation on a history entry.
   Does NOT change the original reasoning - adds a new field.

   Args:
     ctx - The ctx atom
     idx - Index in history (0 = first, -1 = last)
     annotation - String explaining the step in retrospect"
  [ctx idx annotation]
  (let [actual-idx (if (neg? idx)
                     (+ (count (::repl-history @ctx)) idx)
                     idx)]
    (swap! ctx assoc-in [::repl-history actual-idx :annotation] annotation)))

(defn tag!
  "Add tags to a history entry for categorization.

   Args:
     ctx - The ctx atom
     idx - Index in history
     tags - Keywords or strings, e.g. :important, :mistake, :insight"
  [ctx idx & tags]
  (let [actual-idx (if (neg? idx)
                     (+ (count (::repl-history @ctx)) idx)
                     idx)]
    (swap! ctx update-in [::repl-history actual-idx :tags]
           (fnil into #{}) tags)))

;; Usage:
(annotate! ctx -1 "This high IV rank was the key signal that led to the trade")
(tag! ctx -1 :key-insight :volatility-signal)

```

### Decision 6: Session Format and Export

**Decision:** Store sessions as EDN internally, export to JSONL for training.

**Rationale:**
- EDN is native Clojure - best for internal use and debugging
- JSONL is standard for LLM fine-tuning (OpenAI, Anthropic, HuggingFace)
- Separate concerns: internal format vs training format

**Internal Session Structure (EDN):**

```clojure
{:session/id #uuid "550e8400-e29b-41d4-a716-446655440000"
 :session/created #inst "2025-12-19T10:00:00Z"
 :session/frozen-time #inst "2024-06-15T16:00:00Z"  ; Agent's "present"
 :session/ticker "SPY"
 :session/goal "Analyze volatility conditions for potential short vol trade"

 ::repl-history
 [{:fn-name "iv-rank"
   :output "IV Rank: 0.73 (73rd percentile)"
   :val-id "v_a1b2c3d4"
   :timestamp #inst "2025-12-19T10:05:00Z"
   :duration-ms 42
   :reasoning "First, check where IV sits relative to history"
   :annotation "This elevated IV was the primary trade signal"
   :tags #{:key-insight}}
  {:fn-name "skew-index"
   :output "Skew: 0.048 (slightly elevated)"
   :val-id "v_e5f6a7b8"
   :timestamp #inst "2025-12-19T10:05:30Z"
   :duration-ms 38
   :reasoning "Check if put skew confirms bearish sentiment"}]

 ::repl-vals
 {"v_a1b2c3d4" {:iv-rank 0.73
                :lookback 252
                :current-iv 0.28
                :historical-ivs [...]}
  "v_e5f6a7b8" {:skew 0.048
                :put-iv 0.30
                :call-iv 0.252}}}

```

**Training Data Export Format (JSONL):**

```jsonl
{"messages":[{"role":"system","content":"You are a trading analyst..."},{"role":"user","content":"Analyze SPY for potential trades"},{"role":"assistant","content":"I'll analyze SPY's volatility conditions.\n\n```clojure\n(iv-rank ctx {:ticker \"SPY\"})\n```\n\nResult: IV Rank: 0.73 (73rd percentile)\n\nThe IV is elevated, sitting at the 73rd percentile. This suggests options are relatively expensive compared to the past year."}]}

```

**Export Function:**

```clojure
(defn export-session-jsonl
  "Export a session as JSONL training data.

   Format follows OpenAI chat format:
   - system: Agent persona and context
   - user: Task/goal
   - assistant: REPL interactions with reasoning

   Returns: String in JSONL format"
  [session]
  (let [system-prompt (format "You are a trading analyst with access to a REPL environment. Today's date is %s. You are analyzing %s."
                              (:session/frozen-time session)
                              (:session/ticker session))
        user-prompt (:session/goal session)

        ;; Build assistant response from history
        assistant-content
        (->> (::repl-history session)
             (map (fn [{:keys [fn-name output reasoning annotation]}]
                    (str (when reasoning (str reasoning "\n\n"))
                         "```clojure\n(" fn-name " ctx ...)\n```\n\n"
                         "Result: " output
                         (when annotation (str "\n\n*Note: " annotation "*")))))
             (str/join "\n\n---\n\n"))

        messages [{:role "system" :content system-prompt}
                  {:role "user" :content user-prompt}
                  {:role "assistant" :content assistant-content}]]
    (json/write-str {:messages messages})))

```

---

## Complete Prototype Implementation

```clojure
(ns seon.algorithmic-trading.recording
  "REPL session recording for LLM training data capture.

  Provides:
  - Session recording with two-level storage
  - Output truncation for context windows
  - Content-addressed value storage
  - Annotation and tagging
  - JSONL export for training

  Usage:
    ;; Start a session
    (start-session! ctx {:goal \"Analyze SPY volatility\"
                         :frozen-time #inst \"2024-06-15T16:00:00Z\"})

    ;; Record interactions
    (rec! ctx \"Checking IV rank\" (iv-rank ctx {:ticker \"SPY\"}))
    (rec! ctx (skew-index ctx {:ticker \"SPY\"}))

    ;; Annotate after the fact
    (annotate! ctx -1 \"This skew was unusually low - missed opportunity\")

    ;; Review session
    (print-session ctx)

    ;; Export for training
    (spit \"session.jsonl\" (export-session-jsonl @ctx))"
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.security MessageDigest]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:dynamic *output-limit*
  "Maximum characters for truncated output"
  2000)

(def ^:dynamic *print-depth*
  "Maximum nesting depth for pretty-print"
  3)

(def ^:dynamic *collection-limit*
  "Maximum items to show in collections"
  10)

;;; ---------------------------------------------------------------------------
;;; Value Hashing
;;; ---------------------------------------------------------------------------

(defn value-hash
  "Generate a short content-addressed hash for a value.
   Returns string like 'v_a1b2c3d4'"
  [value]
  (let [serialized (pr-str value)
        md (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes serialized "UTF-8"))
        hex-str (->> hash-bytes
                     (take 4)
                     (map #(format "%02x" (bit-and % 0xff)))
                     (apply str))]
    (str "v_" hex-str)))

;;; ---------------------------------------------------------------------------
;;; Output Truncation
;;; ---------------------------------------------------------------------------

(defn truncate-for-context
  "Truncate a value for LLM context window.
   Returns [truncated-string truncated?]"
  [value]
  (let [full-str (binding [*print-length* *collection-limit*
                           *print-level* *print-depth*]
                   (with-out-str (pp/pprint value)))
        char-count (count full-str)
        truncated? (> char-count *output-limit*)]
    [(if truncated?
       (str (subs full-str 0 (min (- *output-limit* 50) (count full-str)))
            "\n... (" char-count " chars total, truncated)")
       (str/trim full-str))
     truncated?]))

(defn format-output
  "Format output for display, with type hints for complex values."
  [value]
  (let [[truncated truncated?] (truncate-for-context value)]
    (cond
      ;; Special formatting for common result types
      (and (map? value) (:iv-rank value))
      (format "IV Rank: %.2f (%d%% percentile)"
              (:iv-rank value)
              (int (* 100 (:iv-rank value))))

      (and (map? value) (:skew value))
      (format "Skew: %.3f (%s)"
              (:skew value)
              (cond
                (> (:skew value) 0.06) "elevated"
                (< (:skew value) 0.02) "low"
                :else "normal"))

      ;; Default: truncated pretty-print
      :else truncated)))

;;; ---------------------------------------------------------------------------
;;; Session Management
;;; ---------------------------------------------------------------------------

(defn start-session!
  "Initialize a new recording session.

   Args:
     ctx - The ctx atom
     opts - {:goal \"...\", :frozen-time #inst \"...\", :ticker \"...\"}"
  [ctx opts]
  (swap! ctx merge
         {:session/id (UUID/randomUUID)
          :session/created (java.time.Instant/now)
          :session/frozen-time (:frozen-time opts)
          :session/ticker (:ticker opts)
          :session/goal (:goal opts)
          ::repl-history []
          ::repl-vals {}}))

(defn end-session!
  "Mark session as complete and return session data.

   Returns: The complete session map"
  [ctx]
  (let [session @ctx]
    (swap! ctx assoc :session/ended (java.time.Instant/now))
    session))

;;; ---------------------------------------------------------------------------
;;; Recording
;;; ---------------------------------------------------------------------------

(defn record-result!
  "Internal: record a result after evaluation."
  [ctx fn-name reasoning result duration-ms]
  (let [val-id (value-hash result)
        formatted (format-output result)]
    (swap! ctx update ::repl-history conj
           {:fn-name (str fn-name)
            :output formatted
            :val-id val-id
            :timestamp (java.time.Instant/now)
            :duration-ms duration-ms
            :reasoning reasoning})
    (swap! ctx assoc-in [::repl-vals val-id] result)
    result))

(defmacro rec!
  "Record a REPL interaction with natural syntax.

   Usage:
     (rec! ctx (iv-rank ctx {:ticker \"SPY\"}))
     (rec! ctx \"Checking IV\" (iv-rank ctx {:ticker \"SPY\"}))"
  ([ctx expr]
   `(rec! ~ctx nil ~expr))
  ([ctx reasoning expr]
   (let [fn-name (if (seq? expr) (first expr) 'eval)]
     `(let [start# (System/currentTimeMillis)
            result# ~expr
            end# (System/currentTimeMillis)]
        (record-result! ~ctx '~fn-name ~reasoning result# (- end# start#))
        result#))))

;;; ---------------------------------------------------------------------------
;;; Annotation
;;; ---------------------------------------------------------------------------

(defn annotate!
  "Add or update annotation on a history entry.

   Args:
     ctx - The ctx atom
     idx - Index in history (0 = first, -1 = last)
     annotation - String explaining the step in retrospect"
  [ctx idx annotation]
  (let [history (::repl-history @ctx)
        actual-idx (if (neg? idx)
                     (+ (count history) idx)
                     idx)]
    (when (and (>= actual-idx 0) (< actual-idx (count history)))
      (swap! ctx assoc-in [::repl-history actual-idx :annotation] annotation))))

(defn tag!
  "Add tags to a history entry for categorization.

   Args:
     ctx - The ctx atom
     idx - Index in history
     tags - Keywords, e.g. :important, :mistake, :insight"
  [ctx idx & tags]
  (let [history (::repl-history @ctx)
        actual-idx (if (neg? idx)
                     (+ (count history) idx)
                     idx)]
    (when (and (>= actual-idx 0) (< actual-idx (count history)))
      (swap! ctx update-in [::repl-history actual-idx :tags]
             (fnil into #{}) tags))))

;;; ---------------------------------------------------------------------------
;;; Review and Retrieval
;;; ---------------------------------------------------------------------------

(defn get-full-value
  "Retrieve the full value for a val-id.

   Args:
     ctx - The ctx atom (or dereferenced value)
     val-id - String like 'v_a1b2c3d4'"
  [ctx val-id]
  (let [ctx-val (if (instance? clojure.lang.Atom ctx) @ctx ctx)]
    (get-in ctx-val [::repl-vals val-id])))

(defn print-session
  "Pretty-print the session history for review."
  [ctx]
  (let [ctx-val (if (instance? clojure.lang.Atom ctx) @ctx ctx)
        history (::repl-history ctx-val)]
    (println "=" (str "Session: " (:session/goal ctx-val)) "=")
    (println "Frozen time:" (:session/frozen-time ctx-val))
    (println "")
    (doseq [[idx entry] (map-indexed vector history)]
      (println (str "--- [" idx "] " (:fn-name entry) " ---"))
      (when (:reasoning entry)
        (println "Reasoning:" (:reasoning entry)))
      (println "Output:" (:output entry))
      (println "Val-ID:" (:val-id entry))
      (when (:annotation entry)
        (println "Annotation:" (:annotation entry)))
      (when (seq (:tags entry))
        (println "Tags:" (str/join ", " (map name (:tags entry)))))
      (println ""))))

(defn session-summary
  "Get a concise summary of the session.

   Returns: Map with session stats"
  [ctx]
  (let [ctx-val (if (instance? clojure.lang.Atom ctx) @ctx ctx)
        history (::repl-history ctx-val)]
    {:session-id (:session/id ctx-val)
     :goal (:session/goal ctx-val)
     :frozen-time (:session/frozen-time ctx-val)
     :step-count (count history)
     :total-duration-ms (reduce + (map :duration-ms history))
     :tagged-steps (count (filter :tags history))
     :annotated-steps (count (filter :annotation history))}))

;;; ---------------------------------------------------------------------------
;;; Export for Training
;;; ---------------------------------------------------------------------------

(defn history-to-markdown
  "Convert history entries to markdown for assistant content."
  [history]
  (->> history
       (map (fn [{:keys [fn-name output reasoning annotation]}]
              (str (when reasoning (str "*" reasoning "*\n\n"))
                   "```clojure\n(" fn-name " ctx ...)\n```\n\n"
                   "**Result:**\n```\n" output "\n```"
                   (when annotation (str "\n\n> **Retrospective:** " annotation)))))
       (str/join "\n\n---\n\n")))

(defn export-session-jsonl
  "Export a session as JSONL training data.

   Format follows OpenAI/Anthropic chat format:
   - system: Agent persona and context
   - user: Task/goal
   - assistant: REPL interactions with reasoning

   Returns: String in JSONL format"
  [session]
  (let [ctx-val (if (instance? clojure.lang.Atom session) @session session)
        system-prompt (str "You are a quantitative trading analyst with access to a Clojure REPL environment for analyzing options data. "
                          "Today's date is " (:session/frozen-time ctx-val) ". "
                          "You are analyzing " (:session/ticker ctx-val) " for trading opportunities. "
                          "Use the available functions to gather data, then provide your analysis and recommendations.")
        user-prompt (:session/goal ctx-val)
        assistant-content (history-to-markdown (::repl-history ctx-val))

        messages [{:role "system" :content system-prompt}
                  {:role "user" :content user-prompt}
                  {:role "assistant" :content assistant-content}]]
    (json/write-str {:messages messages})))

(defn export-session-edn
  "Export a session as EDN for archival.

   Returns: EDN string with full session data"
  [session]
  (let [ctx-val (if (instance? clojure.lang.Atom session) @session session)]
    (pr-str (select-keys ctx-val
                         [:session/id :session/created :session/frozen-time
                          :session/ticker :session/goal :session/ended
                          ::repl-history ::repl-vals]))))

(defn export-training-batch
  "Export multiple sessions to a JSONL file.

   Args:
     sessions - Seq of session maps
     output-path - Path to output file"
  [sessions output-path]
  (spit output-path
        (str/join "\n" (map export-session-jsonl sessions))))

```

---

## Training Data Format Specification

### Chat Format (JSONL)

Each line is a JSON object representing one training example:

```json
{
  "messages": [
    {
      "role": "system",
      "content": "You are a quantitative trading analyst..."
    },
    {
      "role": "user",
      "content": "Analyze SPY for potential short volatility trades"
    },
    {
      "role": "assistant",
      "content": "*First, I'll check where IV sits relative to history*\n\n```clojure\n(iv-rank ctx {:ticker \"SPY\"})\n```\n\n**Result:**\n```\nIV Rank: 0.73 (73% percentile)\n```\n\nThe IV is elevated at the 73rd percentile..."
    }
  ]
}

```

### Metadata Schema

For richer training data, include metadata:

```json
{
  "messages": [...],
  "metadata": {
    "session_id": "550e8400-e29b-41d4-a716-446655440000",
    "frozen_time": "2024-06-15T16:00:00Z",
    "ticker": "SPY",
    "outcome": "successful_trade",
    "tags": ["volatility", "short_vol", "high_iv"]
  }
}

```

### Conversation Format (Multi-Turn)

For interactive sessions with back-and-forth:

```json
{
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "Analyze SPY"},
    {"role": "assistant", "content": "I'll start by checking IV rank..."},
    {"role": "user", "content": "What about the skew?"},
    {"role": "assistant", "content": "Good question, let me check skew..."}
  ]
}

```

---

## Alternative Approaches Considered

### Alternative 1: Middleware Auto-Capture

**Approach:** Wrap all trading functions with recording middleware.

```clojure
(defn with-recording [f]
  (fn [& args]
    (let [result (apply f args)]
      (record-to-history! ...)
      result)))

;; Apply to all functions
(doseq [[name var] (ns-publics 'seon.trading.signals)]
  (alter-var-root var with-recording))

```

**Rejected because:**
- Records internal calls (noisy)
- Can't capture reasoning (why the agent called this)
- Hard to filter what's important
- Performance overhead on every call

### Alternative 2: Atom Watch Pattern

**Approach:** Watch ctx atom for changes and record diffs.

```clojure
(add-watch ctx :recorder
  (fn [_ _ old new]
    (let [diff (compute-diff old new)]
      (record-diff! diff))))

```

**Rejected because:**
- Can't capture function name or input expression
- Doesn't know why state changed
- Multiple updates from one function call = multiple history entries
- Missing the "what agent typed" aspect

### Alternative 3: nREPL Middleware

**Approach:** Intercept at the nREPL level to capture actual REPL input/output.

**Rejected because:**
- Tight coupling to nREPL
- Doesn't work for programmatic calls
- Complex to implement correctly
- Agent doesn't always use literal REPL

---

## Usage Example: Complete Session

```clojure
(require '[seon.algorithmic-trading.recording :as rec])
(require '[seon.algorithmic-trading :as trading])

;; Start session
(def ctx (atom {}))
(rec/start-session! ctx
  {:goal "Analyze SPY for short volatility opportunity"
   :frozen-time #inst "2024-06-15T16:00:00Z"
   :ticker "SPY"})

;; Record analysis steps
(rec/rec! ctx
  "First, check IV rank to see if options are expensive"
  (trading/iv-rank ctx {:ticker "SPY"}))

(rec/rec! ctx
  "Check skew for additional confirmation"
  (trading/skew-index ctx {:ticker "SPY"}))

(rec/rec! ctx
  "Look at term structure"
  (trading/term-structure-slope ctx {:ticker "SPY"}))

;; Later: add annotations after reflecting on the session
(rec/annotate! ctx 0 "The elevated IV rank was the key signal")
(rec/tag! ctx 0 :key-signal :iv-analysis)

;; Review the session
(rec/print-session ctx)
;; = Session: Analyze SPY for short volatility opportunity =
;; Frozen time: 2024-06-15T16:00:00Z
;;
;; --- [0] iv-rank ---
;; Reasoning: First, check IV rank to see if options are expensive
;; Output: IV Rank: 0.73 (73% percentile)
;; Val-ID: v_a1b2c3d4
;; Annotation: The elevated IV rank was the key signal
;; Tags: key-signal, iv-analysis
;; ...

;; Export for training
(spit "training/spy-session-001.jsonl"
      (rec/export-session-jsonl ctx))

;; Access full value for any step
(rec/get-full-value ctx "v_a1b2c3d4")
;; => {:iv-rank 0.73 :lookback 252 :historical-ivs [...]}

```

---

## Integration with ctx Atom

The recording system integrates with the ctx atom pattern:

```clojure
;; ctx schema includes recording keys
(def ctx-schema
  [:map
   ;; ... other ctx keys ...

   ;; Recording
   [:session/id {:optional true} :uuid]
   [:session/created {:optional true} inst?]
   [:session/frozen-time {:optional true} inst?]
   [:session/ticker {:optional true} :string]
   [:session/goal {:optional true} :string]
   [::repl-history {:optional true} [:vector RecordingEntry]]
   [::repl-vals {:optional true} [:map-of :string :any]]])

(def RecordingEntry
  [:map
   [:fn-name :string]
   [:output :string]
   [:val-id :string]
   [:timestamp inst?]
   [:duration-ms :int]
   [:reasoning {:optional true} [:maybe :string]]
   [:annotation {:optional true} [:maybe :string]]
   [:tags {:optional true} [:set :keyword]]])

```

---

## Performance Considerations

1. **Hashing:** SHA-256 is fast (~1ms for typical result maps)
2. **Truncation:** String operations are fast (~0.1ms)
3. **Storage:** In-memory atom - no I/O during session
4. **Export:** One-time cost when session ends

**Recommendation:** Enable recording by default in agent sessions. The overhead is negligible (<5ms per recorded call).

---

## References

### LLM Training Data Formats

- [LLM Dataset Formats 101 - HuggingFace](https://huggingface.co/blog/tegridydev/llm-dataset-formats-101-hugging-face)
- [OpenAI Fine-tuning Data Format](https://platform.openai.com/docs/guides/fine-tuning)
- [Anyscale Dataset Preparation](https://docs.anyscale.com/llm/fine-tuning/data-preparation)

### Clojure Pretty Printing

- [Clojure REPL Data Visualization](https://clojure.org/guides/repl/data_visualization_at_the_repl)
- [clojure.pprint Documentation](https://clojuredocs.org/clojure.pprint)
- [Fipp - Fast Pretty Printer](https://github.com/brandonbloom/fipp)

### Content-Addressable Storage

- Java MessageDigest for SHA-256
- EDN serialization for consistent hashing

---

## Summary

| Question | Answer |
|----------|--------|
| How to truncate for LLM context? | 2000 char limit with `*print-length*`/`*print-level*` |
| What character limit? | 2000 chars (~500 tokens) |
| How to generate hash IDs? | Truncated SHA-256 of serialized value, prefixed with `v_` |
| Auto-capture or explicit? | Explicit `rec!` macro - agent controls what's recorded |
| How to annotate after the fact? | `annotate!` and `tag!` functions by index |
| Training data format? | JSONL with OpenAI chat format |

This design provides a complete solution for capturing REPL sessions in a way that supports both immediate agent use (limited output in context) and future training (full values accessible by hash).
