(ns seon.db.datalevin.writer
  "Flow step-fns for writing transactions to Datalevin.

   Two layers:

   1. Legacy per-connection pipeline (db-writer-step + write-reply-step):
      Used by seon.db/transact! via create-writer-flow. Single conn, old ports.

   2. Infrastructure writer (infra-writer-step):
      Handles ALL databases via connection manager. Uses msg envelope format.
      Part of the infrastructure flow (seon.flow/infrastructure Integrant component).

   Pause/resume gives backup coordination: pausing the writer triggers a
   flush, ensuring all writes are committed before backup."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:fn {:description "Datalevin connection"} some?])

(schema/register! ::db-name
                  [:string {:min 1 :description "Database name for logging"}])

(schema/register! ::total-writes
                  [:int {:min 0 :description "Total successful writes"}])

(schema/register! ::total-errors
                  [:int {:min 0 :description "Total write errors"}])

(schema/register! ::last-write-at
                  [:maybe [:fn {:description "java.time.Instant or nil"} #(or (nil? %) (instance? Instant %))]])

(schema/register! ::tx-data
                  [:sequential {:description "Datalevin transaction data"} :any])

(schema/register! ::flow
                  [:fn {:description "core.async.flow flow object"} some?])

(schema/register! ::correlation-id
                  [:any {:description "Correlation ID for promise delivery (typically UUID)"}])

(schema/register! ::pending-promises
                  [:fn {:description "Atom of {correlation-id -> promise}"} #(instance? clojure.lang.Atom %)])

(schema/register! ::status
                  [:enum {:description "Write result status"} :ok :error])

(schema/register! ::tx-msg
                  [:map
                   [::tx-data ::tx-data]
                   [::correlation-id {:optional true} ::correlation-id]])

(schema/register! ::create-writer-request
                  [:map
                   [::conn ::conn]
                   [::db-name {:optional true} ::db-name]
                   [::pending-promises {:optional true} ::pending-promises]])

(schema/register! ::inject-tx-request
                  [:map
                   [::flow ::flow]
                   [::tx-msg ::tx-msg]])

;;; ---------------------------------------------------------------------------
;;; Writer Step Function (pure transactor — no promise knowledge)
;;; ---------------------------------------------------------------------------

(defn db-writer-step
  "Flow step-fn that writes transactions to a Datalevin connection.

   The writer knows nothing about promises. It is a pure transactor:
   receives tx-data, writes to Datalevin, emits results.

   Init args:
     ::conn    - Datalevin connection (runtime, not serializable)
     ::db-name - Database name for logging

   Inputs:
     :in/transact - Transaction message with ::tx-data and optional ::correlation-id

   Outputs:
     :out/result - Success result map with ::status :ok, ::tx-report, ::elapsed-ms,
                   and pass-through ::correlation-id
     :out/error  - Error result map with ::status :error, ::error, ::elapsed-ms,
                   and pass-through ::correlation-id

   On pause, flushes pending writes via empty transact."
  ;; describe
  ([]
   {:ins {:in/transact "Transaction data maps to write"}
    :outs {:out/result "Successful write results"
           :out/error "Failed write errors"}
    :workload :io})

  ;; init
  ([{::keys [conn db-name]}]
   {::conn conn
    ::db-name (or db-name "unknown")
    ::total-writes 0
    ::total-errors 0
    ::last-write-at nil})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/pause
     (do
       (try
         (when-let [conn (::conn state)]
           (if (d/closed? conn)
             (log/warn "Skipping flush on pause for" (::db-name state)
                       "- connection already closed")
             (do
               (d/transact! conn [])
               (log/debug "Flushed writes on pause for" (::db-name state)))))
         (catch Throwable e
           (log/warn e "Failed to flush on pause for" (::db-name state))))
       state)

     :clojure.core.async.flow/stop
     (do
       (log/debug "Writer stop transition for" (::db-name state) "- no flush (async safety)")
       state)

     :clojure.core.async.flow/resume
     state

     state))

  ;; transform
  ([state input-id tx-msg]
   (case input-id
     :in/transact
     (let [conn (::conn state)
           tx-data (::tx-data tx-msg)
           cid (::correlation-id tx-msg)
           t0 (System/nanoTime)]
       (try
         (let [tx-report (d/transact! conn tx-data)
               elapsed-ms (/ (- (System/nanoTime) t0) 1e6)
               now (Instant/now)
               result (cond-> {::status :ok
                               ::tx-report tx-report
                               ::elapsed-ms elapsed-ms}
                        cid (assoc ::correlation-id cid))]
           [(-> state
                (update ::total-writes inc)
                (assoc ::last-write-at now))
            {:out/result [result]}])
         (catch Exception e
           (let [elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
             (log/warn e "Write failed for" (::db-name state))
             (let [error-result (cond-> {::status :error
                                         ::error e
                                         ::elapsed-ms elapsed-ms}
                                  cid (assoc ::correlation-id cid))]
               [(update state ::total-errors inc)
                {:out/error [error-result]}])))))

     ;; unknown input
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; Reply Sink Step Function (delivers promises — no DB knowledge)
;;; ---------------------------------------------------------------------------

(defn write-reply-step
  "Flow step-fn that delivers write results to waiting callers via promises.

   Terminal sink: receives results from the writer, looks up the corresponding
   promise by ::correlation-id, and delivers. Messages without correlation-id
   are silently ignored (fire-and-forget writes).

   Init args:
     ::pending-promises - Atom of {correlation-id -> promise}

   Inputs:
     :in/result - Successful write results from the writer
     :in/error  - Failed write errors from the writer

   No outputs - this is a terminal sink."
  ;; describe
  ([]
   {:ins {:in/result "Successful write results"
          :in/error "Failed write errors"}
    :outs {}
    :workload :io})

  ;; init
  ([{::keys [pending-promises]}]
   {::pending-promises pending-promises
    ::delivered 0
    ::unmatched 0})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop state
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id msg]
   (let [cid (::correlation-id msg)]
     (if-not cid
       ;; No correlation-id — fire-and-forget write, nothing to deliver
       [state nil]
       (let [promises (::pending-promises state)]
         (if-let [p (get @promises cid)]
           (do
             (swap! promises dissoc cid)
             (deliver p msg)
             [(update state ::delivered inc) nil])
           ;; No matching promise — stale or already delivered
           [(update state ::unmatched inc) nil]))))))

;;; ---------------------------------------------------------------------------
;;; Convenience Helpers
;;; ---------------------------------------------------------------------------

(defn create-writer-flow
  "Create and start a writer flow with writer + reply-sink pipeline.

   The writer transacts data and emits results. The reply-sink delivers
   results to caller promises. Flow wiring connects them:
     writer :out/result -> reply :in/result
     writer :out/error  -> reply :in/error

   Request keys:
     ::conn             - Required. Datalevin connection
     ::db-name          - Optional. Database name for logging
     ::pending-promises - Optional. Atom for promise tracking (created if nil)

   Returns the flow object (already started and resumed)."
  {:malli/schema [:=> [:cat ::create-writer-request] ::flow]}
  [{::keys [conn db-name pending-promises]}]
  (let [promises (or pending-promises (atom {}))
        config {:procs {:writer {:proc (flow/process #'db-writer-step)
                                 :args {::conn conn
                                        ::db-name db-name}}
                        :reply  {:proc (flow/process #'write-reply-step)
                                 :args {::pending-promises promises}}}
                :conns [[[:writer :out/result] [:reply :in/result]]
                        [[:writer :out/error]  [:reply :in/error]]]}
        fl (flow/create-flow config)]
    (flow/start fl)
    (flow/resume fl)
    fl))

(defn inject-tx!
  "Inject a transaction into a writer flow.

   Request keys:
     ::flow   - Flow object from create-writer-flow
     ::tx-msg - Map with ::tx-data key containing datalevin transaction data

   Returns nil."
  {:malli/schema [:=> [:cat ::inject-tx-request] :any]}
  [{::keys [flow tx-msg]}]
  (flow/inject flow [:writer :in/transact] [tx-msg]))

;;; ---------------------------------------------------------------------------
;;; Infrastructure Writer Step (multi-database, msg envelope format)
;;; ---------------------------------------------------------------------------

(defn infra-writer-step
  "Flow step-fn for the infrastructure flow writer.

   Handles ALL databases via a connection manager. Each request is a msg
   envelope with ::msg/payload containing ::tx-data and ::db-name.

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
   {:ins {:seon.flow.in/request "Transaction request envelopes"}
    :outs {:seon.flow.out/reply "Reply envelopes for reply-router"
           :seon.flow.out/error "Error envelopes for error-sink"}
    :workload :io})

  ;; init
  ([{::keys [connection-manager]}]
   {::connection-manager connection-manager
    ::total-writes 0
    ::total-errors 0
    ::last-write-at nil})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/pause
     (do (log/debug "Infra writer paused" {:writes (::total-writes state)
                                            :errors (::total-errors state)})
         state)
     :clojure.core.async.flow/stop
     (do (log/debug "Infra writer stopped" {:writes (::total-writes state)
                                             :errors (::total-errors state)})
         state)
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id request]
   (case input-id
     :seon.flow.in/request
     (let [request-id (::msg/id request)
           payload    (::msg/payload request)
           tx-data    (::tx-data payload)
           db-name    (::db-name payload)
           cm         (::connection-manager state)
           t0         (System/nanoTime)]
       (try
         (let [conn (dl-conn/get-conn! {::dl-conn/manager cm
                                        ::dl-conn/db (keyword db-name)})
               _tx-report (d/transact! conn tx-data)
               elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
               now (Instant/now)
               reply {::msg/id request-id
                      ::msg/version 1
                      ::msg/type :reply
                      ::msg/status :ok
                      ::msg/value {:db-name db-name}
                      ::msg/from-ns "seon.db.writer"
                      ::msg/duration-ms elapsed-ms}]
           [(-> state
                (update ::total-writes inc)
                (assoc ::last-write-at now))
            {:seon.flow.out/reply [reply]}])
         (catch Exception e
           (let [elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                 error-reply {::msg/id request-id
                              ::msg/version 1
                              ::msg/type :reply
                              ::msg/status :error
                              ::msg/error-type :execution
                              ::msg/error-class (.getName (class e))
                              ::msg/error-message (.getMessage e)
                              ::msg/from-ns "seon.db.writer"
                              ::msg/duration-ms elapsed-ms}]
             (log/warn e "Infra write failed" {:db-name db-name})
             [(update state ::total-errors inc)
              {:seon.flow.out/reply [error-reply]
               :seon.flow.out/error [error-reply]}]))))

     ;; unknown input
     [state nil])))
