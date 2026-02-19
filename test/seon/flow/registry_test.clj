(ns seon.flow.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.registry :as registry]))

(use-fixtures :each (fn [f] (registry/clear!) (f) (registry/clear!)))

(defn- make-entry
  [id label]
  {::registry/id id
   ::registry/flow :fake-flow
   ::registry/chans {:error-chan :fake-err :report-chan :fake-rep}
   ::registry/label label})

(deftest register-unregister-roundtrip-test
  (testing "register and retrieve a flow"
    (registry/register! (make-entry :test/flow-a "Flow A"))
    (let [entry (registry/get-flow {::registry/id :test/flow-a})]
      (is (= :test/flow-a (::registry/id entry)))
      (is (= "Flow A" (::registry/label entry)))
      (is (inst? (::registry/started-at entry)))))

  (testing "list-flows returns all registered"
    (registry/register! (make-entry :test/flow-b "Flow B"))
    (let [flows (registry/list-flows)]
      (is (= 2 (count flows)))
      (is (contains? flows :test/flow-a))
      (is (contains? flows :test/flow-b))))

  (testing "unregister removes and returns entry"
    (let [removed (registry/unregister! {::registry/id :test/flow-a})]
      (is (= :test/flow-a (::registry/id removed)))
      (is (nil? (registry/get-flow {::registry/id :test/flow-a})))
      (is (= 1 (count (registry/list-flows)))))))

(deftest duplicate-registration-test
  (testing "re-registering same id overwrites"
    (registry/register! (make-entry :test/dup "Original"))
    (registry/register! (make-entry :test/dup "Updated"))
    (is (= "Updated" (::registry/label (registry/get-flow {::registry/id :test/dup}))))
    (is (= 1 (count (registry/list-flows))))))

(deftest unregister-nonexistent-test
  (testing "unregistering non-existent id returns nil"
    (is (nil? (registry/unregister! {::registry/id :test/nope})))))

(deftest clear-test
  (testing "clear! removes everything"
    (registry/register! (make-entry :test/x "X"))
    (registry/register! (make-entry :test/y "Y"))
    (registry/clear!)
    (is (empty? (registry/list-flows)))))
