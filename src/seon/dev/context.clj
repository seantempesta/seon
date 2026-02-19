(ns seon.dev.context
  "Agent context tracking for development feedback.

   Maintains context of edits and reviews for the development hook:
   - Records edit events (in-memory, ephemeral per dev session)
   - Records review completion events
   - Provides simple rate-limited review triggering

   This replaces the complex debounce logic with straightforward rate limiting:
   at most one review per N seconds.

   Events are stored in-memory atoms. They are ephemeral and reset on server
   restart, which is appropriate since they only matter within a single dev
   session for rate-limiting AI reviews and tracking recent edits.

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
  (:require [seon.schema :as schema])
  (:import [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

;; Primitive types
(schema/register! ::xtdb-node
                  [:any {:description "XTDB node instance (accepted but unused - kept for API compat)"}])

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
(schema/register! ::edit-event
                  [:map
                   [:xt/id :uuid]
                   [::entity-type [:= :edit-event]]
                   [::file ::file-path]
                   [::namespace {:optional true} ::namespace]
                   [::content-hash {:optional true} ::content-hash]
                   [::unit-test-result {:optional true} ::test-result-summary]
                   [::gen-test-result {:optional true} ::test-result-summary]
                   [::decision {:optional true} ::decision]
                   [::reason {:optional true} ::reason]
                   [::feedback {:optional true} ::feedback]])

;; Enhanced review event with full Gemini interaction for training data
(schema/register! ::review-event
                  [:map
                   [:xt/id :uuid]
                   [::entity-type [:= :review-event]]
                   [::files ::files]
                   [::edit-count ::edit-count]
                   [::gemini-prompt {:optional true} [:maybe :string]]
                   [::gemini-response {:optional true} [:maybe :string]]
                   [::gemini-system-instruction {:optional true} [:maybe :string]]
                   [::gemini-code {:optional true} [:maybe :string]]
                   [::gemini-tokens {:optional true} [:maybe ::gemini-tokens]]])

;; Todo item from TodoWrite tool
(schema/register! ::todo-item
                  [:map
                   [:content :string]
                   [:status [:enum "pending" "in_progress" "completed"]]
                   [:activeForm :string]])

;; Todo event
(schema/register! ::todo-event
                  [:map
                   [:xt/id :uuid]
                   [::entity-type [:= :todo-event]]
                   [::session-id :string]
                   [::todos [:vector ::todo-item]]])

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

;; record-todos! schemas
(schema/register! ::record-todos-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::session-id :string]
                   [::todos [:vector ::todo-item]]])

(schema/register! ::record-todos-response
                  [:map
                   [::success :boolean]
                   [::tx-id {:optional true} ::tx-id]])

;; latest-todos schemas
(schema/register! ::latest-todos-request
                  [:map
                   [::xtdb-node ::xtdb-node]
                   [::session-id :string]])

(schema/register! ::latest-todos-response
                  [:map
                   [::todos {:optional true} [:maybe [:vector ::todo-item]]]
                   [::timestamp {:optional true} [:maybe ::timestamp]]])

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
;;; In-Memory Storage
;;; ---------------------------------------------------------------------------

;; Events are stored in-memory atoms, sorted by timestamp.
;; These are ephemeral - they reset on server restart, which is appropriate
;; since they only matter for rate-limiting within a single dev session.

(defonce ^:private edit-events (atom []))
(defonce ^:private review-events (atom []))
(defonce ^:private todo-events (atom []))

;; Monotonic counter for tx-id simulation
(defonce ^:private tx-counter (atom 0))

(defn- next-tx-id []
  (swap! tx-counter inc))

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

   Edit events are stored in-memory. The timestamp is set to the current time.

   Request keys:
     ::xtdb-node        - Accepted but unused (kept for API compat)
     ::file-path        - Required. Absolute path to the edited file
     ::namespace        - Optional. Namespace symbol (e.g., 'seon.foo)
     ::content-hash     - Optional. SHA256 hash of file content
     ::unit-test-result - Optional. Unit test result summary
     ::gen-test-result  - Optional. Generative test result summary
     ::decision         - Optional. Hook decision (:continue or :block)
     ::reason           - Optional. Reason for the decision
     ::feedback         - Optional. Vector of feedback messages

   Response keys:
     ::success - Boolean indicating if event was recorded
     ::tx-id   - Synthetic transaction ID

   Example:
     (record-edit! {::xtdb-node node
                    ::file-path \"/path/to/file.clj\"
                    ::namespace 'seon.foo
                    ::decision :continue})"
  {:malli/schema [:=> [:cat ::record-edit-request] ::record-edit-response]}
  [{::keys [_xtdb-node file-path namespace content-hash unit-test-result
            gen-test-result decision reason feedback]}]
  (let [id (UUID/randomUUID)
        now (Instant/now)
        ns-kw (when namespace (keyword (str namespace)))
        tx-id (next-tx-id)
        event {:xt/id id
               ::entity-type :edit-event
               ::file file-path
               ::namespace ns-kw
               ::content-hash content-hash
               ::unit-test-result unit-test-result
               ::gen-test-result gen-test-result
               ::decision decision
               ::reason reason
               ::feedback feedback
               :xt/valid-from now}]
    (swap! edit-events conj event)
    {::success true
     ::tx-id tx-id}))

(defn record-review!
  "Record that a review was completed.

   Records the files that were reviewed and the count of edits processed.
   Optionally stores full Gemini interaction data for LLM training.

   Request keys:
     ::xtdb-node                 - Accepted but unused (kept for API compat)
     ::files                     - Required. Set of reviewed file paths
     ::gemini-prompt             - Optional. The prompt sent to Gemini
     ::gemini-response           - Optional. The response from Gemini
     ::gemini-system-instruction - Optional. The system instruction used
     ::gemini-code               - Optional. The code that was reviewed
     ::gemini-tokens             - Optional. Token usage map {:prompt N :response N :cached N}

   Response keys:
     ::success - Boolean indicating if event was recorded
     ::tx-id   - Synthetic transaction ID

   Example:
     (record-review! {::xtdb-node node
                      ::files #{\"/path/to/file.clj\"}
                      ::gemini-prompt \"Review these changes...\"
                      ::gemini-response \"The code looks good...\"})"
  {:malli/schema [:=> [:cat ::record-review-request] ::record-review-response]}
  [{::keys [_xtdb-node files gemini-prompt gemini-response
            gemini-system-instruction gemini-code gemini-tokens]}]
  (let [id (UUID/randomUUID)
        now (Instant/now)
        tx-id (next-tx-id)
        files-set (set files)
        edit-count (count (::edits (edits-since-last-review {::xtdb-node nil})))
        event {:xt/id id
               ::entity-type :review-event
               ::files files-set
               ::edit-count edit-count
               ::gemini-prompt gemini-prompt
               ::gemini-response gemini-response
               ::gemini-system-instruction gemini-system-instruction
               ::gemini-code gemini-code
               ::gemini-tokens gemini-tokens
               :xt/valid-from now}]
    (swap! review-events conj event)
    {::success true
     ::tx-id tx-id}))

(defn record-todos!
  "Record an agent's todo list snapshot.

   Todo events capture the agent's current todo list at a point in time,
   enabling widgets to show task progress.

   Request keys:
     ::xtdb-node  - Accepted but unused (kept for API compat)
     ::session-id - Required. Agent session ID (e.g., 'a1b2')
     ::todos      - Required. Vector of todo items

   Response keys:
     ::success - Boolean indicating if event was recorded
     ::tx-id   - Synthetic transaction ID

   Example:
     (record-todos! {::xtdb-node node
                     ::session-id \"a1b2\"
                     ::todos [{:content \"Fix bug\" :status \"completed\" :activeForm \"Fixing bug\"}
                              {:content \"Add tests\" :status \"in_progress\" :activeForm \"Adding tests\"}]})"
  {:malli/schema [:=> [:cat ::record-todos-request] ::record-todos-response]}
  [{::keys [_xtdb-node session-id todos]}]
  (let [id (UUID/randomUUID)
        now (Instant/now)
        tx-id (next-tx-id)
        event {:xt/id id
               ::entity-type :todo-event
               ::session-id session-id
               ::todos todos
               :xt/valid-from now}]
    (swap! todo-events conj event)
    {::success true
     ::tx-id tx-id}))

;;; ---------------------------------------------------------------------------
;;; Query Timing
;;; ---------------------------------------------------------------------------

(defn get-last-review-time
  "Get the timestamp of the most recent review, or nil if never reviewed.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::timestamp - java.time.Instant, nil if never reviewed

   Example:
     (get-last-review-time {::xtdb-node node})
     ;; => {::timestamp #inst \"2024-01-15T12:00:00Z\"}"
  {:malli/schema [:=> [:cat ::get-last-review-time-request] ::get-last-review-time-response]}
  [{::keys [_xtdb-node]}]
  {::timestamp (when-let [events (seq @review-events)]
                 (:xt/valid-from (last events)))})

(defn get-last-edit-time
  "Get the timestamp of the most recent edit, or nil if no edits.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::timestamp - java.time.Instant, nil if no edits

   Example:
     (get-last-edit-time {::xtdb-node node})
     ;; => {::timestamp #inst \"2024-01-15T12:00:00Z\"}"
  {:malli/schema [:=> [:cat ::get-last-edit-time-request] ::get-last-edit-time-response]}
  [{::keys [_xtdb-node]}]
  {::timestamp (when-let [events (seq @edit-events)]
                 (:xt/valid-from (last events)))})

;;; ---------------------------------------------------------------------------
;;; Query Edits
;;; ---------------------------------------------------------------------------

(defn edits-since-last-review
  "Get all edits since the last review.

   If no review has occurred, returns all edits.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::edits - Vector of edit event maps, oldest first

   Example:
     (edits-since-last-review {::xtdb-node node})
     ;; => {::edits [{::file \"/path/to/a.clj\" ::namespace :seon.a} ...]}"
  {:malli/schema [:=> [:cat ::edits-since-last-review-request] ::edits-since-last-review-response]}
  [{::keys [_xtdb-node]}]
  (let [last-review-ts (::timestamp (get-last-review-time {::xtdb-node nil}))
        all-edits @edit-events]
    {::edits (vec (if last-review-ts
                    (let [review-ms (instant->epoch-ms last-review-ts)]
                      (filter #(> (instant->epoch-ms (:xt/valid-from %)) review-ms) all-edits))
                    all-edits))}))

(defn edits-summary
  "Get a summary of edits since last review.

   Returns a map suitable for building review context.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::files      - Set of file paths that were edited
     ::namespaces - Set of namespace keywords
     ::edit-count - Total number of edit events

   Example:
     (edits-summary {::xtdb-node node})
     ;; => {::files #{\"a.clj\" \"b.clj\"} ::namespaces #{:seon.a} ::edit-count 5}"
  {:malli/schema [:=> [:cat ::edits-summary-request] ::edits-summary-response]}
  [{::keys [_xtdb-node] :as request}]
  (let [edits (::edits (edits-since-last-review request))]
    {::files (set (map ::file edits))
     ::namespaces (set (keep ::namespace edits))
     ::edit-count (count edits)}))

;;; ---------------------------------------------------------------------------
;;; Review Rate Limiting
;;; ---------------------------------------------------------------------------

(defn should-review?
  "Determine if a review should trigger now.

   Uses simple rate limiting: at most one review per interval-seconds.

   Returns true if ALL conditions are met:
   1. There are edits since the last review
   2. Either: never reviewed, OR interval has passed since last review

   Request keys:
     ::xtdb-node        - Accepted but unused (kept for API compat)
     ::interval-seconds - Optional. Minimum seconds between reviews (default: 60)

   Response keys:
     ::should-review - Boolean indicating if review should trigger

   Example:
     (should-review? {::xtdb-node node})
     ;; => {::should-review true}"
  {:malli/schema [:=> [:cat ::should-review-request] ::should-review-response]}
  [{::keys [_xtdb-node interval-seconds]}]
  (let [interval-seconds (or interval-seconds 60)
        last-review (::timestamp (get-last-review-time {::xtdb-node nil}))
        last-edit (::timestamp (get-last-edit-time {::xtdb-node nil}))
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

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::success - Boolean indicating if operation completed

   Example:
     (clear-all-events! {::xtdb-node node})
     ;; => {::success true}"
  {:malli/schema [:=> [:cat ::clear-all-events-request] ::clear-all-events-response]}
  [{::keys [_xtdb-node]}]
  (reset! edit-events [])
  (reset! review-events [])
  (reset! todo-events [])
  {::success true})

;;; ---------------------------------------------------------------------------
;;; Query Helpers for Analysis (Phase 7b Observability)
;;; ---------------------------------------------------------------------------

(defn edits-for-file
  "Get all edit events for a specific file.

   Returns vector of edit events, newest first.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)
     ::file-path - Required. Path to the file

   Response keys:
     ::edits - Vector of edit event maps, newest first

   Example:
     (edits-for-file {::xtdb-node node ::file-path \"/path/to/file.clj\"})
     ;; => {::edits [{::file \"...\" ::decision :continue ...} ...]}"
  {:malli/schema [:=> [:cat ::edits-for-file-request] ::edits-for-file-response]}
  [{::keys [_xtdb-node file-path]}]
  {::edits (vec (reverse (filter #(= file-path (::file %)) @edit-events)))})

(defn reviews-in-range
  "Get review events in a time range.

   Request keys:
     ::xtdb-node     - Accepted but unused (kept for API compat)
     ::start-instant - Required. Start time (Instant)
     ::end-instant   - Optional. End time (Instant), defaults to now

   Response keys:
     ::reviews - Vector of review event maps, newest first

   Example:
     (reviews-in-range {::xtdb-node node
                        ::start-instant (.minus (Instant/now) (Duration/ofHours 1))})"
  {:malli/schema [:=> [:cat ::reviews-in-range-request] ::reviews-in-range-response]}
  [{::keys [_xtdb-node start-instant end-instant]}]
  (let [end-instant (or end-instant (Instant/now))
        start-ms (instant->epoch-ms start-instant)
        end-ms (instant->epoch-ms end-instant)]
    {::reviews (vec (reverse
                     (filter (fn [r]
                               (let [t-ms (instant->epoch-ms (:xt/valid-from r))]
                                 (and (>= t-ms start-ms) (<= t-ms end-ms))))
                             @review-events)))}))

(defn failure-rate
  "Calculate the percentage of edits that resulted in blocks.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::total   - Total edit count
     ::blocked - Number of blocked edits
     ::rate    - Failure rate as decimal (0.0 - 1.0)

   Example:
     (failure-rate {::xtdb-node node})
     ;; => {::total 100 ::blocked 5 ::rate 0.05}"
  {:malli/schema [:=> [:cat ::failure-rate-request] ::failure-rate-response]}
  [{::keys [_xtdb-node]}]
  (let [all-edits @edit-events
        total (count all-edits)
        blocked (count (filter #(= :block (::decision %)) all-edits))]
    {::total total
     ::blocked blocked
     ::rate (if (pos? total)
              (double (/ blocked total))
              0.0)}))

(defn gemini-token-usage
  "Get total Gemini token usage in a time period.

   Request keys:
     ::xtdb-node     - Accepted but unused (kept for API compat)
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
  [{::keys [_xtdb-node start-instant end-instant] :as request}]
  (let [reviews (::reviews (reviews-in-range request))
        tokens (keep ::gemini-tokens reviews)]
    {::prompt-tokens (reduce + 0 (keep :prompt tokens))
     ::response-tokens (reduce + 0 (keep :response tokens))
     ::cached-tokens (reduce + 0 (keep :cached tokens))
     ::review-count (count reviews)}))

(defn recent-activity
  "Get summary of recent hook activity.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)
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
  [{::keys [_xtdb-node hours] :as request}]
  (let [hours (or hours 1)
        start-inst (.minus (Instant/now) (java.time.Duration/ofHours hours))
        start-ms (instant->epoch-ms start-inst)
        edits (filter #(>= (instant->epoch-ms (:xt/valid-from %)) start-ms)
                       @edit-events)
        reviews (::reviews (reviews-in-range (assoc request ::start-instant start-inst)))
        blocked (count (filter #(= :block (::decision %)) edits))
        tokens (gemini-token-usage (assoc request ::start-instant start-inst))]
    {::period-hours hours
     ::edit-count (count edits)
     ::review-count (count reviews)
     ::blocked-count blocked
     ::failure-rate (if (pos? (count edits))
                      (double (/ blocked (count edits)))
                      0.0)
     ::gemini-tokens tokens}))

;;; ---------------------------------------------------------------------------
;;; Widget Query Functions (Phase 0.5)
;;; ---------------------------------------------------------------------------

(defn latest-todos
  "Get the most recent todo list for an agent session.

   Returns the latest todo_event for the given session, or nil if none exists.

   Request keys:
     ::xtdb-node  - Accepted but unused (kept for API compat)
     ::session-id - Required. Agent session ID (e.g., 'a1b2')

   Response keys:
     ::todos     - Vector of todo items, or nil if no todos recorded
     ::timestamp - When the todo list was recorded

   Example:
     (latest-todos {::xtdb-node node ::session-id \"a1b2\"})
     ;; => {::todos [{:content \"Fix bug\" :status \"completed\" ...}]
     ;;     ::timestamp #inst \"2024-01-15T12:00:00Z\"}"
  {:malli/schema [:=> [:cat ::latest-todos-request] ::latest-todos-response]}
  [{::keys [_xtdb-node session-id]}]
  (let [matching (->> @todo-events
                      (filter #(= session-id (::session-id %)))
                      last)]
    {::todos (::todos matching)
     ::timestamp (:xt/valid-from matching)}))

(defn latest-test-result
  "Get the most recent test result for an agent session.

   Returns the latest edit_event with test data for the session.
   Filters to edit events that have unit or gen test results.

   Request keys:
     ::xtdb-node  - Accepted but unused (kept for API compat)
     ::session-id - Required. Agent session ID (used to find session's edits)

   Response keys:
     ::unit-test-result - Unit test summary or nil
     ::gen-test-result  - Gen test summary or nil
     ::file             - File that was tested
     ::timestamp        - When the test ran

   Example:
     (latest-test-result {::xtdb-node node ::session-id \"a1b2\"})"
  [{::keys [_xtdb-node _session-id]}]
  (let [result (->> @edit-events
                    (filter #(or (::unit-test-result %) (::gen-test-result %)))
                    last)]
    {::unit-test-result (::unit-test-result result)
     ::gen-test-result (::gen-test-result result)
     ::file (::file result)
     ::timestamp (:xt/valid-from result)}))

(defn latest-review
  "Get the most recent Gemini review.

   Returns the latest review_event with response data.

   Request keys:
     ::xtdb-node - Accepted but unused (kept for API compat)

   Response keys:
     ::gemini-response - The review text
     ::gemini-tokens   - Token usage {:prompt N :response N :cached N}
     ::files           - Files that were reviewed
     ::timestamp       - When the review occurred

   Example:
     (latest-review {::xtdb-node node})"
  [{::keys [_xtdb-node]}]
  (let [result (->> @review-events
                    (filter ::gemini-response)
                    last)]
    {::gemini-response (::gemini-response result)
     ::gemini-tokens (::gemini-tokens result)
     ::files (::files result)
     ::timestamp (:xt/valid-from result)}))

(comment
  ;; REPL exploration

  (require '[seon.dev.context :as ctx])

  ;; Record some edits
  (record-edit! {::xtdb-node nil
                 ::file-path "/tmp/test.clj"
                 ::namespace 'seon.test})
  (record-edit! {::xtdb-node nil
                 ::file-path "/tmp/other.clj"
                 ::namespace 'seon.other})

  ;; Check timing
  (get-last-edit-time {::xtdb-node nil})
  (get-last-review-time {::xtdb-node nil})

  ;; Should we review?
  (should-review? {::xtdb-node nil})

  ;; Get edits
  (edits-since-last-review {::xtdb-node nil})
  (edits-summary {::xtdb-node nil})

  ;; Record review completion
  (record-review! {::xtdb-node nil
                   ::files #{"/tmp/test.clj" "/tmp/other.clj"}})

  ;; Now should-review? returns false (just reviewed)
  (should-review? {::xtdb-node nil})

  ;; Clear all
  (clear-all-events! {::xtdb-node nil})

  nil)
