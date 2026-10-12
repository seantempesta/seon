#!/usr/bin/env bb
;; Run only against an owned isolated root: this probe clears its query and health caches.
;; bb -cp script:src:resources health-cost-probe.bb ROOT MAX-MEDIAN-MS
(require '[seon.operator :as operator])

(let [[root max-ms] *command-line-args*
      samples (operator/prepl-value!
               (operator/advertisement root "default")
               (pr-str
                '(let [database @(seon.cluster.boot/connection "default")
      _ (seon.health/report {:seon.boot/cluster-name "default" :seon.db/db database :seon.health/deadline-ms 10000})
      kinds (into #{} (filter #(= "seon.health" (namespace %))) (keys (:seon.cache/kinds (seon.cache/status))))]
  (mapv (fn [_]
          (seon.cache/evict-kinds! kinds)
          (datahike.query/clear-query-cache!)
          (let [measured (seon.profile/measure #(seon.health/report {:seon.boot/cluster-name "default" :seon.db/db database :seon.health/deadline-ms 10000}))
                report (:seon.profile/value measured)]
            (assoc (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes :seon.cost/jvm-window])
                   :problems-pending (boolean (:seon.health/unit (:seon.health/problems report)))
                   :untested (count (filter #(false? (:seon.health/tested? %)) (:seon.health/profile report))))))
        (range 5))))
               60000)
      median (nth (sort (map :seon.profile/ms samples)) (quot (count samples) 2))]
  (prn {:samples samples :median-ms median :bound-ms (parse-double max-ms)})
  (assert (every? (comp not :problems-pending) samples) "Report derivations must finish within the probe.")
  (assert (< median (parse-double max-ms)) "The complete cold report exceeds its measured bound."))
