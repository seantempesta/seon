(require '[bench.support :as s])
(defn mixed-count []
  (count (filterv (fn [^Thread t] (.startsWith (.getName t) "async-mixed"))
                  (keys (Thread/getAllStackTraces)))))
(doseq [wait [0 30 60 90]]
  (when (pos? wait) (Thread/sleep 30000))
  (System/gc)
  (println :AT-SECONDS wait :mixed (mixed-count) :mem (pr-str (s/memory))))
