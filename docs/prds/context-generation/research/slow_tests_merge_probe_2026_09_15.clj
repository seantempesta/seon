(ns slow-tests-merge-probe
  "In-process probes; load owned tests first. Every database is a canonical scratch fixture."
  (:require [clojure.test :as test]
            [seon.db :as db]
            [seon.sci.eval :as sci-eval]
            [seon.test-support :as support]))

(defn observe! [label body]
  (let [failures (atom [])
        started (System/nanoTime)
        result
        (binding [test/*report-counters* (ref test/*initial-report-counters*)
                  test/report
                  (fn [{:keys [type] :as event}]
                    (when (#{:pass :fail :error} type)
                      (test/inc-report-counter type))
                    (when (#{:fail :error} type)
                      (swap! failures conj
                             (binding [*print-length* 12 *print-level* 6]
                               (pr-str (select-keys event
                                                   [:type :message :file :line
                                                    :expected :actual]))))))]
          (body)
          (assoc @test/*report-counters*
                 :elapsed-ms (quot (- (System/nanoTime) started) 1000000)
                 :failures @failures))]
    (spit (str "tmp/slow-tests-" label ".edn") (pr-str result))
    (dissoc result :failures)))

(defn row2-diagnostic! []
  ;; The live JVM has newer Vars than the fixture's sealed program graph.
  ;; Remove only that separately observed family at the TEST helper seam;
  ;; this is a diagnostic, never the regression or an isolated gate proof.
  (let [found @#'seon.problems-test/found]
    (with-redefs-fn
      {#'seon.problems-test/found
       (fn [connection] (dissoc (found connection) :seon.problems/stale-vars))}
      #(observe! "row2-diagnostic"
                 seon.problems-test/absent-facts-produce-no-entries))))

(defn row3-diagnostic! []
  ;; Probe the missing construction-time carriage without changing production
  ;; or the protected fixture. The real fresh-store owner still acquires once.
  (support/with-database
   (fn [base]
     (let [projection (db/carried-projection (db/db base))
           populate @#'support/populate-database!]
       (with-redefs-fn
         {#'support/populate-database!
          (fn [connection]
            (db/carry-connection-projection-state!
             connection (sci-eval/projection-state @connection projection))
            (populate connection))}
         #(observe! "row3-diagnostic"
                    seon.cluster.mcp-test/artifact-lifecycle-preserves-identity-paging-and-retraction))))))
