(ns seon.test-preparation-test
  "Worker readiness includes the real canonical fixture acquisition."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-runner-failure-fixture :as fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support])
  (:import [java.io BufferedReader PrintWriter StringReader StringWriter]))

(deftest reporter-options-travel-from-task-to-var
  (test-support/with-database
   (fn [connection]
     (let [options (#'runner/report-options)
           explicit-profile (assoc (:seon.render/profile options) :seon.render.profile/max-depth 2)
           defaults (atom 0)
           report-options @#'runner/report-options
           resolution {::runner/report-options options
                       :seon.render/profile explicit-profile
                       :seon.db/db (db/db connection)
                       :seon.db/connection connection
                       :seon.test/class-loader (clojure.lang.RT/baseLoader)
                       :seon.schema/projection (schema/handed-projection)
                       :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)}]
       (with-redefs-fn
         {#'runner/report-options
          (fn ([] (swap! defaults inc) (report-options))
            ([supplied] (report-options supplied)))}
         (fn []
           (is (identical? explicit-profile
                           (:seon.render/profile (#'runner/report-options resolution))))
           (let [result (#'runner/run-task!
                         {::runner/task-symbols [(#'runner/var-symbol #'fixture/passing-example)]}
                         resolution)]
             (is (= 1 (get-in result [::runner/task-summary ::runner/test-count])))
             (is (zero? (get-in result [::runner/task-summary ::runner/error-count])))
             (is (zero? (get-in result [::runner/task-summary ::runner/fail-count]))))))
       (is (zero? @defaults) "Task and Var reporting use the supplied immutable options.")))))

(deftest ^{:seon.test/platform "Worker readiness precedes every per-test clock."}
  worker-readiness-holds-a-usable-canonical-base
  (let [output (StringWriter.)
        reader (BufferedReader. (StringReader. ""))]
    (#'runner/worker-command-loop! "fixture-readiness" reader (PrintWriter. output))
    (let [line (first (str/split-lines (str output)))
          event (edn/read-string (subs line (count @#'runner/protocol-prefix)))
          base @#'test-support/database-base]
      (is (= :ready (::runner/worker-event event)))
      (is (= "fixture-readiness/readiness" (::runner/exchange-id event)))
      (is (nat-int? (::runner/fixture-preparation-ms event)))
      (is (realized? base) "Readiness must not leave acquisition to the first test.")
      (is (nil? (:seon.error/kind @base)))
      (test-support/with-database
        (fn [connection]
          (is (some? (:seon.test-support/connection @base)))
          (is (pos? (:max-tx @connection))))))))
