(ns seon.turn
  "System and virtual turns through the agent's ordinary proc."
  (:require [clojure.string :as str]
            [seon.blob :as blob]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.db :as db]
            [seon.error :as error]
            [seon.program :as program]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.sci.eval :as eval]
            [seon.sci.reader :as reader]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- source-events [source]
  (reader/read {:seon.sci.reader/text (:seon.cluster.eval/source source)
                :seon.sci.reader/ns (:seon.ns/name source)
                :seon.config.eval.result/max-source
                (count (:seon.cluster.eval/source source))}))

(defn- source-key [source]
  (let [events (source-events source)]
    [(:seon.ns/name source)
     (if (vector? events)
       (mapv :seon.sci.reader/form events)
       (:seon.cluster.eval/source source))]))

(defn- declared-sources [handle database agent-id namespace-name]
  (let [calls (atom {})
        invocations (atom {})
        units (walk/neighborhood
               {:seon.db/db database
                :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
                :seon.render/output :seon.render/ai
                :seon.render/captured-calls calls
                :seon.render/captured-invocations invocations
                :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)
                :seon.config/on-core-error (:seon.config/on-core-error handle)})]
    (or (some (fn [unit]
                (let [failure (:seon.error/value unit)]
                  (when-not (:seon.render.walk/elided failure) failure))) units)
        (let [sources (reduce
         (fn [sources unit]
           (let [lookup (:seon.render.walk/lookup unit)
                 call-id [:seon.render/ai lookup (:seon.render/distance unit)]
                 text (:seon.render.call/source (get @calls call-id))]
             (if text
               (let [parsed (loop/planned-sources
                             text namespace-name
                             (get-in handle [:seon.sci.admit/caps
                                             :seon.config.eval.result/max-source]))]
                 (if (:seon.error/kind parsed)
                   (reduced parsed)
                   (into sources
                         (map #(assoc % :seon.render.walk/lookup lookup)) parsed)))
               sources)))
         [] units)]
          (if (:seon.error/kind sources) sources
              {:seon.turn/forms sources :seon.render.walk/units units})))))

(defn- latest-evaluations [database agent-id]
  (reduce
   (fn [latest [basis _ evaluation]]
     (let [source (assoc evaluation :seon.ns/name
                         (get-in evaluation [:seon.cluster.eval/ns :seon.ns/name])
                         :seon.turn/basis-t basis)]
       (assoc latest (source-key source) source)))
   {}
   (sort-by (juxt first second)
            (db/q '[:find ?t ?ordinal
                    (pull ?evaluation
                          [* {:seon.cluster.eval/ns [:seon.ns/name]}
                           {:seon.cluster.eval/read-evidence [*]}])
                    :in $ ?id
                    :where [?agent :seon.cluster.agent/id ?id]
                    [?turn :seon.cluster.run/agent ?agent]
                    [?turn :seon.cluster.run/id _ ?t]
                    [?evaluation :seon.cluster.eval/run ?turn]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                    [?evaluation :seon.cluster.eval/source]]
                  database agent-id))))

(defn- read-only-evaluation? [database evaluation]
  (let [events (source-events evaluation)
        evaluation-id (:db/id evaluation)
        run-id (get-in evaluation [:seon.cluster.eval/run :db/id])]
    (and (seq (:seon.cluster.eval/read-evidence evaluation))
         (vector? events)
         (not-any? #(program/declaration-row % :all :agent) events)
         (nil? (db/q '[:find ?transaction . :in $ ?evaluation
                        :where [?transaction :seon.db/receipt ?evaluation]]
                      database evaluation-id))
         (nil? (db/q '[:find ?effect . :in $ ?turn ?ordinal
                        :where [?effect :seon.effect/run ?turn]
                        [?effect :seon.effect/form-ordinal ?ordinal]]
                      database run-id (:seon.cluster.eval/ordinal evaluation))))))

(defn- system-plan [database declared latest]
  (into []
        (comp
         (filter #(let [previous (get latest (source-key %))]
                    (or (nil? previous)
                        (read-only-evaluation? database previous))))
         (map
          (fn [source]
            (let [previous (get latest (source-key source))
                  evidence (:seon.cluster.eval/read-evidence previous)
                  basis (:seon.cluster.eval/read-basis-transaction previous)
                  status (cond
                           (nil? previous) :none
                           (db/read-evidence-current? database evidence) :unchanged
                           :else :changed)]
              (cond-> (assoc (select-keys source
                                         [:seon.cluster.eval/source
                                          :seon.cluster.eval/comment
                                          :seon.ns/name :seon.render.walk/lookup])
                             :seon.turn/status status)
                basis (assoc :seon.cluster.eval/read-basis-transaction basis)
                (= status :changed)
                (assoc :seon.turn/changes
                       (db/read-evidence-changes database evidence basis)))))))
        (second
         (reduce (fn [[seen sources] source]
                   (let [source-identity (source-key source)]
                     (if (contains? seen source-identity)
                       [seen sources]
                       [(conj seen source-identity) (conj sources source)])))
                 [#{} []]
                 (concat declared
                         (filter #(read-only-evaluation? database %)
                                 (sort-by (juxt :seon.turn/basis-t
                                                :seon.cluster.eval/ordinal)
                                          (vals latest))))))))

(defn system-turn
  "Project the declared opening and every distinct retained read form.
  Unchanged reads contribute no evaluation. With write? true, save the exact
  evaluated sources as one closed system turn with no provider attempt."
  {:malli/schema [:=> [:cat :seon.turn/system-request]
                  [:or :seon.turn/system-result :seon.error/value]]}
  [{handle :seon.cluster.loop/cluster
    agent-id :seon.cluster.agent/id
    write? :seon.turn/write?}]
  (let [connection (:seon.db/connection handle)
        database @connection
        namespace-name (eval/agent-namespace database agent-id)
        declared (when namespace-name
                   (declared-sources handle database agent-id namespace-name))]
    (cond
      (nil? namespace-name)
      (error/diagnostic
       {:seon.error/kind ::agent-namespace-missing
        :seon.error/message "The system turn requires the agent's assigned namespace."
        :seon.error/diagnostic-layer :seon.turn
        :seon.error/diagnostic-operation `system-turn
        :seon.error/diagnostic-member :seon.cluster.agent/id
        :seon.error/diagnostic-expected :seon.cluster.agent/id
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause ::agent-namespace-missing
        :seon.error/diagnostic-evidence {}})

      (:seon.error/kind declared) declared

      :else
      (let [plan (system-plan database (:seon.turn/forms declared)
                              (latest-evaluations database agent-id))
            selected (filterv #(not= :unchanged (:seon.turn/status %)) plan)
            previews (mapv
                      #(loop/preview-sources
                        {:seon.cluster.loop/cluster handle
                         :seon.db/db database
                         :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :seon.cluster.agent/id agent-id
                         :seon.ns/name (:seon.ns/name %)
                         :seon.cluster.reply/text (:seon.cluster.eval/source %)
                         :seon.sci.admit/caps (:seon.sci.admit/caps handle)})
                      selected)]
        (or (some #(when (:seon.error/kind %) %) previews)
            (let [evaluated (mapv (fn [ordinal source preview]
                                    (cond-> (assoc (first (:seon.cluster.loop/evaluated-sources preview))
                                                   :seon.cluster.eval/ordinal ordinal)
                                      (:seon.cluster.eval/comment source)
                                      (assoc-in [:seon.cluster.loop/admitted-form
                                                 :seon.cluster.eval/comment]
                                                (:seon.cluster.eval/comment source))))
                                  (range) selected previews)
                  text-by-source
                  (into {} (map (fn [source item]
                                  [(source-key source)
                                   (repl/text
                                    (merge (:seon.cluster.loop/admitted-form item)
                                           (:seon.sci.eval/evaluation item)
                                           {:seon.ns/name (:seon.ns/name source)}))])
                                selected evaluated))
                  result {:seon.render.walk/units (:seon.render.walk/units declared)
                          :seon.turn/forms
                          (mapv #(if-let [text (get text-by-source (source-key %))]
                                   (assoc % :seon.turn/text text) %) plan)}]
              (if (and write? (seq evaluated))
                (let [turn-id (str (random-uuid))
                      prepared (run/record-evaluated-tx
                                {:seon.cluster.loop/cluster handle
                                 :seon.db/db database
                                 :seon.cluster.run/id turn-id
                                 :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                                 :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
                                 :seon.cluster.run/reply
                                 (str/join "\n" (map :seon.cluster.eval/source selected))
                                 :seon.cluster.run/opened-at
                                 (:seon.cluster.run/opened-at (first previews))
                                 :seon.cluster.run/closed-at
                                 (:seon.cluster.run/closed-at (peek previews))
                                 :seon.cluster.loop/evaluated-sources evaluated})
                      report (blob/with-publication!
                              connection (:seon.blob/staged-writes prepared)
                              #(db/transact! connection (:seon.db/tx-data prepared)))]
                  (if (:seon.error/kind report) report
                      (assoc result :seon.cluster.run/id turn-id)))
                result)))))))

(defn compact-call
  "Retract one idle agent's evaluations at the database writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
                  :seon.store/transaction-data]}
  [database agent-id]
  (let [agent-eid (db/q '[:find ?agent . :in $ ?id
                         :where [?agent :seon.cluster.agent/id ?id]]
                       database agent-id)
        open-id (when agent-eid
                  (db/q '[:find ?id . :in $ ?agent
                          :where [?turn :seon.cluster.run/agent ?agent]
                          [?turn :seon.cluster.run/id ?id]
                          (not [?turn :seon.cluster.run/closed-at _])]
                        database agent-eid))]
    (when (or (nil? agent-eid) open-id)
      (let [message (if open-id
                      "Compaction requires the agent's current turn to close."
                      "Compaction requires an existing agent.")]
        (throw
         (ex-info message
                  (error/diagnostic
                   {:seon.error/kind ::compaction-refused
                    :seon.error/message message
                    :seon.error/diagnostic-layer :seon.turn
                    :seon.error/diagnostic-operation `compact!
                    :seon.error/diagnostic-member :seon.cluster.agent/id
                    :seon.error/diagnostic-expected :seon.cluster.agent/id
                    :seon.error/diagnostic-offending agent-id
                    :seon.error/diagnostic-cause ::compaction-refused
                    :seon.error/diagnostic-evidence
                    (cond-> {} open-id (assoc :seon.cluster.run/id open-id))})))))
    (mapv (fn [evaluation] [:db.fn/retractEntity evaluation])
          (db/q '[:find [?evaluation ...] :in $ ?agent
                  :where [?turn :seon.cluster.run/agent ?agent]
                  [?evaluation :seon.cluster.eval/run ?turn]]
                database agent-eid))))

(defn compact!
  "Retract an idle agent's evaluations; the next system walk is fresh."
  {:malli/schema [:=> [:cat :seon.turn/compaction-request]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.cluster.agent/id}]
  (db/transact! connection [[:db.fn/call #'compact-call agent-id]]))

(defn virtual-turn!
  "Submit a fixture reply to the ordinary agent proc, without a provider.
  Returns its durable turn identity; completion is observed in its facts."
  {:malli/schema [:=> [:cat :seon.turn/virtual-request]
                  [:or :seon.cluster.agent/source-submission-result
                   :seon.error/value]]}
  [request]
  (agent/submit-source!
   (update request :seon.cluster.reply/text #(or % "(+ 1 1)"))))
