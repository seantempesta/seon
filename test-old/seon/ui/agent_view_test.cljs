(ns seon.ui.agent-view-test
  (:require [cljs.test :refer [deftest is]]
            [seon.render.surface :as surface]
            [seon.ui.agent-view :as view]
            [seon.ui.header :as header]
            [seon.ui.html :as html]))

(deftest ordinary-projection-renders-one-complete-agent-view
  (let [surface (surface/materialized
                 {:seon.agent.ctx/name :plan :seon.render/html [:div]}
                 [:article
                  [:section {:class "seon-card-compact"} "small"]
                  [:section {:class "seon-card-expanded"} "large"]])
        markup (html/->string
                (view/render-agent-view
                 {:seon.agent/id "agent-1"
                  ::view/state :running
                  ::surface/surfaces [surface]
                  ::header/projection header/default-projection}))]
    (is (re-find #"id=\"app-view\"" markup))
    (is (re-find #"small" markup))
    (is (re-find #"large" markup))
    (is (re-find #"data-agent-state=\"running\"" markup))))
