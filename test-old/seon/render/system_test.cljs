(ns seon.render.system-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [seon.render.system :as system]
   [seon.ui.html :as html]))

(deftest fleet-summary-is-pure-over-authority-rows
  (let [rows [[{:seon.agent/id "worker"
                :seon.agent/purpose "Measure throughput"
                :seon.agent/run {:seon.agent.run/status :open}}]
              [{:seon.agent/id "root"}]
              [{:seon.agent/id "done"
                :seon.agent/terminated-at (js/Date.)}]]
        agents (system/fleet-summary rows)]
    (testing "root sorts first and state is derived without a database value"
      (is (= ["root" "done" "worker"] (mapv :seon.agent/id agents)))
      (is (= [:idle :terminated :running]
             (mapv :seon.render.system/state agents))))
    (testing "ordinary fields survive the projection"
      (is (= "Measure throughput"
             (:seon.agent/purpose (last agents)))))))

(deftest human-system-view-is-serializable-hiccup
  (let [agents [{:seon.agent/id "root"
                 :seon.render.system/state :idle}
                {:seon.agent/id "worker"
                 :seon.render.system/state :running}]
        markup (html/->string (@#'system/human-view agents))]
    (is (string? markup))
    (is (re-find #">2 agents<" markup))
    (is (re-find #"/agent/worker" markup))))
