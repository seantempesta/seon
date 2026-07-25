(ns flow.driver
  "The loop -- the only impure part.

   claim (CAS) -> for each step: receipt, eval, transform, commit -> release.

   The transform is at FORM granularity, not turn granularity:
   (db-after of the previous step, agent, step result) -> tx-data.
   Each step's basis is the transaction report's :db-after, so
   read-your-own-writes is free and there is no turn-wide basis to teach."
  (:require [datahike.api :as d]
            [flow.eval :as eval])
  (:import (java.util.concurrent Executors)))

(def schema
  (mapv (fn [[ident t & more]]
          (merge {:db/ident ident :db/valueType t
                  :db/cardinality (if (some #{:many} more)
                                    :db.cardinality/many :db.cardinality/one)}
                 (when (some #{:id} more) {:db/unique :db.unique/identity})))
        [[:agent/id :db.type/string :id] [:agent/counter :db.type/long]
         [:agent/log :db.type/string :many]
         [:message/id :db.type/string :id] [:message/to :db.type/ref]
         [:message/from :db.type/ref] [:message/body :db.type/string]
         [:run/id :db.type/string :id] [:run/agent :db.type/ref]
         [:run/message :db.type/ref] [:run/claimant :db.type/string]
         [:run/epoch :db.type/long] [:run/lease-until :db.type/long]
         [:run/open? :db.type/boolean]
         [:step/id :db.type/string :id] [:step/run :db.type/ref]
         [:step/index :db.type/long] [:step/source :db.type/string]
         [:seon.eval/id :db.type/string :id] [:seon.eval/run :db.type/ref]
         [:seon.eval/index :db.type/long] [:seon.eval/total :db.type/long]
         [:seon.eval/source :db.type/string] [:seon.eval/basis-t :db.type/long]
         [:seon.eval/outcome :db.type/keyword] [:seon.eval/fn-entries :db.type/long]
         [:seon.eval/ms :db.type/long] [:seon.eval/allocated-bytes :db.type/long]
         [:config/id :db.type/string :id] [:config/compute-permits :db.type/long]
         [:config/time-limit-ms :db.type/long] [:config/allocation-limit-bytes :db.type/long]
         [:config/lease-ms :db.type/long]]))

;;; The pure part -- callable with a database value and nothing else

(defn transform
  "PURE. (database value, agent id, step result) -> tx-data.
   No connection, no clock, no I/O, and no effects slot: a capability call
   happens INSIDE the step and is receipted there. A message to another agent
   is an ordinary FACT here, so delivery is atomic with the state change."
  [db agent-id {:keys [index value]}]
  (let [{:keys [facts messages note]} (when (map? value) value)
        seen (or (:agent/counter (d/pull db [:agent/counter] [:agent/id agent-id])) 0)]
    (into (vec facts)
          (concat [[:db/add [:agent/id agent-id] :agent/counter (inc seen)]]
                  (when note [[:db/add [:agent/id agent-id] :agent/log (str index ": " note)]])
                  (for [m messages]
                    {:message/id (str agent-id "->" (:to m) "#" index)
                     :message/to [:agent/id (:to m)] :message/from [:agent/id agent-id]
                     :message/body (:body m)})))))

(defn resume
  "PURE query: where is this run? Receipts are ORDERED (:seon.eval/index), so
   'form 3 of 7' is answerable, and the remaining sources come from the
   committed step plan -- never from re-parsing the reply."
  [db run-eid]
  (let [receipts (into (sorted-map)
                       (map (fn [[i o s]] [i {:outcome o :source s}]))
                       (d/q '[:find ?i ?o ?s :in $ ?r :where
                              [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                              [?e :seon.eval/outcome ?o] [?e :seon.eval/source ?s]]
                            db run-eid))
        steps (into (sorted-map)
                    (d/q '[:find ?i ?s :in $ ?r :where
                           [?e :step/run ?r] [?e :step/index ?i] [?e :step/source ?s]]
                         db run-eid))
        in-flight (first (filter #(= :running (:outcome (val %))) receipts))
        next-index (long (if in-flight (key in-flight) (count receipts)))]
    {:total (long (count steps)) :next-index next-index
     :in-flight (when in-flight {:index (key in-flight) :source (:source (val in-flight))})
     :remaining (mapv (fn [i] {:index i :source (steps i)})
                      (range next-index (count steps)))
     :sources steps}))

;;; The impure part

(defn config [db] (dissoc (d/pull db '[*] [:config/id "singleton"]) :db/id))

(defn agent-id-of [db run-eid]
  (get-in (d/pull db [{:run/agent [:agent/id]}] run-eid) [:run/agent :agent/id]))

(defn start-run!
  "Commit the run and its ORDERED step plan in one transaction. Idempotent:
   :run/id is a unique identity derived from the waking message."
  [conn {:keys [run-id agent-id message-eid sources]}]
  (let [existing (d/q '[:find ?r . :in $ ?id :where [?r :run/id ?id]] (d/db conn) run-id)]
    (or existing
        (let [tx (into [(cond-> {:db/id -1 :run/id run-id :run/agent [:agent/id agent-id]
                                 :run/epoch 0 :run/open? true}
                          message-eid (assoc :run/message message-eid))]
                       (map-indexed (fn [i s] {:step/id (str run-id "/" i) :step/run -1
                                               :step/index (long i) :step/source s}))
                       sources)]
          (d/q '[:find ?r . :in $ ?id :where [?r :run/id ?id]]
               (:db-after (d/transact conn {:tx-data tx})) run-id)))))

(defonce claims-lost (atom 0))

(defn claim!
  "CAS on the epoch. Returns the transaction report, or nil when lost."
  [conn run-eid me lease-ms]
  (let [{:run/keys [epoch]} (d/pull (d/db conn) [:run/epoch] run-eid)]
    (try (d/transact conn {:tx-data [[:db/cas run-eid :run/epoch epoch (inc epoch)]
                                     [:db/add run-eid :run/claimant me]
                                     [:db/add run-eid :run/lease-until
                                      (+ (System/currentTimeMillis) lease-ms)]]})
         (catch Throwable _ (swap! claims-lost inc) nil))))

(defn drive-run!
  "Fold the steps. Receipt BEFORE the act; the terminal receipt, the step's
   facts, its messages and the lease renewal all land in ONE transaction."
  ([conn run-eid me] (drive-run! conn run-eid me nil))
  ([conn run-eid me on-step]
  (let [cfg (config (d/db conn))
        run-id (:run/id (d/pull (d/db conn) [:run/id] run-eid))
        agent-id (agent-id-of (d/db conn) run-eid)]
    (loop [db (d/db conn) acc []]
      (let [{:keys [next-index total sources]} (resume db run-eid)]
        (if (>= next-index total)
          (do (d/transact conn {:tx-data [[:db/add run-eid :run/open? false]
                                          [:db/retract run-eid :run/claimant me]]})
              acc)
          (let [source (sources next-index)
                rid (str run-id "#" next-index)
                after-receipt (:db-after
                               (d/transact conn {:tx-data [{:seon.eval/id rid :seon.eval/run run-eid
                                                            :seon.eval/index (long next-index)
                                                            :seon.eval/total (long total)
                                                            :seon.eval/source source
                                                            :seon.eval/basis-t (:max-tx db)
                                                            :seon.eval/outcome :running}]}))
                _ (when on-step (on-step next-index source))
                {:flow/keys [value record]}
                (eval/evaluate {:source source :db after-receipt
                                :time-limit-ms (:config/time-limit-ms cfg)
                                :allocation-limit-bytes (:config/allocation-limit-bytes cfg)})
                tx (-> (transform after-receipt agent-id {:index next-index :value value})
                       (conj (assoc (dissoc record :flow/semaphore-wait-ms) :seon.eval/id rid))
                       (conj [:db/add run-eid :run/lease-until
                              (+ (System/currentTimeMillis) (:config/lease-ms cfg))]))]
            (recur (:db-after (d/transact conn {:tx-data tx}))
                   (conj acc (assoc record :seon.eval/index next-index :flow/value value))))))))))

(defn claimable
  "Open runs that are unclaimed or whose heartbeat lease has gone stale."
  [db]
  (mapv first
        (d/q '[:find ?r :in $ ?now :where
               [?r :run/open? true]
               (or-join [?r ?now]
                        (and [?r :run/open? true] (not-join [?r] [?r :run/claimant _]))
                        (and [?r :run/lease-until ?u] [(< ?u ?now)]))]
             db (System/currentTimeMillis))))

(defn waking-inbound
  "Messages nobody has opened a run for. [[message-eid agent-id] ...]"
  [db]
  (d/q '[:find ?m ?aid :where
         [?m :message/to ?a] [?a :agent/id ?aid]
         (not-join [?m] [?r :run/message ?m])]
       db))

;;; Wake -- event driven. One listen! on the committed-transaction feed, one
;;; virtual thread per scan. No ticker, no poll, no sleep.

(defonce io-pool (Executors/newVirtualThreadPerTaskExecutor))

(defn scan!
  "One pass: open runs for waking messages, then claim and drive what is
   claimable. `program` is the agent's reply: message body -> ordered sources."
  [conn me program]
  (doseq [[m aid] (waking-inbound (d/db conn))]
    (let [body (:message/body (d/pull (d/db conn) [:message/body] m))]
      (start-run! conn {:run-id (str "run/" aid "/" m) :agent-id aid :message-eid m
                        :sources (program {:agent-id aid :body body})})))
  (let [cfg (config (d/db conn))]
    (into [] (for [r (claimable (d/db conn))
                   :when (claim! conn r me (:config/lease-ms cfg))]
               [r (drive-run! conn r me)]))))

(defn wake!
  "Install the one interest. The callback must not transact (Datahike's own
   warning) -- it hands off to an :io virtual thread."
  [conn me program on-idle]
  (d/listen conn ::wake
            (fn [_report]
              (.submit ^java.util.concurrent.ExecutorService io-pool
                       ^Runnable (fn [] (try (let [done (scan! conn me program)]
                                               (when on-idle (on-idle done)))
                                             (catch Throwable t (println "scan failed" t))))))))
