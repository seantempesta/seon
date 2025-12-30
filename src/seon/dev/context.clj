(ns seon.dev.context
  "Agent context tracking for development feedback.

   Maintains context of edits and reviews for the development hook:
   - Records edit events (immutable facts in XTDB)
   - Records review completion events
   - Provides simple rate-limited review triggering

   This replaces the complex debounce logic with straightforward rate limiting:
   at most one review per N seconds.

   Entity types stored:
   - :edit-event - When a file was edited
   - :review-event - When a review was completed

   Example usage:
     (require '[seon.dev.context :as ctx])

     ;; Record an edit
     (ctx/record-edit! node \"/path/to/file.clj\" 'seon.foo)

     ;; Check if we should review
     (ctx/should-review? node)
     ;; => true (if interval has passed)

     ;; Get edits since last review
     (ctx/edits-since-last-review node)
     ;; => [{:edit/file \"...\" :edit/namespace :seon.foo} ...]

     ;; Record review completion
     (ctx/record-review! node #{\"/path/to/file.clj\"})"
  (:require [seon.db.node :as node]
            [seon.schema :as schema])
  (:import [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Absolute path to a file"}])

(schema/register! ::namespace
                  [:keyword {:description "Namespace keyword (e.g., :seon.foo)"}])

(schema/register! ::interval-seconds
                  [:int {:min 1
                         :description "Minimum seconds between reviews"}])

;; Hook decision
(schema/register! ::decision
                  [:enum {:description "Hook decision result"}
                   :continue :block])

;; Test result summary for storage
(schema/register! ::test-result-summary
                  [:map
                   [:success :boolean]
                   [:test-count {:optional true} :int]
                   [:pass-count {:optional true} :int]
                   [:fail-count {:optional true} :int]
                   [:error-count {:optional true} :int]
                   [:error {:optional true} :string]])

;; Enhanced edit event with observability fields
(schema/register! ::edit-event
                  [:map
                   [:xt/id :uuid]
                   [:entity/type [:= :edit-event]]
                   [:edit/file ::file-path]
                   [:edit/namespace {:optional true} ::namespace]
                   ;; Observability fields (all optional for backwards compat)
                   [:edit/content-hash {:optional true} :string]
                   [:edit/unit-test-result {:optional true} ::test-result-summary]
                   [:edit/gen-test-result {:optional true} ::test-result-summary]
                   [:edit/decision {:optional true} ::decision]
                   [:edit/reason {:optional true} :string]
                   [:edit/feedback {:optional true} [:vector :string]]])

;; Enhanced review event with Gemini interaction details
(schema/register! ::review-event
                  [:map
                   [:xt/id :uuid]
                   [:entity/type [:= :review-event]]
                   [:review/files [:set ::file-path]]
                   [:review/edit-count :int]
                   ;; Gemini interaction (optional)
                   [:review/gemini-prompt {:optional true} :string]
                   [:review/gemini-response {:optional true} :string]
                   [:review/gemini-tokens {:optional true}
                    [:map
                     [:prompt {:optional true} :int]
                     [:response {:optional true} :int]
                     [:cached {:optional true} :int]]]])

(schema/register! ::review-options
                  [:map
                   [::interval-seconds {:optional true} ::interval-seconds]])

(schema/register! ::edits-summary
                  [:map
                   [::files [:set ::file-path]]
                   [::namespaces [:set ::namespace]]
                   [::edit-count :int]])

;;; ---------------------------------------------------------------------------
;;; Forward Declarations
;;; ---------------------------------------------------------------------------

(declare edits-since-last-review)

;;; ---------------------------------------------------------------------------
;;; Time Utilities
;;; ---------------------------------------------------------------------------

(defn- instant->epoch-ms
  "Convert Instant or ZonedDateTime to epoch milliseconds."
  [t]
  (cond
    (instance? Instant t) (.toEpochMilli t)
    (nil? t) nil
    :else (.toEpochMilli (.toInstant t))))

(defn- seconds-between
  "Calculate seconds between two instants."
  [earlier later]
  (when (and earlier later)
    (/ (- (instant->epoch-ms later) (instant->epoch-ms earlier)) 1000.0)))

;;; ---------------------------------------------------------------------------
;;; Record Events
;;; ---------------------------------------------------------------------------

(defn record-edit!
  "Record an edit event for a file.

   Edit events are immutable facts stored in XTDB. The valid-time is set
   automatically to the current time by XTDB.

   Basic usage (positional args for backwards compat):
     (record-edit! node \"/path/to/file.clj\" 'seon.foo)

   Enhanced usage (with observability data):
     (record-edit! node \"/path/to/file.clj\" 'seon.foo
       {:content-hash \"sha256:abc123\"
        :unit-test-result {:success true :test-count 5}
        :gen-test-result {:success true}
        :decision :continue
        :feedback [\"5 tests passed\"]})

   Returns:
     Transaction result with :tx-id"
  ([xtdb-node file-path ns-sym]
   (record-edit! xtdb-node file-path ns-sym nil))
  ([xtdb-node file-path ns-sym opts]
   (let [{:keys [content-hash unit-test-result gen-test-result
                 decision reason feedback]} opts
         entity (cond-> {:xt/id (UUID/randomUUID)
                         :entity/type :edit-event
                         :edit/file file-path}
                  ns-sym (assoc :edit/namespace (keyword (str ns-sym)))
                  content-hash (assoc :edit/content-hash content-hash)
                  unit-test-result (assoc :edit/unit-test-result unit-test-result)
                  gen-test-result (assoc :edit/gen-test-result gen-test-result)
                  decision (assoc :edit/decision decision)
                  reason (assoc :edit/reason reason)
                  feedback (assoc :edit/feedback feedback))]
     (node/execute-tx! xtdb-node [[:put-docs :edit-event entity]]))))

(defn record-review!
  "Record that a review was completed.

   Records the files that were reviewed and the count of edits processed.

   Basic usage:
     (record-review! node #{\"/path/to/file.clj\"})

   Enhanced usage (with Gemini interaction data):
     (record-review! node #{\"/path/to/file.clj\"}
       {:gemini-prompt \"Review these changes...\"
        :gemini-response \"The code looks good...\"
        :gemini-tokens {:prompt 1500 :response 800 :cached 0}})

   Returns:
     Transaction result with :tx-id"
  ([xtdb-node files]
   (record-review! xtdb-node files nil))
  ([xtdb-node files opts]
   (let [{:keys [gemini-prompt gemini-response gemini-tokens]} opts
         edit-count (count (edits-since-last-review xtdb-node))
         entity (cond-> {:xt/id (UUID/randomUUID)
                         :entity/type :review-event
                         :review/files (set files)
                         :review/edit-count edit-count}
                  gemini-prompt (assoc :review/gemini-prompt gemini-prompt)
                  gemini-response (assoc :review/gemini-response gemini-response)
                  gemini-tokens (assoc :review/gemini-tokens gemini-tokens))]
     (node/execute-tx! xtdb-node [[:put-docs :review-event entity]]))))

;;; ---------------------------------------------------------------------------
;;; Query Timing
;;; ---------------------------------------------------------------------------

(defn get-last-review-time
  "Get the timestamp of the most recent review, or nil if never reviewed.

   Uses XTDB valid-time (_valid_from) as the authoritative timestamp.

   Returns:
     java.time.Instant or nil"
  [xtdb-node]
  (-> (node/sql-query
       xtdb-node
       "SELECT _valid_from FROM review_event ORDER BY _valid_from DESC LIMIT 1")
      first
      :xt/valid-from))

(defn get-last-edit-time
  "Get the timestamp of the most recent edit, or nil if no edits.

   Uses XTDB valid-time (_valid_from) as the authoritative timestamp.

   Returns:
     java.time.Instant or nil"
  [xtdb-node]
  (-> (node/sql-query
       xtdb-node
       "SELECT _valid_from FROM edit_event ORDER BY _valid_from DESC LIMIT 1")
      first
      :xt/valid-from))

;;; ---------------------------------------------------------------------------
;;; Query Edits
;;; ---------------------------------------------------------------------------

(defn edits-since-last-review
  "Get all edits since the last review.

   If no review has occurred, returns all edits.

   Returns:
     Vector of edit event maps, oldest first

   Example:
     (edits-since-last-review node)
     ;; => [{:edit/file \"/path/to/a.clj\" :edit/namespace :seon.a :xt/valid-from #inst \"...\"}
     ;;     {:edit/file \"/path/to/b.clj\" :edit/namespace :seon.b :xt/valid-from #inst \"...\"}]"
  [xtdb-node]
  (let [last-review (get-last-review-time xtdb-node)]
    (if last-review
      (node/sql-query
       xtdb-node
       ["SELECT *, _valid_from FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from"
        last-review])
      (node/sql-query
       xtdb-node
       "SELECT *, _valid_from FROM edit_event ORDER BY _valid_from"))))

(defn edits-summary
  "Get a summary of edits since last review.

   Returns a map suitable for building review context.

   Returns:
     {::files #{...file paths...}
      ::namespaces #{...namespace keywords...}
      ::edit-count N}"
  [xtdb-node]
  (let [edits (edits-since-last-review xtdb-node)]
    {::files (set (map :edit/file edits))
     ::namespaces (set (keep :edit/namespace edits))
     ::edit-count (count edits)}))

;;; ---------------------------------------------------------------------------
;;; Review Rate Limiting
;;; ---------------------------------------------------------------------------

(defn should-review?
  "Determine if a review should trigger now.

   Uses simple rate limiting: at most one review per interval-seconds.
   This is simpler and more reliable than debounce logic.

   Returns true if ALL conditions are met:
   1. There are edits since the last review
   2. Either: never reviewed, OR interval has passed since last review

   Request keys:
     node - XTDB node
     opts - Optional map with:
            ::interval-seconds - Minimum seconds between reviews (default: 60)

   Returns:
     Boolean - true if review should trigger

   Example:
     (should-review? node)
     ;; => true (if 60+ seconds since last review and edits exist)

     (should-review? node {::interval-seconds 30})
     ;; => true (if 30+ seconds since last review)"
  ([xtdb-node]
   (should-review? xtdb-node {}))
  ([xtdb-node opts]
   (let [interval-seconds (or (::interval-seconds opts) 60)
         last-review (get-last-review-time xtdb-node)
         last-edit (get-last-edit-time xtdb-node)
         now (Instant/now)]
     (and
      ;; 1. Have edits to review
      (some? last-edit)

      ;; 2. Have edits since last review (or never reviewed)
      (or (nil? last-review)
          (let [review-ms (instant->epoch-ms last-review)
                edit-ms (instant->epoch-ms last-edit)]
            (> edit-ms review-ms)))

      ;; 3. Interval has passed (or never reviewed)
      (or (nil? last-review)
          (>= (seconds-between last-review now) interval-seconds))))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all-events!
  "Clear all edit and review events. USE WITH CAUTION - for testing only.

   This deletes all events permanently using XTDB erase."
  [xtdb-node]
  (let [edit-ids (map :xt/id (node/sql-query xtdb-node "SELECT _id FROM edit_event"))
        review-ids (map :xt/id (node/sql-query xtdb-node "SELECT _id FROM review_event"))]
    (when (seq edit-ids)
      (node/execute-tx! xtdb-node
                        (mapv (fn [id] [:erase-docs :edit-event id]) edit-ids)))
    (when (seq review-ids)
      (node/execute-tx! xtdb-node
                        (mapv (fn [id] [:erase-docs :review-event id]) review-ids)))))

;;; ---------------------------------------------------------------------------
;;; Query Helpers for Analysis (Phase 7b Observability)
;;; ---------------------------------------------------------------------------

(defn edits-for-file
  "Get all edit events for a specific file.

   Returns vector of edit events, newest first.

   Example:
     (edits-for-file node \"/path/to/file.clj\")
     ;; => [{:edit/file \"...\" :edit/decision :continue ...} ...]"
  [xtdb-node file-path]
  (node/sql-query
   xtdb-node
   ["SELECT *, _valid_from FROM edit_event WHERE edit_file = ? ORDER BY _valid_from DESC"
    file-path]))

(defn reviews-in-range
  "Get review events in a time range.

   Arguments:
     xtdb-node  - XTDB node
     start-inst - Start time (Instant)
     end-inst   - End time (Instant), defaults to now

   Returns vector of review events, newest first."
  ([xtdb-node start-inst]
   (reviews-in-range xtdb-node start-inst (Instant/now)))
  ([xtdb-node start-inst end-inst]
   (node/sql-query
    xtdb-node
    ["SELECT *, _valid_from FROM review_event WHERE _valid_from >= ? AND _valid_from <= ? ORDER BY _valid_from DESC"
     start-inst end-inst])))

(defn failure-rate
  "Calculate the percentage of edits that resulted in blocks.

   Returns map with:
     :total - Total edit count
     :blocked - Number of blocked edits
     :rate - Failure rate as decimal (0.0 - 1.0)

   Example:
     (failure-rate node)
     ;; => {:total 100 :blocked 5 :rate 0.05}"
  [xtdb-node]
  (let [total-result (node/sql-query
                      xtdb-node
                      "SELECT COUNT(*) as cnt FROM edit_event")
        blocked-result (node/sql-query
                        xtdb-node
                        "SELECT COUNT(*) as cnt FROM edit_event WHERE edit_decision = 'block'")
        total (or (:cnt (first total-result)) 0)
        blocked (or (:cnt (first blocked-result)) 0)]
    {:total total
     :blocked blocked
     :rate (if (pos? total)
             (double (/ blocked total))
             0.0)}))

(defn gemini-token-usage
  "Get total Gemini token usage in a time period.

   Arguments:
     xtdb-node  - XTDB node
     start-inst - Start time (Instant)
     end-inst   - End time (Instant), defaults to now

   Returns map with:
     :prompt-tokens - Total prompt tokens
     :response-tokens - Total response tokens
     :cached-tokens - Total cached tokens
     :review-count - Number of reviews"
  ([xtdb-node start-inst]
   (gemini-token-usage xtdb-node start-inst (Instant/now)))
  ([xtdb-node start-inst end-inst]
   (let [reviews (reviews-in-range xtdb-node start-inst end-inst)
         tokens (keep :review/gemini-tokens reviews)]
     {:prompt-tokens (reduce + 0 (keep :prompt tokens))
      :response-tokens (reduce + 0 (keep :response tokens))
      :cached-tokens (reduce + 0 (keep :cached tokens))
      :review-count (count reviews)})))

(defn recent-activity
  "Get summary of recent hook activity.

   Arguments:
     xtdb-node - XTDB node
     hours     - Hours to look back (default: 1)

   Returns map with edit count, review count, failure rate, tokens used."
  ([xtdb-node]
   (recent-activity xtdb-node 1))
  ([xtdb-node hours]
   (let [start-inst (.minus (Instant/now) (java.time.Duration/ofHours hours))
         edits (node/sql-query
                xtdb-node
                ["SELECT *, _valid_from FROM edit_event WHERE _valid_from >= ? ORDER BY _valid_from DESC"
                 start-inst])
         reviews (reviews-in-range xtdb-node start-inst)
         blocked (count (filter #(= :block (:edit/decision %)) edits))
         tokens (gemini-token-usage xtdb-node start-inst)]
     {:period-hours hours
      :edit-count (count edits)
      :review-count (count reviews)
      :blocked-count blocked
      :failure-rate (if (pos? (count edits))
                      (double (/ blocked (count edits)))
                      0.0)
      :gemini-tokens tokens})))

(comment
  ;; REPL exploration

  ;; Start with a test node (requires running server)
  (require '[seon.dev.context :as ctx])
  (def node (user/xtdb-node))

  ;; Record some edits
  (ctx/record-edit! node "/tmp/test.clj" 'seon.test)
  (ctx/record-edit! node "/tmp/other.clj" 'seon.other)

  ;; Check timing
  (ctx/get-last-edit-time node)
  (ctx/get-last-review-time node)  ; nil initially

  ;; Should we review?
  (ctx/should-review? node)  ; true - never reviewed

  ;; Get edits
  (ctx/edits-since-last-review node)
  (ctx/edits-summary node)

  ;; Record review completion
  (ctx/record-review! node #{"/tmp/test.clj" "/tmp/other.clj"})

  ;; Now should-review? returns false (just reviewed)
  (ctx/should-review? node)  ; false

  ;; With shorter interval, can test timing
  (ctx/should-review? node {::interval-seconds 1})  ; true after 1 second

  nil)
