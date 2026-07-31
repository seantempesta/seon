;; Section 3a — PARKED agent density.
;;
;; Each agent is created (facts) and armed (its own two-proc flow graph). A
;; parked agent is :running and blocked on a channel read — one virtual thread
;; per proc, no platform thread. This measures arm time, marginal memory and
;; PLATFORM thread growth as the fleet grows.
(require '[bench.support :as s] '[seon.cluster.store :as store]
         '[seon.cluster.agent :as agent] '[clojure.core.async.flow :as flow]
         '[datahike.api :as d])
(def handle (:seon.cluster.loop/cluster s/instance))
(defn settle! []
  (System/gc) (Thread/sleep 400) (System/gc) (Thread/sleep 400))
(defn snapshot []
  (settle!)
  (let [m (s/memory)
        bean (java.lang.management.ManagementFactory/getThreadMXBean)]
    (assoc m :peak-threads (.getPeakThreadCount bean)
           :armed (count (:seon.cluster.agent/armed @s/routing)))))
(println :BASE (pr-str (snapshot)))
(def birth-datoms
  (count (:tx-data (store/transact! s/connection
                                    (agent/creation-tx {:seon.cluster.agent/id "density-probe"
                                                        :seon.ns/name 'my.agents.density-probe
                                                        :seon.cluster/name "bench"})))))
(println :BIRTH-DATOMS birth-datoms)
(defn create-batch! [from to]
  (let [rows (into [] (mapcat (fn [i] (agent/creation-tx
                                       {:seon.cluster.agent/id (str "dense-" i)
                                        :seon.ns/name (symbol (str "my.agents.dense" i))
                                        :seon.cluster/name "bench"})))
                   (range from to))]
    (store/transact! s/connection rows)))
(defn arm-range! [from to]
  (mapv (fn [i]
          (let [t (System/nanoTime)]
            (agent/arm! {:seon.cluster.loop/cluster handle
                         :seon.cluster.agent/id (str "dense-" i)
                         :seon.cluster.agent/routing s/routing})
            (- (System/nanoTime) t)))
        (range from to)))
(def steps [100 250 500 1000])
(loop [done 0 steps steps prior (snapshot)]
  (when-let [target (first steps)]
    (let [create-ns (let [t (System/nanoTime)] (create-batch! done target) (- (System/nanoTime) t))
          arm-samples (arm-range! done target)
          now (snapshot)
          added (- target done)]
      (println :FLEET target
               :created-batch-ms (/ (Math/round (/ create-ns 1000.0)) 1000.0)
               :arm (pr-str (s/quantiles arm-samples))
               :mem (pr-str now)
               :marginal-kib-per-agent
               (Math/round (/ (* 1024.0 (- (:rss-mib now) (:rss-mib prior))) added))
               :marginal-heap-kib-per-agent
               (Math/round (/ (* 1024.0 (- (:heap-used-mib now) (:heap-used-mib prior))) added))
               :thread-delta (- (:threads now) (:threads prior)))
      (recur target (rest steps) now))))
(println :FLEET-FINAL (pr-str (snapshot)))
;;; fleet ping cost at density -------------------------------------------------
(def entries (vals (:seon.cluster.agent/armed @s/routing)))
(println :PING-ONE (pr-str (s/quantiles (s/timed 3 20 (fn [_] (flow/ping (:seon.flow/graph (first entries)) 200))))))
(let [t (System/nanoTime)
      answered (reduce + 0 (map (fn [e] (count (flow/ping (:seon.flow/graph e) 200))) (take 200 entries)))]
  (println :PING-200 :answered answered :total-ms (/ (Math/round (/ (- (System/nanoTime) t) 1000.0)) 1000.0)))
(println :AGENTS-DONE)
