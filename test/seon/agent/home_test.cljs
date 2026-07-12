(ns seon.agent.home-test
  "Behavioral coverage for the one agent home-namespace data owner."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [seon.agent.home :as home]
    [seon.config :as config]
    [seon.db :as db]))

(deftest home-namespace-is-a-deterministic-id-projection
  (is (= 'my.agent.lantern-copper-falcon
         (home/home-ns "lantern-copper-falcon")))
  (is (= (home/home-ns "lantern-copper-falcon")
         (home/home-ns "lantern-copper-falcon"))))

(deftest require-spec-contract-is-owned-and-structural
  (is (m/validate :seon.agent.home/require-specs
                  '[[seon.db :as db]
                    [seon.agent.lifecycle :refer [wait complete]]]))
  (is (not (m/validate :seon.agent.home/require-specs '[[seon.db]]))
      "a bare namespace is not a valid home require spec"))

(deftest home-ns-form-renders-the-supplied-requires
  (let [specs '[[seon.db :as db]
                [seon.agent.lifecycle :refer [wait]]]
        form  (reader/read-string (home/home-ns-form 'my.agent.probe specs))]
    (is (= 'ns (first form)))
    (is (= 'my.agent.probe (second form)))
    (is (= (cons :require specs) (nth form 2)))))

(deftest home-requires-precedence-falls-from-config-to-canonical-data
  (binding [db/*conn* nil]
    (testing "a per-agent config value wins before the entity exists"
      (with-redefs [config/resolve-agent-context
                    (fn [_id _override]
                      {:seon.eval/home-requires '[[seon.db :as db]]})]
        (is (= '[[seon.db :as db]]
               (home/home-requires-for "probe")))))
    (testing "an absent config value returns the canonical require data"
      (with-redefs [config/resolve-agent-context (fn [_id _override] {})]
        (is (= home/home-ns-require-specs
               (home/home-requires-for "probe")))))))
