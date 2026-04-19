(ns seon.db.datahike.conn-process
  "A core.async.flow step-fn that owns a single Datahike connection.

   One instance per namespace DB. State lives in the flow process, NOT in
   app-level atoms (Decision 9 of the datahike-migration PRD).

   Inputs:
     :seon.db.datahike/request - request envelope:
       ::msg/id            - request uuid (echoed on reply)
       ::msg/payload       - {::op kw ::args vec ::malli-schema map?}

   Outputs:
     :seon.db.datahike/reply     - reply envelope for the reply-router
     :seon.db.datahike/tx-report - tx-report for the tx-bus (only on successful
                                    :transact!)

   Supported ops (payload ::op):
     :transact!  args=[tx-data]           -> tx-report map
     :q          args=[query & inputs]    -> query result
     :pull       args=[selector eid]      -> entity map
     :pull-many  args=[selector eids]     -> vector of entity maps
     :entity     args=[eid]               -> entity
     :schema     args=[]                  -> installed schema map

   State (per datahike-migration Decision 9 -- lives inside flow state):
     {::conn              <datahike Connection>
      ::config            <full datahike cfg, including :writer>
      ::db-name           <namespace keyword>
      ::schema-installed? true
      ::malli-schema      <map-schema that produced installed idents>
      ::tx-count          <long>
      ::error-count       <long>}

   Gotchas (see docs/prds/datahike-migration/notes.md):
     G1 - d/transact is SYNC, d/transact! is async. Use the sync one.
     G2 - Always pass the full :writer config in every d/connect. Don't trust
          what's stored.
     G3 - Schema type drift is silently corrupting; install is idempotent only
          if the Malli-derived ident is identical to the stored one."
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.db.datahike.schema :as dh-schema]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::op
  [:enum :transact! :q :pull :pull-many :entity :schema])

(schema/register! ::args
  [:vector :seon.flow/dynamic])

(schema/register! ::db-name :keyword)

(schema/register! ::request-payload
  [:map
   [::op ::op]
   [::args ::args]])

(schema/register! :seon.db.datahike.tx-report/db-name :keyword)
(schema/register! :seon.db.datahike.tx-report/tx-data :seon.flow/dynamic)
(schema/register! :seon.db.datahike.tx-report/tx-meta :seon.flow/dynamic)
(schema/register! :seon.db.datahike.tx-report/at :inst)

(schema/register! ::tx-report
  [:map
   [:seon.db.datahike.tx-report/db-name :seon.db.datahike.tx-report/db-name]
   [:seon.db.datahike.tx-report/tx-data :seon.db.datahike.tx-report/tx-data]
   [:seon.db.datahike.tx-report/tx-meta {:optional true} :seon.db.datahike.tx-report/tx-meta]
   [:seon.db.datahike.tx-report/at :seon.db.datahike.tx-report/at]])

;;; ---------------------------------------------------------------------------
;;; Connection Initialization
;;; ---------------------------------------------------------------------------

(defn- full-writer-config
  "Ensure the datahike config carries an explicit :writer. G2: never trust
   the writer stored on disk -- pass it explicitly on every connect."
  [cfg]
  (cond-> cfg
    (not (:writer cfg)) (assoc :writer {:backend :self})))

(defn- connect-or-create!
  "Open a datahike connection for `cfg`. Creates the DB if it doesn't exist.
   Always threads the explicit :writer config (G2)."
  [cfg]
  (let [cfg' (full-writer-config cfg)]
    (when-not (d/database-exists? cfg')
      (d/create-database cfg'))
    (d/connect cfg')))

(defn- install-schema!
  "Idempotently install the Malli-derived datahike schema on `conn`.

   Reads currently-installed schema via `(d/schema @conn)` and only transacts
   idents that aren't already present. If an ident IS present with a different
   `:db/valueType`, throws a clear drift error (G3). Returns the set of idents
   now installed."
  [conn malli-schema]
  (let [derived (when malli-schema
                  (dh-schema/malli-map->datahike-schema malli-schema))
        current (d/schema @conn)
        to-install
        (reduce
         (fn [acc attr-map]
           (let [ident (:db/ident attr-map)
                 existing (get current ident)]
             (cond
               (nil? existing)
               (conj acc attr-map)

               (not= (:db/valueType existing) (:db/valueType attr-map))
               (throw (ex-info
                       (str "Schema drift on " ident
                            ": stored :db/valueType " (:db/valueType existing)
                            " does not match Malli-derived " (:db/valueType attr-map)
                            ". Datahike schemas are append-only at the type level; "
                            "explicit migration is required.")
                       {:ident ident
                        :stored-type (:db/valueType existing)
                        :derived-type (:db/valueType attr-map)}))

               :else acc)))
         []
         derived)]
    (when (seq to-install)
      (log/info "Installing datahike schema"
                {:db-name (some-> conn meta ::db-name) :count (count to-install)})
      (d/transact conn to-install))
    (into #{} (map :db/ident) derived)))

(defn- stamp-namespace!
  "Seed the `:seon.db/namespace` ident if not already present so the
   namespace-stamp pattern (Decision 7) can be used by callers. Idempotent."
  [conn]
  (let [current (d/schema @conn)]
    (when-not (contains? current :seon.db/namespace)
      (d/transact conn [{:db/ident :seon.db/namespace
                         :db/valueType :db.type/keyword
                         :db/cardinality :db.cardinality/one}]))))

;;; ---------------------------------------------------------------------------
;;; Reply Construction
;;; ---------------------------------------------------------------------------

(defn- ok-reply
  [request value duration-ms]
  {::msg/id (::msg/id request)
   ::msg/version 1
   ::msg/type :reply
   ::msg/status :ok
   ::msg/value value
   ::msg/from-ns "seon.db.datahike"
   ::msg/duration-ms duration-ms})

(defn- error-reply
  [request ^Throwable t duration-ms]
  {::msg/id (::msg/id request)
   ::msg/version 1
   ::msg/type :reply
   ::msg/status :error
   ::msg/error-type :execution
   ::msg/error-class (.getName (class t))
   ::msg/error-message (or (.getMessage t) (.getName (class t)))
   ::msg/from-ns "seon.db.datahike"
   ::msg/duration-ms duration-ms})

;;; ---------------------------------------------------------------------------
;;; Op Dispatch
;;; ---------------------------------------------------------------------------

(defn- do-op
  "Execute a single op against the conn. Returns [value tx-data] -- tx-data
   is non-nil only for :transact! (used to build a tx-report)."
  [conn op args]
  (case op
    :transact!
    ;; G1: sync `d/transact`, never `d/transact!`
    (let [tx-data (first args)
          report (d/transact conn tx-data)]
      [{:tempids (:tempids report)
        :tx-data (mapv (juxt :e :a :v :tx :added) (:tx-data report))}
       tx-data])

    :q
    (let [[query & inputs] args
          db @conn
          ;; Query accepts inputs; pass db as default source if no sources
          ;; are given. Callers who want cross-DB queries can specify sources
          ;; explicitly via ::args with :in $a $b style -- we pass @conn only
          ;; when no inputs.
          result (if (seq inputs)
                   (apply d/q query db inputs)
                   (d/q query db))]
      [result nil])

    :pull
    (let [[selector eid] args]
      [(d/pull @conn selector eid) nil])

    :pull-many
    (let [[selector eids] args]
      [(d/pull-many @conn selector eids) nil])

    :entity
    (let [[eid] args
          ent (d/entity @conn eid)]
      ;; Realize eagerly so the reply survives wire transit / printing.
      ;; Shallow is adequate here; callers wanting nested realization should
      ;; use :pull with a recursive selector.
      [(when ent (into {:db/id (:db/id ent)} ent)) nil])

    :schema
    [(d/schema @conn) nil]

    (throw (ex-info (str "Unknown datahike op: " op)
                    {:op op :args args}))))

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn conn-process-step
  "core.async.flow step-fn owning one datahike connection."
  ;; describe
  ([]
   {:ins {:seon.db.datahike/request "Op request envelope"}
    :outs {:seon.db.datahike/reply "Reply to the reply-router"
           :seon.db.datahike/tx-report "tx-report to the tx-bus"}
    :params {::config "Full datahike config map"
             ::db-name "Namespace keyword for this DB"
             ::malli-schema "Optional Malli :map schema to install at :init"}
    :workload :io})

  ;; init
  ([{::keys [config db-name malli-schema] :as args}]
   (let [conn (connect-or-create! config)]
     (stamp-namespace! conn)
     (install-schema! conn malli-schema)
     (log/info "Datahike conn-process initialized"
               {:db-name db-name :backend (get-in config [:store :backend])})
     {::conn conn
      ::config config
      ::db-name db-name
      ::malli-schema malli-schema
      ::schema-installed? true
      ::tx-count 0
      ::error-count 0}))

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop
     (do
       (when-let [conn (::conn state)]
         (try
           (d/release conn)
           (log/info "Datahike conn released" {:db-name (::db-name state)})
           (catch Throwable t
             (log/warn t "Failed to release datahike conn"
                       {:db-name (::db-name state)}))))
       (dissoc state ::conn))

     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id msg]
   (case input-id
     :seon.db.datahike/request
     (let [payload (::msg/payload msg)
           op (::op payload)
           args (or (::args payload) [])
           db-name (::db-name state)
           conn (::conn state)
           t0 (System/nanoTime)]
       (try
         (let [[value tx-data] (do-op conn op args)
               elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
               reply (ok-reply msg value elapsed-ms)
               tx-report (when (and (= op :transact!) tx-data)
                           {:seon.db.datahike.tx-report/db-name db-name
                            :seon.db.datahike.tx-report/tx-data tx-data
                            :seon.db.datahike.tx-report/tx-meta {}
                            :seon.db.datahike.tx-report/at (Instant/now)})
               state' (cond-> state
                        (= op :transact!) (update ::tx-count inc))]
           [state'
            (cond-> {:seon.db.datahike/reply [reply]}
              tx-report (assoc :seon.db.datahike/tx-report [tx-report]))])
         (catch Throwable t
           (let [elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                 reply (error-reply msg t elapsed-ms)]
             (log/warn t "Datahike op failed"
                       {:db-name db-name :op op :msg-id (::msg/id msg)})
             [(update state ::error-count inc)
              {:seon.db.datahike/reply [reply]
               ::flow/report [{:type :datahike-op-error
                               :db-name db-name
                               :op op
                               :error-message (.getMessage t)}]}]))))

     ;; Unknown input
     [state nil])))
