;; Run in a fresh JVM: clojure -M:test docs/prds/steward-platform/research/test-system-config-load-2026-09-23.clj
(require '[seon.schema :as schema])

(assert (nil? (find-ns 'seon.config)) "measurement requires config not yet loaded")
(let [construct schema/declaration-projection
      calls (atom [])
      began (System/nanoTime)]
  (with-redefs [schema/declaration-projection
                (fn [& args]
                  (let [started (System/nanoTime)
                        projection (apply construct args)]
                    (swap! calls conj
                           {:seon.measurement/milliseconds
                            (/ (- (System/nanoTime) started) 1e6)
                            :seon.measurement/schema-count
                            (count (:seon.schema.projection/forms projection))})
                    projection))]
    (require 'seon.config))
  (let [defaults @(resolve 'seon.config/defaults)]
    (assert (map? defaults))
    (assert (= 1 (count @calls)))
    (assert (identical? defaults @(resolve 'seon.config/defaults)))
    (prn {:seon.measurement/config-namespace-load-ms
          (/ (- (System/nanoTime) began) 1e6)
          :seon.measurement/projections @calls
          :seon.measurement/default-count (count defaults)})))

(shutdown-agents)
