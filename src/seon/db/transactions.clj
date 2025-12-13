(ns seon.db.transactions
  "Transaction helpers for XTDB v2.

  Provides functions for:
  - Ingesting options data with deterministic IDs for deduplication
  - Recording trading signals
  - Managing entity lifecycle"
  (:require [seon.db.node :as node]
            [seon.db.schema :as schema])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(defn make-option-quote-id
  "Create a deterministic ID for an option quote.

  Format: \"{OCC_SYMBOL}-{ISO_TIMESTAMP}\"
  Example: \"AAPL231215C00185000-2024-11-01T14:00:00Z\"

  This ensures idempotent ingestion - re-ingesting the same data
  produces the same ID, which XTDB treats as an update (new version).

  Args:
    occ-symbol - OCC option symbol (e.g., \"AAPL231215C00185000\")
    timestamp - Instant, Date, or ISO string

  Returns:
    Deterministic ID string"
  [occ-symbol timestamp]
  (let [ts-str (cond
                 (instance? Instant timestamp)
                 (.format DateTimeFormatter/ISO_INSTANT timestamp)

                 (instance? java.util.Date timestamp)
                 (.format DateTimeFormatter/ISO_INSTANT (.toInstant timestamp))

                 (string? timestamp)
                 timestamp

                 :else
                 (str timestamp))]
    (str occ-symbol "-" ts-str)))

(defn make-iv-surface-id
  "Create a deterministic ID for an IV surface.

  Format: \"{TICKER}-surface-{ISO_TIMESTAMP}\"

  Args:
    ticker - Underlying symbol
    timestamp - Surface timestamp

  Returns:
    Deterministic ID string"
  [ticker timestamp]
  (let [ts-str (cond
                 (instance? Instant timestamp)
                 (.format DateTimeFormatter/ISO_INSTANT timestamp)

                 (instance? java.util.Date timestamp)
                 (.format DateTimeFormatter/ISO_INSTANT (.toInstant timestamp))

                 :else
                 (str timestamp))]
    (str ticker "-surface-" ts-str)))

;;; ---------------------------------------------------------------------------
;;; Transaction Builders
;;; ---------------------------------------------------------------------------

(defn put-option-quote
  "Create a transaction op to insert an option quote.

  Uses deterministic IDs based on OCC symbol + timestamp for deduplication.
  Re-ingesting the same quote creates a new version, not a duplicate.

  Args:
    quote - Option quote map (validated against schema)
           Must contain :option/id (OCC symbol)
    valid-time - Valid time for the quote (required for deterministic ID)

  Returns:
    Transaction op vector"
  ([quote]
   (put-option-quote quote nil))
  ([quote valid-time]
   (let [;; Generate deterministic ID if not provided
         quote-with-id (if (:xt/id quote)
                         quote
                         (if-let [occ-symbol (:option/id quote)]
                           (let [ts (or valid-time (java.time.Instant/now))]
                             (assoc quote :xt/id (make-option-quote-id occ-symbol ts)))
                           ;; Fallback to UUID if no OCC symbol (shouldn't happen)
                           (assoc quote :xt/id (str (UUID/randomUUID)))))
         ;; Add valid-time if provided
         quote-final (if valid-time
                       (assoc quote-with-id :xt/valid-from valid-time)
                       quote-with-id)]
     [:put-docs :option-quotes quote-final])))

(defn put-iv-surface
  "Create a transaction op to insert an IV surface.

  Uses deterministic IDs based on ticker + timestamp for deduplication.

  Args:
    surface - IV surface map (must contain :asset/ticker and :surface/timestamp)
    valid-time - Optional valid time

  Returns:
    Transaction op vector"
  ([surface]
   (put-iv-surface surface nil))
  ([surface valid-time]
   (let [;; Generate deterministic ID if not provided
         surface-with-id (if (:xt/id surface)
                           surface
                           (if-let [ticker (:asset/ticker surface)]
                             (let [ts (or (:surface/timestamp surface)
                                          valid-time
                                          (java.time.Instant/now))]
                               (assoc surface :xt/id (make-iv-surface-id ticker ts)))
                             ;; Fallback to UUID if no ticker
                             (assoc surface :xt/id (str (UUID/randomUUID)))))
         ;; Add valid-time if provided
         surface-final (if valid-time
                         (assoc surface-with-id :xt/valid-from valid-time)
                         surface-with-id)]
     [:put-docs :iv-surfaces surface-final])))

(defn put-signal
  "Create a transaction op to insert a trading signal.

  Args:
    signal - Trading signal map

  Returns:
    Transaction op vector"
  [signal]
  (let [signal-with-id (if (:signal/id signal)
                         signal
                         (assoc signal :signal/id (UUID/randomUUID)))]
    [:put-docs :trading-signals signal-with-id]))

(defn delete-option-quote
  "Create a transaction op to delete an option quote.

  Args:
    id - Quote ID

  Returns:
    Transaction op vector"
  [id]
  [:delete-docs :option-quotes id])

;;; ---------------------------------------------------------------------------
;;; Batch Operations
;;; ---------------------------------------------------------------------------

(defn ingest-quotes!
  "Ingest a batch of option quotes.

  Validates each quote against the schema before inserting.

  Args:
    node - XTDB node
    quotes - Sequence of option quote maps
    valid-time - Optional valid time for all quotes

  Returns:
    Transaction result"
  ([node quotes]
   (ingest-quotes! node quotes nil))
  ([node quotes valid-time]
   (let [validated (filter #(schema/validate :option/quote %) quotes)
         tx-ops (mapv #(put-option-quote % valid-time) validated)]
     (when (seq tx-ops)
       (node/submit-tx! node tx-ops)))))

(defn ingest-surface!
  "Ingest an IV surface.

  Args:
    node - XTDB node
    surface - IV surface map
    valid-time - Optional valid time

  Returns:
    Transaction result"
  ([node surface]
   (ingest-surface! node surface nil))
  ([node surface valid-time]
   (when (schema/validate :iv/surface surface)
     (node/submit-tx! node [(put-iv-surface surface valid-time)]))))

(defn record-signal!
  "Record a trading signal.

  Args:
    node - XTDB node
    signal - Trading signal map

  Returns:
    Transaction result"
  [node signal]
  (when (schema/validate :trading/signal signal)
    (node/submit-tx! node [(put-signal signal)])))

;;; ---------------------------------------------------------------------------
;;; Backdated Transactions (for Oracle)
;;; ---------------------------------------------------------------------------

(defn backdate-correction!
  "Submit a correction with explicit valid-time.

  Used by the Oracle to record 'what we knew when' for training.

  Args:
    node - XTDB node
    table - Target table keyword
    entity - Entity to insert/update
    valid-from - When the fact became true
    valid-to - When the fact stopped being true (optional)

  Returns:
    Transaction result"
  ([node table entity valid-from]
   (backdate-correction! node table entity valid-from nil))
  ([node table entity valid-from valid-to]
   (let [entity-with-temporal (cond-> (assoc entity :xt/valid-from valid-from)
                                valid-to (assoc :xt/valid-to valid-to))
         op [:put-docs table entity-with-temporal]]
     (node/submit-tx! node [op]))))
