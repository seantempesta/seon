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
     (ctx/record-edit! {::ctx/xtdb-node node
                        ::ctx/file-path \"/path/to/file.clj\"
                        ::ctx/namespace 'seon.foo})

     ;; Check if we should review
     (ctx/should-review? {::ctx/xtdb-node node})
     ;; => {::should-review true}

     ;; Get edits since last review
     (ctx/edits-since-last-review {::ctx/xtdb-node node})
     ;; => {::edits [{::file \"/path/a.clj\" ::namespace :seon.a} ...]}

     ;; Record review completion
     (ctx/record-review! {::ctx/xtdb-node node
                          ::ctx/files #{\"/path/to/file.clj\"}})"
  (:require [seon.db.node :as node]
            [seon.schema :as schema])
  (:import [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

;; Primitive types
(schema/register! ::xtdb-node
                  [:any {:description "XTDB node instance"}])

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Absolute path to a file"}])

(schema/register! ::namespace
                  [:keyword {:description "Namespace keyword (e.g., :seon.foo)"}])

(schema/register! ::namespace-symbol
                  [:symbol {:description "Namespace symbol (e.g., seon.foo)"}])

(schema/register! ::interval-seconds
                  [:int {:min 0
                         :description "Minimum seconds between reviews"}])

(schema/register! ::hours
                  [:int {:min 1
                         :description "Number of hours to look back"}])

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

;; Gemini token data
(schema/register! ::gemini-tokens
                  [:map
                   [:prompt {:optional true} [:maybe :int]]
                   [:response {:optional true} [:maybe :int]]
                   [:cached {:optional true} [:maybe :int]]])

;; Content hash
(schema/register! ::content-hash
                  [:string {:description "SHA256 hash of file content"}])

;; Reason for decision
(schema/register! ::reason
                  [:string {:description "Reason for the decision"}])

;; Feedback messages
(schema/register! ::feedback
                  [:vector :string])

;; Files set
(schema/register! ::files
                  [:set ::file-path])

;; Namespaces set
(schema/register! ::namespaces
                  [:set ::namespace])

;; Edit count
(schema/register! ::edit-count
                  [:int {:min 0}])

;; Timestamp
(schema/register! ::timestamp
                  [:any {:description "java.time.Instant or ZonedDateTime"}])

;; Transaction result
(schema/register! ::tx-id
                  [:int {:min 0}])

;; Enhanced edit event with observability fields
;; All keys are fully namespaced (::key expands to :seon.dev.context/key)
(schema/register! ::edit-event
                  [:map
                   [:xt/id :uuid]
                   [::entity-type [:= :edit-event]]
                   [::file ::file-path]
                   [::namespace {:optional true} ::namespace]
                   ;; Observability fields (all optional for backwards compat)
                   [::content-hash {:optional true} ::content-hash]
                   [::unit-test-result {:optional true} ::test-result-summary]
                   [::gen-test-result {:optional true} ::test-result-summary]
                   [::decision {:optional true} ::decision]
                   [::reason {:optional true} ::reason]
                   [::feedback {:optional true} ::feedback]])

;; Enhanced review event with full Gemini interaction for training data
;; All keys are fully namespaced (::key expands to :seon.dev.context/key)
(schema/register! ::review-event
                  [:map
                   [:xt/id :uuid]
                   [::entity-type [:= :review-event]]
                   [::files ::files]
                   [::edit-count ::edit-count]
                   ;; Gemini interaction data for LLM training
                   [::gemini-prompt {:optional true} [:maybe :string]]
                   [::gemini-response {:optional true} [:maybe :string]]
                   [::gemini-system-instruction {:optional true} [:maybe :string]]
                   [::gemini-code {:optional true} [:maybe :string]]
                   [::gemini-tokens {:optional true} [:maybe ::gemini-tokens]]])

;;; ---------------------------------------------------------------------------
;;; Request/Response Schemas
;;; ---------------------------------------------------------------------------

;; record-edit! schemas
(schema/register! ::record-edit-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::file-path ::file-path]
                   [::namespace {:optional true} [:maybe ::namespace-symbol]]
                   [::content-hash {:optional true} ::content-hash]
                   [::unit-test-result {:optional true} ::test-result-summary]
                   [::gen-test-result {:optional true} ::test-result-summary]
                   [::decision {:optional true} ::decision]
                   [::reason {:optional true} ::reason]
                   [::feedback {:optional true} ::feedback]])

(schema/register! ::record-edit-response
                  [:map
                   [::success :boolean]
                   [::tx-id {:optional true} ::tx-id]])

;; record-review! schemas
(schema/register! ::record-review-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::files ::files]
                   [::gemini-prompt {:optional true} [:maybe :string]]
                   [::gemini-response {:optional true} [:maybe :string]]
                   [::gemini-system-instruction {:optional true} [:maybe :string]]
                   [::gemini-code {:optional true} [:maybe :string]]
                   [::gemini-tokens {:optional true} [:maybe ::gemini-tokens]]])

(schema/register! ::record-review-response
                  [:map
                   [::success :boolean]
                   [::tx-id {:optional true} ::tx-id]])

;; get-last-review-time schemas
(schema/register! ::get-last-review-time-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::get-last-review-time-response
                  [:map
                   [::timestamp {:optional true} [:maybe ::timestamp]]])

;; get-last-edit-time schemas
(schema/register! ::get-last-edit-time-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::get-last-edit-time-response
                  [:map
                   [::timestamp {:optional true} [:maybe ::timestamp]]])

;; edits-since-last-review schemas
(schema/register! ::edits-since-last-review-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::edits-since-last-review-response
                  [:map
                   [::edits [:vector :map]]])

;; edits-summary schemas
(schema/register! ::edits-summary-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::edits-summary-response
                  [:map
                   [::files ::files]
                   [::namespaces ::namespaces]
                   [::edit-count ::edit-count]])

;; should-review? schemas
(schema/register! ::should-review-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::interval-seconds {:optional true} ::interval-seconds]])

(schema/register! ::should-review-response
                  [:map
                   [::should-review :boolean]])

;; clear-all-events! schemas
(schema/register! ::clear-all-events-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::clear-all-events-response
                  [:map
                   [::success :boolean]])

;; edits-for-file schemas
(schema/register! ::edits-for-file-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::file-path ::file-path]])

(schema/register! ::edits-for-file-response
                  [:map
                   [::edits [:vector :map]]])

;; reviews-in-range schemas
(schema/register! ::reviews-in-range-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::start-instant ::timestamp]
                   [::end-instant {:optional true} ::timestamp]])

(schema/register! ::reviews-in-range-response
                  [:map
                   [::reviews [:vector :map]]])

;; failure-rate schemas
(schema/register! ::failure-rate-request
                  [:map
                   [::xtdb-node ::xtdb-node]])

(schema/register! ::failure-rate-response
                  [:map
                   [::total ::edit-count]
                   [::blocked ::edit-count]
                   [::rate :double]])

;; gemini-token-usage schemas
(schema/register! ::gemini-token-usage-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::start-instant ::timestamp]
                   [::end-instant {:optional true} ::timestamp]])

(schema/register! ::gemini-token-usage-response
                  [:map
                   [::prompt-tokens :int]
                   [::response-tokens :int]
                   [::cached-tokens :int]
                   [::review-count :int]])

;; recent-activity schemas
(schema/register! ::recent-activity-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::hours {:optional true} ::hours]])

(schema/register! ::recent-activity-response
                  [:map
                   [::period-hours ::hours]
                   [::edit-count ::edit-count]
                   [::review-count :int]
                   [::blocked-count :int]
                   [::failure-rate :double]
                   [::gemini-tokens ::gemini-token-usage-response]])

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
;;; Forward Declarations
;;; ---------------------------------------------------------------------------

(declare edits-since-last-review)
(declare get-last-review-time)
(declare reviews-in-range)
(declare gemini-token-usage)

;;; ---------------------------------------------------------------------------
;;; Record Events
;;; ---------------------------------------------------------------------------

(defn record-edit!
  "Record an edit event for a file.

   Edit events are immutable facts stored in XTDB. The valid-time is set
   automatically to the current time by XTDB.

   Request keys:
     ::xtdb-node        - Required. XTDB node instance
     ::file-path        - Required. Absolute path to the edited file
     ::namespace        - Optional. Namespace symbol (e.g., 'seon.foo)
     ::content-hash     - Optional. SHA256 hash of file content
     ::unit-test-result - Optional. Unit test result summary
     ::gen-test-result  - Optional. Generative test result summary
     ::decision         - Optional. Hook decision (:continue or :block)
     ::reason           - Optional. Reason for the decision
     ::feedback         - Optional. Vector of feedback messages

   Response keys:
     ::success - Boolean indicating if transaction succeeded
     ::tx-id   - Transaction ID if successful

   Example:
     (record-edit! {::xtdb-node node
                    ::file-path \"/path/to/file.clj\"
                    ::namespace 'seon.foo
                    ::decision :continue})"
  {:malli/schema [:=> [:cat ::record-edit-request] ::record-edit-response]}
  [{::keys [xtdb-node file-path namespace content-hash unit-test-result
            gen-test-result decision reason feedback]}]
  (let [entity (cond-> {:xt/id (UUID/randomUUID)
                        ::entity-type :edit-event
                        ::file file-path}
                 namespace (assoc ::namespace (keyword (str namespace)))
                 content-hash (assoc ::content-hash content-hash)
                 unit-test-result (assoc ::unit-test-result unit-test-result)
                 gen-test-result (assoc ::gen-test-result gen-test-result)
                 decision (assoc ::decision decision)
                 reason (assoc ::reason reason)
                 feedback (assoc ::feedback feedback))
        result (node/execute-tx! xtdb-node [[:put-docs :edit-event entity]])]
    {::success (some? result)
     ::tx-id (:tx-id result)}))

(defn record-review!
  "Record that a review was completed.

   Records the files that were reviewed and the count of edits processed.
   Optionally stores full Gemini interaction data for LLM training.

   Request keys:
     ::xtdb-node                 - Required. XTDB node instance
     ::files                     - Required. Set of reviewed file paths
     ::gemini-prompt             - Optional. The prompt sent to Gemini
     ::gemini-response           - Optional. The response from Gemini
     ::gemini-system-instruction - Optional. The system instruction used
     ::gemini-code               - Optional. The code that was reviewed
     ::gemini-tokens             - Optional. Token usage map {:prompt N :response N :cached N}

   Response keys:
     ::success - Boolean indicating if transaction succeeded
     ::tx-id   - Transaction ID if successful

   Example:
     (record-review! {::xtdb-node node
                      ::files #{\"/path/to/file.clj\"}
                      ::gemini-prompt \"Review these changes...\"
                      ::gemini-response \"The code looks good...\"})"
  {:malli/schema [:=> [:cat ::record-review-request] ::record-review-response]}
  [{::keys [xtdb-node files gemini-prompt gemini-response
            gemini-system-instruction gemini-code gemini-tokens]}]
  (let [edit-count (count (::edits (edits-since-last-review {::xtdb-node xtdb-node})))
        entity (cond-> {:xt/id (UUID/randomUUID)
                        ::entity-type :review-event
                        ::files (set files)
                        ::edit-count edit-count}
                 gemini-prompt (assoc ::gemini-prompt gemini-prompt)
                 gemini-response (assoc ::gemini-response gemini-response)
                 gemini-system-instruction (assoc ::gemini-system-instruction gemini-system-instruction)
                 gemini-code (assoc ::gemini-code gemini-code)
                 gemini-tokens (assoc ::gemini-tokens gemini-tokens))
        result (node/execute-tx! xtdb-node [[:put-docs :review-event entity]])]
    {::success (some? result)
     ::tx-id (:tx-id result)}))

;;; ---------------------------------------------------------------------------
;;; Query Timing
;;; ---------------------------------------------------------------------------

(defn get-last-review-time
  "Get the timestamp of the most recent review, or nil if never reviewed.

   Uses XTDB valid-time (_valid_from) as the authoritative timestamp.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::timestamp - java.time.Instant or ZonedDateTime, nil if never reviewed

   Example:
     (get-last-review-time {::xtdb-node node})
     ;; => {::timestamp #inst \"2024-01-15T12:00:00Z\"}"
  {:malli/schema [:=> [:cat ::get-last-review-time-request] ::get-last-review-time-response]}
  [{::keys [xtdb-node]}]
  {::timestamp (-> (node/sql-query
                    xtdb-node
                    "SELECT _valid_from FROM review_event ORDER BY _valid_from DESC LIMIT 1")
                   first
                   :xt/valid-from)})

(defn get-last-edit-time
  "Get the timestamp of the most recent edit, or nil if no edits.

   Uses XTDB valid-time (_valid_from) as the authoritative timestamp.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::timestamp - java.time.Instant or ZonedDateTime, nil if no edits

   Example:
     (get-last-edit-time {::xtdb-node node})
     ;; => {::timestamp #inst \"2024-01-15T12:00:00Z\"}"
  {:malli/schema [:=> [:cat ::get-last-edit-time-request] ::get-last-edit-time-response]}
  [{::keys [xtdb-node]}]
  {::timestamp (-> (node/sql-query
                    xtdb-node
                    "SELECT _valid_from FROM edit_event ORDER BY _valid_from DESC LIMIT 1")
                   first
                   :xt/valid-from)})

;;; ---------------------------------------------------------------------------
;;; Query Edits
;;; ---------------------------------------------------------------------------

(defn edits-since-last-review
  "Get all edits since the last review.

   If no review has occurred, returns all edits.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::edits - Vector of edit event maps, oldest first

   Example:
     (edits-since-last-review {::xtdb-node node})
     ;; => {::edits [{::file \"/path/to/a.clj\" ::namespace :seon.a} ...]}"
  {:malli/schema [:=> [:cat ::edits-since-last-review-request] ::edits-since-last-review-response]}
  [{::keys [xtdb-node]}]
  (let [last-review (::timestamp (get-last-review-time {::xtdb-node xtdb-node}))]
    {::edits (vec (if last-review
                    (node/sql-query
                     xtdb-node
                     ["SELECT *, _valid_from FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from"
                      last-review])
                    (node/sql-query
                     xtdb-node
                     "SELECT *, _valid_from FROM edit_event ORDER BY _valid_from")))}))

(defn edits-summary
  "Get a summary of edits since last review.

   Returns a map suitable for building review context.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::files      - Set of file paths that were edited
     ::namespaces - Set of namespace keywords
     ::edit-count - Total number of edit events

   Example:
     (edits-summary {::xtdb-node node})
     ;; => {::files #{\"a.clj\" \"b.clj\"} ::namespaces #{:seon.a} ::edit-count 5}"
  {:malli/schema [:=> [:cat ::edits-summary-request] ::edits-summary-response]}
  [{::keys [xtdb-node]}]
  (let [edits (::edits (edits-since-last-review {::xtdb-node xtdb-node}))]
    {::files (set (map ::file edits))
     ::namespaces (set (keep ::namespace edits))
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
     ::xtdb-node        - Required. XTDB node instance
     ::interval-seconds - Optional. Minimum seconds between reviews (default: 60)

   Response keys:
     ::should-review - Boolean indicating if review should trigger

   Example:
     (should-review? {::xtdb-node node})
     ;; => {::should-review true}

     (should-review? {::xtdb-node node ::interval-seconds 30})
     ;; => {::should-review true}"
  {:malli/schema [:=> [:cat ::should-review-request] ::should-review-response]}
  [{::keys [xtdb-node interval-seconds]}]
  (let [interval-seconds (or interval-seconds 60)
        last-review (::timestamp (get-last-review-time {::xtdb-node xtdb-node}))
        last-edit (::timestamp (get-last-edit-time {::xtdb-node xtdb-node}))
        now (Instant/now)]
    {::should-review
     (boolean
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
           (>= (seconds-between last-review now) interval-seconds))))}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all-events!
  "Clear all edit and review events. USE WITH CAUTION - for testing only.

   This deletes all events permanently using XTDB erase.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::success - Boolean indicating if operation completed

   Example:
     (clear-all-events! {::xtdb-node node})
     ;; => {::success true}"
  {:malli/schema [:=> [:cat ::clear-all-events-request] ::clear-all-events-response]}
  [{::keys [xtdb-node]}]
  (let [edit-ids (map :xt/id (node/sql-query xtdb-node "SELECT _id FROM edit_event"))
        review-ids (map :xt/id (node/sql-query xtdb-node "SELECT _id FROM review_event"))]
    (when (seq edit-ids)
      (node/execute-tx! xtdb-node
                        (mapv (fn [id] [:erase-docs :edit-event id]) edit-ids)))
    (when (seq review-ids)
      (node/execute-tx! xtdb-node
                        (mapv (fn [id] [:erase-docs :review-event id]) review-ids)))
    {::success true}))

;;; ---------------------------------------------------------------------------
;;; Query Helpers for Analysis (Phase 7b Observability)
;;; ---------------------------------------------------------------------------

(defn edits-for-file
  "Get all edit events for a specific file.

   Returns vector of edit events, newest first.

   Request keys:
     ::xtdb-node - Required. XTDB node instance
     ::file-path - Required. Path to the file

   Response keys:
     ::edits - Vector of edit event maps, newest first

   Example:
     (edits-for-file {::xtdb-node node ::file-path \"/path/to/file.clj\"})
     ;; => {::edits [{::file \"...\" ::decision :continue ...} ...]}"
  {:malli/schema [:=> [:cat ::edits-for-file-request] ::edits-for-file-response]}
  [{::keys [xtdb-node file-path]}]
  {::edits (vec (node/sql-query
                 xtdb-node
                 ["SELECT *, _valid_from FROM edit_event WHERE seon$dev$context$file = ? ORDER BY _valid_from DESC"
                  file-path]))})

(defn reviews-in-range
  "Get review events in a time range.

   Request keys:
     ::xtdb-node     - Required. XTDB node instance
     ::start-instant - Required. Start time (Instant)
     ::end-instant   - Optional. End time (Instant), defaults to now

   Response keys:
     ::reviews - Vector of review event maps, newest first

   Example:
     (reviews-in-range {::xtdb-node node
                        ::start-instant (.minus (Instant/now) (Duration/ofHours 1))})"
  {:malli/schema [:=> [:cat ::reviews-in-range-request] ::reviews-in-range-response]}
  [{::keys [xtdb-node start-instant end-instant]}]
  (let [end-instant (or end-instant (Instant/now))]
    {::reviews (vec (node/sql-query
                     xtdb-node
                     ["SELECT *, _valid_from FROM review_event WHERE _valid_from >= ? AND _valid_from <= ? ORDER BY _valid_from DESC"
                      start-instant end-instant]))}))

(defn failure-rate
  "Calculate the percentage of edits that resulted in blocks.

   Request keys:
     ::xtdb-node - Required. XTDB node instance

   Response keys:
     ::total   - Total edit count
     ::blocked - Number of blocked edits
     ::rate    - Failure rate as decimal (0.0 - 1.0)

   Example:
     (failure-rate {::xtdb-node node})
     ;; => {::total 100 ::blocked 5 ::rate 0.05}"
  {:malli/schema [:=> [:cat ::failure-rate-request] ::failure-rate-response]}
  [{::keys [xtdb-node]}]
  (let [total-result (node/sql-query
                      xtdb-node
                      "SELECT COUNT(*) as cnt FROM edit_event")
        blocked-result (node/sql-query
                        xtdb-node
                        "SELECT COUNT(*) as cnt FROM edit_event WHERE seon$dev$context$decision = 'block'")
        total (or (:cnt (first total-result)) 0)
        blocked (or (:cnt (first blocked-result)) 0)]
    {::total total
     ::blocked blocked
     ::rate (if (pos? total)
              (double (/ blocked total))
              0.0)}))

(defn gemini-token-usage
  "Get total Gemini token usage in a time period.

   Request keys:
     ::xtdb-node     - Required. XTDB node instance
     ::start-instant - Required. Start time (Instant)
     ::end-instant   - Optional. End time (Instant), defaults to now

   Response keys:
     ::prompt-tokens   - Total prompt tokens
     ::response-tokens - Total response tokens
     ::cached-tokens   - Total cached tokens
     ::review-count    - Number of reviews

   Example:
     (gemini-token-usage {::xtdb-node node
                          ::start-instant (.minus (Instant/now) (Duration/ofHours 24))})"
  {:malli/schema [:=> [:cat ::gemini-token-usage-request] ::gemini-token-usage-response]}
  [{::keys [xtdb-node start-instant end-instant]}]
  (let [reviews (::reviews (reviews-in-range {::xtdb-node xtdb-node
                                               ::start-instant start-instant
                                               ::end-instant end-instant}))
        tokens (keep ::gemini-tokens reviews)]
    {::prompt-tokens (reduce + 0 (keep :prompt tokens))
     ::response-tokens (reduce + 0 (keep :response tokens))
     ::cached-tokens (reduce + 0 (keep :cached tokens))
     ::review-count (count reviews)}))

(defn recent-activity
  "Get summary of recent hook activity.

   Request keys:
     ::xtdb-node - Required. XTDB node instance
     ::hours     - Optional. Hours to look back (default: 1)

   Response keys:
     ::period-hours  - Hours covered by this summary
     ::edit-count    - Total edits in period
     ::review-count  - Total reviews in period
     ::blocked-count - Number of blocked edits
     ::failure-rate  - Percentage of blocked edits (0.0 - 1.0)
     ::gemini-tokens - Token usage summary

   Example:
     (recent-activity {::xtdb-node node ::hours 24})"
  {:malli/schema [:=> [:cat ::recent-activity-request] ::recent-activity-response]}
  [{::keys [xtdb-node hours]}]
  (let [hours (or hours 1)
        start-inst (.minus (Instant/now) (java.time.Duration/ofHours hours))
        edits (node/sql-query
               xtdb-node
               ["SELECT *, _valid_from FROM edit_event WHERE _valid_from >= ? ORDER BY _valid_from DESC"
                start-inst])
        reviews (::reviews (reviews-in-range {::xtdb-node xtdb-node
                                               ::start-instant start-inst}))
        blocked (count (filter #(= :block (::decision %)) edits))
        tokens (gemini-token-usage {::xtdb-node xtdb-node
                                     ::start-instant start-inst})]
    {::period-hours hours
     ::edit-count (count edits)
     ::review-count (count reviews)
     ::blocked-count blocked
     ::failure-rate (if (pos? (count edits))
                      (double (/ blocked (count edits)))
                      0.0)
     ::gemini-tokens tokens}))

(comment
  ;; REPL exploration

  ;; Start with a test node (requires running server)
  (require '[seon.dev.context :as ctx])
  (def node (user/xtdb-node))

  ;; Record some edits
  (record-edit! {::xtdb-node node
                 ::file-path "/tmp/test.clj"
                 ::namespace 'seon.test})
  (record-edit! {::xtdb-node node
                 ::file-path "/tmp/other.clj"
                 ::namespace 'seon.other})

  ;; Check timing
  (get-last-edit-time {::xtdb-node node})
  (get-last-review-time {::xtdb-node node})  ; {::timestamp nil} initially

  ;; Should we review?
  (should-review? {::xtdb-node node})  ; {::should-review true} - never reviewed

  ;; Get edits
  (edits-since-last-review {::xtdb-node node})
  (edits-summary {::xtdb-node node})

  ;; Record review completion
  (record-review! {::xtdb-node node
                   ::files #{"/tmp/test.clj" "/tmp/other.clj"}})

  ;; Now should-review? returns false (just reviewed)
  (should-review? {::xtdb-node node})  ; {::should-review false}

  ;; With shorter interval, can test timing
  (should-review? {::xtdb-node node ::interval-seconds 1})

  nil)
