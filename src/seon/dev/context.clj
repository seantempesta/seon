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

(schema/register! ::edit-event
                  [:map
                   [:xt/id :uuid]
                   [:entity/type [:= :edit-event]]
                   [:edit/file ::file-path]
                   [:edit/namespace {:optional true} ::namespace]])

(schema/register! ::review-event
                  [:map
                   [:xt/id :uuid]
                   [:entity/type [:= :review-event]]
                   [:review/files [:set ::file-path]]
                   [:review/edit-count :int]])

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

   Request keys:
     node      - XTDB node
     file-path - Absolute path to the edited file
     ns-sym    - Namespace symbol (e.g., 'seon.foo)

   Returns:
     Transaction result with :tx-id

   Example:
     (record-edit! node \"/path/to/file.clj\" 'seon.foo)"
  [xtdb-node file-path ns-sym]
  (let [entity {:xt/id (UUID/randomUUID)
                :entity/type :edit-event
                :edit/file file-path
                :edit/namespace (when ns-sym (keyword (str ns-sym)))}]
    (node/execute-tx! xtdb-node [[:put-docs :edit-event entity]])))

(defn record-review!
  "Record that a review was completed.

   Records the files that were reviewed and the count of edits processed.

   Request keys:
     node  - XTDB node
     files - Set of file paths that were reviewed

   Returns:
     Transaction result with :tx-id

   Example:
     (record-review! node #{\"/path/to/file.clj\" \"/path/to/other.clj\"})"
  [xtdb-node files]
  (let [edit-count (count (edits-since-last-review xtdb-node))
        entity {:xt/id (UUID/randomUUID)
                :entity/type :review-event
                :review/files (set files)
                :review/edit-count edit-count}]
    (node/execute-tx! xtdb-node [[:put-docs :review-event entity]])))

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
