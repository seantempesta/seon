(ns seon.sci.program-change-test
  "An agent evaluation reads the loaded program identity when it starts and
  when it ends; a change by another thread marks its kept value untrusted,
  naming both identities, and nothing waits for adoption. Each test injects its own identity atom, so the
  JVM's `seon.sci.eval/loaded-identity` is never touched."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]))

(defn- evaluation
  "Evaluate `source` in a fresh base context whose `user/change!` runs
  `change!`, reading `identity-atom` as the loaded program."
  [identity-atom change! source]
  (let [ctx (eval/build-base-ctx (schema/handed-projection))]
    (sci/intern ctx (sci/find-ns ctx 'user) 'change! change!)
    (eval/evaluate {:seon.schema/projection (schema/handed-projection)
                    :seon.sci.eval/ctx ctx
                    :seon.cluster.eval/source source
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.sci.eval/loaded-identity identity-atom})))

(defn- on-another-thread!
  "Advance `identity-atom` to `value` from a thread carrying no bindings, as a
  hook-driven adoption does."
  [identity-atom value]
  (let [thread (Thread. ^Runnable #(eval/advance-loaded-identity! identity-atom value))]
    (.start thread)
    (.join thread)
    nil))

(deftest an-evaluation-keeps-an-untrusted-value-when-the-loaded-program-changes
  (testing "no change: the ordinary value"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          result (evaluation loaded (fn [] nil) "(do (change!) (+ 1 2))")]
      (is (= 3 (:seon.sci.admit/value result)))
      (is (not (contains? result :seon.cluster.eval/error)))))
  (testing "another thread adopts during the evaluation: the value is kept, untrusted, beside a refusal naming both identities"
    (let [before (random-uuid)
          after (random-uuid)
          loaded (atom {:seon.source/commit-id before})
          result (evaluation loaded #(on-another-thread! loaded {:seon.source/commit-id after})
                             "(do (change!) (+ 1 2))")
          untrusted (:seon.sci.eval/untrusted result)]
      (is (= 3 (:seon.sci.admit/value result)) "the path is total: the evaluated value stays")
      (is (= :seon.cluster/adoption (:seon.error/layer untrusted)))
      (is (= 'seon.sci.eval/evaluate (:seon.error/operation untrusted)))
      (is (= (str before " -> " after) (:seon.test/not-runnable untrusted)))
      (is (str/includes? (:seon.error/message untrusted) "Rerun"))
      (is (= {:seon.source/commit-id after} @loaded) "adoption never waited")))
  (testing "this evaluation's own adoption keeps its value"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          result (evaluation loaded
                             #(eval/advance-loaded-identity! loaded {:seon.source/commit-id (random-uuid)})
                             "(do (change!) :kept)")]
      (is (= :kept (:seon.sci.admit/value result)))))
  (testing "an unknown program at start: the form never runs"
    (let [ran (atom false)
          loaded (atom {:seon.error/at (java.util.Date.) :seon.error/layer :seon.cluster/adoption
                        :seon.error/operation 'seon.cluster/call-with-program-change
                        :seon.error/message "adoption failed" :seon.test/not-runnable "x"})
          result (evaluation loaded #(reset! ran true) "(change!)")]
      (is (= "adoption failed" (:seon.error/message (:seon.sci.admit/value result))))
      (is (false? @ran)))))
