(ns seon.fault
  "The one core-fault route (AGENTS.md \"The error policy\").

  `record!` commits one escaped failure through `seon.error`'s recording
  (signature, occurrence identity and the writer-decided wake are its own);
  `fault!` applies the one policy to that committed outcome. Moved from
  `seon.cluster/commit-fault!` (M4 step 0); the Flow committer still calls
  `record!` through its `:seon.flow/commit-fault!` callback until its slice."
  (:require [clojure.core.async.flow :as-alias flow.core]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.flow :as-alias flow]))

(defn- tagged-run
  "The tagged agent's open turn, or nil.
  Attribution is STRUCTURAL: an agent graph's fault arrives tagged with
  its agent (structural provenance from the error-channel join), so
  attribution is that agent's one open turn — exact under concurrency,
  where the serial-era global query stopped being. That global query
  (`attributed-run`) is deleted at F2 §3.3."
  [db agent-id]
  (db/q '[:find ?id .
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?run :seon.turn/agent ?agent]
         (not [?run :seon.turn/closed-tx])
         [?run :seon.turn/id ?id]]
       db agent-id))

(defn- previously-reported-fault-signature?
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/signature] :boolean]}
  [database signature]
  (some?
   (db/q '[:find ?error .
           :in $ ?signature
           :where [?error :seon.error/signature ?signature]]
         database signature)))

(defn record!
  "Commit one escaped Throwable as one durable fact per delivery.

  TOTAL, never throws. Returns `[fact outcome previously-reported? occurrence]`
  (`:seon.fault/recording`), deriving
  `fact` and its content signature before the transaction attempt. Every
  delivery reaches the writer; `previously-reported?` lets the Flow committer
  suppress only stderr/panic output for a signature already seen in facts.
  `outcome` is `:seon.flow/committed` or the transaction failure value.

  Everything it needs is read fresh: the dials from the config
  singleton, the attribution from the database value at the fault's
  own basis. A fault from an agent graph carries its agent as a
  structural tag (F1 §6) and attributes through `tagged-run`. An
  UNTAGGED fault — the cluster graph's own, from the armer or the
  render proc — attributes to NO run, and that is correct rather than
  missing: it is not a run's fault. The serial-era fallback query is
  gone (F2 §3.3). It goes through
  `db/transact!`, which never throws. The signature query and Flow's
  process-local signature set bound notification only; recurrence remains the
  query-derived count of committed facts."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.boot/cluster-name :seon.db.process/id
                       :seon.sci.admit/caps :seon.error/recording-observation]
                  :seon.fault/recording]}
  [connection cluster-name process caps observation]
  (try
    (let [db (db/db connection)
          dials (config/effective db cluster-name)
          source-fault (:seon.error/source observation)
          agent-id (:seon.agent/id source-fault)
          run-id (when agent-id (tagged-run db agent-id))
          dropped-count (::flow/dropped-fault-count source-fault)
          threshold (:seon.config.eval.result/blob-threshold dials)
          request
          (cond-> {:seon.schema/projection (db/carried-projection db)
                   :seon.error/source source-fault
                   :seon.error/declared-schema (:seon.error/declared-schema observation)
                   :seon.error/id (str (random-uuid))
                   :seon.error/at (java.util.Date.)
                   :seon.error/process process
                   :seon.sci.admit/caps caps
                   :seon.error/basis-t (db/basis-t db)
                   :seon.config.error/recurrence-limit
                   (:seon.config.error/recurrence-limit dials)
                   ;; THE FAULT FAMILY'S OWN BOUND rides the request the
                   ;; committer builds, so `error/prepare` AND
                   ;; `error/commit-tx` — both of which declare it required —
                   ;; read the one dial this cluster's effective config
                   ;; carries. Handing it to only one of the two is how every
                   ;; core fault became unrecordable: `commit-tx` refused its
                   ;; contract and the operator printed the refusal about
                   ;; itself instead of the fault.
                   :seon.config.error/max-evidence-bytes
                   (:seon.config.error/max-evidence-bytes dials)}
            (:seon.config.error/escalate-to dials)
            (assoc :seon.config.error/escalate-to
                   (:seon.config.error/escalate-to dials))
            run-id (assoc :seon.turn/id run-id)
            agent-id (assoc :seon.agent/id agent-id))
          ;; The fault family's own bound decides how much evidence the
          ;; durable fact keeps; the blob threshold decides where the
          ;; complete evidence lives. Two decisions, two declared keys, ONE
          ;; request — `prepare` and `commit-tx` see the same value.
          prepared (error/prepare request)
          staged (when (or (let [size (:seon.error/data-size
                                       (:seon.error/fact prepared))]
                             ;; AN UNSERIALIZABLE EVIDENCE MEASURED NOTHING,
                             ;; so the fact carries no size; the content
                             ;; comparison below is what decides staging then.
                             (and (int? size) (> size threshold)))
                           (not= (:seon.error/data-content prepared)
                                 (:seon.error/data-edn (:seon.error/fact prepared))))
                   (blob/stage! connection (:seon.error/data-content prepared)))
          prepared-fact (cond-> (:seon.error/fact prepared)
                          staged (assoc :seon.error/data-blob (:seon.blob/digest staged))
                          (pos-int? dropped-count)
                          (assoc :seon.error/dropped-fault-count dropped-count
                                 :seon.error/dropped-fault-digest
                                 (::flow/dropped-fault-digest source-fault)))
          recording (error/recording db (assoc request :seon.error/fact prepared-fact))
          transaction-data (:seon.db/tx-data recording)
          fact (:seon.error/fact recording)
          signature (:seon.error/signature fact)
          previously-reported?
          (previously-reported-fault-signature? db signature)]
      (try
        (let [result (blob/with-publication!
                      connection (if staged [staged] [])
                      #(db/transact! connection transaction-data))]
          [fact (if (db/database-value? (:db-after result))
                  ::flow/committed
                  result)
           previously-reported? (:seon.error.occurrence/ref recording)])
        (catch Throwable failure
          [fact failure previously-reported? (:seon.error.occurrence/ref recording)])))
    (catch Throwable failure
      ;; `error/commit-tx` is total. This last-resort shape is only for a
      ;; failure before its fact exists, so no content signature is available
      ;; for Flow to collapse honestly.
      (let [fault (:seon.error/source observation)
            cause (if (instance? Throwable fault) fault (::flow.core/ex fault))
            message (or (:seon.error/message fault)
                        (when (instance? Throwable cause)
                          (str (.getName (class cause)) ": " (ex-message cause)))
                        (str fault))]
        [{:seon.error/message message} failure false nil]))))


(defn policy
  "The one decision: `:record` only when record mode's write committed.
  Every other pair — panic mode, an absent or unknown dial, a refused, thrown
  or outcome-unknown write — panics, production included."
  {:malli/schema [:=> [:cat [:or :nil :keyword] :boolean] [:enum :record :panic]]}
  [mode committed?]
  (if (and (= :record mode) committed?) :record :panic))

(defn- carried-receipt
  "The receipt a panic already carries anywhere in `failure`'s cause chain."
  {:malli/schema [:=> [:cat [:or :seon.error/throwable :map]] [:or :nil :seon.fault/recorded]]}
  [failure]
  (when (instance? Throwable failure)
    (some (comp :seon.fault/recorded ex-data)
          (take-while some? (iterate ex-cause failure)))))

(defn fault!
  "Record one core fault on the caller's thread, wake its responsible agent,
  and apply [[policy]]. Returns the `:seon.fault/recorded` receipt under
  `:record`; under `:panic` throws an `ex-info` whose data carries the receipt
  and whose cause is `failure` itself. A failure already carrying a receipt
  was recorded by this propagation: it is rethrown, never recounted.

  The wake is the writer's decision (`seon.error/commit-call`): the
  namespace steward, else the configured `:seon.config.error/escalate-to`
  (root by default). The dial is read after the write, from the cluster's
  current configuration; no write is retried."
  {:malli/schema [:=> [:cat :seon.env/environment [:or :seon.error/throwable :seon.error/base]
                       :seon.fault/context]
                  :seon.fault/recorded]}
  [environment failure context]
  (when (carried-receipt failure) (throw failure))
  (let [{connection :seon.db/connection cluster-name :seon.boot/cluster-name
         caps :seon.sci.admit/caps} environment
        agent-id (:seon.agent/id context)
        throwable? (instance? Throwable failure)
        [fact outcome _ occurrence]
        (record! connection cluster-name (:seon.db.process/id context) caps
                 {:seon.error/source
                  (cond-> (if throwable? {::flow.core/ex failure} failure)
                    agent-id (assoc :seon.agent/id agent-id))
                  :seon.error/declared-schema
                  (or (:seon.error/declared-schema context)
                      (when throwable? (:seon.error/declared-schema (meta (ex-data failure))))
                      :seon.flow/exception-error)})
        committed? (= ::flow/committed outcome)
        [mode read-failure]
        (when committed?
          (try [(:seon.config/on-core-error (config/effective (db/db connection) cluster-name))]
               (catch Throwable read-failure [nil read-failure])))
        receipt (cond-> {:seon.boot/cluster-name cluster-name
                         :seon.error/layer (:seon.error/layer context)
                         :seon.error/operation (:seon.error/operation context)
                         :seon.fault/committed? committed?
                         :seon.fault/policy (policy mode committed?)}
                  (:seon.error/signature fact) (assoc :seon.error/signature (:seon.error/signature fact))
                  occurrence (assoc :seon.error.occurrence/id (second occurrence)))]
    (if (= :record (:seon.fault/policy receipt))
      receipt
      (let [panic (ex-info (str "SEON CORE FAULT (panic): "
                                (or (:seon.error/message fact) (ex-message failure)))
                           (cond-> {:seon.fault/recorded receipt}
                             (not throwable?) (assoc :seon.error/base failure)
                             (map? outcome) (assoc :seon.fault/outcome outcome))
                           (when throwable? failure))]
        (doseq [suppressed [outcome read-failure] :when (instance? Throwable suppressed)]
          (.addSuppressed panic suppressed))
        (throw panic)))))
