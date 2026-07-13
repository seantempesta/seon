(ns seon.dev.test-runner
  (:require [clojure.test :refer [run-tests]]
            [seon.dev.artifact-test]
            [seon.dev.process-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (run-tests 'seon.dev.artifact-test 'seon.dev.process-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
