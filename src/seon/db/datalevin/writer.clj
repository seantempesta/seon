(ns seon.db.datalevin.writer
  "Flow step-fn that writes transactions to a Datalevin connection.

   Provides coordinated database writes via clojure.core.async.flow.
   Pause/resume gives backup coordination: pausing triggers a flush,
   ensuring all writes are committed before backup."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
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

(schema/register! ::tx-msg
  [:map
   [::tx-data ::tx-data]])

(schema/register! ::create-writer-request
  [:map
   [::conn ::conn]
   [::db-name {:optional true} ::db-name]])

(schema/register! ::inject-tx-request
  [:map
   [::flow ::flow]
   [::tx-msg ::tx-msg]])

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn db-writer-step
  "Flow step-fn that writes transactions to a Datalevin connection.

   Init args: {::conn <datalevin-conn> ::db-name <string>}

   Inputs:
     :in/transact - Transaction message with ::tx-data key

   Outputs:
     :out/result - Successful write result with timing
     :out/error  - Error details on write failure

   On pause/stop, flushes pending writes via empty transact."
  ;; describe
  ([]
   {:ins {:in/transact "Transaction data maps to write"}
    :outs {:out/result "Successful write results"
           :out/error "Write error details"}
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
     (:clojure.core.async.flow/pause :clojure.core.async.flow/stop)
     (do
       (try
         (when-let [conn (::conn state)]
           ;; Flush by issuing an empty transaction
           (d/transact! conn [])
           (log/debug "Flushed writes on" (name transition) "for" (::db-name state)))
         (catch Throwable e
           (log/warn e "Failed to flush on" (name transition) "for" (::db-name state))))
       state)

     :clojure.core.async.flow/resume
     state

     ;; default
     state))

  ;; transform
  ([state input-id tx-msg]
   (case input-id
     :in/transact
     (let [conn (::conn state)
           tx-data (::tx-data tx-msg)
           t0 (System/nanoTime)]
       (try
         (let [_result (d/transact! conn tx-data)
               elapsed-ms (/ (- (System/nanoTime) t0) 1e6)
               now (Instant/now)]
           [(-> state
                (update ::total-writes inc)
                (assoc ::last-write-at now))
            {:out/result [{::status :ok
                           ::db-name (::db-name state)
                           ::tx-count (count tx-data)
                           ::elapsed-ms elapsed-ms
                           ::at now}]}])
         (catch Exception e
           (let [elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
             (log/warn e "Write failed for" (::db-name state))
             [(update state ::total-errors inc)
              {:out/error [{::status :error
                            ::db-name (::db-name state)
                            ::error (.getMessage e)
                            ::elapsed-ms elapsed-ms
                            ::at (Instant/now)}]}]))))

     ;; unknown input
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; Convenience Helpers
;;; ---------------------------------------------------------------------------

(defn create-writer-flow
  "Create and start a writer flow with a single db-writer-step process.

   Request keys:
     ::conn    - Required. Datalevin connection
     ::db-name - Optional. Database name for logging

   Returns the flow object (already started and resumed)."
  {:malli/schema [:=> [:cat ::create-writer-request] ::flow]}
  [{::keys [conn db-name]}]
  (let [config {:procs {:writer {:proc (flow/process #'db-writer-step)
                                 :args {::conn conn ::db-name db-name}}}
                :conns []}
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
