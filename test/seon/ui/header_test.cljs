(ns seon.ui.header-test
  "Behavioral checks for the shared database-derived header."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.render.system :as system]
    [seon.ui.header :as header]))

(deftest header-uses-the-maintained-index-count
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [count-calls (atom 0)
                  fleet {::system/agents [{:seon.agent/id "root"}]
                         ::system/state-counts {:idle 1}
                         ::system/embedding? false}]
              (try
                (with-redefs [system/fleet-summary (fn [_] fleet)
                              seval/error-storms (fn [_] [])
                              db/datom-count
                              (fn
                                ([] (swap! count-calls inc) 42)
                                ([_] (swap! count-calls inc) 42))]
                  (let [view (header/system-header @conn)]
                    (testing "the complete header renders from bounded projections"
                      (is (= "system-header" (:id (second view))) (pr-str view))
                      (is (= 1 (:data-agent-count (second view))) (pr-str view)))
                    (testing "the database metric uses the maintained index count"
                      (is (= 1 @count-calls)))))
                (finally
                  (d/release conn))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest eval-storm-health-signal-is-bounded-and-self-healing
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [agent-id "root"
                  eval-row
                  (fn [millis ok? source]
                    {:seon.eval/agent [:seon.agent/id agent-id]
                     :seon.eval/at (js/Date. millis)
                     :seon.eval/ok? ok?
                     :seon.eval/source source})]
              (-> (db/transact!
                    conn
                    (into [(eval-row 1 false "}")]
                          (map #(eval-row % false "(broken)") (range 2 6))))
                  (.then
                    (fn [_]
                      (let [storm (seval/error-storm @conn agent-id)]
                        (is (= 4 (::seval/error-storm-failed storm)))
                        (is (= 4 (::seval/error-storm-window storm)))
                        (is (= 4 (::seval/error-storm-consecutive storm))
                            "content-free segmentation noise is excluded"))))
                  (.then
                    (fn [_]
                      (db/transact!
                        conn
                        (mapv #(eval-row % true "(+ 1 1)") (range 6 10)))))
                  (.then
                    (fn [_]
                      (is (nil? (seval/error-storm @conn agent-id))
                          "successes slide the bounded window back to healthy")))
                  (.finally (fn [] (d/release conn)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))
