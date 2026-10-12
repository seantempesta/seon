(ns seon.config-load-timing
  "Sequential require attribution of the config load path, in a fresh JVM.
  Run from the measured archive on its classpath:
  clojure -Sdeps '{:paths [\"script\" \"src\" \"resources\"]}' -M -m seon.config-load-timing
  Prefix class directories for a cached comparison; omit them for source loading.
  Rendering and process shutdown occur after the measurement window.")

(defn -main
  [& _]
  (let [started (System/nanoTime)
        phases (mapv (fn [subject]
                       (let [began (System/nanoTime)]
                         (require subject)
                         {:seon.profile/subject subject
                          :seon.profile/ms (/ (- (System/nanoTime) began) 1e6)}))
                     '[malli.core datahike.api seon.schema seon.schema.edn
                       seon.print seon.error.refusal seon.db seon.error
                       seon.reconcile seon.config])
        result {:seon.profile/ms (/ (- (System/nanoTime) started) 1e6)
                :seon.profile/phases phases
                :seon.schema/packaged-provider?
                (contains? ((requiring-resolve 'seon.schema/registered-schemas))
                           :seon.ai.model/provider-id)}]
    (println ((requiring-resolve 'seon.render.value/core-text) result))
    (shutdown-agents)))
