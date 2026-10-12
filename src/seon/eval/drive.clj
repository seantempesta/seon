(ns seon.eval.drive
  "Run one bounded agent episode against an already-published source basis."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.operator.runtime :as operator.runtime]
            [seon.render.transcript :as transcript])
  (:import [java.util UUID]))

(def ^:private default-run-cap 6)

(defn- uuid-text [] (str (UUID/randomUUID)))

(defn- default-agent-namespace [agent-id]
  (symbol (str "my.agents." agent-id)))

(defn- creation-request [cluster-name agent-id]
  {:seon.agent/id agent-id
   :seon.cluster/name cluster-name
   :seon.ns/name (default-agent-namespace agent-id)})

(defn await-fact!
  "The first truthy value `probe` answers for `connection`'s database, from its
  current value or a commit after it; throws when none arrives within
  `timeout-ms`. The listener is registered before the first read, so no
  commit between them is missed."
  {:malli/schema [:=> [:cat :seon.db/connection pos-int? :string [:fn clojure.core/ifn?]] :seon.schema/value]}
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

(defn inbound!
  "Write one outside message to `agent-id` on `connection` and return its id."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.boot/cluster-name
                       :seon.db.process/id :seon.agent/id :string]
                  :seon.message/id]}
  [connection cluster-name process agent-id content]
  (let [caps (config/result-caps
              (config/effective @connection cluster-name))
        request {:seon.agent/id agent-id :seon.message/inbound-content content :seon.config.eval.result/max-string (:seon.config.eval.result/max-string caps)}
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
      (when (or (:seon.db.write.attempt/request-id result)
                (:seon.db/invalid-read result) (:seon.schema/expected-value result))
        (throw
         (ex-info "The objective message transaction was refused."
                  {:seon.eval.drive/refusal result}))))
    (or (db/q '[:find ?id .
               :in $ ?agent-id ?content
               :where
               [?agent :seon.agent/id ?agent-id]
               [?message :seon.message/to ?agent]
               [?message :seon.message/content ?content]
               [?message :seon.message/id ?id]]
             @connection agent-id content)
        (throw (ex-info "The committed objective message has no identity."
                        {:seon.agent/id agent-id})))))

(defn- objective-run-ids [db message-id]
  (->> (db/q '[:find ?run-id ?opened-tx
              :in $ ?message-id
              :where
              [?message :seon.message/id ?message-id]
              [?run :seon.turn/trigger ?message]
              [?run :seon.turn/id ?run-id ?opened-tx]]
            db message-id)
       (sort-by second)
       (mapv first)))

(defn run-receipts
  "Ordered fact-space receipt values for `run-ids`."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:vector :seon.turn/id]]
                  [:vector :seon.eval.drive/evaluation]]}
  [db run-ids]
  (if (seq run-ids)
    (->> (db/q '[:find ?run-id ?ordinal ?source ?result ?error ?output ?at
                :in $ [?run-id ...]
                :where
                [?run :seon.turn/id ?run-id]
                [?form :seon.cluster.eval/run ?run]
                [?form :seon.cluster.eval/ordinal ?ordinal]
                [?form :seon.cluster.eval/source ?source]
                [?receipt :seon.cluster.eval/run ?run]
                [?receipt :seon.cluster.eval/ordinal ?ordinal]
                [?receipt :seon.cluster.eval/at ?at]
                [(get-else $ ?receipt :seon.eval/shown ::absent) ?result]
                [(get-else $ ?receipt :seon.cluster.eval/error ::absent) ?error]
                [(get-else $ ?receipt :seon.cluster.eval/output ::absent) ?output]]
              db run-ids)
         (sort-by (juxt #(inst-ms (nth % 6)) second))
         ;; An absent shown text, error or output is no key, never an empty-string sentinel.
         (mapv (fn [[run-id ordinal source result error output at]]
                 (cond-> {:seon.turn/id run-id
                          :seon.cluster.eval/ordinal ordinal
                          :seon.cluster.eval/source source
                          :seon.cluster.eval/at at}
                   (not= ::absent result) (assoc :seon.eval/shown result)
                   (not= ::absent error) (assoc :seon.cluster.eval/error error)
                   (not= ::absent output) (assoc :seon.cluster.eval/output output)))))
    []))

(defn- completed-results
  "The `:seon.turn/result` of each of `run-ids` settled `:completed`, in run order.
  Completion is the settlement's facts, never a reading of shown text."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.turn/id]]
                  [:vector :seon.turn/result]]}
  [db run-ids]
  (if (seq run-ids)
    (->> (db/q '[:find ?opened-tx ?result
                :in $ [?run-id ...]
                :where
                [?run :seon.turn/id ?run-id]
                [?run :seon.turn/disposition :completed]
                [?run :seon.turn/opened-tx ?opened-tx]
                [?run :seon.turn/result ?result]]
              db run-ids)
         (sort-by first)
         (mapv second))
    []))

(defn completed-result
  "The last completed result of `run-ids`, from the settlement's facts."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.turn/id]]
                  [:maybe :seon.turn/result]]}
  [db run-ids]
  (peek (completed-results db run-ids)))

(defn- model-attempts
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.turn/id]]
                  [:vector :seon.db/pulled-entity]]}
  [db run-ids]
  (if (seq run-ids)
    (->> (db/q '[:find [?attempt ...]
                :in $ [?run-id ...]
                :where
                [?run :seon.turn/id ?run-id]
                [?run :seon.turn/attempts ?attempt]]
              db run-ids)
         (map #(db/pull
                db
                '[:seon.ai.attempt/id
                  :seon.ai.attempt/ordinal
                  :seon.ai.attempt/at
                  :seon.ai/endpoint
                  :seon.ai/model
                  {:seon.ai.attempt/settings [:db/id :seon.config/agent :seon.config/cluster]}
                  :seon.ai.usage/prompt-tokens
                  :seon.ai.usage/completion-tokens
                  :seon.ai.usage/total-tokens
                  :seon.ai.usage/cached-tokens
                  :seon.ai.attempt/finish-reason
                  :seon.ai/http-status
                  :seon.ai/request-transmitted?
                  :seon.ai/response-started?
                  :seon.ai/output-observed?
                  {:seon.ai.attempt/error
                   [:seon.error/message
                    :seon.error/data-edn]}]
                %))
         (sort-by (juxt :seon.ai.attempt/at :seon.ai.attempt/ordinal))
         vec)
    []))

(defn- run-records [db run-ids]
  (mapv #(db/pull db
                 [:seon.turn/id
                  :seon.turn/opened-tx
                  :seon.turn/closed-tx
                  :seon.turn/reply-size]
                 [:seon.turn/id %])
        run-ids))

(defn terminal-state
  "Derive a terminal episode state from a trigger or explicit proof run ids."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.agent/id
          :seon.db.process/id :seon.message/id [:int {:min 1}]]
     [:maybe :seon.eval.drive/terminal-state]]
    [:=> [:cat :seon.db/database-value :seon.agent/id
          :seon.db.process/id
          [:map [:seon.eval.drive/run-ids :seon.eval.drive/run-ids]
           [:seon.eval.drive/run-cap :seon.eval.drive/run-cap]]]
     [:maybe :seon.eval.drive/terminal-state]]]}
  ([db agent-id process message-id run-cap]
   (terminal-state db agent-id process
                   {:seon.eval.drive/run-ids
                    (objective-run-ids db message-id)
                    :seon.eval.drive/run-cap run-cap}))
  ([db agent-id process request]
   (let [run-ids (:seon.eval.drive/run-ids request)
         run-cap (:seon.eval.drive/run-cap request)
        completions (completed-results db run-ids)
        undisposed? (and (seq run-ids) (empty? completions)
                          (every? #(some? (:seon.turn/closed-tx %))
                                  (run-records db run-ids)))
        closed-count
        (if (seq run-ids)
          (count
           (db/q '[:find [?run ...]
                  :in $ [?run-id ...]
                  :where
                  [?run :seon.turn/id ?run-id]
                  [?run :seon.turn/closed-tx _]]
                db run-ids))
          0)
        idle? (and (seq run-ids)
                   (nil? (turn/next-agent-work
                          db
                          {:seon.agent/id agent-id
                           :seon.db.process/id process})))]
    (cond
      (seq completions)
      {:seon.eval.drive/outcome :completed
       :seon.eval.drive/run-ids run-ids}

      (and idle? undisposed?)
      {:seon.eval.drive/outcome :undisposed
       :seon.eval.drive/run-ids run-ids}

      (and idle? (>= closed-count run-cap))
      {:seon.eval.drive/outcome :capped
       :seon.eval.drive/run-ids run-ids}

      (and idle? (= closed-count (count run-ids)))
      {:seon.eval.drive/outcome :stopped
       :seon.eval.drive/run-ids run-ids}

      :else nil))))

(defn- full-transcript [db agent-id instance settings]
  (transcript/render-ai
   {:seon.db/db db
    :seon.db/connection (:seon.boot/cluster-connection instance)
    :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
    :seon.agent/id agent-id
    :seon.render.value/root [:seon.agent/id agent-id]
    :seon.sci.admit/caps (config/result-caps settings)
    :seon.sci.eval/time-limit-ms
    (:seon.config.eval/time-limit-ms settings)}))

(defn- grading-branch! [store ending-commit episode-id]
  (let [branch (keyword (str "inspect-grade-" episode-id))]
    (registry/branch! {:seon.store/store store
                       :seon.cluster.registry/from ending-commit
                       :seon.store/branch branch})
    branch))

(defn run-episode!
  "Run one objective in an already-running cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance :seon.eval.drive/episode-request] :seon.eval.drive/episode-result]}
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
       :seon.eval.drive/completed-result (completed-result ending-db run-ids)
       :seon.eval.drive/transcript
       (full-transcript ending-db agent-id instance settings)
       :seon.eval.drive/model (:seon.config.ai/model settings)
       :seon.eval.drive/thinking (:seon.config.ai/thinking settings)})))

(defn- sample-cluster-name []
  (str "inspect-sample-" (subs (uuid-text) 0 12)))

(defn run-sample!
  "Start, run, and retire one isolated sample cluster."
  {:malli/schema [:=> [:cat :seon.eval.drive/sample-request] :seon.eval.drive/episode-result]}
  [request]
  (let [root (:seon.eval.drive/root request)
        sample-id (str (:seon.eval.drive/sample-id request))
        run-cap (or (:seon.eval.drive/run-cap request) default-run-cap)
        timeout-ms (or (:seon.eval.drive/remote-timeout-ms request)
                       (* run-cap 240000))
        cluster-name (sample-cluster-name)
        episode-id (str sample-id "-" (subs (uuid-text) 0 8))
        agent-id (str "inspect-" (subs (uuid-text) 0 12))
        ;; One release for every exit: stop the instance, then retire its
        ;; branches from the store the instance holds.
        release! (fn [instance grading-branch]
                   (try
                     (boot/stop! instance)
                     (finally
                       (when-let [store (:seon.store/store instance)]
                         (when grading-branch
                           (registry/retire-branch!
                            {:seon.store/store store :seon.store/branch grading-branch}))
                         (registry/retire-branch!
                          {:seon.store/store store
                           :seon.store/branch (registry/cluster-branch cluster-name)})))))]
    (when-not (and (string? root) (not (str/blank? root)))
      (throw (ex-info "The sample request requires an operator cluster root."
                      {:seon.eval.drive/request request})))
    (let [instance
          (try
            (boot/start!
             {:seon.boot/cluster-name cluster-name
              :seon.boot/root root
              :seon.config/manifest
              {:seon.config.run/max-episode-runs run-cap}})
            (catch Throwable failure
              ;; A boot that fails partway leaves its partial instance in the
              ;; one instance registry; the failure references it by name.
              (some-> (get @operator.runtime/running-instances
                           (:seon.boot/cluster-name (ex-data failure)))
                      (release! nil))
              (throw failure)))
          report
          (try
            (run-episode!
             instance
             {:seon.eval.drive/id episode-id
              :seon.eval.drive/objective
              (:seon.eval.drive/objective request)
              :seon.eval.drive/agent-ids [agent-id]
              :seon.eval.drive/run-cap run-cap
              :seon.eval.drive/remote-timeout-ms timeout-ms})
            (catch Throwable failure
              (release! instance nil)
              (throw failure)))]
      (release! instance (:seon.eval.drive/grading-branch report))
      (assoc report :seon.eval.drive/sample-id sample-id))))
