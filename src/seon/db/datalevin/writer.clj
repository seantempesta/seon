(ns seon.db.datalevin.writer
  "Infrastructure writer step-fn for Datalevin.

   Handles ALL databases via connection manager. Uses msg envelope format.
   Part of the infrastructure flow (seon.flow/infrastructure Integrant component).

   Pause/resume gives backup coordination: pausing the writer triggers a
   flush, ensuring all writes are committed before backup.

   Backoff: When the server is completely down (both initial attempt and retry
   fail with connection errors), the writer enters an exponential backoff.
   During backoff, requests return error replies immediately without attempting
   connection. On success, backoff clears."
  (:require [datalevin.core :as d]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

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

(schema/register! ::connection-manager
                  [:fn {:description "Datalevin connection manager"} some?])

(schema/register! ::owned-conns
                  [:map-of {:description "Writer-owned connections by db keyword"}
                   :keyword :any])

(schema/register! ::backoff-ms
                  [:int {:min 0 :description "Current backoff duration in ms (0 = no backoff)"}])

(schema/register! ::backoff-until
                  [:int {:min 0 :description "Epoch ms when backoff expires (0 = no backoff)"}])

(def ^:const write-timeout-ms
  "Timeout for individual d/transact! calls in milliseconds.
   Prevents a hung Datalevin remote from blocking the writer thread indefinitely."
  30000)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(def ^:const ^:private initial-backoff-ms
  "First backoff duration after server-down detection."
  1000)

(def ^:const ^:private max-backoff-ms
  "Maximum backoff duration cap."
  30000)

(defn- make-error-reply
  "Build a standard error reply envelope."
  [request-id ^Throwable e from-ns elapsed-ms]
  {::msg/id request-id
   ::msg/version 1
   ::msg/type :reply
   ::msg/status :error
   ::msg/error-type :execution
   ::msg/error-class (.getName (class e))
   ::msg/error-message (.getMessage e)
   ::msg/from-ns from-ns
   ::msg/duration-ms elapsed-ms})

(defn- set-backoff
  "Set exponential backoff on state after server-down detection."
  [state]
  (let [now (System/currentTimeMillis)
        current (::backoff-ms state 0)
        next-ms (min max-backoff-ms (* 2 (max current initial-backoff-ms)))]
    (assoc state
           ::backoff-ms next-ms
           ::backoff-until (+ now next-ms))))

(defn- clear-backoff
  "Clear backoff state after a successful operation."
  [state]
  (assoc state ::backoff-ms 0 ::backoff-until 0))

(defn- in-backoff?
  "True if state is currently in backoff period."
  [state]
  (let [until (::backoff-until state 0)]
    (and (pos? until)
         (< (System/currentTimeMillis) until))))

(defn- transact-with-timeout!
  "Execute d/transact! with a future+deref timeout.
   Returns the tx-report on success.
   Throws on timeout (with a clear message) or if transact! itself throws."
  [conn tx-data db-label]
  (let [f (future (d/transact! conn tx-data))
        result (deref f write-timeout-ms ::timed-out)]
    (if (= result ::timed-out)
      (do
        (future-cancel f)
        (throw (ex-info "d/transact! timed out"
                        {:timeout-ms write-timeout-ms
                         :db db-label
                         :tx-count (count tx-data)})))
      result)))

;;; ---------------------------------------------------------------------------
;;; Infrastructure Writer Step (multi-database, msg envelope format)
;;; ---------------------------------------------------------------------------

(defn infra-writer-step
  "Flow step-fn for the infrastructure flow writer.

   Handles ALL databases via a connection manager. Each request is a msg
   envelope with ::msg/payload containing ::tx-data and ::db-name.

   Replies use the standard msg envelope format so the topology
   reply-router can deliver promises to callers.

   Backoff behavior:
     When server is completely down (retry also fails), enters exponential
     backoff (1s, 2s, 4s, ... up to 30s). During backoff, requests get
     immediate error replies without connection attempts. Backoff clears
     on first successful operation.

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
    ::owned-conns {}
    ::total-writes 0
    ::total-errors 0
    ::last-write-at nil
    ::backoff-ms 0
    ::backoff-until 0})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/pause
     (do (log/info "Infra writer paused" {:writes (::total-writes state)
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
         (log/info "Writer closed owned connections" {:count n}))
       (log/info "Infra writer stopped" {:writes (::total-writes state)
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
           tx-data    (::tx-data payload)
           db-name    (::db-name payload)
           cm         (::connection-manager state)
           t0         (System/nanoTime)
           tx-count   (count tx-data)]
       ;; Backoff check: if server was recently down, fail fast without attempting
       (if (in-backoff? state)
         (let [error-reply {::msg/id request-id
                            ::msg/version 1
                            ::msg/type :reply
                            ::msg/status :error
                            ::msg/error-type :backoff
                            ::msg/error-class "seon.db.datalevin.writer"
                            ::msg/error-message "Writer in backoff — server was unreachable"
                            ::msg/from-ns "seon.db.writer"
                            ::msg/duration-ms 0}]
           [(update state ::total-errors inc)
            {:seon.flow.out/reply [error-reply]}])
         ;; Normal path
         (let [db-kw      (keyword db-name)
               owned      (::owned-conns state)
               [conn new?] (if-let [c (get owned db-kw)]
                             [c false]
                             (let [c (dl-conn/get-conn! {::dl-conn/manager cm
                                                         ::dl-conn/db db-kw})]
                               (log/info "Writer acquired connection" {:db db-name})
                               [c true]))
               state      (if new?
                            (assoc-in state [::owned-conns db-kw] conn)
                            state)]
           (log/debug "Writer processing" {:db db-name :tx-count tx-count :request-id request-id})
           (try
             (let [_tx-report (transact-with-timeout! conn tx-data db-name)
                   elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                   now (Instant/now)
                   reply {::msg/id request-id
                          ::msg/version 1
                          ::msg/type :reply
                          ::msg/status :ok
                          ::msg/value {:db-name db-name}
                          ::msg/from-ns "seon.db.writer"
                          ::msg/duration-ms elapsed-ms}]
               (when (> elapsed-ms 1000)
                 (log/warn "Slow write" {:db db-name :elapsed-ms elapsed-ms :tx-count tx-count}))
               [(-> state
                    (update ::total-writes inc)
                    (assoc ::last-write-at now)
                    clear-backoff)
                {:seon.flow.out/reply [reply]}])
             (catch Exception e
               (if (dl-conn/connection-error? e)
                 ;; Retry once with fresh connection (stale cached conn case)
                 (do
                   (log/warn "Writer reconnecting" {:db db-name :error (.getMessage e)})
                   (try (d/close conn) (catch Exception _))
                   (let [state (update state ::owned-conns dissoc db-kw)]
                     (try
                       (let [fresh (dl-conn/get-conn! {::dl-conn/manager cm
                                                       ::dl-conn/db db-kw})
                             _tx-report (transact-with-timeout! fresh tx-data db-name)
                             elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                             now (Instant/now)
                             reply {::msg/id request-id
                                    ::msg/version 1
                                    ::msg/type :reply
                                    ::msg/status :ok
                                    ::msg/value {:db-name db-name}
                                    ::msg/from-ns "seon.db.writer"
                                    ::msg/duration-ms elapsed-ms}]
                         (log/info "Writer acquired connection" {:db db-name})
                         [(-> state
                              (assoc-in [::owned-conns db-kw] fresh)
                              (update ::total-writes inc)
                              (assoc ::last-write-at now)
                              clear-backoff)
                          {:seon.flow.out/reply [reply]}])
                       (catch Exception e2
                         (let [elapsed-ms  (long (/ (- (System/nanoTime) t0) 1e6))
                               error-reply (make-error-reply request-id e2 "seon.db.writer" elapsed-ms)
                               conn-err?   (dl-conn/connection-error? e2)]
                           ;; Connection error after retry = server down, enter backoff
                           ;; Non-connection error after retry = real bug, log full trace
                           (if conn-err?
                             (let [backoff-state (set-backoff state)]
                               (log/warn "Writer backing off"
                                         {:db db-name
                                          :backoff-ms (::backoff-ms backoff-state)
                                          :error (.getMessage e2)})
                               [(update backoff-state ::total-errors inc)
                                {:seon.flow.out/reply [error-reply]
                                 :seon.flow.out/error [error-reply]}])
                             (do
                               (log/error e2 "Write failed after retry" {:db db-name :request-id request-id})
                               [(update state ::total-errors inc)
                                {:seon.flow.out/reply [error-reply]
                                 :seon.flow.out/error [error-reply]}])))))))
                 ;; Non-connection error, no retry
                 (let [elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                       error-reply (make-error-reply request-id e "seon.db.writer" elapsed-ms)]
                   (log/error e "Write failed" {:db db-name :tx-count tx-count
                                                :elapsed-ms elapsed-ms :request-id request-id})
                   [(update state ::total-errors inc)
                    {:seon.flow.out/reply [error-reply]
                     :seon.flow.out/error [error-reply]}])))))))

     ;; unknown input
     [state nil])))
