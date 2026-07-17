(ns seon.derive-test
  "Focused proof for the pure state rule and asynchronous database facade."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.db :as db]
    [seon.derive :as derive]))

(deftest state-from-primitives-projects-the-rule
  (is (= :idle       (derive/state-from-primitives {})) "no open run ⇒ idle")
  (is (= :idle       (derive/state-from-primitives {:seon.agent.run/open? false})))
  (is (= :running    (derive/state-from-primitives {:seon.agent.run/open? true})))
  (is (= :paused     (derive/state-from-primitives {:seon.agent.run/open? true
                                                    :seon.agent.run/paused-at (js/Date.)})))
  (is (= :terminated (derive/state-from-primitives {:seon.agent/terminated-at (js/Date.)})))
  (is (= :terminated (derive/state-from-primitives {:seon.agent/terminated-at (js/Date.)
                                                    :seon.agent.run/open? true
                                                    :seon.agent.run/paused-at (js/Date.)}))
      "terminated dominates every other primitive"))

(deftest derive-state-reads-one-explicit-database-value
  (async done
    (let [database ::database
          requests (atom [])
          original-pull db/pull]
      (set! db/pull
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                {:seon.agent/run
                 {:seon.agent.run/status :open
                  :seon.agent.run/paused-at (js/Date. 1000)}}))
              ([_ _] (js/Promise.resolve nil))
              ([_ _ _] (js/Promise.resolve nil))))
      (-> (derive/derive-state database "agent-a")
          (.then
           (fn [state]
             (is (= :paused state))
             (is (= 1 (count @requests)))
             (is (identical? database (:seon.db/db (first @requests))))
             (is (= [:seon.agent/id "agent-a"]
                    (:seon.db/eid (first @requests))))))
          (.catch (fn [exception]
                    (is false (str "derive-state threw: " exception))))
          (.finally (fn [] (set! db/pull original-pull) (done)))))))

(deftest armable-agent-ids-filters-with-the-one-state-rule
  (async done
    (let [database ::database
          original-query db/query]
      (set! db/query
            (fn
              ([request]
               (is (identical? database (:seon.db/db request)))
               (js/Promise.resolve
                [["running" {:seon.agent/run {:seon.agent.run/status :open}}]
                 ["terminated" {:seon.agent/terminated-at (js/Date. 1000)}]
                 ["idle" {}]]))
              ([_ _ & _]
               (js/Promise.resolve []))))
      (-> (derive/armable-agent-ids database)
          (.then #(is (= ["idle"] %)))
          (.catch (fn [exception]
                    (is false (str "armable-agent-ids threw: " exception))))
          (.finally (fn [] (set! db/query original-query) (done)))))))
