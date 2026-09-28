(ns tmp.settle-cost.adoption-probe
  "Probe: adopt the candidate :seon.runtime/turns declaration on a disposable
  branch of the live default store through the one schema-change mechanism
  (seon.cluster/attribute-change-tx, which declaration-changes calls), then
  retire it, and show what retiring it straight from the component form does.
  Every branch is retired in finally."
  (:require [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.operator.runtime :as operator.runtime]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [clojure.edn]
            [seon.profile]
            [seon.config]
            [seon.schema.datahike :as schema.datahike]))

(defn- candidate-projection
  "The packaged population with a candidate seon.runtime.edn merged over it.
  (Stage 1 was first proven, 2026-09-28 21:56Z, by reading the candidate
  archive's resources through schema.edn/*resources*, since removed.)"
  [runtime-edn]
  (let [candidate (clojure.edn/read-string (slurp runtime-edn))
        forms (merge (into {} (remove #(= "seon.runtime" (namespace (key %)))) (schema.edn/packaged-forms))
                     candidate)]
    (schema/declaration-projection forms)))

(defn- candidate-declaration
  "The projection and Datahike declaration the candidate derives."
  [runtime-edn attribute]
  (let [projection (candidate-projection runtime-edn)]
    [projection (schema.datahike/malli->datahike-attr-in projection attribute)]))

(defn- counts [database]
  {:turns (count (d/datoms database :aevt :seon.turn/id))
   :runtime-turn-datoms (if (get (:schema database) :seon.runtime/turns)
                          (count (d/datoms database :aevt :seon.runtime/turns)) :retired)
   :root-turns (or (d/q '[:find (count ?t) . :where [?a :seon.agent/id "root"] [?t :seon.turn/agent ?a]] database) 0)
   :installed (get (:schema database) :seon.runtime/turns)})

(defn- on-branch
  "Call (f connection) on a new branch of default's head; retire it after."
  [f]
  (let [instance (get @operator.runtime/running-instances "default")
        st (:seon.store/store instance)
        branch (keyword (str "settle-cost-probe-" (random-uuid)))
        _ (registry/branch! {:seon.store/store st :seon.store/branch branch
                             :seon.cluster.registry/from (:seon.store/branch instance)})
        connection (store/open-branch! st branch)]
    (try (f connection)
         (finally
           (store/release-branch! connection)
           (registry/retire-branch! {:seon.store/store st :seon.store/branch branch})))))

(defn- timed-transact [connection tx]
  (let [t0 (System/nanoTime)
        r (db/call-without-custody #(db/transact! connection {:tx-data tx}))
        message (:seon.error/message r)]
    {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)
     :error (some-> message (subs 0 (min 300 (count message))))
     :after (when (:db-after r) (counts (:db-after r)))}))

(defn two-stage
  "Stage 1 adopts the plain-ref form; stage 2 retires the attribute."
  [directory]
  (let [[projection declaration] (candidate-declaration (str directory "/resources/seon/schemas/seon.runtime.edn") :seon.runtime/turns)]
    (on-branch
     (fn [connection]
       (let [before (counts @connection)
             stage-1 (cluster/attribute-change-tx @connection [:seon.runtime/turns]
                                                  {:seon.runtime/turns declaration} projection)
             r1 (timed-transact connection stage-1)
             stage-2 (cluster/attribute-change-tx @connection [:seon.runtime/turns] {} projection)
             r2 (timed-transact connection stage-2)]
         {:declaration declaration :before before
          :stage-1 (assoc r1 :ops (count stage-1) :op-kinds (frequencies (map first stage-1)))
          :stage-2 (assoc r2 :ops (count stage-2) :op-kinds (frequencies (map first stage-2)))})))))

(defn retire-component-directly
  "What removing the declaration in ONE step would do: the component form's
  retirement retracts each turn as an entity."
  [directory]
  (let [[projection _] (candidate-declaration (str directory "/resources/seon/schemas/seon.runtime.edn") :seon.runtime/turns)]
    (on-branch
     (fn [connection]
       (let [tx (cluster/attribute-change-tx @connection [:seon.runtime/turns] {} projection)]
         {:op-kinds (frequencies (map first tx))
          :result (timed-transact connection tx)})))))

(defn- validation-ms
  "Median ms and write-entity-value calls of the writer's final-report check
  for `tx` on `database`, never committed."
  [database tx]
  (let [projection (db/carried-projection database)
        cell-calls (fn [] (reduce + 0 (keep #(let [o (seon.profile/observation %)]
                                                (when (= 'seon.db/write-entity-value (:seon.profile/sym o))
                                                  (:seon.profile/calls o)))
                                             (seon.profile/cells))))
        runs (vec (for [_ (range 5)]
                    (let [report (d/with database tx)
                          c0 (cell-calls)
                          t0 (System/nanoTime)
                          refusal (#'seon.db/validate-write-report projection report)]
                      [(/ (- (System/nanoTime) t0) 1e6) (- (cell-calls) c0) (:seon.error/message refusal)])))]
    {:ms (/ (Math/round (* 100 (nth (sort (map first runs)) 2))) 100.0)
     :entities-read (second (first runs))
     :refusal (some #(nth % 2) runs)}))

(defn- agent-eid [database agent-id] (:db/id (d/pull database [:db/id] [:seon.agent/id agent-id])))

(defn- open-shape
  "A new turn as the writer writes it: with the runtime edge (HEAD) or without (candidate)."
  [database agent-id edge?]
  (let [a (agent-eid database agent-id)
        runtime (:db/id (:seon.agent/runtime (d/pull database [:seon.agent/runtime] a)))]
    (cond-> [{:db/id "t" :seon.turn/id (str "probe-" (random-uuid)) :seon.turn/agent a :seon.turn/opened-tx "datomic.tx"}]
      edge? (conj {:db/id runtime :seon.runtime/agent a :seon.runtime/turns ["t"]}))))

(defn- close-shape
  "Closing the agent's latest turn."
  [database agent-id]
  (let [t (d/q '[:find (max ?t) . :in $ ?id :where [?a :seon.agent/id ?id] [?t :seon.turn/agent ?a]] database agent-id)]
    [[:db/add t :seon.turn/reply-size 1]]))

(defn validation-table
  "Write validation for opening and closing a turn of an agent with few turns
  and of root, on default's head (HEAD's schema and writer shape) and on a
  branch where the candidate's plain-ref form is adopted (candidate shape)."
  [directory small-agent]
  (let [instance (get @operator.runtime/running-instances "default")
        head (db/db (:seon.db/connection (:seon.turn.loop/cluster instance)))
        row (fn [database agent-id edge?]
              {:agent agent-id
               :turns (or (d/q '[:find (count ?t) . :in $ ?id :where [?a :seon.agent/id ?id] [?t :seon.turn/agent ?a]] database agent-id) 0)
               :open (validation-ms database (open-shape database agent-id edge?))
               :close (when (d/q '[:find ?t . :in $ ?id :where [?a :seon.agent/id ?id] [?t :seon.turn/agent ?a]] database agent-id)
                        (validation-ms database (close-shape database agent-id)))})
        [projection declaration] (candidate-declaration (str directory "/resources/seon/schemas/seon.runtime.edn") :seon.runtime/turns)]
    {:before (mapv #(row head % true) [small-agent "root"])
     :after (on-branch
             (fn [connection]
               (timed-transact connection (cluster/attribute-change-tx @connection [:seon.runtime/turns]
                                                                       {:seon.runtime/turns declaration} projection))
               (mapv #(row @connection % false) [small-agent "root"])))}))

(defn retire-plain
  "Stage 2 on a branch of default's current head: the candidate (no
  :seon.runtime/turns) retires the installed plain-ref attribute."
  [runtime-edn]
  (let [projection (candidate-projection runtime-edn)]
    (on-branch
     (fn [connection]
       (let [before (counts @connection)
             tx (cluster/attribute-change-tx @connection [:seon.runtime/turns] {} projection)]
         {:before before :op-kinds (frequencies (map first tx))
          :declared-in-candidate? (contains? (:seon.schema.projection/forms projection) :seon.runtime/turns)
          :result (timed-transact connection tx)})))))

(defn retire-inherited-cluster
  "Can a world branched from default drop default's cluster and config so the
  test cluster is the branch's one cluster?"
  []
  (on-branch
   (fn [connection]
     (let [tx [[:db/retractEntity [:seon.config/cluster "default"]]
               [:db/retractEntity [:seon.cluster/name "default"]]]]
       (assoc (timed-transact connection tx)
              :configs-after (d/q '[:find [?n ...] :where [_ :seon.config/cluster ?n]] @connection))))))

(defn two-cluster-caps
  "b2 on a live-store branch: adopt :seon.agent/cluster from the candidate
  seon.agent.edn, add a second cluster's config row (cap 2) beside the
  inherited one (cap 5), name two agents' clusters, and read each agent's cap
  the way seon.config/agent-cluster-dial reads it."
  [agent-edn]
  (let [candidate (clojure.edn/read-string (slurp agent-edn))
        forms (merge (into {} (remove #(= "seon.agent" (namespace (key %)))) (schema.edn/packaged-forms)) candidate)
        projection (schema/declaration-projection forms)
        declaration (schema.datahike/malli->datahike-attr-in projection :seon.agent/cluster)]
    (on-branch
     (fn [connection]
       (let [install (timed-transact connection (cluster/attribute-change-tx @connection [:seon.agent/cluster]
                                                                              {:seon.agent/cluster declaration} projection))
             agents (vec (take 2 (sort (d/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] @connection))))
             apply-cap! (fn [cluster-name cap]
                          (db/call-without-custody
                           #(seon.config/apply! {:seon.db/connection connection :seon.boot/cluster-name cluster-name
                                                 :seon.config/manifest {:seon.config.run/max-episode-runs cap}})))
             applied [(apply-cap! "default" 5) (apply-cap! "cap-two" 2)]
             write (timed-transact connection
                                   [[:db/add [:seon.agent/id (first agents)] :seon.agent/cluster "default"]
                                    [:db/add [:seon.agent/id (second agents)] :seon.agent/cluster "cap-two"]])
             database @connection
             dial (fn [agent-id]
                    (let [c (d/q '[:find ?n . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/cluster ?n]] database agent-id)]
                      [c (get (d/pull database [:seon.config.run/max-episode-runs] [:seon.config/cluster c]) :seon.config.run/max-episode-runs)]))]
         {:declaration declaration :install (dissoc install :after) :write (dissoc write :after)
          :configs (d/q '[:find ?n ?v :where [?c :seon.config/cluster ?n] [?c :seon.config.run/max-episode-runs ?v]] database)
          :first-config-read (d/q '[:find ?v . :where [?c :seon.config/cluster _] [?c :seon.config.run/max-episode-runs ?v]] database)
          :caps (into {} (map (juxt identity dial)) agents)})))))
