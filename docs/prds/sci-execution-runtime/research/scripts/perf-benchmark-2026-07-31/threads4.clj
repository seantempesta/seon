(require '[bench.support :as s] '[seon.cluster.agent :as agent] '[seon.cluster.store :as store]
         '[datahike.api :as d])
(defn mixed-count []
  (count (filterv (fn [^Thread t] (.startsWith (.getName t) "async-mixed"))
                  (keys (Thread/getAllStackTraces)))))
(println :BEFORE (mixed-count) :armed (count (:seon.cluster.agent/armed @s/routing)))
(dotimes [i 1000]
  (agent/disarm! {:seon.cluster.agent/id (str "dense-" i) :seon.cluster.agent/routing s/routing}))
(Thread/sleep 3000) (System/gc) (Thread/sleep 1000)
(println :AFTER-DISARM (mixed-count) :armed (count (:seon.cluster.agent/armed @s/routing))
         :mem (pr-str (s/memory)))
;; retract the fleet so later sections start clean
(let [eids (mapv first (d/q '[:find ?e :where [?e :seon.cluster.agent/id ?id]
                              [(clojure.string/starts-with? ?id "dense-")]] @s/connection))]
  (println :RETRACTING (count eids))
  (doseq [chunk (partition-all 200 eids)]
    (store/transact! s/connection (mapv (fn [e] [:db/retractEntity e]) chunk))))
(Thread/sleep 1000) (System/gc) (Thread/sleep 500)
(println :FINAL (mixed-count) :mem (pr-str (s/memory))
         :agents (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]] @s/connection)))
