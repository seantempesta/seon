(ns seon.dev.test-runner
  (:require [clojure.test :refer [run-tests]]
            [seon.dev.test-roots :as roots]))

(defn -main
  "Run every discovered operator test or the selected namespaces."
  [& selectors]
  (let [namespaces (if (seq selectors)
                     (mapv symbol selectors)
                     (roots/operator-test-namespaces
                      (System/getProperty "user.dir")))
        _ (doseq [test-namespace namespaces] (require test-namespace))
        {:keys [fail error]} (apply run-tests namespaces)]
    (when (pos? (+ fail error)) (System/exit 1))))
