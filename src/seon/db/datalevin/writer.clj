(ns seon.db.datalevin.writer
  "Infrastructure writer step-fn for Datalevin.

   Handles ALL databases via connection manager. Uses msg envelope format.
   Part of the infrastructure flow (seon.flow/infrastructure Integrant component).

   Pause/resume gives backup coordination: pausing the writer triggers a
   flush, ensuring all writes are committed before backup."
  (:require [datalevin.core :as d]
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

(schema/register! ::connection-manager
                  [:fn {:description "Datalevin connection manager"} some?])

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
           raw-conn   (::conn payload)
           cm         (::connection-manager state)
           t0         (System/nanoTime)]
       (try
         (let [conn (or raw-conn
                        (dl-conn/get-conn! {::dl-conn/manager cm
                                            ::dl-conn/db (keyword db-name)}))
               _tx-report (d/transact! conn tx-data)
               elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
               now (Instant/now)
               reply {::msg/id request-id
                      ::msg/version 1
                      ::msg/type :reply
                      ::msg/status :ok
                      ::msg/value {:db-name (or db-name "direct-conn")}
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
             (log/warn e "Infra write failed" {:db-name (or db-name "direct-conn")})
             [(update state ::total-errors inc)
              {:seon.flow.out/reply [error-reply]
               :seon.flow.out/error [error-reply]}]))))

     ;; unknown input
     [state nil])))
