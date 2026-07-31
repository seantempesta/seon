;; Section 4 — CLUSTER SCALE in one JVM / one process root.
;;
;; A cluster is one Datahike BRANCH forked from the published `current-src`
;; commit plus its own flow graphs, connection and web view. Sibling clusters
;; in one JVM share only the process-root store holder and the root executors.
;; This measures fork cost, full boot-to-ready, marginal memory, store growth
;; and cross-cluster interference.
(require '[bench.support :as s] '[seon.cluster :as cluster]
         '[seon.cluster.registry :as registry] '[seon.cluster.store :as store]
         '[seon.render.web :as web] '[seon.config :as config]
         '[datahike.api :as d] '[clojure.java.shell :as shell] '[clojure.string :as str])
(def bench-store (:seon.store/store s/instance))
(def manifest (clojure.edn/read-string (slurp "/Users/sean/src/seon/tmp/bench/ollama.edn")))
(def source-commit
  (registry/branch-commit-id bench-store :current-src))
(defn store-mib []
  (-> (:out (shell/sh "du" "-sm" "/Users/sean/src/seon/tmp/bench-head/data/clusters/store"))
      (str/split #"\t") first parse-long))
(defn settle! [] (System/gc) (Thread/sleep 400) (System/gc) (Thread/sleep 300))
(println :BASE (pr-str (s/memory)) :store-mib (store-mib) :source-commit (str source-commit))

;;; 1. the FORK alone — a new isolated environment's facts ---------------------
(def fork-samples
  (mapv (fn [i]
          (let [t (System/nanoTime)]
            (registry/branch! {:seon.store/store bench-store
                               :seon.cluster.registry/from source-commit
                               :seon.store/branch (keyword (str "forkprobe-" i))})
            (- (System/nanoTime) t)))
        (range 30)))
(println :FORK-ONLY (pr-str (s/quantiles fork-samples)) :store-mib (store-mib))

;;; 2. full cluster boot into the ALREADY-RUNNING JVM --------------------------
(def started (atom []))
(defn boot! [i]
  (let [name (str "scale-" i)
        t (System/nanoTime)
        instance (cluster/start! {:seon.boot/cluster-name name
                                  :seon.config/manifest manifest})]
    (swap! started conj instance)
    {:name name :ready-ms (/ (Math/round (/ (- (System/nanoTime) t) 1000.0)) 1000.0)
     :reported-ready-ms (:seon.boot/ready-ms instance)}))
(settle!)
(def before-boot (s/memory))
(doseq [target [1 5 10 25]]
  (let [from (count @started)
        results (mapv boot! (range from target))]
    (settle!)
    (let [now (s/memory)]
      (println :CLUSTERS target
               :boot-ms (pr-str (mapv :ready-ms results))
               :mem (pr-str now)
               :store-mib (store-mib)
               :marginal-rss-mib-per-cluster
               (/ (Math/round (* 100.0 (/ (double (- (:rss-mib now) (:rss-mib before-boot)))
                                          (max 1 target)))) 100.0)))))
(println :CLUSTERS-DONE (count @started))
