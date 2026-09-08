(ns seon.test.fast
  "`bin/test-fast NS…`: one armed JVM, no operator root, no checkout copy,
  no base publication — the thirty-second inner loop a lane iterates on.
  It arms contracts exactly as a `bin/test` worker does (`seon.test.arm`)
  and runs the named namespaces with clojure.test. What it does NOT prove:
  isolation per worker, retained roots, the platform tier — those are
  `bin/test --paths … -- …` per commit and `--platform` per lane."
  (:require [clojure.test :as test]
            [seon.schema :as schema]
            [seon.test.arm :as arm]
            [seon.test.runner :as runner]))

(defn -main
  "Arm contracts, run the named test namespaces, exit with the tally."
  [& args]
  (let [namespaces (mapv symbol args)
      arming (#'arm/initialize-contracts! "test-fast" namespaces)
      started (java.time.Instant/now)
      progress (atom {:seon.test.runner/description "test-fast initialization"
                      :seon.test.runner/at-nanos (System/nanoTime)
                      :seon.test.runner/at started})
      backstop (#'runner/start-liveness-backstop!
                progress (#'runner/silence-seconds) started)
      exit-code
      (try
        (let [arming arming]
          (doseq [namespace-name namespaces]
            (when (empty? (#'runner/test-vars-in [namespace-name]))
              (throw (ex-info "Requested namespace has no tests."
                              {:seon.test.runner/namespace namespace-name}))))
          (schema/call-with-projection
           (:seon.test.runner/projection arming)
           (fn []
             (let [report test/report
                   result
                   (binding [test/report
                             (fn [event]
                               (when (= :begin-test-ns (:type event))
                                 (#'runner/reassert-contracts! arming "test-fast"))
                               (report event))]
                     (#'runner/run-request!
                      {:seon.test.runner/namespaces namespaces
                       :seon.test.run/id (str (random-uuid))
                       :seon.test.run/at (java.util.Date.)
                       :seon.test.run/git-sha "working-tree"}
                      progress nil))
                   summary (:seon.test.runner/summary result)]
               (if (zero? (+ (:seon.test.runner/fail-count summary)
                             (:seon.test.runner/error-count summary))) 0 1)))))
        (catch Throwable failure
          (binding [*out* *err*]
            (println "bin/test-fast: initialization or execution failed:"
                     (ex-message failure))
            (prn (ex-data failure)))
          1)
        (finally (.shutdownNow backstop)))]
    (shutdown-agents)
    (System/exit exit-code)))
