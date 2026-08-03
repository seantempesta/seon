(ns seon.eval.drive
  "Run one bounded agent episode against an already-published source basis."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render.transcript :as transcript]
            [seon.sci.eval :as sci.eval])
  (:import [java.util Date UUID]))

(def ^:private default-run-cap 6)

(defn- uuid-text [] (str (UUID/randomUUID)))

(defn- agent-namespace [agent-id]
  (sci.eval/agent-namespace agent-id))

(defn- creation-request [cluster-name agent-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster/name cluster-name
   :seon.ns/name (agent-namespace agent-id)})

(defn- bootstrap-complete? [db agent-id]
  (let [run-id (bootstrap/run-id agent-id)]
    (when-let [closed-at
               (db/q '[:find ?closed .
                      :in $ ?run-id
                      :where
                      [?run :seon.cluster.run/id ?run-id]
                      [?run :seon.cluster.run/closed-at ?closed]]
                    db run-id)]
      (let [receipt-count
            (or (db/q '[:find (count ?receipt) .
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?receipt :seon.cluster.eval/run ?run]]
                     db run-id)
                0)]
        (when (= (count (bootstrap/agent-sources db agent-id))
                 receipt-count)
          {:seon.cluster.run/id run-id
           :seon.cluster.run/closed-at closed-at
           :seon.eval.drive/receipt-count receipt-count})))))

(defn- await-fact!
  [connection timeout-ms label probe]
  (let [events (async/promise-chan)
        listener-key (keyword "seon.eval.drive" (uuid-text))]
    (d/listen connection listener-key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (let [[value selected]
            (async/alts!! [events (async/timeout timeout-ms)] :priority true)]
        (when-not (= selected events)
          (throw (ex-info (str "Timed out awaiting " label ".")
                          {:seon.eval.drive/label label
                           :seon.eval.drive/timeout-ms timeout-ms})))
        value)
      (finally
        (d/unlisten connection listener-key)))))

(defn- inbound!
  [connection cluster-name process agent-id content]
  (let [caps (config/result-caps
              (config/effective @connection cluster-name))
        request {:seon.cluster.agent/id agent-id
                 :seon.cluster.message/inbound-content content
                 :seon.cluster.message/at (Date.)
                 :seon.config.eval.result/max-string
                 (:seon.config.eval.result/max-string caps)}
        before (message/inbound-tx @connection request)]
    (when-not (vector? before)
      (throw (ex-info "The objective message was refused."
                      {:seon.eval.drive/refusal before})))
    (let [result
          (db/transact!
           connection
           {:tx-data [[:db.fn/call #'message/inbound-tx request]]
            :tx-meta {:seon.db/process
                      [:seon.db.process/id process]}})]
      (when (:seon.error/kind result)
        (throw
         (ex-info "The objective message transaction was refused."
                  {:seon.eval.drive/refusal result}))))
    (or (db/q '[:find ?id .
               :in $ ?agent-id ?content
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?message :seon.cluster.message/to ?agent]
               [?message :seon.cluster.message/content ?content]
               [?message :seon.cluster.message/id ?id]]
             @connection agent-id content)
        (throw (ex-info "The committed objective message has no identity."
                        {:seon.cluster.agent/id agent-id})))))

(defn- objective-run-ids [db message-id]
  (->> (db/q '[:find ?run-id ?opened-tx
              :in $ ?message-id
              :where
              [?message :seon.cluster.message/id ?message-id]
              [?run :seon.cluster.run/trigger ?message]
              [?run :seon.cluster.run/id ?run-id ?opened-tx]]
            db message-id)
       (sort-by second)
       (mapv first)))

(defn- read-result [serialized]
  (when (string? serialized)
    (try
      (let [parsed (edn/read-string {:default (fn [_ value] value)}
                                    serialized)]
        (if (:seon.print/face parsed)
          (edn/read-string {:default (fn [_ value] value)}
                           (print/emit-text parsed {}))
          parsed))
      (catch Throwable _ ::unreadable))))

(defn- run-receipts [db run-ids]
  (if (seq run-ids)
    (->> (db/q '[:find ?run-id ?ordinal ?source ?result ?error ?error-kind ?at
                :in $ [?run-id ...]
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?form :seon.cluster.run.form/run ?run]
                [?form :seon.cluster.run.form/ordinal ?ordinal]
                [?form :seon.cluster.run.form/source ?source]
                [?receipt :seon.cluster.eval/run ?run]
                [?receipt :seon.cluster.eval/ordinal ?ordinal]
                [?receipt :seon.cluster.eval/at ?at]
                [(get-else $ ?receipt :seon.cluster.eval/result-edn "") ?result]
                [(get-else $ ?receipt :seon.cluster.eval/error "") ?error]
                [(get-else $ ?receipt :seon.error/kind :seon.eval.drive/absent)
                 ?error-kind]]
              db run-ids)
         (sort-by (juxt #(inst-ms (nth % 6)) second))
         (mapv (fn [[run-id ordinal source result error error-kind at]]
                 {:seon.cluster.run/id run-id
                  :seon.cluster.eval/ordinal ordinal
                  :seon.cluster.run.form/source source
                  :seon.cluster.eval/result-edn result
                  :seon.eval.drive/value (read-result result)
                  :seon.cluster.eval/error error
                  :seon.error/kind error-kind
                  :seon.cluster.eval/at at})))
    []))

(defn- completion-values [receipts]
  (into []
        (comp (map :seon.eval.drive/value)
              (filter #(= :completed (:my.run/disposition %))))
        receipts))

(defn- completed-result [receipts]
  (:my.run/result (last (completion-values receipts))))

(defn- model-attempts [db run-ids]
  (if (seq run-ids)
    (->> (db/q '[:find [?attempt ...]
                :in $ [?run-id ...]
                :where
                [?run :seon.cluster.run/id ?run-id]
                [?attempt :seon.ai.attempt/run ?run]]
              db run-ids)
         (map #(db/pull
                db
                '[:seon.ai.attempt/id
                  :seon.ai.attempt/ordinal
                  :seon.ai.attempt/at
                  :seon.ai/endpoint
                  :seon.ai/model
                  :seon.ai.attempt/settings-edn
                  :seon.ai.attempt/usage-edn
                  :seon.ai.attempt/finish-reason
                  :seon.ai/http-status
                  :seon.ai/request-transmitted?
                  :seon.ai/response-started?
                  :seon.ai/output-observed?
                  {:seon.ai.attempt/error
                   [:seon.error/kind
                    :seon.error/message
                    :seon.error/data-edn]}]
                %))
         (sort-by (juxt :seon.ai.attempt/at :seon.ai.attempt/ordinal))
         vec)
    []))

(defn- run-records [db run-ids]
  (mapv #(db/pull db
                 [:seon.cluster.run/id
                  :seon.cluster.run/opened-at
                  :seon.cluster.run/closed-at
                  :seon.cluster.run/plan-digest
                  :seon.cluster.run/error]
                 [:seon.cluster.run/id %])
        run-ids))

(defn- terminal-state [db agent-id process message-id run-cap]
  (let [run-ids (objective-run-ids db message-id)
        receipts (run-receipts db run-ids)
        completions (completion-values receipts)
        closed-count
        (if (seq run-ids)
          (count
           (db/q '[:find [?run ...]
                  :in $ [?run-id ...]
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?run :seon.cluster.run/closed-at _]]
                db run-ids))
          0)
        idle? (and (seq run-ids)
                   (nil? (work/next-agent-work
                          db
                          {:seon.cluster.agent/id agent-id
                           :seon.cluster.run/process process
                           :seon.cluster.work/now (java.util.Date.)})))]
    (cond
      (seq completions)
      {:seon.eval.drive/outcome :completed
       :seon.eval.drive/run-ids run-ids}

      (and idle? (>= closed-count run-cap))
      {:seon.eval.drive/outcome :capped
       :seon.eval.drive/run-ids run-ids}

      (and idle? (= closed-count (count run-ids)))
      {:seon.eval.drive/outcome :stopped
       :seon.eval.drive/run-ids run-ids}

      :else nil)))

(defn- full-transcript [db agent-id cluster-name]
  (transcript/render-ai
   {:seon.db/db db
    :seon.cluster.agent/id agent-id
    :seon.sci.admit/caps
    (config/result-caps (config/effective db cluster-name))
    ::transcript/token-budget 1000000000}))

(defn- grading-branch! [store ending-commit episode-id]
  (let [branch (keyword (str "inspect-grade-" episode-id))]
    (registry/branch! {:seon.store/store store
                       :seon.cluster.registry/from ending-commit
                       :seon.store/branch branch})
    branch))

(defn run-episode!
  "Run one objective in an already-running cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance :seon.schema/value]
    :seon.schema/value]}
  [instance request]
  (let [connection (:seon.boot/cluster-connection instance)
        process (cluster/process-identity (:seon.boot/advertisement instance))
        cluster-name (get-in instance [:seon.boot/config
                                       :seon.boot/cluster-name])
        episode-id (:seon.eval.drive/id request)
        objective (:seon.eval.drive/objective request)
        agent-ids (:seon.eval.drive/agent-ids request)
        agent-id (first agent-ids)
        run-cap (:seon.eval.drive/run-cap request)
        timeout-ms (:seon.eval.drive/remote-timeout-ms request)]
    (when-not (and (string? episode-id)
                   (not (str/blank? episode-id))
                   (string? objective)
                   (not (str/blank? objective))
                   (seq agent-ids)
                   (every? #(and (string? %) (not (str/blank? %))) agent-ids)
                   (pos-int? run-cap)
                   (pos-int? timeout-ms))
      (throw (ex-info "The episode request is incomplete."
                      {:seon.eval.drive/request request})))
    (doseq [id agent-ids]
      (cluster/ensure-entity! connection process
                              (creation-request cluster-name id)))
    (doseq [id agent-ids]
      (await-fact! connection 120000 (str "bootstrap " id)
                   #(bootstrap-complete? % id)))
    (let [message-id (inbound! connection cluster-name process agent-id objective)
          terminal
          (await-fact! connection timeout-ms (str "objective " episode-id)
                       #(terminal-state % agent-id process message-id run-cap))
          ending-db @connection
          ending-commit (db/commit-id ending-db)
          run-ids (:seon.eval.drive/run-ids terminal)
          receipts (run-receipts ending-db run-ids)
          store (:seon.store/store instance)
          grading-branch (grading-branch! store ending-commit episode-id)
          settings (config/effective ending-db cluster-name)]
      {:seon.eval.drive/id episode-id
       :seon.eval.drive/episode-semantics
       "one Inspect completion is one seeded Seon agent episode"
       :seon.eval.drive/cluster cluster-name
       :seon.eval.drive/agent-id agent-id
       :seon.eval.drive/agent-ids agent-ids
       :seon.eval.drive/message-id message-id
       :seon.eval.drive/ending-commit ending-commit
       :seon.eval.drive/grading-branch grading-branch
       :seon.eval.drive/terminal terminal
       :seon.eval.drive/run-ids run-ids
       :seon.eval.drive/runs (run-records ending-db run-ids)
       :seon.eval.drive/model-attempts (model-attempts ending-db run-ids)
       :seon.eval.drive/receipts receipts
       :seon.eval.drive/completed-result (completed-result receipts)
       :seon.eval.drive/transcript
       (full-transcript ending-db agent-id cluster-name)
       :seon.eval.drive/model (:seon.config.ai/model settings)
       :seon.eval.drive/thinking (:seon.config.ai/thinking settings)})))

(defn- sample-cluster-name []
  (str "inspect-sample-" (subs (uuid-text) 0 12)))

(defn run-sample!
  "Start, run, and retire one isolated sample cluster."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [request]
  (let [root (:seon.eval.drive/root request)
        sample-id (str (:seon.eval.drive/sample-id request))
        run-cap (or (:seon.eval.drive/run-cap request) default-run-cap)
        timeout-ms (or (:seon.eval.drive/remote-timeout-ms request)
                       (* run-cap 240000))
        cluster-name (sample-cluster-name)
        episode-id (str sample-id "-" (subs (uuid-text) 0 8))
        agent-id (str "inspect-" (subs (uuid-text) 0 12))
        instance* (volatile! nil)
        store* (volatile! nil)
        grading-branch* (volatile! nil)]
    (when-not (and (string? root) (not (str/blank? root)))
      (throw (ex-info "The sample request requires an operator cluster root."
                      {:seon.eval.drive/request request})))
    (try
      (let [instance
            (cluster/start!
             {:seon.boot/cluster-name cluster-name
              :seon.boot/root root
              :seon.config/manifest
              {:seon.config.run/max-episode-runs run-cap}})]
        (vreset! instance* instance)
        (vreset! store* (:seon.store/store instance))
        (let [report
              (run-episode!
               instance
               {:seon.eval.drive/id episode-id
                :seon.eval.drive/objective
                (:seon.eval.drive/objective request)
                :seon.eval.drive/agent-ids [agent-id]
                :seon.eval.drive/run-cap run-cap
                :seon.eval.drive/remote-timeout-ms timeout-ms})]
          (vreset! grading-branch*
                   (:seon.eval.drive/grading-branch report))
          (assoc report :seon.eval.drive/sample-id sample-id)))
      (catch Throwable failure
        (when-let [instance (:seon.boot/instance (ex-data failure))]
          (vreset! instance* instance)
          (vreset! store* (:seon.store/store instance)))
        (throw failure))
      (finally
        (when-let [instance @instance*]
          (cluster/stop! instance))
        (when-let [store @store*]
          (when-let [grading-branch @grading-branch*]
            (registry/retire-branch!
             {:seon.store/store store :seon.store/branch grading-branch}))
          (registry/retire-branch!
           {:seon.store/store store
            :seon.store/branch (registry/cluster-branch cluster-name)}))))))

(defn- qualified-name [value]
  (if-let [owner (namespace value)]
    (str owner "/" (name value))
    (name value)))

(defn- json-value [value]
  (cond
    (or (nil? value) (string? value) (boolean? value) (number? value)) value
    (or (keyword? value) (symbol? value)) (qualified-name value)
    (instance? Date value) (str (.toInstant ^Date value))
    (map? value)
    (into (sorted-map)
          (map (fn [[item-key item]]
                 [(if (or (keyword? item-key) (symbol? item-key))
                    (qualified-name item-key)
                    (str item-key))
                  (json-value item)]))
          value)
    (set? value) (mapv json-value (sort-by pr-str value))
    (sequential? value) (mapv json-value value)
    :else (str value)))

(defn run-sample-json!
  "Run one sample and return its qualified JSON projection."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [request]
  (json/write-str (json-value (run-sample! request))))
