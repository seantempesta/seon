(ns seon.db.datahike.flow
  "Build + start a core.async.flow topology that owns datahike connections.

   Topology:
     :seon.db.datahike.conn/<ns>   conn-process (one per namespace)
       :seon.db.datahike/request   in  <- request! injects here
       :seon.db.datahike/reply     out -> shared reply-router
       :seon.db.datahike/tx-report out -> tx-bus
     :seon.db.datahike/tx-bus      subscriber fan-out
       :seon.db.datahike/tx-report in
       :seon.db.datahike/sub       in
       :seon.db.datahike/unsub     in
       :seon.db.datahike/delivery-error out -> error-sink
     :seon.flow/reply-router       (reused from seon.flow.topology)
       :seon.flow.in/reply         in <- rewired from conn-process reply
     :seon.flow/error-sink         (reused sink for reporting)

   Reply-router decision: we reuse `seon.flow.topology/reply-router-step`
   together with its global `pending-promises` atom. This atom is flow-
   infrastructure (keyed by request-id UUID -- collision-free across flows),
   NOT DB state; DB state (connections, subscriber maps) still lives in flow
   process state per Decision 9. Alternative (b) -- a parallel reply router
   with promises in its own flow state -- is valid but more invasive for no
   practical gain right now. If Phase 2 shows a need, we can migrate.

   Single-writer guard (Decision 3): build-datahike-flow! rejects
   configs that request two conn-processes targeting the same
   :store path. This prevents two :writer :self connections from
   silently corrupting the same konserve store."
  (:require [clojure.core.async.flow :as flow]
            [seon.db.datahike.conn-process :as conn-process]
            [seon.db.datahike.tx-bus :as tx-bus]
            [seon.flow.msg :as msg]
            [seon.flow.topology :as topology]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::namespaces [:vector :keyword])
(schema/register! ::backend [:enum :memory :file])
(schema/register! ::data-root [:string {:description "Root directory for file backend"}])
(schema/register! ::db-name :keyword)
(schema/register! ::op :keyword)
(schema/register! ::args [:sequential :any])
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::from-ns :string)
(schema/register! ::key :keyword)
(schema/register! ::callback fn?)
(schema/register! ::flow :some)
(schema/register! ::pids :map)
(schema/register! ::flow-id :keyword)
(schema/register! ::chans :map)

(schema/register! ::namespace-schemas
                  [:map-of :keyword :any])

(schema/register! ::aliases
                  [:map-of :keyword :keyword])

(schema/register! ::flow-state
                  [:map
                   [::flow ::flow]
                   [::flow-id ::flow-id]
                   [::pids ::pids]
                   [::chans ::chans]
                   [::aliases {:optional true} ::aliases]])

(schema/register! ::build-datahike-flow!-request
                  [:map
                   [::namespaces ::namespaces]
                   [::backend ::backend]
                   [::data-root {:optional true} ::data-root]
                   [::namespace-schemas {:optional true} ::namespace-schemas]])

(schema/register! ::build-datahike-flow!-response ::flow-state)

(schema/register! ::namespace-config-request
                  [:map
                   [::db-name ::db-name]
                   [::backend ::backend]
                   [::data-root {:optional true} ::data-root]])

(schema/register! ::namespace-config-response :map)

(schema/register! ::request!-request
                  [:map
                   [::flow ::flow-state]
                   [::db-name ::db-name]
                   [::op ::op]
                   [::args {:optional true} ::args]
                   [::timeout-ms {:optional true} ::timeout-ms]
                   [::from-ns {:optional true} ::from-ns]])

(schema/register! ::request!-response :any)

(schema/register! ::subscribe!-request
                  [:map
                   [::flow ::flow-state]
                   [::db-name ::db-name]
                   [::key ::key]
                   [::callback ::callback]])

(schema/register! ::subscribe!-response :nil)

(schema/register! ::unsubscribe!-request
                  [:map
                   [::flow ::flow-state]
                   [::db-name ::db-name]
                   [::key ::key]])

(schema/register! ::unsubscribe!-response :nil)

(schema/register! ::stop-datahike-flow!-request
                  [:map
                   [::flow {:optional true} ::flow]])

(schema/register! ::stop-datahike-flow!-response :nil)

;;; ---------------------------------------------------------------------------
;;; Config Derivation
;;; ---------------------------------------------------------------------------

(defn- db-name-slug
  "Collision-free string form of a db-name keyword. `:seon.phase1.a` becomes
   `\"seon.phase1.a\"`; namespaced keywords like `:tenant-a/db` become
   `\"tenant-a__db\"` so filesystem paths and process-id strings can't collide
   across different namespaces with the same name part."
  [db-name]
  (if-let [ns (namespace db-name)]
    (str ns "__" (name db-name))
    (name db-name)))

(defn- stable-id
  "Deterministic UUID derived from a db-name keyword. Used as :store :id so
   repeated :memory connects see the same store; for :file it's a stable id."
  [db-name]
  (UUID/nameUUIDFromBytes (.getBytes (db-name-slug db-name)
                                     StandardCharsets/UTF_8)))

(defn namespace-config
  "Derive a datahike config map for a single namespace."
  {:malli/schema [:=> [:cat ::namespace-config-request] ::namespace-config-response]}
  [{::keys [db-name backend data-root]}]
  (let [id (stable-id db-name)
        slug (db-name-slug db-name)
        store (case backend
                :memory {:backend :memory :id id}
                :file (do (when (empty? data-root)
                            (throw (ex-info ":data-root is required for :file backend"
                                            {:db-name db-name})))
                          {:backend :file
                           :path (str data-root "/" slug)
                           :id id}))]
    {:store store
     :schema-flexibility :write
     :keep-history? false
     :writer {:backend :self}}))

(defn- conn-pid
  "Process id keyword for a namespace conn-process. Uses db-name-slug so
   pids are collision-free across differently namespaced db-names."
  [db-name]
  (keyword "seon.db.datahike.conn" (db-name-slug db-name)))

(defn- guard-single-writer!
  "Decision 3: reject if two namespaces would open a :writer :self against
   the same konserve store path."
  [configs]
  (let [store-key (fn [cfg]
                    (let [s (:store cfg)]
                      [(:backend s) (or (:path s) (:id s))]))
        by-store (group-by (comp store-key ::config) configs)
        dupes (into {} (filter (fn [[_ v]] (> (count v) 1)) by-store))]
    (when (seq dupes)
      (throw (ex-info
              (str "Single-writer guard (Decision 3): multiple conn-processes "
                   "would claim the same store. Refusing to build.")
              {:duplicates
               (into {}
                     (map (fn [[k v]] [k (mapv ::db-name v)])
                          dupes))})))))

;;; ---------------------------------------------------------------------------
;;; Build / Stop
;;; ---------------------------------------------------------------------------

(defn build-datahike-flow!
  "Build and start a datahike-backed flow.

   Opts:
     ::namespaces         - vector of db-name keywords
     ::backend            - :memory | :file
     ::data-root          - base dir for :file backend (required when :file)
     ::namespace-schemas  - optional map of db-name -> Malli :map schema to
                            install at :init. Missing = no schema installed
                            (only system idents will be present).

   Returns a ::flow-state map."
  {:malli/schema [:=> [:cat ::build-datahike-flow!-request] ::build-datahike-flow!-response]}
  [{::keys [namespaces backend data-root namespace-schemas]}]
  (let [configs
        (mapv (fn [db-name]
                {::db-name db-name
                 ::config (namespace-config
                           (cond-> {::db-name db-name ::backend backend}
                             data-root (assoc ::data-root data-root)))
                 ::malli-schema (get namespace-schemas db-name)})
              namespaces)

        _ (guard-single-writer! configs)

        pids (into {} (map (fn [{::keys [db-name]}] [db-name (conn-pid db-name)])) configs)

        conn-procs
        (into {}
              (map (fn [{::keys [db-name config malli-schema]}]
                     [(conn-pid db-name)
                      {:proc (flow/process #'conn-process/conn-process-step)
                       :args {::conn-process/config config
                              ::conn-process/db-name db-name
                              ::conn-process/malli-schema malli-schema}}]))
              configs)

        bus-proc
        {:seon.db.datahike/tx-bus
         {:proc (flow/process #'tx-bus/tx-bus-step)}}

        router-proc
        {:seon.flow/reply-router
         {:proc (flow/process #'topology/reply-router-step)}}

        error-sink
        {:seon.flow/error-sink
         {:proc (flow/process #'topology/error-sink-step)}}

        conns
        (into []
              (mapcat
               (fn [{::keys [db-name]}]
                 [;; conn-process reply -> reply-router
                  [[(conn-pid db-name) :seon.db.datahike/reply]
                   [:seon.flow/reply-router :seon.flow.in/reply]]
                  ;; conn-process tx-report -> tx-bus
                  [[(conn-pid db-name) :seon.db.datahike/tx-report]
                   [:seon.db.datahike/tx-bus :seon.db.datahike/tx-report]]]))
              configs)

        conns (conj conns
                    ;; tx-bus delivery-error -> error-sink
                    [[:seon.db.datahike/tx-bus :seon.db.datahike/delivery-error]
                     [:seon.flow/error-sink :seon.flow.out/error]])

        config {:procs (merge conn-procs bus-proc router-proc error-sink)
                :conns conns}
        fl (flow/create-flow config)
        chans (flow/start fl)]
    (flow/resume fl)
    (try
      (flow/ping fl :timeout-ms 5000)
      (catch Exception e
        (flow/stop fl)
        (throw (ex-info "Datahike flow failed to start: processes did not respond to ping within 5s"
                        {:namespaces namespaces
                         :pids (vals pids)}
                        e))))
    (log/info "Datahike flow started"
              {:namespaces namespaces :backend backend})
    {::flow fl
     ::flow-id :seon.db.datahike/flow
     ::chans chans
     ::pids pids
     ::backend backend
     ::data-root data-root
     ::started-at (Instant/now)}))

(defn stop-datahike-flow!
  "Halt a datahike flow. Safe to call multiple times."
  {:malli/schema [:=> [:cat ::stop-datahike-flow!-request] ::stop-datahike-flow!-response]}
  [{::keys [flow]}]
  (when flow
    (try
      (flow/pause flow)
      (flow/ping flow :timeout-ms 3000)
      (catch Throwable t
        (log/warn t "Error pausing datahike flow before stop")))
    (flow/stop flow))
  nil)

;;; ---------------------------------------------------------------------------
;;; Request / Subscribe helpers
;;; ---------------------------------------------------------------------------

(defn request!
  "Send an op to a datahike conn-process and wait for the reply.

   Returns the ::msg/value from the reply on success. Throws on timeout
   or :error status. Uses the shared topology/pending-promises atom; request
   ids are uuids, so cross-flow collisions are not a concern."
  {:malli/schema [:=> [:cat ::request!-request] ::request!-response]}
  [{::keys [flow db-name op args timeout-ms from-ns]
    :or {args [] timeout-ms 10000 from-ns "seon.db"}}]
  (let [fl (::flow flow)
        pids (::pids flow)
        pid (get pids db-name)]
    (when-not pid
      (throw (ex-info (str "No conn-process registered for " db-name)
                      {:db-name db-name :known (keys pids)})))
    (let [request-id (random-uuid)
          pending topology/pending-promises
          p (promise)
          request {::msg/id request-id
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns from-ns
                   ::msg/to-ns (str db-name)
                   ::msg/created-at (Instant/now)
                   ::msg/payload {::conn-process/op op
                                  ::conn-process/args (vec args)}}]
      (swap! pending assoc request-id p)
      (try
        (flow/inject fl [pid :seon.db.datahike/request] [request])
        (let [reply (deref p timeout-ms ::timed-out)]
          (if (= reply ::timed-out)
            (do
              (swap! pending dissoc request-id)
              (throw (ex-info "Datahike request timed out"
                              {::msg/status :timeout
                               ::msg/id request-id
                               ::db-name db-name
                               ::op op
                               ::timeout-ms timeout-ms})))
            (case (::msg/status reply)
              :ok (::msg/value reply)
              (throw (ex-info (or (::msg/error-message reply)
                                  (str "Datahike request failed: " (::msg/status reply)))
                              (select-keys reply [::msg/status ::msg/error-type
                                                  ::msg/error-class ::msg/error-message
                                                  ::msg/id ::msg/duration-ms]))))))
        (catch Exception e
          (swap! pending dissoc request-id)
          (throw e))))))

(defn subscribe!
  "Register a tx-report callback on the datahike flow's tx-bus.

   The callback receives the tx-report map and is invoked in the tx-bus
   flow thread. Keep it cheap. Exceptions in the callback are caught
   and reported on :seon.db.datahike/delivery-error."
  {:malli/schema [:=> [:cat ::subscribe!-request] ::subscribe!-response]}
  [{::keys [flow db-name key callback]}]
  (let [fl (::flow flow)]
    (flow/inject fl [:seon.db.datahike/tx-bus :seon.db.datahike/sub]
                 [{::tx-bus/db-name db-name
                   ::tx-bus/key key
                   ::tx-bus/callback callback}]))
  nil)

(defn unsubscribe!
  "Remove a tx-report subscriber from the datahike flow's tx-bus."
  {:malli/schema [:=> [:cat ::unsubscribe!-request] ::unsubscribe!-response]}
  [{::keys [flow db-name key]}]
  (let [fl (::flow flow)]
    (flow/inject fl [:seon.db.datahike/tx-bus :seon.db.datahike/unsub]
                 [{::tx-bus/db-name db-name
                   ::tx-bus/key key}]))
  nil)
