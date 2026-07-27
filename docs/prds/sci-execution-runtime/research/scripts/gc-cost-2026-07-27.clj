(ns docs.prds.sci-execution-runtime.research.scripts.gc-cost-2026-07-27
  "Measures retire and full-store GC cost across ten warm branches."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]))

(def ^:private scratch-root "tmp/gc-cost")
(def ^:private store-dir (str scratch-root "/store"))
(def ^:private cluster-count 10)
(def ^:private retire-count 5)
(def ^:private datoms-per-cluster 400)
(def ^:private tx-batch-size 100)
(def ^:private ancestor-digest (apply str (repeat 64 "c")))

(defn- delete-recursively!
  "Delete only this experiment's fixed project-local scratch root."
  [path]
  (let [file (.getCanonicalFile (io/file path))
        expected (.getCanonicalFile (io/file scratch-root))]
    (when-not (= file expected)
      (throw (ex-info "refusing to delete outside the experiment scratch root"
                      {:seon.research/path (.getPath file)})))
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (when-not (.delete ^java.io.File entry)
          (throw (ex-info "failed to delete experiment scratch entry"
                          {:seon.research/path (.getPath
                                               ^java.io.File entry)}))))))
  nil)

(defn- directory-bytes
  "Total bytes in regular files below `path`."
  [path]
  (reduce + 0
          (map #(.length ^java.io.File %)
               (filter #(.isFile ^java.io.File %)
                       (file-seq (io/file path))))))

(defn- elapsed
  "Return `[elapsed-ms result]` for one synchronous operation."
  [operation]
  (let [started (System/nanoTime)
        result (operation)]
    [(/ (double (- (System/nanoTime) started)) 1000000.0)
     result]))

(defn- cluster-name-for [number]
  (str "gc-cost-" number))

(defn- expected-agent-ids [cluster-id]
  (set (map #(str cluster-id "/agent-" %) (range datoms-per-cluster))))

(defn- agent-ids
  "Every workload identity visible through one branch connection."
  [connection]
  (set
   (d/q '[:find [?id ...]
          :where [_ :seon.cluster.agent/id ?id]]
        @connection)))

(defn- warm-cluster!
  "Write the experiment's distinct workload datoms to one branch."
  [opened cluster-id]
  (let [branch (registry/cluster-branch cluster-id)
        connection (store/open-branch! opened branch)]
    (try
      (doseq [batch (partition-all tx-batch-size
                                   (expected-agent-ids cluster-id))]
        (d/transact connection
                    {:tx-data
                     (mapv (fn [agent-id]
                             {:seon.cluster.agent/id agent-id})
                           batch)}))
      (let [actual (agent-ids connection)]
        (when-not (= (expected-agent-ids cluster-id) actual)
          (throw (ex-info "warm branch did not contain its expected datoms"
                          {:seon.boot/cluster-name cluster-id
                           :seon.research/expected datoms-per-cluster
                           :seon.research/actual (count actual)}))))
      (finally
        (d/release connection)))))

(defn- prove-survivor!
  "Query one survivor after GC and return its workload datom count."
  [opened cluster-id]
  (let [branch (registry/cluster-branch cluster-id)
        connection (store/open-branch! opened branch)]
    (try
      (let [expected (expected-agent-ids cluster-id)
            actual (agent-ids connection)]
        (when-not (= expected actual)
          (throw (ex-info "GC changed a surviving branch"
                          {:seon.boot/cluster-name cluster-id
                           :seon.research/missing
                           (count (set/difference expected actual))
                           :seon.research/unexpected
                           (count (set/difference actual expected))})))
        (count actual))
      (finally
        (d/release connection)))))

(defn- run-experiment!
  "Run the complete fresh-store experiment and return its measurements."
  []
  (delete-recursively! scratch-root)
  (.mkdirs (io/file scratch-root))
  (let [opened (store/open-store! {:seon.store/dir store-dir})]
    (try
      (let [ancestor-result
            (ancestor/ensure!
             {:seon.store/store opened
              :seon.ancestor/digest ancestor-digest
              :seon.ancestor/populate `seon.cluster/populate-ancestor!})
            ancestor-branch (:seon.ancestor/branch ancestor-result)
            cluster-names (mapv cluster-name-for (range cluster-count))
            retired-names (subvec cluster-names 0 retire-count)
            survivor-names (subvec cluster-names retire-count)]
        (doseq [cluster-id cluster-names]
          (registry/ensure-cluster!
           {:seon.store/store opened
            :seon.boot/cluster-name cluster-id
            :seon.ancestor/branch ancestor-branch})
          (warm-cluster! opened cluster-id))
        (let [bytes-before-retire (directory-bytes store-dir)
              [retire-ms _]
              (elapsed
               #(doseq [cluster-id retired-names]
                  (registry/retire-branch!
                   {:seon.store/store opened
                    :seon.store/branch
                    (registry/cluster-branch cluster-id)})))
              bytes-before-gc (directory-bytes store-dir)
              [gc-ms swept] (elapsed #(registry/collect! opened))
              bytes-after-gc (directory-bytes store-dir)
              survivor-counts
              (into {}
                    (map (fn [cluster-id]
                           [cluster-id
                            (prove-survivor! opened cluster-id)]))
                    survivor-names)
              roster-after-gc (registry/roster opened)
              [noop-gc-ms noop-swept] (elapsed #(registry/collect! opened))
              bytes-after-noop-gc (directory-bytes store-dir)]
          (when-not (every? #(contains? roster-after-gc
                                       (registry/cluster-branch %))
                            survivor-names)
            (throw (ex-info "GC removed a survivor from the branch roster"
                            {:seon.research/roster roster-after-gc})))
          (when (some #(contains? roster-after-gc
                                  (registry/cluster-branch %))
                      retired-names)
            (throw (ex-info "retired branches remain in the branch roster"
                            {:seon.research/roster roster-after-gc})))
          {:seon.research/environment
           {:java-version (System/getProperty "java.version")
            :os-name (System/getProperty "os.name")
            :os-arch (System/getProperty "os.arch")
            :datahike-revision "357ffc87c8009f342b239145802e1385d4a18ca9"}
           :seon.research/workload
           {:clusters cluster-count
            :retired retire-count
            :survivors (count survivor-names)
            :distinct-datoms-per-cluster datoms-per-cluster
            :distinct-datoms-total (* cluster-count datoms-per-cluster)
            :distinct-datoms-retired (* retire-count datoms-per-cluster)
            :distinct-datoms-surviving
            (* (count survivor-names) datoms-per-cluster)
            :transactions-per-cluster
            (/ datoms-per-cluster tx-batch-size)}
           :seon.research/measurement
           {:retire-ms retire-ms
            :gc-ms gc-ms
            :gc-swept-objects swept
            :noop-gc-ms noop-gc-ms
            :noop-gc-swept-objects noop-swept
            :store-bytes-before-retire bytes-before-retire
            :store-bytes-before-gc bytes-before-gc
            :store-bytes-after-gc bytes-after-gc
            :store-bytes-after-noop-gc bytes-after-noop-gc}
           :seon.research/safety
           {:survivor-counts survivor-counts
            :all-survivors-exact?
            (every? #(= datoms-per-cluster %) (vals survivor-counts))
            :retired-branches-absent? true
            :survivor-branches-present? true}}))
      (finally
        (store/release-store! opened)
        (delete-recursively! scratch-root)))))

(println (pr-str (run-experiment!)))
