(ns seon.cluster.status
  "Root's cluster observation and per-agent accounting, derived at read time."
  (:require [seon.error.refusal]
            [clojure.edn :as edn]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.env :as env]
            [seon.render.value :as render.value])
  (:import [java.lang.management ManagementFactory]))

(defn- unknown
  {:malli/schema [:=> [:cat :string] :seon.cluster.status/unavailable-error]}
  [message]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.cluster.status/observation 'seon.cluster.status/unknown
   {:seon.error/message message
   :seon.cluster.status/unavailable-observation message}))

(defn- boot-time []
  (java.util.Date. (.getStartTime (ManagementFactory/getRuntimeMXBean))))

(defn- thread-counts
  "Live platform threads from the JVM's thread bean. Virtual threads are a
  typed unknown: the bean counts platform threads only, and a thread dump to
  count them wrote a file per observation."
  {:malli/schema [:=> [:cat] [:map [:seon.cluster.status/platform-threads :seon.cluster.status/measurement]
                              [:seon.cluster.status/virtual-threads :seon.cluster.status/measurement]]]}
  []
  {:seon.cluster.status/platform-threads (.getThreadCount (ManagementFactory/getThreadMXBean))
   :seon.cluster.status/virtual-threads
   (unknown "Virtual threads are not counted: the JVM's thread bean counts platform threads only.")})

(defn snapshot
  "Observe this cluster and JVM. SCI supplies the database and connection.
  Store size is an explicit operator diagnostic: routine observations never
  traverse the store's files. Open faults are `seon.problems/open-faults`'s,
  shown by the health report beside this value.

  Example: (seon.cluster.status/snapshot {})"
  {:malli/schema [:=> [:cat :seon.cluster.status/request]
                  [:or :seon.cluster.status/value :seon.error/base
                   :seon.cluster.status/unavailable-error]]}
  [{database :seon.db/db connection :seon.db/connection :as request}]
  (try
    (let [;; THE CALLER'S CONTEXT NAMES THE CLUSTER: a forked branch holds
          ;; its parent's cluster entities too, so "some cluster entity" observed
          ;; another cluster's commit (web-context-test unrelated-adoption).
          ;; Without a context, a database holding one cluster entity names it.
          named (:seon.boot/cluster-name (or (env/of (:seon.sci.eval/ctx request)) (env/of request)))
          ;; two reads, not a pull inside :find: Datahike plans a find-pull
          ;; as a dependency on every attribute, so any commit re-rendered
          ;; the status block
          cluster (some->> (if named
                             (db/q '[:find ?c . :in $ ?name :where [?c :seon.cluster/name ?name]] database named)
                             (db/q '[:find ?c . :where [?c :seon.cluster/name]] database))
                           (db/pull database [:seon.cluster/name :seon.source/commit-id]))
          heap (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
      (if-not (:seon.cluster/name cluster)
        (unknown "This database has no cluster identity.")
        (merge
         {:seon.cluster/name (:seon.cluster/name cluster)
          :seon.cluster.status/heap-used (.getUsed heap)
          :seon.cluster.status/heap-max (.getMax heap)
          ;; Used heap counts garbage G1 has not yet collected (4.56 GB used
          ;; beside 0.97 GB live after a full collection, default
          ;; 2026-09-28). What the collected pools held after their last
          ;; collection bounds the live set from above without forcing one
          ;; (1.63 GB just after boot; old regions wait for a mixed cycle).
          :seon.cluster.status/heap-live
          (reduce + 0 (keep #(when (= java.lang.management.MemoryType/HEAP (.getType ^java.lang.management.MemoryPoolMXBean %))
                               (some-> (.getCollectionUsage ^java.lang.management.MemoryPoolMXBean %) .getUsed))
                            (ManagementFactory/getMemoryPoolMXBeans)))
          :seon.cluster.status/store-bytes
          (unknown "Store bytes are not scanned during a cluster observation. Use bin/seon status --verbose for an explicit footprint measurement.")
          :seon.cluster.status/adopted-commit
          (or (:seon.source/commit-id cluster) (unknown "No source adoption is recorded."))
          :seon.cluster.status/current-commit
          (or (registry/connection-branch-commit-id connection :current-src)
              (unknown "No current source branch is published."))
          :seon.cluster.status/agents
          (count (db/q '[:find ?a :where [?a :seon.agent/id]] database))
          :seon.cluster.status/open-turns
          (count (db/q '[:find ?t :where [?t :seon.turn/id]
                        (not [?t :seon.turn/closed-tx])] database))}
         (thread-counts))))
    (catch Exception failure (unknown (ex-message failure)))))

(defn- usage [attempt]
  (if-let [text (:seon.ai.attempt/usage-edn attempt)]
    (edn/read-string text)
    {}))

(defn- sum-known [values message]
  (if (every? number? values) (reduce + 0 values) (unknown message)))

(defn agents
  "Read every agent's current work and measured accounting.
  Evaluations and attempts are scoped to this JVM session. Last latency
  uses the latest closed turn's transaction instants. Storage counts all
  retained shown text plus distinct referenced reply, reasoning and fault
  blobs; shared blobs count once per agent. Cost is provider-reported USD,
  unknown when any attempt omits it, never inferred from current prices.

  Example: (seon.cluster.status/agents {})"
  {:malli/schema [:=> [:cat :seon.cluster.status/request]
                  [:or [:vector :seon.cluster.status/agent] :seon.error/value
                   :seon.cluster.status/unavailable-error]]}
  [{database :seon.db/db connection :seon.db/connection}]
  (try
    (let [boot (inst-ms (boot-time))
          entities (db/q '[:find [(pull ?a [:db/id :seon.agent/id
                                      {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}]) ...]
                       :where [?a :seon.agent/id]] database)]
      (mapv
       (fn [agent]
         (let [turns (db/q '[:find [(pull ?t [:db/id :seon.turn/reply-blob
                                            {:seon.turn/opened-tx [:db/txInstant]}
                                            {:seon.turn/closed-tx [:db/txInstant]}
                                            {:seon.turn/attempts [*]}]) ...]
                             :in $ ?a :where [?t :seon.turn/agent ?a]] database (:db/id agent))
               evaluations (db/q '[:find [(pull ?e [:seon.eval/shown :seon.eval/duration-ms
                                                   :seon.cluster.eval/at]) ...]
                                   :in $ ?a :where [?t :seon.turn/agent ?a]
                                   [?e :seon.cluster.eval/run ?t]] database (:db/id agent))
               session (filter #(>= (some-> (:seon.cluster.eval/at %) inst-ms) boot) evaluations)
               attempts (filter #(>= (inst-ms (:seon.ai.attempt/at %)) boot)
                                (mapcat :seon.turn/attempts turns))
               usages (map usage attempts)
               last-turn (last (sort-by #(inst-ms (get-in % [:seon.turn/closed-tx :db/txInstant]))
                                       (filter :seon.turn/closed-tx turns)))
               fault-blobs (db/q '[:find [?digest ...] :in $ ?a
                                   :where [?o :seon.error.occurrence/agent ?a]
                                   [?o :seon.error.occurrence/data-blob ?b]
                                   [?b :seon.error.occurrence/blob-digest ?digest]] database (:db/id agent))
               digests (set (concat (keep :seon.turn/reply-blob turns)
                                    (keep :seon.ai.attempt/reasoning-blob (mapcat :seon.turn/attempts turns))
                                    fault-blobs))
               blob-sizes (map #(some-> (blob/get connection %) blob/utf8-size) digests)
               storage (sum-known (concat (map #(blob/utf8-size (get % :seon.eval/shown "")) evaluations)
                                           blob-sizes) "A referenced blob is unavailable.")]
           (cond-> (assoc (dissoc agent :db/id)
                          :seon.cluster.status/evaluations (count session)
                          :seon.cluster.status/evaluation-ms (reduce + 0 (map #(get % :seon.eval/duration-ms 0) session))
                          :seon.cluster.status/provider-tokens
                          (sum-known (map :seon.ai.usage/total-tokens attempts) "An attempt has no total token usage.")
                          :seon.cluster.status/provider-cost-usd
                          (sum-known (map #(get % "cost") usages) "An attempt has no reported USD cost.")
                          :seon.cluster.status/storage-bytes storage)
             last-turn
             (assoc :seon.cluster.status/last-turn-ms
                    (- (inst-ms (get-in last-turn [:seon.turn/closed-tx :db/txInstant]))
                       (inst-ms (get-in last-turn [:seon.turn/opened-tx :db/txInstant])))))))
       (sort-by :seon.agent/id entities)))
    (catch Exception failure (unknown (ex-message failure)))))

(def ^:private cluster-query
  '[:find (pull ?cluster [:seon.cluster/name :seon.source/commit-id]) . :where [?cluster :seon.cluster/name]])

(defn render-ai
  "My cluster and its adopted source as data, offering the statistics read.

  `snapshot` reads JVM and turn statistics on demand."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.cluster.status/view :seon.db/error-result]]}
  [{database :seon.db/db}]
  (let [cluster (db/q cluster-query database)]
    (if (db/read-refusal? cluster)
      cluster
      (assoc cluster :seon.render/operations ['(seon.cluster.status/snapshot {})]))))

(defn render-form
  "The read that finds my cluster and adopted source again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [_unit]
  (list 'seon.db/q (list 'quote cluster-query) '(seon.db/db)))

(defn render-html
  "Show a cluster observation as labeled data: the value it is given when that
  is a `snapshot`, else my cluster and adopted source, as the AI face reads
  them. A render never collects: `snapshot` is offered, not run."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [{value :seon.render/value :as unit}]
  (let [shown (if (and (map? value) (:seon.cluster.status/heap-used value)) value (render-ai unit))]
    [:section {:class "seon-cluster-status"}
     [:h3 "Cluster"]
     (into [:dl] (map (fn [[k v]] [:div [:dt (name k)] [:dd (render.value/bounded-text v)]]) shown))]))
