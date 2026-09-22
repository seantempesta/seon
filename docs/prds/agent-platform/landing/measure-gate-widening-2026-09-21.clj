(require '[seon.test.arm :as arm] '[seon.schema :as schema]
         '[seon.test.runner :as runner] '[seon.test-support :as support]
         '[seon.test.selection-test] '[clojure.test :as test])
(let [arming (#'arm/initialize-contracts! "gate-widening-measure" ['seon.test.selection-test])]
  (schema/call-with-projection
   (:seon.test.runner/projection arming)
   (fn []
     (let [start (System/nanoTime) base @@#'support/database-base]
       (println "BASE-MS" (/ (- (System/nanoTime) start) 1e6))
       (when (:seon.error/at base) (throw (ex-info "Base refused" base))))
     (doseq [trial (range 1 4)]
       (let [timings (atom {})
             vars [#'support/seed-cluster! #'support/transacted!
                   #'seon.fn/source-rows #'seon.test/select
                   #'runner/program-digest #'runner/reach-digests]
             wrappers (into {} (map (fn [v]
                                     (let [original @v]
                                       [v (fn [& args]
                                            (let [start (System/nanoTime)]
                                              (try (apply original args)
                                                   (finally
                                                     (swap! timings update (str v) (fnil conj [])
                                                            (/ (- (System/nanoTime) start) 1e6))))))])) vars))
             target #'seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content
             original-meta (meta target)
             elapsed (atom nil)
             _ (alter-meta! target assoc :test
                            (fn []
                              (let [start (System/nanoTime)]
                                (try ((:test original-meta))
                                     (finally (reset! elapsed (/ (- (System/nanoTime) start) 1e6)))))))
             result (try (with-redefs-fn wrappers #(runner/run-var! target))
                         (finally (reset-meta! target original-meta)))]
         (prn {:trial trial :elapsed-ms @elapsed :timings @timings :result result})
         (flush))))))
(shutdown-agents)
(System/exit 0)
