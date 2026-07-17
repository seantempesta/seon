(ns seon.runtime.lifecycle-test
  "Portable runtime lifecycle response contract tests."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [malli.core :as m]
   [seon.runtime.lifecycle :as lifecycle]))

(def ^:private success
  {:seon.client/quiesced? true
   :seon.client/quiesced-run-ids ["f5m5ckg3k2pf"]
   :seon.client/completed-turn-ids ["turn-1"]
   :seon.client/errored-turn-ids []
   :seon.agent.runtime/unhosted-ids ["root"]})

(deftest quiesce-response-is-one-closed-portable-contract
  (testing "both success and failure lifecycle data validate"
    (is (m/validate ::lifecycle/quiesce-response success))
    (is (m/validate
         ::lifecycle/quiesce-response
         (assoc success ::lifecycle/process-generation
                "6d295410-5883-4d9f-a532-8f7b71b9812a")))
    (is (m/validate
         ::lifecycle/quiesce-response
         {:seon.client/quiesced? false
          :seon.client/quiesce-error "cleanup remains retryable"})))
  (testing "the discriminator and closed shape are exact"
    (is (not (m/validate ::lifecycle/quiesce-response
                         (assoc success :seon.client/quiesced? false))))
    (is (not (m/validate
              ::lifecycle/quiesce-response
              (assoc success :seon.db.coordinate/coordinate {}))))
    (is (not (m/validate ::lifecycle/quiesce-response
                         (assoc success :seon.runtime.lifecycle/extra true))))))
