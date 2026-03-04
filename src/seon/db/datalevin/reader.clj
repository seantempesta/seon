(ns seon.db.datalevin.reader
  "Infrastructure reader step-fn for Datalevin.

   Handles ALL databases via connection manager. Uses msg envelope format.
   Part of the infrastructure flow (seon.flow/infrastructure Integrant component).

   Dispatches on ::query-fn in the payload to call d/q, d/pull, d/pull-many,
   or d/entity on the appropriate database."
  (:require [datalevin.core :as d]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::query-fn
                  [:enum {:description "Datalevin read dispatch key"}
                   :q :pull :pull-many :entity])

(schema/register! ::db-name
                  [:string {:min 1 :description "Database name for routing"}])

(schema/register! ::args
                  [:vector {:description "Arguments to pass to query function"} :any])

(schema/register! ::total-reads
                  [:int {:min 0 :description "Total successful reads"}])

(schema/register! ::total-errors
                  [:int {:min 0 :description "Total read errors"}])

(schema/register! ::connection-manager
                  [:fn {:description "Datalevin connection manager"} some?])

(schema/register! ::owned-conns
                  [:map-of {:description "Reader-owned connections by db keyword"}
                   :keyword :any])

;;; ---------------------------------------------------------------------------
;;; Query Dispatch
;;; ---------------------------------------------------------------------------

(defn- execute-query
  "Dispatch a read query against the given connection."
  [conn query-fn args]
  (case query-fn
    :q          (apply d/q (first args) @conn (rest args))
    :pull       (d/pull @conn (first args) (second args))
    :pull-many  (d/pull-many @conn (first args) (second args))
    :entity     (d/entity @conn (first args))))

;;; ---------------------------------------------------------------------------
;;; Infrastructure Reader Step (multi-database, msg envelope format)
;;; ---------------------------------------------------------------------------

(defn infra-reader-step
  "Flow step-fn for the infrastructure flow reader.

   Handles ALL databases via a connection manager. Each request is a msg
   envelope with ::msg/payload containing ::query-fn, ::db-name, and ::args.

   Replies use the standard msg envelope format so the topology
   reply-router can deliver promises to callers.

   Init args:
     ::connection-manager - Datalevin connection manager (Integrant component)

   Inputs:
     :seon.flow.in/request - Message envelope (::msg/id, ::msg/payload)

   Outputs:
     :seon.flow.out/reply - Reply envelope for reply-router
     :seon.flow.out/error - Error envelope for error-sink"
  ;; describe
  ([]
   {:ins {:seon.flow.in/request "Query request envelopes"}
    :outs {:seon.flow.out/reply "Reply envelopes for reply-router"
           :seon.flow.out/error "Error envelopes for error-sink"}
    :workload :io})

  ;; init
  ([{::keys [connection-manager]}]
   {::connection-manager connection-manager
    ::owned-conns {}
    ::total-reads 0
    ::total-errors 0})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/pause
     (do (log/info "Infra reader paused" {:reads (::total-reads state)
                                           :errors (::total-errors state)})
         state)
     :clojure.core.async.flow/stop
     (let [owned (::owned-conns state)
           n     (count owned)]
       (when (pos? n)
         (doseq [[db-kw conn] owned]
           (try (d/close conn)
                (catch Exception e
                  (log/warn "Error closing owned conn" {:db db-kw :error (.getMessage e)}))))
         (log/info "Reader closed owned connections" {:count n}))
       (log/info "Infra reader stopped" {:reads (::total-reads state)
                                          :errors (::total-errors state)})
       (assoc state ::owned-conns {}))
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id request]
   (case input-id
     :seon.flow.in/request
     (let [request-id (::msg/id request)
           payload    (::msg/payload request)
           query-fn   (::query-fn payload)
           db-name    (::db-name payload)
           args       (::args payload)
           cm         (::connection-manager state)
           t0         (System/nanoTime)]
       (log/debug "Reader processing" {:db db-name :query-fn query-fn :request-id request-id})
       (let [db-kw     (keyword db-name)
             owned     (::owned-conns state)
             [conn new?] (if-let [c (get owned db-kw)]
                           [c false]
                           (let [c (dl-conn/get-conn! {::dl-conn/manager cm
                                                       ::dl-conn/db db-kw})]
                             (log/info "Reader acquired connection" {:db db-name})
                             [c true]))
             state     (if new?
                         (assoc-in state [::owned-conns db-kw] conn)
                         state)]
         (try
           (let [result     (execute-query conn query-fn args)
                 elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                 reply      {::msg/id request-id
                             ::msg/version 1
                             ::msg/type :reply
                             ::msg/status :ok
                             ::msg/value result
                             ::msg/from-ns "seon.db.reader"
                             ::msg/duration-ms elapsed-ms}]
             (when (> elapsed-ms 1000)
               (log/warn "Slow read" {:db db-name :query-fn query-fn :elapsed-ms elapsed-ms}))
             [(update state ::total-reads inc)
              {:seon.flow.out/reply [reply]}])
           (catch Exception e
             (if (dl-conn/connection-error? e)
               ;; Retry once with fresh connection
               (do
                 (log/warn "Reader reconnecting" {:db db-name :error (.getMessage e)})
                 (try (d/close conn) (catch Exception _))
                 (let [state (update state ::owned-conns dissoc db-kw)]
                   (try
                     (let [fresh      (dl-conn/get-conn! {::dl-conn/manager cm
                                                           ::dl-conn/db db-kw})
                           result     (execute-query fresh query-fn args)
                           elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                           reply      {::msg/id request-id
                                       ::msg/version 1
                                       ::msg/type :reply
                                       ::msg/status :ok
                                       ::msg/value result
                                       ::msg/from-ns "seon.db.reader"
                                       ::msg/duration-ms elapsed-ms}]
                       (log/info "Reader acquired connection" {:db db-name})
                       [(-> state
                            (assoc-in [::owned-conns db-kw] fresh)
                            (update ::total-reads inc))
                        {:seon.flow.out/reply [reply]}])
                     (catch Exception e2
                       (let [elapsed-ms  (long (/ (- (System/nanoTime) t0) 1e6))
                             error-reply {::msg/id request-id
                                          ::msg/version 1
                                          ::msg/type :reply
                                          ::msg/status :error
                                          ::msg/error-type :execution
                                          ::msg/error-class (.getName (class e2))
                                          ::msg/error-message (.getMessage e2)
                                          ::msg/from-ns "seon.db.reader"
                                          ::msg/duration-ms elapsed-ms}]
                         (log/error e2 "Read failed after retry" {:db db-name :request-id request-id})
                         [(update state ::total-errors inc)
                          {:seon.flow.out/reply [error-reply]
                           :seon.flow.out/error [error-reply]}])))))
               ;; Non-connection error, no retry
               (let [elapsed-ms  (long (/ (- (System/nanoTime) t0) 1e6))
                     error-reply {::msg/id request-id
                                  ::msg/version 1
                                  ::msg/type :reply
                                  ::msg/status :error
                                  ::msg/error-type :execution
                                  ::msg/error-class (.getName (class e))
                                  ::msg/error-message (.getMessage e)
                                  ::msg/from-ns "seon.db.reader"
                                  ::msg/duration-ms elapsed-ms}]
                 (log/error e "Read failed" {:db db-name :query-fn query-fn
                                              :elapsed-ms elapsed-ms :request-id request-id})
                 [(update state ::total-errors inc)
                  {:seon.flow.out/reply [error-reply]
                   :seon.flow.out/error [error-reply]}]))))))

     ;; unknown input
     [state nil])))
