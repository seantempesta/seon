(ns seon.agent.lifecycle-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [seon.agent.lifecycle :as lifecycle]
            [seon.schema :as schema]))

(deftest disposition-schemas-are-registered
  (is (= [:enum :wait :completed :pause :resume :terminate]
         (schema/schema-definition
          :seon.agent.lifecycle/disposition)))
  (is (= [:map {:closed true}
          [:seon.agent.lifecycle/disposition [:= :completed]]
          [:seon.agent.lifecycle/result
           :seon.agent.lifecycle/result]]
         (schema/schema-definition
          :seon.agent.lifecycle/complete-disposition))))

(deftest lifecycle-functions-return-driver-dispositions
  (testing "waiting returns data and retains its note"
    (is (= {:seon.agent.lifecycle/disposition :wait
            :seon.agent.lifecycle/note "until a reply arrives"}
           (lifecycle/wait "until a reply arrives"))))
  (testing "completion returns the exact terminal synthesis"
    (is (= {:seon.agent.lifecycle/disposition :completed
            :seon.agent.lifecycle/result "X"}
           (lifecycle/complete "X")))
    (is (= {:seon.error/message
            "complete requires non-blank synthesis text."}
           (lifecycle/complete "  "))))
  (testing "pause and resume may address an agent"
    (is (= {:seon.agent.lifecycle/disposition :pause}
           (lifecycle/pause)))
    (is (= {:seon.agent.lifecycle/disposition :pause
            :seon.agent/id "worker"}
           (lifecycle/pause {:seon.agent/id "worker"})))
    (is (= {:seon.agent.lifecycle/disposition :resume}
           (lifecycle/resume)))
    (is (= {:seon.agent.lifecycle/disposition :resume
            :seon.agent/id "worker"}
           (lifecycle/resume {:seon.agent/id "worker"}))))
  (testing "termination addresses exactly one agent"
    (is (= {:seon.agent.lifecycle/disposition :terminate
            :seon.agent/id "worker"}
           (lifecycle/terminate "worker")))))

(deftest lifecycle-functions-are-pure
  (doseq [v [#'lifecycle/wait
             #'lifecycle/complete
             #'lifecycle/pause
             #'lifecycle/resume
             #'lifecycle/terminate]]
    (is (= :pure (:seon.capability/effect (meta v)))
        (str (:name (meta v)) " must only return data"))
    (is (vector? (:malli/schema (meta v)))
        (str (:name (meta v)) " must publish its Malli contract"))))
