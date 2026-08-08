(ns tool-exercise.probe
  "Drive ordered agent-authored forms through the real run loop.

  The MCP `door` mode has no run, so `seon.effect/request!` refuses with
  `:seon.effect/no-evaluation-context` and no capability ever crosses the
  door. This probe opens a SYSTEM RUN with caller-supplied sources — the
  same mechanism `seon.cluster.curate/prove!` uses for a proof — and drives
  `seon.cluster.loop/turn` until the run closes. Nothing here is a model
  call: the sources ARE the agent's forms, so the exercised path is
  sci eval -> call preparation -> effect door -> :io executor -> receipt ->
  settled value, exactly as a real turn."
  (:require [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.db :as db]
            [seon.operator.runtime :as rt])
  (:import [java.util Date UUID]))

(def ^:dynamic *cluster-name* "tools")

(defn instance [] (get @rt/running-instances *cluster-name*))

(defn- digest-value [value]
  (let [octets (.getBytes (pr-str value) "UTF-8")
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        octets)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(def receipt-selector
  [:seon.cluster.eval/ordinal :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob :seon.cluster.eval/result-size
   :seon.cluster.eval/error :seon.cluster.eval/output
   :seon.cluster.eval/interrupted-at :seon.sci.eval/ending-ns])

(def effect-selector
  [:seon.effect/id :seon.effect/form-ordinal :seon.effect/ordinal
   :seon.effect/request-edn :seon.effect/result-edn :seon.effect/result-blob
   :seon.effect/result-size :seon.effect/duration-ms
   :seon.effect/opened-at :seon.effect/settled-at
   :seon.effect/interrupted-at
   {:seon.effect/owner [:seon.fn/sym]}
   {:seon.effect/run [:seon.cluster.run/id]}])

(defn eval-receipts [database run-id]
  (->> (db/q '[:find ?receipt ?ordinal
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?receipt :seon.cluster.eval/run ?run]
               [?receipt :seon.cluster.eval/ordinal ?ordinal]]
             database run-id)
       (sort-by second)
       (mapv (fn [[receipt _]] (db/pull database receipt-selector receipt)))))

(defn effect-receipts [database run-id]
  (->> (db/q '[:find [?receipt ...]
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?receipt :seon.effect/run ?run]]
             database run-id)
       (mapv (fn [receipt] (db/pull database effect-selector receipt)))
       (sort-by (juxt :seon.effect/form-ordinal :seon.effect/ordinal))
       vec))

(defn open-run
  "The agent's currently open (unclosed) run id, when one exists."
  [database agent-id]
  (db/q '[:find ?id .
          :in $ ?agent-id
          :where
          [?a :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?a]
          [?run :seon.cluster.run/id ?id]
          (not [?run :seon.cluster.run/closed-at])]
        database agent-id))

(defn close-stale!
  "Close any run this agent still holds, so a probe fault cannot wedge it."
  [agent-id]
  (let [connection (:seon.boot/cluster-connection (instance))]
    (when-let [run-id (open-run @connection agent-id)]
      (let [process (db/q '[:find ?p .
                            :in $ ?run-id
                            :where
                            [?run :seon.cluster.run/id ?run-id]
                            [?run :seon.cluster.run/process ?p]]
                          @connection run-id)]
        {:closed run-id
         :result (db/transact!
                  connection
                  {:tx-data (run/close-tx {:seon.cluster.run/id run-id
                                           :seon.cluster.run/process process
                                           :seon.cluster.run/closed-at (Date.)})})}))))

(defn ensure-agent!
  "Create `agent-id` if absent and wait until it holds no open run."
  [agent-id]
  (let [inst (instance)
        connection (:seon.boot/cluster-connection inst)
        cluster-name (:seon.cluster/name (:seon.cluster.loop/cluster inst))]
    (when-not (db/pull @connection [:db/id] [:seon.cluster.agent/id agent-id])
      (let [created
            (cluster/ensure-entity!
             connection (:seon.cluster.run/process
                         (:seon.cluster.loop/cluster inst))
             {:seon.cluster.agent/id agent-id
              :seon.cluster/name cluster-name
              :seon.ns/name (symbol (str "my.agents." agent-id))})]
        (when (:seon.error/kind created)
          (throw (ex-info "agent creation refused" created)))))
    (loop [waited 0]
      (if-let [open (open-run @connection agent-id)]
        (if (< waited 60000)
          (do (Thread/sleep 250) (recur (+ waited 250)))
          {:seon.error/kind ::agent-busy :open-run open})
        {:seon.cluster.agent/id agent-id :waited-ms waited}))))

(defn drive!
  "Run `sources` (a vector of form source strings) as one system run.

  Returns the run id, the eval receipts, and the effect receipts. `:wait-ms`
  additionally sleeps for background effects to settle before reading."
  ([sources] (drive! sources {}))
  ([sources {:keys [agent-id starting-ns wait-ms turn-cap time-limit-ms]
             :or {agent-id "root" starting-ns 'my.agents.root
                  wait-ms 0 turn-cap 64}}]
   (let [inst (instance)
         connection (:seon.boot/cluster-connection inst)
         run-id (str "exercise:" (UUID/randomUUID))
         process (str "exercise:" (UUID/randomUUID))
         cluster (cond-> (assoc (:seon.cluster.loop/cluster inst)
                                :seon.cluster.run/process process)
                   time-limit-ms
                   (assoc :seon.config.eval/time-limit-ms time-limit-ms))
         request {:seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.cluster.run/process process
                  :seon.cluster.run/opened-at (Date.)
                  :seon.cluster.run/starting-ns [:seon.ns/name starting-ns]
                  :seon.cluster.run/plan-digest (digest-value sources)
                  :seon.cluster.run/sources
                  (mapv (fn [source]
                          {:seon.cluster.run.form/source source})
                        sources)}
         opened (db/transact! connection
                              {:tx-data (run/system-run-tx @connection request)})]
     (if (:seon.error/kind opened)
       opened
       (let [started (System/nanoTime)]
         (loop [turns 0]
           (when (< turns turn-cap)
             (when-let [next-work
                        (work/next-agent-work
                         @connection
                         {:seon.cluster.agent/id agent-id
                          :seon.cluster.run/process process
                          :seon.cluster.work/now (Date.)})]
               (when (= run-id (:seon.cluster.run/id next-work))
                 (cluster.loop/turn
                  {:seon.cluster.loop/cluster cluster
                   :seon.cluster.work/next next-work}
                  (Date.))
                 (recur (inc turns))))))
         (when (pos? wait-ms) (Thread/sleep (long wait-ms)))
         (let [database @connection]
           {:run-id run-id
            :process process
            :elapsed-ms (quot (- (System/nanoTime) started) 1000000)
            :evals (eval-receipts database run-id)
            :effects (effect-receipts database run-id)}))))))

(defn effects-for
  "Re-read one run's effect receipts at the current basis."
  [run-id]
  (effect-receipts @(:seon.boot/cluster-connection (instance)) run-id))
