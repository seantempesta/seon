(ns seon.db.datahike.tx-bus
  "core.async.flow step-fn that fans tx-reports out to registered subscribers.

   One instance per datahike flow. State lives in the flow process, not in
   an app-level atom (Decision 9 of the datahike-migration PRD).

   Inputs:
     :seon.db.datahike/tx-report - tx-report map from any conn-process
     :seon.db.datahike/sub       - {::db-name kw ::key kw ::callback fn}
     :seon.db.datahike/unsub     - {::db-name kw ::key kw}

   Outputs:
     :seon.db.datahike/delivery-error - reports for subscriber callbacks that
                                         threw (one entry per failed delivery).

   State:
     {::subscribers {[db-name key] callback}
      ::delivered   <long>
      ::errors      <long>}

   Callbacks are invoked inline (in the flow thread). They must be cheap
   and not block. If a callback throws, the exception is caught, counted,
   and emitted on :seon.db.datahike/delivery-error -- no other subscribers
   are affected."
  (:require [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name :keyword)
(schema/register! ::key :keyword)
(schema/register! ::callback fn?)

(schema/register! ::sub-request
  [:map
   [::db-name ::db-name]
   [::key ::key]
   [::callback ::callback]])

(schema/register! ::unsub-request
  [:map
   [::db-name ::db-name]
   [::key ::key]])

(schema/register! ::delivery-error
  [:map
   [::db-name ::db-name]
   [::key ::key]
   [:seon.db.datahike.tx-bus.error/class :string]
   [:seon.db.datahike.tx-bus.error/message :string]])

;;; ---------------------------------------------------------------------------
;;; Step Function
;;; ---------------------------------------------------------------------------

(defn tx-bus-step
  "core.async.flow step-fn that dispatches tx-reports to subscribers."
  ;; describe
  ([]
   {:ins {:seon.db.datahike/tx-report "tx-reports from conn-processes"
          :seon.db.datahike/sub "Subscribe request"
          :seon.db.datahike/unsub "Unsubscribe request"}
    :outs {:seon.db.datahike/delivery-error "Errors from subscriber callbacks"}
    :workload :io})

  ;; init
  ([_args]
   {::subscribers {}
    ::delivered 0
    ::errors 0})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop (assoc state ::subscribers {})
     :clojure.core.async.flow/pause state
     :clojure.core.async.flow/resume state
     state))

  ;; transform
  ([state input-id msg]
   (case input-id

     :seon.db.datahike/sub
     (let [{::keys [db-name key callback]} msg]
       (log/debug "tx-bus subscribe" {:db-name db-name :key key})
       [(assoc-in state [::subscribers [db-name key]] callback)
        nil])

     :seon.db.datahike/unsub
     (let [{::keys [db-name key]} msg]
       (log/debug "tx-bus unsubscribe" {:db-name db-name :key key})
       [(update state ::subscribers dissoc [db-name key])
        nil])

     :seon.db.datahike/tx-report
     (let [report msg
           db-name (:seon.db.datahike.tx-report/db-name report)
           matching (filter (fn [[[sub-db _] _]] (= sub-db db-name))
                            (::subscribers state))
           errors
           (reduce
            (fn [acc [[sub-db sub-key] cb]]
              (try
                (cb report)
                acc
                (catch Throwable t
                  (log/warn t "tx-bus subscriber callback threw"
                            {:db-name sub-db :key sub-key})
                  (conj acc
                        {::db-name sub-db
                         ::key sub-key
                         :seon.db.datahike.tx-bus.error/class (.getName (class t))
                         :seon.db.datahike.tx-bus.error/message
                         (or (.getMessage t) (.getName (class t)))}))))
            []
            matching)
           state' (-> state
                      (update ::delivered + (- (count matching) (count errors)))
                      (update ::errors + (count errors)))]
       [state'
        (cond-> nil
          (seq errors) (assoc :seon.db.datahike/delivery-error errors))])

     ;; unknown input
     [state nil])))
