(ns seon.test-preparation-test
  "Worker readiness includes the real canonical fixture acquisition."
  (:require [clojure.test :refer [deftest is]]
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
        reader (BufferedReader. (StringReader. ""))
        diagnostic "unterminated startup diagnostic"]
    (.write output diagnostic)
    (#'runner/worker-command-loop! "fixture-readiness" reader (PrintWriter. output))
    (let [diagnostics (atom [])
          terminal (with-redefs-fn
                     {#'runner/append-worker-line!
                      (fn [_ line] (swap! diagnostics conj line))}
                     #(#'runner/read-exchange-reply!
                       {::runner/worker-reader (BufferedReader. (StringReader. (str output)))}
                       {::runner/worker-id "fixture-readiness"
                        ::runner/worker-event :ready
                        ::runner/exchange-id "fixture-readiness/readiness"}
                       (java.util.concurrent.CompletableFuture/completedFuture
                        {::runner/exchange-terminal :exit})
                       (atom :readiness)))
          event (::runner/exchange-reply terminal)
          base @#'test-support/database-base]
      (is (= :reply (::runner/exchange-terminal terminal)))
      (is (= [diagnostic] @diagnostics)
          "Unterminated nonprotocol output is preserved separately from readiness.")
      (let [prefix @#'runner/protocol-prefix
            unmatched (str prefix (pr-str (assoc event ::runner/worker-id "another-worker")))
            normal (str prefix (pr-str event))
            noise (atom [])
            parsed (with-redefs-fn
                     {#'runner/append-worker-line!
                      (fn [_ line] (swap! noise conj line))}
                     #(#'runner/read-exchange-reply!
                       {::runner/worker-reader
                        (BufferedReader. (StringReader. (str unmatched "\n" normal "\n")))}
                       {::runner/worker-id "fixture-readiness"
                        ::runner/worker-event :ready
                        ::runner/exchange-id "fixture-readiness/readiness"}
                       (java.util.concurrent.CompletableFuture/completedFuture
                        {::runner/exchange-terminal :exit})
                       (atom :readiness)))]
        (is (= terminal parsed) "Normal frames retain the same parsing.")
        (is (= [(str "UNMATCHED_WORKER_REPLY " unmatched)] @noise)
            "Unmatched frames remain attributed, never accepted as readiness."))
      (is (= :ready (::runner/worker-event event)))
      (is (= "fixture-readiness/readiness" (::runner/exchange-id event)))
      (is (nat-int? (::runner/fixture-preparation-ms event)))
      (is (realized? base) "Readiness must not leave acquisition to the first test.")
      (is (nil? (:seon.error/kind @base)))
      (test-support/with-database
        (fn [connection]
          (is (some? (:seon.test-support/connection @base)))
          (is (pos? (:max-tx @connection))))))))
