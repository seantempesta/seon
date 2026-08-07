(ns probe-runner
  "Run named isolation probes and print one result map per probe.

   Usage: clojure -M:dev:test -m probe-runner probe-shape-generation-cache ..."
  (:require [clojure.pprint :as pprint]))

(defn -main [& probe-names]
  (doseq [probe-name probe-names]
    (let [probe-ns (symbol probe-name)
          _ (require probe-ns)
          run (ns-resolve probe-ns 'run)
          result (try
                   (run {})
                   (catch Throwable failure
                     {:probe/name probe-name
                      :probe/verdict :error
                      :probe/error (str failure)
                      :probe/data (ex-data failure)}))]
      (pprint/pprint result)
      (flush)))
  (shutdown-agents)
  (System/exit 0))
