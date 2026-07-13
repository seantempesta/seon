(ns seon.ui.header-test
  "Behavioral checks for the shared database-derived header."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render.system :as system]
    [seon.ui.header :as header]))

(deftest header-uses-the-index-count-without-building-an-inventory
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [count-calls (atom 0)
                  inventory-calls (atom 0)
                  fleet {::system/agents [{:seon.agent/id "root"}]
                         ::system/state-counts {:idle 1}
                         ::system/embedding? false}]
              (try
                (with-redefs [system/fleet-summary (fn [_] fleet)
                              derive/error-storms (fn [_] [])
                              db/datom-count
                              (fn
                                ([] (swap! count-calls inc) 42)
                                ([_] (swap! count-calls inc) 42))
                              db/store-inventory
                              (fn
                                ([]
                                 (swap! inventory-calls inc)
                                 (throw (js/Error. "inventory should not be built")))
                                ([_]
                                 (swap! inventory-calls inc)
                                 (throw (js/Error. "inventory should not be built"))))]
                  (let [view (header/system-header @conn)]
                    (testing "the complete header renders from bounded projections"
                      (is (= "system-header" (:id (second view))) (pr-str view))
                      (is (= 1 (:data-agent-count (second view))) (pr-str view)))
                    (testing "the database metric uses only the maintained index count"
                      (is (= 1 @count-calls))
                      (is (zero? @inventory-calls)))))
                (finally
                  (d/release conn))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))
