(ns seon.cluster.status
  "Root's cluster observation and per-agent accounting, derived at read time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.id :as id])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management HotSpotDiagnosticMXBean HotSpotDiagnosticMXBean$ThreadDumpFormat]))

(defn- unknown
  {:malli/schema [:=> [:cat :string] :seon.cluster.status/unavailable-error]}
  [message]
  {:seon.error/at (java.util.Date.)
   :seon.error/layer :seon.cluster.status/observation
   :seon.error/operation 'seon.cluster.status/unknown
   :seon.error/message message
   :seon.cluster.status/unavailable-observation message})

(defn- boot-time []
  (java.util.Date. (.getStartTime (ManagementFactory/getRuntimeMXBean))))

(defn- utf8-size [text]
  (alength (.getBytes ^String text "UTF-8")))

(defn- thread-counts []
  (let [directory (io/file "tmp/root-cluster-observations")
        file (io/file directory (str (id/id) ".json"))]
    (.mkdirs directory)
    (try
      (.dumpThreads (ManagementFactory/getPlatformMXBean HotSpotDiagnosticMXBean)
                    (.getAbsolutePath file) HotSpotDiagnosticMXBean$ThreadDumpFormat/JSON)
      (let [dump (json/parse-string (slurp file))
            containers (get-in dump ["threadDump" "threadContainers"])
            threads (mapcat #(get % "threads") containers)]
        (if (seq threads)
          {:seon.cluster.status/platform-threads (count (remove #(get % "virtual") threads))
           :seon.cluster.status/virtual-threads
           (if (= "false" (System/getProperty "jdk.trackAllThreads"))
             (unknown "The JVM disables tracking all virtual threads.")
             (count (filter #(get % "virtual") threads)))}
          (throw (ex-info "The JVM thread dump contained no threads." {}))))
      (catch Exception failure
        {:seon.cluster.status/platform-threads (unknown (ex-message failure))
         :seon.cluster.status/virtual-threads (unknown (ex-message failure))})
      (finally (.delete file)))))

(defn snapshot
  "Observe this cluster and JVM. SCI supplies the database and connection.
  Thread counts are a non-atomic JVM dump. Store size is an explicit operator
  diagnostic: routine observations never traverse the store's files.
  Faults and session accounting start at this JVM's boot instant.

  Example: (seon.cluster.status/snapshot {})"
  {:malli/schema [:=> [:cat :seon.cluster.status/request]
                  [:or :seon.cluster.status/value :seon.error/base
                   :seon.cluster.status/unavailable-error]]}
  [{database :seon.db/db connection :seon.db/connection}]
  (try
    (let [cluster (db/q '[:find (pull ?c [:seon.cluster/name :seon.source/commit-id]) .
                          :where [?c :seon.cluster/name]] database)
          heap (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))
          faults (db/q '[:find ?signature (sum ?count)
                         :with ?o
                         :in $ ?boot
                         :where [?f :seon.error/signature ?signature]
                         [?f :seon.error/occurrences ?o]
                         [?o :seon.error.occurrence/count ?count]
                         [?o :seon.error.occurrence/last-at ?at] [(compare ?at ?boot) ?order]
                         [(>= ?order 0)]] database (boot-time))]
      (if-not (:seon.cluster/name cluster)
        (unknown "This database has no cluster identity.")
        (merge
         {:seon.cluster/name (:seon.cluster/name cluster)
          :seon.cluster.status/heap-used (.getUsed heap)
          :seon.cluster.status/heap-max (.getMax heap)
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
                        (not [?t :seon.turn/closed-tx])] database))
          :seon.cluster.status/faults (vec (sort-by first faults))}
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
          rows (db/q '[:find [(pull ?a [:db/id :seon.agent/id
                                      {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}]) ...]
                       :where [?a :seon.agent/id]] database)]
      (mapv
       (fn [agent]
         (let [turns (db/q '[:find [(pull ?t [:db/id :seon.turn/reply-blob
                                            {:seon.turn/opened-tx [:db/txInstant]}
                                            {:seon.turn/closed-tx [:db/txInstant]}
                                            {:seon.turn/attempts [*]}]) ...]
                             :in $ ?a :where [?a :seon.agent/runtime ?r]
                             [?r :seon.runtime/turns ?t]] database (:db/id agent))
               evaluations (db/q '[:find [(pull ?e [:seon.eval/shown :seon.eval/duration-ms
                                                   :seon.cluster.eval/at]) ...]
                                   :in $ ?a :where [?a :seon.agent/runtime ?r]
                                   [?r :seon.runtime/turns ?t]
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
               blob-sizes (map #(some-> (blob/get connection %) utf8-size) digests)
               storage (sum-known (concat (map #(utf8-size (get % :seon.eval/shown "")) evaluations)
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
       (sort-by :seon.agent/id rows)))
    (catch Exception failure (unknown (ex-message failure)))))

(defn render-ai
  "Read cluster identity and adopted source; accounting is available on demand."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  ";; My cluster and adopted source; (seon.cluster.status/snapshot {}) shows JVM and turn statistics on demand.\n(seon.db/q '[:find (pull ?cluster [:seon.cluster/name :seon.source/commit-id]) . :where [?cluster :seon.cluster/name]])")

(defn render-html
  "Show the same cluster observation as labeled data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [value (snapshot unit)]
    [:section {:class "seon-cluster-status"}
     [:h3 "Cluster"]
     (into [:dl] (map (fn [[k v]] [:div [:dt (name k)] [:dd (pr-str v)]]) value))]))
