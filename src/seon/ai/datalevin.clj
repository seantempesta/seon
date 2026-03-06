(ns seon.ai.datalevin
  "Datalevin storage layer for AI sessions and messages.

   Purpose: Persist agent sessions and conversation messages to Datalevin.
   Fire-and-forget writes (errors logged, not propagated) with debounced SSE
   refresh to update Observatory UI.

   Depends on: seon.ai (schemas), seon.db (query, transact!, pull-by-name, pull-many-by-name)
   Depended on by: seon.ai (primary), seon.ai.claude, seon.web.agents

   Consumers:
   - seon.ai: Calls write functions via lazy datalevin-write! to avoid circular deps.
     Uses dl-get-session, dl-get-messages, dl-list-sessions, dl-session-stats for
     public API implementations. Clean separation — seon.ai owns map-in API, we own storage.
   - seon.ai.claude: Uses enabled? atom + save-message! for real-time persistence.
     Queries via dl-find-by-agent-session-id, dl-get-result-message, dl-count-assistant-turns,
     dl-message-count, dl-recent-messages. All via requiring-resolve.
   - seon.web.agents: Direct require. Uses dl-* functions for observatory rendering:
     dl-find-ai-session-id, dl-load-session-messages, dl-load-session-info,
     dl-load-context-tokens, dl-message-stats-by-session, dl-context-tokens-by-session.

   Watch out for:
   - dl-* functions use positional args (internal pattern) but are de-facto public API
   - entity-id/entity-type keys are our internal Datalevin markers, not seon.ai keys

   Needs work:
   - P2: No :malli/schema on write functions (justified: fire-and-forget, entity maps vary)
   - P2: dl-* functions use positional args (would break consumers to change)

   Migrated (2026-03-04):
   - All reads use seon.db/query, pull-by-name, pull-many-by-name
   - All writes use seon.db/transact! with :seon.ai keyword
   - Removed *test-conn*, get-conn, private q/pull-entity/pull-many-entities
   - No direct datalevin.core usage

   Fixed (2026-02-26):
   - P1: Created test file with 12 tests, 36 assertions
   - P3: stats function now has :malli/schema and uses namespaced keys

   Last audit: 2026-03-04 | Tests: 12 pass / 0 fail"
  (:require [clojure.edn :as edn]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.db.schema :as dbs]
            [seon.db.tx :as tx]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

;; Atom controlling whether Datalevin writes are enabled.
;; Set to false to disable parallel writes (e.g., if Datalevin is unstable).
(defonce enabled? (atom true))

;; Global monotonic counter for message ordering.
;; Ensures messages saved in the same millisecond have deterministic order.
(defonce msg-sequence (atom 0))

(defn set-enabled!
  "Enable or disable Datalevin parallel writes.

   Usage:
     (set-enabled! true)   ; Enable parallel writes
     (set-enabled! false)  ; Disable parallel writes

   Returns the new state."
  [value]
  (reset! enabled? (boolean value)))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::write-count
                  [:int {:min 0 :description "Number of successful writes"}])

(schema/register! ::error-count
                  [:int {:min 0 :description "Number of failed writes"}])

(schema/register! ::last-write-at :inst)

(schema/register! ::session-count
                  [:int {:min 0 :description "Number of sessions stored"}])

(schema/register! ::message-count
                  [:int {:min 0 :description "Number of messages stored"}])

(schema/register! ::session-writes
                  [:int {:min 0 :description "Number of session writes"}])

(schema/register! ::message-writes
                  [:int {:min 0 :description "Number of message writes"}])

(schema/register! ::stats-response
                  [:map
                   [::write-count ::write-count]
                   [::error-count ::error-count]
                   [::last-write-at {:optional true} ::last-write-at]
                   [::session-writes ::session-writes]
                   [::message-writes ::message-writes]])

;; --- Datalevin entity attribute schemas ---
;; Internal markers for all entities stored in Datalevin.

(schema/register! ::entity-id
                  [:string {:min 1
                            :seon.db/identity true
                            :description "Logical entity ID (e.g. ses-xxx, msg-xxx)"}])

(schema/register! ::entity-type
                  [:enum {:description "Datalevin entity type discriminator"}
                   :session :message])

(schema/register! ::stored-at :inst)

;; --- Entity schemas for Malli->Datalevin bridge ---

(def session-entity-schema
  "Malli schema for AI session entities stored in Datalevin.
   Single source of truth -- Datalevin schema is derived from this."
  [:map
   [::entity-id ::entity-id]
   [::entity-type :keyword]
   [::stored-at :inst]
   [:seon.ai/session-id :string]
   [:seon.ai/namespace {:optional true} :string]
   [:seon.ai/agent-session-id {:optional true} :string]
   [:seon.ai/started-at {:optional true} :inst]
   [:seon.ai/ended-at {:optional true} :inst]
   [:seon.ai/status {:optional true} :keyword]
   [:seon.ai/cost-usd {:optional true} :double]])

(def message-entity-schema
  "Malli schema for AI message entities stored in Datalevin.
   Single source of truth -- Datalevin schema is derived from this."
  [:map
   [::entity-id ::entity-id]
   [::entity-type :keyword]
   [::stored-at :inst]
   [:seon.ai/session-id :string]
   [:seon.ai/role {:optional true} :string]
   [:seon.ai/content {:optional true} :string]
   [:seon.ai/input-tokens {:optional true} :int]
   [:seon.ai/output-tokens {:optional true} :int]
   [:seon.ai/timestamp {:optional true} :inst]
   [:seon.ai.claude/message-type {:optional true} :string]
   [:seon.ai/sequence {:optional true} :int]
   [:seon.ai.claude/cache-read-tokens {:optional true} :int]
   [:seon.ai.claude/cache-creation-tokens {:optional true} :int]])

(dbs/register-entity-schema! "seon.ai.session" session-entity-schema)
(dbs/register-entity-schema! "seon.ai.message" message-entity-schema)

(def datalevin-schema
  "Datalevin attribute schema for AI entities.
   Derived from entity schemas via bridge, merged with tx metadata."
  (merge
   (dbs/malli-map->datalevin-schema session-entity-schema)
   (dbs/malli-map->datalevin-schema message-entity-schema)
   tx/datalevin-schema))

;;; ---------------------------------------------------------------------------
;;; Statistics
;;; ---------------------------------------------------------------------------

;; Atom tracking write statistics for monitoring.
(defonce stats-atom
  (atom {::write-count 0
         ::error-count 0
         ::last-write-at nil
         ::session-writes 0
         ::message-writes 0}))

(defn stats
  "Get current Datalevin storage statistics.

   Returns map with namespaced keys:
     ::write-count    - Total successful writes
     ::error-count    - Total failed writes
     ::last-write-at  - Timestamp of last successful write
     ::session-writes - Session entities written
     ::message-writes - Message entities written

   Example:
     (stats)
     ;; => {::write-count 42, ::error-count 0, ...}"
  {:malli/schema [:=> [:cat] ::stats-response]}
  []
  @stats-atom)

(defn reset-stats!
  "Reset statistics counters."
  []
  (reset! stats-atom {::write-count 0
                      ::error-count 0
                      ::last-write-at nil
                      ::session-writes 0
                      ::message-writes 0}))

;;; ---------------------------------------------------------------------------
;;; Database Identity
;;; ---------------------------------------------------------------------------

;; All AI storage operations use this db-name keyword.
;; Resolved by seon.db to the :seon.ai Datalevin database via conn manager.
(def ^:private db-name :seon.ai)

;;; ---------------------------------------------------------------------------
;;; Entity Conversion
;;; ---------------------------------------------------------------------------

(defn- remove-nil-values
  "Remove key-value pairs where the value is nil.
   Datalevin throws NPE when trying to transact nil values.
   This is defensive — source should avoid nils, but belt-and-suspenders."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn- coerce-instants
  "Coerce java.time.Instant values to java.util.Date in a map.
   Datalevin :db.type/instant requires java.util.Date, not Instant."
  [m]
  (into {} (map (fn [[k v]]
                  [k (if (instance? Instant v)
                       (java.util.Date/from v)
                       v)])
                m)))

(defn- entity->datalevin-session
  "Convert session entity to Datalevin format.

   Key differences:
   - :seon/id -> stored separately as :seon.ai.datalevin/entity-id for traceability
   - :db/id is auto-generated by Datalevin
   - All other fields pass through unchanged (nils filtered out)
   - java.time.Instant coerced to java.util.Date for Datalevin"
  [entity]
  (let [session-id (:seon/id entity)]
    (-> entity
        (dissoc :seon/id)
        (assoc :seon.ai.datalevin/entity-id session-id
               :seon.ai.datalevin/entity-type :session
               :seon.ai.datalevin/stored-at (java.util.Date.))
        remove-nil-values
        coerce-instants)))

(defn- serialize-content
  "Serialize ::ai/content for Datalevin storage.
   Strings pass through; maps are pr-str'd to EDN so they survive :db.type/string."
  [entity]
  (let [content (:seon.ai/content entity)]
    (if (map? content)
      (assoc entity :seon.ai/content (pr-str content))
      entity)))

(defn- deserialize-content
  "Deserialize ::ai/content from Datalevin storage.
   Attempts to read EDN for strings that look like maps; plain strings pass through."
  [entity]
  (let [content (:seon.ai/content entity)]
    (if (and (string? content) (.startsWith ^String content "{"))
      (try
        (assoc entity :seon.ai/content (edn/read-string content))
        (catch Exception _ entity))
      entity)))

(defn- entity->datalevin-message
  "Convert message entity to Datalevin format.
   Nil values are filtered out to prevent Datalevin NPE.
   java.time.Instant coerced to java.util.Date for Datalevin.
   Map content is serialized to EDN string for :db.type/string storage."
  [entity]
  (let [message-id (:seon/id entity)]
    (-> entity
        (dissoc :seon/id)
        (assoc :seon.ai.datalevin/entity-id message-id
               :seon.ai.datalevin/entity-type :message
               :seon.ai.datalevin/stored-at (java.util.Date.))
        serialize-content
        remove-nil-values
        coerce-instants)))

;;; ---------------------------------------------------------------------------
;;; SSE Refresh (debounced)
;;; ---------------------------------------------------------------------------

(defonce ^:private last-sse-refresh-ms (atom 0))

(defn- maybe-refresh-sse!
  "Trigger SSE refresh for observatory, debounced to max once per 200ms."
  []
  (let [now (System/currentTimeMillis)
        last @last-sse-refresh-ms]
    (when (> (- now last) 200)
      (when (compare-and-set! last-sse-refresh-ms last now)
        (try
          (when-let [f (resolve 'seon.web.sse/refresh-all!)]
            (f))
          (catch Exception _))))))

;;; ---------------------------------------------------------------------------
;;; Write Operations
;;; ---------------------------------------------------------------------------

(defn save-session!
  "Save a session entity to Datalevin.

   Called by seon.ai to persist session data. Fire-and-forget:
   errors are logged but don't propagate to caller.

   Args:
     entity - Session entity map (with :seon/id)

   Returns:
     true if write succeeded, false if failed or disabled"
  [entity]
  (if-not @enabled?
    (do (log/trace "Datalevin writes disabled, skipping session")
        false)
    (try
      (let [dl-entity (entity->datalevin-session entity)]
        (db/transact! db-name [dl-entity])
        (swap! stats-atom (fn [s]
                            (-> s
                                (update ::write-count inc)
                                (update ::session-writes inc)
                                (assoc ::last-write-at (Instant/now)))))
        (log/debug "Saved session to Datalevin"
                   {:session-id (::ai/session-id entity)
                    :entity-id (:seon/id entity)})
        (maybe-refresh-sse!)
        true)
      (catch Exception e
        (swap! stats-atom update ::error-count inc)
        (log/warn "Failed to save session to Datalevin"
                  {:session-id (::ai/session-id entity)
                   :error (.getMessage e)})
        false))))

(defn save-message!
  "Save a message entity to Datalevin.

   Called by seon.ai.claude to persist messages. Fire-and-forget.

   Args:
     entity - Message entity map (with :seon/id)

   Returns:
     true if write succeeded, false if failed or disabled"
  [entity]
  (if-not @enabled?
    (do (log/trace "Datalevin writes disabled, skipping message")
        false)
    (try
      (let [seq-num (swap! msg-sequence inc)
            dl-entity (-> (entity->datalevin-message entity)
                          (assoc :seon.ai/sequence seq-num))]
        (db/transact! db-name [dl-entity])
        (swap! stats-atom (fn [s]
                            (-> s
                                (update ::write-count inc)
                                (update ::message-writes inc)
                                (assoc ::last-write-at (Instant/now)))))
        (log/trace "Saved message to Datalevin"
                   {:message-id (:seon/id entity)
                    :session-id (::ai/session-id entity)})
        (maybe-refresh-sse!)
        true)
      (catch Exception e
        (swap! stats-atom update ::error-count inc)
        (log/warn "Failed to save message to Datalevin"
                  {:message-id (:seon/id entity)
                   :error (.getMessage e)})
        false))))

(defn update-session!
  "Update a session entity in Datalevin (for end-session).

   Finds existing session by logical ID and updates it with new fields.

   Args:
     entity - Updated session entity map (with :seon/id)

   Returns:
     true if write succeeded, false if failed or disabled"
  [entity]
  (if-not @enabled?
    false
    (try
      (let [entity-id (:seon/id entity)
            existing (first (db/query db-name
                                      '[:find ?e
                                        :in $ ?entity-id
                                        :where
                                        [?e :seon.ai.datalevin/entity-id ?entity-id]]
                                      entity-id))]
        (if existing
          (let [db-id (first existing)
                dl-entity (-> (entity->datalevin-session entity)
                              (assoc :db/id db-id))]
            (db/transact! db-name [dl-entity])
            (swap! stats-atom (fn [s]
                                (-> s
                                    (update ::write-count inc)
                                    (assoc ::last-write-at (Instant/now)))))
            (log/debug "Updated session in Datalevin" {:entity-id entity-id})
            (maybe-refresh-sse!)
            true)
          (save-session! entity)))
      (catch Exception e
        (swap! stats-atom update ::error-count inc)
        (log/warn "Failed to update session in Datalevin"
                  {:session-id (:seon/id entity)
                   :error (.getMessage e)})
        false))))

;;; ---------------------------------------------------------------------------
;;; Query Operations (For Verification)
;;; ---------------------------------------------------------------------------

(defn query-sessions
  "Query sessions from Datalevin for verification.

   Opts:
     :limit - Max results (default 20)
     :status - Filter by status

   Returns:
     Vector of session entities"
  ([] (query-sessions {}))
  ([opts]
   (let [limit (or (:limit opts) 20)
         results (db/query db-name
                           '[:find ?e ?stored
                             :in $
                             :where
                             [?e :seon.ai.datalevin/entity-type :session]
                             [?e :seon.ai.datalevin/stored-at ?stored]])]
     (->> results
          (sort-by second #(compare %2 %1))
          (take limit)
          (mapv (fn [[eid _]]
                  (db/pull-by-name db-name '[*] eid)))))))

(defn query-messages
  "Query messages from Datalevin for verification.

   Opts:
     :session-id - Filter by AI session ID
     :limit - Max results (default 100)

   Returns:
     Vector of message entities"
  ([] (query-messages {}))
  ([opts]
   (let [limit (or (:limit opts) 100)
         session-id (:session-id opts)
         results (if session-id
                   (db/query db-name
                             '[:find ?e ?stored
                               :in $ ?sid
                               :where
                               [?e :seon.ai.datalevin/entity-type :message]
                               [?e :seon.ai/session-id ?sid]
                               [?e :seon.ai.datalevin/stored-at ?stored]]
                             session-id)
                   (db/query db-name
                             '[:find ?e ?stored
                               :where
                               [?e :seon.ai.datalevin/entity-type :message]
                               [?e :seon.ai.datalevin/stored-at ?stored]]))]
     (->> results
          (sort-by second #(compare %2 %1))
          (take limit)
          (mapv (fn [[eid _]]
                  (db/pull-by-name db-name '[*] eid)))))))

(defn count-entities
  "Count entities in Datalevin by type.

   Returns:
     Map with :sessions and :messages counts"
  []
  (let [session-count (count (db/query db-name
                                       '[:find ?e
                                         :where
                                         [?e :seon.ai.datalevin/entity-type :session]]))
        message-count (count (db/query db-name
                                       '[:find ?e
                                         :where
                                         [?e :seon.ai.datalevin/entity-type :message]]))]
    {:sessions session-count
     :messages message-count}))

;;; ---------------------------------------------------------------------------
;;; Datalevin Read Functions
;;; ---------------------------------------------------------------------------

(defn- dl-entity->session
  "Convert a Datalevin entity (from pull) to the shape callers expect.
   Maps :seon.ai.datalevin/entity-id back to :seon/id (logical ID) and removes Datalevin-internal keys."
  [entity]
  (when entity
    (-> entity
        (assoc :seon/id (::entity-id entity))
        (dissoc :db/id ::entity-type ::stored-at ::entity-id))))

(defn- dl-entity->message
  "Convert a Datalevin message entity to the shape callers expect.
   Deserializes EDN-encoded map content back to maps."
  [entity]
  (when entity
    (-> entity
        (assoc :seon/id (::entity-id entity))
        (dissoc :db/id ::entity-type ::stored-at ::entity-id)
        deserialize-content)))

(defn dl-get-session
  "Find session by session-id (logical ID) from Datalevin.
   Returns session entity map or nil."
  [session-id]
  (when-let [results (db/query db-name
                               '[:find ?e
                                 :in $ ?sid
                                 :where
                                 [?e ::entity-type :session]
                                 [?e ::entity-id ?sid]]
                               session-id)]
    (when-let [eid (ffirst results)]
      (dl-entity->session (db/pull-by-name db-name '[*] eid)))))

(defn dl-get-messages
  "All messages for a session, ordered by timestamp ASC then sequence ASC.
   Uses pull-many for batch retrieval (~19ms vs ~2400ms for 215 messages)."
  [session-id]
  (when-let [results (db/query db-name
                               '[:find ?e ?ts ?seq
                                 :in $ ?sid
                                 :where
                                 [?e ::entity-type :message]
                                 [?e :seon.ai/session-id ?sid]
                                 [?e :seon.ai/timestamp ?ts]
                                 [(get-else $ ?e :seon.ai/sequence 0) ?seq]]
                               session-id)]
    (let [sorted (sort-by (fn [[_ ts seq]] [ts seq]) results)
          eids (mapv first sorted)]
      (mapv dl-entity->message (db/pull-many-by-name db-name '[*] eids)))))

(defn dl-list-sessions
  "List sessions with optional namespace/status filter, ordered by started-at DESC.
   Opts: :namespace, :status, :limit (default 20)."
  ([] (dl-list-sessions {}))
  ([{:keys [namespace status limit] :or {limit 20}}]
   (let [base-where '[[?e ::entity-type :session]
                      [?e :seon.ai/started-at ?started]]
         ns-where (when namespace
                    [['?e :seon.ai/namespace (str namespace)]])
         status-where (when status
                        [['?e :seon.ai/status status]])
         all-where (vec (concat base-where ns-where status-where))
         query {:find '[?e ?started]
                :in (if (or namespace status) '[$] '[$])
                :where all-where}
         results (db/query db-name query)]
     (when results
       (let [taken (->> results (sort-by second #(compare %2 %1)) (take limit))
             eids (mapv first taken)]
         (mapv dl-entity->session (db/pull-many-by-name db-name '[*] eids)))))))

(defn dl-session-stats
  "Aggregate stats: total cost, sessions, messages, token counts."
  []
  (let [sessions (db/query db-name
                           '[:find ?e ?cost
                             :where
                             [?e ::entity-type :session]
                             [(get-else $ ?e :seon.ai/cost-usd 0.0) ?cost]])
        messages (db/query db-name
                           '[:find ?e ?in ?out ?cr ?cc
                             :where
                             [?e ::entity-type :message]
                             [(get-else $ ?e :seon.ai/input-tokens 0) ?in]
                             [(get-else $ ?e :seon.ai/output-tokens 0) ?out]
                             [(get-else $ ?e :seon.ai.claude/cache-read-tokens 0) ?cr]
                             [(get-else $ ?e :seon.ai.claude/cache-creation-tokens 0) ?cc]])
        total-cost (reduce + 0.0 (map second sessions))
        total-sessions (count sessions)
        total-messages (count messages)
        input-tokens (reduce + 0 (map #(nth % 1) messages))
        output-tokens (reduce + 0 (map #(nth % 2) messages))
        cache-read (reduce + 0 (map #(nth % 3) messages))
        cache-creation (reduce + 0 (map #(nth % 4) messages))
        total-input (+ cache-read input-tokens)
        cache-hit-rate (if (pos? total-input)
                         (/ (double cache-read) total-input)
                         0.0)]
    {:seon.ai/total-cost-usd (double total-cost)
     :seon.ai/total-sessions (long total-sessions)
     :seon.ai/total-messages (long total-messages)
     :seon.ai/tokens {:input (long input-tokens)
                      :output (long output-tokens)
                      :cache-read (long cache-read)
                      :cache-creation (long cache-creation)}
     :seon.ai/cache-hit-rate cache-hit-rate}))

(defn dl-find-by-agent-session-id
  "Find session by 4-char agent session ID."
  [agent-session-id]
  (when-let [results (db/query db-name
                               '[:find ?e
                                 :in $ ?asid
                                 :where
                                 [?e ::entity-type :session]
                                 [?e :seon.ai/agent-session-id ?asid]]
                               agent-session-id)]
    (when-let [eid (ffirst results)]
      (dl-entity->session (db/pull-by-name db-name '[*] eid)))))

(defn dl-get-result-message
  "Get the result message for a session (message-type = 'result')."
  [ai-session-id]
  (when-let [results (db/query db-name
                               '[:find ?e
                                 :in $ ?sid
                                 :where
                                 [?e ::entity-type :message]
                                 [?e :seon.ai/session-id ?sid]
                                 [?e :seon.ai.claude/message-type "result"]]
                               ai-session-id)]
    (when-let [eid (ffirst results)]
      (dl-entity->message (db/pull-by-name db-name '[*] eid)))))

(defn dl-count-assistant-turns
  "Count assistant turns in a session (role=assistant, message-type=assistant)."
  [ai-session-id]
  (let [results (db/query db-name
                          '[:find ?e
                            :in $ ?sid
                            :where
                            [?e ::entity-type :message]
                            [?e :seon.ai/session-id ?sid]
                            [?e :seon.ai/role "assistant"]
                            [?e :seon.ai.claude/message-type "assistant"]]
                          ai-session-id)]
    (count (or results []))))

(defn dl-message-count
  "Total messages in a session."
  [ai-session-id]
  (let [results (db/query db-name
                          '[:find ?e
                            :in $ ?sid
                            :where
                            [?e ::entity-type :message]
                            [?e :seon.ai/session-id ?sid]]
                          ai-session-id)]
    (count (or results []))))

(defn dl-recent-messages
  "Recent N messages for a session, ordered by timestamp DESC then reversed to chronological."
  [ai-session-id limit]
  (when-let [results (db/query db-name
                               '[:find ?e ?ts ?seq
                                 :in $ ?sid
                                 :where
                                 [?e ::entity-type :message]
                                 [?e :seon.ai/session-id ?sid]
                                 [?e :seon.ai/timestamp ?ts]
                                 [(get-else $ ?e :seon.ai/sequence 0) ?seq]]
                               ai-session-id)]
    (let [taken (->> results
                     (sort-by (fn [[_ ts seq]] [ts seq]) #(compare %2 %1))
                     (take limit)
                     reverse)
          eids (mapv first taken)]
      (mapv dl-entity->message (db/pull-many-by-name db-name '[*] eids)))))

(defn dl-message-stats-by-session
  "Batch: message counts + latest timestamp per session.
   Returns map of session-id -> {:count n :latest-ts instant}."
  []
  (when-let [results (db/query db-name
                               '[:find ?sid ?ts
                                 :where
                                 [?e ::entity-type :message]
                                 [?e :seon.ai/session-id ?sid]
                                 [?e :seon.ai/timestamp ?ts]])]
    (->> results
         (group-by first)
         (reduce-kv
          (fn [acc sid entries]
            (let [timestamps (map second entries)
                  latest (apply max-key #(.toEpochMilli %) timestamps)]
              (assoc acc sid {:count (count entries)
                              :latest-ts latest})))
          {}))))

(defn dl-context-tokens-by-session
  "Batch: cache creation tokens per session (from result messages).
   Returns map of session-id -> token-count."
  []
  (when-let [results (db/query db-name
                               '[:find ?sid ?tokens
                                 :where
                                 [?e ::entity-type :message]
                                 [?e :seon.ai/session-id ?sid]
                                 [?e :seon.ai.claude/message-type "result"]
                                 [?e :seon.ai.claude/cache-creation-tokens ?tokens]])]
    (into {} results)))

(defn dl-find-ai-session-id
  "Find the AI session ID (ses-xxx) for a given agent session ID (4-char hex).
   Returns the logical ID string or nil."
  [agent-session-id]
  (when-let [session (dl-find-by-agent-session-id agent-session-id)]
    (:seon/id session)))

(defn dl-load-session-messages
  "Load all messages for an AI session, ordered by timestamp.
   Alias for dl-get-messages."
  [ai-session-id]
  (dl-get-messages ai-session-id))

(defn dl-load-session-info
  "Load session metadata by AI session ID (ses-xxx).
   Returns session entity or nil."
  [ai-session-id]
  (when-let [results (db/query db-name
                               '[:find ?e
                                 :in $ ?entity-id
                                 :where
                                 [?e ::entity-type :session]
                                 [?e ::entity-id ?entity-id]]
                               ai-session-id)]
    (when-let [eid (ffirst results)]
      (dl-entity->session (db/pull-by-name db-name '[*] eid)))))

(defn dl-load-context-tokens
  "Load context token usage from the result message for a session."
  [ai-session-id]
  (when-let [result-msg (dl-get-result-message ai-session-id)]
    (:seon.ai.claude/cache-creation-tokens result-msg)))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Check if enabled
  @enabled?

  ;; Toggle
  (set-enabled! true)
  (set-enabled! false)

  ;; View stats
  (stats)

  ;; Reset stats
  (reset-stats!)

  ;; Count entities
  (count-entities)

  ;; Query sessions
  (query-sessions {:limit 5})

  ;; Query messages
  (query-messages {:limit 10})

  nil)
